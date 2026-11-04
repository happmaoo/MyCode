package com.myapp.blecams.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import com.myapp.blecams.protocol.BleCamUuids;
import com.myapp.blecams.protocol.StreamProtocol;

/**
 * BLE Peripheral（GATT Server）：广播 + 连接管理 + notify 发送 + 控制命令接收。
 *
 * <p>关于 MTU：Android 的 GATT <b>服务端</b>在 API 33 之前拿不到协商后的 MTU，因此本协议
 * 要求客户端在使能 CCCD 之后通过控制通道 {@link StreamProtocol#CMD_CLIENT_MTU} 上报自己的
 * ATT 载荷上限。没有收到上报时，服务端按最保守的 MTU=23（每包 20 字节）工作。
 */
public class BleCamServer {

    private static final String TAG = "BleCamServer";

    public interface Callback {
        void onAdvertisingStarted();

        void onAdvertisingFailed(int errorCode, String message);

        void onClientConnected(BluetoothDevice device);

        void onClientDisconnected();

        /** 客户端写入 CCCD，开始/停止接收 notify。 */
        void onSubscriptionChanged(boolean enabled);

        /** 控制通道收到的命令，{@code value[0]} 是命令 id。 */
        void onControlCommand(byte commandId, byte[] value);

        /** 约每 1 秒一次的链路统计回调。 */
        void onLinkStats(LinkStats stats);

        void onError(String message);
    }

    public interface StatusProvider {
        byte[] statusBytes();
    }

    public static final class LinkStats {
        public int bytesPerSecond;
        public int mtu;
        public int chunkPayload;
        public long notifyOk;
        public long notifyFail;
        public boolean clientPresent;

        public LinkStats copy() {
            LinkStats s = new LinkStats();
            s.bytesPerSecond = bytesPerSecond;
            s.mtu = mtu;
            s.chunkPayload = chunkPayload;
            s.notifyOk = notifyOk;
            s.notifyFail = notifyFail;
            s.clientPresent = clientPresent;
            return s;
        }
    }

    private final Context context;
    private final Callback callback;

    private BluetoothAdapter adapter;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;

    private BluetoothGattService gattService;
    private BluetoothGattCharacteristic streamChar;
    private BluetoothGattCharacteristic controlChar;
    private BluetoothGattCharacteristic statusChar;

    private volatile BluetoothDevice client;
    private volatile boolean notifyEnabled;
    private volatile int mtu = StreamProtocol.FALLBACK_MTU;
    private volatile int chunkPayload = StreamProtocol.chunkPayloadForMtu(StreamProtocol.FALLBACK_MTU);
    private volatile boolean advertising;
    private volatile boolean stopped;

    private StatusProvider statusProvider;

    // ---- 吞吐统计 ----
    private final LinkStats stats = new LinkStats();
    private long windowBytes;
    private long windowStart = now();

    public BleCamServer(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public void setStatusProvider(StatusProvider provider) {
        this.statusProvider = provider;
    }

    public boolean start() {
        stopped = false;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null) {
            callback.onError("BluetoothManager 不可用");
            return false;
        }
        adapter = manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            callback.onError("蓝牙未开启");
            return false;
        }
        if (!adapter.isMultipleAdvertisementSupported()) {
            // 多数手机都支持；个别机型（仅 peripheral 模式）会失败，继续尝试并给出错误回调
            Log.w(TAG, "isMultipleAdvertisementSupported=false, try anyway");
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            callback.onError("该设备不支持 BLE 外围模式(Peripheral)");
            return false;
        }
        try {
            server = manager.openGattServer(context, gattCallback);
        } catch (SecurityException e) {
            callback.onError("缺少蓝牙权限: " + e.getMessage());
            return false;
        }
        if (server == null) {
            callback.onError("openGattServer 失败");
            return false;
        }
        buildService();
        return true;
    }

    private void buildService() {
        gattService = new BluetoothGattService(BleCamUuids.SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        streamChar = new BluetoothGattCharacteristic(BleCamUuids.CHAR_VIDEO_STREAM,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        // 显式添加 CCCD，确保客户端可以订阅
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(BleCamUuids.DESC_CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        streamChar.addDescriptor(cccd);

        controlChar = new BluetoothGattCharacteristic(BleCamUuids.CHAR_CONTROL,
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        statusChar = new BluetoothGattCharacteristic(BleCamUuids.CHAR_STATUS,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);

        gattService.addCharacteristic(streamChar);
        gattService.addCharacteristic(controlChar);
        gattService.addCharacteristic(statusChar);

        try {
            server.addService(gattService);
        } catch (SecurityException e) {
            Log.e(TAG, "addService failed", e);
        }
    }

    public void startAdvertising() {
        if (advertiser == null || advertising) {
            return;
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY) // 7.5ms 连接间隔
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();

        // adv 包 31 字节：128bit UUID(16B) + 头部，设备名放到 scan response
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(BleCamUuids.SERVICE))
                .build();
        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();

        try {
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback);
        } catch (SecurityException e) {
            callback.onError("缺少 BLUETOOTH_ADVERTISE 权限: " + e.getMessage());
        } catch (Exception e) {
            callback.onError("startAdvertising 异常: " + e);
        }
    }

    public void stopAdvertising() {
        if (advertiser != null && advertising) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (Exception e) {
                Log.w(TAG, "stopAdvertising failed", e);
            }
        }
        advertising = false;
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
            Log.i(TAG, "advertising started");
            callback.onAdvertisingStarted();
        }

        @Override
        public void onStartFailure(int errorCode) {
            advertising = false;
            String msg;
            switch (errorCode) {
                case ADVERTISE_FAILED_DATA_TOO_LARGE:
                    msg = "广播数据超过 31 字节";
                    break;
                case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                    msg = "广播实例已满（请关闭其他 BLE 广播）";
                    break;
                case ADVERTISE_FAILED_ALREADY_STARTED:
                    msg = "广播已在进行";
                    break;
                case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                    msg = "设备不支持 BLE 外围模式";
                    break;
                case ADVERTISE_FAILED_INTERNAL_ERROR:
                    msg = "蓝牙协议栈内部错误";
                    break;
                default:
                    msg = "未知错误 " + errorCode;
            }
            Log.e(TAG, "advertising failed: " + msg);
            callback.onAdvertisingFailed(errorCode, msg);
        }
    };

    public boolean isClientConnected() {
        return client != null && notifyEnabled;
    }

    public int getChunkPayload() {
        return chunkPayload;
    }

    public int getMtu() {
        return mtu;
    }

    /** 收到客户端上报的 MTU。 */
    public void setClientMtu(int reported) {
        // 服务端拿不到协商 MTU（Android 的 GATT Server 没有公开该回调），
        // 因此以上报值为准，只做合法性钳制。
        int v = Math.max(StreamProtocol.MTU_MIN, Math.min(reported, StreamProtocol.MTU_MAX));
        if (v == mtu) {
            return;
        }
        mtu = v;
        chunkPayload = StreamProtocol.chunkPayloadForMtu(v);
        Log.i(TAG, "client mtu=" + v + " -> chunk payload " + chunkPayload + "B");
    }

    /**
     * 发送一个 notify 分包。必须在单一线程调用（Android 的 notifyCharacteristicChanged
     * 内部会排队，但没有发送完成回调，靠调用线程自己做节奏控制）。
     *
     * @return true 表示协议栈已接受该包
     */
    public boolean sendNotification(byte[] packet) {
        BluetoothDevice device = client;
        if (device == null || !notifyEnabled || server == null) {
            return false;
        }
        try {
            streamChar.setValue(packet);
            boolean ok = server.notifyCharacteristicChanged(device, streamChar, false);
            if (ok) {
                stats.notifyOk++;
                windowBytes += packet.length;
            } else {
                stats.notifyFail++;
            }
            tickStats();
            return ok;
        } catch (SecurityException e) {
            callback.onError("缺少 BLUETOOTH_CONNECT 权限: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.w(TAG, "notify failed", e);
            stats.notifyFail++;
            return false;
        }
    }

    public void stop() {
        stopped = true;
        stopAdvertising();
        client = null;
        notifyEnabled = false;
        if (server != null) {
            try {
                server.clearServices();
                server.close();
            } catch (Exception e) {
                Log.w(TAG, "close server failed", e);
            }
            server = null;
        }
    }

    private void tickStats() {
        long now = now();
        long dt = now - windowStart;
        if (dt >= 1000) {
            stats.bytesPerSecond = (int) (windowBytes * 1000L / dt);
            stats.mtu = mtu;
            stats.chunkPayload = chunkPayload;
            stats.clientPresent = client != null;
            windowBytes = 0;
            windowStart = now;
            callback.onLinkStats(stats.copy());
        }
    }

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "client connected " + device.getAddress() + " status=" + status);
                client = device;
                notifyEnabled = false;
                // 连接后 Android 会自动停止广播；断开时再恢复
                callback.onClientConnected(device);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "client disconnected " + device.getAddress() + " status=" + status);
                if (client != null && client.equals(device)) {
                    client = null;
                    notifyEnabled = false;
                    callback.onClientDisconnected();
                    if (!stopped) {
                        stopAdvertising();
                        startAdvertising();
                    }
                }
            }
        }

        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            Log.i(TAG, "service added status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                startAdvertising();
            } else {
                callback.onError("添加 GATT 服务失败 status=" + status);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            if (BleCamUuids.CHAR_STATUS.equals(characteristic.getUuid()) && statusProvider != null) {
                byte[] all = statusProvider.statusBytes();
                if (all == null) {
                    all = new byte[0];
                }
                int len = Math.max(0, Math.min(all.length - offset, mtu - 1));
                byte[] slice = new byte[len];
                System.arraycopy(all, offset, slice, 0, len);
                safeResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice);
            } else {
                safeResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            if (BleCamUuids.CHAR_CONTROL.equals(characteristic.getUuid()) && value != null
                    && value.length > 0) {
                handleControl(value);
            }
            if (responseNeeded) {
                safeResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattDescriptor descriptor) {
            if (BleCamUuids.DESC_CCCD.equals(descriptor.getUuid())) {
                byte[] v = notifyEnabled
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                safeResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v);
            } else {
                safeResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, new byte[0]);
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            if (BleCamUuids.DESC_CCCD.equals(descriptor.getUuid())
                    && BleCamUuids.CHAR_VIDEO_STREAM.equals(descriptor.getCharacteristic().getUuid())) {
                notifyEnabled = value != null && value.length >= 1
                        && (value[0] & 0x01) != 0;
                Log.i(TAG, "CCCD -> notifyEnabled=" + notifyEnabled);
                callback.onSubscriptionChanged(notifyEnabled);
            }
            if (responseNeeded) {
                safeResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
            tickStats();
        }

        @Override
        public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
            safeResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }
    };

    private void handleControl(byte[] value) {
        byte cmd = value[0];
        if (cmd == StreamProtocol.CMD_CLIENT_MTU && value.length >= 3) {
            setClientMtu(StreamProtocol.u16(value, 1));
        }
        callback.onControlCommand(cmd, value);
    }

    private void safeResponse(BluetoothDevice device, int requestId, int status, int offset, byte[] value) {
        if (server == null) {
            return;
        }
        try {
            server.sendResponse(device, requestId, status, offset, value);
        } catch (SecurityException e) {
            Log.w(TAG, "sendResponse denied", e);
        } catch (Exception e) {
            Log.w(TAG, "sendResponse failed", e);
        }
    }

    /** 每包发送后由推流线程调用，驱动 1 秒窗口统计。 */
    public void pumpStats() {
        tickStats();
    }

    private static long now() {
        return android.os.SystemClock.elapsedRealtime();
    }
}

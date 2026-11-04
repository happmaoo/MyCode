package com.myapp.blecams.receiver.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import com.myapp.blecams.protocol.BleCamUuids;
import com.myapp.blecams.protocol.StreamProtocol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * BLE Central：扫描 BleCam 服务、连接、协商 MTU、订阅 CCCD、上报 MTU，然后把收到的
 * notify 分包交给 {@link com.myapp.blecams.protocol.AuReassembler}。
 *
 * <p>GATT 是串行协议，所有写操作必须排队（一次只有一个 in-flight），否则会返回
 * {@code GATT_BUSY} / 直接丢失。这里用一个小的操作队列 + 超时看门狗处理。
 */
public class BleStreamClient {

    private static final String TAG = "BleStreamClient";
    /**
     * 参考 ESPAUTO（ESP32 侧 setMTU(512)）：能要多大就要多大，协商结果由协议栈给。
     * 分包大小以回调回来的实际 MTU 为准，并上报给服务端。
     */
    private static final int REQUEST_MTU = 517;
    private static final long OP_TIMEOUT_MS = 2500;
    private static final long RECONNECT_DELAY_MS = 1500;

    public interface Listener {
        void onLog(String message);

        void onConnectionState(boolean connected);

        /** 收到一个 notify 分包（原始字节）。 */
        void onChunk(byte[] packet);

        /** 读到的服务端 STATUS 文本。 */
        void onServerStatus(String text);

        void onStats(Stats stats);
    }

    public static final class Stats {
        public int mtu;
        public int phy;
        public int chunkPayload;
        public int bytesPerSecond;
        public int packetsPerSecond;
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic streamChar;
    private BluetoothGattCharacteristic controlChar;
    private BluetoothGattCharacteristic statusChar;

    private volatile boolean connected;
    private volatile boolean shouldConnect;
    private volatile int mtu = StreamProtocol.FALLBACK_MTU;
    private volatile int phy = -1;

    private final Deque<Runnable> ops = new ArrayDeque<>();
    private boolean opInFlight;
    private long opSeq;

    private volatile long windowBytes;
    private volatile long windowPackets;

    public BleStreamClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) this.context
                .getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    public boolean isAvailable() {
        return adapter != null && adapter.isEnabled();
    }

    public boolean isConnected() {
        return connected;
    }

    public int getMtu() {
        return mtu;
    }

    public String getPhyName() {
        return phy < 0 ? "-" : phyName(phy);
    }

    public void connect() {
        if (adapter == null || !adapter.isEnabled()) {
            listener.onLog("蓝牙未开启");
            return;
        }
        shouldConnect = true;
        startScan();
        main.postDelayed(statsTicker, 1000);
    }

    public void disconnect() {
        shouldConnect = false;
        main.removeCallbacks(statsTicker);
        stopScan();
        closeGatt();
        connected = false;
        listener.onConnectionState(false);
    }

    // ------------------------------------------------------------------ 扫描

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) {
                return;
            }
            stopScan();
            listener.onLog("发现摄像头 " + device.getAddress() + "，开始连接");
            connectGatt(device);
        }

        @Override
        public void onScanFailed(int errorCode) {
            listener.onLog("扫描失败 errorCode=" + errorCode);
        }
    };

    private void startScan() {
        scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onLog("无法获取 BluetoothLeScanner");
            return;
        }
        List<ScanFilter> filters = new ArrayList<>();
        // 服务端广播的是“完整 128bit Service UUID 列表”，所以按 serviceUuid 过滤
        filters.add(new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(BleCamUuids.SERVICE))
                .build());
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();
        try {
            scanner.startScan(filters, settings, scanCallback);
            listener.onLog("正在扫描 BleCam…");
        } catch (SecurityException | IllegalStateException e) {
            listener.onLog("扫描异常：" + e.getMessage());
        }
    }

    private void stopScan() {
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
    }

    private void scheduleReconnect() {
        main.postDelayed(() -> {
            if (shouldConnect && !connected) {
                listener.onLog("重连中…");
                startScan();
            }
        }, RECONNECT_DELAY_MS);
    }

    // ------------------------------------------------------------------ 连接

    @SuppressLint("MissingPermission")
    private void connectGatt(BluetoothDevice device) {
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            listener.onLog("缺少蓝牙权限：" + e.getMessage());
        }
    }

    private void closeGatt() {
        BluetoothGatt g = gatt;
        gatt = null;
        streamChar = null;
        controlChar = null;
        statusChar = null;
        if (g != null) {
            try {
                g.close();
            } catch (Exception ignored) {
            }
        }
        synchronized (ops) {
            ops.clear();
            opInFlight = false;
        }
    }

    /**
     * 主动请求 2M PHY（参考 ESPAUTO 的 Android 端也这么做）。
     *
     * <p>注意两点：一、BLE 的 PHY 是<b>链路两端的最小值</b>，只要有一边不支持 2M 就谈不下来；
     * 二、PHY 更新通常只有 <b>central</b> 能发起，服务端（Android peripheral）没有公开 API，
     * 所以这一请求必须由本客户端发出。谈成谈不成都会把结果打进日志，不要靠猜。
     */
    private void request2mPhy(BluetoothGatt g) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED);
            g.readPhy();
        } catch (Exception e) {
            Log.w(TAG, "setPreferredPhy failed", e);
        }
    }

    private static String phyName(int phy) {
        if (phy == BluetoothDevice.PHY_LE_1M) {
            return "1M";
        }
        if (phy == BluetoothDevice.PHY_LE_2M) {
            return "2M";
        }
        if (phy == BluetoothDevice.PHY_LE_CODED) {
            return "Coded";
        }
        return "?" + phy;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onPhyUpdate(BluetoothGatt g, int txPhy, int rxPhy, int status) {
            phy = txPhy;
            listener.onLog("PHY 更新：" + phyName(txPhy) + "/" + phyName(rxPhy)
                    + " status=" + status);
        }

        @Override
        public void onPhyRead(BluetoothGatt g, int txPhy, int rxPhy, int status) {
            phy = txPhy;
            listener.onLog("当前 PHY：" + phyName(txPhy) + "/" + phyName(rxPhy)
                    + "（2M 需要两端都支持）");
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                listener.onConnectionState(true);
                listener.onLog("已连接，发现服务");
                try {
                    // 7.5ms 连接间隔，吞吐最大化
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                } catch (Exception ignored) {
                }
                request2mPhy(g);
                enqueue(() -> g.discoverServices());
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                listener.onConnectionState(false);
                listener.onLog("连接断开 status=" + status);
                closeGatt();
                if (shouldConnect) {
                    scheduleReconnect();
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            opDone();
            BluetoothGattService service = g.getService(BleCamUuids.SERVICE);
            if (service == null) {
                listener.onLog("没找到 BleCam 服务，请确认对端已启动推流");
                return;
            }
            streamChar = service.getCharacteristic(BleCamUuids.CHAR_VIDEO_STREAM);
            controlChar = service.getCharacteristic(BleCamUuids.CHAR_CONTROL);
            statusChar = service.getCharacteristic(BleCamUuids.CHAR_STATUS);
            if (streamChar == null) {
                listener.onLog("STREAM 特征不存在");
                return;
            }
            enqueue(() -> g.requestMtu(REQUEST_MTU));
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int newMtu, int status) {
            opDone();
            mtu = Math.max(StreamProtocol.MTU_MIN, newMtu);
            listener.onLog("MTU=" + mtu + "（分包上限 "
                    + StreamProtocol.chunkPayloadForMtu(mtu) + " 字节）");
            if (streamChar == null) {
                return;
            }
            BluetoothGattDescriptor cccd = streamChar.getDescriptor(BleCamUuids.DESC_CCCD);
            if (cccd == null) {
                listener.onLog("CCCD 不存在，无法订阅");
                return;
            }
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            // setCharacteristicNotification 没有回调，必须在同一个 op 里立刻结队
            enqueue(() -> {
                g.setCharacteristicNotification(streamChar, true);
                opDone();
            });
            enqueue(() -> {
                if (!g.writeDescriptor(cccd)) {
                    opDone();
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            opDone();
            if (BleCamUuids.DESC_CCCD.equals(descriptor.getUuid())) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    listener.onLog("已订阅 STREAM，上报本机 MTU");
                    // 关键：服务端在 API33 之前拿不到协商 MTU，必须由客户端上报
                    reportMtu();
                } else {
                    listener.onLog("订阅失败 status=" + status);
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic,
                                          int status) {
            opDone();
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onLog("控制写入失败 status=" + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] value = c.getValue();
            if (value == null) {
                return;
            }
            windowBytes += value.length;
            windowPackets++;
            listener.onChunk(value);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            opDone();
            if (status == BluetoothGatt.GATT_SUCCESS && c.getValue() != null) {
                listener.onServerStatus(new String(c.getValue()));
            }
        }
    };

    // ------------------------------------------------------------------ 操作队列

    private void enqueue(Runnable op) {
        synchronized (ops) {
            ops.add(op);
            if (!opInFlight) {
                pumpLocked();
            }
        }
    }

    private void pumpLocked() {
        Runnable op = ops.poll();
        if (op == null) {
            opInFlight = false;
            return;
        }
        opInFlight = true;
        final long generation = ++opSeq;
        // 看门狗：某些 op 没有回调（或协议栈卡住），超时后放行队列
        main.postDelayed(() -> {
            if (opSeq == generation && opInFlight) {
                Log.w(TAG, "gatt op timeout, skip");
                opDone();
            }
        }, OP_TIMEOUT_MS);
        try {
            op.run();
        } catch (Exception e) {
            Log.w(TAG, "gatt op failed", e);
            opDone();
        }
    }

    private void opDone() {
        synchronized (ops) {
            if (!opInFlight) {
                return;
            }
            opSeq++;
            pumpLocked();
        }
    }

    private void writeControl(byte[] payload) {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic c = controlChar;
        if (g == null || c == null || payload == null) {
            return;
        }
        enqueue(() -> {
            c.setValue(payload);
            if (!g.writeCharacteristic(c)) {
                opDone();
            }
        });
    }

    /** 把本机协商到的 MTU 告诉服务端。 */
    public void reportMtu() {
        writeControl(StreamProtocol.cmdU16(StreamProtocol.CMD_CLIENT_MTU, mtu));
    }

    public void requestIdr() {
        writeControl(StreamProtocol.cmd(StreamProtocol.CMD_REQUEST_IDR));
    }

    public void requestStart() {
        writeControl(StreamProtocol.cmd(StreamProtocol.CMD_START));
    }

    public void requestStop() {
        writeControl(StreamProtocol.cmd(StreamProtocol.CMD_STOP));
    }

    public void setProfile(int profileId) {
        writeControl(StreamProtocol.cmd(StreamProtocol.CMD_SET_PROFILE, profileId));
    }

    public void setBitrate(int bps) {
        writeControl(StreamProtocol.cmdU32(StreamProtocol.CMD_SET_BITRATE, bps));
    }

    public void setFps(int fps) {
        writeControl(StreamProtocol.cmd(StreamProtocol.CMD_SET_FPS, fps));
    }

    public void readStatus() {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic c = statusChar;
        if (g == null || c == null) {
            return;
        }
        enqueue(() -> {
            if (!g.readCharacteristic(c)) {
                opDone();
            }
        });
    }

    public void sendClientThroughput(int bytesPerSecond) {
        writeControl(StreamProtocol.cmdU32(StreamProtocol.CMD_CLIENT_STATS, bytesPerSecond));
    }

    // ------------------------------------------------------------------ 统计

    private final Runnable statsTicker = new Runnable() {
        @Override
        public void run() {
            if (!shouldConnect) {
                return;
            }
            Stats s = new Stats();
            s.bytesPerSecond = (int) windowBytes;
            s.packetsPerSecond = (int) windowPackets;
            s.mtu = mtu;
            s.phy = phy;
            s.chunkPayload = StreamProtocol.chunkPayloadForMtu(mtu);
            windowBytes = 0;
            windowPackets = 0;
            listener.onStats(s);

            if (connected) {
                // 每 2 秒上报一次实测吞吐，服务端可用于更精确的自适应
                sendClientThroughput(s.bytesPerSecond);
            }
            main.postDelayed(this, 1000);
        }
    };
}

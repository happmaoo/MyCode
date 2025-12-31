package com.myapp.mybleclient;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

// bt -pan
import android.bluetooth.BluetoothProfile;

import java.lang.reflect.Method;



import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.data.Data;

public class GattService extends Service {

    public static final String DATA_PLANE_ACTION = "data-plane";
    // 广播相关常量
    public static final String ACTION_DATA_RECEIVED = "com.myapp.mybleclient.ACTION_DATA_RECEIVED";
    public static final String EXTRA_DATA_VALUE = "data_value";
    public static final String EXTRA_DEVICE_ADDRESS = "device_address";

    private static final String TAG = "gatt-service";

    private BroadcastReceiver bluetoothObserver;
    private final Map<String, ClientManager> clientManagers = new HashMap<>();

    private BluetoothProfile panProxy; // PAN 代理对象
    private boolean panConnecting = false;
    private int panRetryCount = 0;
    private static final int MAX_PAN_RETRY = 5;

    private boolean isDestroying = false;

    MyApp app;
    private static String allLog = "";
    public static void clearlog() {
        allLog = "";
    }

    @Override
    public void onCreate() {
        super.onCreate();


        // 如果蓝牙没有打开则打开
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (!adapter.isEnabled()) { adapter.enable(); }


        setupForegroundService();
        setupBluetoothObserver();

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null && bluetoothManager.getAdapter() != null && bluetoothManager.getAdapter().isEnabled()) {
            enableBleServices();
        }


        // 初始化 PAN 代理
        if (adapter != null) {
            // PAN 的 Profile ID 是 5
            adapter.getProfileProxy(this, panListener, 5);
        }

        // SharedPreferences
        app = (MyApp) getApplicationContext();
    }



    private void setupForegroundService() {
        String channelId = GattService.class.getSimpleName();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "Gatt Service",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("BLE Client Service")
                .setContentText("Running...")
                .build();

        startForeground(1, notification);
    }

    private void setupBluetoothObserver() {
        bluetoothObserver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                    if (state == BluetoothAdapter.STATE_ON) enableBleServices();
                    else if (state == BluetoothAdapter.STATE_OFF) disableBleServices();
                } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) {
                        if (device.getBondState() == BluetoothDevice.BOND_BONDED) addDevice(device);
                        else if (device.getBondState() == BluetoothDevice.BOND_NONE) removeDevice(device);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(bluetoothObserver, filter);
    }

    @Override
    public void onDestroy() {
        isDestroying = true; // 设置标志位
        stopForeground(true);
        super.onDestroy();
        disconnectPan();
        unregisterReceiver(bluetoothObserver);
        disableBleServices();

        // pan
        if (panProxy != null) {
            BluetoothAdapter.getDefaultAdapter().closeProfileProxy(5, panProxy);
        }
        app.remove("log");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        if (intent != null && DATA_PLANE_ACTION.equals(intent.getAction())) {
            return new DataPlane();
        }
        return null;
    }

    /**
     * 供 Activity/Fragment 调用的 Binder
     */
    public class DataPlane extends Binder {
        // 发送数据到服务端的方法
        // 也可以在这里写 activity传来的命令
        public void send(String message) {

            sendMessageToAll(message);

            if ("enable_pan".equals(message)) {

                if (!clientManagers.isEmpty()) {
                    String firstAddress = clientManagers.keySet().iterator().next();
                    BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(firstAddress);
                    Log.d(TAG, "DataPlane: Triggering PAN connection for " + firstAddress);
                    connectPan(device);
                } else {
                    Log.e(TAG, "No device connected to perform PAN connection");
                }
            }
            if("disable_pan".equals(message)){
                disconnectPan();
            }
            if("enable_wifi".equals(message)){
                //打开服务端热点的同时还要打开本机 wifi
                setWifiEnabled(true);
            }
            if("disable_wifi".equals(message)){
                setWifiEnabled(false);
            }
        }


    }

    private void enableBleServices() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null && bm.getAdapter() != null) {
            for (BluetoothDevice device : bm.getAdapter().getBondedDevices()) {
                addDevice(device);
            }
        }
    }

    private void disableBleServices() {
        for (ClientManager manager : clientManagers.values()) {
            manager.close();
        }
        clientManagers.clear();
    }

    private void addDevice(BluetoothDevice device) {

        if (!clientManagers.containsKey(device.getAddress())) {
            ClientManager manager = new ClientManager(this, device.getAddress());

            manager.connect(device)
                    .retry(10, 2000)      // 如果连接失败，重试10次
                    .useAutoConnect(false) // 保持 false，但配合 retry 使用
                    .timeout(10000)        // 10秒超时
                    .enqueue();

            clientManagers.put(device.getAddress(), manager);
        }
    }

    private void removeDevice(BluetoothDevice device) {
        ClientManager manager = clientManagers.remove(device.getAddress());
        if (manager != null) manager.close();
    }

    /**
     * 向所有已连接的设备发送字符串数据
     */
    public void sendMessageToAll(String message) {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        for (ClientManager manager : clientManagers.values()) {
            manager.sendData(data);
        }
    }


    /**
     * 数据接收, 发送广播通知
     */
    private void broadcastData(String value) {


        // 如果消息是 notif 则弹出通知
        if(value.startsWith("notif:")){
            notif(value);
        }
        if(value.startsWith("sig:")){
            int sigInt = Integer.parseInt(value.substring(4));
            app.setInt("sig", sigInt);
        }


        String tm = getCurrentTime();
        value = tm + ":" + value + "\n";
        allLog = allLog + value;

        Intent intent = new Intent(ACTION_DATA_RECEIVED);
        intent.putExtra(EXTRA_DATA_VALUE, value);

        // 使用 LocalBroadcastManager 发送本地广播（更安全）
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        app.setString("log", allLog);

        // 或者使用系统广播（如果需要跨进程通信）
        // sendBroadcast(intent);

        Log.d(TAG, "Broadcast sent: " + value);
    }

    // --- BLE Manager 内部实现 ---

    private class ClientManager extends BleManager {

        private BluetoothGattCharacteristic myChar;
        private BluetoothGattCharacteristic batteryChar;
        private final String deviceAddress;

        public ClientManager(@NonNull Context context, String address) {
            super(context);
            this.deviceAddress = address;
        }

        // 默认打印日志。不用调用
        @Override
        public void log(int priority, @NonNull String message) {
            android.util.Log.println(priority, "GATT_Client", message);
        }

        // 辅助方法：确保任何时候都能拿到真实的设备对象
        private BluetoothDevice getRemoteDevice() {
            return BluetoothAdapter.getDefaultAdapter().getRemoteDevice(deviceAddress);
        }

        public void sendData(byte[] data) {
            if (myChar != null && (myChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                writeCharacteristic(myChar, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        .with((device, result) -> Log.d(TAG, "Data sent to " + device.getAddress()))
                        .enqueue();
            } else {
                Log.e(TAG, "Characteristic does not support writing or is null");
            }
        }

        @NonNull
        @Override
        protected BleManagerGattCallback getGattCallback() { return new GattCallback(); }

        private class GattCallback extends BleManagerGattCallback {

            @Override
            protected boolean isRequiredServiceSupported(@NonNull BluetoothGatt gatt) {

                // 先列出所有服务（用于调试）
                logAllServices(gatt);


                BluetoothGattService service =
                        gatt.getService(UUID.fromString("77770000-3537-1F0B-A53B-CF000ECEAAB3"));
                if (service != null) {
                    // 赋值给外部类的 myChar
                    myChar = service.getCharacteristic(UUID.fromString("77770001-3537-1F0B-A53B-CF000ECEAAB3"));
                }
                // 2. 同时检查电池服务
                BluetoothGattService batteryService =
                        gatt.getService(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"));
                if (batteryService != null) {
                    batteryChar = batteryService.getCharacteristic(
                            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"));

                    if (batteryChar != null) {
                        Log.d(TAG, "Found battery service, properties: 0x" +
                                Integer.toHexString(batteryChar.getProperties()));
                    }
                }

                return myChar != null; // 只要求主服务存在
            }






            @Override
            protected void initialize() {

                // 安卓最大 517, UTF-8 可发送 514 个英文 / 171 个中文
                // 大的 mtu 也会导致传输可靠性易受干扰降低 158 比较折中
                requestMtu(158)
                        .with((device, mtu) -> {
                            Log.d(TAG, "MTU 协商完成，当前 MTU: " + mtu);
                        })
                        .fail((device, status) -> {
                            Log.e(TAG, "MTU 请求失败，状态码: " + status);
                        })
                        .enqueue();

                // 数据通知
                setNotificationCallback(myChar).with((device, data) -> {
                    byte[] bytes = data.getValue();
                    if (bytes != null) {
                        String value = new String(bytes, StandardCharsets.UTF_8);
                        broadcastData(value);
                    }
                });
                enableNotifications(myChar).enqueue();

                // ⭐ 电池电量变化监听
                if (batteryChar != null &&
                        (batteryChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {

                    setNotificationCallback(batteryChar).with((device, data) -> {
                        Integer level = data.getIntValue(Data.FORMAT_UINT8, 0);
                        if (level != null) {
                            Log.d(TAG, "Battery updated: " + level + "%");
                            broadcastData("bat:" + level);
                            app.setInt("bat", level);
                        }
                    });

                    enableNotifications(batteryChar).enqueue();
                }
            }






            private void readBatteryLevel() {
                if (batteryChar != null) {
                    // 使用 Nordic BLE Library 提供的 readCharacteristic
                    readCharacteristic(batteryChar)
                            .with((device, data) -> {
                                if (data.getValue() != null && data.getValue().length > 0) {

                                    int batteryLevel = data.getIntValue(Data.FORMAT_UINT8, 0);
                                    Log.d(TAG, "Device: " + device.getAddress() + " Battery Level: " + batteryLevel + "%");

                                    broadcastData("bat:" + batteryLevel);
                                    app.setInt("bat", batteryLevel);
                                }
                            })
                            .enqueue();
                }
            }


            @Override
            protected void onDeviceReady() {
                super.onDeviceReady();
                broadcastData( "connected:[" + deviceAddress+"]");

                app.setBoolean("connected", true);
                app.setString("addr", deviceAddress);

                // 读取初始电池电量
                if (batteryChar != null) {
                    readBatteryLevel();
                }

                if (myChar != null) {
                    enableNotifications(myChar).enqueue();
                    Log.d(TAG, "重连成功：显式重新开启通知");
                }

                //如果pan是勾选的，首先打开服务端的 bt-tether
                boolean sp_autopan = app.getBoolean("autopan", false);
                if(sp_autopan){sendMessageToAll("enable_pan");}


                // 如果该设备支持 PAN，尝试连接
                BluetoothDevice device = getBluetoothDevice();
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    // 调用外部类的 connectPan 方法
                    if(sp_autopan){connectPan(device);}
                }
            }

            @Override
            protected void onDeviceDisconnected() {

                // 如果 Service 已经准备销毁，直接返回，不要 postDelayed
                if (isDestroying) {
                    Log.d(TAG, "Service is destroying, skipping reconnect.");
                    return;
                }

                super.onDeviceDisconnected();
                broadcastData( "disconnected:[" + deviceAddress+"]");
                myChar = null;

                app.setBoolean("connected", false);



                //检查蓝牙适配器状态 off 直接返回，不再进入 Handler 重连逻辑
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    Log.d(TAG, "Bluetooth is OFF, stopping retry for: " + deviceAddress);
                    stopForeground(true);
                    stopSelf();
                    return;
                }


                // 如果 Service 已经准备销毁，直接返回,不要重连了
                if (isDestroying) {
                    Log.d(TAG, "Service is destroying, skipping reconnect.");
                    return;
                }


                // 如果不是因为主动关闭 Service 导致的断开，就重新调用 connect
                // 配合 useAutoConnect(true)，它会进入静默等待状态，对方一开机就能连上
                // 延迟 1 秒后再发起重连，给 Android 蓝牙堆栈释放资源的时间

                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    Log.d(TAG, "Attempting to reconnect to: " + deviceAddress);

                    BluetoothDevice device = getRemoteDevice();
                    if (device != null) {
                        connect(device)
                                .useAutoConnect(true) // Android 8 上建议开启
                                .retry(999, 2000)
                                .enqueue();
                    }
                }, 2000); // 延迟 2 秒给系统释放旧句柄的时间
            }


            @Override
            protected void onServicesInvalidated() { myChar = null; batteryChar = null;}
        }






        // --------- 列出所有支持的服务和特性 ---------
        private void logAllServices(BluetoothGatt gatt) {
            if (gatt == null) return;

            Log.d(TAG, "=== Bluetooth GATT Services for " + deviceAddress + " ===");
            for (BluetoothGattService service : gatt.getServices()) {
                Log.d(TAG, "Service UUID: " + service.getUuid().toString());
            }
            Log.d(TAG, "==========================================");

        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        private String getPropertiesString(int properties) {
            List<String> props = new ArrayList<>();
            if ((properties & BluetoothGattCharacteristic.PROPERTY_READ) != 0) props.add("READ");
            if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) props.add("WRITE");
            if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) props.add("NOTIFY");
            if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) props.add("INDICATE");
            if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) props.add("WRITE_NO_RESPONSE");
            return String.join("|", props);
        }





    }












    // bt-pan
    private final BluetoothProfile.ServiceListener panListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == 5) { // 5 代表 PAN
                panProxy = proxy;
                Log.d(TAG, "PAN Service Connected");
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == 5) panProxy = null;
        }
    };




    /**
     * 连接 PAN 网络
     */
    public void connectPan(BluetoothDevice device) {
        if (panProxy == null || device == null) return;
        if (panConnecting) return;

        // 已经连上就不要再连
        if (isPanConnected(device)) {
            Log.d(TAG, "PAN already connected: " + device.getAddress());
            panRetryCount = 0;
            return;
        }
        panConnecting = true;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> {
                    try {
                        Method connectMethod =
                                panProxy.getClass().getDeclaredMethod("connect", BluetoothDevice.class);
                        connectMethod.setAccessible(true);

                        boolean result = (boolean) connectMethod.invoke(panProxy, device);
                        Log.d(TAG, "PAN connect attempt " + panRetryCount +
                                " result=" + result);

                    } catch (Exception e) {
                        Log.e(TAG, "PAN connect error: " + e.getMessage());
                    }
                    //  秒后检查是否连上
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(() -> {
                                panConnecting = false;

                                if (isPanConnected(device)) {
                                    Log.d(TAG, "PAN connected OK: " + device.getAddress());
                                    panRetryCount = 0;
                                } else {
                                    panRetryCount++;
                                    Log.w(TAG, "PAN not connected, retry=" + panRetryCount);

                                    if (panRetryCount < MAX_PAN_RETRY) {
                                        connectPan(device); // 重试
                                    } else {
                                        Log.e(TAG, "PAN connect failed after max retries");
                                    }
                                }
                            }, 1000);

                }, 1000); // ⭐ 延迟  秒再连
    }

    private boolean isPanConnected(BluetoothDevice device) {
        if (panProxy == null) return false;

        try {
            Method getConnectedDevices =
                    panProxy.getClass().getDeclaredMethod("getConnectedDevices");
            List<BluetoothDevice> list =
                    (List<BluetoothDevice>) getConnectedDevices.invoke(panProxy);

            return list != null && list.contains(device);
        } catch (Exception e) {
            return false;
        }
    }



    /**
     * 断开 PAN 网络
     */
    public void disconnectPan() {
        if (panProxy == null) return;

        try {
            // 1. 反射获取 getConnectedDevices 方法
            Method getConnectedDevicesMethod = panProxy.getClass().getDeclaredMethod("getConnectedDevices");
            List<BluetoothDevice> connectedDevices = (List<BluetoothDevice>) getConnectedDevicesMethod.invoke(panProxy);

            if (connectedDevices != null && !connectedDevices.isEmpty()) {
                // 2. 反射获取 disconnect 方法
                Method disconnectMethod = panProxy.getClass().getDeclaredMethod("disconnect", BluetoothDevice.class);

                for (BluetoothDevice device : connectedDevices) {
                    disconnectMethod.invoke(panProxy, device);
                    Log.d(TAG, "Disconnected PAN device: " + device.getAddress());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error disconnecting all PAN devices: " + e.getMessage());
        }
    }



    /**
     * 打开或关闭 WiFi
     *
     */
    public static void setWifiEnabled(boolean enable) {
        new Thread(() -> {
            try {
                // 延迟 2 秒 因为服务端需要时间开热点
                Thread.sleep(2000);
                Process process = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(process.getOutputStream());
                if (enable) {
                    os.writeBytes("svc wifi enable\n");
                } else {
                    os.writeBytes("svc wifi disable\n");
                }
                os.flush();
                os.writeBytes("exit\n");
                os.flush();
                process.waitFor();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }




    public String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date());
    }


    // 弹出通知方法
    private void notif(String msg) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 使用 IMPORTANCE_HIGH 或 IMPORTANCE_MAX 来弹出通知
            NotificationChannel channel = new NotificationChannel(
                    "notif",
                    "notif",
                    NotificationManager.IMPORTANCE_HIGH  // 这个级别会弹出通知
            );
            channel.setDescription("notif 通知");

            // 设置弹出效果
            channel.enableLights(true);
            channel.setLightColor(Color.BLUE);


            // 显示在锁屏上
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "notif")
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle("notif")
                    .setContentText(msg)
                    .setPriority(NotificationCompat.PRIORITY_HIGH) // 优先级设为 HIGH
                    .setAutoCancel(true) // 点击后自动消失
                    .setDefaults(NotificationCompat.DEFAULT_ALL); // 使用默认声音、震动等


            // 发送通知
            int notificationId = (int) System.currentTimeMillis(); // 使用时间戳作为唯一ID
            NotificationManagerCompat.from(this).notify(notificationId, builder.build());
        }
    }
}
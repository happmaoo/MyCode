package com.myapp.mybleserver;


import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.BleServerManager;
import no.nordicsemi.android.ble.observer.ServerObserver;

import android.os.BatteryManager;
import android.widget.Toast;

public class GattService extends Service {
    private static final String TAG = "gatt-service";
    private ServerManager serverManager;
    private BroadcastReceiver bluetoothObserver;
    private BleAdvertiser.Callback bleAdvertiseCallback;

    private BluetoothPanServer mBluetoothPanServer;



    private int lastBatteryPercent = -1; // 记录上次电量，避免频繁提示

    String received;

    MyApp app;
    private String allLog = "";

    int sig;


    public static class MyServiceProfile {
        // 服务1：标准电池服务 UUID
        public static final UUID BATTERY_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb");
        public static final UUID BATTERY_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb");

        // 服务2: UUID
        public static final UUID MY_SERVICE_UUID = UUID.fromString("77770000-3537-1F0B-A53B-CF000ECEAAB3");

        // 特征 1
        public static final UUID MY_CHARACTERISTIC_UUID1 = UUID.fromString("77770001-3537-1F0B-A53B-CF000ECEAAB3");
        // 特征 2
        public static final UUID MY_CHARACTERISTIC_UUID2 = UUID.fromString("77770002-3537-1F0B-A53B-CF000ECEAAB3");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        setupForeground();

        bluetoothObserver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                    if (state == BluetoothAdapter.STATE_ON) enableBleServices();
                    else if (state == BluetoothAdapter.STATE_OFF) disableBleServices();
                }
            }
        };
        registerReceiver(bluetoothObserver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled()) {
            enableBleServices();
        }




        // SharedPreferences
        app = (MyApp) getApplication();

    }

    private void setupForeground() {
        String channelId = getClass().getSimpleName();
        NotificationCompat.Builder builder;

        // 创建通知渠道（仅 Android 8.0+ 需要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "GATT Service",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
            builder = new NotificationCompat.Builder(this, channelId);
        } else {
            // Android 7 及以下版本
            builder = new NotificationCompat.Builder(this);
        }

        // 设置通知属性
        builder.setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("MyBleServer")
                .setContentText("running")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        // 启动前台服务
        startForeground(1, builder.build());
    }


    private void enableBleServices() {
        serverManager = new ServerManager(this, this); // 传递this
        serverManager.open();

        bleAdvertiseCallback = new BleAdvertiser.Callback();
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null && bm.getAdapter().getBluetoothLeAdvertiser() != null) {
            //自定义 ble 名称
            if (!bm.getAdapter().getName().equals("MyBleServer")) {
                bm.getAdapter().setName("MyBleServer");
            }
            bm.getAdapter().getBluetoothLeAdvertiser().startAdvertising(
                    BleAdvertiser.settings(),
                    BleAdvertiser.scanResponseData(),
                    BleAdvertiser.advertiseData(),
                    bleAdvertiseCallback
            );
        }
    }

    private void disableBleServices() {
        if (bleAdvertiseCallback != null) {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm != null && bm.getAdapter().getBluetoothLeAdvertiser() != null) {
                bm.getAdapter().getBluetoothLeAdvertiser().stopAdvertising(bleAdvertiseCallback);
            }
            bleAdvertiseCallback = null;
        }
        if (serverManager != null) {
            serverManager.close();
            serverManager = null;
        }
    }

    @Override
    public void onDestroy() {
        stopForeground(true); // 移除前台通知
        super.onDestroy();
        unregisterReceiver(bluetoothObserver);
        disableBleServices();




        // 电池-取消注册广播接收器
        if (batteryReceiver != null) {
            unregisterReceiver(batteryReceiver);
        }
        Toast.makeText(this, "电池监听服务已停止", Toast.LENGTH_SHORT).show();
        app.remove("log");

    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new DataPlane();
    }

    private class DataPlane extends Binder implements DeviceAPI {
        @Override
        public void setMyCharacteristicValue(String value) {
            if (serverManager != null) serverManager.setMyCharacteristicValue(value);
            //Log.d(TAG, "setMyCharacteristicValue: " + value );
            Log.d(TAG, "setMyCharacteristicValue: " + value, new Throwable());
        }


    }


    // ------------------- 发送广播到主界面 ----------------------
    public void sendBroadcastData(String data) {

        // 记录日志到文件
        String tm = getCurrentTime();
        String logdata = tm + ": " + data + "\n";
        allLog = allLog + logdata;
        app.setString("log", allLog);

        new Handler(Looper.getMainLooper()).post(() -> {
            Intent intent = new Intent("com.myapp.BLE_DATA_RECEIVED");
            intent.putExtra("data", logdata);
            sendBroadcast(intent);
            Log.d(TAG, "已发送广播: " + logdata );

            if("enable_pan".equals(data)){
                //如果客户端发来 enable_pan 开启蓝牙共享 服务端
                mBluetoothPanServer = new BluetoothPanServer();
                mBluetoothPanServer.enableBtPan(getApplicationContext(),true);
            }
            if("disable_pan".equals(data)){
                //如果客户端发来 enable_pan 开启蓝牙共享 服务端
                mBluetoothPanServer = new BluetoothPanServer();
                mBluetoothPanServer.enableBtPan(getApplicationContext(),false);
            }
            if("enable_wifi".equals(data)){
                //如果客户端发来 enable_wifi 开启wifi


                if (Build.VERSION.SDK_INT < 31) {
                    try {
                        // Android 10 对应的 startSoftAp 方法编号通常在 40-50 之间
                        // 可以通过 `adb shell service list` 查看 wifi 服务的编号
                        Process p = Runtime.getRuntime().exec("su");
                        DataOutputStream os = new DataOutputStream(p.getOutputStream());
                        os.writeBytes("svc data enable\n");
                        os.writeBytes("service call wifi 42\n");
                        os.writeBytes("exit\n");
                        os.flush();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }else{
                    try {
                        Process p = Runtime.getRuntime().exec("su");
                        DataOutputStream os = new DataOutputStream(p.getOutputStream());
                        os.writeBytes("svc data enable\n");
                        os.writeBytes("cmd wifi start-softap redmin9 wpa2 happmaoo\n");
                        os.writeBytes("exit\n");
                        os.flush();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                }
            }
            if("disable_wifi".equals(data)){
                if (Build.VERSION.SDK_INT < 31) {
                    try {
                        // Android 10 对应的 startSoftAp 方法编号通常在 40-50 之间
                        // 可以通过 `adb shell service list` 查看 wifi 服务的编号
                        Process p = Runtime.getRuntime().exec("su");
                        DataOutputStream os = new DataOutputStream(p.getOutputStream());

                        os.writeBytes("service call wifi 43\n");
                        os.writeBytes("exit\n");
                        os.flush();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }else{
                    try {
                        Process p = Runtime.getRuntime().exec("su");
                        DataOutputStream os = new DataOutputStream(p.getOutputStream());
                        os.writeBytes("cmd wifi stop-softap\n");
                        os.writeBytes("exit\n");
                        os.flush();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                }
            }
            // 信号检测 红米note9特定参数
            if("sig".equals(data)) {
                int status = -1;
                try {
                    Process p = Runtime.getRuntime().exec("ping -c 1 baidu.com");
                    status = p.waitFor();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if(status==0){
                    serverManager.setMyCharacteristicValue("sig:1");
                }else{
                    serverManager.setMyCharacteristicValue("sig:0");
                    //没网络尝试打开数据开关
                    try {
                        Runtime.getRuntime().exec("svc data enable");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

            }
            serverManager.setMyCharacteristicValue("received:"+data);

        });
    }

    private static class ServerManager extends BleServerManager implements ServerObserver, DeviceAPI {
        private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
        private final Map<String, ServerConnection> serverConnections = new HashMap<>();
        private final BluetoothGattCharacteristic batteryLevelCharacteristic;
        private final List<BluetoothGattService> myGattServices;

        private final BluetoothGattCharacteristic myGattCharacteristic;

        private final Context mContext;

        private final GattService gattService; // 新增：保存外部类引用

        // 默认打印日志。不用调用
        @Override
        public void log(int priority, @NonNull String message) {
            android.util.Log.println(priority, "GATT_SERVER", message);
        }


        ServerManager(@NonNull Context context, GattService service) {
            super(context);
            this.mContext = context.getApplicationContext();
            this.gattService = service; // 保存引用



            myGattCharacteristic = sharedCharacteristic(
                    MyServiceProfile.MY_CHARACTERISTIC_UUID1,
                    BluetoothGattCharacteristic.PROPERTY_READ |
                            BluetoothGattCharacteristic.PROPERTY_NOTIFY |
                            BluetoothGattCharacteristic.PROPERTY_WRITE|
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_READ |
                            BluetoothGattCharacteristic.PERMISSION_WRITE,
                    descriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE, new byte[]{0, 0}),
                    description("A characteristic to be read", false)
            );

            // 2. 初始化标准电池电量特征 (只读 + 通知)
            batteryLevelCharacteristic = sharedCharacteristic(
                    MyServiceProfile.BATTERY_LEVEL_CHARACTERISTIC_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                    cccd() // 添加通知描述符
            );

            // 首次连接自动获取电量并设置到特征值
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, ifilter); // 不需要真正的 receiver，只为获取当前状态
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int batteryPercent = (int) (level * 100f / scale);

                // 设置初始值
                batteryLevelCharacteristic.setValue(new byte[]{(byte) batteryPercent});
            }



            // 3. 组装服务列表
            BluetoothGattService customService = service(MyServiceProfile.MY_SERVICE_UUID, myGattCharacteristic);
            BluetoothGattService batteryService = service(MyServiceProfile.BATTERY_SERVICE_UUID, batteryLevelCharacteristic);

            // 将两个服务都加入列表
            myGattServices = java.util.Arrays.asList(customService, batteryService);
        }


        // 电池电量的方法
        public void updateBatteryLevel(int level) {
            int clampedLevel = Math.max(0, Math.min(100, level));
            byte[] value = new byte[]{(byte) clampedLevel};

            batteryLevelCharacteristic.setValue(value);

            for (ServerConnection conn : serverConnections.values()) {
                conn.notifyBatteryLevel(batteryLevelCharacteristic, value);
            }
        }

        @Override
        public void setMyCharacteristicValue(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            myGattCharacteristic.setValue(bytes);
            for (ServerConnection conn : serverConnections.values()) {
                conn.sendNotificationForMyGattCharacteristic(bytes);
            }
        }

        @NonNull
        @Override
        protected List<BluetoothGattService> initializeServer() {
            setServerObserver(this);
            return myGattServices;
        }

        @Override
        public void onServerReady() { Log.i(TAG, "Gatt server ready"); }

        @Override
        public void onDeviceConnectedToServer(@NonNull BluetoothDevice device) {
            ServerConnection conn = new ServerConnection(gattService); // 传递this
            conn.useServer(this);
            conn.connect(device).enqueue();
            serverConnections.put(device.getAddress(), conn);

            if (gattService != null) {
                gattService.sendBroadcastData(device.getAddress() + " " + "Connected");
                }
        }

        @Override
        public void onDeviceDisconnectedFromServer(@NonNull BluetoothDevice device) {
            String address = device.getAddress();
            ServerConnection conn = serverConnections.remove(address);
            if (conn != null) {
                conn.close();
            }
            if (gattService != null) {
                gattService.sendBroadcastData(address + " " + "Disconnected");
            }
        }

        // 内部类：管理单个连接
        private class ServerConnection extends BleManager {

            private final WeakReference<GattService> serviceRef; // 新增
            ServerConnection(GattService service) { // 修改构造函数
                super(mContext);
                this.serviceRef = new WeakReference<>(service);
            }

            void sendNotificationForMyGattCharacteristic(byte[] value) {
                sendNotification(myGattCharacteristic, value).enqueue();
            }

            // 添加这个公共方法供外部调用
            public void notifyBatteryLevel(BluetoothGattCharacteristic characteristic, byte[] value) {
                // 在这里可以访问 protected 的 sendNotification
                sendNotification(characteristic, value).enqueue();
            }


            @NonNull
            @Override
            protected BleManagerGattCallback getGattCallback() {
                return new BleManagerGattCallback() {
                    @Override
                    protected boolean isRequiredServiceSupported(@NonNull BluetoothGatt gatt) {
                        return true;
                    }

                    @Override
                    protected void initialize() {
                        // 这里有提示错误，没事。 设置连接后的连接间隔 省电
                        requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER).enqueue();
                        setWriteCallback(myGattCharacteristic)
                                .with((device, data) -> {
                                    byte[] bytes = data.getValue();
                                    if (bytes != null) {
                                        String receivedStr = new String(bytes, StandardCharsets.UTF_8);

                                        GattService service = serviceRef.get();
                                        if (service != null) {
                                            service.sendBroadcastData(receivedStr);
                                        }
                                    }
                                });
                    }

                    @Override
                    protected void onServicesInvalidated() {}
                };
            }
        }
    }





    // 在 GattService 类中定义变量
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int percent = (int) (level * 100f / scale);

            if (lastBatteryPercent != percent) {
                lastBatteryPercent = percent;
                if (serverManager != null) {
                    serverManager.updateBatteryLevel(percent);
                }
            }
        }
    };







    // isMobileDataEnabled
    public boolean isMobileDataEnabled(Context context) {
        int state = Settings.Global.getInt(context.getContentResolver(), "mobile_data", 0);
        return state == 1;
    }

    public void setMobileDataEnabled(boolean enabled) {
        String command = enabled ? "svc data enable" : "svc data disable";
        try {
            // 执行 su 命令
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date());
    }

}
package com.myapp.mybleserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt; // <-- 确保此行导入
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
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static com.myapp.mybleserver.Constants.BATTERY_LEVEL_CHAR_UUID;
import static com.myapp.mybleserver.Constants.BATTERY_SERVICE_UUID;
import static com.myapp.mybleserver.Constants.CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID;
import static com.myapp.mybleserver.Constants.CUSTOM_NOTIFY_CHAR_UUID;
import static com.myapp.mybleserver.Constants.CUSTOM_READ_WRITE_CHAR_UUID;
import static com.myapp.mybleserver.Constants.CUSTOM_SERVICE_UUID;

/**
 * 运行在前台的 BLE 服务端。
 * 实现了 GATT Server 和 BLE 广播。
 */
public class BleServer extends Service {
    private static final String TAG = "BleServer";

    // --- 前台服务常量 ---
    private static final String CHANNEL_ID = "BleServerChannel";
    private static final int NOTIFICATION_ID = 101;

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeAdvertiser mAdvertiser;
    private BluetoothGattServer mGattServer;
    private Handler mHandler;

    // 自定义特征的内存值
    private byte[] mReadWriteValue = "Initial Read Value".getBytes();
    private byte[] mNotifyValue = "Notification Data: 0".getBytes();

    // 客户端集合，用于发送通知
    private Set<BluetoothDevice> mConnectedDevices = new HashSet<>();

    // 特征引用
    private BluetoothGattCharacteristic mNotifyCharacteristic;
    private BluetoothGattCharacteristic mReadWriteCharacteristic;
    private BluetoothGattCharacteristic mBatteryLevelCharacteristic;

    // 广播回调
    private AdvertiseCallback mAdvertiseCallback;

    public static final String ACTION_STATUS_UPDATE = "com.example.bleserverapp.STATUS_UPDATE";

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper());
        initializeBluetooth();
        createNotificationChannel(); // 在服务创建时创建通知通道
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 1. 将服务提升为前台服务 (必须在 5 秒内调用)
        startForeground(NOTIFICATION_ID, buildNotification("服务启动中..."));

        // 2. 初始化 BLE
        if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()) {
            setupGattServer();
            startAdvertising();
        } else {
            Log.e(TAG, "Bluetooth not enabled or adapter is null.");
            sendBroadcastStatus("蓝牙未启用，服务停止。");
            stopSelf(); // 无法运行，则自我停止
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAdvertising();
        closeGattServer();
        // 停止前台服务并移除通知
        stopForeground(true);
        Log.i(TAG, "BleServer 已停止并退出前台模式。");
    }

    /**
     * 为 Android O 及更高版本创建通知通道
     */
    private void createNotificationChannel() {
        // 仅在 Android 8.0 (API 26) 及以上版本需要创建通道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "BLE Server Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    /**
     * 构建用于前台服务的通知
     */
    private Notification buildNotification(String text) {
        // 点击通知回到 MainActivity
        Intent notificationIntent = new Intent(this, MainActivity.class);
        // FLAG_IMMUTABLE 是新版本 Android 的要求，这里使用 FLAG_UPDATE_CURRENT 确保兼容性
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        // 使用 Notification.Builder
        Notification.Builder builder = new Notification.Builder(this)
                .setContentTitle("BLE Server 正在广播")
                .setContentText(text)
                // 必须设置一个小图标
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setTicker("BLE Server 启动")
                .setOngoing(true); // 表示通知无法被滑动清除

        // 对于 Android O 及更高版本，需要设置通道 ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID);
        }

        return builder.build();
    }


    private void initializeBluetooth() {
        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (mBluetoothManager == null) {
            Log.e(TAG, "BluetoothManager is null");
            return;
        }
        mBluetoothAdapter = mBluetoothManager.getAdapter();
    }

    // --- 广播相关方法 ---

    /**
     * 开始 BLE 广播
     */
    private void startAdvertising() {
        mAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        if (mAdvertiser == null) {
            Log.e(TAG, "Failed to get BluetoothLeAdvertiser");
            sendBroadcastStatus("广播器获取失败。");
            return;
        }

        // 1. 广播设置 (AdvertiseSettings)
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true) // 允许连接
                .setTimeout(0) // 永不超时
                .build();

        // 2. 广播数据 (AdvertiseData)
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new android.os.ParcelUuid(CUSTOM_SERVICE_UUID))
                .addServiceData(new android.os.ParcelUuid(BATTERY_SERVICE_UUID), new byte[]{0x00})
                .build();

        mAdvertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                String msg = "BLE 广播启动成功。";
                Log.i(TAG, msg);
                sendBroadcastStatus(msg);
            }

            @Override
            public void onStartFailure(int errorCode) {
                String msg = "BLE 广播启动失败, 错误码: " + errorCode;
                Log.e(TAG, msg);
                sendBroadcastStatus(msg);
            }
        };

        mAdvertiser.startAdvertising(settings, data, mAdvertiseCallback);
    }

    /**
     * 停止 BLE 广播
     */
    private void stopAdvertising() {
        if (mAdvertiser != null && mAdvertiseCallback != null) {
            mAdvertiser.stopAdvertising(mAdvertiseCallback);
            Log.i(TAG, "BLE 广播已停止。");
            // 不需要再次发送状态，因为 onDestroy 时会停止服务。
        }
    }

    // --- GATT Server 相关方法 ---

    /**
     * 设置 GATT Server，添加服务和特征
     */
    private void setupGattServer() {
        mGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);
        if (mGattServer == null) {
            Log.e(TAG, "Failed to open BluetoothGattServer.");
            sendBroadcastStatus("GATT Server 开启失败。");
            return;
        }

        // 1. 添加自定义服务 (包含读、写、通知特性)
        mGattServer.addService(createCustomService());

        // 2. 添加电池服务 (用于模拟)
        mGattServer.addService(createBatteryService());

        Log.i(TAG, "GATT Server 设置完成，服务已添加。");
        sendBroadcastStatus("GATT Server 设置完成，等待客户端连接...");

        // 启动定时器，每 5 秒更新一次数据
        mHandler.post(mUpdateRunnable);
    }

    /**
     * 创建自定义服务 (包含读写和通知特征)
     */
    private BluetoothGattService createCustomService() {
        // 创建自定义服务
        BluetoothGattService service = new BluetoothGattService(CUSTOM_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // 1. 读写特征 (R/W)
        mReadWriteCharacteristic = new BluetoothGattCharacteristic(
                CUSTOM_READ_WRITE_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);

        mReadWriteCharacteristic.setValue(mReadWriteValue);
        service.addCharacteristic(mReadWriteCharacteristic);

        // 2. 通知特征 (R/Notify)
        mNotifyCharacteristic = new BluetoothGattCharacteristic(
                CUSTOM_NOTIFY_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);

        mNotifyCharacteristic.setValue(mNotifyValue);

        // 必须添加 CCCD 描述符才能启用通知
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);

        mNotifyCharacteristic.addDescriptor(cccd);
        service.addCharacteristic(mNotifyCharacteristic);

        return service;
    }

    /**
     * 创建电池服务 (仅包含电量特征)
     */
    private BluetoothGattService createBatteryService() {
        // 创建标准电池服务 (0x180F)
        BluetoothGattService service = new BluetoothGattService(BATTERY_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // 电池电量特征 (0x2A19): Read | Notify
        mBatteryLevelCharacteristic = new BluetoothGattCharacteristic(
                BATTERY_LEVEL_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);

        // 必须添加 CCCD 描述符才能启用通知
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);

        mBatteryLevelCharacteristic.addDescriptor(cccd);
        service.addCharacteristic(mBatteryLevelCharacteristic);

        // 初始化电量值
        updateBatteryLevel();

        return service;
    }

    /**
     * 关闭 GATT Server
     */
    private void closeGattServer() {
        if (mGattServer != null) {
            mGattServer.close();
            mGattServer = null;
        }
        mHandler.removeCallbacks(mUpdateRunnable);
    }

    // --- GATT Server 回调 ---

    private final BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            super.onConnectionStateChange(device, status, newState);
            String deviceAddress = device.getAddress();

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    mConnectedDevices.add(device);
                    Log.i(TAG, "设备连接成功: " + deviceAddress);
                    sendBroadcastStatus("客户端连接: " + deviceAddress);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    mConnectedDevices.remove(device);
                    Log.i(TAG, "设备断开连接: " + deviceAddress);
                    sendBroadcastStatus("客户端断开: " + deviceAddress);
                }
            } else {
                Log.e(TAG, "连接状态变化失败，状态码: " + status);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);

            byte[] valueToRead;
            if (CUSTOM_READ_WRITE_CHAR_UUID.equals(characteristic.getUuid())) {
                valueToRead = mReadWriteValue;
                Log.i(TAG, "收到读请求 (R/W Char): " + new String(valueToRead));
            } else if (CUSTOM_NOTIFY_CHAR_UUID.equals(characteristic.getUuid())) {
                valueToRead = mNotifyValue;
                Log.i(TAG, "收到读请求 (Notify Char): " + new String(valueToRead));
            } else if (BATTERY_LEVEL_CHAR_UUID.equals(characteristic.getUuid())) {
                updateBatteryLevel(); // 读取时更新最新电量
                valueToRead = mBatteryLevelCharacteristic.getValue();
                Log.i(TAG, "收到读请求 (Battery Level): " + (valueToRead.length > 0 ? valueToRead[0] + "%" : "N/A"));
            } else {
                valueToRead = new byte[0];
                Log.w(TAG, "不支持的特征读请求: " + characteristic.getUuid());
            }

            // 发送响应，使用 BluetoothGatt.GATT_SUCCESS
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, valueToRead);
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);

            if (CUSTOM_READ_WRITE_CHAR_UUID.equals(characteristic.getUuid())) {
                // 仅处理自定义读写特征的写入
                String received = new String(value);
                Log.i(TAG, "收到写请求 (R/W Char): " + received);

                // 更新特征的内部值
                mReadWriteValue = value;

                // 更新特征对象的 value，确保下次读取时返回新值
                characteristic.setValue(value);

                sendBroadcastStatus("收到写入: " + received);
            } else {
                Log.w(TAG, "不支持的特征写请求: " + characteristic.getUuid());
            }

            // 必须响应写请求 (如果 responseNeeded 为 true)
            if (responseNeeded) {
                // 使用 BluetoothGatt.GATT_SUCCESS
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value);

            if (CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID.equals(descriptor.getUuid())) {
                // 确认是 CCCD 写入
                BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();

                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    Log.i(TAG, "客户端启用通知: " + characteristic.getUuid());
                    // 仅显示 UUID 的后四位，避免日志过长
                    sendBroadcastStatus("通知已启用: " + characteristic.getUuid().toString().substring(4, 8));
                } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    Log.i(TAG, "客户端禁用通知: " + characteristic.getUuid());
                    sendBroadcastStatus("通知已禁用: " + characteristic.getUuid().toString().substring(4, 8));
                } else {
                    Log.w(TAG, "收到未知的描述符写入值");
                }

                // 更新描述符的值
                descriptor.setValue(value);
            } else {
                Log.w(TAG, "不支持的描述符写请求: " + descriptor.getUuid());
            }

            // 必须响应描述符写请求
            if (responseNeeded) {
                // 使用 BluetoothGatt.GATT_SUCCESS
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
            }
        }
    };

    // --- 周期性任务和通知 ---

    private int notificationCounter = 0;

    private final Runnable mUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            // 1. 更新自定义通知特征的数据
            notificationCounter++;
            mNotifyValue = ("Notification Data: " + notificationCounter).getBytes();
            mNotifyCharacteristic.setValue(mNotifyValue);

            // 2. 更新电池电量特征的数据
            updateBatteryLevel();

            // 3. 尝试发送通知 (给所有已连接的设备)
            sendNotification(mNotifyCharacteristic);
            sendNotification(mBatteryLevelCharacteristic);

            // 5 秒后再次执行
            mHandler.postDelayed(this, 5000);
        }
    };

    /**
     * 发送特征通知给所有已连接且启用了通知的客户端
     * @param characteristic 要通知的特征
     */
    private void sendNotification(BluetoothGattCharacteristic characteristic) {
        if (mGattServer == null || mConnectedDevices.isEmpty()) {
            return;
        }

        for (BluetoothDevice device : mConnectedDevices) {
            // 检查 CCCD 描述符是否已启用通知
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID);
            if (descriptor != null && Arrays.equals(descriptor.getValue(), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {

                boolean success = mGattServer.notifyCharacteristicChanged(device, characteristic, false);
                if (success) {
                    // Log 仅打印自定义通知数据
                    if (CUSTOM_NOTIFY_CHAR_UUID.equals(characteristic.getUuid())) {
                        Log.i(TAG, "成功发送通知到 " + device.getAddress() + ": " + new String(characteristic.getValue()));
                    }
                }
            }
        }
    }

    /**
     * 获取并更新本机电池电量，并设置给 Battery Level Characteristic
     */
    private void updateBatteryLevel() {
        // 获取电池电量
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bm == null) {
            Log.e(TAG, "BatteryManager is null.");
            return;
        }
        int batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

        // Battery Level Characteristic 是一个 UINT8 值 (0-100)
        byte[] level = new byte[]{(byte) batteryLevel};

        mBatteryLevelCharacteristic.setValue(level);
        Log.d(TAG, "更新电池电量: " + batteryLevel + "%");
    }

    // --- Service 绑定相关 (本例不使用绑定) ---

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- 状态广播给 Activity 并更新前台通知 ---

    private void sendBroadcastStatus(String message) {
        // 1. 发送广播给 Activity
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra("status_message", message);
        sendBroadcast(intent);

        // 2. 更新前台通知文本
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification("最新状态: " + message));
        }
    }
}
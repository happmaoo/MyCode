package com.myapp.mygattserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertiseData;

import android.bluetooth.BluetoothProfile;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE;

public class GattServer extends Service {

    private static final String CHANNEL_ID = "ForegroundServiceChannel";
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private static final String SERVICE_UUID = "00000001-0000-0000-0000-000000000000";
    private static final String CHARACTERISTIC_UUID = "00000011-0000-0000-0000-000000000000";





    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Foreground Service")
                .setContentText("Service is running...")
                .setSmallIcon(R.drawable.ic_launcher_foreground) // 确保你有一个通知图标
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);

        // 这里可以执行你的服务逻辑

        // 初始化 BluetoothManager 和 BluetoothAdapter
        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        // 检查蓝牙是否可用
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            // 蓝牙未启用，提示用户
            //return;
        }

        // 创建 GATT Server
        setupGattServer();

        advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();

        // 开始广播
        startAdvertising();
        //run("ls", "/sdcard/");



        return START_NOT_STICKY;
    }





    private void startAdvertising() {
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW)
                .setConnectable(true)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true) // 包含设备名称
                .setIncludeTxPowerLevel(true) // 包含 TX 功率级别
                //.addServiceUuid("serviceUuid") // 添加服务 UUID
                .build();

        advertiser.startAdvertising(settings, data, advertiseCallback);


    }


    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
/*        //@Override
        public void onStartSuccess(int advertiseId) {
            super.onStartSuccess(advertiseId);
            Log.i("GATT", "广播成功");
        }

        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);
            Log.e("GATT", "广播失败，错误代码: " + errorCode);
        }*/
    };


    private void setupGattServer() {
        gattServer = bluetoothManager.openGattServer(this, gattServerCallback);

        // 创建服务
        BluetoothGattService service = new BluetoothGattService(
                UUID.fromString(SERVICE_UUID), // 示例 UUID
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );

        // 创建可读写特征
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                UUID.fromString(CHARACTERISTIC_UUID), // 示例 UUID
                BluetoothGattCharacteristic.PROPERTY_READ |
                        BluetoothGattCharacteristic.PROPERTY_WRITE | // 可写
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ |
                        BluetoothGattCharacteristic.PERMISSION_WRITE // 读写权限
        );

        // 添加特征到服务
        service.addCharacteristic(characteristic);

        // 将服务添加到 GATT Server
        gattServer.addService(service);
    }

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // 设备连接
                advertiser.stopAdvertising(advertiseCallback);
                Log.i("GATT", "设备连接.");


            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // 设备断开连接
                startAdvertising();
                Log.i("GATT", "设备断开连接.");
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                                                int offset, BluetoothGattCharacteristic characteristic) {
            // 处理特征读取请求
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.getValue());
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                                                 boolean responseNeeded, int offset, byte[] value) {
            // 处理特征写入请求
            if (characteristic.getUuid().equals(UUID.fromString(CHARACTERISTIC_UUID))) {
                // 处理写入的数据
                //characteristic.setValue(value);
                String text1=new String(value);
                Log.i("mylog",new String(value));

                /*
                if(text1.equals("start_record")){
                    Log.i("mylog","开始录像");
                    termuxcommand("record","1");
                    characteristic.setValue("recording");

                } else if (text1.equals("stop_record")){
                    termuxcommand("record","0");
                    characteristic.setValue("record-stopped");
                }
                */

                termuxcommand("record","");

                int attempts = 0;
                while (gval.get_termux_result() == null && attempts < 50) {
                    try {
                        Thread.sleep(500);
                        System.out.println("变量未获取, waiting for...");
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                Log.i("mylog","get_termux_result: "+gval.get_termux_result());

                if(gval.get_termux_result().contains("recording1")){
                    characteristic.setValue("recording");
                } else if (gval.get_termux_result().contains("recording0")) {
                    characteristic.setValue("record-stopped");
                } else {
                    characteristic.setValue("gval.get_termux_result unknow");
                }
                // 重置
                gval.set_termux_result(null);



                //characteristic.setValue("recording");
                //characteristic.setValue("record-stopped");

                // 发送响应
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);



            } else {
                // 发送错误响应
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null);
            }
        }
    };







    private void termuxcommand(String arg1,String arg2){

        Intent intent = new Intent();
        intent.setClassName(TermuxConstants.TERMUX_PACKAGE_NAME, TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE_NAME);
        intent.setAction(RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND);
        intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_ARGUMENTS, new String[]{"./gattserver.sh", arg1,arg2});
        intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_WORKDIR, "/data/data/com.termux/files/home");
        intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_BACKGROUND, true);
        intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_SESSION_ACTION, "0");
        //intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_COMMAND_LABEL, "my command");
        //intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_COMMAND_DESCRIPTION, "Runs the top command to show processes using the most resources.");

        Intent pluginResultsServiceIntent = new Intent(getApplicationContext(), PluginResultsService.class);
        int executionId = PluginResultsService.getNextExecutionId();
        pluginResultsServiceIntent.putExtra(PluginResultsService.EXTRA_EXECUTION_ID, executionId);

        PendingIntent pendingIntent = PendingIntent.getService(getApplicationContext(), executionId,
                pluginResultsServiceIntent,
                PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0));
        intent.putExtra(TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE.EXTRA_PENDING_INTENT, pendingIntent);

        try {
            // Send command intent for execution
            Log.d("aaa", "Sending execution command with id " + executionId);
            startService(intent);
        } catch (Exception e) {
            Log.e("aaa", "Failed to start execution command with id " + executionId + ": " + e.getMessage());
        }

    }








    @Override
    public void onDestroy() {
        super.onDestroy();
        if (gattServer != null) {
            gattServer.close();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }
}

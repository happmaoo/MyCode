package com.myapp.gattserver;


import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertiseData;

import android.bluetooth.BluetoothProfile;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import android.app.Instrumentation;
import android.os.SystemClock;
import com.myapp.gattserver.ShellCommand;

public class MainActivity extends AppCompatActivity {

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private static final String SERVICE_UUID = "00000001-0000-0000-0000-000000000000";
    private static final String CHARACTERISTIC_UUID = "00000011-0000-0000-0000-000000000000";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化 BluetoothManager 和 BluetoothAdapter
        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        // 检查蓝牙是否可用
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            // 蓝牙未启用，提示用户
            return;
        }

        // 创建 GATT Server
        setupGattServer();

        advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();

        // 开始广播
        startAdvertising();
        //run("ls", "/sdcard/");
    }

    private void run(String... cmd){
        new Thread(() -> {
            String result = ShellCommand.runShellCommand(cmd);
            runOnUiThread(() -> {
                // 在 UI 线程中更新 UI
                Log.i("",result);
            });
        }).start();
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
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if(Objects.equals(readFirstLineFromFile(), "1")){
                    characteristic.setValue("recording");
                }else{
                    characteristic.setValue("record-stopped");
                };

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
        intent.setClassName("com.termux", "com.termux.app.RunCommandService");
        intent.setAction("com.termux.RUN_COMMAND");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"./gattserver.sh", arg1,arg2});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");

        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        startService(intent);
    }

    private String readFirstLineFromFile() {
        String firstLine = "";
        File file = new File(Environment.getExternalStorageDirectory(), "DCIM/record.txt");
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            firstLine = br.readLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return firstLine;
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gattServer != null) {
            gattServer.close();
        }
    }
}

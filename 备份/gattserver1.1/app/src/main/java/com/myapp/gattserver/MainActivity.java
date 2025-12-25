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
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertiseData;

import android.bluetooth.BluetoothProfile;

import android.content.Context;
import android.widget.Toast;


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
    }


    private void startAdvertising() {
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
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
                java.util.UUID.fromString(SERVICE_UUID), // 示例 UUID
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );

        // 创建可读写特征
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                java.util.UUID.fromString(CHARACTERISTIC_UUID), // 示例 UUID
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

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // 设备断开连接
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
            if (characteristic.getUuid().equals(java.util.UUID.fromString(CHARACTERISTIC_UUID))) {
                // 处理写入的数据
                characteristic.setValue(value);

                // 发送响应
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
            } else {
                // 发送错误响应
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null);
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gattServer != null) {
            gattServer.close();
        }
    }
}

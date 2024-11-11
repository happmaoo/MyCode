package com.example.myapplication;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;


import android.bluetooth.BluetoothProfile;

import android.content.Context;
import android.widget.Toast;


public class MainActivity extends AppCompatActivity {

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGattServer gattServer;
    private static final String SERVICE_UUID = "0000aaa0-0000-1000-8000-aabbccddeeff";
    private static final String CHARACTERISTIC_UUID = "0000abc0-0000-1000-8000-aabbccddee00";

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
    }

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
                //Toast.makeText(MainActivity.this, "设备连接.", Toast.LENGTH_SHORT).show();
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

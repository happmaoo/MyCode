package com.example.myapplication;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import java.util.List;
import java.util.UUID;

public class BleManager {
    private static final String TAG = "BleManager";
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private Context context;
    private GattConnectionStateListener listener;


    private static final String SERVICE_UUID = "0000aaa0-0000-1000-8000-aabbccddeeff";
    private static final String CHARACTERISTIC_UUID = "0000abc0-0000-1000-8000-aabbccddee00";


    public BleManager(Context context, GattConnectionStateListener listener) {
        this.listener = listener;
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
    }



    public void connectToDevice(String deviceAddress) {
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
        bluetoothGatt = device.connectGatt(null, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server.");
                // 在这里把 newState 值传递给 MainActivity
                if (listener != null) {
                    listener.onConnectionStateChanged(newState);
                }
                // 连接成功后，开始发现服务
                gatt.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.");
                if (listener != null) {
                    listener.onConnectionStateChanged(newState);
                }
            }
        }

        private void printGattServices(BluetoothGatt gatt) {
            List<BluetoothGattService> services = gatt.getServices();
            StringBuilder servicesString = new StringBuilder();

            for (BluetoothGattService service : services) {
                UUID serviceUUID = service.getUuid();
                servicesString.append("Service UUID: ").append(serviceUUID.toString()).append("\n");

                // 如果需要，可以遍历特征
                service.getCharacteristics().forEach(characteristic -> {
                    UUID characteristicUUID = characteristic.getUuid();
                    servicesString.append("  Characteristic UUID: ").append(characteristicUUID.toString()).append("\n");
                });
            }

            // 打印或使用服务字符串
            String servicesOutput = servicesString.toString();
            System.out.println(servicesOutput); // 或者使用 Toast 显示
            //Toast.makeText(context, servicesOutput, Toast.LENGTH_LONG).show(); // 如果需要在 UI 中显示
        }
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 查找服务
                BluetoothGattService service = bluetoothGatt.getService(UUID.fromString(SERVICE_UUID));
                if (service != null) {
                    // 查找特征
                    BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID));
                    if (characteristic != null) {
                        // 写入数据
                        //writeData(characteristic, "Hello BLE");
                        // 读取特征
                        bluetoothGatt.readCharacteristic(characteristic);

                    } else {
                        Log.e("BLE", "特征未找到");
                    }
                } else {
                    Log.e("BLE", "服务未找到");
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 读取成功，处理特征值
                byte[] data = characteristic.getValue();
                String value = new String(data); // 根据需要处理数据
                Log.i("BLE", "读取的特征值: " + value);
                // 把 value 值传递给 MainActivity
                if (listener != null) {
                    listener.onCharacteristicRW(value);
                }
            } else {
                Log.e("BLE", "读取特征失败，状态: " + status);
            }
        }
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("BLE", "数据写入成功");
                // 把 value 值传递给 MainActivity
                if (listener != null) {
                    listener.onCharacteristicRW("数据写入成功");
                }
            } else {
                Log.e("BLE", "数据写入失败，状态: " + status);
            }
        }
    };

    // 写入数据的方法
    private void writeData(BluetoothGattCharacteristic characteristic, String data) {
        byte[] valueBytes = data.getBytes(); // 将字符串转换为字节数组
        characteristic.setValue(valueBytes); // 设置特征的值
        boolean success = bluetoothGatt.writeCharacteristic(characteristic); // 写入特征
        if (!success) {
            Log.e("BLE", "写入请求失败");
        }

    };

    public void disconnect() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }
}

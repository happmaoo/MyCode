package com.example.gattclient;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;


import java.util.List;
import java.util.UUID;

public class myservice extends Service {
    private static final String TAG = "BleService";
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;

    //private static final String SERVICE_UUID = "0000aaa0-0000-1000-8000-aabbccddeeff";
    //private static final String CHARACTERISTIC_UUID = "0000abc0-0000-1000-8000-aabbccddee00";

    private static String SERVICE_UUID;
    private static String CHARACTERISTIC_UUID;
    private static String CHARACTERISTIC_WRITE;

    public void msg(String msg){
        // 这里无法用Toast，无法在非ui进程显示，所以我就用广播来显示读取到的数据。
        //Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        Intent intent2 = new Intent("com.example.gattclient");
        intent2.setComponent(new ComponentName("com.example.gattclient",
                "com.example.gattclient.MyBroadcastReceiver"));
        intent2.putExtra("msg",msg);
        sendBroadcast(intent2);


        // UI 更新广播
        Intent intentui = new Intent("com.example.gattclient");
        intentui.setComponent(new ComponentName("com.example.gattclient",
                "com.example.gattclient.MyUIBroadcastReceiver"));
        intentui.putExtra("uitext",msg);
        sendBroadcast(intentui);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String deviceAddress = intent.getStringExtra("DEVICE_ADDRESS");
        SERVICE_UUID = intent.getStringExtra("SERVICE_UUID");
        CHARACTERISTIC_UUID = intent.getStringExtra("CHARACTERISTIC_UUID");
        CHARACTERISTIC_WRITE = intent.getStringExtra("CHARACTERISTIC_WRITE");
        if (deviceAddress != null) {
            connectToDevice(deviceAddress);
        }


        Toast.makeText(this, "地址:" + deviceAddress, Toast.LENGTH_SHORT).show();
        String message = intent.getStringExtra("message");
        if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }


        return START_STICKY; // 服务在被杀死后会重新创建
    }

    public void connectToDevice(String deviceAddress) {
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
        bluetoothGatt = device.connectGatt(this, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server.");
                //msg("Connected to GATT server.");
                bluetoothGatt.readRemoteRssi();
                gatt.discoverServices();

                BluetoothDevice BluetoothDevice = gatt.getDevice();
                String deviceName = BluetoothDevice.getName(); // 获取设备名称
                String deviceAddress = BluetoothDevice.getAddress(); // 获取设备地址

                msg(deviceName+":"+deviceAddress);

            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.");
                stopSelf(); // 断开连接后停止服务
            }
        }

        private void printGattServices(BluetoothGatt gatt) {
            List<BluetoothGattService> services = gatt.getServices();
            StringBuilder servicesString = new StringBuilder();

            for (BluetoothGattService service : services) {
                UUID serviceUUID = service.getUuid();
                servicesString.append("Service UUID: ").append(serviceUUID.toString()).append("\n");

                service.getCharacteristics().forEach(characteristic -> {
                    UUID characteristicUUID = characteristic.getUuid();
                    servicesString.append("  Characteristic UUID: ").append(characteristicUUID.toString()).append("\n");
                });
            }

            Log.i("mylog", "Service 和 characteristic 列表:\n" + servicesString);
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 成功获取 RSSI 值
                Log.i("mylog", "RSSI: " + rssi);
                msg("RSSI: " + rssi);
            } else {
                Log.w("mylog", "Failed to read RSSI, status: " + status);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                printGattServices(gatt);
                BluetoothGattService service = bluetoothGatt.getService(UUID.fromString(SERVICE_UUID));
                BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID));
                if (CHARACTERISTIC_WRITE == null){

                    if (service != null) {
                        if (characteristic != null) {
                            bluetoothGatt.readCharacteristic(characteristic);
                        } else {
                            Log.i("mylog", "特征未找到");
                        }
                    } else {
                        Log.i("mylog", "服务未找到");
                    }
                }else{
                    characteristic.setValue(CHARACTERISTIC_WRITE.getBytes());
                    gatt.writeCharacteristic(characteristic);
                }
            }
        }


        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                byte[] data = characteristic.getValue();
                String value = new String(data);

                Log.i("mylog", "读取的特征UUID和值: " + characteristic.getUuid() +"\n"+ value);
                msg("读取的特征UUID和值: \n" + characteristic.getUuid() +"\n"+ value);
            } else {
                Log.i("mylog", "读取特征失败，状态: " + status);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("mylog", "数据写入成功");
            } else {
                Log.i("mylog", "数据写入失败，状态: " + status);
            }
        }
    };

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnect();
    }

    private void disconnect() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // 不需要绑定
    }
}

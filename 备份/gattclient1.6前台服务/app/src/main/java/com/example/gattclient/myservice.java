package com.example.gattclient;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public class myservice extends Service {
    private static final String CHANNEL_ID = "MyForegroundServiceChannel";
    private static final String TAG = "BleService";
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothLeScanner bluetoothLeScanner;
    private boolean isConnected = false;

    //private static final String SERVICE_UUID = "0000aaa0-0000-1000-8000-aabbccddeeff";
    //private static final String CHARACTERISTIC_UUID = "0000abc0-0000-1000-8000-aabbccddee00";

    private static String SERVICE_UUID;
    private static String DEVICE_NAME;
    private static String CHARACTERISTIC_UUID;
    private static String CHARACTERISTIC_WRITE;
    private HashSet<String> deviceNames = new HashSet<>();


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

        Log.i("mylog", msg);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        createNotificationChannel();
    }




    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 创建通知
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("gattclient")
                .setContentText("gattclient is running in the foreground")
                .setSmallIcon(R.drawable.ic_launcher_foreground) // 替换为你的图标
                .build();
        startForeground(1, notification);

        DEVICE_NAME = intent.getStringExtra("DEVICE_NAME");
        SERVICE_UUID = intent.getStringExtra("SERVICE_UUID");
        CHARACTERISTIC_UUID = intent.getStringExtra("CHARACTERISTIC_UUID");
        CHARACTERISTIC_WRITE = intent.getStringExtra("CHARACTERISTIC_WRITE");

        if(isConnected){
            msg("已连接.");
            bluetoothGatt.readRemoteRssi();
            bluetoothGatt.discoverServices();
            return START_STICKY; // 直接返回，停止执行后续代码
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        bluetoothLeScanner.startScan(scanCallback);
        msg("正在扫描.");

        String message = intent.getStringExtra("message");
        if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }


        return START_STICKY; // 服务在被杀死后会重新创建
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, @NonNull ScanResult result) {
            super.onScanResult(callbackType, result);
            BluetoothDevice device = result.getDevice();
            String deviceName = device.getName();
            if (deviceName != null) {
                // 获取全部设备
                //deviceNames.add(deviceName); // 添加设备名称到集合
                //msg("发现设备: " + deviceName + " - " + device.getAddress());
                if (DEVICE_NAME.equals(device.getName())) {
                    msg("发现目标设备: " + device.getName() + " - " + device.getAddress());

                    bluetoothLeScanner.stopScan(this); // 停止扫描
                    connectToDevice(device.getAddress());
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            msg("扫描失败，错误代码: " + errorCode);
        }
    };


    public void connectToDevice(String deviceAddress) {
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
        bluetoothGatt = device.connectGatt(this, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                msg("Connected to GATT server.");

                //msg("Connected to GATT server.");
                bluetoothGatt.readRemoteRssi();
                gatt.discoverServices();

                BluetoothDevice BluetoothDevice = gatt.getDevice();
                String deviceName = BluetoothDevice.getName(); // 获取设备名称
                String deviceAddress = BluetoothDevice.getAddress(); // 获取设备地址

                msg(deviceName+":"+deviceAddress);
                //修改连接参数
                bluetoothGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);
                isConnected = true;


            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                msg("Disconnected from GATT server.");
                stopSelf(); // 断开连接后停止服务
                isConnected = false;
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

            //msg("Service 和 characteristic 列表:\n" + servicesString);
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 成功获取 RSSI 值
                msg("RSSI: " + rssi);
            } else {
               msg("Failed to read RSSI, status: " + status);
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
                            msg( "特征未找到");
                        }
                    } else {
                        msg("服务未找到");
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
                msg(value);
            } else {
               msg("读取特征失败，状态: " + status);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                msg("数据写入成功:"+CHARACTERISTIC_WRITE);
                bluetoothGatt.readCharacteristic(characteristic);
            } else {
                msg("数据写入失败，状态: " + status);
            }
        }
    };

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnect();
        stopForeground(true); // 移除通知
    }

    private void disconnect() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
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

    @Override
    public IBinder onBind(Intent intent) {
        return null; // 不需要绑定
    }
}

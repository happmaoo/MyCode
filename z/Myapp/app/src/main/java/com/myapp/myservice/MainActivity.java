package com.myapp.myservice;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;



import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.*;
import no.nordicsemi.android.ble.data.Data;


public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_LOCATION_PERMISSION = 1;
    private MyBleManager bleManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 检查权限
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, REQUEST_LOCATION_PERMISSION);
        } else {
            initBleManager();
        }
    }

    private void initBleManager() {
        bleManager = new MyBleManager(this);
        bleManager.setListener(new BleManagerListener() {
            @Override
            public void onDeviceConnected(BluetoothDevice device) {
                Log.d("BLE", "Device connected: " + device.getName());
            }

            @Override
            public void onDeviceDisconnected(BluetoothDevice device) {
                Log.d("BLE", "Device disconnected: " + device.getName());
            }
        });
        // 开始扫描设备
        bleManager.startScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bleManager.disconnect().enqueue();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initBleManager();
            }
        }
    }
}

// MyBleManager.java
import android.content.Context;
import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.callback.BleManagerGattCallback;

public class MyBleManager extends BleManager<MyBleManager> {

    public MyBleManager(Context context) {
        super(context);
    }

    @Override
    protected BleManagerGattCallback getGattCallback() {
        return new BleManagerGattCallback() {
            @Override
            protected void onDeviceReady() {
                // 设备准备好后可以进行数据交互
            }

            @Override
            protected void onServicesDiscovered(boolean optionalServicesFound) {
                // 服务发现后可以进行特征读取或写入
            }
        };
    }
}

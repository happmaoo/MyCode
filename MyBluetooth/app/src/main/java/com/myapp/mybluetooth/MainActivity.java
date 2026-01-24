package com.myapp.mybluetooth;

import androidx.appcompat.app.AppCompatActivity;

import android.bluetooth.BluetoothAdapter;
import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

    private BluetoothManager mPanConnecter;
    private BluetoothPanServer mBluetoothPanServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //蓝牙共享 客户端
        mPanConnecter = new BluetoothManager();
        String targetName = "Redmi Note 9";
        mPanConnecter.connectToPanDevice(MainActivity.this, targetName);

        //蓝牙共享 服务端
        //mBluetoothPanServer = new BluetoothPanServer();
        //mBluetoothPanServer.enableBtPan(MainActivity.this);

    }
}
package com.example.myapplication;

import androidx.appcompat.app.AppCompatActivity;
import com.example.myapplication.BleManager;

import android.bluetooth.BluetoothGatt;
import android.os.Bundle;
import android.widget.TextView;

import com.example.myapplication.GattConnectionStateListener;


public class MainActivity extends AppCompatActivity implements GattConnectionStateListener{
    private BleManager bleManager;
    private TextView statusTextView; // 声明 TextView
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusTextView = findViewById(R.id.TextView1); // 初始化 TextView
        bleManager = new BleManager(this, (GattConnectionStateListener) this);
        String deviceAddress = "3C:28:6D:DD:D3:0D"; // 替换为你的设备地址
        bleManager.connectToDevice(deviceAddress);


    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bleManager.disconnect();
    }

    public void onConnectionStateChanged(int newState) {
        if (newState == BluetoothGatt.STATE_CONNECTED) {
            statusTextView.setText("已连接到 GATT 服务器"); // 更新 TextView
        } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
            statusTextView.setText("已断开与 GATT 服务器的连接"); // 更新 TextView
        }
    }

    public void onCharacteristicRW(String value) {
        statusTextView.setText("读取的特征值: " + value); // 更新 TextView
    }
}

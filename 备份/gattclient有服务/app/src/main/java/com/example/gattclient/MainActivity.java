package com.example.gattclient;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;


public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 屏幕保持常亮不超时 ,需要授予权限
        // <uses-permission android:name="android.permission.WRITE_SETTINGS" />
        //ContentResolver resolver = getContentResolver();
        //Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, 3600000);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        //bleManager = new BleManager(this);
        //String deviceAddress = "3C:28:6D:DD:D3:0D"; // 替换为你的设备地址
        //bleManager.connectToDevice(deviceAddress);

        // 在 onCreate 中启动服务
        Intent serviceIntent = new Intent(this, myservice.class);
        serviceIntent.putExtra("DEVICE_ADDRESS", "3C:28:6D:DD:D3:0D");
        serviceIntent.putExtra("toast_message", "这是自定义的 Toast 消息");
        startService(serviceIntent); // 启动服务

    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        //bleManager.disconnect();
    }
}

package com.example.gattclient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
        //Intent serviceIntent = new Intent(this, myservice.class);
        //serviceIntent.putExtra("DEVICE_ADDRESS", "3C:28:6D:DD:D3:0D");
        //serviceIntent.putExtra("toast_message", "这是自定义的 Toast 消息");
        //startService(serviceIntent); // 启动服务



        // 外部调用方法
        //am broadcast -a com.example.gattclient -n com.example.gattclient/com.example.gattclient.MyBroadcastReceiver --es DEVICE_NAME "Pixel 3" --es SERVICE_UUID 0000aaa0-0000-1000-8000-aabbccddeeff --es CHARACTERISTIC_UUID 0000abc0-0000-1000-8000-aabbccddee00 --es CHARACTERISTIC_WRITE abc
        start();
        updateui();

    }

    public void start(){
        Intent intent = new Intent("com.example.gattclient");
        intent.setComponent(new ComponentName("com.example.gattclient",
                "com.example.gattclient.MyBroadcastReceiver"));
        intent.putExtra("DEVICE_NAME", "Pixel 3");
        //intent.putExtra("message","Android 8.0 系统静态广播的测试数据");
        sendBroadcast(intent);
    }


    public void updateui(){
        // 定义ui广播接收器
        BroadcastReceiver uiUpdateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String currentDateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                String uitext = intent.getStringExtra("uitext");
                // 更新界面上的文字
                TextView textView = findViewById(R.id.text1);
                textView.setText(textView.getText() + "\n" + currentDateTime + "\n" + uitext);
            }
        };

        // 注册本地ui广播接收器
        LocalBroadcastManager.getInstance(this).registerReceiver(uiUpdateReceiver,
                new IntentFilter("UPDATE_UI"));

        Intent intentui = new Intent("com.example.gattclient");
        intentui.setComponent(new ComponentName("com.example.gattclient",
                "com.example.gattclient.MyUIBroadcastReceiver"));
        intentui.putExtra("uitext","Android 8.0 系统静态广播的测试数据");
        sendBroadcast(intentui);
    }

    @Override
    protected void onResume() {
        super.onResume();
        //start();
        //updateui();

    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}

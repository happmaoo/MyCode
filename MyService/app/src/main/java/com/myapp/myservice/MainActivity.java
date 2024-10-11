package com.myapp.myservice;

import androidx.appcompat.app.AppCompatActivity;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Intent intent = new Intent("com.myapp.myservice");
        intent.setComponent(new ComponentName("com.myapp.myservice",
                "com.myapp.myservice.MyBroadcastReceiver"));
        intent.putExtra("message","我是来测 A 应用的Android 8.0 系统静态广播的测试数据");
        sendBroadcast(intent);
    }
}
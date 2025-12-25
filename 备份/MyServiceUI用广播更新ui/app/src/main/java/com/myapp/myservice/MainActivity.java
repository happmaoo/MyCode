package com.myapp.myservice;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 定义ui广播接收器
        BroadcastReceiver uiUpdateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String uitext = intent.getStringExtra("uitext");
                // 更新界面上的文字
                TextView textView = findViewById(R.id.text1);
                textView.setText(uitext);
            }
        };

        // 注册本地ui广播接收器
        LocalBroadcastManager.getInstance(this).registerReceiver(uiUpdateReceiver,
                new IntentFilter("UPDATE_UI"));

        Intent intent = new Intent("com.myapp.myservice");
        intent.setComponent(new ComponentName("com.myapp.myservice",
                "com.myapp.myservice.MyBroadcastReceiver"));
        intent.putExtra("uitext","我是来测 A 应用的Android 8.0 系统静态广播的测试数据");
        sendBroadcast(intent);


        //am broadcast -a com.myapp.myservice -n com.myapp.myservice/com.myapp.myservice.MyBroadcastReceiver --es uitext "我是来"
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 取消注册广播接收器
        //LocalBroadcastManager.getInstance(this).unregisterReceiver(uiUpdateReceiver);
    }
}


package com.myapp.livedata;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {


    Intent serviceIntent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        TextView textView = findViewById(R.id.textView_log);

        // 监听数据变化
        DataManager.getInstance().getLiveDataMessage().observe(this, newMessage -> {
            textView.setText(newMessage);
        });





        serviceIntent = new Intent(this, MyService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

    }

    @Override
    protected void onPause() {
        super.onPause();
        DataManager.getInstance().getLiveDataMessage().postValue("此消息来自 MainActivity.");
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();

    }





}

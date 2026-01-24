package com.myapp.livedata;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.util.Pair;
import androidx.lifecycle.Observer;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {


    Intent serviceIntent;
    TextView textView;
    Button btn;
    private Observer<Pair<String, String>> messageObserver; // 添加观察者变量


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        textView = findViewById(R.id.textView_log);
        btn = findViewById(R.id.btn_start);



        setupMessageObserver();

        serviceIntent = new Intent(this, MyService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DataManager.getInstance().sendMessage("Activity","我是Activity.");
            }
        });

    }

    @Override
    protected void onDestroy() {
        if (messageObserver != null) {
            DataManager.getInstance().getLiveDataMessage().removeObserver(messageObserver);
            Log.i("Activity", "onDestroy: removeObserver");
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();

    }

    @Override
    protected void onStop() {
        super.onStop();

    }

    // 设置消息观察者
    private void setupMessageObserver() {
        messageObserver = new Observer<Pair<String, String>>() {
            @Override
            public void onChanged(Pair<String, String> pair) {
                if (pair != null) {
                    String from = pair.first;
                    String content = pair.second;
                    //Log.i("TAG", "来自 " + from + " 的消息: " + content);

                    if ("Service".equals(from)) {
                        textView.setText(content);
                        Log.i("Activity", "收到消息: " + content);
                    }
                }
            }
        };

        // 使用observeForever而不是observe
        DataManager.getInstance().getLiveDataMessage().observeForever(messageObserver);
    }



}

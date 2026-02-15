package com.myapp.mymqtt;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.util.Pair;
import androidx.lifecycle.Observer;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;


import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends AppCompatActivity {


    Intent serviceIntent;
    TextView textView;
    Button btn_start,btn_serversettings,btn_connect;

    private Observer<Pair<String, String>> messageObserver; // 添加观察者变量

    MyMQTT myapp;
    MyMQTT application;


    private AutoCompleteTextView editText_cmd;


    @Override
    protected void onCreate(Bundle savedInstanceState) {


        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        // SharedPreferences 全局保存
        myapp = (MyMQTT) getApplicationContext();




        textView = findViewById(R.id.textView_log);
        btn_start = findViewById(R.id.btn_start);
        btn_serversettings = findViewById(R.id.btn_serversettings);
        btn_connect = findViewById(R.id.btn_connect);

        editText_cmd = findViewById(R.id.editText_cmd);



        String fontSizeStr = myapp.getString("fontsize", "12");
        if (fontSizeStr == null || fontSizeStr.trim().isEmpty()) {
            fontSizeStr = "12";  // 默认值
        }
        textView.setTextSize(Float.parseFloat(fontSizeStr));

        myapp.historyList = new ArrayList<>();
        // 设置适配器
        myapp.adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, myapp.historyList);
        editText_cmd.setAdapter(myapp.adapter);

        // 加载历史记录 (现在 historyList 已经实例化，addAll 不会报空指针)
        loadPrefs();



        serviceIntent = new Intent(this, MyService.class);



        btn_start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String cmd = editText_cmd.getText().toString().trim();
                if (!cmd.isEmpty()) {
                    // 发送消息
                    DataManager.getInstance().sendMessage("Activity", cmd);

                    // 1. 如果历史里没有，才添加
                    if (!myapp.historyList.contains(cmd)) {
                        myapp.historyList.add(0, cmd); // 插入到最前面

                        // 2. 【最关键的一步】重新绑定一次 Adapter
                        // 这样会强制 AutoCompleteTextView 刷新内部的缓存列表
                        myapp.adapter = new ArrayAdapter<>(MainActivity.this, android.R.layout.simple_list_item_1, myapp.historyList);
                        editText_cmd.setAdapter(myapp.adapter);

                        // 3. 立即保存到磁盘
                        savePrefs();
                    }
                }
            }
        });

        btn_serversettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent EditIntent = new Intent(MainActivity.this,settings.class);
                startActivity(EditIntent);
            }
        });



        btn_connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if(myapp.isRunning){
                    stopService(serviceIntent);
                    textView.setText("Stopped");
                    btn_connect.setText("Start");
                }else{
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                    btn_connect.setText("Stop");

                }
            }
        });


        // Activity：使用生命周期感知的 observe 自动注销
        DataManager.getInstance().getLiveDataMessage().observe(this, new Observer<Pair<String, String>>() {
            @Override
            public void onChanged(Pair<String, String> pair) {
                if (pair != null) {
                    String from = pair.first;
                    String content = pair.second;

                    if ("Service".equals(from)) {
                        textView.setText(content);
                        Log.i("Activity", "收到消息: " + content);
                    }
                }
            }
        });




        editText_cmd.setAdapter(myapp.adapter);

        editText_cmd.setOnTouchListener((v, event) -> {
            editText_cmd.showDropDown(); // 触摸即显示下拉列表
            return false;
        });

    }

    private void saveToHistory(String cmd) {
        // 检查是否已存在，避免重复
        if (!myapp.historyList.contains(cmd)) {
            myapp.historyList.add(0, cmd); // 新记录插到最前面
            // 限制历史数量，比如只留 10 条
            if (myapp.historyList.size() > 10) myapp.historyList.remove(10);

            // 通知适配器刷新
            myapp.adapter.notifyDataSetChanged();
        }
    }


    // 修改后的保存方法，不再需要参数，直接同步整张表
    private void savePrefs() {
        SharedPreferences prefs = getSharedPreferences("mqtt_history", MODE_PRIVATE);
        // 注意：StringSet 是无序的，如果对历史记录的“先后顺序”要求很高，
        // 后期建议换成 JSON 字符串存储，但目前这样够用了。
        Set<String> set = new HashSet<>(myapp.historyList);
        prefs.edit().putStringSet("history", set).apply();
    }

    // 读取本地
    private void loadPrefs() {
        SharedPreferences prefs = getSharedPreferences("mqtt_history", MODE_PRIVATE);
        Set<String> set = prefs.getStringSet("history", new HashSet<>());
        myapp.historyList.addAll(set);
        myapp.adapter.notifyDataSetChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(myapp.isRunning){
            btn_connect.setText("Stop");
        }
    }

    @Override
    protected void onDestroy() {
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
        savePrefs();

    }


}

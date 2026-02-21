package com.myapp.mymqtt;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;
import androidx.core.widget.NestedScrollView;
import androidx.lifecycle.Observer;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;


import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {


    Intent serviceIntent;
    TextView textView;
    Button btn_start,btn_settings,btn_connect,btn_cmds,btn_editcmds;
    RadioGroup radioGroup;
    ImageView imageView;
    NestedScrollView scrollView;
    LinearLayout Layout_cmds;
    ListView listview;
    EditText editText_cmds;

    private Observer<Pair<String, String>> messageObserver; // 添加观察者变量

    MyMQTT myapp;


    private AutoCompleteTextView editText_cmd;
    List<MyMQTT.ServerItem> serverList;
    MyMQTT.ServerItem cur_server;
    String[] cmdsItems;

    @Override
    protected void onCreate(Bundle savedInstanceState) {


        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        // SharedPreferences 全局保存
        myapp = (MyMQTT) getApplicationContext();


        textView = findViewById(R.id.textView_log);
        btn_start = findViewById(R.id.btn_start);
        btn_settings = findViewById(R.id.btn_settings);
        btn_connect = findViewById(R.id.btn_connect);
        btn_cmds = findViewById(R.id.btn_cmds);
        btn_editcmds = findViewById(R.id.btn_editcmds);

        radioGroup = findViewById(R.id.radiogroup);
        imageView = findViewById(R.id.imageView);
        editText_cmd = findViewById(R.id.editText_cmd);
        scrollView = findViewById(R.id.scrollView);
        listview = findViewById(R.id.listview);
        Layout_cmds = findViewById(R.id.Layout_cmds);
        editText_cmds = findViewById(R.id.editText_cmds);


        String fontSizeStr = myapp.getString("fontsize", "12");
        if (fontSizeStr == null || fontSizeStr.trim().isEmpty()) {
            fontSizeStr = "12";  // 默认值
        }
        textView.setTextSize(Float.parseFloat(fontSizeStr));



        serverList = myapp.getServerList();

        String selected = myapp.getString("server","");

        cur_server = getServer(myapp.getString("server",""));




        for (int i = 0; i < serverList.size(); i++) {
            MyMQTT.ServerItem server = serverList.get(i);
            // 创建RadioButton
            RadioButton radioButton = new RadioButton(this);
            radioButton.setId(View.generateViewId()); // 生成唯一ID
            radioButton.setText(server.name); // 显示服务器名称
            radioButton.setTag(server); // 可以把整个server对象作为tag保存
            // 添加到RadioGroup
            radioGroup.addView(radioButton);

            // 设置点击事件
            radioButton.setOnClickListener(v -> {
                RadioButton rb = (RadioButton) v;
                MyMQTT.ServerItem selectedServer = (MyMQTT.ServerItem) rb.getTag();
                myapp.setString("server",selectedServer.name);

                //重新加载界面
                recreate();
            });

            if(selected.equals(server.name)){
                radioButton.setChecked(true);
            }
        }



        // 添加动态按钮。
        if(cur_server != null && !cur_server.send_command.isEmpty()){

            // 正则表达式：匹配 name=//(.*?)//cmd=//(.*?)//
            String regex = "name=//(.*?)//cmd=//(.*?)//";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(cur_server.send_command);

            int groupCount = 0;
            while (matcher.find()) {
                groupCount++;
                String name = matcher.group(1);
                String cmd = matcher.group(2);


                LinearLayout buttonContainer = findViewById(R.id.Layout_btns);
                Button dynamicButton = new Button(this);
                dynamicButton.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));
                dynamicButton.setText(name);
                dynamicButton.setEnabled(true);
                dynamicButton.setAllCaps(false);
                dynamicButton.setId(View.generateViewId());
                dynamicButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if(myapp.isRunning){
                        DataManager.getInstance().sendMessage("Activity", cmd);
                        }
                    }
                });
                buttonContainer.addView(dynamicButton);
            }


        }








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
                editText_cmd.setText("");
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

        btn_settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent EditIntent = new Intent(MainActivity.this,settings.class);
                startActivityForResult(EditIntent,1001);
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
                        if ("data_image".equals(content)) {
                            scrollView.setVisibility(View.GONE);
                            imageView.setVisibility(View.VISIBLE);

                            Bitmap bitmap = BitmapFactory.decodeByteArray(myapp.imageData, 0, myapp.imageData.length);
                            imageView.setImageBitmap(bitmap);

                        }else{
                            scrollView.setVisibility(View.VISIBLE);
                            imageView.setVisibility(View.GONE);
                            textView.setText(content);
                            Log.i("Activity", "收到消息: " + content);
                        }
                    }
                }
            }
        });




        editText_cmd.setAdapter(myapp.adapter);

        editText_cmd.setOnTouchListener((v, event) -> {
            editText_cmd.showDropDown(); // 触摸即显示下拉列表
            return false;
        });

        btn_cmds.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Layout_cmds.getVisibility() == View.VISIBLE) {
                    Layout_cmds.setVisibility(View.GONE);
                    scrollView.setVisibility(View.VISIBLE);
                    imageView.setVisibility(View.GONE);
                } else {
                    Layout_cmds.setVisibility(View.VISIBLE);
                    scrollView.setVisibility(View.GONE);
                    imageView.setVisibility(View.GONE);
                }
            }
        });



        myapp.cmds = myapp.loadCommands();


        String[] items = myapp.cmds.keySet().toArray(new String[0]);


        String[] displayItems = new String[myapp.cmds.size()];
        int index = 0;
        for (Map.Entry<String, String> entry : myapp.cmds.entrySet()) {
            displayItems[index] = entry.getKey() + ": " + entry.getValue();
            index++;
        }

        ArrayAdapter<String> arrayAdapter = new ArrayAdapter<>(
                this,
                R.layout.listview,
                displayItems
        );

        listview.setAdapter(arrayAdapter);


        listview.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

                String selectedItem = (String) parent.getItemAtPosition(position);
                String[] parts = selectedItem.split(":", 2);
                String commandName = parts[0].trim();
                String fullCommand = myapp.cmds.get(commandName);

                if(myapp.isRunning){
                    DataManager.getInstance().sendMessage("Activity", fullCommand);
                }

                textView.setText("RUN:"+parts[1] + "...");

                Layout_cmds.setVisibility(View.GONE);
                scrollView.setVisibility(View.VISIBLE);
                imageView.setVisibility(View.GONE);
            }
        });
        //Log.i("aaa", "Command: " + myapp.findCommandByName(myapp.cmds,"ls"));

        btn_editcmds.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listview.getVisibility() == View.VISIBLE) {
                    listview.setVisibility(View.GONE);
                    editText_cmds.setVisibility(View.VISIBLE);
                    editText_cmds.setText(myapp.mapToString(myapp.loadCommands()));
                }else{
                    listview.setVisibility(View.VISIBLE);
                    editText_cmds.setVisibility(View.GONE);

                    myapp.cmds = myapp.parseCommands(editText_cmds.getText().toString());
                    myapp.saveCommands(myapp.cmds);
                    refreshListView();
                }

            }
        });




    }

    private void refreshListView() {
        cmdsItems = new String[myapp.cmds.size()];
        int i = 0;
        for (Map.Entry<String, String> entry : myapp.cmds.entrySet()) {
            cmdsItems[i] = entry.getKey() + ": " + entry.getValue();
            i++;
        }

        // 创建新Adapter并设置
        ArrayAdapter<String> newAdapter = new ArrayAdapter<>(
                MainActivity.this,
                R.layout.listview,
                cmdsItems
        );
        listview.setAdapter(newAdapter);
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

    // 获取某个服务器的信息
    private MyMQTT.ServerItem getServer(String name) {

        if (name == null || name.isEmpty() || serverList == null) {
            return null;
        }

        for (MyMQTT.ServerItem server : serverList) {
            if (server.name.equals(name)) {
                return server;  // 返回找到的服务器
            }
        }
        return null;  // 没找到返回null
    }

    // settings 界面回调
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1001) {
            if (resultCode == RESULT_OK && data != null) {
                boolean settings_ok = data.getBooleanExtra("settings_ok", false);
                if (settings_ok) {
                    Toast.makeText(this,"已保存.",Toast.LENGTH_SHORT).show();
                    recreate();
                }
            }
        }
    }





}

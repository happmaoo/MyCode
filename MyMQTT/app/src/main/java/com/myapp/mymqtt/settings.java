package com.myapp.mymqtt;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class settings extends AppCompatActivity {


    private EditText editTextServers;
    MyMQTT app;
    List<MyMQTT.ServerItem> serverList;
    Button button_clearhistory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);


        editTextServers = findViewById(R.id.editTextServers);
        button_clearhistory = findViewById(R.id.button_clearhistory);

        // 获取Application实例
        app = MyMQTT.getInstance();

        serverList = app.getServerList();

        loadServersToEditText();



        button_clearhistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences prefs = getSharedPreferences("mqtt_history", MODE_PRIVATE);
                prefs.edit().clear().apply(); // apply() 比 commit() 更推荐，异步不卡顿
                app.historyList.clear();

            }
        });


    }



    /**
     * 加载服务器列表到EditText
     */
    private void loadServersToEditText() {
        // 从SharedPreferences获取服务器列表
        serverList = app.getServerList();

        if (serverList.isEmpty()) {
            // 如果没有数据，显示默认模板
            String defaultText =
                    "name:server1\n" +
                            "url:qq.com\n" +
                            "port:\n" +
                            "username:\n" +
                            "password:\n" +
                            "keepalive:\n" +
                            "topic_send:\n" +
                            "topic_receive:\n" +
                            "send_command:name=//n1//cmd=//cmd1//name=//n2//cmd=//cmd2//\n" +
                            "comment:\n" +
                            "----------\n" +
                            "name:server2\n" +
                            "url:baidu.com\n" +
                            "port:";

            editTextServers.setText(defaultText);

            saveServersFromEditText();

            Toast.makeText(this, "显示默认模板", Toast.LENGTH_SHORT).show();
        } else {
            // 如果有数据，显示已有数据
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < serverList.size(); i++) {
                MyMQTT.ServerItem server = serverList.get(i);

                // 显示服务器信息
                sb.append("name:").append(server.name).append("\n");
                sb.append("url:").append(server.url).append("\n");
                sb.append("port:").append(server.port).append("\n");
                sb.append("username:").append(server.username).append("\n");
                sb.append("password:").append(server.password).append("\n");
                sb.append("keepalive:").append(server.keepalive).append("\n");
                sb.append("topic_send:").append(server.topic_send).append("\n");
                sb.append("topic_receive:").append(server.topic_receive).append("\n");
                sb.append("send_command:").append(server.send_command).append("\n");
                sb.append("comment:").append(server.comment).append("\n");

                // 添加分隔线（除了最后一个）
                if (i < serverList.size() - 1) {
                    sb.append("----------\n");
                }
            }

            editTextServers.setText(sb.toString());
            //Toast.makeText(this, "已加载 " + serverList.size() + " 个服务器", Toast.LENGTH_SHORT).show();
        }
    }



    /**
     * 从EditText保存服务器列表
     */
    private void saveServersFromEditText() {
        String content = editTextServers.getText().toString().trim();

        if (TextUtils.isEmpty(content)) {
            Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            List<MyMQTT.ServerItem> newServerList = parseServerList(content);

            if (newServerList.isEmpty()) {
                Toast.makeText(this, "没有解析到有效的服务器数据", Toast.LENGTH_SHORT).show();
                return;
            }

            // 保存到SharedPreferences
            app.saveServerList(newServerList);

            // 更新本地serverList引用
            serverList = newServerList;

            //Toast.makeText(this, "成功保存 " + serverList.size() + " 个服务器", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 解析EditText中的内容为ServerItem列表
     */
    private List<MyMQTT.ServerItem> parseServerList(String content) {
        List<MyMQTT.ServerItem> serverList = new ArrayList<>();

        // 按分隔符分割
        String[] serverBlocks;
        if (content.contains("----------")) {
            serverBlocks = content.split("----------");
        } else {
            // 如果没有分隔符，把整个内容当作一个服务器
            serverBlocks = new String[]{content};
        }

        for (String block : serverBlocks) {
            // 去除每块的前后空白，并跳过空块
            block = block.trim();
            if (block.isEmpty()) {
                continue;
            }

            // 解析服务器信息
            MyMQTT.ServerItem server = parseServerBlock(block);

            // 检查是否有效（至少有一个字段不为空）
            if (!server.name.isEmpty()) {
                serverList.add(server);
            }
        }

        return serverList;
    }

    /**
     * 解析单个服务器块
     */
    private MyMQTT.ServerItem parseServerBlock(String block) {
        String[] lines = block.split("\n");
        String name = "", url = "",port = "",username="",password="",keepalive="",topic_send="",topic_receive="",send_command="",comment="";

        for (String line : lines) {
            line = line.trim();
            if (line.contains(":")) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();

                    switch(key) {
                        case "name":
                            name = value;
                            break;
                        case "url":
                            url = value;
                            break;
                        case "port":
                            port = value;
                            break;
                        case "username":
                            username = value;
                            break;
                        case "password":
                            password = value;
                            break;
                        case "keepalive":
                            keepalive = value;
                            break;
                        case "topic_send":
                            topic_send = value;
                            break;
                        case "topic_receive":
                            topic_receive = value;
                            break;
                        case "send_command":
                            send_command = value;
                            break;
                        case "comment":
                            comment = value;
                            break;
                    }
                }
            }
        }

        return new MyMQTT.ServerItem(name, url, port,username,password,keepalive,topic_send,topic_receive,send_command,comment);
    }


    // 获取某个服务器的信息
    private void getServer(String name){
        for (MyMQTT.ServerItem server : serverList) {
            if (server.name.equals(name)) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("找到服务器")
                        .setMessage("名称：" + server.name + "\nURL：" + server.url + "\n端口：" + server.port)
                        .setPositiveButton("确定", null)
                        .show();
                return;
            }
        }
    }

    /**
     * 添加一个新的服务器模板
     */
    private void addNewServerTemplate() {
        String currentContent = editTextServers.getText().toString().trim();

        StringBuilder newContent = new StringBuilder();

        if (!currentContent.isEmpty()) {
            newContent.append(currentContent);

            // 如果最后没有换行，添加换行
            if (!currentContent.endsWith("\n")) {
                newContent.append("\n");
            }

            // 添加分隔符
            newContent.append("---\n");
        }

        // 添加新的服务器模板
        newContent.append("name:新服务器\n");
        //...

        editTextServers.setText(newContent.toString());

        // 将光标移到新内容的末尾
        editTextServers.setSelection(editTextServers.length());
    }


    @Override
    protected void onPause() {
        saveServersFromEditText();


        super.onPause();

    }

    @Override
    public void onBackPressed() {
        // 用户按下返回键时保存并返回
        saveServersFromEditText();

        Intent resultIntent = new Intent();
        resultIntent.putExtra("settings_ok", true);
        setResult(RESULT_OK, resultIntent);

        super.onBackPressed(); // 会调用finish()
    }

}

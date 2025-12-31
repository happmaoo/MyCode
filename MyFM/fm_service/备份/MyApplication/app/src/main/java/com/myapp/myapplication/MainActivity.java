package com.myapp.myapplication;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;


import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;

public class MainActivity extends AppCompatActivity implements FMClient.MessageCallback {

    private FMClient fmClient;
    private TextView statusTextView;
    private TextView logTextView;
    private Button connectButton;
    private Button tuneButton;
    private Button quitButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // 假设您的布局文件名为 activity_main.xml

        // 初始化 UI 元素
        statusTextView = findViewById(R.id.status_text);
        logTextView = findViewById(R.id.log_text);
        connectButton = findViewById(R.id.connect_button);
        tuneButton = findViewById(R.id.tune_button);
        quitButton = findViewById(R.id.quit_button);

        // 初始化 FMClient
        fmClient = new FMClient(this);

        // 设置按钮点击事件
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fmClient.connect();
            }
        });

        tuneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 默认发送 TUNE 98.7 命令
                fmClient.sendCommand("TUNE 93");
            }
        });

        quitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 发送 QUIT 命令，根据您的 C 代码，这将导致服务端进程退出
                fmClient.sendCommand("QUIT");
            }
        });

        // 应用程序启动时自动连接
        //fmClient.connect();
    }

    // 实现 FMClient.MessageCallback 接口的方法

    /**
     * 处理从服务端接收到的数据（例如 PUSH 消息）。
     */
    @Override
    public void onMessageReceived(final String message) {
        // Socket 回调在后台线程，更新 UI 必须回到主线程
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // 将接收到的消息追加到日志视图
                logTextView.append(message);
                // 限制日志长度以避免内存问题
                if (logTextView.getText().length() > 5000) {
                    logTextView.setText(logTextView.getText().subSequence(
                            logTextView.getText().length() - 4000,
                            logTextView.getText().length()
                    ));
                }
            }
        });
    }

    /**
     * 处理连接状态的变化。
     */
    @Override
    public void onStatusChanged(final String status) {
        // 更新 UI 必须回到主线程
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusTextView.setText("状态: " + status);
                Log.i("MainActivity", "状态更新: " + status);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 确保 Activity 销毁时关闭 Socket 连接，释放资源
        if (fmClient != null) {
            fmClient.close();
        }
    }
}
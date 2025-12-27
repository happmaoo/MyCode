package com.myapp.handler;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.TextView;

import java.lang.ref.WeakReference;

public class MainActivity extends AppCompatActivity {
    private TextView tvDisplay;
    private Messenger mServiceMessenger = null; // 服务的地址
    private boolean isBound = false;

    // 1. 定义一个静态内部类，避免持有外部 Activity 的隐式引用
    private static class ClientHandler extends Handler {
        private final WeakReference<MainActivity> mActivity;

        ClientHandler(MainActivity activity) {
            super(Looper.getMainLooper());
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            MainActivity activity = mActivity.get();
            if (activity != null) {
                // 安全地更新 UI
                activity.tvDisplay.setText("服务说: " + msg.obj);
            }
        }
    }

    // 2. 初始化时使用这个安全的 Handler
    private final Messenger clientMessenger = new Messenger(new ClientHandler(this));

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // 2. 拿到服务的 Messenger
            mServiceMessenger = new Messenger(service);
            isBound = true;
            tvDisplay.setText("已连接到服务");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceMessenger = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvDisplay = findViewById(R.id.textView_log);

        // 绑定并启动服务
        Intent intent = new Intent(this, MyForegroundService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);


        // 3. 按钮点击：给服务发信
        findViewById(R.id.btn_start).setOnClickListener(v -> {

            ContextCompat.startForegroundService(this, intent);

            if (isBound && mServiceMessenger != null) {
                try {
                    Message msg = Message.obtain();
                    msg.obj = "你好 Service，我是 Activity！";

                    // 【关键点】告诉服务：如果你要回信，请发给这个 Messenger (clientMessenger)
                    msg.replyTo = clientMessenger;

                    mServiceMessenger.send(msg);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) unbindService(connection);
    }
}
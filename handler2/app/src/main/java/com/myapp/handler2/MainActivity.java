package com.myapp.handler2;

import androidx.appcompat.app.AppCompatActivity;

import android.content.ComponentName;
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





public class MainActivity extends AppCompatActivity {
    private Messenger mServiceMessenger; // 往 Service 发消息
    private TextView tvStatus;

    // 1. 接收来自 Service 的回复
    private final Messenger mActivityMessenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msgFromService) {
            if (msgFromService.what == 2) {
                tvStatus.setText("Service 回复：" + msgFromService.arg1 + msgFromService.obj.toString());
            }
        }
    });

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mServiceMessenger = new Messenger(service); // 拿到 Service 的信使
        }
        @Override
        public void onServiceDisconnected(ComponentName name) { mServiceMessenger = null; }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvStatus = findViewById(R.id.tvResult);

        bindService(new Intent(this, MyService.class), connection, BIND_AUTO_CREATE);

        findViewById(R.id.btnSend).setOnClickListener(v -> {
            if (mServiceMessenger != null) {
                Message msg = Message.obtain();
                msg.what = 1;
                msg.obj = "I am from activity.";
                msg.replyTo = mActivityMessenger; // 关键：把自己的“地址”告诉 Service
                try {
                    mServiceMessenger.send(msg);
                } catch (RemoteException e) { e.printStackTrace(); }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(connection);
    }
}
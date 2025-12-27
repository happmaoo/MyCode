package com.myapp.handler;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class MyForegroundService extends Service {
    private static final String CHANNEL_ID = "ForegroundServiceChannel";

    // 1. 服务端的邮递员：处理来自 Activity 的信件
    private final Handler serviceHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msgFromActivity) {
            // 收到 Activity 的信
            String content = (String) msgFromActivity.obj;
            Log.d("Service", "收到 Activity 的消息: " + content);

            // 2. 回信给 Activity (通过信件自带的“回邮地址”)
            Messenger replyToMessenger = msgFromActivity.replyTo;
            if (replyToMessenger != null) {
                Message replyMsg = Message.obtain();
                replyMsg.obj = "【回信】我是前台服务，我已收到你的：" + content;
                try {
                    replyToMessenger.send(replyMsg);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }
    };

    // 3. 把 Handler 包装成 Messenger（对外窗口）
    private final Messenger serviceMessenger = new Messenger(serviceHandler);


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 1. 创建通知（徽章），这是前台服务的必须要求
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("邮局运行中")
                .setContentText("正在后台分拣信件...")
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .build();

        // 2. 变成前台服务
        startForeground(1, notification);
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // 当 Activity 绑定时，返回这个 Messenger 的“地址”
        return serviceMessenger.getBinder();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "邮局频道", NotificationManager.IMPORTANCE_DEFAULT);
            getSystemService(NotificationManager.class).createNotificationChannel(serviceChannel);
        }
    }
}
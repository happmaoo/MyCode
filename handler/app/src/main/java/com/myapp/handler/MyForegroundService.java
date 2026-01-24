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
    // Handler 就是管家：外人只能把信件（Message）交给管家

    private final Handler serviceHandler = new Handler(Looper.getMainLooper()) {


        // 你在 Activity 里调用 mServiceMessenger.send(msg) 这就像你把一封信投进了邮筒。
        // 管理员发现收件人是你的 UiHandler，就会自动触发 handleMessage 方法

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
    // serviceMessenger (在服务里)： 这是本体。就像是 Service 在自己办公室里安装的一台固定基站。它连接着 serviceHandler（服务端的管家）。
    // mServiceMessenger (在 Activity 里)： 这是副本（分机）。当 Activity 绑定服务成功时，Service 把“基站”的频道参数发给了 Activity，Activity 据此创建了一台一模一样的对讲机。

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
        // Service 说：这是我的对外窗口，拿着！
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
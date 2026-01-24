package com.myapp.livedata;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.util.Pair;
import androidx.lifecycle.Observer;

public class MyService extends Service {

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "my_channel";
    private static final String TAG = "Service";

    private Observer<Pair<String, String>> messageObserver; // 添加观察者变量

    @Override
    public void onCreate() {
        setupMessageObserver();
        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }


    @Override
    public void onDestroy() {
        if (messageObserver != null) {
            DataManager.getInstance().getLiveDataMessage().removeObserver(messageObserver);
            Log.i("Service", "onDestroy: removeObserver");
        }
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {



        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Service Running")
                .setContentText("Doing important work...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        // 发送数据
        DataManager.getInstance().sendMessage("Service","我是 Service.");




        return START_STICKY;
    }


    // Service: 设置消息观察者,需手动注册/注销
    private void setupMessageObserver() {
        messageObserver = new Observer<Pair<String, String>>() {
            @Override
            public void onChanged(Pair<String, String> pair) {
                if (pair != null) {
                    String from = pair.first;
                    String content = pair.second;
                    //Log.i(TAG, "来自 " + from + " 的消息: " + content);

                    if ("Service".equals(from)) {
                        // 处理来自服务的消息
                    } else if ("Activity".equals(from)) {
                        // 处理来自Activity的消息
                        Log.i(TAG, "收到消息: " + content);
                    }
                }
            }
        };
        // 使用observeForever
        DataManager.getInstance().getLiveDataMessage().observeForever(messageObserver);
    }




    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "My Foreground Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Channel description");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }




}

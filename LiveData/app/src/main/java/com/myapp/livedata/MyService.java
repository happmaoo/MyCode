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
import androidx.lifecycle.Observer;

public class MyService extends Service {

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "my_channel";
    private static final String TAG = "MyService";

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
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

        // 发送数据 还可以在DataManager类中定义消息类型枚举，用来区分不同消息类型和来源
        DataManager.getInstance().getLiveDataMessage().postValue("此消息来自 MyService.");



        // 监听数据变化 - 使用 observeForever
        DataManager.getInstance().getLiveDataMessage().observeForever(new Observer<String>() {
            @Override
            public void onChanged(String newMessage) {
                Log.i(TAG, "收到:" + newMessage);
            }
        });


        return START_STICKY;
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

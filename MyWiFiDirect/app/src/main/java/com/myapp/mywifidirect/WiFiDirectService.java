package com.myapp.mywifidirect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import java.util.ArrayList;
import java.util.List;

public class WiFiDirectService extends Service {
    public static final String CHANNEL_ID = "WiFiDirectChannel";
    private final IBinder binder = new LocalBinder();

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;


    // 添加这两个方法给 Activity 调用
    public WifiP2pManager getManager() {
        return manager;
    }

    public WifiP2pManager.Channel getChannel() {
        return channel;
    }


    public class LocalBinder extends Binder {
        WiFiDirectService getService() { return WiFiDirectService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WiFi Direct 已启动")
                .setContentText("正在保持连接状态...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();

        startForeground(1, notification);

        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);
    }

    public void discoverPeers(WifiP2pManager.ActionListener listener) {
        manager.discoverPeers(channel, listener);
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "WiFi Direct Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(serviceChannel);
        }
    }


    @Override
    public void onDestroy() {
        // 1. 彻底断开 P2P 组
        if (manager != null && channel != null) {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.d("P2P", "物理连接已断开");
                }
                @Override
                public void onFailure(int reason) {
                    // 如果已经是断开状态会返回失败，通常可以忽略
                }
            });
        }

        stopForeground(true);
        super.onDestroy();
    }
}
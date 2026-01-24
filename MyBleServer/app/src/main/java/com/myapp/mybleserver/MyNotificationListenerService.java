package com.myapp.mybleserver;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.os.Bundle;
import android.util.Log;

import java.util.List;

public class MyNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "MyNotificationListener";

    MyApp app;
    String log_noti = "";
    List<String> perapp;


    // --- 新增：用于连接 GattService ---
    private DeviceAPI gattBinding;
    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            gattBinding = (DeviceAPI) service;
            //Log.d(TAG, "成功绑定到 GattService");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            gattBinding = null;
        }
    };



    // 命令监听器服务去主动解绑 GattService 不然停不掉服务
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_FORCE_UNBIND".equals(intent.getAction())) {
            if (gattBinding != null) {
                try {
                    unbindService(connection); // 释放对 GattService 的绑定
                    gattBinding = null;
                    Log.d(TAG, "已手动释放对 GattService 的绑定");
                } catch (Exception e) {
                    Log.e(TAG, "解绑失败: " + e.getMessage());
                }
            }
        }
        // 必须返回这个，确保系统不会在内存不足杀掉后以错误状态重启
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // SharedPreferences
        app = (MyApp) getApplication();
        perapp = app.readMultilineText("apps","");

        // --- 新增：启动并绑定 GattService ---
        Intent intent = new Intent(this, GattService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);

    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        // 解绑服务防止内存泄漏
        if (gattBinding != null) {
            unbindService(connection);
        }
    }

    // 当通知被发布或更新时触发
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        if (sbn == null) return;

        String packageName = sbn.getPackageName();
        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        String title = extras.getCharSequence(Notification.EXTRA_TITLE, "").toString();
        String text = extras.getCharSequence(Notification.EXTRA_TEXT, "").toString();

        Log.d(TAG, "Notification Posted from: " + packageName);
        Log.d(TAG, "Title: " + title + ", Text: " + text);

        boolean isMatch = perapp.contains(packageName.trim());
        if(isMatch){
            if (gattBinding != null) {

                String[] packageName2= packageName.split("\\.");
                String packageNameShort = packageName2[packageName2.length - 1];

                String notif = "notif:[" + packageNameShort + ", t:" + title + "," + text + "]";
                gattBinding.setMyCharacteristicValue(notif);

                Log.d(TAG, "已通过 BLE 发送 " + packageName + " 的 Notification 通知数据");
            } else {
                Log.e(TAG, "GattService 未绑定，无法发送");
            }

        }
    }


    // 服务连接就绪
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "Notification Listener Connected");

        // 获取所有活跃通知
        StatusBarNotification[] activeNotifications = getActiveNotifications();
        if (activeNotifications != null) {
            for (StatusBarNotification sbn : activeNotifications) {
                Log.d(TAG, "Active Notification: " + sbn.getPackageName());
            }
        }
    }
}

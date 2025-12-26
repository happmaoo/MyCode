package com.myapp.handler;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.lang.ref.WeakReference;

public class MyService extends Service {
    // 1. 处理来自 Activity 的消息
    private final Handler serviceHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msgFromActivity) {
            if (msgFromActivity.what == 1) {


                Log.i("收到 activity 消息",msgFromActivity.obj.toString());

                // 收到消息，获取回复用的 Messenger
                Messenger replyMessenger = msgFromActivity.replyTo;

                // 模拟耗时任务并回传
                new Thread(() -> {
                    for (int i = 1; i <= 100; i++) {
                        try { Thread.sleep(10); } catch (InterruptedException e) {}

                        // 回发消息给 Activity
                        Message msgToActivity = Message.obtain();
                        msgToActivity.what = 2;
                        msgToActivity.arg1 = i * 1; // 模拟进度
                        msgToActivity.obj = " % 下载中";
                        try {
                            replyMessenger.send(msgToActivity);
                        } catch (RemoteException e) { e.printStackTrace(); }
                    }
                }).start();
            }
        }
    };

    // 2. 将 Messenger 暴露给 Activity
    private final Messenger messenger = new Messenger(serviceHandler);

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }
}
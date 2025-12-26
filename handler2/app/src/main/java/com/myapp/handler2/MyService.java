package com.myapp.handler2;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

public class MyService extends Service {

 /*
 1. 处理来自 Activity 的消息
    获取主线程（UI线程）的 Looper

    Handler	邮递员
    Looper	邮局
    Message	信件
    handleMessage 处理信件阅读

    方式1：在当前线程创建 Handler（必须有Looper）
    Handler handler1 = new Handler();
    如果当前线程没有 Looper会崩溃！

    方式2：明确指定在主线程（UI线程
    Handler handler = new Handler(Looper.getMainLooper());
*/


    private final Handler serviceHandler = new Handler(Looper.getMainLooper()) {

        @Override
        public void handleMessage(Message msgFromActivity) {
            if (msgFromActivity.what == 1) {


                Log.i("收到 activity 消息",msgFromActivity.obj.toString());

                // 收到消息，获取回复用的 Messenger (从对方来信中取出回信地址)
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
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
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
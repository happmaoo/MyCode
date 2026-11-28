package com.myapp.mqtt.base;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * 给 shell / 自动化用的启停开关（exported，需要口令）：
 *
 *   su -c "am broadcast -a com.myapp.mymqtt.CONTROL --es action stop  --es token mymqtt -p com.myapp.mymqtt"
 *   su -c "am broadcast -a com.myapp.mymqtt.CONTROL --es action start --es token mymqtt -p com.myapp.mymqtt"
 *
 * stop = 软退出：
 *   1) 先在后台线程把 offline presence（retained）+ MQTT DISCONNECT 发完（走 goAsync 的窗口，不被提前回收）
 *   2) 主线程关掉所有界面、停掉前台服务（通知栏与 wakelock 随 onDestroy 一起收掉）
 * 之后进程里没有 Activity / 前台服务 / 定时器，系统很快回收，观感就是彻底退出。
 */
public class ControlReceiver extends BroadcastReceiver {

    public static final String ACTION_CONTROL = "com.myapp.mymqtt.CONTROL";
    public static final String EXTRA_ACTION = "action";     // start / stop
    public static final String EXTRA_TOKEN = "token";       // 必须等于 P.ctrlToken()，默认 mymqtt

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        final Context app = context.getApplicationContext();
        String want = intent.getStringExtra(EXTRA_ACTION);
        if (want == null) want = "";
        String token = intent.getStringExtra(EXTRA_TOKEN);
        if (!P.ctrlToken(app).equals(token)) {
            LogFile.write("[控制广播] 口令不对，忽略 action=" + want);
            return;
        }

        if ("stop".equalsIgnoreCase(want)) {
            LogFile.write("[控制广播] stop");
            // goAsync：让后台线程拿到约 10s 的存活窗口，足够 presence + DISCONNECT 走完
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    MqttCore.get().disconnectBlocking();
                } catch (Exception ignore) {
                }
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        MainActivity.closeAll();          // 收掉界面（没有界面时是空操作）
                        app.stopService(new Intent(app, MqttService.class));   // 前台通知、wakelock 在 onDestroy 里一起放
                    } catch (Exception ignore) {
                    }
                    try {
                        pr.finish();
                    } catch (Exception ignore) {
                    }
                });
            }, "ctrl-stop").start();
            return;
        }

        if ("start".equalsIgnoreCase(want)) {
            LogFile.write("[控制广播] start");
            Intent i = new Intent(app, MqttService.class).setAction(MqttService.ACTION_CONNECT);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        }
    }
}

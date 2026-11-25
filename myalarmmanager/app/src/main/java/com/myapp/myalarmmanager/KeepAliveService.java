package com.myapp.myalarmmanager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

/** 常驻前台服务：只负责挂一条通知、抬高进程优先级，定时仍由 AlarmManager 负责 */
public class KeepAliveService extends Service {

    static final String CHANNEL = "alarmrunner";
    static final int NOTI_ID = 1001;
    private static volatile boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel(this);
        startForeground(NOTI_ID, buildNotification(this));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 被系统回收后尽量拉起
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 服务在跑就刷新一下通知文案（下次触发时间变了） */
    public static void refresh(Context ctx) {
        if (!running) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTI_ID, buildNotification(ctx));
    }

    public static void start(Context ctx) {
        tryStart(ctx, "ui");
    }

    /** 尝试拉起服务；失败时把原因写进运行日志，不再静默吞掉 */
    public static boolean tryStart(Context ctx, String src) {
        if (running) {
            refresh(ctx);
            return true;
        }
        Intent i = new Intent(ctx, KeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            return true;
        } catch (Exception e) {
            ScriptRunner.mark(ctx.getFilesDir().getAbsolutePath(),
                    "FGS start failed(" + src + "): " + e);
            return false;
        }
    }

    public static boolean isRunning() {
        return running;
    }

    private static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "定时任务", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setDescription("AlarmRunner 定时任务运行状态");
        nm.createNotificationChannel(ch);
    }

    private static Notification buildNotification(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(Cfg.PREFS, Context.MODE_PRIVATE);
        long next = p.getLong(Cfg.KEY_NEXT_AT, 0);
        boolean loop = p.getBoolean(Cfg.KEY_LOOP, false);

        String text;
        if (next > 0 && loop) {
            text = "循环中 · 下次 " + ScriptRunner.fmt(next);
        } else if (next > 0) {
            text = "已排定一次 · " + ScriptRunner.fmt(next);
        } else {
            text = "待机中，未排定任务";
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, 2, open, f);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(ctx, CHANNEL);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("MyAlarmManager")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_LOW);
        }
        return b.build();
    }
}

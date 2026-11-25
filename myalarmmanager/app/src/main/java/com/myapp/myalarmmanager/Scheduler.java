package com.myapp.myalarmmanager;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** 统一管理闹钟的排定 / 取消 / 续排 */
public class Scheduler {

    private static final int RC_TRIGGER = 0;
    private static final int RC_SHOW = 1;
    private static final long MIN_INTERVAL = 5;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Cfg.PREFS, Context.MODE_PRIVATE);
    }

    private static int flags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            f |= PendingIntent.FLAG_IMMUTABLE;
        }
        return f;
    }

    private static PendingIntent triggerPi(Context c) {
        Intent i = new Intent(c, AlarmTriggerReceiver.class);
        i.setAction(Cfg.ACTION_TRIGGER);
        return PendingIntent.getBroadcast(c, RC_TRIGGER, i, flags());
    }

    /** 排定一次触发（会覆盖之前排的那条，天然去重） */
    public static void schedule(Context c, long atMs, boolean strong) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = triggerPi(c);

        if (strong) {
            Intent show = new Intent(c, MainActivity.class);
            show.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent showPi = PendingIntent.getActivity(c, RC_SHOW, show, flags());
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(atMs, showPi), pi);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, atMs, pi);
        }

        prefs(c).edit().putLong(Cfg.KEY_NEXT_AT, atMs).commit();
    }

    private static void prune(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(triggerPi(c));
        prefs(c).edit().putLong(Cfg.KEY_NEXT_AT, 0).commit();
    }

    /** 取消所有排定，并关掉循环 */
    public static void stop(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(triggerPi(c));
        prefs(c).edit()
                .putLong(Cfg.KEY_NEXT_AT, 0)
                .putBoolean(Cfg.KEY_LOOP, false)
                .commit();
    }

    /** 取下一个对齐边界（如 10 分钟 -> :00 :10 :20；5 分钟 -> :00 :05 :10） */
    public static long nextBoundary(long now, long intervalSec) {
        long step = Math.max(intervalSec, MIN_INTERVAL) * 1000L;
        long next = ((now / step) + 1) * step;
        // 贴着边界（1.5 秒内）就顺延一格，避免刚启动就立刻炸一轮
        if (next - now < 1500L) next += step;
        return next;
    }

    /** 开始跑：loop=true 就循环，false 只跑一次；第一轮立即触发，不等延迟 */
    public static void start(Context c, boolean loop) {
        SharedPreferences p = prefs(c);
        p.edit().putBoolean(Cfg.KEY_LOOP, loop).commit();

        long interval = p.getLong(Cfg.KEY_INTERVAL, Cfg.DEFAULT_INTERVAL);
        if (interval < MIN_INTERVAL) interval = MIN_INTERVAL;

        if (loop) {
            // 首轮也等边界，保证 :00 :10 :20 这种整点节奏
            schedule(c, nextBoundary(System.currentTimeMillis(), interval),
                    p.getBoolean(Cfg.KEY_STRONG, false));
        } else {
            prune(c);
        }
    }

    /** 强制打开循环（不可见入口用） */
    public static void startLoop(Context c) {
        start(c, true);
    }

    /** 只要有循环开关就保证闹钟存在（幂等，可任意频繁调用） */
    public static void ensureLoop(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean(Cfg.KEY_LOOP, false)) return;

        long now = System.currentTimeMillis();
        long next = p.getLong(Cfg.KEY_NEXT_AT, 0);
        long interval = p.getLong(Cfg.KEY_INTERVAL, Cfg.DEFAULT_INTERVAL);
        if (interval < MIN_INTERVAL) interval = MIN_INTERVAL;

        long at = (next > now + 3000) ? next : nextBoundary(now, interval);
        schedule(c, at, p.getBoolean(Cfg.KEY_STRONG, false));
    }

    /** 触发执行结束后调用：循环开着就排下一次，否则清掉排定时间 */
    public static long rescheduleNext(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean(Cfg.KEY_LOOP, false)) {
            p.edit().putLong(Cfg.KEY_NEXT_AT, 0).commit();
            return 0;
        }
        long interval = p.getLong(Cfg.KEY_INTERVAL, Cfg.DEFAULT_INTERVAL);
        if (interval < MIN_INTERVAL) interval = MIN_INTERVAL;

        long next = nextBoundary(System.currentTimeMillis(), interval);
        schedule(c, next, p.getBoolean(Cfg.KEY_STRONG, false));
        return next;
    }
}

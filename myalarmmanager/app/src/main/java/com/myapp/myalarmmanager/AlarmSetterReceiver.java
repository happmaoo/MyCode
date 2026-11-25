package com.myapp.myalarmmanager;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** 只负责“设定闹钟”。别的 App / 脚本可以发广播调它 */
public class AlarmSetterReceiver extends BroadcastReceiver {

    private static final String TAG = "AlarmRunner";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Cfg.ACTION_SET_ALARM.equals(intent.getAction())) return;

        long delaySec = 60;
        if (intent.getExtras() != null) {
            delaySec = intent.getExtras().getLong(Cfg.EXTRA_DELAY_SEC, 60);
        }
        if (delaySec < 0) delaySec = 0;

        long triggerAt = System.currentTimeMillis() + delaySec * 1000L;

        boolean strong = ctx.getSharedPreferences(Cfg.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Cfg.KEY_STRONG, false);

        Intent ti = new Intent(ctx, AlarmTriggerReceiver.class);
        ti.setAction(Cfg.ACTION_TRIGGER);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, ti, flags);

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        if (strong) {
            // AlarmClock：系统级闹钟，几乎不会被 Doze / 国产 ROM 后台策略吞掉
            Intent show = new Intent(ctx, MainActivity.class);
            show.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent showPi = PendingIntent.getActivity(ctx, 1, show, flags);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt, showPi), pi);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
        Log.d(TAG, "alarm set, delay=" + delaySec + "s, at=" + triggerAt);
    }
}

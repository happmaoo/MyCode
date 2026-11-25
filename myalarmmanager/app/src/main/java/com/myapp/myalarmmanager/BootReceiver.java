package com.myapp.myalarmmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** 重启后把循环任务捡回来（否则闹钟随重启一起消失） */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent.getAction() == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !"android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) return;

        Context app = ctx.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(Cfg.PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(Cfg.KEY_LOOP, false)) return;

        long saved = p.getLong(Cfg.KEY_NEXT_AT, 0);
        long now = System.currentTimeMillis();
        long at = (saved > now + 5000) ? saved : now + 10000;

        Scheduler.schedule(app, at, p.getBoolean(Cfg.KEY_STRONG, false));
        ScriptRunner.mark(app.getFilesDir().getAbsolutePath(),
                "boot: loop restored, next " + ScriptRunner.fmt(at));
    }
}

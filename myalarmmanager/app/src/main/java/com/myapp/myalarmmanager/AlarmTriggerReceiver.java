package com.myapp.myalarmmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.util.Log;

/** 闹钟触发：读 shared_prefs 里的脚本路径并执行，跑完自己排下一次 */
public class AlarmTriggerReceiver extends BroadcastReceiver {

    private static final String TAG = "AlarmRunner";
    private static final long WAKELOCK_MS = 60 * 1000L;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Cfg.ACTION_TRIGGER.equals(intent.getAction())) return;

        final Context app = ctx.getApplicationContext();
        final SharedPreferences pref = app.getSharedPreferences(Cfg.PREFS, Context.MODE_PRIVATE);
        final String path = pref.getString(Cfg.KEY_SCRIPT, Cfg.DEFAULT_SCRIPT);
        final String dir = app.getFilesDir().getAbsolutePath();

        // 先落一行标记：证明闹钟确实送达了（这一步不依赖 su）
        ScriptRunner.mark(dir, "recv alarm ok, path=" + path);

        // goAsync：让进程在后台线程跑完这段时间不被判超时
        final PendingResult pr = goAsync();

        new Thread(new Runnable() {
            @Override
            public void run() {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmRunner:run");
                try {
                    wl.acquire(WAKELOCK_MS);
                    Log.d(TAG, "trigger, path=" + path);

                    if (path == null || path.trim().isEmpty()) {
                        ScriptRunner.Result r = new ScriptRunner.Result();
                        r.out = "[跳过] 脚本路径为空，请先打开 App 设置\n";
                        r.cmd = "(none)";
                        ScriptRunner.append(dir, "alarm", r, "");
                    } else {
                        ScriptRunner.Result r = ScriptRunner.run(path.trim(),
                                pref.getBoolean(Cfg.KEY_ROOT, true));
                        ScriptRunner.append(dir, "alarm", r, path.trim());
                        Log.d(TAG, "done, exit=" + r.exit);
                    }

                    // 循环续排：同一时间只会有一条待触发闹钟（PendingIntent 覆盖式）
                    long next = Scheduler.rescheduleNext(app);
                    if (next > 0) {
                        ScriptRunner.mark(dir, "next scheduled at " + ScriptRunner.fmt(next));
                    }
                    // 服务可能被 ROM 清掉了，顺手拉回来（harness 有临时 allowlist 时间窗）
                    KeepAliveService.tryStart(app, "alarm");
                    KeepAliveService.refresh(app);
                } finally {
                    if (wl.isHeld()) wl.release();
                    pr.finish();
                }
            }
        }).start();
    }
}

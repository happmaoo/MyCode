package com.qw.pancli;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 触发入口（推荐）：显式广播不受 Android 8+ 的后台 startService 限制，
 * goAsync() 让进程在活儿干完前不被回收。实测一次 connect 约 1 秒。
 *
 *   am broadcast -n com.qw.pancli/.PanRcv --es dev Cannon
 *   am broadcast -n com.qw.pancli/.PanRcv --es dev Cannon --es act disconnect
 */
public class PanRcv extends BroadcastReceiver {

    @Override
    public void onReceive(final Context ctx, final Intent it) {
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Pan.run(ctx, it == null ? null : it.getStringExtra("dev"),
                            Pan.extra(it, "act", "connect"));
                } catch (Throwable t) {
                    Pan.status(ctx, "FAILED receiver " + t);
                } finally {
                    try { pr.finish(); } catch (Throwable ignored) { }
                }
            }
        }, "panrcv").start();
    }
}

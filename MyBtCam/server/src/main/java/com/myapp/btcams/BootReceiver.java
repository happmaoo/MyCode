package com.myapp.btcams;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

/** 开机自启（需在设置页打开「开机自动运行」，且 APP 至少手动启动过一次）。 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (!Prefs.isAutoStart(context)) {
            return;
        }
        Log.i(TAG, "auto start service after " + action);
        Intent service = new Intent(context, SppService.class);
        service.setAction(SppService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, service);
            } else {
                context.startService(service);
            }
        } catch (Exception e) {
            Log.w(TAG, "start service failed", e);
        }
    }
}

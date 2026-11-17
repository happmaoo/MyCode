package com.myapp.autobrightness;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        // 开机广播，以及应用被覆盖安装后（升级完服务不会自动恢复）都要检查一次
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Log.d(TAG, "收到广播: " + action);

        SharedPreferences sharedPref = context.getSharedPreferences("app_config", Context.MODE_PRIVATE);

        // 用户没启用就什么都不做，否则会出现“明明关掉了，重启手机又自动生效”
        if (!sharedPref.getBoolean("enable", false)) {
            Log.d(TAG, "开关未启用，不启动服务");
            return;
        }

        String config = sharedPref.getString("config", "");

        Intent serviceIntent = new Intent(context, autobrightness.class);
        serviceIntent.putExtra("arg-config", config);

        // 直接在 onReceive 里启动。原来用 Handler.postDelayed(5s)：onReceive 返回后
        // 进程随时可能被回收，那段延迟代码根本不保证会执行。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "开机启动服务失败", e);
        }
    }
}

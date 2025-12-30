package com.myapp.autobrightness;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {

            // 这个自启动很奇怪，重启前app如果正在运行则重启后app能开机启动，否则无法开机启动。

            Log.d("myapp", "ACTION_BOOT_COMPLETED");
            // 初始化 SharedPreferences
            SharedPreferences sharedPref = context.getSharedPreferences("app_config", Context.MODE_PRIVATE);
            String config = sharedPref.getString("config", null);

            // 启动应用的主活动或服务
            Intent serviceIntent = new Intent(context, autobrightness.class);
            serviceIntent.putExtra("arg-config", config);



            new Handler(Looper.getMainLooper()).postDelayed(() -> {

                // 针对 API 26 及更高版本，使用 startForegroundService
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }


            }, 5000); // 延迟5秒

        }
    }
}

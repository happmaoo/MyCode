package com.myapp.autobrightness;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // 初始化 SharedPreferences
            SharedPreferences sharedPref = context.getSharedPreferences("app_config", Context.MODE_PRIVATE);
            String config = sharedPref.getString("config", null);

            // 启动应用的主活动或服务
            Intent serviceIntent = new Intent(context, autobrightness.class);
            serviceIntent.putExtra("arg1", "run");
            serviceIntent.putExtra("arg-config", config);

            // 针对 API 26 及更高版本，使用 startForegroundService
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}

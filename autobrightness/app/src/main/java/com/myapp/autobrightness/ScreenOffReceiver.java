package com.myapp.autobrightness;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
public class ScreenOffReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
            Intent serviceIntent = new Intent(context, autobrightness.class);
            context.stopService(serviceIntent);
            serviceIntent.putExtra("arg1", "stop");
            // 针对 API 26 及更高版本，使用 startForegroundService
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.d("TAG", "ACTION_SCREEN_OFF");

        }else if(Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
            Intent serviceIntent = new Intent(context, autobrightness.class);
            serviceIntent.putExtra("arg1", "run");
            // 针对 API 26 及更高版本，使用 startForegroundService
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.d("TAG", "ACTION_SCREEN_ON");
        }
    }
}

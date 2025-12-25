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
            context.startService(serviceIntent);
            Log.d("TAG", "ACTION_SCREEN_OFF");

        }else if(Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
            Intent serviceIntent = new Intent(context, autobrightness.class);
            serviceIntent.putExtra("arg1", "run");
            context.startService(serviceIntent);
            Log.d("TAG", "ACTION_SCREEN_ON");
        }
    }
}

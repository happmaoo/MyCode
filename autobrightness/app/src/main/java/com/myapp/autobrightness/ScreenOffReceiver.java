package com.myapp.autobrightness;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
public class ScreenOffReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
            Intent broadcastIntent = new Intent("com.myapp.ACTION_SCREEN");
            broadcastIntent.putExtra("arg1", "stop");
            context.sendBroadcast(broadcastIntent);

            Log.d("TAG", "ACTION_SCREEN_OFF");

        }else if(Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
            Intent broadcastIntent = new Intent("com.myapp.ACTION_SCREEN");
            broadcastIntent.putExtra("arg1", "run");
            context.sendBroadcast(broadcastIntent);

            Log.d("TAG", "ACTION_SCREEN_ON");
        }
    }
}

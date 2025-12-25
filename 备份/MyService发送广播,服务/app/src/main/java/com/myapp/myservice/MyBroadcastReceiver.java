package com.myapp.myservice;

// MyBroadcastReceiver.java
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class MyBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String message = intent.getStringExtra("message");
        Intent serviceIntent = new Intent(context, MyService.class);
        serviceIntent.putExtra("message", message);
        context.startService(serviceIntent);
    }
}

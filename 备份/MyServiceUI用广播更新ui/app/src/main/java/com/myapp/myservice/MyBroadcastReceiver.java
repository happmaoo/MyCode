package com.myapp.myservice;

// MyBroadcastReceiver.java
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class MyBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {

        String uitext = intent.getStringExtra("uitext");

        // 发送本地广播以更新 MainActivity 界面
        Intent updateIntent = new Intent("UPDATE_UI");
        updateIntent.putExtra("uitext", uitext);
        LocalBroadcastManager.getInstance(context).sendBroadcast(updateIntent);

        Intent serviceIntent = new Intent(context, MyService.class);
        serviceIntent.putExtra("uitext", uitext);
        context.startService(serviceIntent);

    }
}

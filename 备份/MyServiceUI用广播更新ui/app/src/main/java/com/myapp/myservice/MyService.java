package com.myapp.myservice;

// MyService.java
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.widget.Toast;

public class MyService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null; // 不需要绑定
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String message = intent.getStringExtra("uitext");
        if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
        return START_NOT_STICKY;
    }
}

package com.myapp.myservice;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class MyService extends Service {

    private static final String TAG = "MyService";

    @Override
    public IBinder onBind(Intent intent) {
        return null; // 不绑定
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("START_SERVICE".equals(action)) {
                // 启动服务的逻辑
                Log.d(TAG, "Service started");
            } else if ("SEND_HELLO".equals(action)) {
                Log.d(TAG, "Hello");
            }
        }
        return START_NOT_STICKY; // 选择合适的返回值
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroyed");
    }
}

package com.myapp.servicebind;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

public class MyService extends Service {

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        MyService getService() {
            return MyService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public String getData() {
        return "Some result data";
    }
}

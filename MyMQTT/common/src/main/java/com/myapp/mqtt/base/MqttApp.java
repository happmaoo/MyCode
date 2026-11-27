package com.myapp.mqtt.base;

import android.app.Application;

/** 两端共用 Application：只负责安装崩溃日志钩子。 */
public class MqttApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        LogFile.init(this);
    }
}

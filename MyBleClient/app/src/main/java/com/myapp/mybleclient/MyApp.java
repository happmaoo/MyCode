package com.myapp.mybleclient;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

// MyApp.java
public class MyApp extends Application {
    private static MyApp instance;
    private SharedPreferences sharedPreferences;

    // 全局变量
    private Boolean pan;
    private Boolean wifi;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        sharedPreferences = getSharedPreferences("my_prefs", Context.MODE_PRIVATE);

        pan = false;
        wifi = false;
    }

    public static MyApp getInstance() {
        return instance;
    }

    // 封装常用的SharedPreferences操作方法
    public void setString(String key, String value) {
        sharedPreferences.edit().putString(key, value).apply();
    }

    public String getString(String key, String defaultValue) {
        return sharedPreferences.getString(key, defaultValue);
    }

    public void setInt(String key, int value) {
        sharedPreferences.edit().putInt(key, value).apply();
    }

    public int getInt(String key, int defaultValue) {
        return sharedPreferences.getInt(key, defaultValue);
    }

    public void setBoolean(String key, boolean value) {
        sharedPreferences.edit().putBoolean(key, value).apply();
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return sharedPreferences.getBoolean(key, defaultValue);
    }

    // 清除某个key
    public void remove(String key) {
        sharedPreferences.edit().remove(key).apply();
    }

    // 清除所有数据
    public void clearAll() {
        sharedPreferences.edit().clear().apply();
    }




    // Getter和Setter方法
    public Boolean getpan() {
        return pan;
    }

    public void setpan(Boolean pan) {
        this.pan = pan;
    }
    public Boolean getwifi() {
        return wifi;
    }

    public void setwifi(Boolean wifi) {
        this.wifi = wifi;
    }

}
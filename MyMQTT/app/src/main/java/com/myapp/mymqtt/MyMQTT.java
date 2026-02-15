package com.myapp.mymqtt;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ArrayAdapter;

import java.util.ArrayList;


// 本类用于在app主界面退出后还能保存电台数据，不用每次从外部读取。
public class MyMQTT extends Application {




    // SharedPreferences 读写 ----------------------------------------------------

    private static MyMQTT instance;
    private SharedPreferences sharedPreferences;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        sharedPreferences = getSharedPreferences("my_prefs", Context.MODE_PRIVATE);
    }

    public static MyMQTT getInstance() {
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


  public boolean isRunning;

    public ArrayList<String> historyList;
    public ArrayAdapter<String> adapter;
//    public String fm_state;
//    public boolean isKeepDispOn = false;

}

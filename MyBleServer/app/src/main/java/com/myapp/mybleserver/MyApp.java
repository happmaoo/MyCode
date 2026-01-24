package com.myapp.mybleserver;



import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

// MyApp.java
public class MyApp extends Application {
    private static MyApp instance;
    private SharedPreferences sharedPreferences;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        sharedPreferences = getSharedPreferences("my_prefs", Context.MODE_PRIVATE);
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






    //  读取多行文本，按行分割并去除空白
    //    List<String> configLines = app.readMultilineText("apps","");
    //
    //    StringBuilder sb = new StringBuilder();
    //        for (int i = 0; i < configLines.size(); i++) {
    //        Log.d("App",configLines.get(i));
    //    }

    public List<String> readMultilineText(String key, String defaultValue) {

        String multilineText = getString(key, defaultValue);

        List<String> processedLines = new ArrayList<>();

        if (!multilineText.isEmpty()) {
            String[] lines = multilineText.split("\\n|\\r\\n?");

            for (String line : lines) {
                // 去除两端空白字符
                String trimmedLine = line.trim();
                if (!trimmedLine.isEmpty()) { // 可选：跳过空行
                    processedLines.add(trimmedLine);
                }
            }
        }

        return processedLines;
    }
}
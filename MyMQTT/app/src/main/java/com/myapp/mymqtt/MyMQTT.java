package com.myapp.mymqtt;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ArrayAdapter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class MyMQTT extends Application {

    // SharedPreferences 读写 ----------------------------------------------------
    private static MyMQTT instance;
    private SharedPreferences sharedPreferences;
    private Gson gson;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        sharedPreferences = getSharedPreferences("my_prefs", Context.MODE_PRIVATE);
        gson = new Gson();
    }

    public static MyMQTT getInstance() {
        return instance;
    }

    // ServerItem 内部类，字段直接公开，方便外部读写
    public static class ServerItem {
        public String name;
        public String url;
        public String port;
        public String username;
        public String password;
        public String keepalive;
        public String topic_send;
        public String topic_receive;
        public String send_command;
        public String comment;

        public ServerItem(String name, String url, String port,String username,String password,String keepalive,String topic_send,String topic_receive,String send_command,String comment) {
            this.name = name;
            this.url = url;
            this.port = port;
            this.username = username;
            this.password = password;
            this.keepalive = keepalive;
            this.topic_send = topic_send;
            this.topic_receive = topic_receive;
            this.send_command = send_command;
            this.comment = comment;
        }





    }


    // 方法1：使用Gson保存Server列表（推荐）
    public void saveServerList(List<ServerItem> serverList) {
        String json = gson.toJson(serverList);
        setString("server_list", json);
    }

    public List<ServerItem> getServerList() {
        String json = getString("server_list", "");
        if (json.isEmpty()) {
            return new ArrayList<>();
        }
        Type type = new TypeToken<List<ServerItem>>(){}.getType();
        return gson.fromJson(json, type);
    }



    // SharedPreferences基础方法
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

    public void remove(String key) {
        sharedPreferences.edit().remove(key).apply();
    }

    public void clearAll() {
        sharedPreferences.edit().clear().apply();
    }

    public boolean isRunning;
    public ArrayList<String> historyList;
    public ArrayAdapter<String> adapter;

    public byte[] imageData;
}
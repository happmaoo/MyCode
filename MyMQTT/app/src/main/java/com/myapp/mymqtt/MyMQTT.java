package com.myapp.mymqtt;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ArrayAdapter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MyMQTT extends Application {

    // SharedPreferences 读写 ----------------------------------------------------
    private static MyMQTT instance;
    private SharedPreferences sharedPreferences;
    private Gson gson;


    private static final String PREFS_NAME = "command_prefs";
    private static final String KEY_COMMANDS = "commands_map";


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


    //  cmds --------------------------

    public Map<String, String> cmds;

    public static Map<String, String> parseCommands(String input) {
        // 使用LinkedHashMap保持插入顺序
        Map<String, String> commandMap = new LinkedHashMap<>();

        String regex = "^(.+?):(.*)$";
        Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(input);

        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String command = matcher.group(2).trim();
            commandMap.put(name, command);
        }

        return commandMap;
    }


    public static String findCommandByName(Map<String, String> commandMap, String name) {
        return commandMap.get(name);
    }


    // 保存Map到SharedPreferences
    public void saveCommands(Map<String, String> commands) {
        SharedPreferences.Editor editor = sharedPreferences.edit();

        // 将Map转换为JSON字符串
        String json = gson.toJson(commands);
        editor.putString(KEY_COMMANDS, json);
        editor.apply(); // 或使用commit()进行同步保存
    }

    // 从SharedPreferences读取Map
    public Map<String, String> loadCommands() {
        String json = sharedPreferences.getString(KEY_COMMANDS, "");

        if (json.isEmpty()) {
            return new HashMap<>(); // 返回空Map
        }

        // 将JSON字符串转换回Map
        Type type = new TypeToken<Map<String, String>>(){}.getType();
        Map<String, String> commands = gson.fromJson(json, type);

        return commands;
    }

    public static String mapToString(Map<String, String> commandMap) {
        return commandMap.entrySet()
                .stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    // 清除保存的命令
    public void clearCommands() {
        sharedPreferences.edit().remove(KEY_COMMANDS).apply();
    }
    //  end cmds --------------------------







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
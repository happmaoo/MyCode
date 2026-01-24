package com.myapp.myaudiorecorder;

import androidx.core.util.Pair;
import androidx.lifecycle.MutableLiveData;

public class DataManager {
    private static DataManager instance;

    // 使用Pair<String, String>，第一个是来源，第二个是内容
    private MutableLiveData<Pair<String, String>> liveDataMessage = new MutableLiveData<>();

    public static synchronized DataManager getInstance() {
        if (instance == null) instance = new DataManager();
        return instance;
    }

    public MutableLiveData<Pair<String, String>> getLiveDataMessage() {
        return liveDataMessage;
    }

    // 便捷方法
    public void sendMessage(String from, String content) {
        liveDataMessage.postValue(new Pair<>(from, content));
    }
}
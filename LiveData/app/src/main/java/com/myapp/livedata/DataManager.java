package com.myapp.livedata;

import androidx.lifecycle.MutableLiveData;

public class DataManager {
    private static DataManager instance;

    // 1. 定义一个 LiveData (装载 String 类型数据)
    private MutableLiveData<String> liveDataMessage = new MutableLiveData<>();

    public static synchronized DataManager getInstance() {
        if (instance == null) instance = new DataManager();
        return instance;
    }

    public MutableLiveData<String> getLiveDataMessage() {
        return liveDataMessage;
    }
}
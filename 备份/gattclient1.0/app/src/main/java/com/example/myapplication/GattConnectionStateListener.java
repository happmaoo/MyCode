package com.example.myapplication;

public interface GattConnectionStateListener {
    void onConnectionStateChanged(int newState);
    void onCharacteristicRW(String value); // 新增方法
}
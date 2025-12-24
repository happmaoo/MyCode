package com.myapp.myfm;


// 本类用于在app主界面退出后还能保存电台数据，不用每次从外部读取。
public class RadioStation {
    private String number; // 90, 91, 95
    private String name;   // 电台1, 电台2, 电台5

    public RadioStation(String number, String name) {
        this.number = number;
        this.name = name;
    }

    // 添加 getter 方法
    public String getNumber() {
        return number;
    }

    public String getName() {
        return name;
    }

    public String getButtonText() {
        return number + "\n" + name;
    }

    public String getDisplayText() {
        return "" + name + " (频道: " + number + ")";
    }
}

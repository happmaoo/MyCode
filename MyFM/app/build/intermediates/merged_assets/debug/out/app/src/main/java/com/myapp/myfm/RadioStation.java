package com.myapp.myfm;


import java.util.List;

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

    // 添加 setter 方法
    public void setNumber(String number) {
        this.number = number;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setStation(String number, String name) {
        this.number = number;
        this.name = name;
    }

    // 清空方法
    public void clear() {
        this.number = null;
        this.name = null;
    }

    public void clearNumber() {
        this.number = null;
    }

    public void clearName() {
        this.name = null;
    }

    // 清空并返回一个新实例的方法
    public static RadioStation getClearedInstance() {
        return new RadioStation(null, null);
    }

    // 判断是否为空的方法
    public boolean isEmpty() {
        return (number == null || number.trim().isEmpty()) &&
                (name == null || name.trim().isEmpty());
    }

    public boolean isNumberEmpty() {
        return number == null || number.trim().isEmpty();
    }

    public boolean isNameEmpty() {
        return name == null || name.trim().isEmpty();
    }

    // 静态方法：从列表中根据number查找name
    public static String findNameByNumber(List<RadioStation> stations, String number) {
        if (stations == null || number == null) {
            return null;
        }

        for (RadioStation station : stations) {
            if (station.number != null && station.number.equals(number)) {
                return station.name;
            }
        }
        return null;
    }


    // 重置为默认值的方法
    public void reset() {
        this.number = "";
        this.name = "";
    }
}
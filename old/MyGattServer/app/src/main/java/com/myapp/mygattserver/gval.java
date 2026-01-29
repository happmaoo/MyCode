package com.myapp.mygattserver;

import android.app.Application;

public class gval extends Application {

    // 定义全局变量
    private static String globalVariable_termux_result;
    // Getter
    public static String get_termux_result() {
        return globalVariable_termux_result;
    }
    public static String set_termux_result(String string) {
        globalVariable_termux_result =string;
        return globalVariable_termux_result;
    }
}

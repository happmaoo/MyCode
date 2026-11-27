package com.myapp.mqtt.base;

import java.util.Locale;

/** 体积显示：B / KB / MB 自动换算 */
public final class Fmt {
    private Fmt() {
    }

    public static String size(long n) {
        if (n < 1024) return n + "B";
        if (n < 1024L * 1024) return String.format(Locale.US, "%.1fKB", n / 1024.0);
        return String.format(Locale.US, "%.2fMB", n / (1024.0 * 1024));
    }
}

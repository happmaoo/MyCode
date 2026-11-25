package com.myapp.myalarmmanager;

/** 全局常量与 SharedPreferences 键名 */
public class Cfg {

    public static final String PREFS = "cfg";

    /** 要运行的脚本绝对路径 */
    public static final String KEY_SCRIPT = "script_path";
    /** 首次/单次延迟（秒） */
    public static final String KEY_DELAY = "delay_sec";
    /** 循环间隔（秒） */
    public static final String KEY_INTERVAL = "interval_sec";
    /** 是否用 su 以 root 身份运行 */
    public static final String KEY_ROOT = "run_as_root";

    /** 是否循环执行 */
    public static final String KEY_LOOP = "loop_enabled";
    /** 下一次触发时间（毫秒时间戳），0 表示没有排定 */
    public static final String KEY_NEXT_AT = "next_at_ms";
    /** 是否用 AlarmClock（最不容易被 ROM 延迟/吞掉，但会亮状态栏闹钟图标） */
    public static final String KEY_STRONG = "strong_alarm";

    public static final String DEFAULT_SCRIPT = "/data/data/com.termux/files/home/test.sh";
    public static final long DEFAULT_INTERVAL = 600;

    public static final String ACTION_SET_ALARM = "com.myapp.myalarmmanager.SET_ALARM";
    public static final String ACTION_TRIGGER = "com.myapp.myalarmmanager.TRIGGER";
    public static final String EXTRA_DELAY_SEC = "delay_sec";
}

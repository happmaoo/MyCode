package com.myapp.mydesknote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机 / 改时间 / 换时区 / 更新安装后，把桌面小工具重画一遍并重新排闹钟。
 * （AlarmManager 的闹钟在重启后会全部丢失，这里补回来。）
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            NoteWidget.requestUpdate(context);
        }
    }
}

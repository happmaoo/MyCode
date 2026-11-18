package com.myapp.mydesknote;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 桌面小工具：和 MyDeskNote 一样的一整块文字 + 淡黄底，
 * 但只显示已经到时间的项目；到点后由 AlarmManager 立刻刷新一次。
 */
public class NoteWidget extends AppWidgetProvider {

    private static final String TAG = "NoteWidget";
    /** 组件内部用来触发刷新的 action（由 AlarmManager 在解锁时刻发出） */
    private static final String ACTION_REFRESH = "com.myapp.mydesknote.ACTION_WIDGET_REFRESH";
    private static final int ALARM_REQUEST_CODE = 1001;

    /** 与参考 app 一致：有内容用不透明淡黄，没有内容用很淡的半透明黄 */
    private static final String BG_FILLED = "#FFFFE0";
    private static final String BG_EMPTY = "#11FFFFE0";


    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            render(context, appWidgetManager, appWidgetId);
        }
        scheduleNextRefresh(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            refreshAll(context);
            scheduleNextRefresh(context);
        }
    }

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        refreshAll(context);
        scheduleNextRefresh(context);
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        // 最后一个小组件被移除，取消定时闹钟
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(refreshPendingIntent(context));
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        render(context, appWidgetManager, appWidgetId);
    }

    /** 数据变动（新增/删除）后由界面调用 */
    public static void requestUpdate(Context context) {
        refreshAll(context);
        scheduleNextRefresh(context);
    }

    private static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, NoteWidget.class));
        if (ids == null || ids.length == 0) {
            return;
        }
        for (int id : ids) {
            render(context, manager, id);
        }
    }

    private static void render(Context context, AppWidgetManager manager, int widgetId) {
        List<NoteStore.Item> all = NoteStore.load(context);
        long now = System.currentTimeMillis();

        List<NoteStore.Item> visible = new ArrayList<>();
        for (NoteStore.Item item : all) {
            if (item.due <= now) {
                visible.add(item);
            }
        }
        Collections.sort(visible, (a, b) -> Long.compare(b.due, a.due));

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_note);

        // 字号由 App 里的"小工具字号"决定
        float textSize = NoteStore.getWidgetTextSize(context);
        views.setFloat(R.id.widget_text, "setTextSize", textSize);
        // 所有已到时间的条目全部列出，不做条数截断
        views.setInt(R.id.widget_text, "setMaxLines", 200);

        if (visible.isEmpty()) {
            // 没有已到时间的项目：什么都不显示，只留很淡的半透明黄底
            views.setTextViewText(R.id.widget_text, "");
            views.setInt(R.id.widget_root, "setBackgroundColor", Color.parseColor(BG_EMPTY));
        } else {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < visible.size(); i++) {
                if (i > 0) {
                    builder.append('\n');
                }
                builder.append(oneLine(visible.get(i).text));
            }
            views.setTextViewText(R.id.widget_text, builder.toString());
            views.setInt(R.id.widget_root, "setBackgroundColor", Color.parseColor(BG_FILLED));
        }

        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(context, 0, openIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.widget_text, openPendingIntent);

        manager.updateAppWidget(widgetId, views);
    }

    /** 在最近一条项目的解锁时刻安排一次刷新，没有待解锁项目就取消 */
    private static void scheduleNextRefresh(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        for (NoteStore.Item item : NoteStore.load(context)) {
            if (item.due > now && item.due < next) {
                next = item.due;
            }
        }
        PendingIntent pi = refreshPendingIntent(context);
        // 桌面上没有这个小组件就不用定闹钟
        int[] ids = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(new ComponentName(context, NoteWidget.class));
        if (ids == null || ids.length == 0 || next == Long.MAX_VALUE) {
            am.cancel(pi);
            return;
        }
        // 到点后立刻刷新，桌面上就能看到刚解锁的那条
        long triggerAt = next + 500;
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } catch (Exception e) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    private static PendingIntent refreshPendingIntent(Context context) {
        Intent intent = new Intent(context, NoteWidget.class);
        intent.setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** 一条项目占一行：把内容里的换行压平，但文字不截断 */
    private static String oneLine(String text) {
        return text.replace('\n', ' ').trim();
    }
}

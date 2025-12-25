package com.myapp.clockwidget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.RemoteViews;
import android.content.SharedPreferences;


public class ClockWidgetProvider extends AppWidgetProvider {

    private static final int UPDATE_INTERVAL = 60000; // 每分钟更新一次


    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        // 执行初始化任务
    }


    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {

        for (int appWidgetId : appWidgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.layout);
            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
        startAlarm(context);
    }

    private void startAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, ClockWidgetProvider.class);
        intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        long triggerAtMillis = SystemClock.elapsedRealtime() + UPDATE_INTERVAL;

        alarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, triggerAtMillis, UPDATE_INTERVAL, pendingIntent);
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        cancelAlarm(context);
    }

    private void cancelAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, ClockWidgetProvider.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        alarmManager.cancel(pendingIntent);
    }
}

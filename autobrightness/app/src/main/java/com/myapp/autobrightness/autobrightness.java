package com.myapp.autobrightness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.res.Configuration;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Build;
import android.widget.RemoteViews;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class autobrightness extends Service {

    private static final String TAG = "AutoBrightness";
    private static final String CHANNEL_ID = "AutoBrightnessService";
    private static final String PREFS_NAME = "app_config";
    private static final String KEY_CONFIG = "config";
    private static final String KEY_ENABLE = "enable";
    private static final String EXTRA_CONFIG = "arg-config";
    /** 通知栏按钮点击后发给服务的 action：翻转启用/停用 */
    private static final String ACTION_TOGGLE = "com.myapp.autobrightness.ACTION_TOGGLE";

    private static final int NOTIFICATION_ID = 1;
    /** 大多数机型的亮度设置范围是 0-255；这里只做下限保护，上限由系统裁剪 */
    private static final int DEFAULT_BRIGHTNESS = 125;
    /** 传感器事件最小处理间隔，避免高频回调 */
    private static final long MIN_PROCESS_INTERVAL_MS = 500;
    private static final int TOTAL_STEPS = 25;

    private SensorManager sensorManager;
    private Sensor lightSensor;
    private SensorEventListener lightSensorListener;
    private boolean sensorRegistered = false;

    // --- 配置 ---
    private final List<int[]> cachedConfigData = new ArrayList<>();
    private int configLmd = 3;
    private int configDuration = 2000;

    private long lastProcessingTime = 0;

    private Handler brightnessHandler = new Handler(Looper.getMainLooper());
    private Runnable brightnessRunnable;

    private float lightLevel;
    private int consecutiveCount = 0;
    /** 正在累计的候选目标亮度，只有它连续出现 configLmd 次才会真正调整 */
    private int pendingTarget = Integer.MIN_VALUE;
    private int lastTargetBrightness = -1;
    private boolean firstStart = true;

    /** 我们自己上一次写进系统的亮度值 / 时间，用来区分"用户手动调的" */
    private int lastOwnWriteValue = -1;
    private long lastOwnWriteTime = 0;
    /** 用户手动调过亮度：临时暂停自动调节，下一次亮屏时恢复 */
    private boolean manualBrightness = false;
    private ContentObserver brightnessObserver;

    /**
     * 监听开关变化：界面上的复选框、通知栏按钮改的都是同一个 prefs 值，
     * 服务这边统一在这里响应，避免两处各写一套起停逻辑而不同步。
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                    if (KEY_ENABLE.equals(key)) {
                        onEnableChanged();
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF); // 监听屏幕关闭
        filter.addAction(Intent.ACTION_SCREEN_ON);   // 监听屏幕开启
        registerReceiver(screenStateReceiver, filter);

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefListener);

        // 监听系统亮度值变化：用来发现"用户自己拉了亮度条"
        brightnessObserver = new ContentObserver(brightnessHandler) {
            @Override
            public void onChange(boolean selfChange) {
                onBrightnessSettingChanged();
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false, brightnessObserver);
        } catch (Exception e) {
            Log.w(TAG, "注册亮度监听失败", e);
        }
    }

    /** 解析配置：逐行容错，单行写错只跳过该行，不会让整份配置作废 */
    private void parseConfig(String config) {
        cachedConfigData.clear();
        if (config == null || config.trim().isEmpty()) {
            Log.w(TAG, "配置为空，不做亮度调节");
            return;
        }
        try {
            Matcher matcher = Pattern.compile("lmd:\\s*(\\d+)\\s*/\\s*(\\d+)([\\s\\S]*)").matcher(config);
            if (!matcher.find()) {
                Log.e(TAG, "配置缺少 lmd:灵敏度/过渡秒数，解析失败");
                return;
            }
            configLmd = Math.max(1, Integer.parseInt(matcher.group(1).trim()));
            // 第二个数字是过渡时长（秒），单位换算成毫秒
            configDuration = Math.max(1, Integer.parseInt(matcher.group(2).trim())) * 1000;

            String[] lines = matcher.group(3).trim().split("\\n");
            for (String line : lines) {
                String[] numbers = line.trim().split("\\s+");
                if (numbers.length < 2) {
                    continue;
                }
                try {
                    int myLight = Integer.parseInt(numbers[0].trim());
                    int myBrightness = Integer.parseInt(numbers[1].trim());
                    if (myBrightness < 0) {
                        continue;
                    }
                    cachedConfigData.add(new int[]{Math.max(0, myLight), myBrightness});
                } catch (NumberFormatException e) {
                    Log.w(TAG, "跳过无法解析的配置行: " + line);
                }
            }
            // 按光照强度从小到大排序，防止输入乱序导致逻辑失效
            Collections.sort(cachedConfigData, (a, b) -> Integer.compare(a[0], b[0]));
            Log.d(TAG, "配置解析完成，规则 " + cachedConfigData.size() + " 条，灵敏度 " + configLmd
                    + "，过渡 " + configDuration + "ms");
        } catch (Exception e) {
            Log.e(TAG, "解析失败: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences pref = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // 0. 通知栏按钮：翻转启用/停用。写入 prefs 后由 prefListener 立刻生效，
        //    这样无论从通知栏还是从界面开关改的，服务行为都一致
        if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
            boolean target = !pref.getBoolean(KEY_ENABLE, false);
            pref.edit().putBoolean(KEY_ENABLE, target).commit();
            Log.d(TAG, "通知栏切换开关 -> " + (target ? "启用" : "停用"));
        }

        boolean enabled = pref.getBoolean(KEY_ENABLE, false);

        // 1. 先把前台通知挂上。被 startForegroundService() 拉起后必须在 5 秒内
        //    调用 startForeground()，否则系统会抛 RemoteServiceException 把 app 干掉，
        //    所以这一步要放在所有提前 return 之前
        postNotification(enabled);

        // 2. 进程被系统回收后自动重启时，如果用户是“停用”状态，不要再复活，
        //    否则“已暂停”的通知会莫名其妙自己冒出来
        boolean systemRestart = intent == null || (flags & START_FLAG_REDELIVERY) != 0;
        if (systemRestart && !enabled) {
            Log.d(TAG, "停用状态下被系统重启，服务退出");
            stopSelf();
            return START_NOT_STICKY;
        }

        // 3. 拿配置：优先用 intent 里的，进程被回收后重启拿不到 intent，
        //    就退回 SharedPreferences，保证重启后依然能按配置工作
        String config = (intent != null) ? intent.getStringExtra(EXTRA_CONFIG) : null;
        if (config == null) {
            config = pref.getString(KEY_CONFIG, "");
        }

        parseConfig(config);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            // 没有写系统设置权限，调节一定失败，直接报出来而不是静默失败
            Log.e(TAG, "缺少 WRITE_SETTINGS 权限，无法调节亮度");
        }

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        if (lightSensor == null) {
            Log.e(TAG, "设备没有光线传感器，服务退出");
            stopSelf();
            return START_NOT_STICKY;
        }

        // 4. 按开关状态挂/摘传感器（通知上面已经刷过）
        applyEnabledState();
        // 用 REDELIVER：系统重启服务时会把本次 intent（含配置）重新投递过来
        return START_REDELIVER_INTENT;
    }

    /** 只按 prefs 里的开关状态切换采样，不发通知 */
    private void applyEnabledState() {
        boolean enabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ENABLE, false);
        if (enabled) {
            startSensorListening();
        } else {
            stopSensorListening();
            cancelBrightnessAnimation();
        }
    }

    /** 开关被改动（界面开关 / 通知栏按钮）后的统一处理 */
    private void onEnableChanged() {
        boolean enabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ENABLE, false);
        if (enabled) {
            // 用户主动打开开关，视为恢复自动调节
            manualBrightness = false;
        }
        applyEnabledState();
        postNotification(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ENABLE, false));
    }

    /**
     * 系统亮度被改动时调用。剔除掉我们自己写的那些变化，剩下的就是用户手动调的：
     * 临时暂停自动调节，通知文案改成"已暂停（手动亮度）"，下一次亮屏再恢复。
     */
    private void onBrightnessSettingChanged() {
        boolean enabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ENABLE, false);
        if (!enabled || manualBrightness) {
            return;
        }
        // 刚写过系统亮度（渐变动画期间），这次变化是自己造成的，忽略
        if (SystemClock.uptimeMillis() - lastOwnWriteTime < 2000) {
            return;
        }
        int current = getCurrentBrightness();
        if (current < 0 || current == lastOwnWriteValue) {
            return;
        }
        manualBrightness = true;
        cancelBrightnessAnimation();
        postNotification(true);
        Log.d(TAG, "检测到手动调节亮度（当前 " + current + "），临时暂停自动调节");
    }

    /** 亮屏后恢复自动调节 */
    private void clearManualBrightness() {
        if (!manualBrightness) {
            return;
        }
        manualBrightness = false;
        postNotification(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ENABLE, false));
        Log.d(TAG, "亮屏，恢复自动调节");
    }

    /** 停用时把没跑完的亮度渐变掐掉，避免“已停用”还在偷偷改亮度 */
    private void cancelBrightnessAnimation() {
        if (brightnessRunnable != null) {
            brightnessHandler.removeCallbacks(brightnessRunnable);
            brightnessRunnable = null;
        }
        lastTargetBrightness = -1;
        consecutiveCount = 0;
        pendingTarget = Integer.MIN_VALUE;
    }

    private void postNotification(boolean enabled) {
        Notification notification = buildNotification(enabled);
        if (firstStart) {
            startForeground(NOTIFICATION_ID, notification);
            firstStart = false;
        } else {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(boolean enabled) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 0, openIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);

        // 通知栏按钮：点击翻转开关
        Intent toggleIntent = new Intent(this, autobrightness.class);
        toggleIntent.setAction(ACTION_TOGGLE);
        PendingIntent togglePendingIntent = PendingIntent.getService(this, 10, toggleIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);

        // 用自定义布局把"停用/启用"按钮放到右边
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        RemoteViews content = new RemoteViews(getPackageName(), R.layout.notification_brightness);
        String appName = getString(R.string.app_name);
        String stateText;
        if (!enabled) {
            stateText = appName + " · 已暂停";
        } else if (manualBrightness) {
            stateText = appName + " · 已暂停（手动亮度）";
        } else {
            stateText = appName + " · 已开启";
        }
        content.setTextViewText(R.id.notif_state, stateText);
        content.setTextViewText(R.id.notif_toggle, enabled ? "停用" : "启用");

        int titleColor = dark ? 0xFFFFFFFF : 0xFF1A1A1A;
        int buttonColor = dark ? 0xFF80CBC4 : 0xFF00796B;
        content.setTextColor(R.id.notif_state, titleColor);
        content.setTextColor(R.id.notif_toggle, buttonColor);

        content.setImageViewResource(R.id.notif_icon,
                dark ? R.drawable.ic_notif_glyph_white : R.drawable.ic_notif_glyph_black);
        content.setInt(R.id.notif_toggle, "setBackgroundResource",
                dark ? R.drawable.bg_notif_btn_dark : R.drawable.bg_notif_btn_light);

        content.setOnClickPendingIntent(R.id.notif_toggle, togglePendingIntent);
        content.setOnClickPendingIntent(R.id.notif_state, openPendingIntent);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(appName)
                .setContentText(stateText)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCustomContentView(content)
                .setCustomBigContentView(content)
                .build();
    }

    /** 只创建一次监听器；重复 startService 时复用，避免监听器越挂越多 */
    private void ensureListener() {
        if (lightSensorListener != null) {
            return;
        }
        lightSensorListener = new SensorEventListener() {

            @Override
            public void onSensorChanged(SensorEvent event) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastProcessingTime < MIN_PROCESS_INTERVAL_MS) {
                    return;
                }
                lastProcessingTime = currentTime;

                lightLevel = event.values[0];

                // 发送广播到 UI
                Intent broadcastIntent = new Intent("com.myapp.LIGHT_LEVEL_UPDATE");
                broadcastIntent.putExtra("lightLevel", lightLevel);
                sendBroadcast(broadcastIntent);

                if (cachedConfigData.isEmpty()) {
                    return;
                }

                // 1. 寻找当前光照对应的目标亮度（从高到低遍历）
                int currentTarget = -1;
                for (int i = cachedConfigData.size() - 1; i >= 0; i--) {
                    int[] rule = cachedConfigData.get(i);
                    if (lightLevel >= rule[0]) {
                        currentTarget = rule[1];
                        break;
                    }
                }
                if (currentTarget == -1) {
                    return;
                }

                // 2. 光感为 0 时立即调整，不参与计数逻辑
                if (lightLevel <= 0f) {
                    if (currentTarget != lastTargetBrightness) {
                        setScreenBrightnessSmoothly(currentTarget);
                    }
                    consecutiveCount = 0;
                    pendingTarget = Integer.MIN_VALUE;
                    return;
                }

                // 3. 常规处理：要求“同一个目标”连续出现 configLmd 次才调整，
                //    原先只统计“目标与当前亮度不同”的次数，光线抖动几次就会跳档
                if (currentTarget == lastTargetBrightness) {
                    consecutiveCount = 0;
                    pendingTarget = Integer.MIN_VALUE;
                    return;
                }
                if (currentTarget == pendingTarget) {
                    consecutiveCount++;
                } else {
                    pendingTarget = currentTarget;
                    consecutiveCount = 1;
                }
                if (consecutiveCount >= configLmd) {
                    setScreenBrightnessSmoothly(currentTarget);
                    consecutiveCount = 0;
                    pendingTarget = Integer.MIN_VALUE;
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
    }

    private void startSensorListening() {
        if (sensorManager == null || lightSensor == null) {
            return;
        }
        // 停用状态下不要采样（例如屏幕点亮时这条路径也会进来）
        if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_ENABLE, false)) {
            return;
        }
        ensureListener();
        if (sensorRegistered) {
            // 先注销旧的，保证全局只有一个注册，不会重复回调
            sensorManager.unregisterListener(lightSensorListener);
        }
        sensorRegistered = sensorManager.registerListener(lightSensorListener, lightSensor,
                SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void stopSensorListening() {
        if (sensorManager != null && lightSensorListener != null && sensorRegistered) {
            sensorManager.unregisterListener(lightSensorListener);
        }
        sensorRegistered = false;
    }

    private void setScreenBrightnessSmoothly(int targetBrightness) {
        if (manualBrightness) {
            return; // 用户手动调过亮度，先不要抢
        }
        if (targetBrightness == lastTargetBrightness) {
            return;
        }
        // 停用状态下不再写系统亮度
        if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_ENABLE, false)) {
            return;
        }

        // 停止之前的任务
        if (brightnessRunnable != null) {
            brightnessHandler.removeCallbacks(brightnessRunnable);
            brightnessRunnable = null;
        }

        lastTargetBrightness = targetBrightness;
        final int startBrightness = getCurrentBrightness();
        final int finalTarget = Math.max(0, targetBrightness);
        final long stepDelay = Math.max(1, configDuration / TOTAL_STEPS);

        brightnessRunnable = new Runnable() {
            private int step = 0;

            @Override
            public void run() {
                step++;

                // 线性插值
                float fraction = (float) step / TOTAL_STEPS;
                int nextVal = (int) (startBrightness + (finalTarget - startBrightness) * fraction);
                nextVal = Math.max(0, nextVal);

                try {
                    // 第一步先切成手动模式，防止 MIUI 自动亮度冲突
                    if (step == 1) {
                        Settings.System.putInt(getContentResolver(),
                                Settings.System.SCREEN_BRIGHTNESS_MODE,
                                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    }
                    Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, nextVal);
                    lastOwnWriteValue = nextVal;
                    lastOwnWriteTime = SystemClock.uptimeMillis();
                } catch (Exception e) {
                    Log.e(TAG, "写入亮度失败", e);
                    return;
                }

                if (step < TOTAL_STEPS) {
                    brightnessHandler.postDelayed(this, stepDelay);
                }
            }
        };

        brightnessHandler.post(brightnessRunnable);
    }

    private int getCurrentBrightness() {
        try {
            return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
        } catch (Settings.SettingNotFoundException e) {
            return DEFAULT_BRIGHTNESS;
        }
    }

    @Override
    public void onDestroy() {
        // 停掉没跑完的亮度渐变，避免服务退出后还在写系统亮度
        if (brightnessRunnable != null) {
            brightnessHandler.removeCallbacks(brightnessRunnable);
            brightnessRunnable = null;
        }
        stopSensorListening();
        try {
            if (brightnessObserver != null) {
                getContentResolver().unregisterContentObserver(brightnessObserver);
                brightnessObserver = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "注销亮度监听失败", e);
        }
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(prefListener);
            unregisterReceiver(screenStateReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "广播/监听已注销", e);
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID,
                    "AutoBrightnessService", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                // 屏幕关闭：注销传感器监听，节省耗电
                stopSensorListening();
                Log.d(TAG, "屏幕关闭，停止采样");
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                // 屏幕开启：先把"手动亮度"的临时暂停解除，再恢复采样
                clearManualBrightness();
                startSensorListening();
                Log.d(TAG, "屏幕开启，恢复采样");
            }
        }
    };
}

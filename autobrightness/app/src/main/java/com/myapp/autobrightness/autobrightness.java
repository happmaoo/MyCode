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
    /** 是否启用"确认窗口期"兜底（缺省 true）；关掉就是纯次数判定 */
    static final String KEY_WINDOW_ENABLE = "window_enable";
    /** 确认窗口期（毫秒）；0 或缺失表示用默认 10 秒 */
    static final String KEY_WINDOW_MS = "algo_window_ms";
    /** 通知栏按钮点击后发给服务的 action：翻转启用/停用 */
    private static final String ACTION_TOGGLE = "com.myapp.autobrightness.ACTION_TOGGLE";

    private static final int NOTIFICATION_ID = 1;
    /** 大多数机型的亮度设置范围是 0-255；这里只做下限保护，上限由系统裁剪 */
    private static final int DEFAULT_BRIGHTNESS = 125;
    /** 传感器事件最小处理间隔，避免高频回调 */
    private static final long MIN_PROCESS_INTERVAL_MS = 500;
    private static final int TOTAL_STEPS = 25;
    /** 阶梯阈值确认窗口的默认值：10 秒（设置页留空时用） */
    private static final long DEFAULT_CONFIRM_WINDOW_MS = 10000;
    /** 确认窗口期可填范围：2–30 秒 */
    static final int MIN_CONFIRM_WINDOW_MS = 2000;
    static final int MAX_CONFIRM_WINDOW_MS = 30000;
    /** Settings.System.SCREEN_BRIGHTNESS_MODE 的自动值（该常量在 SDK 里是隐藏的，这里写死 1） */
    private static final int SCREEN_BRIGHTNESS_MODE_AUTO = 1;
    /** 给界面刷数值用的广播节流（比判定用的 500ms 更快，界面才跟得上） */
    private static final long UI_BROADCAST_INTERVAL_MS = 100;

    private SensorManager sensorManager;
    private Sensor lightSensor;
    private SensorEventListener lightSensorListener;
    private boolean sensorRegistered = false;

    // --- 配置 ---
    private final List<int[]> cachedConfigData = new ArrayList<>();
    /** 是否启用确认窗口期（设置页复选框）；关掉则只按次数判定 */
    private boolean windowEnabled = true;
    /** 连续"偏离当前亮度"的采样次数，凑够 lmd 次就立即切档 */
    private int consecutiveCount = 0;
    /** 候选目标第一次出现的时刻：兜底窗口的截止时间以此为基准 */
    private long pendingSinceMs = 0;
    private int configLmd = 3;
    private int configDuration = 2000;

    private long lastProcessingTime = 0;
    private long lastBroadcastTime = 0;

    private Handler brightnessHandler = new Handler(Looper.getMainLooper());
    private Runnable brightnessRunnable;

    private float lightLevel;
    /** 候选目标亮度：进入后等确认窗口（configLmd × 500ms）到点再裁决 */
    private int pendingTarget = Integer.MIN_VALUE;
    /** 待执行的确认任务，目标变化时要取消重排 */
    private Runnable pendingConfirmRunnable;

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
                    } else if (KEY_WINDOW_MS.equals(key) || KEY_WINDOW_ENABLE.equals(key)) {
                        // 改了窗口期/开关了兜底：丢掉正在等的候选，下一次采样按新规则重算
                        loadWindowConfig();
                        clearPending();
                        lastTargetBrightness = -1;
                        if (brightnessRunnable != null) {
                            brightnessHandler.removeCallbacks(brightnessRunnable);
                            brightnessRunnable = null;
                        }
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
        loadWindowConfig();

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

    /** 读取"确认窗口期"开关（设置页复选框；缺省 = 启用） */
    private void loadWindowConfig() {
        windowEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_WINDOW_ENABLE, true);
    }

    /** 光照 → 目标亮度：取阈值不超过当前光照的最高一档；无匹配返回 -1 */
    private int targetForLight(float lux) {
        int target = -1;
        for (int i = cachedConfigData.size() - 1; i >= 0; i--) {
            int[] rule = cachedConfigData.get(i);
            if (lux >= rule[0]) {
                target = rule[1];
                break;
            }
        }
        return target;
    }

    /**
     * 确认窗口长度，单位毫秒。
     * 设置页填了具体值就用它（KEY_WINDOW_MS）；留空一律用默认 10 秒。
     * 只在"启用确认窗口期"时才会用到（见 windowEnabled）。
     * 允许范围 2–30 秒，超出按边界夹取，防止设成 0 疯狂调亮度或设太长像卡死。
     */
    private long confirmWindowMs() {
        int custom = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(KEY_WINDOW_MS, 0);
        if (custom > 0) return clampWindowMs(custom);
        return DEFAULT_CONFIRM_WINDOW_MS;
    }

    /** 窗口期上下限夹取（2–30 秒） */
    static int clampWindowMs(int ms) {
        if (ms < MIN_CONFIRM_WINDOW_MS) return MIN_CONFIRM_WINDOW_MS;
        if (ms > MAX_CONFIRM_WINDOW_MS) return MAX_CONFIRM_WINDOW_MS;
        return ms;
    }

    /** 只掐掉确认窗口的定时器，保留 pendingTarget（事件计数模式要拿它做连续计数） */
    private void cancelPendingConfirmOnly() {
        if (pendingConfirmRunnable != null) {
            brightnessHandler.removeCallbacks(pendingConfirmRunnable);
            pendingConfirmRunnable = null;
        }
    }

    /** 取消待裁决的候选目标，计数一起清零 */
    private void clearPending() {
        pendingTarget = Integer.MIN_VALUE;
        pendingSinceMs = 0;
        consecutiveCount = 0;
        cancelPendingConfirmOnly();
    }

    /** 为当前候选目标排确认任务：截止时间固定在"第一次偏离"那一刻 + 窗口期，后续抖动只更新候选，不再顺延 */
    private void armPendingConfirm() {
        cancelPendingConfirmOnly();
        long elapsed = SystemClock.uptimeMillis() - pendingSinceMs;
        long delay = Math.max(0, confirmWindowMs() - elapsed);
        pendingConfirmRunnable = new Runnable() {
            @Override public void run() {
                pendingConfirmRunnable = null;
                commitPendingTarget();
            }
        };
        brightnessHandler.postDelayed(pendingConfirmRunnable, delay);
    }

    /**
     * 窗口到点：用最新光照再算一次目标，不等于当前亮度就执行。
     * 关键点：截止时间是从第一次偏离开始算的固定期限，窗口内光线再跳几次都只是
     * 更新候选值、不会把计时器往后推，所以"变了 3 次"不会变成"再等 30 秒"。
     */
    private void commitPendingTarget() {
        int pending = pendingTarget;
        pendingTarget = Integer.MIN_VALUE;
        pendingSinceMs = 0;
        consecutiveCount = 0;
        if (pending == Integer.MIN_VALUE) return;
        if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_ENABLE, false)) return;
        if (manualBrightness) return;                       // 用户手动调过，先别抢
        if (lightLevel <= 0f) return;                       // 已进入全黑立即分支
        int now = targetForLight(lightLevel);
        if (now == -1) return;
        if (now == lastTargetBrightness) {                  // 光线已经回到当前档，不折腾
            Log.d(TAG, "窗口到点，目标已等于当前亮度 " + now + "，忽略");
            return;
        }
        Log.d(TAG, "窗口到点，执行 " + now + "（候选 " + pending + "，光照 " + lightLevel + "）");
        setScreenBrightnessSmoothly(now);
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

        loadWindowConfig();
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
        // 系统仍是自动亮度模式时，改 SCREEN_BRIGHTNESS 的是系统自己，不是用户；
        // 这种情况绝不能判成"手动调节"，否则自动调节会被永久锁死到下一次亮屏
        try {
            int mode = Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE);
            if (mode == SCREEN_BRIGHTNESS_MODE_AUTO) {
                return;
            }
        } catch (Exception ignored) {
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
        clearPending();
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
                // 先无条件刷新缓存：确认窗口到点时要用“此刻最新”的光照复查，
                // 否则被节流丢掉的事件会让缓存值停留在旧读数上
                lightLevel = event.values[0];

                long currentTime = System.currentTimeMillis();

                // 先按 100ms 节流广播给界面：这只影响显示，不参与判定，
                // 所以界面数字能跟上传感器，而不会把判定频率也抬高
                if (currentTime - lastBroadcastTime >= UI_BROADCAST_INTERVAL_MS) {
                    lastBroadcastTime = currentTime;
                    Intent broadcastIntent = new Intent("com.myapp.LIGHT_LEVEL_UPDATE");
                    broadcastIntent.putExtra("lightLevel", lightLevel);
                    sendBroadcast(broadcastIntent);
                }

                // 判定用 500ms 节流：过密的事件不参与算法统计
                if (currentTime - lastProcessingTime < MIN_PROCESS_INTERVAL_MS) {
                    return;
                }
                lastProcessingTime = currentTime;

                if (cachedConfigData.isEmpty()) {
                    return;
                }

                // 1. 光照 → 目标亮度（两种算法共用同一套阈值表）
                int currentTarget = targetForLight(lightLevel);
                if (currentTarget == -1) {
                    return;
                }

                // 2. 光感为 0 时立即调整，不参与确认窗口
                if (lightLevel <= 0f) {
                    clearPending();
                    if (currentTarget != lastTargetBrightness) {
                        setScreenBrightnessSmoothly(currentTarget);
                    }
                    return;
                }

                // 3. 统一算法（只有一套）：次数优先，窗口兜底。
                //   ① 次数：连续 lmd 次采样都落在"非当前档"，第 lmd 次立即按最新档切；
                //   ② 兜底：勾选"确认窗口期"时，自第一次偏离起算固定截止时间，到点按最新光照裁决；
                //      没勾选就是纯次数判定，不设时间兜底。
                if (currentTarget == lastTargetBrightness) {
                    clearPending();       // 回到当前档：计数清零，不做调整
                    return;
                }
                if (pendingTarget == Integer.MIN_VALUE) {
                    // 从"没有候选"进入候选状态：这里才定截止起点
                    pendingSinceMs = SystemClock.uptimeMillis();
                }
                if (currentTarget != pendingTarget) {
                    pendingTarget = currentTarget;
                    if (windowEnabled) {
                        armPendingConfirm();  // 换档：截止时间不变，只重排定时器
                    }
                }
                consecutiveCount++;           // 连续"偏离当前档"的采样次数
                Log.d(TAG, "偏离第 " + consecutiveCount + "/" + configLmd + " 次，目标 "
                        + currentTarget + "（光照 " + lightLevel + "，兜底窗口 "
                        + (windowEnabled ? confirmWindowMs() + "ms" : "关") + "）");
                if (consecutiveCount >= configLmd) {
                    // 次数够了：马上切，不用等窗口到点
                    cancelPendingConfirmOnly();
                    consecutiveCount = 0;
                    pendingTarget = Integer.MIN_VALUE;
                    pendingSinceMs = 0;
                    Log.d(TAG, "连续 " + configLmd + " 次偏离，立即执行 " + currentTarget);
                    setScreenBrightnessSmoothly(currentTarget);
                    return;
                }
                // 次数还没凑够：勾了窗口就等 commitPendingTarget()，没勾就等下一次采样
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

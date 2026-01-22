package com.myapp.autobrightness;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections; // 记得导入排序工具
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;

public class autobrightness extends Service {

    private static final String CHANNEL_ID = "AutoBrightnessService";
    private SensorManager sensorManager;
    private Sensor lightSensor;
    private SensorEventListener lightSensorListener;

    // --- 成员变量声明 (解决 Cannot resolve symbol 错误) ---
    private List<int[]> cachedConfigData = new ArrayList<>();
    private int configLmd = 3;
    private int configDuration = 2000;
    private long lastProcessingTime = 0;
    private ValueAnimator currentAnimator;

    private float lightLevel;
    private float lastProcessedLightLevel = -1f; // 此处即为报错的变量声明
    private int lastMatchedLightRule = -1;
    private int consecutiveCount = 0;
    private int lastTargetBrightness = -1;
    private boolean firstStart = true;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    // --- 改进的解析逻辑：支持排序，确保匹配准确 ---
    private void parseConfig(String config) {
        if (config == null || config.isEmpty()) return;
        try {
            cachedConfigData.clear();
            Pattern pattern = Pattern.compile("lmd:\\s*(\\d+)/(\\d+)([\\s\\S]*)");
            Matcher matcher = pattern.matcher(config);

            if (matcher.find()) {
                configLmd = Integer.parseInt(matcher.group(1).trim());
                configDuration = Integer.parseInt(matcher.group(2).trim()) * 1000;
                String mainBody = matcher.group(3).trim();

                String[] lines = mainBody.split("\\n");
                for (String line : lines) {
                    String[] numbers = line.trim().split("\\s+");
                    if (numbers.length >= 2) {
                        int mylight = Integer.parseInt(numbers[0]);
                        int mybrightness = Integer.parseInt(numbers[1]);
                        cachedConfigData.add(new int[]{mylight, mybrightness});
                    }
                }
                // 关键：按光照强度从小到大排序，防止输入乱序导致逻辑失效
                Collections.sort(cachedConfigData, (a, b) -> Integer.compare(a[0], b[0]));
            }
        } catch (Exception e) {
            Log.e("CONFIG", "解析失败: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (firstStart) {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("AutoBrightness")
                    .setContentText("自动亮度已开启")
                    .setSmallIcon(R.drawable.ic_stat_name)
                    .setContentIntent(pendingIntent)
                    .build();

            startForeground(1, notification);
            firstStart = false;
        }

        if (intent != null && intent.getStringExtra("arg-config") != null) {
            parseConfig(intent.getStringExtra("arg-config"));
        }

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        lightSensorListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                long currentTime = System.currentTimeMillis();
                //  ms 采样一次逻辑，降低 CPU 占用
                if (currentTime - lastProcessingTime < 500) return;
                lastProcessingTime = currentTime;

                lightLevel = event.values[0];

                // 发送更新到 UI
                Intent broadcastIntent = new Intent("com.myapp.LIGHT_LEVEL_UPDATE");
                broadcastIntent.putExtra("lightLevel", lightLevel);
                sendBroadcast(broadcastIntent);

                // --- 匹配逻辑优化：解决 300 降到 100 没反应的问题 ---
                for (int i = cachedConfigData.size() - 1; i >= 0; i--) {
                    int[] rule = cachedConfigData.get(i);
                    int ruleLight = rule[0];
                    int ruleBrightness = rule[1];

                    if (lightLevel >= ruleLight) {

                        // 如果光感值为0，直接设置亮度并跳过检测逻辑
                        if (lightLevel == 0) {
                            setScreenBrightnessSmoothly(ruleBrightness);
                            lastProcessedLightLevel = lightLevel;
                            break;
                        }


                        // 计算光线变动跨度
                        float lightDiff = Math.abs(lightLevel - lastProcessedLightLevel);

                        // 如果光线剧烈变化（跨度 > 50 lux）或者进入了新区间
                        if (lightDiff > 50 || ruleLight != lastMatchedLightRule) {
                            consecutiveCount = 1;
                            lastMatchedLightRule = ruleLight;
                        } else {
                            consecutiveCount++;
                        }

                        // 如果满足连续计数，或者光线剧烈变化，则立即调整亮度
                        if (lightDiff > 50 || consecutiveCount >= configLmd) {
                            setScreenBrightnessSmoothly(ruleBrightness);
                            lastProcessedLightLevel = lightLevel; // 更新记录点
                        }
                        break;
                    }
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };

        sensorManager.registerListener(lightSensorListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        return START_STICKY;
    }

    private void setScreenBrightnessSmoothly(int targetBrightness) {
        if (targetBrightness == lastTargetBrightness) return;
        lastTargetBrightness = targetBrightness;

        if (currentAnimator != null && currentAnimator.isRunning()) {
            currentAnimator.cancel();
        }

        int currentBrightness = getCurrentBrightness();
        currentAnimator = ValueAnimator.ofInt(currentBrightness, targetBrightness);
        currentAnimator.setDuration(configDuration);
        currentAnimator.addUpdateListener(animation -> {
            int val = (int) animation.getAnimatedValue();
            try {
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, val);
            } catch (Exception e) {
                e.printStackTrace();
            }

        });
        currentAnimator.start();
    }

    private int getCurrentBrightness() {
        try {
            return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
        } catch (Settings.SettingNotFoundException e) {
            return 125;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sensorManager != null) sensorManager.unregisterListener(lightSensorListener);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID,
                    "AutoBrightnessService", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }
}
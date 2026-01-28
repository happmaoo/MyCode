package com.myapp.autobrightness;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Looper;
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
    private int consecutiveCount = 0;
    private int lastTargetBrightness = -1;
    private boolean firstStart = true;


    private Handler brightnessHandler = new Handler(Looper.getMainLooper());
    private Runnable brightnessRunnable;
    private int currentStep = 0;
    private static final int TOTAL_STEPS = 25; // 步数越多越平滑，25步通常足够

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();


        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenStateReceiver, filter);
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
                if (currentTime - lastProcessingTime < 500) return;
                lastProcessingTime = currentTime;

                lightLevel = event.values[0];
                //Log.i("TAG", "onSensorChanged: lightLevel"+lightLevel);

                // 发送广播到 UI (保持原样)
                Intent broadcastIntent = new Intent("com.myapp.LIGHT_LEVEL_UPDATE");
                broadcastIntent.putExtra("lightLevel", lightLevel);
                sendBroadcast(broadcastIntent);




                // --- 匹配逻辑优化：支持跨区间计数 & 极速响应 0 ---
                int currentTarget = -1;

                // 1. 寻找当前光照对应的目标亮度 (从高到低遍历)
                for (int i = cachedConfigData.size() - 1; i >= 0; i--) {
                    int[] rule = cachedConfigData.get(i);
                    if (lightLevel >= rule[0]) {
                        currentTarget = rule[1];
                        break;
                    }
                }

                // 2. 执行判定逻辑
                if (currentTarget != -1) {

                    // 特殊处理：如果光感值为0，立即调整亮度，不参与计数逻辑
                    if (lightLevel == 0) {
                        if (currentTarget != lastTargetBrightness) {
                            setScreenBrightnessSmoothly(currentTarget);
                            consecutiveCount = 0; // 重置计数，因为已经调整过了
                        }
                        return; // 直接返回，不再执行后续计数判断
                    }

                    // 常规处理：如果目标亮度发生了变化
                    if (currentTarget != lastTargetBrightness) {
                        consecutiveCount++;

                        // 达到连续计数阈值，执行调整
                        if (consecutiveCount >= configLmd) {
                            setScreenBrightnessSmoothly(currentTarget);
                            consecutiveCount = 0; // 调整完毕，重置计数
                        }
                    } else {
                        // 如果环境亮度回到了当前档位，说明环境已稳定，重置计数
                        consecutiveCount = 0;
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

        // 停止之前的任务
        if (brightnessRunnable != null) {
            brightnessHandler.removeCallbacks(brightnessRunnable);
        }

        lastTargetBrightness = targetBrightness;
        final int startBrightness = getCurrentBrightness();
        currentStep = 0;

        // 计算每一步的时间间隔 (总时长 / 总步数)
        final long stepDelay = configDuration / TOTAL_STEPS;

        brightnessRunnable = new Runnable() {
            @Override
            public void run() {
                currentStep++;

                // 计算当前步应该达到的亮度值 (线性插值)
                float fraction = (float) currentStep / TOTAL_STEPS;
                int nextVal = (int) (startBrightness + (targetBrightness - startBrightness) * fraction);

                try {
                    // 确保在调节前是手动模式，防止 MIUI 自动亮度冲突
                    if (currentStep == 1) {
                        Settings.System.putInt(getContentResolver(),
                                Settings.System.SCREEN_BRIGHTNESS_MODE,
                                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    }

                    Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, nextVal);
                    // Log.i("TAG", "后台调节进度: " + currentStep + "/" + TOTAL_STEPS + " 值: " + nextVal);
                } catch (Exception e) {
                    Log.e("TAG", "写入亮度失败", e);
                }

                // 如果还没达到总步数，继续跑下一步
                if (currentStep < TOTAL_STEPS) {
                    brightnessHandler.postDelayed(this, stepDelay);
                }
            }
        };

        // 立即开始执行
        brightnessHandler.post(brightnessRunnable);
    }

    private int getCurrentBrightness() {
        try {
            //Log.i("TAG", "---------getCurrentBrightness:-------- ");
            return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
        } catch (Settings.SettingNotFoundException e) {
            return 125;
        }
    }

    @Override
    public void onDestroy() {

        unregisterReceiver(screenStateReceiver);
        if (sensorManager != null) {
            sensorManager.unregisterListener(lightSensorListener);
        }



        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID,
                    "AutoBrightnessService", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }


    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                // 屏幕关闭：注销传感器监听，节省耗电
                if (sensorManager != null) {
                    sensorManager.unregisterListener(lightSensorListener);
                    Log.d("AutoBrightness", "屏幕关闭，停止采样");
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                // 屏幕开启：重新注册传感器监听
                if (sensorManager != null && lightSensor != null) {
                    sensorManager.registerListener(lightSensorListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
                    Log.d("AutoBrightness", "屏幕开启，恢复采样");
                }
            }
        }
    };
}
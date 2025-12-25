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
import java.util.List;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

public class autobrightness extends Service {

    private static final String CHANNEL_ID = "ForegroundServiceChannel";
    private SensorManager sensorManager;
    private Sensor lightSensor;
    private SensorEventListener lightSensorListener;
    private static String CONFIG = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AutoBrightness")
                .setContentText("AutoBrightness Service is running")
                .setSmallIcon(R.drawable.ic_launcher_foreground) // 确保你有一个通知图标
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);

        // 这里可以执行你的服务逻辑
        Log.d("TAG", "onStartCommand");



        // 获取 SensorManager 实例
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        // 初始化传感器监听器
        lightSensorListener = new SensorEventListener() {


            @Override
            public void onSensorChanged(SensorEvent event) {
                float lightLevel = event.values[0];
                //Log.d("TAG", String.valueOf(lightLevel));

                // 发送广播 改变主界面的文字
                Intent broadcastIntent = new Intent("com.myapp.LIGHT_LEVEL_UPDATE");
                broadcastIntent.putExtra("lightLevel", lightLevel);
                sendBroadcast(broadcastIntent);


                if (intent.getStringExtra("arg-config") != null) {
                    CONFIG = intent.getStringExtra("arg-config");
                }

                String[] lines = CONFIG.split("\\n");

                List<int[]> configData = new ArrayList<>();

                // 解析每行规则：每行包含三个数，分别代表范围起始、范围结束、亮度值
                // 例如 1 10 50 if ( 表示 1 >=10 <=50) 则设置亮度
                for (String line : lines) {
                    String[] numbers = line.trim().split("\\s+");
                    int start = Integer.parseInt(numbers[0]);
                    int end = Integer.parseInt(numbers[1]);
                    int brightness = Integer.parseInt(numbers[2]);
                    configData.add(new int[]{start, end, brightness});
                }

                for (int[] rule : configData) {
                    int start = rule[0];
                    int end = rule[1];
                    int brightness = rule[2];


                    if (lightLevel >= start && lightLevel <= end) {
                        //Log.d("TAG", "当前配置: "+start+end);
                        //setScreenBrightness(brightness);
                        checkb(lightLevel,start,end,brightness);
                        break;
                    }

                }



                /*
                if (lightLevel >= 0 && lightLevel <= 10) {
                    setScreenBrightness(50);
                }
                else if (lightLevel > 10 && lightLevel <= 200) {
                    setScreenBrightness(150);
                }

                 */

            }

            int lastStart = 0; // 用于记录上一次的 start 值
            int consecutiveCount = 0; // 用于记录连续出现相同 start 的次数

            public void checkb(float lightLevel,int start,int end,int brightness) {

                //Log.d("TAG", "当前设置: " + brightness +" start:"+start+" lastStart:"+lastStart);
                if (start == lastStart) {
                    consecutiveCount++; // 如果当前 start 与上一个相同，计数器加 1
                } else {
                    consecutiveCount = 1; // 如果不相同，重置计数器为 1
                    lastStart = start; // 更新 lastStart
                }

                if (consecutiveCount >= 3) { // 如果连续次数达到 3
                    //Log.d("TAG", "--------start 连续次数达到 3.------");
                    setScreenBrightnessSmoothly(brightness); // 调用设置亮度的方法
                    lastStart = 0;
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // 处理精度变化
            }
        };
        String arg1 = intent.getStringExtra("arg1");

        if("stop".equals(arg1)){
            sensorManager.unregisterListener(lightSensorListener);
        }
        else if("run".equals(arg1)){

            sensorManager.registerListener(lightSensorListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);

        }
        //sensorManager.registerListener(lightSensorListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        return START_STICKY;
    }



    private void setScreenBrightness(int brightnessValue) {
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, brightnessValue);
    }


    private void setScreenBrightnessSmoothly(int targetBrightness) {

        int currentBrightness = getCurrentBrightness();

        // 创建 ValueAnimator 动画对象，从当前亮度过渡到目标亮度
        ValueAnimator animator = ValueAnimator.ofInt(currentBrightness, targetBrightness);
        animator.setDuration(2000);  // 设置过渡时间为500毫秒
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                // 获取当前动画进度的亮度值
                int animatedValue = (int) animation.getAnimatedValue();
                // 更新系统亮度
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, animatedValue);
            }
        });
        animator.start();
    }



    // 获取当前的屏幕亮度
    private int getCurrentBrightness() {
        int currentBrightness = 0;
        try {
            currentBrightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
        } catch (Settings.SettingNotFoundException e) {
            e.printStackTrace();
        }
        return currentBrightness;
    }



    @Override
    public void onDestroy() {
        super.onDestroy();
        sensorManager.unregisterListener(lightSensorListener);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }
}

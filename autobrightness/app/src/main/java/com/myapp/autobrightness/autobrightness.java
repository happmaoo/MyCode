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
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.provider.Settings;

import androidx.core.app.NotificationCompat;

public class autobrightness extends Service {

    private static final String CHANNEL_ID = "ForegroundServiceChannel";
    private SensorManager sensorManager;
    private Sensor lightSensor;
    private SensorEventListener lightSensorListener;

    float lightLevel;
    private static String CONFIG = "";
    private static String CONFIG_main = "";
    private static int CONFIG_lmd = 3;
    private static int CONFIG_duration = 2;
    String arg1;

    private boolean firstStart = true; // 声明firstStart并设置初始值为true

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {







        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        if (firstStart) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AutoBrightness")
                .setContentText("AutoBrightness Service is running")
                .setSmallIcon(R.mipmap.ic_launcher) // 确保你有一个通知图标
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);
            // 将firstStart设置为false，避免重复启动前台通知
            firstStart = false;
        }

        // 这里可以执行你的服务逻辑
        Log.d("TAG", "onStartCommand");



        // 获取 SensorManager 实例
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        // 初始化传感器监听器
        lightSensorListener = new SensorEventListener() {


            @Override
            public void onSensorChanged(SensorEvent event) {
                lightLevel = event.values[0];
                //Log.d("TAG", String.valueOf(lightLevel));

                // 发送广播 改变主界面的文字
                Intent broadcastIntent = new Intent("com.myapp.LIGHT_LEVEL_UPDATE");
                broadcastIntent.putExtra("lightLevel", lightLevel);
                sendBroadcast(broadcastIntent);


                if (intent.getStringExtra("arg-config") != null) {
                    CONFIG = intent.getStringExtra("arg-config");
                    // 使用正则表达式查找 "lmd:5" 及其后面所有内容
                    Pattern pattern = Pattern.compile("lmd:(\\d+)/(\\d+)([\\s\\S]*)");
                    Matcher matcher = pattern.matcher(CONFIG);

                    if (matcher.find()) {
                        CONFIG_lmd = Integer.parseInt(matcher.group(1).trim());
                        CONFIG_duration= Integer.parseInt(matcher.group(2).trim())* 1000;
                        CONFIG_main = matcher.group(3).trim(); // 去掉开头或结尾的空白
                        //Log.d("CONFIG", "匹配到的内容:\n" + CONFIG_main);
                    } else {
                        Log.d("CONFIG", "未找到匹配内容");
                    }
                }

                String[] lines = CONFIG_main.split("\\n");

                List<int[]> configData = new ArrayList<>();

                // 解析每行规则：每行包含2个数，分别代表 光线强度低于  亮度值
                // 例如 0 50 if ( 表示光线强度>0) 则设置亮度50
                for (String line : lines) {
                    String[] numbers = line.trim().split("\\s+");
                    int mylight = Integer.parseInt(numbers[0]);
                    int mybrightness = Integer.parseInt(numbers[1]);
                    configData.add(new int[]{mylight,mybrightness});
                }

                for (int i = configData.size() - 1; i >= 0; i--) {
                    int[] rule = configData.get(i);
                    int light = rule[0];
                    int brightness = rule[1];


                    if (lightLevel  >= light) {
                        //Log.d("TAG", "设置亮度: "+brightness);
                        //setScreenBrightness(brightness);
                        checkb(lightLevel,light,brightness);
                        break;
                    }

                }


            }

            int lastlight = 0; // 用于记录上一次的 值
            int consecutiveCount = 0; // 用于记录连续出现相同 的次数

            public void checkb(float lightLevel,int mylight,int mybrightness) {

                if (mylight == 0) {
                    // 如果调用的是配置文件里光线=0时，暂停2秒后获取光线强度，如果是0就立即设置屏幕亮度
                    // 适用于晚上看不见的情况，因为这时光线传感器始终为0，不会触发 onSensorChanged
                    //Log.d("TAG", "lightLevel: "+lightLevel );
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (lightLevel == 0) {
                                setScreenBrightnessSmoothly(mybrightness);
                            }
                        }
                    }, 2000); // 延迟2秒 (2000毫秒)
                }


                //Log.d("TAG", "mylight: "+mylight );
                if (mylight == lastlight) {
                    consecutiveCount++; // 如果当前 与上一个相同，计数器加 1
                } else {
                    consecutiveCount = 1; // 如果不相同，重置计数器为 1
                    lastlight = mylight; // 更新
                }

                if (consecutiveCount >= CONFIG_lmd) { // 如果连续次数达到 CONFIG_lmd
                    //Log.d("TAG", "-------- 连续次数达到 "+CONFIG_lmd+"------");
                    setScreenBrightnessSmoothly(mybrightness); // 调用设置亮度的方法
                    lastlight = 0;
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // 处理精度变化
            }
        };

        // 这里是启动服务时自启动 sensorManager
        sensorManager.registerListener(lightSensorListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);

        // 创建广播接收器 接收 ScreenOffReceiver
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // 获取广播中传递的数据
                arg1 = intent.getStringExtra("arg1");
                // 处理信息
                //Log.d("TAG", "ACTION_SCREEN:"+arg1);
                if("stop".equals(arg1)){

                    //Log.d("TAG", "arg1:"+arg1);
                    sensorManager.unregisterListener(lightSensorListener);
                }
                else if("run".equals(arg1)){
                    //Log.d("TAG", "arg1:"+arg1);
                    sensorManager.registerListener(lightSensorListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);

                }
            }
        };
        // 注册广播接收器
        IntentFilter filter = new IntentFilter("com.myapp.ACTION");
        registerReceiver(receiver, filter);



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
        animator.setDuration(CONFIG_duration);  // 设置过渡时间
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

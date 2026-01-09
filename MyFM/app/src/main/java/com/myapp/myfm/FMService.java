package com.myapp.myfm;



import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.os.Looper;
import android.util.Log;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class FMService extends Service implements FMClient.MessageCallback {


    private MyFmApp application;
    List<RadioStation> stations;



    private static final String TAG = "FMService";
    private static final int NOTIFICATION_ID = 101;
    private static final String CHANNEL_ID = "FM_SERVICE_CHANNEL";

    // 广播 Actions 发到 Activity
    public static final String ACTION_LOG_UPDATE = "com.myapp.myfm.LOG_UPDATE";
    public static final String ACTION_STATUS_UPDATE = "com.myapp.myfm.STATUS_UPDATE";
    public static final String ACTION_VOLUME_UPDATE = "com.myapp.myfm.VOLUME_UPDATE";

    // 从 主界面和通知栏 发送服务启动停止命令
    public static final String ACTION_SERVICE_CMD = "com.myapp.myfm.SERVICE_CMD";

    // Extra 键名
    public static final String EXTRA_MESSAGE = "extra_message";
    public static final String EXTRA_VOLUME_LEVEL = "volume_level";


    private static final int SOURCE_RADIO_TUNER = 1998;
    private static final int SAMPLE_RATE = 48000;

    private volatile boolean isRunning = false;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread loopbackThread;

    private float currentFreq = 0.0f;
    private FMClient fmClient;
    private final IBinder binder = new LocalBinder();
    MyFmApp myapp;

    private String playstatus = "PAUSE";

    public class LocalBinder extends Binder {
        FMService getService() {
            return FMService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "FMService Created");

        application = (MyFmApp) getApplication();
        stations = application.getRadioStations();




        // 2. 立即启动前台通知，防止 Android 8+ 报 ANR
        startForegroundServiceNotification();



        // 可选：服务启动自动连接
        // fmClient.connect();

        // SharedPreferences 全局保存当前频率 用于 首次打开电台加载默认频率
        myapp = (MyFmApp) getApplicationContext();
        currentFreq = Float.parseFloat(myapp.getString("freq","93"));

        registerReceiver(actionReceiver, new android.content.IntentFilter(ACTION_SERVICE_CMD));

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }




    public String getCurrentState() {
        return playstatus;
    }




    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopLoopback(); // 必须先停止音频循环
        cancelNotification();
        if (fmClient != null) {
            fmClient.close();
        }
        unregisterReceiver(actionReceiver); // 别忘了注销广播
        super.onDestroy();
        System.exit(0);
    }

    /**
     * 启动前台服务通知 (适配 Android 7 - 14)
     */
    private void startForegroundServiceNotification() {
        // Android 8.0 (API 26) 及以上需要 NotificationChannel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "FM Radio Service",
                    NotificationManager.IMPORTANCE_LOW // LOW 级别无声音，适合后台常驻
            );
            channel.setDescription("FM 后台连接服务");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        // 点击通知栏跳转回 MainActivity
        Intent notificationIntent = new Intent(this, MainActivity.class);
        // Android 12 (API 31) 必须指定 FLAG_IMMUTABLE
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MyFM")
                .setContentText("准备就绪")
                .setSmallIcon(R.drawable.ic_radio)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true) // 禁止用户左右滑动清除
                .build();

        // 启动前台服务
        startForeground(NOTIFICATION_ID, notification);
    }





    // --- 代理 FMClient 方法供 Activity 调用 ---

    public void connectFm() {
        if (fmClient != null) fmClient.connect();
    }

    public void sendFmCommand(String cmd) {


        if (fmClient != null) {
            // 检查是否已连接（假设 FMClient 有 isConnected() 方法，如果没有见下方补充）
            if (!fmClient.isConnected()) {
                Log.w(TAG, "FMClient disconnected, attempting to reconnect before sending: " + cmd);
                fmClient.connect();

                // 策略 A：延迟重试（简单处理）
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (fmClient.isConnected()) {
                        fmClient.sendCommand(cmd);
                    } else {
                        Log.e(TAG, "Reconnect failed, command dropped: " + cmd);
                    }
                }, 500); // 给 500ms 尝试连接
            } else {
                fmClient.sendCommand(cmd);
            }
        }
    }

    // --- 回调实现：发送广播通知 Activity ---

    @Override
    public void onMessageReceived(String message) {
        Intent intent = new Intent(ACTION_LOG_UPDATE);
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.setPackage(getPackageName()); // 明确包名，安全且符合 Android 14 要求
        sendBroadcast(intent);
        Log.d("onMessageReceived:",message);

        // 通知栏频率处理
        String lastProcessedFreq = "";
        String lastDisplayedFreq = "";

        Matcher matcher_FREQ = Pattern.compile("FREQ:(\\d+\\.?\\d*)").matcher(message);
        if (matcher_FREQ.find()) {
            String incomingFreq = matcher_FREQ.group(1); // 刚拿到的

            if (!incomingFreq.equals(lastProcessedFreq) || !incomingFreq.equals(lastDisplayedFreq)) {
                lastProcessedFreq = incomingFreq;
                currentFreq = Float.parseFloat(incomingFreq);
                String pname = RadioStation.findNameByNumber(stations, incomingFreq);
                if (pname == null) { pname = ""; }
                updateNotificationText(incomingFreq, pname);
                lastDisplayedFreq = incomingFreq;
            }
        }



    }

    // 通知主界面状态改变
    @Override
    public void onStatusChanged(String status) {

        playstatus = status;



        Log.d(TAG, "onStatusChanged:"+status);
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra(EXTRA_MESSAGE, status);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void updateNotificationText(String freq, String name) {


        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        // 点击通知跳转 Activity
        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent mainPI = PendingIntent.getActivity(this, 0, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 停止服务按钮
        Intent stopIntent = new Intent(ACTION_SERVICE_CMD);
        stopIntent.setPackage(getPackageName());
        stopIntent.putExtra("cmd", "stop");
        PendingIntent stopPI = PendingIntent.getBroadcast(this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_radio)
                .setContentTitle("正在播放: " + freq + " MHz")
                .setContentText(name.isEmpty() ? "" : name)
                .setContentIntent(mainPI)
                .setOngoing(true)
                // --- 核心优化部分 ---
                .setPriority(NotificationCompat.PRIORITY_HIGH) // 提高优先级
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 锁屏可见
                // 关键：即使没文字也添加 BigTextStyle，这能强制通知在某些机型上默认展开
                .setStyle(new NotificationCompat.BigTextStyle().bigText(name.isEmpty() ? "电台正在后台运行" : name))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0))
                // 添加按钮
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止并退出服务", stopPI);

        manager.notify(NOTIFICATION_ID, builder.build());
    }


    /**
     * 取消前台服务通知
     */
    private void cancelNotification() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
            }

            // 停止前台服务（可选，但推荐）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cancelling notification", e);
        }
    }



    private int bufferSize = 1024; // 调小 bufferSize 可以增加跳动频率 (例如 512 或 1024)
    private float lastLevel = 0;   // 用于平滑衰减
    private final float DECAY_FACTOR = 0.85f; // 衰减系数，越小下降越快，跳动感越强

    private void startLoopback() {
        if (isRunning) return;

        final int minRecBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);

        final int minTrackBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);

        final int bufferSize = Math.max(minRecBuf, minTrackBuf) * 2;

        try {
            audioRecord = new AudioRecord(
                    SOURCE_RADIO_TUNER, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            audioTrack = new AudioTrack(
                    AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize,
                    AudioTrack.MODE_STREAM);


            isRunning = true;
            audioRecord.startRecording();
            audioTrack.play();



            loopbackThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    // 确保 bufferSize 为 2 的倍数
                    byte[] buffer = new byte[bufferSize];
                    short[] shortBuffer = new short[bufferSize / 2];

                    while (isRunning) {
                        int readBytes = audioRecord.read(buffer, 0, buffer.length);

                        if (readBytes > 0) {
                            // 1. 播放音频
                            audioTrack.write(buffer, 0, readBytes);

                            // 2. byte[] 转 short[]
                            ByteBuffer.wrap(buffer)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .asShortBuffer()
                                    .get(shortBuffer, 0, readBytes / 2);

                            // 3. 计算 RMS (均方根) 和 Peak (峰值)
                            double sum = 0;
                            double peak = 0;
                            int samples = readBytes / 2;

                            for (int i = 0; i < samples; i++) {
                                double sample = Math.abs(shortBuffer[i]);
                                sum += sample * sample;
                                if (sample > peak) peak = sample; // 记录峰值
                            }

                            double rms = Math.sqrt(sum / samples);

                            // 4. 将线性数值转换为分贝 (dB)
                            // 这样即使声音较小，数值变化也会很大，视觉效果更灵敏
                            // 参考值 32768.0 是 short 的最大值
                            double db = 20 * Math.log10(rms / 32768.0);

                            // 5. 将 dB 映射到 0~100 刻度
                            // 通常环境噪音在 -60dB 左右，最大声在 0dB
                            int targetLevel = (int) ((db + 60) * (100.0 / 60.0));

                            // 6. 动态范围增强：如果想让跳动更“暴力”，可以结合峰值
                            if (peak > 0) {
                                int peakLevel = (int) (peak / 32768.0 * 100);
                                targetLevel = (targetLevel + peakLevel) / 2;
                            }

                            // 7. 应用衰减逻辑（防止 UI 闪烁过快，但保持上升灵敏）
                            float finalLevel;
                            if (targetLevel >= lastLevel) {
                                // 如果音量在变大，立即跳上去
                                finalLevel = targetLevel;
                            } else {
                                // 如果音量在变小，按比例衰减，产生“Q弹”的落差感
                                finalLevel = lastLevel * DECAY_FACTOR;
                            }
                            lastLevel = finalLevel;

                            // 8. 边界限制
                            int resultLevel = Math.max(0, Math.min(100, (int) finalLevel));

                            // 9. 通知 UI
                            sendVolumeBroadcast(resultLevel);
                        }
                    }
                }
            });
            loopbackThread.start();

        } catch (Exception e) {
            e.printStackTrace();
            stopLoopback();
        }
    }


    private void sendVolumeBroadcast(int level) {
        Intent intent = new Intent(ACTION_VOLUME_UPDATE);
        intent.putExtra(EXTRA_VOLUME_LEVEL, level);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }


    private void stopLoopback() {
        isRunning = false;
        try {
            if (loopbackThread != null) {
                loopbackThread.join(500);
                loopbackThread = null;
            }
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
                audioTrack = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void runFMRoot(String path) {
        try {
            // 使用完整的shell命令，确保su能退出，不然会有2个进程
            String cmd = "(" + path + " </dev/null >/dev/null 2>&1 &) && exit";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            // 立即关闭所有流
            p.getOutputStream().close();
            // 快速消费输出
            byte[] buffer = new byte[1024];
            p.getInputStream().read(buffer);
            p.getErrorStream().read(buffer);
            Thread.sleep(100);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    private String installFMExecutable() {
        try {
            File outFile = new File(getFilesDir(), "fm_service");
            if (!outFile.exists()) {
                InputStream is = getAssets().open("fm_service");
                FileOutputStream fos = new FileOutputStream(outFile);
                byte[] buffer = new byte[1024];
                int len;
                while ((len = is.read(buffer)) > 0) fos.write(buffer, 0, len);
                fos.close();
                is.close();
                Runtime.getRuntime().exec("chmod 777 " + outFile.getAbsolutePath());
            }
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }



    private final android.content.BroadcastReceiver actionReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_SERVICE_CMD.equals(intent.getAction())) {
                String cmd = intent.getStringExtra("cmd");
                if("stop".equals(cmd)){
                    Log.d(TAG, "通过通知栏停止服务");
                    sendFmCommand("QUIT");
                    // 1. 立即取消通知栏
                    NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (manager != null) manager.cancel(NOTIFICATION_ID);

                    // 2. 停止服务
                    stopForeground(true);
                    myapp.saveBoolean("running",false);
                    stopSelf();
                    System.exit(0);
                }
                if("start".equals(cmd)){

                    if (!isRunning) {
                        String fmPath = installFMExecutable();
                        runFMRoot(fmPath);
                    }

                    try { Thread.sleep(1000);} catch (InterruptedException e) {e.printStackTrace();}

                    // 1. 初始化 FMClient
                    fmClient = new FMClient(FMService.this);
                    fmClient.connect();
                    try { Thread.sleep(500);} catch (InterruptedException e) {e.printStackTrace();}
                    // 启动音频循环
                    startLoopback();

                    new android.os.Handler().postDelayed(() -> {
                        sendFmCommand("TUNE "+currentFreq);
                        sendFmCommand("UNMUTE");
                        sendFmCommand("PUSH 1");
                    }, 500); // 延迟秒
                    onStatusChanged("PLAY");

                }
                if("pause".equals(cmd)){
                    sendFmCommand("QUIT");
                    stopLoopback();

                    // 关闭FMClient连接
                    if (fmClient != null) {
                        Log.d(TAG, "正在关闭FMClient连接...");
                        try {
                            // 调用FMClient的close方法关闭Socket和线程池
                            fmClient.close();
                        } catch (Exception e) {
                            Log.e(TAG, "关闭FMClient时出错", e);
                        }
                        fmClient = null; // 设置为null，确保资源被释放
                    }


                    onStatusChanged("PAUSE");

                    try {
                        Runtime.getRuntime().exec(new String[]{"su", "-c", "killall fm_service"});
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    };




}

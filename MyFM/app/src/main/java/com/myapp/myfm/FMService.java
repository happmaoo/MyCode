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





public class FMService extends Service implements FMClient.MessageCallback {

    private static final String TAG = "FMService";
    private static final int NOTIFICATION_ID = 101;
    private static final String CHANNEL_ID = "FM_SERVICE_CHANNEL";

    // 广播 Action
    public static final String ACTION_LOG_UPDATE = "com.myapp.myfm2.LOG_UPDATE";
    public static final String ACTION_STATUS_UPDATE = "com.myapp.myfm2.STATUS_UPDATE";
    public static final String EXTRA_MESSAGE = "extra_message";

    // ⭐ 新增状态变量用于通知栏显示
    private float currentFreq = 0.0f;
    private String currentStationName = "Unknown Station";

    private static final int SOURCE_RADIO_TUNER = 1998;
    private static final int SAMPLE_RATE = 48000;

    private volatile boolean isRunning = false;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread loopbackThread;


    private FMClient fmClient;
    private Process fmProcess;
    private final IBinder binder = new LocalBinder();
    MyFmApp myapp;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private short[] shortBuffer; // 复用 buffer 减少 GC



    public class LocalBinder extends Binder {
        FMService getService() {
            return FMService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "FMService Created");

        // 1. 初始化 FMClient
        fmClient = new FMClient(this);

        // 2. 立即启动前台通知，防止 Android 8+ 报 ANR
        startForegroundServiceNotification();

        // 可选：服务启动自动连接
        // fmClient.connect();

        // SharedPreferences 全局保存当前频率 用于 首次打开电台加载默认频率
        myapp = (MyFmApp) getApplicationContext();
        currentFreq = Float.parseFloat(myapp.getString("freq","93"));

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {



        if (!isRunning) {
            String fmPath = installFMExecutable();
            if (fmPath != null) {
                runFMRoot(fmPath);
            }
        }


        try { Thread.sleep(3000);} catch (InterruptedException e) {e.printStackTrace();}

        // 启动音频循环
        startLoopback();

        new android.os.Handler().postDelayed(() -> {
            sendFmCommand("TUNE "+currentFreq);
            Log.d(TAG, "onStartCommand");
            sendFmCommand("UNTUNE");
        }, 1000); // 延迟1秒

        // 确保服务重启机制
        return START_STICKY;
    }


    private void runFMRoot(String path) {
        try {
            fmProcess = Runtime.getRuntime().exec(new String[]{"su", "-c", path});
        } catch (Exception e) {
            e.printStackTrace();
        }
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
        if (fmProcess != null) {
            fmProcess.destroy(); // 杀死 su 进程
        }
        super.onDestroy();
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
                .setContentText("正在后台运行...")
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
    }

    @Override
    public void onStatusChanged(String status) {
        // 更新通知栏文字（可选）

        String freqtext = myapp.getString("freq","93");
        updateNotificationText(freqtext,"");
        Log.d(TAG, "onStatusChanged:"+status);
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra(EXTRA_MESSAGE, status);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void updateNotificationText(String freq,String name) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            int pendingFlags =  PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);

            // ⭐ 使用当前频率和名称构建通知内容
            String contentTitle = freq;
            String contentText = name;

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(contentTitle)
                    .setContentText(contentText)
                    .setSmallIcon(R.drawable.ic_radio)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true) // 防止每次更新都震动/闪烁
                    .build();
            manager.notify(NOTIFICATION_ID, notification);
        }
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


    // 1. 定义回调接口
    public interface OnVolumeChangeListener {
        void onVolumeChanged(int level);
    }

    private OnVolumeChangeListener volumeChangeListener;

    // 2. 暴露给 Activity 设置监听器
    public void setOnVolumeChangeListener(OnVolumeChangeListener listener) {
        this.volumeChangeListener = listener;
    }




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
                private float smoothedLevel = 0f;
                private final float alpha = 0.2f;
                private long lastUiUpdateTime = 0; // 用于控制 UI 刷新频率

                @Override
                public void run() {
                    byte[] buffer = new byte[bufferSize];

                    while (isRunning) {
                        int readBytes = audioRecord.read(buffer, 0, buffer.length);
                        if (readBytes > 0) {
                            audioTrack.write(buffer, 0, readBytes);

                            // 计算峰值而非RMS，波动更剧烈
                            int maxAmplitude = 0;
                            for (int i = 0; i < readBytes; i += 2) {
                                short sample = (short)((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
                                int amplitude = Math.abs(sample);
                                if (amplitude > maxAmplitude) {
                                    maxAmplitude = amplitude;
                                }
                            }

                            // 直接使用峰值，不使用log计算，波动更大
                            int level;
                            if (maxAmplitude < 100) {
                                level = 0;
                            } else if (maxAmplitude > 30000) {
                                level = 100;
                            } else {
                                // 非线性放大：低音量部分放大更多
                                level = (int)(Math.pow(maxAmplitude / 30000.0, 0.5) * 80);
                            }

                            // 添加随机波动（±10%）
                            int randomFactor = (int)(Math.random() * 20) - 10;
                            level = Math.max(0, Math.min(100, level + randomFactor));

                            // 更新UI（每16ms更新一次，更快刷新）
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastUiUpdateTime > 30) {
                                final int finalLevel = level;

                                // --- 核心修复开始 ---
                                final OnVolumeChangeListener listener = volumeChangeListener;
                                if (listener != null) {
                                    mainHandler.post(() -> {
                                        try {
                                            // 即使此时全局变量 volumeChangeListener 被设为 null 了
                                            // 这里的局部变量 listener 依然指向之前的对象，不会 NPE
                                            listener.onVolumeChanged(finalLevel);
                                        } catch (Exception e) {
                                            Log.e(TAG, "Callback failed", e);
                                        }
                                    });
                                }
                                lastUiUpdateTime = currentTime;
                            }
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











}

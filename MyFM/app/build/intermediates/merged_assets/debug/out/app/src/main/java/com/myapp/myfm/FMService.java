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

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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

    int NotificationToggleIcon = 0;
    private String headsetEvent;

    AudioManager audioManager;

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


        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // 可选：服务启动自动连接
        // fmClient.connect();

        // SharedPreferences 全局保存当前频率 用于 首次打开电台加载默认频率
        myapp = (MyFmApp) getApplicationContext();
        currentFreq = Float.parseFloat(myapp.getString("freq","93"));

        registerReceiver(actionReceiver, new android.content.IntentFilter(ACTION_SERVICE_CMD));


        // 获取耳机的event序号
        if("".equals(myapp.getString("HeadsetEvent", ""))) {
            headsetEvent = findHeadsetEvent();
            myapp.setString("HeadsetEvent", headsetEvent);
            Log.i(TAG, "HeadsetEvent 获取成功.");
        }else{
            headsetEvent = myapp.getString("HeadsetEvent", "");
        }

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
                stations = application.getRadioStations();

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
//
//        playstatus = status;
//
//
//
//        Log.d(TAG, "onStatusChanged:"+status);
//        Intent intent = new Intent(ACTION_STATUS_UPDATE);
//        intent.putExtra(EXTRA_MESSAGE, status);
//        intent.setPackage(getPackageName());
//        sendBroadcast(intent);
    }


    public void Status(String status) {

        playstatus = status;

        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra(EXTRA_MESSAGE, status);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void updateNotificationText(String freq, String name) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        // 1. 点击通知跳转 Activity
        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent mainPI = PendingIntent.getActivity(this, 0, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(ACTION_SERVICE_CMD);
        stopIntent.setPackage(getPackageName());
        stopIntent.putExtra("cmd", "stop");
        PendingIntent stopPI = PendingIntent.getBroadcast(this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent muteIntent = new Intent(ACTION_SERVICE_CMD);
        muteIntent.setPackage(getPackageName());
        muteIntent.putExtra("cmd", "toggle");
        PendingIntent pausePI = PendingIntent.getBroadcast(this, 3, muteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int toggleIcon;
        String toggleLabel;

        Log.i(TAG, "NotificationToggleIcon: "+NotificationToggleIcon);
        if (NotificationToggleIcon==1) {
            toggleIcon = android.R.drawable.ic_media_play;
            toggleLabel = "播放";
        }else{
            toggleIcon = android.R.drawable.ic_media_pause;
            toggleLabel = "暂停";
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_radio)
                .setContentTitle(name.isEmpty() ? "" : name + " - " + freq + " MHz")
                .setContentText("服务运行中")
                .setContentIntent(mainPI)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                // 注意：setStyle 只能设置一次。如果要用 MediaStyle，请删除之前的 BigTextStyle
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        // 这里的参数 0, 1 代表在折叠后的精简视图中显示前两个按钮
                        .setShowActionsInCompactView(0, 1))

                // --- 添加按钮 ---
                .addAction(toggleIcon, toggleLabel, pausePI) // 第 0 个按钮
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止退出", stopPI); // 第 1 个按钮

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



    private int lastLevel = 0;

    private void startLoopback() {
        if (isRunning) return;


        if(myapp.getBoolean("forceSPK", true)) {
            runcmd("sendevent /dev/input/" + headsetEvent + " 5 2 0");
            runcmd("sendevent /dev/input/" + headsetEvent + " 0 0 0");
        }


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
                    byte[] buffer = new byte[bufferSize];
                    short[] shortBuffer = new short[bufferSize / 2];

                    while (isRunning) {
                        int readBytes = audioRecord.read(buffer, 0, buffer.length);
                        if (readBytes > 0) {
                            audioTrack.write(buffer, 0, readBytes);

                            ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                                    .asShortBuffer().get(shortBuffer, 0, readBytes / 2);

                            // 1. 改为计算 Peak (峰值) 结合 RMS，增加灵敏度
                            double maxAbs = 0;
                            int samples = readBytes / 2;
                            for (int i = 0; i < samples; i++) {
                                int val = Math.abs(shortBuffer[i]);
                                if (val > maxAbs) maxAbs = val; // 捕获瞬时最大跳动
                            }

                            // 2. 核心算法修改：增加动态系数
                            // 将原有的线性映射逻辑修改为：(原始占比 * 灵敏度系数)
                            // 这里的 1.5 是灵敏度因子，可以根据需要调整 (1.0~2.5)
                            int level = (int) (maxAbs / 80000 * 100 * 1.5);

                            // 3. 加入“非对称平衡”：让它弹上去快，掉下来有过程
                            if (level < lastLevel) {
                                level = (int) (lastLevel * 0.8); // 缓慢掉落，避免闪烁
                            }
                            lastLevel = level;

                            if (level > 100) level = 100;

                            sendVolumeBroadcast(level);
                        }
                    }
                }
            });
            loopbackThread.start();

        } catch (Exception e) {
            e.printStackTrace();
            stopLoopback();
        }


        // 开始播放，获取双击亮屏设置
        if(myapp.getBoolean("setting_tap_to_wake",false)){
            runcmd("settings put secure double_tap_to_wake 1");
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

        // 停止播放，取消双击亮屏
        if(myapp.getBoolean("setting_tap_to_wake",false)){
            runcmd("settings put secure double_tap_to_wake 0");
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
                if ("toggle".equals(cmd)) {
                        Log.d(TAG, "cmd:toggle,playstatus:"+playstatus);
                    if ("PLAY".equals(playstatus)) {
                        cmd = "pause";
                        NotificationToggleIcon = 1;
                        updateNotificationText("", "已停止");
                    } else {
                        cmd = "start";
                        NotificationToggleIcon = 0;
                        updateNotificationText("", "正在播放");
                    }
                }
                if("stop".equals(cmd)){
                    Log.d(TAG, "通过通知栏停止服务");
                    sendFmCommand("QUIT");
                    stopLoopback();
                    // 1. 立即取消通知栏
                    NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (manager != null) manager.cancel(NOTIFICATION_ID);

                    // 2. 停止服务
                    stopForeground(true);
                    myapp.setBoolean("running",false);
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
                    Status("PLAY");
                    NotificationToggleIcon = 0;
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


                    Status("PAUSE");

                    try {
                        Runtime.getRuntime().exec(new String[]{"su", "-c", "killall fm_service"});
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    NotificationToggleIcon = 1;
                    updateNotificationText("", "已停止");
                }
            }
        }
    };


    private void runcmd(String cmd) {
        try {
            // 使用完整的shell命令，确保su能退出，不然会有2个进程
            String mycmd = "(" + cmd + " </dev/null >/dev/null 2>&1 &) && exit";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", mycmd});
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

    // 找到耳机对应的 event 序号
    public static String findHeadsetEvent() {
        Process process = null;
        DataOutputStream os = null;
        BufferedReader reader = null;
        StringBuilder output = new StringBuilder();

        try {
            // 获取 root 权限
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            // 要执行的命令
            String command = "cat /proc/bus/input/devices | grep -iA 8 'Headset Jack' | grep 'Handlers' | head -n 1 | grep -oE 'event[0-9]+'";

            // 执行命令
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            // 读取输出
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            // 等待命令执行完成
            process.waitFor();

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            try {
                if (os != null) os.close();
                if (reader != null) reader.close();
                if (process != null) process.destroy();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return output.toString().trim();
    }

}

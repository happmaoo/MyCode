package com.myapp.myfm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


public class FMService extends Service {

    private static final String CHANNEL_ID = "FM_Service_Channel";
    private static final int NOTIFICATION_ID = 1;

    private static final int SOURCE_RADIO_TUNER = 1998;
    private static final int SAMPLE_RATE = 48000;

    private volatile boolean isRunning = false; // ⭐ 确保线程可见性
    private Thread loopbackThread;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Process fmProcess;




    // ⭐ 新增状态变量用于通知栏显示
    private float currentFreq = 0.0f;
    private String currentStationName = "Unknown Station";
    //  增状态变量用于存储最新的 FM 服务端发来的命令响应
    private volatile String ServerResponse = "SERVICE_START";

    // ⭐ 优化 2: 创建单个后台线程的执行器来处理所有 FM 命令
    private ExecutorService commandExecutor;



    public static final String ACTION_VOLUME_UPDATE =
            "com.myapp.myfm.VOLUME_UPDATE";






    // ========= Socket控制类 =========
    public class FmClient {

        private static final String SOCKET_NAME = "fm_service";




        // ⭐ 优化 3: 异步发送命令
        public void sendCommandAsync(final String cmd) {
            if (commandExecutor == null || commandExecutor.isShutdown()) {
                Log.e("FmClient", "Executor is not running. Command not sent: " + cmd);
                return;
            }

            commandExecutor.submit(() -> {
                String response = sendCommand(cmd); // 在后台线程执行同步 I/O
                ServerResponse = response;
                sendResponseBroadcast(response); // 发到主界面
                Log.d("MyFmClient", "CMD: " + cmd + " -> RESP: " + response);
            });
        }

        // 保留同步方法，但只在 ExecutorService 内部调用
        private String sendCommand(String cmd) {
            LocalSocket socket = new LocalSocket();
            BufferedReader reader = null;
            OutputStream os = null;
            try {
                LocalSocketAddress address = new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT);
                socket.connect(address);
                socket.setSoTimeout(10000);

                os = socket.getOutputStream();
                String packet = cmd + "\r\n";
                os.write(packet.getBytes("US-ASCII"));
                os.flush();

                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "US-ASCII"));
                String response = reader.readLine();

                // ⭐ 优化 4: 移除重复的 socket.close()，统一到 finally
                return response;

            } catch (IOException e) {
                // e.printStackTrace(); // 频繁的错误日志可能干扰调试
                return "ERROR:Connection_Failed";
            } finally {
                // ⭐ 优化 4: 确保所有资源被关闭
                try {
                    if (reader != null) reader.close();
                } catch (IOException ignored) {}
                try {
                    if (os != null) os.close();
                } catch (IOException ignored) {}
                try {
                    if (socket != null) socket.close();
                } catch (IOException ignored) {}
            }
        }

    }

    private FmClient fmClient = new FmClient();


    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        commandExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {




        Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (!isRunning) {
            String fmPath = installFMExecutable();
            if (fmPath != null) {
                runFMRoot(fmPath);
            }

            // 启动音频循环
            startLoopback();

            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            // 首次启动传递默认频率
            String freqStr = intent.getStringExtra("freq");
            float freq = 87.5f;  // 默认值
            freq = Float.parseFloat(freqStr);

            fmClient.sendCommandAsync("TUNE " + freq);
            fmClient.sendCommandAsync("UNMUTE");

        }

        // 如果传递了新的频率，设置新的频率
        if (intent != null && intent.hasExtra("freq")) {
            float freq = intent.getFloatExtra("freq", 0);
            String stationName = intent.getStringExtra("station_name"); // 用于通知栏更新

            if (freq > 0) {
                // 更新服务内的状态
                currentFreq = freq;
                if (stationName != null) {
                    currentStationName = stationName;
                } else {
                    currentStationName = String.format("%.1f", freq); // 默认显示频率
                }

                // 只发送TUNE命令来更改频率 (异步)
                fmClient.sendCommandAsync("TUNE " + freq);
                Log.d("FMService", "Changing frequency to: " + freq);

                // 每次频率更改后，更新通知
                Notification updatedNotification = buildNotification();
                NotificationManager mNotificationManager =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                mNotificationManager.notify(NOTIFICATION_ID, updatedNotification);
            }
        }



        if (intent != null && intent.hasExtra("seek")) {
            int dir = intent.getIntExtra("seek", 0);
            if(dir == 0){
                fmClient.sendCommandAsync("SEEK " + 0);
                Log.d("FMService", "seek pre.");
            }else{
                fmClient.sendCommandAsync("SEEK " + 1);
                Log.d("FMService", "seek next.");
            }
        }

        return START_STICKY;
    }

    // 发送服务器响应 到主界面
    private void sendResponseBroadcast(String response) {
        Intent intent = new Intent("com.myapp.myfm.SERVER_RESPONSE");
        intent.putExtra("response_data", response);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // ⭐ 优化 5: 在销毁时关闭执行器
        if (commandExecutor != null) {
            commandExecutor.shutdownNow();
        }

        if (fmClient != null) {
            fmClient.sendCommand("QUIT");
        }

        stopLoopback();

    }

    private void runFMRoot(String path) {
        try {
            fmProcess = Runtime.getRuntime().exec(new String[]{"su", "-c", path});
        } catch (Exception e) {
            e.printStackTrace();
        }
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
                @Override
                public void run() {
                    byte[] buffer = new byte[bufferSize];
                    short[] shortBuffer = new short[bufferSize / 2];

                    while (isRunning) {
                        int readBytes = audioRecord.read(buffer, 0, buffer.length);

                        if (readBytes > 0) {
                            audioTrack.write(buffer, 0, readBytes);

                            // ⭐ byte → short
                            ByteBuffer.wrap(buffer)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .asShortBuffer()
                                    .get(shortBuffer, 0, readBytes / 2);

                            // ⭐ 计算 RMS
                            double sum = 0;
                            int samples = readBytes / 2;

                            for (int i = 0; i < samples; i++) {
                                sum += shortBuffer[i] * shortBuffer[i];
                            }

                            double rms = Math.sqrt(sum / samples);

                            // ⭐ 映射到 0~100
                            int level = (int) (rms / 32768.0 * 100);
                            if (level > 100) level = 100;

                            // ⭐ 通知 UI
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

    }


    private void sendVolumeBroadcast(int level) {
        Intent intent = new Intent(ACTION_VOLUME_UPDATE);
        intent.putExtra("volume_level", level);
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

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        // ⭐ 使用当前频率和名称构建通知内容
        String contentTitle = currentStationName;
        String contentText = "Frequency: " + String.format("%.1f", currentFreq) + " MHz"; // 格式化频率

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(contentTitle) // ⭐ 使用电台名称作为标题
                .setContentText(contentText)   // ⭐ 使用频率作为内容
                .setSmallIcon(R.drawable.ic_radio)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "FM Service Channel", NotificationManager.IMPORTANCE_LOW);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }




}



package com.myapp.myaudiorecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.util.Pair;
import androidx.lifecycle.Observer;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MyAudioRecorderService extends Service {

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "MyAudioRecorderService";
    private static final String TAG = "MyAudioRecorderService";


    private MediaRecorder recorder;
    private String fileName;
    AudioStreamer streamer;
    private Observer<Pair<String, String>> messageObserver; // 添加观察者变量

    private boolean isRecording;
    MyApp myapp;


    private long startTime;
    private Handler timerHandler = new Handler();
    private Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording) {
                long millis = System.currentTimeMillis() - startTime;
                int seconds = (int) (millis / 1000);
                int minutes = seconds / 60;
                seconds = seconds % 60;

                Log.d(TAG, String.format("当前录制时长: %02d:%02d", minutes, seconds));
                sendMessage(String.format("当前录制时长: %02d:%02d", minutes, seconds));

                // 每隔一秒更新一次
                timerHandler.postDelayed(this, 1000);
            }
        }
    };




    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onDestroy() {
        if (messageObserver != null) {
            DataManager.getInstance().getLiveDataMessage().removeObserver(messageObserver);
        }
        stopRecording();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {


        myapp = (MyApp) getApplicationContext();

        setupMessageObserver();

        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Service Running")
                //.setContentText("Doing important work...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);



        //DataManager.getInstance().sendMessage("MyService", "开始录音");




        //startRecording();





        return START_STICKY;
    }



    // 设置消息观察者
    private void setupMessageObserver() {
        messageObserver = new Observer<Pair<String, String>>() {
            @Override
            public void onChanged(Pair<String, String> pair) {
                if (pair != null) {
                    String from = pair.first;
                    String content = pair.second;
                    Log.i(TAG, "来自 " + from + " 的消息: " + content);

                    if ("Service".equals(from)) {
                        // 处理来自服务的消息
                    } else if ("Activity".equals(from)) {
                        // 处理来自Activity的消息
                        handleActivityMessage(content);
                    }
                }
            }
        };

        // 使用observeForever而不是observe
        DataManager.getInstance().getLiveDataMessage().observeForever(messageObserver);
    }


    private void sendMessage(String content) {
        DataManager.getInstance().sendMessage("Service", content);
    }


    // 处理来自Activity的消息
    private void handleActivityMessage(String content) {
        switch (content) {
            case "startRecording":
                startRecording();
                sendMessage("本地录音已开始");
                break;
            case "stopRecording":
                stopRecording();
                //sendMessage("本地录音已停止");
                break;
            case "startNetRecording":
                startNetRecording();
                sendMessage("网络录音已开始");
                break;
            case "stopNetRecording":
                stopNetRecording();
                sendMessage("网络录音已停止");
                break;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "MyAudioRecorderService",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("AudioRecorder");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }



    private void startNetRecording() {
        String net = myapp.getString("net", "");
        String[] parts = net.split(":");
        String ip = parts[0];
        int port = Integer.parseInt(parts[1]);

        streamer = new AudioStreamer(ip, port);
        streamer.start();
        isRecording = true;
        myapp.isRecording = true;
        //电脑:
        //ffmpeg -i tcp://0.0.0.0:7777?listen -c copy "output-$(date +'%Y%m%d-%H.%M.%S').aac"
    }

    private void stopNetRecording() {
        if(isRecording){
            streamer.stop();
        }
        isRecording = false;
        myapp.isRecording = false;
    }




    private void startRecording() {
        isRecording = true;
        myapp.isRecording = true;

        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);

        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

        String timeStamp = new SimpleDateFormat("yyyyMMdd-HH.mm.ss", Locale.getDefault()).format(new Date());
        String dirPath = myapp.getString("local","");
        File dir = new File(dirPath);
        if (!dir.exists()) {
            boolean success = dir.mkdirs();
            if (!success) {
                Log.e(TAG, "无法创建目录");
                return;
            }
        }
        fileName = dirPath + "/"+ timeStamp +".m4a";


        recorder.setOutputFile(fileName);
        recorder.setAudioSamplingRate(44100); // 44.1kHz 高音质
        recorder.setAudioEncodingBitRate(96000); // 96kbps

        try {
            recorder.prepare();
            recorder.start();

            startTime = System.currentTimeMillis(); // 记录开始时间
            timerHandler.postDelayed(timerRunnable, 0); // 启动计时器

        } catch (IOException e) {
            e.printStackTrace();
            sendMessage("错误!");
        }
    }

    private void stopRecording() {
        isRecording = false;
        myapp.isRecording = false;

        if (recorder != null) {
            recorder.stop();
            recorder.release();
            recorder = null;
            //Toast.makeText(this, "录音保存至: " + fileName, Toast.LENGTH_LONG).show();


            Log.i(TAG, "录音保存至: "+fileName);
            sendMessage("录音保存至: "+fileName);
        }
    }




}

package com.myapp.myaudiorecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MyAudioRecorderService extends Service {

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "my_channel";
    private static final String TAG = "MyService";


    private MediaRecorder recorder;
    private String fileName;

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Service Running")
                .setContentText("Doing important work...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);


        startRecording();


        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "My Foreground Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Channel description");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }




    private void startRecording() {
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);

        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

        String timeStamp = new SimpleDateFormat("yyyyMMdd-HH.mm.ss", Locale.getDefault()).format(new Date());
        String dirPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/myapp/MyAudioRecorder";
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

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void stopRecording() {
        if (recorder != null) {
            recorder.stop();
            recorder.release();
            recorder = null;
            //Toast.makeText(this, "录音保存至: " + fileName, Toast.LENGTH_LONG).show();
            Log.i(TAG, "录音保存至: "+fileName);
        }
    }




}

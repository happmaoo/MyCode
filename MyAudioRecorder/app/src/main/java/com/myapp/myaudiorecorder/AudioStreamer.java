package com.myapp.myaudiorecorder;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Handler;
import android.util.Log;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

public class AudioStreamer {
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int SAMPLE_RATE = 44100;
    private static final int BIT_RATE = 96000; // 96kbps
    private static final int CHANNEL_COUNT = 1; // 单声道

    private AudioRecord audioRecord;
    private MediaCodec mediaCodec;
    private boolean isRecording = false;
    private String serverIp;
    private int serverPort;



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

                Log.d("TAG", String.format("当前录制时长: %02d:%02d", minutes, seconds));
                DataManager.getInstance().sendMessage("AudioStreamer",String.format("当前录制时长: %02d:%02d", minutes, seconds));

                // 每隔一秒更新一次
                timerHandler.postDelayed(this, 1000);
            }
        }
    };



    public AudioStreamer(String ip, int port) {
        this.serverIp = ip;
        this.serverPort = port;
    }

    public void start() {
        isRecording = true;
        new Thread(this::recordingLoop).start();
    }

    public void stop() {
        isRecording = false;
    }

    private void recordingLoop() {
        Socket socket = null;
        OutputStream outputStream = null;

        try {
            // 1. 建立 Socket 连接
            DataManager.getInstance().sendMessage("AudioStreamer", "正在连接");

            socket = new Socket(serverIp, serverPort);
            outputStream = socket.getOutputStream();


            startTime = System.currentTimeMillis(); // 记录开始时间
            timerHandler.postDelayed(timerRunnable, 0); // 启动计时器

            // 2. 初始化 AudioRecord
            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            // 3. 初始化 MediaCodec
            MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize);

            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mediaCodec.start();

            audioRecord.startRecording();

            byte[] pcmBuffer = new byte[bufferSize];
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            while (isRecording) {
                // 读取 PCM 数据
                int readSize = audioRecord.read(pcmBuffer, 0, pcmBuffer.length);
                if (readSize > 0) {
                    // 送入编码器
                    int inputBufferIndex = mediaCodec.dequeueInputBuffer(10000);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                        inputBuffer.clear();
                        inputBuffer.put(pcmBuffer, 0, readSize);
                        mediaCodec.queueInputBuffer(inputBufferIndex, 0, readSize, System.nanoTime() / 1000, 0);
                    }
                }

                // 获取编码后的数据
                int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
                while (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex);
                    int outPacketSize = bufferInfo.size + 7; // 加上 7 字节的 ADTS 头

                    byte[] outData = new byte[outPacketSize];
                    addADTStoPacket(outData, outPacketSize); // 添加 ADTS 头
                    outputBuffer.get(outData, 7, bufferInfo.size); // 将编码数据放入 ADTS 头之后

                    // 通过 Socket 发送
                    outputStream.write(outData, 0, outPacketSize);

                    mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                    outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            DataManager.getInstance().sendMessage("AudioStreamer", "连接失败");
            // 1. 移除所有的回调
            timerHandler.removeCallbacks(timerRunnable);

        } finally {
            cleanup(socket, outputStream);
        }
    }

    /**
     * 添加 ADTS 头 (AAC 音频流必须)
     */
    private void addADTStoPacket(byte[] packet, int packetLen) {
        int profile = 2;  // AAC LC
        int freqIdx = 4;  // 44.1KHz
        int chanCfg = 1;  // Mono

        packet[0] = (byte)0xFF;
        packet[1] = (byte)0xF1;
        packet[2] = (byte)(((profile-1)<<6) + (freqIdx<<2) +(chanCfg>>2));
        packet[3] = (byte)(((chanCfg&3)<<6) + (packetLen>>11));
        packet[4] = (byte)((packetLen&0x7FF) >> 3);
        packet[5] = (byte)(((packetLen&7)<<5) + 0x1F);
        packet[6] = (byte)0xFC;
    }

    private void cleanup(Socket s, OutputStream os) {
        try {
            if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); }
            if (mediaCodec != null) { mediaCodec.stop(); mediaCodec.release(); }
            if (os != null) os.close();
            if (s != null) s.close();
        } catch (Exception e) { e.printStackTrace(); }
    }
}
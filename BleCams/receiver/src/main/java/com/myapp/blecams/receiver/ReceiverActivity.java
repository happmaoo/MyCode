package com.myapp.blecams.receiver;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.myapp.blecams.protocol.Perms;
import com.myapp.blecams.protocol.VideoProfile;
import com.myapp.blecams.receiver.ble.BleStreamClient;
import com.myapp.blecams.protocol.AuReassembler;
import com.myapp.blecams.receiver.video.AvcDecoder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 接收端 Demo：扫描并连接服务端，订阅 notify，重组 AU，硬解码显示。
 *
 * <p>这条链路的正确姿势是<b>宁可丢帧也不要堆积</b>：分包一到就交给组帧线程，组好的 AU
 * 丢进长度为 4 的解码队列（满了丢最老的），解码端检测到丢帧立刻 flush 并请求 IDR。
 */
public class ReceiverActivity extends AppCompatActivity
        implements BleStreamClient.Listener, AuReassembler.Sink, AvcDecoder.Listener,
        SurfaceHolder.Callback {

    private static final int REQ_PERMS = 2001;

    private BleStreamClient client;
    private AuReassembler reassembler;
    private AvcDecoder decoder;

    private SurfaceView videoView;
    private FrameLayout videoContainer;
    private TextView statusView;
    private TextView serverStatusView;
    private Spinner profileSpinner;

    private HandlerThread chunkThread;
    private Handler chunkHandler;

    private final StringBuilder log = new StringBuilder();
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private volatile int lastWidth;
    private volatile int lastHeight;
    private long decodedFrames;
    private long lastDecodedFrames;
    private long ausReceived;
    private long gaps;
    private long corrupt;
    private BleStreamClient.Stats lastStats;
    private String lastServerStatus = "";
    private volatile boolean connected;

    private final Handler ui = new Handler(android.os.Looper.getMainLooper());
    private final Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receiver);

        videoView = findViewById(R.id.video);
        videoContainer = findViewById(R.id.video_container);
        statusView = findViewById(R.id.status);
        serverStatusView = findViewById(R.id.server_status);
        profileSpinner = findViewById(R.id.profile);
        Button connect = findViewById(R.id.btn_connect);
        Button disconnect = findViewById(R.id.btn_disconnect);
        Button idr = findViewById(R.id.btn_idr);
        Button readStatus = findViewById(R.id.btn_read_status);

        videoView.getHolder().addCallback(this);

        String[] labels = new String[VideoProfile.ALL.length];
        for (int i = 0; i < VideoProfile.ALL.length; i++) {
            labels[i] = VideoProfile.ALL[i].name + " " + VideoProfile.ALL[i].describe();
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);
        profileSpinner.setSelection(3); // 与服务端默认档 BALANCED 对齐（ALL 数组下标）

        chunkThread = new HandlerThread("ble-chunk");
        chunkThread.start();
        chunkHandler = new Handler(chunkThread.getLooper());

        decoder = new AvcDecoder(this);
        decoder.start();
        reassembler = new AuReassembler(this);
        client = new BleStreamClient(this, this);

        connect.setOnClickListener(v -> {
            String[] missing = Perms.missing(this, Perms.clientPermissions());
            if (missing.length > 0) {
                requestPermissions(missing, REQ_PERMS);
                return;
            }
            client.connect();
        });
        disconnect.setOnClickListener(v -> client.disconnect());
        idr.setOnClickListener(v -> client.requestIdr());
        readStatus.setOnClickListener(v -> client.readStatus());
        profileSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (connected && position >= 0 && position < VideoProfile.ALL.length) {
                    client.setProfile(VideoProfile.ALL[position].id);
                    appendLog("切换档位 " + VideoProfile.ALL[position].name);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        if (!client.isAvailable()) {
            appendLog("蓝牙不可用，请先开启");
        }
        ui.post(uiTicker);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(uiTicker);
        client.disconnect();
        decoder.stop();
        if (chunkThread != null) {
            chunkThread.quitSafely();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ SurfaceView

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        decoder.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        decoder.setSurface(holder.getSurface());
        layoutVideo();
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        decoder.setSurface(null);
    }

    /** 按真实编码分辨率等比摆放，避免拉伸。 */
    private void layoutVideo() {
        int w = lastWidth;
        int h = lastHeight;
        if (w <= 0 || h <= 0) {
            return;
        }
        int containerW = videoContainer.getWidth();
        int containerH = videoContainer.getHeight();
        if (containerW <= 0 || containerH <= 0) {
            return;
        }
        int viewW = containerW;
        int viewH = (int) ((long) viewW * h / w);
        if (viewH > containerH) {
            viewH = containerH;
            viewW = (int) ((long) viewH * w / h);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(viewW, viewH);
        lp.gravity = Gravity.CENTER;
        videoView.setLayoutParams(lp);
    }

    @Override
    public void onResolution(final int width, final int height) {
        lastWidth = width;
        lastHeight = height;
        runOnUiThread(this::layoutVideo);
    }

    @Override
    public void onDecodedFrame() {
        decodedFrames++;
    }

    @Override
    public void onError(String message) {
        appendLog(message);
    }

    // ------------------------------------------------------------------ BleStreamClient.Listener

    @Override
    public void onLog(String message) {
        appendLog(message);
    }

    @Override
    public void onConnectionState(final boolean isConnected) {
        connected = isConnected;
        if (!isConnected) {
            reassembler.reset();
        } else {
            client.readStatus();
        }
    }

    @Override
    public void onChunk(byte[] packet) {
        // GATT 回调线程不做重活，交给组帧线程
        chunkHandler.post(() -> reassembler.feed(packet));
    }

    @Override
    public void onServerStatus(String text) {
        lastServerStatus = text == null ? "" : text;
    }

    @Override
    public void onStats(BleStreamClient.Stats stats) {
        lastStats = stats;
    }

    // ------------------------------------------------------------------ AuReassembler.Sink

    @Override
    public void onAccessUnit(byte[] au, boolean keyFrame, boolean hasConfig, int seq) {
        ausReceived++;
        decoder.submit(au, keyFrame);
    }

    @Override
    public void onStreamGap(int lastSeq, int currentSeq) {
        gaps++;
        decoder.flushAfterGap();
        reassembler.discardPartial();
        client.requestIdr();
        appendLog(String.format(Locale.US, "丢帧 seq %d→%d，已请求 IDR", lastSeq, currentSeq));
    }

    /** CRC8 或 JPEG 结构校验失败：整帧作废，AVC 还需要重同步。 */
    @Override
    public void onCorruptFrame(int seq, String reason) {
        corrupt++;
        decoder.flushAfterGap();
        client.requestIdr();
        appendLog(String.format(Locale.US, "坏帧 seq=%d (%s)", seq, reason));
    }

    // ------------------------------------------------------------------ UI

    private void appendLog(String message) {
        synchronized (log) {
            log.append(time.format(new Date())).append(' ').append(message).append('\n');
            int from = Math.max(0, log.length() - 1200);
            String tail = log.substring(from);
            log.setLength(0);
            log.append(tail);
        }
    }

    private void render() {
        StringBuilder sb = new StringBuilder();
        sb.append(connected ? "已连接" : "未连接");
        BleStreamClient.Stats s = lastStats;
        if (s != null) {
            sb.append(String.format(Locale.US,
                    "  MTU %d  分包 %dB  %dB/s  %d包/s  PHY %s\n", s.mtu, s.chunkPayload,
                    s.bytesPerSecond, s.packetsPerSecond, client.getPhyName()));
        } else {
            sb.append('\n');
        }
        long frames = decodedFrames;
        long delta = frames - lastDecodedFrames;
        lastDecodedFrames = frames;
        sb.append(String.format(Locale.US, "画面 %dx%d  帧 %d  丢帧 %d  坏帧 %d  解码 %dfps\n",
                lastWidth, lastHeight, ausReceived, gaps, corrupt, delta * 2));
        serverStatusView.setText("服务端: " + (lastServerStatus.isEmpty() ? "-" : lastServerStatus));
        statusView.setText(sb.toString());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS && Perms.hasAll(this, Perms.clientPermissions())) {
            client.connect();
        }
    }
}

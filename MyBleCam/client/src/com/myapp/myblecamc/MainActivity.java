package com.myapp.myblecamc;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * MyBleCamC —— BLE 图传客户端（H.264 硬解码版）
 * 中心设备：扫描 -> 连接 -> MTU 517 -> HIGH -> 订阅 VIDEO notify -> AB CD 组帧 -> MediaCodec 解码到 Surface
 */
public class MainActivity extends Activity {

    static final UUID SVC = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    static final UUID CH_VIDEO = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    static final UUID CH_CTRL = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb");
    static final UUID CH_STATUS = UUID.fromString("0000ffe3-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static final String DEFAULT_SERVER_NAME = "MyBleCamS";
    String serverName = DEFAULT_SERVER_NAME;   // 要连接的服务端名称（设置页可改）
    static final byte[] FRAME_HEAD = new byte[]{(byte) 0xAB, (byte) 0xCD};
    static final String MIME = "video/avc";

    static final byte CMD_CAM = 0x10, CMD_QUALITY = 0x11, CMD_MODE = 0x12;
    // 传输模式：0 画质优先（服务端不丢帧），1 延迟优先（服务端丢帧保实时，默认）
    static final int MODE_QUALITY = 0, MODE_LATENCY = 1;
    static final byte[] CTRL_HEAD = new byte[]{(byte) 0xAA, (byte) 0xFF};

    static final String[] CAMS = new String[]{"后置摄像头", "前置摄像头"};
    static final String[] QUALITIES = new String[]{"Low 160x120", "Mid 256x192", "High 320x240"};

    TextView tvStatus, tvFps;
    ScrollView svLog;
    SurfaceView svVideo;
    FrameLayout flVideo;
    View rowBtns, rowLabels, rowCtrl, lblCtrl, btnFs;
    Button btnScan, btnConnect, btnDisconnect;
    boolean fullscreen = false;
    Spinner spCam, spQuality;

    final Handler ui = new Handler(Looper.getMainLooper());
    BluetoothAdapter adapter;
    BluetoothLeScanner scanner;
    BluetoothGatt gatt;
    BluetoothGattCharacteristic videoCh, ctrlCh, statusCh;
    volatile boolean connected = false;
    volatile boolean scanning = false;
    volatile boolean subscribed = false;

    int camIdx = 0;
    int qualityIdx = 0;   // 默认 Low
    int latencyMode = MODE_LATENCY;   // 传输模式：1 延迟优先（默认）/ 0 画质优先
    String pendingCmd = null;   // 当前等待回执的控制命令描述

    // 组帧
    final ByteArrayOutputStream frameBuf = new ByteArrayOutputStream(128 * 1024);
    int expectLen = 0;

    // 无帧看门狗：服务端 Stop 后 Start（gattServer 重建）会导致 CCCD 订阅失效，
    // 链路仍在（命令可发但画面黑），超过阈值收不到帧就自动重写 CCCD 重订阅
    volatile long lastVideoMs = 0;
    static final int RESUB_AFTER_MS = 4000;
    boolean resubPending = false;

    // H.264 解码器
    MediaCodec decoder;
    final MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
    Thread renderThread;
    volatile boolean decodeRunning = false;
    volatile Surface surface;

    int fpsCount = 0;
    long fpsLast = 0;
    boolean spReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        svLog = (ScrollView) findViewById(R.id.sv_log);
        tvFps = (TextView) findViewById(R.id.tv_fps);
        svVideo = (SurfaceView) findViewById(R.id.sv_video);
        flVideo = (FrameLayout) findViewById(R.id.fl_video);
        rowBtns = findViewById(R.id.row_btns);
        rowLabels = findViewById(R.id.row_labels);
        rowCtrl = findViewById(R.id.row_ctrl);
        lblCtrl = findViewById(R.id.lbl_ctrl);
        btnFs = findViewById(R.id.btn_fullscreen);
        btnFs.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleFullscreen(); }
        });
        btnScan = (Button) findViewById(R.id.btn_scan);
        btnConnect = (Button) findViewById(R.id.btn_connect);
        btnDisconnect = (Button) findViewById(R.id.btn_disconnect);
        spCam = (Spinner) findViewById(R.id.sp_cam);
        spQuality = (Spinner) findViewById(R.id.sp_quality);

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();

        svVideo.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) {
                surface = h.getSurface();
                initDecoder();
            }
            public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) { }
            public void surfaceDestroyed(SurfaceHolder h) {
                surface = null;
                stopDecoder();
            }
        });

        spCam.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, CAMS));
        spQuality.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, QUALITIES));
        spCam.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (!spReady || suppressSync) return;
                camIdx = pos;
                sendCtrl(CMD_CAM, (byte) pos, "切换摄像头 -> " + CAMS[pos]);
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        spQuality.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (!spReady || suppressSync) return;
                qualityIdx = pos;
                sendCtrl(CMD_QUALITY, (byte) pos, "画质 -> " + QUALITIES[pos]);
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        spCam.setSelection(camIdx);
        spQuality.setSelection(qualityIdx);
        // 延迟置位 spReady，避开 setSelection 的异步 onItemSelected 回调
        ui.postDelayed(new Runnable() {
            public void run() { spReady = true; }
        }, 500);

        btnScan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startScan(); }
        });
        btnConnect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { connectByName(serverName); }
        });
        btnDisconnect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { disconnect(); }
        });
        updateButtons();
        requestPermsIfNeeded();
        status("MyBleCamC 就绪，点 Scan 或 Connect");

        // 无帧看门狗循环：连接+已订阅但长时间无视频帧 -> 重写 CCCD 重新订阅
        ui.postDelayed(new Runnable() {
            public void run() {
                checkVideoWatchdog();
                ui.postDelayed(this, 2000);
            }
        }, 2000);
    }

    void checkVideoWatchdog() {
        if (!connected || gatt == null) return;
        long now = System.currentTimeMillis();
        if (lastVideoMs == 0) { lastVideoMs = now; return; }   // 刚连接，等第一批帧
        if (now - lastVideoMs < RESUB_AFTER_MS || resubPending) return;
        resubPending = true;
        status("超过 " + (RESUB_AFTER_MS / 1000) + "s 无帧，重新发现服务并订阅…");
        try {
            // 服务端 Stop->Start 后 gattServer 重建，服务句柄可能变化：
            // 重新发现服务会刷新 videoCh 并走 onMtuChanged -> subscribeVideo 重写 CCCD，
            // 服务端 onDescriptorWriteRequest 收到后 subscribed=true，恢复推送
            gatt.discoverServices();
            lastVideoMs = System.currentTimeMillis();
        } finally {
            resubPending = false;
        }
    }

    // 视频区按画面比例（竖屏 3:4）设置 SurfaceView 尺寸，居中显示两侧留黑边，与画面比例一致
    void applyVideoRatio() {
        int cw = flVideo.getWidth(), ch = flVideo.getHeight();
        if (cw <= 0 || ch <= 0) return;
        int w = cw, h = w * 4 / 3;
        if (h > ch) { h = ch; w = h * 3 / 4; }
        ViewGroup.LayoutParams lp = svVideo.getLayoutParams();
        lp.width = w;
        lp.height = h;
        svVideo.setLayoutParams(lp);
        ((FrameLayout.LayoutParams) svVideo.getLayoutParams()).gravity = Gravity.CENTER;
        svVideo.requestLayout();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus) applyVideoRatio();
    }

    // 标题栏右侧 ⋮ 溢出菜单：设置页入口
    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "设置");
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            startActivity(new android.content.Intent(MainActivity.this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // 全屏：隐藏日志/按钮/控制区，只留视频；再点恢复
    void toggleFullscreen() {
        fullscreen = !fullscreen;
        int vis = fullscreen ? View.GONE : View.VISIBLE;
        svLog.setVisibility(vis);
        rowBtns.setVisibility(vis);
        rowLabels.setVisibility(vis);
        rowCtrl.setVisibility(vis);
        lblCtrl.setVisibility(vis);
        ((Button) btnFs).setText(fullscreen ? "退出全屏" : "全屏");
        applyVideoRatio();
    }

    String qName(int i) { return i == 0 ? "Low" : (i == 1 ? "Mid" : "High"); }

    // 服务端状态同步到下拉框（静默，不回发命令）
    boolean suppressSync = false;
    void syncSpinners(int cam, int quality) {
        if (spReady && (spCam.getSelectedItemPosition() != cam
                || spQuality.getSelectedItemPosition() != quality)) {
            status("同步服务端状态: " + (cam == 0 ? "后置" : "前置") + " / " + qName(quality));
        }
        suppressSync = true;
        spCam.setSelection(cam);
        spQuality.setSelection(quality);
        ui.postDelayed(new Runnable() {
            public void run() { suppressSync = false; }
        }, 600);
    }

    void requestPermsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23) {
            List<String> need = new ArrayList<String>();
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED)
                need.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED)
                need.add(android.Manifest.permission.ACCESS_COARSE_LOCATION);
            if (!need.isEmpty())
                requestPermissions(need.toArray(new String[0]), 1);
        }
    }

    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        status("权限结果已处理");
    }

    final ArrayList<String> logLines = new ArrayList<String>();
    void status(final String s) {
        ui.post(new Runnable() {
            public void run() {
                logLines.add(s);
                while (logLines.size() > 12) logLines.remove(0);
                StringBuilder sb = new StringBuilder();
                for (String line : logLines) sb.append(line).append("\n");
                tvStatus.setText(sb.toString());
                svLog.post(new Runnable() {
                    public void run() { svLog.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    void toast(final String s) {
        ui.post(new Runnable() { public void run() { Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show(); } });
    }

    void updateButtons() {
        btnScan.setEnabled(!connected);
        btnConnect.setEnabled(!connected);
        btnDisconnect.setEnabled(connected);
        spCam.setEnabled(connected);
        spQuality.setEnabled(connected);
    }

    // ===================== H.264 解码器 =====================
    synchronized void initDecoder() {
        stopDecoder();
        if (surface == null) return;
        try {
            decoder = MediaCodec.createDecoderByType(MIME);
            MediaFormat fmt = MediaFormat.createVideoFormat(MIME, 240, 320);
            decoder.configure(fmt, surface, null, 0);
            // 保持 3:4 比例缩放（不满屏则留黑边），不拉伸变形
            try { decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT); } catch (Throwable t) { }
            decoder.start();
            decodeRunning = true;
            renderThread = new Thread(renderLoop, "cam-render");
            renderThread.start();
            status("H.264 解码器就绪");
        } catch (Throwable t) {
            status("解码器失败: " + t);
            stopDecoder();
        }
    }

    synchronized void stopDecoder() {
        decodeRunning = false;
        if (renderThread != null) { renderThread.interrupt(); renderThread = null; }
        if (decoder != null) {
            try { decoder.stop(); } catch (Throwable t) { }
            try { decoder.release(); } catch (Throwable t) { }
            decoder = null;
        }
    }

    void feedDecoder(byte[] au) {
        MediaCodec d = decoder;
        if (d == null || !decodeRunning) return;
        try {
            int idx = d.dequeueInputBuffer(10000);
            if (idx < 0) return;
            ByteBuffer buf = d.getInputBuffer(idx);
            buf.clear();
            buf.put(au);
            long pts = System.nanoTime() / 1000L;
            d.queueInputBuffer(idx, 0, au.length, pts, 0);
        } catch (Throwable t) { }
    }

    final Runnable renderLoop = new Runnable() {
        public void run() {
            while (decodeRunning) {
                MediaCodec d = decoder;
                if (d == null) { sleep(20); continue; }
                try {
                    int idx = d.dequeueOutputBuffer(decInfo, 30000);
                    if (idx >= 0) {
                        d.releaseOutputBuffer(idx, true);
                        fpsCount++;
                        long now = System.currentTimeMillis();
                        if (now - fpsLast >= 1000) {
                            final int fps = fpsCount * 1000 / (int) (now - fpsLast);
                            fpsCount = 0;
                            fpsLast = now;
                            ui.post(new Runnable() {
                                public void run() { tvFps.setText(String.format(Locale.US, "%d fps", fps)); }
                            });
                        } else if (fpsLast == 0) {
                            fpsLast = now;
                        }
                    }
                } catch (Throwable t) {
                    sleep(10);
                }
            }
        }
    };

    void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ===================== 扫描 =====================
    void startScan() {
        if (adapter == null || !adapter.isEnabled()) { toast("蓝牙未开启"); return; }
        if (scanning) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { toast("扫描器不可用"); return; }
        scanning = true;
        status("扫描中… 找 " + serverName);
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(null, settings, scanCb);
        ui.postDelayed(new Runnable() {
            public void run() { if (scanning) stopScan(); }
        }, 8000);
    }

    void stopScan() {
        if (!scanning) return;
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (Throwable t) { }
        scanning = false;
        status("扫描结束，未发现 " + serverName + "（可点 Connect 重试）");
    }

    final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int cbType, ScanResult r) {
            BluetoothDevice d = r.getDevice();
            String n = d.getName();
            if (n == null && r.getScanRecord() != null) n = r.getScanRecord().getDeviceName();
            final String name = n;
            boolean isCamServer = false;
            if (r.getScanRecord() != null) {
                byte[] md = r.getScanRecord().getManufacturerSpecificData(0xE5E5);
                if (md != null && md.length >= 4 && md[1] == 'C' && md[2] == 'A' && md[3] == 'M') {
                    isCamServer = true;
                }
            }
            if (!isCamServer && name != null &&
                    (name.equals(serverName) || name.contains("MyBleCam") || name.equals("Rolex"))) {
                isCamServer = true;
            }
            if (isCamServer) {
                stopScan();
                status("发现服务端 " + (name == null ? serverName : name) + " " + d.getAddress() + "，连接中…");
                connectGatt(d);
            }
        }
        @Override public void onScanFailed(int code) {
            scanning = false;
            android.util.Log.e("CamC", "onScanFailed code=" + code);
            status("扫描失败 code=" + code);
        }
    };

    void connectByName(String name) {
        if (adapter != null) {
            java.util.Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    if (name.equals(d.getName())) { connectGatt(d); return; }
                }
            }
        }
        startScan();
    }

    void connectGatt(BluetoothDevice d) {
        disconnect();
        status("连接 " + d.getAddress());
        gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
    }

    void disconnect() {
        stopScan();
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Throwable t) { }
            gatt = null;
        }
        connected = false;
        subscribed = false;
        videoCh = null;
        statusCh = null;
        ctrlCh = null;
        resetFrame();
        clearVideo();
        ui.post(new Runnable() { public void run() { updateButtons(); } });
    }

    // 断开后清空画面：重建解码器，Surface 恢复为黑屏（不再保留最后一帧）
    void clearVideo() {
        status("已断开，画面清空");
        initDecoder();
    }

    // ===================== GATT 回调 =====================
    final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                status("已连接，服务发现…");
                requestHigh(g);
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                subscribed = false;
                lastVideoMs = 0;
                ui.post(new Runnable() {
                    public void run() { clearVideo(); }
                });
            }
            ui.post(new Runnable() { public void run() { updateButtons(); } });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            if (st != BluetoothGatt.GATT_SUCCESS) { status("服务发现失败 " + st); return; }
            BluetoothGattService s = g.getService(SVC);
            if (s == null) { status("未找到服务 ffe0"); return; }
            videoCh = s.getCharacteristic(CH_VIDEO);
            ctrlCh = s.getCharacteristic(CH_CTRL);
            statusCh = s.getCharacteristic(CH_STATUS);
            status("请求 MTU 517…");
            g.requestMtu(517);
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int st) {
            status("MTU=" + mtu + "，订阅 VIDEO…");
            // MTU 协商完成、链路完全就绪后再请求一次 HIGH 连接优先级，
            // 刚连接时请求常被协议栈忽略导致中高画质吞吐不足
            requestHigh(g);
            subscribeVideo(g);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (videoCh != null && ch.getUuid().equals(videoCh.getUuid())) {
                byte[] data = ch.getValue();
                if (data != null) onVideoData(data);
            } else if (statusCh != null && ch.getUuid().equals(statusCh.getUuid())) {
                byte[] data = ch.getValue();
                // 状态帧 {0x5B, cam, quality}：服务端摄像头/画质变化 -> 静默同步下拉框
                if (data != null && data.length >= 3 && data[0] == 0x5B) {
                    final int c = data[1] & 0xFF, q = data[2] & 0xFF;
                    ui.post(new Runnable() {
                        public void run() { syncSpinners(c, q); }
                    });
                }
            }
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int st) {
            if (ctrlCh != null && ch.getUuid().equals(ctrlCh.getUuid())) {
                if (st == BluetoothGatt.GATT_SUCCESS) {
                    status("✓ 命令已发送: " + (pendingCmd == null ? "控制" : pendingCmd));
                } else {
                    status("✗ 命令发送失败: status=" + st + " " + (pendingCmd == null ? "" : pendingCmd));
                }
                pendingCmd = null;
            }
        }
    };

    void subscribeVideo(BluetoothGatt g) {
        if (videoCh == null) return;
        g.setCharacteristicNotification(videoCh, true);
        BluetoothGattDescriptor d = videoCh.getDescriptor(CCCD);
        if (d != null) {
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            g.writeDescriptor(d);
        }
        // 订阅状态特征 ffe3：接收服务端摄像头/画质变化，同步本端下拉框
        if (statusCh != null) {
            g.setCharacteristicNotification(statusCh, true);
            BluetoothGattDescriptor sd = statusCh.getDescriptor(CCCD);
            if (sd != null) {
                sd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(sd);
            }
        }
        subscribed = true;
        status("订阅成功，等待图像…");
        // 稍后再补一次 HIGH 连接优先级请求（部分系统需要链路稳定后才能生效）
        ui.postDelayed(new Runnable() {
            public void run() {
                if (gatt != null && connected) requestHigh(gatt);
            }
        }, 800);
        // 把传输模式（画质/延迟优先）下发给服务端：
        // 延迟 500ms 发送，避开 CCCD 订阅写入期间 write 返回 false
        ui.postDelayed(new Runnable() {
            public void run() { sendMode(); }
        }, 500);
    }

    // 下发传输模式命令 CMD_MODE：0 画质优先 / 1 延迟优先
    void sendMode() {
        if (connected && ctrlCh != null)
            sendCtrl(CMD_MODE, (byte) latencyMode,
                    latencyMode == MODE_QUALITY ? "模式 -> 画质优先" : "模式 -> 延迟优先");
    }

    // 设置页保存后返回时读取模式；已连接则立即下发
    @Override protected void onResume() {
        super.onResume();
        android.content.SharedPreferences sp = getSharedPreferences("myblecamc", MODE_PRIVATE);
        // 要连接的服务端名称（设置页可改）
        String n = sp.getString("server_name", DEFAULT_SERVER_NAME).trim();
        serverName = n.isEmpty() ? DEFAULT_SERVER_NAME : n;
        int m = sp.getInt("mode", MODE_LATENCY);
        if (m != latencyMode) {
            latencyMode = m;
            status("传输模式: " + (latencyMode == MODE_QUALITY ? "画质优先" : "延迟优先"));
            sendMode();
        }
    }

    void requestHigh(BluetoothGatt g) {
        try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Throwable t) { }
        try {
            java.lang.reflect.Method m = BluetoothGatt.class.getMethod("setPreferredPhy", int.class, int.class, int.class);
            int PHY_LE_2M_MASK = BluetoothDevice.class.getField("PHY_LE_2M_MASK").getInt(null);
            int PHY_OPTION_NO_PREFERRED = BluetoothDevice.class.getField("PHY_OPTION_NO_PREFERRED").getInt(null);
            m.invoke(g, PHY_LE_2M_MASK, PHY_LE_2M_MASK, PHY_OPTION_NO_PREFERRED);
        } catch (Throwable t) { }
    }

    void sendCtrl(byte cmd, byte param, String desc) {
        if (!connected || ctrlCh == null || gatt == null) { toast("未连接，命令未发送"); return; }
        byte[] v = new byte[]{CTRL_HEAD[0], CTRL_HEAD[1], cmd, param};
        try {
            pendingCmd = desc;
            status("发送命令: " + desc);
            ctrlCh.setValue(v);
            boolean ok = gatt.writeCharacteristic(ctrlCh);
            if (!ok) { status("✗ 命令提交失败(write 返回 false): " + desc); pendingCmd = null; }
        } catch (Throwable t) {
            status("✗ 命令发送异常: " + t);
            pendingCmd = null;
        }
    }

    // ===================== 组帧（Annex-B access unit）=====================
    // 客户端延迟控制：帧龄超限的旧帧直接丢弃，等下一个关键帧再送解码，
    // 防止解码/渲染积压造成延迟累积
    long frameStartMs = 0;
    boolean discardUntilKey = false;
    int clientDropped = 0;

    void resetFrame() {
        synchronized (frameBuf) { frameBuf.reset(); expectLen = 0; }
        frameStartMs = 0;
    }

    static final int MAX_FRAME_AGE_MS = 600;   // 帧龄超过 600ms 视为过期帧

    // Annex-B 帧是否关键帧（SPS 或 IDR NALU 开头）
    boolean isKeyFrame(byte[] au) {
        int i = -1;
        if (au.length > 5 && au[0] == 0 && au[1] == 0 && au[2] == 0 && au[3] == 1) i = 4;
        else if (au.length > 4 && au[0] == 0 && au[1] == 0 && au[2] == 1) i = 3;
        if (i < 0) return false;
        int t = au[i] & 0x1F;
        return t == 5 || t == 7;   // 5=IDR slice 7=SPS
    }

    void onVideoData(byte[] data) {
        lastVideoMs = System.currentTimeMillis();
        if (data.length < 2) return;
        if (data.length >= 4 && data[0] == FRAME_HEAD[0] && data[1] == FRAME_HEAD[1]) {
            int len = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
            synchronized (frameBuf) {
                frameBuf.reset();
                expectLen = len;
                frameStartMs = System.currentTimeMillis();
                if (data.length > 4) frameBuf.write(data, 4, data.length - 4);
                maybeComplete();
            }
        } else {
            synchronized (frameBuf) {
                if (expectLen <= 0) { frameBuf.reset(); return; }
                frameBuf.write(data, 0, data.length);
                maybeComplete();
            }
        }
    }

    void maybeComplete() {
        if (expectLen > 0 && frameBuf.size() >= expectLen) {
            final byte[] au = frameBuf.toByteArray();
            frameBuf.reset();
            expectLen = 0;
            long age = frameStartMs == 0 ? 0 : System.currentTimeMillis() - frameStartMs;
            frameStartMs = 0;
            boolean key = isKeyFrame(au);
            if (key) discardUntilKey = false;   // 关键帧重置参考链，恢复送解码
            else if (discardUntilKey || age > MAX_FRAME_AGE_MS) {
                // 过期旧帧 / 丢帧后等待关键帧：丢弃，避免花屏和延迟累积
                clientDropped++;
                if (clientDropped % 20 == 1)
                    status("客户端丢旧帧（累计 " + clientDropped + "），等关键帧恢复");
                return;
            }
            feedDecoder(au);
        }
    }

    @Override protected void onDestroy() {
        disconnect();
        stopDecoder();
        super.onDestroy();
    }
}

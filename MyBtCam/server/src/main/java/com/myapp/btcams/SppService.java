package com.myapp.btcams;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.myapp.btcam.common.BtCamProtocol;
import com.myapp.btcams.stream.SppWriter;
import com.myapp.btcams.stream.StreamEngine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * SPP 监听服务（前台服务）。
 *
 * 推流完全在本服务内完成，不依赖 Activity 可见：
 * 客户端 connect + HELLO 后，服务直接打开 Camera2 开始编码，
 * 因此客户端「让服务端开始推流」根本不需要 root，也不需要额外广播。
 * 只有希望把服务端主界面显示出来时，才用 startActivity（7.1 允许后台启动），
 * 若被 ROM 拦截再退回 su -c am start。
 */
// targetSdk 28：蓝牙权限为安装时授予，无需运行时申请。
@SuppressLint("MissingPermission")
public class SppService extends Service {

    private static final String TAG = "SppService";

    public static final String ACTION_START = "com.myapp.btcams.action.START";
    public static final String ACTION_STOP = "com.myapp.btcams.action.STOP";
    public static final String ACTION_STATUS = "com.myapp.btcams.action.STATUS";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_CONNECTED = "connected";

    private static final int NOTIFY_ID = 1001;
    private static final String CHANNEL_ID = "btcams_listen";

    public static volatile boolean sRunning;
    public static volatile boolean sConnected;
    public static volatile String sState = "未启动";

    private volatile boolean running;
    private BluetoothAdapter adapter;
    private volatile BluetoothServerSocket serverSocket;
    private Thread acceptThread;
    private volatile Session session;
    private NotificationManager notificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        startForegroundInternal("等待客户端连接");
        adapter = BluetoothAdapter.getDefaultAdapter();
        running = true;
        sRunning = true;
        updateState("正在监听");
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "BtCamAccept");
        acceptThread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.getBooleanExtra("root_start", false)) {
            Log.i(TAG, "started via root");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        sRunning = false;
        updateState("未启动");
        closeServerSocket();
        Session s = session;
        if (s != null) {
            s.close();
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            try {
                acceptThread.join(500);
            } catch (InterruptedException ignored) {
            }
            acceptThread = null;
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* 监听                                                                */
    /* ------------------------------------------------------------------ */

    private void acceptLoop() {
        while (running) {
            BluetoothServerSocket ss = null;
            try {
                if (adapter == null || !adapter.isEnabled()) {
                    updateState("蓝牙未开启，等待中");
                    Thread.sleep(3000);
                    continue;
                }
                ss = adapter.listenUsingRfcommWithServiceRecord(
                        BtCamProtocol.SERVICE_NAME, BtCamProtocol.SPP_UUID);
                serverSocket = ss;
                updateState("正在监听，等待客户端连接");
                BluetoothSocket socket = ss.accept();
                closeQuietly(ss);
                ss = null;
                serverSocket = null;

                if (running && socket != null) {
                    sConnected = true;
                    String name = "已连接";
                    try {
                        name = "已连接 " + socket.getRemoteDevice().getName();
                    } catch (Exception ignored) {
                    }
                    updateState(name);
                    updateNotification(name);
                    Session s = new Session(socket);
                    session = s;
                    s.run();
                    session = null;
                    sConnected = false;
                    if (running) {
                        updateState("正在监听，等待客户端连接");
                        updateNotification("等待客户端连接");
                    }
                }
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "accept: " + e);
                    sleepQuietly(800);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.e(TAG, "accept loop error", e);
                sleepQuietly(800);
            } finally {
                closeQuietly(ss);
            }
        }
    }

    private static void closeQuietly(BluetoothServerSocket ss) {
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeServerSocket() {
        BluetoothServerSocket ss = serverSocket;
        serverSocket = null;
        closeQuietly(ss);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /* ------------------------------------------------------------------ */
    /* 会话                                                                */
    /* ------------------------------------------------------------------ */

    private final class Session {

        private final BluetoothSocket socket;
        private InputStream in;
        private OutputStream out;
        private SppWriter writer;
        private volatile StreamEngine engine;
        private PowerManager.WakeLock wakeLock;
        private volatile boolean alive = true;
        private int width = 640;
        private int height = 480;
        private int bitrate = 500000;

        Session(BluetoothSocket socket) {
            this.socket = socket;
        }

        void run() {
            try {
                in = socket.getInputStream();
                out = socket.getOutputStream();
            } catch (IOException e) {
                Log.w(TAG, "open streams failed: " + e);
                close();
                return;
            }

            acquireWakeLock();
            writer = new SppWriter(out, new SppWriter.Listener() {
                @Override
                public void onWriterError(Exception e) {
                    close();
                }
            });
            writer.start();

            // 客户端一上来就让服务端把界面点亮（可在设置里关掉）
            if (Prefs.isOpenUiOnConnect(SppService.this)) {
                launchUi();
            }

            try {
                readLoop();
            } catch (IOException e) {
                if (alive) {
                    Log.i(TAG, "client disconnected: " + e.getMessage());
                }
            } catch (Exception e) {
                Log.w(TAG, "session error", e);
            } finally {
                alive = false;
                stopEngine();
                if (writer != null) {
                    writer.stop();
                }
                closeQuietly();
                releaseWakeLock();
                Log.i(TAG, "session end");
            }
        }

        private void readLoop() throws IOException {
            while (alive && running) {
                BtCamProtocol.Header header = BtCamProtocol.readHeader(in);
                byte[] payload = BtCamProtocol.readPayload(in, header.length);
                switch (header.type) {
                    case BtCamProtocol.T_HELLO:
                    case BtCamProtocol.T_PARAM:
                        handleRequest(BtCamProtocol.parseRequest(payload),
                                header.type == BtCamProtocol.T_PARAM);
                        break;
                    case BtCamProtocol.T_STOP:
                        stopEngine();
                        writer.sendStatus("已停止推流", BtCamProtocol.FLAG_STATUS_INFO);
                        updateState("已连接，空闲");
                        break;
                    case BtCamProtocol.T_IDR_REQ: {
                        StreamEngine current = engine;
                        if (current != null) {
                            current.requestIdr();
                        }
                        break;
                    }
                    case BtCamProtocol.T_PING:
                        writer.sendPong(BtCamProtocol.parsePingTs(payload));
                        break;
                    default:
                        Log.d(TAG, "ignore msg type " + header.type);
                        break;
                }
            }
        }

        private void handleRequest(BtCamProtocol.Request request, boolean liveChange) {
            if (ContextCompat.checkSelfPermission(SppService.this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                writer.sendAck(BtCamProtocol.RESULT_ERROR, "服务端未授予相机权限");
                writer.sendStatus("请先在本机打开 MyBtCamS 授予相机权限",
                        BtCamProtocol.FLAG_STATUS_ERROR);
                return;
            }

            int w = request.width;
            int h = request.height;
            if (!(w == 640 && h == 480) && !(w == 320 && h == 240)) {
                w = width;
                h = height;
            }
            int br = request.bitrate;
            if (br < 100000 || br > 2000000) {
                br = bitrate;
            }
            int cam = request.camera;
            if (cam != BtCamProtocol.CAMERA_FRONT && cam != BtCamProtocol.CAMERA_BACK) {
                cam = Prefs.getDefaultCamera(SppService.this);
            }
            int rot = request.rotation;
            if (rot != 0 && rot != 90 && rot != 180 && rot != 270) {
                rot = Prefs.getRotation(SppService.this);
            }

            width = w;
            height = h;
            bitrate = br;

            stopEngine();
            writer.resetVideo();
            StreamEngine newEngine = new StreamEngine(SppService.this, writer,
                    new StreamEngine.Listener() {
                        @Override
                        public void onConfig(int cfw, int cfh, byte[] sps, byte[] pps) {
                            writer.sendConfig(cfw, cfh, sps, pps);
                            String text = "推流中 " + cfw + "x" + cfh + " "
                                    + (bitrate / 1000) + "kbps";
                            updateState(text);
                            updateNotification(text);
                        }

                        @Override
                        public void onFailure(String reason) {
                            writer.sendStatus(reason, BtCamProtocol.FLAG_STATUS_ERROR);
                            updateState("推流失败: " + reason);
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    stopEngine();
                                }
                            }, "BtCamEngineCleanup").start();
                        }
                    });
            engine = newEngine;

            boolean ok = newEngine.start(w, h, br, cam, rot);
            if (ok) {
                writer.sendAck(BtCamProtocol.RESULT_OK, "");
                Log.i(TAG, "request accepted " + w + "x" + h + " @" + br
                        + (liveChange ? " (live change)" : ""));
            } else {
                engine = null;
                writer.sendAck(BtCamProtocol.RESULT_ERROR, "服务端编码器初始化失败");
                writer.sendStatus("服务端编码器初始化失败", BtCamProtocol.FLAG_STATUS_ERROR);
            }
        }

        private void stopEngine() {
            StreamEngine e = engine;
            engine = null;
            if (e != null) {
                try {
                    e.stop();
                } catch (Exception ignored) {
                }
            }
        }

        private void acquireWakeLock() {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyBtCamS:stream");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
                Log.i(TAG, "wakelock acquired");
            } catch (Exception e) {
                Log.w(TAG, "acquire wakelock failed", e);
            }
        }

        private void releaseWakeLock() {
            try {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                    Log.i(TAG, "wakelock released");
                }
            } catch (Exception ignored) {
            }
            wakeLock = null;
        }

        void close() {
            alive = false;
            closeQuietly();
        }

        private void closeQuietly() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 界面 / 通知                                                          */
    /* ------------------------------------------------------------------ */

    private void launchUi() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            // ROM 可能「调用成功但没真正显示」，稍后再检查一次，必要时走 root
            new Thread(new Runnable() {
                @Override
                public void run() {
                    sleepQuietly(1200);
                    if (!MainActivity.sVisible) {
                        Log.w(TAG, "UI not visible, fallback to root am start");
                        RootShell.amStartMainActivity();
                    }
                }
            }, "BtCamUiCheck").start();
        } catch (Exception e) {
            Log.w(TAG, "startActivity failed, fallback to root", e);
            RootShell.amStartMainActivity();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "蓝牙推流服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持 SPP 监听与摄像头推流");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("MyBtCamS")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi);
        return builder.build();
    }

    private void startForegroundInternal(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFY_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFY_ID, notification);
        }
    }

    private void updateNotification(String text) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFY_ID, buildNotification(text));
        }
    }

    private void updateState(String state) {
        sState = state;
        Intent intent = new Intent(ACTION_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATE, state);
        intent.putExtra(EXTRA_CONNECTED, sConnected);
        sendBroadcast(intent);
    }
}

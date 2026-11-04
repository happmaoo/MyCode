package com.myapp.blecams.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.myapp.blecams.MainActivity;
import com.myapp.blecams.R;
import com.myapp.blecams.ble.BleCamServer;
import com.myapp.blecams.camera.Camera2Source;
import com.myapp.blecams.codec.AvcEncoder;
import com.myapp.blecams.protocol.StatusInfo;
import com.myapp.blecams.protocol.StreamProtocol;
import com.myapp.blecams.protocol.VideoProfile;
import com.myapp.blecams.stream.StreamPump;

/**
 * 推流服务端进程内编排：Camera2 -> MediaCodec(AVC) -> StreamPump -> BLE notify。
 *
 * <p>用前台服务承载，退到后台/锁屏仍可继续推流；UI 通过 bindService 拿到实例来挂接预览面。
 */
public class ServerService extends Service implements
        Camera2Source.Listener,
        AvcEncoder.AuCallback,
        BleCamServer.Callback,
        BleCamServer.StatusProvider,
        StreamPump.Sink,
        StreamPump.Control,
        StreamPump.Listener {

    private static final String TAG = "ServerService";

    public static final String ACTION_START = "com.myapp.blecams.action.START";
    public static final String ACTION_STOP = "com.myapp.blecams.action.STOP";
    public static final String EXTRA_PROFILE_ID = "profile_id";

    private static final int NOTIFICATION_ID = 0x0BEC;

    public static class Snapshot {
        public String state = StatusInfo.STATE_IDLE;
        public String error = "";
        public String client = "";
        public String resolution = "-";
        public int profileId = VideoProfile.DEFAULT.id;
        public int fps;
        public int bitrate;
        public int mtu;
        public int chunkPayload;
        public int throughput;
        public long framesSent;
        public long framesDropped;
        public int queueBytes;
        public boolean advertising;
        public int profileIdc = -1;
        public int cabac = -1;
    }

    public interface UiListener {
        void onSnapshot(Snapshot snapshot);
    }

    private static volatile UiListener uiListener;

    public static void setUiListener(UiListener listener) {
        uiListener = listener;
    }

    /** Activity 通过 bindService 拿到服务能力（挂预览、切档位）。 */
    public interface Client {
        void attachPreview(SurfaceTexture texture);

        void detachPreview();

        void switchProfile(VideoProfile profile);
    }

    private final class LocalBinder extends Binder implements Client {
        @Override
        public void attachPreview(SurfaceTexture texture) {
            setPreviewTexture(texture);
        }

        @Override
        public void detachPreview() {
            setPreviewTexture(null);
        }

        @Override
        public void switchProfile(VideoProfile p) {
            applyProfile(p);
        }
    }

    private final IBinder binder = new LocalBinder();

    private BleCamServer bleServer;
    private AvcEncoder encoder;
    private Camera2Source camera;
    private StreamPump pump;
    private PowerManager.WakeLock wakeLock;

    private Surface previewSurface;
    private SurfaceTexture previewTexture;

    private VideoProfile profile = VideoProfile.DEFAULT;
    private Size encodeSize;
    private volatile boolean streaming;
    private volatile boolean advertising;

    private final StatusInfo status = new StatusInfo();
    private final Snapshot snapshot = new Snapshot();
    private volatile String lastError = "";
    private volatile String clientAddress = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopStream();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            int id = intent == null ? VideoProfile.DEFAULT.id
                    : intent.getIntExtra(EXTRA_PROFILE_ID, VideoProfile.DEFAULT.id);
            profile = VideoProfile.byId(id);
            snapshot.profileId = profile.id;
        }
        startForegroundCompat();
        if (!streaming) {
            startStream();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopStream();
        super.onDestroy();
    }

    /** 启动推流（Android 8+ 走 startForegroundService，保证后台存活）。 */
    public static void start(Context context, int profileId) {
        Intent i = new Intent(context, ServerService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_PROFILE_ID, profileId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    public static void stop(Context context) {
        Intent i = new Intent(context, ServerService.class);
        i.setAction(ACTION_STOP);
        context.startService(i);
    }

    // ------------------------------------------------------------------ 生命周期

    private synchronized void startStream() {
        if (streaming) {
            return;
        }
        lastError = "";
        acquireWakeLock();

        bleServer = new BleCamServer(this, this);
        bleServer.setStatusProvider(this);
        if (!bleServer.start()) {
            abort("BLE 服务端初始化失败：" + lastError);
            return;
        }

        encodeSize = Camera2Source.chooseSize(this, profile.width, profile.height);

        pump = new StreamPump(this, this, this);
        pump.setProfile(profile);
        pump.start();

        encoder = new AvcEncoder(this);
        if (!encoder.prepare(encodeSize.getWidth(), encodeSize.getHeight(),
                profile.fps, profile.bitrate)) {
            abort("H.264 编码器初始化失败");
            return;
        }
        encoder.start();

        camera = new Camera2Source(this, this);
        camera.open(encoder.getInputSurface(), previewSurface, encodeSize, profile.fps);

        streaming = true;
        status.state = StatusInfo.STATE_ADVERTISING;
        publish();
        Log.i(TAG, "stream started " + encodeSize + " " + profile);
    }

    /** 初始化失败时把已创建的资源收干净，避免线程/编解码器泄漏。 */
    private void abort(String message) {
        stopStream();
        fail(message);
    }

    private synchronized void stopStream() {
        streaming = false;
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (encoder != null) {
            encoder.stop();
            encoder = null;
        }
        if (pump != null) {
            pump.stop();
            pump = null;
        }
        if (bleServer != null) {
            bleServer.stop();
            bleServer = null;
        }
        advertising = false;
        releaseWakeLock();
        status.state = StatusInfo.STATE_IDLE;
        publish();
    }

    /** 切换分辨率档位需要重建相机会话和编码器；只改码率/帧率则原地生效。 */
    public synchronized void applyProfile(VideoProfile p) {
        if (p == null) {
            return;
        }
        boolean sameSize = encodeSize != null
                && encodeSize.getWidth() == p.width && encodeSize.getHeight() == p.height;
        profile = p;
        snapshot.profileId = p.id;
        if (!streaming) {
            return;
        }
        if (sameSize) {
            if (pump != null) {
                pump.setProfile(p);
            }
            if (encoder != null) {
                encoder.setBitrate(p.bitrate);
            }
            if (camera != null) {
                camera.setFps(p.fps);
            }
        } else {
            stopStream();
            startStream();
        }
    }

    private void fail(String message) {
        lastError = message;
        status.state = StatusInfo.STATE_ERROR;
        Log.e(TAG, message);
        publish();
    }

    // ------------------------------------------------------------------ 预览挂接

    private void setPreviewTexture(SurfaceTexture texture) {
        Surface old = previewSurface;
        Surface created = null;
        if (texture != null) {
            Size size = encodeSize != null ? encodeSize : Camera2Source.chooseSize(this, 320, 240);
            try {
                texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
            } catch (Exception ignored) {
            }
            created = new Surface(texture);
        }
        previewTexture = texture;
        previewSurface = created;
        Camera2Source c = camera;
        if (c != null) {
            c.setPreviewSurface(created);
        }
        if (old != null) {
            try {
                old.release();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ Camera2Source.Listener

    @Override
    public void onReady(int width, int height) {
        status.resolution = width + "x" + height;
        snapshot.resolution = status.resolution;
        Log.i(TAG, "camera ready " + width + "x" + height);
        publish();
    }

    @Override
    public void onFailed(String message) {
        fail("相机失败：" + message);
    }

    // ------------------------------------------------------------------ AvcEncoder.AuCallback

    @Override
    public void onAccessUnit(long ptsUs, byte[] annexB, boolean keyFrame) {
        StreamPump p = pump;
        if (p != null) {
            p.offer(ptsUs, annexB, keyFrame);
        }
    }

    // ------------------------------------------------------------------ BleCamServer.Callback

    @Override
    public void onAdvertisingStarted() {
        advertising = true;
        if (status.state.equals(StatusInfo.STATE_IDLE)) {
            status.state = StatusInfo.STATE_ADVERTISING;
        }
        publish();
    }

    @Override
    public void onAdvertisingFailed(int errorCode, String message) {
        advertising = false;
        fail("广播失败：" + message);
    }

    @Override
    public void onClientConnected(android.bluetooth.BluetoothDevice device) {
        clientAddress = device.getAddress();
        status.state = StatusInfo.STATE_CONNECTED;
        publish();
    }

    @Override
    public void onClientDisconnected() {
        clientAddress = "";
        status.mtu = StreamProtocol.FALLBACK_MTU;
        status.state = streaming ? StatusInfo.STATE_ADVERTISING : StatusInfo.STATE_IDLE;
        publish();
    }

    @Override
    public void onSubscriptionChanged(boolean enabled) {
        if (enabled) {
            status.state = StatusInfo.STATE_STREAMING;
            if (encoder != null) {
                encoder.requestKeyFrame();
            }
        } else {
            status.state = clientAddress.isEmpty()
                    ? StatusInfo.STATE_ADVERTISING : StatusInfo.STATE_CONNECTED;
        }
        publish();
    }

    @Override
    public void onControlCommand(byte commandId, byte[] value) {
        switch (commandId) {
            case StreamProtocol.CMD_REQUEST_IDR:
                if (encoder != null) {
                    encoder.requestKeyFrame();
                }
                break;
            case StreamProtocol.CMD_START:
                if (!streaming) {
                    startForegroundCompat();
                    startStream();
                }
                break;
            case StreamProtocol.CMD_STOP:
                stopStream();
                break;
            case StreamProtocol.CMD_SET_PROFILE:
                if (value.length >= 2) {
                    applyProfile(VideoProfile.byId(StreamProtocol.u8(value[1])));
                }
                break;
            case StreamProtocol.CMD_SET_BITRATE:
                if (value.length >= 5 && pump != null) {
                    pump.setBitrateLimit((int) StreamProtocol.u32(value, 1));
                }
                break;
            case StreamProtocol.CMD_SET_FPS:
                if (value.length >= 2) {
                    int f = StreamProtocol.u8(value[1]);
                    if (pump != null) {
                        pump.setFpsLimit(f);
                    }
                    if (camera != null) {
                        camera.setFps(f);
                    }
                }
                break;
            case StreamProtocol.CMD_PING:
                Log.d(TAG, "ping from client");
                break;
            case StreamProtocol.CMD_CLIENT_STATS:
                if (value.length >= 5) {
                    Log.d(TAG, "client throughput " + StreamProtocol.u32(value, 1) + " B/s");
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onLinkStats(BleCamServer.LinkStats stats) {
        status.throughput = stats.bytesPerSecond;
        status.mtu = stats.mtu;
        status.chunkSize = stats.chunkPayload;
        publish();
    }

    @Override
    public void onError(String message) {
        fail(message);
    }

    // ------------------------------------------------------------------ StreamPump.Sink

    @Override
    public boolean isReady() {
        BleCamServer s = bleServer;
        return streaming && s != null && s.isClientConnected();
    }

    @Override
    public int getChunkPayload() {
        BleCamServer s = bleServer;
        return s == null ? 0 : s.getChunkPayload();
    }

    @Override
    public boolean sendNotification(byte[] packet) {
        BleCamServer s = bleServer;
        return s != null && s.sendNotification(packet);
    }

    // ------------------------------------------------------------------ StreamPump.Control

    @Override
    public void requestKeyFrame() {
        AvcEncoder e = encoder;
        if (e != null) {
            e.requestKeyFrame();
        }
    }

    @Override
    public void setBitrate(int bps) {
        AvcEncoder e = encoder;
        if (e != null) {
            e.setBitrate(bps);
        }
    }

    @Override
    public void setFps(int fps) {
        Camera2Source c = camera;
        if (c != null) {
            c.setFps(fps);
        }
    }

    // ------------------------------------------------------------------ StreamPump.Listener

    @Override
    public void onStats(StreamPump.Stats s) {
        status.fps = s.fps;
        status.bitrate = s.bitrate;
        status.throughput = s.bytesPerSecond;
        status.droppedFrames = s.framesDropped;
        status.queueBytes = s.queueBytes;
        status.mtu = bleServer == null ? StreamProtocol.FALLBACK_MTU : bleServer.getMtu();
        status.chunkSize = s.chunkPayload;
        publish();
    }

    // ------------------------------------------------------------------ BleCamServer.StatusProvider

    @Override
    public byte[] statusBytes() {
        status.profileId = profile.id;
        StreamPump p = pump;
        status.lastSeq = p == null ? 0 : (p.peekNextSeq() - 1) & 0xFF;
        return status.serializeBytes();
    }

    // ------------------------------------------------------------------ 通知 / UI

    private void publish() {
        AvcEncoder e = encoder;
        if (e != null) {
            status.profileIdc = e.getActualProfileIdc();
            Boolean cabac = e.getActualCabac();
            status.cabac = cabac == null ? -1 : (cabac ? 1 : 0);
        } else {
            status.profileIdc = -1;
            status.cabac = -1;
        }
        synchronized (snapshot) {
            snapshot.state = status.state;
            snapshot.error = lastError;
            snapshot.client = clientAddress;
            snapshot.resolution = status.resolution;
            snapshot.profileId = profile.id;
            snapshot.fps = status.fps;
            snapshot.bitrate = status.bitrate;
            snapshot.mtu = status.mtu;
            snapshot.chunkPayload = status.chunkSize;
            snapshot.throughput = status.throughput;
            snapshot.framesDropped = status.droppedFrames;
            snapshot.framesSent = encoder != null ? encoder.getTotalFrames() : 0;
            snapshot.queueBytes = status.queueBytes;
            snapshot.profileIdc = status.profileIdc;
            snapshot.cabac = status.cabac;
            snapshot.advertising = advertising;
        }
        updateNotification();
        UiListener l = uiListener;
        if (l != null) {
            l.onSnapshot(copySnapshot());
        }
    }

    private Snapshot copySnapshot() {
        Snapshot out = new Snapshot();
        synchronized (snapshot) {
            out.state = snapshot.state;
            out.error = snapshot.error;
            out.client = snapshot.client;
            out.resolution = snapshot.resolution;
            out.profileId = snapshot.profileId;
            out.fps = snapshot.fps;
            out.bitrate = snapshot.bitrate;
            out.mtu = snapshot.mtu;
            out.chunkPayload = snapshot.chunkPayload;
            out.throughput = snapshot.throughput;
            out.framesSent = snapshot.framesSent;
            out.framesDropped = snapshot.framesDropped;
            out.queueBytes = snapshot.queueBytes;
            out.advertising = snapshot.advertising;
            out.profileIdc = snapshot.profileIdc;
            out.cabac = snapshot.cabac;
        }
        return out;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    getString(R.string.channel_id), getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.channel_desc));
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void startForegroundCompat() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
        }
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        try {
            nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification() {
        Intent tap = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap, flags);
        Intent stop = new Intent(this, ServerService.class);
        stop.setAction(ACTION_STOP);
        int sflags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            sflags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, sflags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, getString(R.string.channel_id))
                : new Notification.Builder(this);
        b.setContentTitle(getString(R.string.app_name))
                .setContentText(notificationText())
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.action_stop), stopPi).build());
        return b.build();
    }

    private String notificationText() {
        Size size = encodeSize;
        String res = size == null ? "-" : size.getWidth() + "x" + size.getHeight();
        return status.state + " " + res + "@" + status.fps + "fps "
                + (status.bitrate / 1000) + "kbps " + status.throughput + "B/s";
    }

    private void acquireWakeLock() {
        if (wakeLock != null) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blecams:stream");
        wakeLock.setReferenceCounted(false);
        try {
            wakeLock.acquire(8 * 60 * 60 * 1000L);
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        wakeLock = null;
    }
}

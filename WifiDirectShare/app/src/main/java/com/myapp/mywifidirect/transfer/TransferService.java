package com.myapp.mywifidirect.transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;

import com.myapp.mywifidirect.R;
import com.myapp.mywifidirect.p2p.P2pEngine;
import com.myapp.mywifidirect.ui.MainActivity;
import com.myapp.mywifidirect.util.Settings;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that keeps the receive server alive while the app is in
 * the background, and performs outgoing transfers on worker threads.
 *
 * Wire protocol (one file per connection):
 *   client -> server : writeUTF("WDS1"), writeUTF(sender), writeUTF(name), writeLong(size)
 *   server -> client : writeInt(1 accept | 0 reject)
 *   client -> server : raw file bytes
 *   size < 0 means "unknown, read until EOF" (streamed SAF sources).
 */
public final class TransferService extends Service {

    public static final int PORT = 8988;
    public static final String ACTION_START_SERVER = "com.myapp.mywifidirect.START_SERVER";
    public static final String ACTION_SEND = "com.myapp.mywifidirect.SEND";
    public static final String ACTION_SYNC_WAKE_LOCK = "com.myapp.mywifidirect.SYNC_WAKE_LOCK";
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_HOST = "host";

    private static final String MAGIC = "WDS1";
    private static final String CHANNEL = "wds_transfer";
    private static final int NOTIF_ID = 1;
    private static final int BUFFER = 64 * 1024;

    private final ExecutorService pool = Executors.newCachedThreadPool();
    /** 文件发送用单线程串行执行：同一文件夹的多个文件一个接一个发，避免
     *  多个并发 socket 连接让接收端同时创建同一层目录，产生 folder / folder (1)。 */
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
    private volatile ServerSocket server;
    private volatile boolean running;
    private Settings settings;
    private PowerManager.WakeLock wakeLock;
    private boolean wakeLockHeld;

    /**
     * 接收 SAF 文件夹时缓存已创建的子目录：key = 接收树 URI + "|" + 相对目录路径。
     * 目的：同一文件夹内多个文件共享同一批子目录，避免因 provider 查询不到已存在目录
     * 而反复 createDocument，被系统自动重命名成 temp2 / temp2 (1) / temp2 (2)...
     */
    private final Map<String, Uri> safDirCache = new ConcurrentHashMap<>();

    private static final class Target {
        OutputStream os;
        String label;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new Settings(this);
        createChannel();
        applyWakeLock(settings.wakeLock(), false);
        startForegroundCompat(getString(R.string.notif_state_idle));
        // Render the ongoing notification from the shared state snapshot so it
        // reflects searching / connected / transferring without any UI attached.
        Bus.get().setNotifObserver(this::applyNotifState);
        // Keep the P2P engine alive for the whole process lifetime so discovery,
        // connection and receiving continue in the background / after UI exit.
        P2pEngine.get(this).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START_SERVER.equals(action)) {
                startServer();
            } else if (ACTION_SEND.equals(action)) {
                final Uri uri = intent.getParcelableExtra(EXTRA_URI);
                final String host = intent.getStringExtra(EXTRA_HOST);
                // 串行发送，保证同一批（同一文件夹）文件在接收端按顺序落地。
                sendExecutor.execute(() -> send(uri, host));
            } else if (ACTION_SYNC_WAKE_LOCK.equals(action)) {
                applyWakeLock(settings.wakeLock(), true);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Bus.get().setNotifObserver(null);
        releaseWakeLock();
        stopServer();
        pool.shutdownNow();
        sendExecutor.shutdownNow();
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------- server

    private void startServer() {
        if (running) return;
        running = true;
        pool.execute(() -> {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT));
                server = ss;
                Bus.get().log(getString(R.string.msg_server_ready, PORT));
                Bus.get().server(true, localAddr());
                while (running) {
                    Socket client = ss.accept();
                    pool.execute(() -> handleIncoming(client));
                }
            } catch (Exception e) {
                if (running) Bus.get().log("接收服务异常: " + e.getMessage());
            } finally {
                try {
                    if (ss != null) ss.close();
                } catch (Exception ignored) {
                }
                server = null;
                running = false;
                Bus.get().server(false, null);
            }
        });
    }

    private void stopServer() {
        running = false;
        ServerSocket s = server;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
        server = null;
        Bus.get().log(getString(R.string.msg_server_stopped));
        Bus.get().server(false, null);
    }

    private String localAddr() {
        return "0.0.0.0:" + PORT;
    }

    private void handleIncoming(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(60000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));

            if (!MAGIC.equals(in.readUTF())) {
                Bus.get().log("已忽略未知连接");
                return;
            }
            String from = in.readUTF();
            String name = safeRelativeName(in.readUTF());
            long size = in.readLong();

            Bus.get().log(getString(R.string.msg_incoming, name, sizeLabel(size), from));

            boolean accept;
            if (settings.autoAccept() || !Bus.get().hasConfirmHandler()) {
                // Auto-accept setting, or UI not attached (app in background):
                // accept directly so background receiving keeps working.
                accept = true;
                Bus.get().log(getString(settings.autoAccept()
                        ? R.string.msg_auto_accept : R.string.msg_auto_accept_background));
            } else {
                Bus.ConfirmRequest req = new Bus.ConfirmRequest(name, from, size);
                if (!Bus.get().postConfirm(req)) {
                    accept = true;  // race: UI detached mid-way -> accept in background
                    Bus.get().log(getString(R.string.msg_auto_accept_background));
                } else {
                    accept = req.await(30);
                }
            }

            Target target = null;
            if (accept) {
                try {
                    target = openTarget(name);
                } catch (Exception e) {
                    accept = false;
                    Bus.get().log("无法创建接收文件: " + e.getMessage());
                }
            }
            out.writeInt(accept ? 1 : 0);
            out.flush();
            if (!accept || target == null) {
                Bus.get().log(getString(R.string.msg_rejected));
                return;
            }

            s.setSoTimeout(0);
            long total = 0;
            int lastPct = -1;
            byte[] buf = new byte[BUFFER];
            try (OutputStream os = target.os) {
                while (true) {
                    int want = buf.length;
                    if (size >= 0) {
                        long remaining = size - total;
                        if (remaining <= 0) break;
                        want = (int) Math.min(buf.length, remaining);
                    }
                    int n = in.read(buf, 0, want);
                    if (n < 0) break;
                    os.write(buf, 0, n);
                    total += n;
                    int pct = size > 0 ? (int) (total * 100 / size) : -1;
                    if (pct >= 0 && pct != lastPct) {
                        lastPct = pct;
                        Bus.get().progress("接收 " + name, pct);
                    }
                }
            }
            Bus.get().progressDone(name);
            Bus.get().log(getString(R.string.msg_receive_done) + " · " + sizeLabel(total));
            Bus.get().saved(target.label);
        } catch (Exception e) {
            Bus.get().log(getString(R.string.msg_transfer_fail, e.getMessage()));
        }
    }

    // ------------------------------------------------------------- client

    private void send(Uri uri, String host) {
        if (uri == null || host == null || host.isEmpty()) {
            Bus.get().log("发送参数不完整");
            return;
        }
        try (Socket s = new Socket()) {
            ContentResolver cr = getContentResolver();
            // 系统分享文件夹时，相对路径编码在 URI fragment 中；优先用它作为
            // 发送名（含子目录），这样接收端能重建目录结构。
            String fragment = uri.getFragment();
            String name = (fragment != null && !fragment.isEmpty())
                    ? safeRelativeName(fragment) : safeName(nameOf(cr, uri));
            long size = sizeOf(cr, uri);
            InputStream is = openSource(cr, uri);
            if (is == null) throw new Exception("无法读取所选文件");

            s.connect(new InetSocketAddress(host, PORT), 10000);
            s.setSoTimeout(60000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));

            out.writeUTF(MAGIC);
            out.writeUTF(settings.deviceName());
            out.writeUTF(name);
            out.writeLong(size);
            out.flush();

            int ack = in.readInt();
            if (ack != 1) {
                Bus.get().log(getString(R.string.msg_peer_rejected));
                is.close();
                return;
            }
            s.setSoTimeout(0);

            long total = 0;
            int lastPct = -1;
            byte[] buf = new byte[BUFFER];
            try (InputStream src = is) {
                int n;
                while ((n = src.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    int pct = size > 0 ? (int) (total * 100 / size) : -1;
                    if (pct >= 0 && pct != lastPct) {
                        lastPct = pct;
                        Bus.get().progress("发送 " + name, pct);
                    }
                }
            }
            out.flush();
            Bus.get().progressDone(name);
            Bus.get().log(getString(R.string.msg_send_done) + " · " + sizeLabel(total));
        } catch (Exception e) {
            Bus.get().log(getString(R.string.msg_transfer_fail, e.getMessage()));
        }
    }

    // ------------------------------------------------------------- storage

    private Target openTarget(String name) throws Exception {
        Target t = new Target();
        // 拆分相对路径：末段是文件名，前面是子目录层级
        String[] parts = name.split("/");
        String fileName = parts[parts.length - 1];
        List<String> dirs = new ArrayList<>();
        for (int i = 0; i < parts.length - 1; i++) {
            if (!parts[i].isEmpty()) dirs.add(parts[i]);
        }

        Uri tree = settings.receiveTree();
        if (tree != null) {
            // SAF 的「查-建-缓存-建文件」必须原子化：同一文件夹的多个文件是
            // 并发到达的（每个文件一个 socket 连接），若不加锁，两个线程会同时
            // 判定 folder 不存在并各自 createDocument，被 provider 自动重命名成
            // folder 与 folder (1)（文件同理变成 a.txt / a (1).txt）。
            // 用 safDirCache 作为锁对象，把整段目录解析 + 建文件串行化。
            synchronized (safDirCache) {
                ContentResolver cr = getContentResolver();
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
                // 逐级创建（或复用）子目录；缓存保证同一相对目录只建一次。
                StringBuilder relPath = new StringBuilder();
                for (String d : dirs) {
                    if (relPath.length() > 0) relPath.append('/');
                    relPath.append(d);
                    String key = tree.toString() + "|" + relPath;
                    Uri cached = safDirCache.get(key);
                    if (cached != null) {
                        parent = cached;
                    } else {
                        Uri made = ensureSafFolder(cr, tree, parent, d);
                        safDirCache.put(key, made);
                        parent = made;
                    }
                }
                Uri child = DocumentsContract.createDocument(cr, parent, mimeOf(fileName), fileName);
                if (child == null) throw new Exception("所选文件夹不可写");
                OutputStream os = cr.openOutputStream(child);
                if (os == null) throw new Exception("无法打开输出流");
                t.os = os;
                t.label = name + " → " + settings.folderLabel();
                return t;
            }
        }
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Received");
        for (String d : dirs) dir = new File(dir, d);
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建接收目录");
        File f = uniqueFile(dir, fileName);
        t.os = new FileOutputStream(f);
        t.label = f.getAbsolutePath();
        return t;
    }

    /** Find an existing SAF child folder by display name, else create it. */
    private Uri ensureSafFolder(ContentResolver cr, Uri tree, Uri parent, String name) throws Exception {
        Uri found = findSafChild(cr, tree, parent, name);
        if (found != null) return found;
        Uri made = DocumentsContract.createDocument(cr, parent,
                DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (made == null) throw new Exception("无法创建子文件夹 " + name);
        return made;
    }

    /**
     * 在 parent 下按显示名查找已存在子项。兼容两类 provider：
     *  - tree uri（buildChildDocumentsUriUsingTree）
     *  - 普通 document uri（buildChildDocumentsUri）
     * 命中目录（isDir）直接返回；若 MIME 缺失但名字完全一致，也视为目录复用，
     * 以容忍不返回 MIME 列的第三方 provider。
     */
    private Uri findSafChild(ContentResolver cr, Uri tree, Uri parent, String name) {
        String[] proj = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        Uri[] queries = new Uri[2];
        try {
            queries[0] = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getDocumentId(parent));
        } catch (Exception ignored) {
        }
        try {
            queries[1] = DocumentsContract.buildChildDocumentsUri(
                    parent.getAuthority(), DocumentsContract.getDocumentId(parent));
        } catch (Exception ignored) {
        }
        for (Uri children : queries) {
            if (children == null) continue;
            try (Cursor c = cr.query(children, proj, null, null, null)) {
                if (c == null) continue;
                int idxId = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int idxName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int idxMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                Uri fallback = null;
                while (c.moveToNext()) {
                    String dn = idxName >= 0 ? c.getString(idxName) : null;
                    if (!name.equals(dn)) continue;
                    String mime = idxMime >= 0 ? c.getString(idxMime) : null;
                    boolean isDir = mime != null && (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)
                            || mime.toLowerCase().contains("directory"));
                    if (idxId < 0) continue;
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(idxId));
                    if (isDir) return doc;
                    if (mime == null) fallback = doc;   // MIME 缺失，名字一致也复用
                }
                if (fallback != null) return fallback;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            File c = new File(dir, base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    /**
     * 系统分享进来的文件已由 MainActivity 落地到应用私有缓存（file://）。
     * 直接按路径打开，避免 ContentResolver 对 file scheme 的实现差异，也让
     * 临时 URI 授权失效后仍能继续发送。
     */
    private static InputStream openSource(ContentResolver cr, Uri uri) throws Exception {
        if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
            return new FileInputStream(new File(uri.getPath()));
        }
        return cr.openInputStream(uri);
    }

    private String nameOf(ContentResolver cr, Uri uri) {
        if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
            String p = uri.getLastPathSegment();
            if (p != null && !p.isEmpty()) return p;
        }
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && !c.isNull(i)) return c.getString(i);
            }
        } catch (Exception ignored) {
        }
        return "file.bin";
    }

    private long sizeOf(ContentResolver cr, Uri uri) {
        if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
            File f = new File(uri.getPath());
            return f.exists() ? f.length() : -1;
        }
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static String mimeOf(String name) {
        String m = URLConnection.guessContentTypeFromName(name);
        return m == null ? "application/octet-stream" : m;
    }

    private static String safeName(String n) {
        String s = n == null ? "file.bin" : n;
        s = s.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        s = s.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5() ]", "_");
        if (s.trim().isEmpty()) s = "file.bin";
        if (s.length() > 120) s = s.substring(s.length() - 120);
        return s;
    }

    /**
     * 保留安全的相对路径子目录（用 '/' 分隔），过滤绝对路径与 '..' 穿越。
     * 兼容普通文件名（无 '/' 时行为与 safeName 一致）。
     */
    private static String safeRelativeName(String n) {
        String s = n == null ? "file.bin" : n.replace('\\', '/');
        // 去掉开头的 '/'（绝对/根路径），避免写到接收目录之外
        while (s.startsWith("/")) s = s.substring(1);
        String[] parts = s.split("/");
        List<String> safe = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) continue; // 禁止目录穿越
            String seg = part.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5() ]", "_");
            if (seg.isEmpty()) seg = "_";
            if (seg.length() > 100) seg = seg.substring(seg.length() - 100);
            safe.add(seg);
        }
        if (safe.isEmpty()) return "file.bin";
        return TextUtils.join("/", safe);
    }

    private static String sizeLabel(long n) {
        if (n < 0) return "未知大小";
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.1f MB", n / (1024.0 * 1024.0));
    }

    // ------------------------------------------------------------- wake lock

    /**
     * Acquire or release a PARTIAL_WAKE_LOCK so the CPU stays on while the app
     * is running (screen off included), keeping background discovery / transfer
     * from being frozen by Doze. Reads the persisted setting.
     */
    private synchronized void applyWakeLock(boolean enable, boolean announce) {
        if (enable) {
            if (!wakeLockHeld) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                            "MyWifidirect:TransferWakeLock");
                    wakeLock.acquire();
                    wakeLockHeld = true;
                    if (announce) Bus.get().log(getString(R.string.msg_wake_lock_on));
                }
            }
        } else {
            releaseWakeLock(announce);
        }
    }

    private synchronized void releaseWakeLock() {
        releaseWakeLock(false);
    }

    private synchronized void releaseWakeLock(boolean announce) {
        if (wakeLockHeld && wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
            wakeLockHeld = false;
            if (announce) Bus.get().log(getString(R.string.msg_wake_lock_off));
        }
    }

    /** Ask the running service to re-read the WakeLock setting (called after settings save). */
    public static void syncWakeLock(android.content.Context ctx) {
        Intent i = new Intent(ctx, TransferService.class);
        i.setAction(ACTION_SYNC_WAKE_LOCK);
        try {
            ctx.startService(i);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------- notification

    /** Actively remove the foreground notification (called on app exit). */
    public static void cancelNotification(android.content.Context ctx) {
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.notif_channel_desc));
            nm.createNotificationChannel(ch);
        }
    }

    /** PendingIntent that brings the (singleTask) MainActivity to the foreground. */
    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, 0, i, flags);
    }

    private Notification build(String stateLabel, int progress, boolean indeterminate) {
        String title = getString(R.string.notif_title_fmt, stateLabel);
        String log = Bus.get().getLastLogLine();
        if (log == null || log.isEmpty()) log = getString(R.string.notif_log_empty);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(log)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false);
        if (indeterminate) {
            b.setProgress(0, 0, true);
        } else if (progress >= 0) {
            b.setProgress(100, progress, false);
        }
        return b.build();
    }

    private void startForegroundCompat(String stateLabel) {
        Notification n = build(stateLabel, -1, false);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    /**
     * Render the ongoing notification from the shared state snapshot.
     * Main title = "WiFi Direct (状态)", subtitle = the latest single log line.
     * Priority: transferring > connected > searching > last result > idle.
     */
    private void applyNotifState(Bus.NotifState s) {
        String state;
        int progress = -1;
        boolean indeterminate = false;

        if (s.transferLabel != null && !s.transferLabel.isEmpty()) {
            // transferLabel is "发送 xxx" / "接收 xxx"; show verb + percent as state.
            state = getString(R.string.notif_transferring, s.transferLabel, s.transferPct);
            progress = s.transferPct;
        } else if (s.connected) {
            state = getString(R.string.notif_state_connected);
        } else if (s.discovering) {
            state = getString(R.string.notif_state_searching);
        } else if (s.lastDone != null && !s.lastDone.isEmpty()) {
            state = getString(R.string.notif_done, s.lastDone);
        } else if (!s.p2pEnabled) {
            state = getString(R.string.notif_p2p_off);
        } else {
            state = getString(R.string.notif_state_idle);
        }

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, build(state, progress, indeterminate));
    }
}

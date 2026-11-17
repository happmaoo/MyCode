package com.myapp.mywifidirect.ui;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.myapp.mywifidirect.R;
import com.myapp.mywifidirect.p2p.P2pEngine;
import com.myapp.mywifidirect.transfer.Bus;
import com.myapp.mywifidirect.transfer.TransferService;
import com.myapp.mywifidirect.util.RootFs;
import com.myapp.mywifidirect.util.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Thin UI controller. All P2P / transfer lifecycle now lives in the process-wide
 * {@link P2pEngine} + foreground {@link TransferService}, so leaving the UI (even
 * finishing it with Back) never stops discovery, connection or receiving.
 */
public final class MainActivity extends AppCompatActivity
        implements Bus.Listener, Bus.ConfirmHandler {

    private static final int REQ_PERMS = 1;
    private static final int REQ_NOTIF = 2;
    private static final int REQ_FILE = 3;

    private P2pEngine engine;
    private Settings settings;
    private PeerAdapter adapter;

    private TextView tvState, tvName, tvConn, tvProgress, tvLog, tvNoPeers;
    private Button btnEnableWifi, btnDiscover, btnSend, btnDisconnect;
    private ProgressBar progress;

    private boolean notifRequested;
    private String lastSystemName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        tvState = findViewById(R.id.tv_p2p_state);
        tvName = findViewById(R.id.tv_my_name);
        tvConn = findViewById(R.id.tv_connection);
        tvProgress = findViewById(R.id.tv_progress);
        tvLog = findViewById(R.id.tv_log);
        tvNoPeers = findViewById(R.id.tv_no_peers);
        btnEnableWifi = findViewById(R.id.btn_enable_wifi);
        btnDiscover = findViewById(R.id.btn_discover);
        btnSend = findViewById(R.id.btn_send);
        btnDisconnect = findViewById(R.id.btn_disconnect);
        progress = findViewById(R.id.progress);

        RecyclerView list = findViewById(R.id.peer_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PeerAdapter(d -> engine.connectTo(d));
        list.setAdapter(adapter);

        // 日志区可滚动 + 可选中复制：textIsSelectable 已使其自带可滚动的
        // SelectionMovementMethod；这里仅阻止外层 ScrollView 抢走触摸事件，
        // 否则在某些版本上日志区无法自身滚动。不要另设 ScrollingMovementMethod，
        // 那会破坏长按复制。
        tvLog.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // 手指落在日志区：禁止父 ScrollView 拦截，交给日志自身滚动/选择
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                default:
                    break;
            }
            return false; // 返回 false 保留 TextView 自身的长按选择/复制能力
        });

        settings = new Settings(this);
        tvName.setText(settings.deviceName());
        setDiscovering(false);
        btnDisconnect.setEnabled(false);
        btnSend.setEnabled(false);

        engine = P2pEngine.get(this);

        btnEnableWifi.setOnClickListener(v -> engine.onEnableWifiClicked());
        btnDiscover.setOnClickListener(v -> engine.toggleDiscovery());
        btnSend.setOnClickListener(v -> pickAndSend());
        btnDisconnect.setOnClickListener(v -> engine.disconnect());

        ensurePermissions();
        handleIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Start the engine / foreground service as soon as the app is used.
        engine.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Bus.get().setUiForeground(true);
        Bus.get().setListener(this);
        Bus.get().setConfirmHandler(this);
        tvName.setText(settings.deviceName());
        engine.onSettingsChanged();
        engine.publishState();
        refreshLog();
        if (engine.isInitialized()) updateWifiHint();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Detach UI hooks: while no Activity is in front the service auto-accepts
        // incoming files and discovery failures are treated as background noise.
        Bus.get().setUiForeground(false);
        Bus.get().setConfirmHandler(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Bus.get().setListener(null);
        Bus.get().setConfirmHandler(null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    // ------------------------------------------------------------- permissions

    private String discoveryPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    private String[] permissionsToRequest() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.POST_NOTIFICATIONS};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasDiscoveryPermission() {
        return ContextCompat.checkSelfPermission(this, discoveryPermission())
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermissions() {
        Bus.get().log(getString(R.string.msg_perm_needed));
        if (hasDiscoveryPermission()) {
            engine.onPermissionsGranted();
            requestNotificationIfNeeded();
        } else {
            requestPermissions(permissionsToRequest(), REQ_PERMS);
        }
    }

    private void requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT < 33 || notifRequested) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        notifRequested = true;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_PERMS) {
            if (hasDiscoveryPermission()) {
                Bus.get().log("权限已授予，初始化 Wi-Fi P2P");
                engine.onPermissionsGranted();
            } else {
                Bus.get().log(getString(R.string.msg_perm_denied));
            }
            requestNotificationIfNeeded();
        }
    }

    private void updateWifiHint() {
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean wifiOn = wm != null && wm.isWifiEnabled();
        btnEnableWifi.setVisibility(wifiOn ? Button.GONE : Button.VISIBLE);
    }

    // ------------------------------------------------------------- sending

    private void pickAndSend() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, REQ_FILE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQ_FILE) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            engine.sendPicked(uri);
        }
    }

    // ------------------------------------------------------------- shared files

    /** Max files staged from one share (guard against huge folders). */
    private static final int MAX_SHARE_FILES = 500;
    private static final int MAX_DIR_DEPTH = 12;

    /** A staged source plus its relative path inside the shared folder. */
    private static final class SharedItem {
        final Uri uri;
        final String relative; // 可能为空；含子目录时用 '/' 分隔
        SharedItem(Uri uri, String relative) {
            this.uri = uri;
            this.relative = relative;
        }
    }

    /** Stage system-shared files/folders into private cache, then hand them to the engine. */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        List<Uri> incoming = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) incoming.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) incoming.addAll(list);
        }
        if (incoming.isEmpty()) return;

        cleanStaleShared();
        final long batchId = System.currentTimeMillis();
        List<Uri> local = new ArrayList<>();
        for (Uri u : incoming) {
            stageUri(u, "", batchId, local, 0);
            if (local.size() >= MAX_SHARE_FILES) break;
        }
        if (local.isEmpty()) {
            Bus.get().log(getString(R.string.msg_share_fail));
            return;
        }
        Bus.get().log(getString(R.string.msg_share_received, local.size()));
        engine.enqueueShared(local);
    }

    /**
     * Recursively stage a URI: directories are walked into their children, concrete
     * files are copied into the private cache (relative path encoded in the URI
     * fragment so the receiver can rebuild sub-folders).
     *
     * Robustness: some file managers report a directory with an unreliable MIME
     * type, so isDirectoryUri() may miss it. If reading a "file" fails (typically
     * EISDIR), we fall back to treating it as a directory and walking its children.
     */
    private void stageUri(Uri uri, String relBase, long batchId, List<Uri> local, int depth) {
        if (local.size() >= MAX_SHARE_FILES || depth > MAX_DIR_DEPTH || uri == null) return;
        String name = displayName(uri);
        String rel = relBase.isEmpty() ? name : relBase + "/" + name;

        if (isDirectoryUri(uri)) {
            for (Uri child : listChildren(uri)) {
                stageUri(child, rel, batchId, local, depth + 1);
                if (local.size() >= MAX_SHARE_FILES) break;
            }
            return;
        }

        Uri staged = stageShared(uri, rel, batchId);
        if (staged != null) {
            local.add(staged);
            return;
        }
        // 兜底：读取失败很可能因为其实是目录（provider 未报告目录 MIME），再当目录展开一次。
        List<Uri> kids = listChildren(uri);
        if (!kids.isEmpty()) {
            for (Uri child : kids) {
                stageUri(child, rel, batchId, local, depth + 1);
                if (local.size() >= MAX_SHARE_FILES) break;
            }
        }
    }

    /** Determine whether a URI points at a directory. Multiple signals, most reliable first. */
    private boolean isDirectoryUri(Uri uri) {
        if (uri == null) return false;
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) return false;
            // 递归展开子目录时，子项是 file:// + 真实路径（由 root 列举得出）。
            // Android 11+ 分区存储下普通 File.isDirectory() 可能返回 false，
            // 导致子目录被当成文件；已有 root 时优先用 root 判断。
            if (RootFs.isDirectory(path)) return true;
            return new File(path).isDirectory();
        }
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            // 1) SAF tree URI 一定是目录（很多文件管理器分享文件夹就用这种）。
            try {
                if (DocumentsContract.getTreeDocumentId(uri) != null) return true;
            } catch (Exception ignored) {
            }
            // 2) URI 路径含 /tree/，或本身就是真实目录路径（自定义 FileProvider 常见，
            //    例如 content://xxx.files/storage/emulated/0/temp2）。
            String path = uri.getPath();
            if (path != null && path.startsWith("/")) {
                if (path.contains("/tree/") || path.endsWith("/tree")) return true;
                // root 环境：直接按真实路径判断，绕过分区存储（最可靠）。
                if (RootFs.isDirectory(path)) return true;
                // 非 root / Android 10-：普通 File 判断。
                try {
                    if (new File(path).isDirectory()) return true;
                } catch (Exception ignored) {
                }
            }
            // 3) 查 MIME type；用 null projection 兼容返回列不全的 provider。
            try {
                Cursor c = getContentResolver().query(uri, null, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            int i = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                            String mime = i >= 0 ? c.getString(i) : null;
                            if (isDirMime(mime)) return true;
                        }
                    } finally {
                        c.close();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static boolean isDirMime(String mime) {
        if (mime == null) return false;
        String m = mime.toLowerCase(Locale.US);
        return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)
                || m.contains("directory") || m.contains("folder")
                || m.equals("inode/dir");
    }

    /** List direct children of a directory URI (file / custom provider / SAF tree / SAF document). */
    private List<Uri> listChildren(Uri dir) {
        List<Uri> out = new ArrayList<>();
        if ("file".equalsIgnoreCase(dir.getScheme())) {
            String path = dir.getPath();
            // 递归到子目录时，URI 已是 file:// + 真实路径（上一层用 root 列举得出）。
            // 分区存储下 new File(path).listFiles() 会返回 null，导致子目录漏发；
            // 已有 root 时必须同样用 root 列举，才能读取子文件夹里的内容。
            if (path != null && path.startsWith("/")) {
                List<String> kids = RootFs.listChildren(path);
                if (!kids.isEmpty()) {
                    for (String k : kids) out.add(Uri.fromFile(new File(k)));
                    return out;
                }
            }
            File[] kids = new File(dir.getPath()).listFiles();
            if (kids != null) for (File f : kids) out.add(Uri.fromFile(f));
            return out;
        }
        // 自定义 FileProvider：URI path 常直接是真实路径（.../storage/emulated/0/temp2）。
        // root 环境优先用 root 列举（子项 file://，由 root 读取），绕过分区存储；
        // 否则回退普通 File（Android 10- 或非受限路径有效）。
        if ("content".equalsIgnoreCase(dir.getScheme())) {
            String path = dir.getPath();
            if (path != null && path.startsWith("/")) {
                List<String> kids = RootFs.listChildren(path);
                if (!kids.isEmpty()) {
                    for (String k : kids) out.add(Uri.fromFile(new File(k)));
                    return out;
                }
                try {
                    File df = new File(path);
                    if (df.isDirectory()) {
                        File[] files = df.listFiles();
                        if (files != null) {
                            for (File f : files) out.add(Uri.fromFile(f));
                            return out;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        try {
            ContentResolver cr = getContentResolver();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(dir,
                    DocumentsContract.getTreeDocumentId(dir));
            String[] proj = {DocumentsContract.Document.COLUMN_DOCUMENT_ID};
            Cursor c = cr.query(children, proj, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                    while (c.moveToNext() && out.size() < MAX_SHARE_FILES) {
                        String docId = c.getString(idx);
                        out.add(DocumentsContract.buildDocumentUriUsingTree(dir, docId));
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception e) {
            // uri 可能不是 tree uri，尝试作为 document uri 处理
            try {
                ContentResolver cr = getContentResolver();
                Uri children = DocumentsContract.buildChildDocumentsUri(
                        dir.getAuthority(), DocumentsContract.getDocumentId(dir));
                String[] proj = {DocumentsContract.Document.COLUMN_DOCUMENT_ID};
                Cursor c = cr.query(children, proj, null, null, null);
                if (c != null) {
                    try {
                        int idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                        while (c.moveToNext() && out.size() < MAX_SHARE_FILES) {
                            String docId = c.getString(idx);
                            out.add(DocumentsContract.buildDocumentUri(
                                    dir.getAuthority(), docId));
                        }
                    } finally {
                        c.close();
                    }
                }
            } catch (Exception ignored) {
                Bus.get().log(getString(R.string.msg_share_dir_fail, String.valueOf(dir)));
            }
        }
        return out;
    }

    private Uri stageShared(Uri src, String relative, long batchId) {
        File batch = new File(new File(getCacheDir(), "shared"), String.valueOf(batchId));
        if (!batch.exists() && !batch.mkdirs()) return null;
        File dst = uniqueSharedFile(batch, safeFileName(displayName(src)));
        try {
            boolean copied = false;
            // root 环境：file:// 子项按真实路径用 root 复制，绕过 Android 11+ 分区存储。
            if ("file".equalsIgnoreCase(src.getScheme())) {
                String path = src.getPath();
                if (path != null && RootFs.available()) {
                    copied = RootFs.copyFile(path, dst);
                }
            }
            if (!copied) {
                try (InputStream in = openSharedSource(src);
                     OutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                }
            }
            // 把相对路径编码进 fragment，随 URI 传到服务，用于接收端重建目录结构。
            String rel = relative == null ? "" : relative.trim();
            Uri staged = Uri.fromFile(dst);
            if (!rel.isEmpty()) staged = staged.buildUpon().fragment(rel).build();
            return staged;
        } catch (Exception e) {
            // 目录被误当文件读取时会留下一个 0 字节文件，清掉避免残留。
            if (dst.exists()) dst.delete();
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String low = msg.toLowerCase(Locale.US);
            // EISDIR / "Is a directory" 说明其实是目录：由 stageUri 兜底展开，不算错误。
            boolean likelyDir = low.contains("eisdir") || low.contains("is a directory");
            if (!likelyDir) {
                Bus.get().log(getString(R.string.msg_share_stage_fail, msg));
            }
            return null;
        }
    }

    private InputStream openSharedSource(Uri uri) throws Exception {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return new java.io.FileInputStream(new File(uri.getPath()));
        }
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new java.io.IOException("无法打开输入流");
        return in;
    }

    private String displayName(Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String p = uri.getLastPathSegment();
            if (p != null && !p.isEmpty()) return p;
        }
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && !c.isNull(i)) return c.getString(i);
            }
        } catch (Exception ignored) {
        }
        // 兜底：自定义 provider 常把真实文件名/目录名直接放在 URI 最后一段。
        String last = uri.getLastPathSegment();
        if (last != null && !last.isEmpty()) return last;
        return "shared.bin";
    }

    private static String safeFileName(String n) {
        String s = n == null ? "shared.bin" : n;
        s = s.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        s = s.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5() ]", "_");
        if (s.trim().isEmpty()) s = "shared.bin";
        if (s.length() > 100) s = s.substring(s.length() - 100);
        return s;
    }

    private static File uniqueSharedFile(File dir, String name) {
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

    private void cleanStaleShared() {
        File root = new File(getCacheDir(), "shared");
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        for (File d : dirs) {
            if (d.lastModified() < cutoff) deleteRecursively(d);
        }
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }

    // ------------------------------------------------------------- Bus callbacks (UI)

    private void setDiscovering(boolean on) {
        btnDiscover.setText(getString(on
                ? R.string.btn_stop_discover : R.string.btn_discover));
    }

    /** 设置日志文本并自动滚动到底部（等布局完成后再滚，否则高度尚未算好）。 */
    private void refreshLog() {
        tvLog.setText(Bus.get().getLog());
        tvLog.post(() -> {
            android.text.Layout layout = tvLog.getLayout();
            if (layout == null) return;
            int scrollY = layout.getHeight() - tvLog.getHeight() + tvLog.getPaddingBottom();
            tvLog.scrollTo(0, Math.max(0, scrollY));
        });
    }

    @Override
    public void onLog(String line) {
        refreshLog();
    }

    @Override
    public void onProgress(String label, int pct) {
        if (pct < 0) {
            // 传输完成：进度条复位，文字提示刚完成的文件
            progress.setProgress(0);
            String n = label == null ? "" : label;
            tvProgress.setText(n.isEmpty() ? getString(R.string.status_idle)
                    : getString(R.string.progress_done_fmt, n));
            return;
        }
        progress.setProgress(Math.max(0, Math.min(100, pct)));
        tvProgress.setText(label + " · " + pct + "%");
    }

    @Override
    public void onServer(boolean running, String addr) {
        // Engine logs server state; nothing else needed in UI.
    }

    @Override
    public void onSaved(String display) {
        // Logged by the service.
    }

    @Override
    public void onP2pState(boolean enabled) {
        tvState.setText(getString(enabled ? R.string.state_on : R.string.state_off));
    }

    @Override
    public void onThisDevice(String name) {
        if (name == null || name.isEmpty()) return;
        if (!name.equals(lastSystemName)) lastSystemName = name;
    }

    @Override
    public void onPeers(List<android.net.wifi.p2p.WifiP2pDevice> peers) {
        adapter.submit(peers);
        tvNoPeers.setVisibility(peers.isEmpty() ? TextView.VISIBLE : TextView.GONE);
    }

    @Override
    public void onConnection(boolean connected, String connLabel) {
        tvConn.setText(connLabel);
    }

    @Override
    public void onDiscovering(boolean on) {
        setDiscovering(on);
    }

    @Override
    public void onWifiHint(boolean showEnableButton) {
        btnEnableWifi.setVisibility(showEnableButton ? Button.VISIBLE : Button.GONE);
    }

    @Override
    public void onDisconnectEnabled(boolean enabled) {
        // 发送文件与断开连接都只在已连接时可用。
        btnDisconnect.setEnabled(enabled);
        btnSend.setEnabled(enabled);
        // 已连接时禁用附近设备列表里的「连接」按钮，避免重复连接。
        if (adapter != null) adapter.setConnectEnabled(!enabled);
    }

    @Override
    public void onExitRequest() {
        finishAffinity();
    }

    @Override
    public void onConfirm(Bus.ConfirmRequest req) {
        if (isFinishing() || isDestroyed()) {
            req.resolve(true);  // background -> accept
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_incoming_title)
                .setMessage(getString(R.string.dlg_incoming_msg, req.name,
                        sizeLabel(req.size), req.from))
                .setPositiveButton(R.string.lbl_accept, (d, w) -> req.resolve(true))
                .setNegativeButton(R.string.lbl_reject, (d, w) -> req.resolve(false))
                .setOnCancelListener(d -> req.resolve(false))
                .show();
    }

    private static String sizeLabel(long n) {
        if (n < 0) return "未知大小";
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", n / 1024.0);
        return String.format(Locale.getDefault(), "%.1f MB", n / (1024.0 * 1024.0));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (id == R.id.action_refresh) {
            engine.rescan();
        } else if (id == R.id.action_about) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.about_text)
                    .setPositiveButton(R.string.lbl_ok, null)
                    .show();
        } else if (id == R.id.action_exit) {
            engine.exitApp();
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }
}

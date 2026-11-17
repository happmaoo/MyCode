package com.myapp.mywifidirect.p2p;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.myapp.mywifidirect.R;
import com.myapp.mywifidirect.transfer.Bus;
import com.myapp.mywifidirect.transfer.TransferService;
import com.myapp.mywifidirect.util.RootWifi;
import com.myapp.mywifidirect.util.Settings;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Process-wide P2P engine. Owns the whole Wi-Fi Direct lifecycle (broadcast
 * receiver, discovery retries, auto-connect, connection state, group info,
 * shared-file queue and the idle watchdog). It lives as long as the process,
 * independent of any Activity, so discovery/connection/receiving keep working
 * when the UI is in the background or the Activity has been finished.
 *
 * The foreground {@link TransferService} keeps the process alive; the UI
 * merely observes state via {@link Bus} and issues commands through this class.
 */
public final class P2pEngine implements P2pController.Listener {

    private static final long AUTO_RETRY_MS = 20_000L;
    private static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000L;

    private static volatile P2pEngine instance;

    public static P2pEngine get(Context ctx) {
        if (instance == null) {
            synchronized (P2pEngine.class) {
                if (instance == null) instance = new P2pEngine(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Settings settings;
    private final P2pController p2p;

    private boolean started;
    /** Set once exit begins; blocks every path that could restart the service. */
    private boolean exiting;
    private boolean initialized;
    private String appliedName;
    private String lastSystemName;

    private boolean connected;
    /** True only after a real connection was established; avoids false
     *  "disconnected" logs from the initial / periodic "no group" broadcasts. */
    private boolean everConnected;
    /** 上次主动连接的对端（设备名或 MAC），用于断开后自动重连。 */
    private String lastConnectedTarget;
    /** 用户手动点击了「断开连接」；此次断开不应触发自动重连。 */
    private boolean manualDisconnect;
    private boolean isGo;
    private String goHost;
    private WifiP2pGroup group;

    private boolean discovering;

    private boolean rootPending;
    private long lastRootMs;

    private boolean exitStarted;
    private boolean exitDone;

    // auto search / auto connect (startup cycle)
    private String autoConnectTarget;
    private boolean autoConnectPending;
    private boolean autoConnectDone;
    private int autoSearchCount;
    /** 自动连接请求已发出、等待结果，避免同一目标被重复 connect。 */
    private boolean autoConnectTrying;

    // reconnect-after-drop: independent from the startup auto-connect cycle so a
    // failed attempt can be retried and a manual action can cancel it cleanly.
    private boolean reconnecting;
    private boolean reconnectConnectPending;
    private int reconnectAttempt;
    private static final long RECONNECT_RETRY_MS = 6000L;
    private static final int RECONNECT_RETRY_MAX = 8;

    // system-shared files staged into private cache, waiting to be sent
    private final List<Uri> pendingShared = new ArrayList<>();
    private String sendTarget;

    private final Runnable autoRetryTask = this::onAutoRetry;
    private final Runnable reconnectRetryTask = this::onReconnectTick;
    private final Runnable idleExitTask = this::onIdleTimeout;
    private final Runnable idleConfirmTask = this::confirmIdleExit;
    private final Runnable exitTask = this::performExit;

    // Background discovery is frequently throttled/rejected by the system.
    // Retry a few times quietly instead of showing a misleading "check Wi-Fi".
    private static final long BG_DISCOVER_RETRY_MS = 4000L;
    private static final int BG_DISCOVER_RETRY_MAX = 3;
    private int bgDiscoverRetries;
    private final Runnable bgDiscoverRetryTask = this::onBgDiscoverRetry;

    private P2pEngine(Context app) {
        this.app = app;
        this.settings = new Settings(app);
        this.p2p = new P2pController(app, this);
    }

    // ------------------------------------------------------------- lifecycle

    /** Idempotent. Starts the engine exactly once for the process. */
    public void start() {
        if (started || exiting) return;
        started = true;
        Bus.get().log(app.getString(R.string.msg_engine_start));
        // Bring up the foreground service first: it keeps this engine (and the
        // whole process) alive so discovery/connection/receiving survive even
        // after the Activity is finished with Back.
        Intent svc = new Intent(app, TransferService.class);
        ContextCompat.startForegroundService(app, svc);
        if (hasDiscoveryPermission()) {
            initP2p();
        } else {
            Bus.get().log(app.getString(R.string.msg_perm_needed));
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String deviceName() {
        return settings.deviceName();
    }

    /** Called after runtime permissions are granted. */
    public void onPermissionsGranted() {
        if (!initialized && hasDiscoveryPermission()) initP2p();
    }

    private String discoveryPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    private boolean hasDiscoveryPermission() {
        return ContextCompat.checkSelfPermission(app, discoveryPermission())
                == PackageManager.PERMISSION_GRANTED;
    }

    private void initP2p() {
        if (initialized) return;
        initialized = true;
        if (!p2p.init()) {
            Bus.get().p2pState(false);
            Bus.get().log(app.getString(R.string.state_unsupported));
            return;
        }
        p2p.register();
        appliedName = settings.deviceName();
        p2p.setDeviceName(appliedName);
        p2p.requestInfo();
        updateWifiHint();
        scheduleIdleExit();
        maybeStartAutoConnect();
    }

    /** Re-apply display name & auto-connect target after settings changed. */
    public void onSettingsChanged() {
        if (!initialized || !p2p.ready()) return;
        String n = settings.deviceName();
        if (!n.equals(appliedName)) {
            appliedName = n;
            p2p.setDeviceName(n);
        }
        // maybeStartAutoConnect() detects a changed target itself and only then
        // restarts the cycle; calling it on every onResume won't restart an
        // in-progress / finished search with an unchanged target.
        maybeStartAutoConnect();
    }

    /** Re-push all cached state to a freshly attached UI. */
    public void publishState() {
        // P2P enabled state is re-queried from the system Wi-Fi manager indirectly
        // via wifi hint; peers/connection are re-requested from the system.
        if (initialized && p2p.ready()) {
            p2p.requestInfo();
            p2p.requestPeers();
        }
        Bus.get().p2pState(isWifiOn());
        Bus.get().discovering(discovering);
        Bus.get().disconnectEnabled(connected);
        String label;
        if (!connected) label = app.getString(R.string.state_not_connected);
        else if (isGo) label = app.getString(R.string.state_connected_go);
        else label = app.getString(R.string.state_connected_client,
                goHost == null ? "?" : goHost);
        Bus.get().connection(connected, label);
        Bus.get().thisDevice(lastSystemName == null ? "" : lastSystemName);
        Bus.get().wifiHint(!isWifiOn());
    }

    // ------------------------------------------------------------- UI commands

    public void toggleDiscovery() {
        // 手动搜索操作优先，取消后台重连会话。
        stopReconnect();
        reconnectConnectPending = false;
        if (discovering) stopDiscovery();
        else startDiscovery();
    }

    private void startDiscovery() {
        if (!initialized || !p2p.ready()) return;
        p2p.discover();
        setDiscovering(true);
    }

    private void stopDiscovery() {
        if (!initialized || !p2p.ready()) return;
        if (autoConnectPending) {
            Bus.get().log(app.getString(R.string.msg_auto_connect_cancel));
            stopAutoConnect();
        }
        p2p.stopDiscovery();
        setDiscovering(false);
    }

    /** Menu "rescan": restart a full 3-attempt cycle when not connected. */
    public void rescan() {
        stopReconnect();
        reconnectConnectPending = false;
        if (connected) {
            startDiscovery();
            return;
        }
        ui.removeCallbacks(autoRetryTask);
        autoConnectPending = false;
        autoConnectDone = false;
        autoConnectTarget = null;
        maybeStartAutoConnect();
    }

    public void disconnect() {
        if (!initialized || !p2p.ready()) return;
        // 标记为用户主动断开：取消任何重连会话，并避免断开回调再次触发。
        stopReconnect();
        reconnectConnectPending = false;
        manualDisconnect = true;
        p2p.disconnect();
    }

    public void connectTo(WifiP2pDevice d) {
        if (!initialized || !p2p.ready() || d == null) return;
        String label = d.deviceName == null || d.deviceName.trim().isEmpty()
                ? d.deviceAddress : d.deviceName.trim();
        // 记住本次连接目标，供「断开后自动连接」使用。
        if (label != null && !label.isEmpty()) lastConnectedTarget = label;
        // 手动发起连接（含重连命中）后停掉重连定时器，连接结果由 onActionResult 处理。
        ui.removeCallbacks(reconnectRetryTask);
        manualDisconnect = false;
        Bus.get().log(app.getString(R.string.msg_connect, label));
        p2p.connect(d.deviceAddress, settings.preferGo() ? 15 : 0);
    }

    /** Send a file via ACTION_OPEN_DOCUMENT result. Target resolved internally. */
    public void sendPicked(Uri uri) {
        String target = resolveSendTarget();
        if (target == null) {
            Bus.get().log(app.getString(isGo
                    ? R.string.msg_no_target : R.string.msg_need_connection));
            return;
        }
        dispatchSend(uri, target);
    }

    private void dispatchSend(Uri uri, String host) {
        if (exiting) return;
        Bus.get().log("发送到 " + host + ":" + TransferService.PORT);
        Intent i = new Intent(app, TransferService.class);
        i.setAction(TransferService.ACTION_SEND);
        i.putExtra(TransferService.EXTRA_URI, uri);
        i.putExtra(TransferService.EXTRA_HOST, host);
        ContextCompat.startForegroundService(app, i);
    }

    // ------------------------------------------------------------- shared files

    /** Stage system-shared files (already copied to private cache by the UI). */
    public void enqueueShared(List<Uri> localUris) {
        if (localUris == null || localUris.isEmpty()) return;
        pendingShared.addAll(localUris);
        if (!connected) {
            Bus.get().log(app.getString(R.string.msg_share_wait, pendingShared.size()));
        }
        flushPendingShared();
    }

    private void flushPendingShared() {
        if (pendingShared.isEmpty() || !connected) return;
        String target = resolveSendTarget();
        if (target == null) {
            Bus.get().log(app.getString(isGo
                    ? R.string.msg_no_target : R.string.msg_need_connection));
            return;
        }
        List<Uri> batch = new ArrayList<>(pendingShared);
        pendingShared.clear();
        Bus.get().log(app.getString(R.string.msg_share_auto, batch.size(), target));
        for (Uri u : batch) dispatchSend(u, target);
    }

    private String resolveSendTarget() {
        if (goHost != null) return goHost;
        if (isGo) return resolveClientIp(group);
        return null;
    }

    private String resolveClientIp(WifiP2pGroup g) {
        if (g == null) return null;
        for (WifiP2pDevice d : g.getClientList()) {
            String ip = arpLookup(d.deviceAddress);
            if (ip != null) return ip;
        }
        return null;
    }

    private static String arpLookup(String mac) {
        if (mac == null) return null;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/arp"))) {
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 4 && mac.equalsIgnoreCase(f[3])) return f[0];
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------- auto search

    /**
     * 连接被意外中断后自动重连上次目标。使用独立的重试循环（不复用启动时
     * 一次性自动连接流程），每一轮都重新启动搜索并在 onPeers 中匹配目标，
     * 连接失败也会继续重试，直到成功、被手动操作取消或达到次数上限。
     */
    private void reconnectAfterDrop() {
        if (connected || exiting || !initialized || !p2p.ready()) return;
        if (!hasDiscoveryPermission()) return;
        String target = lastConnectedTarget == null ? "" : lastConnectedTarget.trim();
        if (target.isEmpty()) {
            Bus.get().log(app.getString(R.string.msg_reconnect_no_target));
            return;
        }
        Bus.get().log(app.getString(R.string.msg_reconnect_start, target));
        reconnecting = true;
        reconnectAttempt = 0;
        ui.removeCallbacks(reconnectRetryTask);
        onReconnectTick();
    }

    /** 每一轮重连：重新发起一次设备搜索；onPeers 命中目标时会发起连接。 */
    private void onReconnectTick() {
        if (!reconnecting || connected || exiting || !initialized || !p2p.ready()) return;
        if (!hasDiscoveryPermission()) {
            stopReconnect();
            return;
        }
        if (reconnectAttempt >= RECONNECT_RETRY_MAX) {
            Bus.get().log(app.getString(R.string.msg_reconnect_stop, RECONNECT_RETRY_MAX));
            stopReconnect();
            if (discovering) {
                p2p.stopDiscovery();
                setDiscovering(false);
            }
            return;
        }
        reconnectAttempt++;
        Bus.get().log(app.getString(R.string.msg_reconnect_retry,
                reconnectAttempt, RECONNECT_RETRY_MAX));
        if (!discovering) setDiscovering(true);
        p2p.discover();
        ui.removeCallbacks(reconnectRetryTask);
        ui.postDelayed(reconnectRetryTask, RECONNECT_RETRY_MS);
    }

    private void stopReconnect() {
        reconnecting = false;
        ui.removeCallbacks(reconnectRetryTask);
    }

    private void maybeStartAutoConnect() {
        String target = settings.autoConnectName();
        String cur = autoConnectTarget == null ? "" : autoConnectTarget;
        if (!target.equals(cur)) {
            ui.removeCallbacks(autoRetryTask);
            autoConnectPending = false;
            autoConnectDone = false;
        }
        autoConnectTarget = target;
        if (autoConnectDone || autoConnectPending) return;
        if (!initialized || !p2p.ready() || connected) return;
        if (!hasDiscoveryPermission()) return;

        autoConnectPending = true;
        autoConnectDone = false;
        autoSearchCount = 1;
        Bus.get().log(target.isEmpty()
                ? app.getString(R.string.msg_auto_search_start)
                : app.getString(R.string.msg_auto_connect_start, target));
        startDiscovery();
        scheduleAutoRetry();
    }

    private void scheduleAutoRetry() {
        ui.removeCallbacks(autoRetryTask);
        ui.postDelayed(autoRetryTask, AUTO_RETRY_MS);
    }

    private void onAutoRetry() {
        if (!autoConnectPending || autoConnectDone || connected
                || !initialized || !p2p.ready()) return;
        // 新一轮搜索：允许再次尝试连接（上一轮 connect 可能失败或未收到结果回调）。
        autoConnectTrying = false;
        // 无论是否设置自动连接设备名，都无限搜索：直到真正连上，或
        // 10 分钟未连接由空闲退出关闭 APP。搜索次数仅用于日志展示，不再设上限。
        autoSearchCount++;
        boolean hasTarget = autoConnectTarget != null && !autoConnectTarget.trim().isEmpty();
        if (hasTarget) {
            Bus.get().log(app.getString(R.string.msg_auto_connect_retry,
                    autoSearchCount, autoConnectTarget));
        } else {
            Bus.get().log(app.getString(R.string.msg_auto_search_retry, autoSearchCount));
        }
        p2p.discover();
        scheduleAutoRetry();
    }

    private void stopAutoConnect() {
        autoConnectPending = false;
        autoConnectDone = true;
        autoConnectTrying = false;
        ui.removeCallbacks(autoRetryTask);
    }

    private static String normalizeMac(String s) {
        return s == null ? "" : s.replace(":", "").replace("-", "").trim().toLowerCase();
    }

    // ------------------------------------------------------------- P2P callbacks

    @Override
    public void onP2pState(boolean enabled) {
        Bus.get().p2pState(enabled);
        updateWifiHint();
    }

    @Override
    public void onPeers(List<WifiP2pDevice> peers) {
        Bus.get().peers(peers);
        Bus.get().log("发现 " + peers.size() + " 台设备");

        if (connected) return;
        // 目标：重连循环优先，否则用启动自动连接的目标。
        String target = null;
        boolean forReconnect = false;
        if (reconnecting && lastConnectedTarget != null && !lastConnectedTarget.isEmpty()) {
            target = lastConnectedTarget;
            forReconnect = true;
        } else if (autoConnectPending && !autoConnectDone
                && autoConnectTarget != null && !autoConnectTarget.isEmpty()) {
            // 上次发起的连接请求还在等待结果，避免同一轮重复 connect。
            if (autoConnectTrying) return;
            target = autoConnectTarget;
        }
        if (target == null) return;
        for (WifiP2pDevice d : peers) {
            String name = d.deviceName == null ? "" : d.deviceName.trim();
            String mac = d.deviceAddress == null ? "" : d.deviceAddress;
            boolean byName = !name.isEmpty() && name.equalsIgnoreCase(target);
            boolean byMac = !mac.isEmpty()
                    && normalizeMac(mac).equals(normalizeMac(target));
            if (byName || byMac) {
                if (forReconnect) {
                    // 保持重连会话：连接失败时 onActionResult 会重新安排重试。
                    reconnectConnectPending = true;
                    ui.removeCallbacks(reconnectRetryTask);
                } else {
                    // 设置了自动连接设备名：不结束自动连接循环。连接失败后由
                    // 下一轮 onAutoRetry 重新搜索并再次尝试，直到真正连上。
                    autoConnectTrying = true;
                }
                Bus.get().log(app.getString(R.string.msg_auto_connect_found,
                        name.isEmpty() ? mac : name));
                connectTo(d);
                break;
            }
        }
    }

    @Override
    public void onThisDevice(WifiP2pDevice device) {
        String n = device.deviceName == null ? "?" : device.deviceName;
        if (!n.equals(lastSystemName)) {
            lastSystemName = n;
            Bus.get().thisDevice(n);
            Bus.get().log("系统 P2P 名称：" + n);
        }
    }

    @Override
    public void onConnection(WifiP2pInfo info, WifiP2pGroup grp) {
        connected = info != null && info.groupFormed;
        Bus.get().disconnectEnabled(connected);
        String label;
        if (!connected) {
            isGo = false;
            goHost = null;
            group = null;
            label = app.getString(R.string.state_not_connected);
            Bus.get().connection(false, label);
            // Only report "disconnected" if we had actually connected before.
            // The system broadcasts "no group" on start-up / periodic queries;
            // logging those would falsely claim a connection was lost.
            if (everConnected) {
                everConnected = false;
                Bus.get().log(app.getString(R.string.msg_disconnected));
                // 仅"非手动断开"且用户开启该选项时自动重连。
                if (!manualDisconnect && settings.reconnectOnDrop()) {
                    reconnectAfterDrop();
                }
            }
            manualDisconnect = false;
            scheduleIdleExit();
        } else {
            everConnected = true;
            cancelIdleExit();
            stopAutoConnect();
            // 重连成功，收尾清理重连会话。
            reconnectConnectPending = false;
            reconnecting = false;
            ui.removeCallbacks(reconnectRetryTask);
            if (discovering) setDiscovering(false);
            if (grp != null) group = grp;
            isGo = info.isGroupOwner;
            // 记录对端目标供「断开后自动连接」使用：GO 端从 clientList 取，
            // client 端在 connectTo() 已记录；这里仅在没有目标时补充。
            if ((lastConnectedTarget == null || lastConnectedTarget.isEmpty()) && isGo) {
                p2p.requestGroupInfo(g -> {
                    if (g != null && g.getClientList() != null
                            && !g.getClientList().isEmpty()) {
                        android.net.wifi.p2p.WifiP2pDevice cli =
                                g.getClientList().iterator().next();
                        if (cli != null && cli.deviceAddress != null
                                && !cli.deviceAddress.isEmpty()) {
                            lastConnectedTarget = cli.deviceAddress;
                        }
                    }
                });
            }
            if (isGo) {
                goHost = null;
                label = app.getString(R.string.state_connected_go);
                if (!exiting) {
                    Intent i = new Intent(app, TransferService.class);
                    i.setAction(TransferService.ACTION_START_SERVER);
                    ContextCompat.startForegroundService(app, i);
                }
            } else {
                goHost = info.groupOwnerAddress != null
                        ? info.groupOwnerAddress.getHostAddress() : null;
                label = app.getString(R.string.state_connected_client,
                        goHost == null ? "?" : goHost);
            }
            Bus.get().connection(true, label);
            Bus.get().log(app.getString(R.string.msg_connected) + " · 组长=" + isGo);
            if (!pendingShared.isEmpty() && isGo && group == null) {
                p2p.requestGroupInfo(g -> {
                    if (g != null) group = g;
                    flushPendingShared();
                });
            } else {
                flushPendingShared();
            }
        }
    }

    @Override
    public void onActionResult(String tag, boolean ok, int reason) {
        switch (tag) {
            case "discover":
                if (ok) {
                    bgDiscoverRetries = 0;
                    Bus.get().log(app.getString(R.string.msg_discover_ok));
                } else {
                    onDiscoverFailed(reason);
                }
                break;
            case "stopDiscovery":
                Bus.get().log(ok ? "已停止搜索" : "停止搜索失败（设备可能已不在搜索）");
                break;
            case "connect":
                if (!ok) {
                    Bus.get().log(app.getString(R.string.msg_connect_fail, reason));
                    // 自动连接目标场景：清除进行中标志，下一轮 onAutoRetry 会继续重试。
                    if (autoConnectTrying) autoConnectTrying = false;
                    if (reconnectConnectPending) {
                        reconnectConnectPending = false;
                        // 连接失败，稍后自动重试下一轮（避免立即风暴）。
                        if (!connected && !exiting && settings.reconnectOnDrop()) {
                            ui.removeCallbacks(reconnectRetryTask);
                            ui.postDelayed(() -> {
                                if (!connected && !exiting) onReconnectTick();
                            }, RECONNECT_RETRY_MS);
                        } else {
                            stopReconnect();
                        }
                    }
                } else if (reconnectConnectPending) {
                    // 连接请求被系统接受；真正连上由 onConnection 确认并收尾。
                }
                break;
            case "setName":
                Bus.get().log(app.getString(ok ? R.string.msg_name_ok : R.string.msg_name_fail));
                break;
            case "disconnect":
                // The connection-changed broadcast drives the "disconnected"
                // message (guarded by everConnected), so don't log it twice here.
                break;
            default:
                break;
        }
    }

    private void setDiscovering(boolean on) {
        discovering = on;
        Bus.get().discovering(on);
    }

    /**
     * Handle a failed discoverPeers call. When the UI is in the background the
     * system frequently throttles / rejects P2P scans; that is neither a Wi-Fi
     * nor a permission problem, so avoid the misleading generic error. Keep the
     * "searching" state intact and quietly retry a bounded number of times.
     */
    private void onDiscoverFailed(int reason) {
        boolean background = !Bus.get().isUiForeground();
        if (background && bgDiscoverRetries < BG_DISCOVER_RETRY_MAX) {
            bgDiscoverRetries++;
            Bus.get().log(app.getString(R.string.msg_discover_bg_retry,
                    bgDiscoverRetries, BG_DISCOVER_RETRY_MAX));
            ui.removeCallbacks(bgDiscoverRetryTask);
            ui.postDelayed(bgDiscoverRetryTask, BG_DISCOVER_RETRY_MS);
            // Keep discovering == true so the button/notification stay in "searching".
            return;
        }
        if (background) {
            // Retries exhausted: report accurately, still not blaming Wi-Fi/permissions.
            Bus.get().log(app.getString(R.string.msg_discover_bg_stop));
        } else {
            Bus.get().log(app.getString(R.string.msg_discover_fail, reason));
        }
        setDiscovering(false);
    }

    private void onBgDiscoverRetry() {
        if (!discovering || autoConnectDone || connected
                || !initialized || !p2p.ready()) return;
        Bus.get().log(app.getString(R.string.msg_discover_bg_retry_now));
        p2p.discover();
    }

    // ------------------------------------------------------------- Wi-Fi / root

    private boolean isWifiOn() {
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        return wm != null && wm.isWifiEnabled();
    }

    private void updateWifiHint() {
        boolean wifiOn = isWifiOn();
        Bus.get().wifiHint(!wifiOn);
        if (!wifiOn) {
            Bus.get().log(app.getString(R.string.msg_wifi_off));
            if (settings.rootWifi()) triggerRootWifi(true);
        }
    }

    /** UI button "enable wifi" -> root or system settings. */
    public void onEnableWifiClicked() {
        if (settings.rootWifi()) {
            triggerRootWifi(false);
        } else {
            Intent i = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        }
    }

    private void triggerRootWifi(boolean auto) {
        long now = SystemClock.elapsedRealtime();
        if (auto && (rootPending || now - lastRootMs < 8000)) return;
        if (rootPending) return;
        lastRootMs = now;
        rootPending = true;
        Bus.get().log(app.getString(auto
                ? R.string.msg_root_wifi_auto : R.string.msg_root_wifi_manual));
        RootWifi.enableAsync((ok, detail) -> {
            rootPending = false;
            Bus.get().log(app.getString(ok
                    ? R.string.msg_root_wifi_ok : R.string.msg_root_wifi_fail)
                    + " · " + detail);
            ui.postDelayed(this::updateWifiHint, 1500);
        });
    }

    // ------------------------------------------------------------- idle watchdog

    private void scheduleIdleExit() {
        if (exitStarted || exitDone) return;
        ui.removeCallbacks(idleExitTask);
        ui.postDelayed(idleExitTask, IDLE_TIMEOUT_MS);
    }

    private void cancelIdleExit() {
        ui.removeCallbacks(idleExitTask);
    }

    private void onIdleTimeout() {
        if (exitStarted || exitDone) return;
        if (connected) {
            Bus.get().log(app.getString(R.string.msg_idle_cancel));
            return;
        }
        // Re-check real P2P state before quitting, avoid killing a live group.
        if (initialized && p2p.ready()) p2p.requestInfo();
        ui.postDelayed(idleConfirmTask, 1500);
    }

    private void confirmIdleExit() {
        if (exitStarted || exitDone) return;
        if (connected) {
            Bus.get().log(app.getString(R.string.msg_idle_cancel));
            return;
        }
        // 与菜单退出走同一套收尾：按规则按需关闭 Wi-Fi 后真正退出。
        beginExit(app.getString(R.string.msg_idle_exit));
    }

    /**
     * 所有退出路径的统一收尾：先按规则决定是否关闭 Wi-Fi，再真正结束进程。
     * 规则：仅当 Wi-Fi 开着、且当前未关联任何可用 Wi-Fi 时才尝试关闭；
     * 能否真正关闭取决于设置中的 Root 开关与设备是否具备 Root 权限。
     *
     * @param reasonLog 退出原因日志（超时退出 / 用户菜单退出）
     */
    private void beginExit(String reasonLog) {
        if (exitStarted || exitDone) return;
        exitStarted = true;
        ui.removeCallbacks(idleExitTask);
        ui.removeCallbacks(idleConfirmTask);
        ui.removeCallbacks(autoRetryTask);
        Bus.get().log(reasonLog);

        boolean wifiOn = isWifiOn();
        if (wifiOn && !isWifiAssociated()) {
            Bus.get().log(app.getString(R.string.msg_wifi_auto_off_start));
            // 兜底：即使关闭 Wi-Fi 的异步回调迟迟不返回，也保证退出。
            ui.postDelayed(exitTask, 2500);
            RootWifi.disableAsync(settings.rootWifi(), (ok, detail) -> {
                Bus.get().log(app.getString(ok
                        ? R.string.msg_wifi_auto_off_ok : R.string.msg_wifi_auto_off_fail)
                        + " · " + detail);
                performExit();
            });
        } else {
            if (wifiOn) Bus.get().log(app.getString(R.string.msg_wifi_keep_on));
            performExit();
        }
    }

    private boolean isWifiAssociated() {
        ConnectivityManager cm = (ConnectivityManager)
                app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null
                        && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return true;
                }
            }
        }
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            WifiInfo wi = wm.getConnectionInfo();
            if (wi != null && wi.getNetworkId() != -1) {
                String ssid = wi.getSSID();
                if (ssid != null && !ssid.isEmpty()
                        && !ssid.contains("<unknown ssid>")) return true;
            }
        }
        return false;
    }

    /** Explicit user "Exit app" from the menu: clean up and close everything. */
    public void exitApp() {
        // 与超时退出同一规则：Wi-Fi 开着且未连普通 Wi-Fi 时按需关闭。
        beginExit(app.getString(R.string.msg_exit_app));
    }

    private void performExit() {
        if (exitDone) return;
        exitDone = true;
        shutdown();
    }

    private void shutdown() {
        if (exiting) return;
        exiting = true;
        stopReconnect();
        reconnectConnectPending = false;
        ui.removeCallbacks(exitTask);
        ui.removeCallbacks(idleExitTask);
        ui.removeCallbacks(idleConfirmTask);
        ui.removeCallbacks(autoRetryTask);
        stopAutoConnect();
        try {
            p2p.unregister();
        } catch (Exception ignored) {
        }
        // Detach the notification observer FIRST so any log/state event still
        // queued on the main thread can no longer republish the notification.
        Bus.get().setNotifObserver(null);
        // Ask any attached UI to finish itself.
        Bus.get().exitRequest();
        // Stop the foreground service and actively remove its notification.
        app.stopService(new Intent(app, TransferService.class));
        TransferService.cancelNotification(app);
        // Give finish()/stopService a moment to run on the main thread, then
        // terminate the process so the singleton engine and its thread pool die
        // with it — otherwise a cached shell / broadcast could revive things.
        ui.postDelayed(() -> {
            TransferService.cancelNotification(app);
            try {
                android.os.Process.killProcess(android.os.Process.myPid());
            } catch (Throwable ignored) {
            }
            System.exit(0);
        }, 350);
    }
}

package com.myapp.mywifidirect.transfer;

import android.net.wifi.p2p.WifiP2pDevice;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Same-process event bus between the background engine / foreground service
 * and the UI. All callbacks are delivered on the main thread except
 * {@link ConfirmRequest#await} which intentionally blocks the transfer thread.
 * The log buffer lives here so the UI can restore the full log after a
 * configuration change or after the Activity was recreated.
 *
 * It also keeps a {@link NotifState} snapshot so the foreground service can
 * render the current status (searching / connected / sending / receiving)
 * in the notification without depending on the Activity lifecycle.
 */
public final class Bus {

    public interface Listener {
        void onLog(String line);
        void onProgress(String label, int pct);
        void onServer(boolean running, String addr);
        void onSaved(String display);
        // P2P state pushed by the background engine
        void onP2pState(boolean enabled);
        void onThisDevice(String name);
        void onPeers(List<WifiP2pDevice> peers);
        void onConnection(boolean connected, String connLabel);
        void onDiscovering(boolean on);
        void onWifiHint(boolean showEnableButton);
        void onDisconnectEnabled(boolean enabled);
        void onExitRequest();
    }

    public interface ConfirmHandler {
        void onConfirm(ConfirmRequest req);
    }

    /** Immutable-ish snapshot of the state shown in the ongoing notification. */
    public static final class NotifState {
        public boolean p2pEnabled;
        public boolean discovering;
        public boolean connected;
        public String connLabel = "";
        /** Non-null while a file transfer is in progress, e.g. "发送 a.jpg". */
        public String transferLabel;
        public int transferPct;
        /** File name of the most recently finished transfer. */
        public String lastDone;
    }

    /** Implemented by the foreground service to refresh the notification. */
    public interface NotifObserver {
        void onNotifState(NotifState state);
    }

    /** One pending "accept this incoming file?" decision. */
    public static final class ConfirmRequest {
        public final String name;
        public final String from;
        public final long size;
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean accepted;

        public ConfirmRequest(String name, String from, long size) {
            this.name = name;
            this.from = from;
            this.size = size;
        }

        public boolean await(long seconds) {
            try {
                return latch.await(seconds, TimeUnit.SECONDS) && accepted;
            } catch (InterruptedException e) {
                return false;
            }
        }

        public void resolve(boolean ok) {
            accepted = ok;
            latch.countDown();
        }
    }

    private static final Bus INSTANCE = new Bus();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    private volatile ConfirmHandler confirmHandler;
    private volatile NotifObserver notifObserver;
    private volatile boolean uiForeground;
    private final StringBuilder logBuffer = new StringBuilder();

    // All reads/writes of notifState happen on the main thread (inside the
    // main.post runnables below), so no extra synchronization is needed.
    private final NotifState notifState = new NotifState();

    private Bus() {
    }

    public static Bus get() {
        return INSTANCE;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setConfirmHandler(ConfirmHandler h) {
        confirmHandler = h;
    }

    /** Whether an Activity of this app is currently in the foreground. */
    public void setUiForeground(boolean foreground) {
        uiForeground = foreground;
    }

    public boolean isUiForeground() {
        return uiForeground;
    }

    /** Register the notification observer; immediately pushed the current state. */
    public void setNotifObserver(NotifObserver o) {
        notifObserver = o;
        main.post(this::dispatchNotif);
    }

    /** Full formatted log (main thread). */
    public String getLog() {
        return logBuffer.toString();
    }

    /**
     * Most recent single log line without its trailing newline (main thread).
     * Used for the notification subtitle, which should only show the latest entry.
     */
    public String getLastLogLine() {
        String all = logBuffer.toString();
        if (all.isEmpty()) return "";
        int end = all.length();
        if (all.charAt(end - 1) == '\n') end--;
        if (end <= 0) return "";
        int start = all.lastIndexOf('\n', end - 1);
        return all.substring(start + 1, end);
    }

    private static String formatLine(String msg) {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date()) + "  " + msg + "\n";
    }

    private NotifState snapshot() {
        NotifState s = new NotifState();
        s.p2pEnabled = notifState.p2pEnabled;
        s.discovering = notifState.discovering;
        s.connected = notifState.connected;
        s.connLabel = notifState.connLabel;
        s.transferLabel = notifState.transferLabel;
        s.transferPct = notifState.transferPct;
        s.lastDone = notifState.lastDone;
        return s;
    }

    /** Called on the main thread only. */
    private void dispatchNotif() {
        NotifObserver o = notifObserver;
        if (o != null) o.onNotifState(snapshot());
    }

    public void log(String msg) {
        final String line = formatLine(msg);
        main.post(() -> {
            logBuffer.append(line);
            if (logBuffer.length() > 9000) {
                logBuffer.delete(0, logBuffer.length() - 7000);
            }
            Listener l = listener;
            if (l != null) l.onLog(line);
            // Keep the notification subtitle (full log) in sync.
            dispatchNotif();
        });
    }

    public void progress(String label, int pct) {
        main.post(() -> {
            if (pct >= 100) {
                notifState.transferLabel = null;
                notifState.lastDone = label;
            } else {
                notifState.transferLabel = label;
                notifState.transferPct = pct;
            }
            Listener l = listener;
            if (l != null) l.onProgress(label, pct);
            dispatchNotif();
        });
    }

    /** 传输完成：清掉进行中的通知状态，并通知 UI 把进度条复位。 */
    public void progressDone(String label) {
        main.post(() -> {
            notifState.transferLabel = null;
            notifState.transferPct = 0;
            notifState.lastDone = label;
            Listener l = listener;
            if (l != null) l.onProgress(label, -1); // -1 = 复位
            dispatchNotif();
        });
    }

    public void server(boolean running, String addr) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onServer(running, addr);
        });
    }

    public void saved(String display) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onSaved(display);
        });
    }

    public void p2pState(boolean enabled) {
        main.post(() -> {
            notifState.p2pEnabled = enabled;
            Listener l = listener;
            if (l != null) l.onP2pState(enabled);
            dispatchNotif();
        });
    }

    public void thisDevice(String name) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onThisDevice(name);
        });
    }

    public void peers(List<WifiP2pDevice> peers) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onPeers(peers);
        });
    }

    public void connection(boolean connected, String label) {
        main.post(() -> {
            notifState.connected = connected;
            notifState.connLabel = label == null ? "" : label;
            Listener l = listener;
            if (l != null) l.onConnection(connected, label);
            dispatchNotif();
        });
    }

    public void discovering(boolean on) {
        main.post(() -> {
            notifState.discovering = on;
            Listener l = listener;
            if (l != null) l.onDiscovering(on);
            dispatchNotif();
        });
    }

    public void wifiHint(boolean showEnableButton) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onWifiHint(showEnableButton);
        });
    }

    public void disconnectEnabled(boolean enabled) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onDisconnectEnabled(enabled);
        });
    }

    public void exitRequest() {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onExitRequest();
        });
    }

    /** Returns false when no UI is attached to answer the question. */
    public boolean postConfirm(ConfirmRequest req) {
        if (confirmHandler == null) return false;
        main.post(() -> {
            ConfirmHandler h = confirmHandler;
            if (h != null) h.onConfirm(req);
            else req.resolve(false);
        });
        return true;
    }

    /** UI attached? Decides whether an incoming file can be confirmed in background. */
    public boolean hasConfirmHandler() {
        return confirmHandler != null;
    }
}

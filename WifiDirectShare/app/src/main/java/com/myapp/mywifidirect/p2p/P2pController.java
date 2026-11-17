package com.myapp.mywifidirect.p2p;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/** Thin wrapper around WifiP2pManager: lifecycle, discovery, connect, info. */
public final class P2pController {

    public interface Listener {
        void onP2pState(boolean enabled);
        void onPeers(List<WifiP2pDevice> peers);
        void onConnection(WifiP2pInfo info, WifiP2pGroup group);
        void onThisDevice(WifiP2pDevice device);
        void onActionResult(String tag, boolean ok, int reason);
    }

    private final Context ctx;
    private final Listener listener;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private boolean registered;

    public P2pController(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
    }

    /** Must be called AFTER the location / nearby-wifi permission is granted. */
    public boolean init() {
        manager = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) return false;
        channel = manager.initialize(ctx, Looper.getMainLooper(), null);
        return channel != null;
    }

    public boolean ready() {
        return manager != null && channel != null;
    }

    public void register() {
        if (registered || receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String action = intent.getAction();
                if (action == null || !ready()) return;
                switch (action) {
                    case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION: {
                        int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                        listener.onP2pState(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
                        break;
                    }
                    case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION: {
                        manager.requestPeers(channel, (WifiP2pDeviceList peers) -> {
                            List<WifiP2pDevice> list =
                                    new ArrayList<>(peers.getDeviceList());
                            listener.onPeers(list);
                        });
                        break;
                    }
                    case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION: {
                        NetworkInfo net = intent.getParcelableExtra(
                                WifiP2pManager.EXTRA_NETWORK_INFO);
                        WifiP2pGroup group = intent.getParcelableExtra(
                                WifiP2pManager.EXTRA_WIFI_P2P_GROUP);
                        if (net != null && net.isConnected()) {
                            manager.requestConnectionInfo(channel, info ->
                                    listener.onConnection(info, group));
                        } else {
                            listener.onConnection(null, null);
                        }
                        break;
                    }
                    case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION: {
                        WifiP2pDevice dev = intent.getParcelableExtra(
                                WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                        if (dev != null) listener.onThisDevice(dev);
                        break;
                    }
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            // These are system broadcasts; EXPORTED is required to receive them.
            ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, f);
        }
        registered = true;
    }

    public void unregister() {
        if (registered && receiver != null) {
            try {
                ctx.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
        registered = false;
        receiver = null;
    }

    public void discover() {
        if (!ready()) return;
        manager.discoverPeers(channel, action("discover"));
    }

    public void stopDiscovery() {
        if (!ready()) return;
        manager.stopPeerDiscovery(channel, action("stopDiscovery"));
    }

    public void connect(String deviceAddress, int groupOwnerIntent) {
        if (!ready()) return;
        WifiP2pConfig cfg = new WifiP2pConfig();
        cfg.deviceAddress = deviceAddress;
        cfg.groupOwnerIntent = Math.max(0, Math.min(15, groupOwnerIntent));
        manager.connect(channel, cfg, action("connect"));
    }

    public void disconnect() {
        if (!ready()) return;
        manager.removeGroup(channel, action("disconnect"));
    }

    public void requestInfo() {
        if (!ready()) return;
        manager.requestConnectionInfo(channel,
                info -> listener.onConnection(info, null));
    }

    /** Ask the system for the current peer list (used on UI re-attach). */
    public void requestPeers() {
        if (!ready()) return;
        manager.requestPeers(channel, peers -> {
            if (peers != null) listener.onPeers(new ArrayList<>(peers.getDeviceList()));
        });
    }

    /** 读取当前 P2P 组；GO 端需要 clientList 才能从 ARP 表解析对端 IP。 */
    public void requestGroupInfo(WifiP2pManager.GroupInfoListener l) {
        if (!ready()) return;
        manager.requestGroupInfo(channel, l);
    }

    /**
     * Try to sync the P2P device name. setDeviceName is a hidden platform API
     * (and Android 10+ refuses it for third-party apps), so we use reflection
     * and fall back gracefully — the local alias still drives display and the
     * sender identity in the transfer protocol.
     */
    public void setDeviceName(String name) {
        if (!ready()) {
            listener.onActionResult("setName", false, -1);
            return;
        }
        try {
            java.lang.reflect.Method m = WifiP2pManager.class.getMethod(
                    "setDeviceName",
                    WifiP2pManager.Channel.class,
                    String.class,
                    WifiP2pManager.ActionListener.class);
            m.invoke(manager, channel, name, action("setName"));
        } catch (Throwable t) {
            listener.onActionResult("setName", false, -1);
        }
    }

    private WifiP2pManager.ActionListener action(String tag) {
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                listener.onActionResult(tag, true, 0);
            }

            @Override
            public void onFailure(int reason) {
                listener.onActionResult(tag, false, reason);
            }
        };
    }
}

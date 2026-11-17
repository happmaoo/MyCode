package com.myapp.mywifidirect.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import java.net.URLDecoder;

/** Persisted preferences for the Wi-Fi Direct file transfer app. */
public final class Settings {

    private static final String PREF = "wds";
    private final Context ctx;
    private final SharedPreferences p;

    public Settings(Context c) {
        ctx = c.getApplicationContext();
        p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String defaultName() {
        String m = Build.MODEL;
        return (m == null || m.trim().isEmpty()) ? "WDS-Device" : m.trim();
    }

    public String deviceName() {
        return p.getString("device_name", defaultName());
    }

    public void setDeviceName(String v) {
        String s = v == null ? "" : v.trim();
        p.edit().putString("device_name", s.isEmpty() ? defaultName() : s).apply();
    }

    /** 自动连接的设备名（或 MAC）；留空 = 关闭自动连接。 */
    public String autoConnectName() {
        String s = p.getString("auto_connect_name", "");
        return s == null ? "" : s.trim();
    }

    public void setAutoConnectName(String v) {
        String s = v == null ? "" : v.trim();
        p.edit().putString("auto_connect_name", s).apply();
    }

    public boolean autoAccept() {
        return p.getBoolean("auto_accept", true);
    }

    public void setAutoAccept(boolean v) {
        p.edit().putBoolean("auto_accept", v).apply();
    }

    /** Prefer becoming Group Owner (the receive server) during GO negotiation. */
    public boolean preferGo() {
        return p.getBoolean("prefer_go", false);
    }

    public void setPreferGo(boolean v) {
        p.edit().putBoolean("prefer_go", v).apply();
    }

    /** Use root (libsu / svc wifi enable) to switch on system Wi-Fi automatically. */
    public boolean rootWifi() {
        return p.getBoolean("root_wifi", false);
    }

    public void setRootWifi(boolean v) {
        p.edit().putBoolean("root_wifi", v).apply();
    }

    /** Keep a PARTIAL_WAKE_LOCK while the app (foreground service) is running. */
    public boolean wakeLock() {
        return p.getBoolean("wake_lock", false);
    }

    public void setWakeLock(boolean v) {
        p.edit().putBoolean("wake_lock", v).apply();
    }

    /** Auto-reconnect after an unexpected (non-manual) disconnect. */
    public boolean reconnectOnDrop() {
        return p.getBoolean("reconnect_on_drop", false);
    }

    public void setReconnectOnDrop(boolean v) {
        p.edit().putBoolean("reconnect_on_drop", v).apply();
    }

    /** Persisted SAF document-tree URI, or null for the app-private folder. */
    public Uri receiveTree() {
        String s = p.getString("tree_uri", null);
        if (s == null) return null;
        try {
            return Uri.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void setReceiveTree(Uri u) {
        if (u == null) p.edit().remove("tree_uri").apply();
        else p.edit().putString("tree_uri", u.toString()).apply();
    }

    /** Human-readable label for the current receive folder. */
    public String folderLabel() {
        Uri u = receiveTree();
        if (u == null) return ctx.getString(com.myapp.mywifidirect.R.string.set_folder_default);
        String seg = u.getLastPathSegment();
        if (seg != null) {
            try {
                seg = URLDecoder.decode(seg, "UTF-8");
            } catch (Exception ignored) {
            }
            int colon = seg.lastIndexOf(':');
            if (colon >= 0 && colon < seg.length() - 1) seg = seg.substring(colon + 1);
        }
        if (seg == null || seg.isEmpty()) seg = u.toString();
        return ctx.getString(com.myapp.mywifidirect.R.string.set_folder_custom, seg);
    }
}

package com.myapp.mqtt.base;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务：常驻通知 + 持有 MQTT 连接，进程被切后台/锁屏也不会被回收。
 * 两端包共用同一个类，靠 P.role() 区分行为（执行端的 shell 执行在 MqttCore → Exec 里）。
 */
public class MqttService extends Service implements MqttCore.Listener {

    public static final String ACTION_CONNECT = "com.myapp.mqtt.action.CONNECT";
    public static final String ACTION_DISCONNECT = "com.myapp.mqtt.action.DISCONNECT";
    public static final String ACTION_PUBLISH = "com.myapp.mqtt.action.PUBLISH";
    public static final String EXTRA_TOPIC = "topic";
    public static final String EXTRA_PAYLOAD = "payload";

    public static final String CHANNEL_ID = "mqtt";
    private static final int NOTIF_ID = 1101;

    public static volatile MqttService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent == null) ? null : intent.getAction();

        if (ACTION_DISCONNECT.equals(action)) {
            MqttCore.get().disconnect();
            MqttCore.get().removeListener(this);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // 其他情况都保证是前台服务
        startForeground(NOTIF_ID, buildNotif(MqttCore.get().stateText()));

        if (ACTION_CONNECT.equals(action)) {
            MqttCore.get().addListener(this);
            if (!MqttCore.get().isConnected()) MqttCore.get().connect(this);
        } else if (ACTION_PUBLISH.equals(action)) {
            MqttCore.get().publish(intent.getStringExtra(EXTRA_TOPIC),
                    intent.getStringExtra(EXTRA_PAYLOAD), P.qos(this));
        } else {
            MqttCore.get().addListener(this);
        }
        startBattery();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (battRx != null) {
            try { unregisterReceiver(battRx); } catch (Exception ignore) { }
            battRx = null;
        }
        holdConnWake(false);
        MqttCore.get().removeListener(this);
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- MqttCore.Listener ----

    @Override
    public void onState(String text, boolean connected) {
        holdConnWake(connected && P.wakeLock(this));   // 连着就一直持锁，断了立刻放
        if (connected && !loggedConn && P.isExecutor(this)) {
            loggedConn = true;
            sendBattery(true);
        } else if (!connected) {
            loggedConn = false;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotif(text));
    }

    @Override
    public void onPeer(boolean online, String id) {
        // 控制端刚上线：立刻把当前电量补发一次，不用等电量变化
        if (online && P.isExecutor(this) && MqttCore.get().isConnected()) {
            lastLevel = -1;
            sendBattery(true);
        }
    }

    @Override
    public void onMessage(String topic, String payload) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            String shortMsg = payload.length() > 60 ? payload.substring(0, 60) + "…" : payload;
            nm.notify(NOTIF_ID, buildNotif(MqttCore.get().stateText() + "  ← " + topic + ": " + shortMsg));
        }
    }

    @Override
    public void onLog(String line) {
    }

    // ---- 通知 ----

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "MQTT 连接",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotif(String text) {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = open == null ? null : PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_mqtt)
                .setColor(0xFF12968A)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (pi != null) b.setContentIntent(pi);
        return b.build();
    }

    // ===== 电池：执行端把电量变化推给控制端 =====

    private BroadcastReceiver battRx;
    private boolean loggedConn;
    private int lastLevel = -1;
    private boolean lastCharging;

    private void startBattery() {
        if (battRx != null) return;
        battRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent it) {
                if (!P.isExecutor(c) || !MqttCore.get().isConnected()) return;
                if (!MqttCore.get().isPeerOnline()) return;   // 控制端不在线就不推，省流量
                int level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                if (level < 0 || scale <= 0) return;
                int pct = (int) (level * 100.0 / scale);
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                if (pct == lastLevel && charging == lastCharging) return;   // 只在真变化时发
                lastLevel = pct;
                lastCharging = charging;
                pushBattery(pct, charging);
            }
        };
        registerReceiver(battRx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    /** 读一次当前电量并发出去（连接成功时调用，保证控制端马上有值） */
    private void sendBattery(boolean force) {
        if (!MqttCore.get().isPeerOnline()) return;
        Intent it = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (it == null) return;
        int level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (level < 0 || scale <= 0) return;
        int pct = (int) (level * 100.0 / scale);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        if (!force && pct == lastLevel && charging == lastCharging) return;
        lastLevel = pct;
        lastCharging = charging;
        pushBattery(pct, charging);
    }

    private void pushBattery(int pct, boolean charging) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            org.json.JSONObject b = new org.json.JSONObject();
            b.put("level", pct);
            b.put("charging", charging);
            b.put("ts", System.currentTimeMillis());
            o.put("battery", b);
            MqttCore.get().publish(P.pubTopic(this), o.toString(), 0);
            LogFile.write("[电池] " + pct + "% " + (charging ? "充电中" : "放电"));
        } catch (Exception ignore) {
        }
    }

    // ===== 连接期间保持 wakelock（防 Doze 时心跳停摆导致掉线）=====

    private PowerManager.WakeLock connWl;

    private void holdConnWake(boolean hold) {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            if (hold) {
                if (connWl == null) {
                    connWl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyMQTT:conn");
                    connWl.setReferenceCounted(false);
                }
                if (!connWl.isHeld()) connWl.acquire();      // 不设超时：连接多久就持多久
                LogFile.write("[wakelock] 连接期间持锁");
            } else if (connWl != null && connWl.isHeld()) {
                connWl.release();
                LogFile.write("[wakelock] 已释放");
            }
        } catch (Exception ignore) {
        }
    }
}

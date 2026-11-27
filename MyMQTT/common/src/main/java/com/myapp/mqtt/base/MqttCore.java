package com.myapp.mqtt.base;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttClientState;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.exceptions.MqttClientStateException;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.TrustManagerFactory;

/**
 * MQTT 连接的唯一持有者：连接 / 断开 / 发布 / 订阅 / 断线自动重连。
 * 状态以 client.getState() 为准，避免「显示已连接但发布抛 MqttClientStateException」。
 */
public final class MqttCore {

    /** 在线状态主题：控制端 / 执行端各自 retained 发 online/offline，异常断开由 broker 的 will 兜底 */
    public static final String PRESENCE_CTL = "control/presence";
    public static final String PRESENCE_EXE = "executor/presence";

    public interface Listener {
        void onState(String text, boolean connected);

        void onMessage(String topic, String payload);

        void onLog(String line);

        /** 对端上线/离线（执行端看到的是控制端，反之亦然） */
        default void onPeer(boolean online, String id) {
        }
    }

    private static volatile MqttCore inst;

    /** 对端（控制端 / 执行端）是否在线，由 presence 更新 */
    private volatile boolean peerOnline;

    public static MqttCore get() {
        if (inst == null) {
            synchronized (MqttCore.class) {
                if (inst == null) inst = new MqttCore();
            }
        }
        return inst;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile Mqtt3AsyncClient client;
    private volatile boolean wantConnected;
    private volatile Thread watchdog;
    private volatile boolean connecting;
    private volatile boolean retryPending;
    private volatile String lastState = "未连接";
    private volatile int retryCount;
    private volatile int qos = 0;
    private volatile Context appCtx;

    public volatile int rxCount;
    public volatile int txCount;
    public volatile long lastRxAt;
    public volatile long lastTxAt;

    private MqttCore() {
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** 真实状态：直接问 HiveMQ 客户端 */
    public boolean isConnected() {
        Mqtt3AsyncClient c = client;
        return c != null && c.getState().isConnected();
    }

    public boolean isConnecting() {
        Mqtt3AsyncClient c = client;
        return connecting || (c != null && c.getState() == MqttClientState.CONNECTING);
    }

    public String stateText() {
        String s = lastState;
        if (s.startsWith("已连接") && !isConnected()) return "已断开（连接已丢失）";
        return s;
    }

    private void state(final String text, final boolean conn) {
        LogFile.write("[状态] " + text);
        lastState = text;
        for (final Listener l : listeners) {
            main.post(() -> l.onState(text, conn));
        }
    }

    private void log(final String line) {
        LogFile.write(line);
        for (final Listener l : listeners) {
            main.post(() -> l.onLog(line));
        }
    }

    private void deliver(final String topic, final String payload) {
        LogFile.write("← " + topic + " " + payload);
        rxCount++;
        lastRxAt = System.currentTimeMillis();
        for (final Listener l : listeners) {
            main.post(() -> l.onMessage(topic, payload));
        }
    }

    private static MqttQos toQos(int q) {
        if (q == 2) return MqttQos.EXACTLY_ONCE;
        if (q == 1) return MqttQos.AT_LEAST_ONCE;
        return MqttQos.AT_MOST_ONCE;
    }

    /** 建立连接（异步；成功后自动订阅 P.subTopics 里的全部主题） */
    public void connect(final Context ctx) {
        appCtx = ctx.getApplicationContext();
        wantConnected = true;
        retryCount = 0;
        retryPending = false;
        connectOnce(appCtx);
    }

    private void connectOnce(final Context app) {
        if (connecting) return;
        if (isConnected()) return;
        connecting = true;
        retryPending = false;
        final String host = P.host(app);
        final int port = P.port(app);
        final boolean tls = P.tls(app);
        final String crt = P.crtPath(app);
        final String user = P.user(app);
        final String pass = P.pass(app);
        final int ka = Math.max(5, P.keepAlive(app));
        final int timeout = P.connectTimeout(app);
        qos = P.qos(app);
        final String clientId = P.clientId(app);

        state("连接中 " + host + ":" + port + (tls ? " (TLS) " : " ") + "…", false);

        new Thread(() -> {
            Mqtt3AsyncClient c = null;
            try {
                Mqtt3ClientBuilder b = MqttClient.builder()
                        .useMqttVersion3()
                        .identifier(clientId)
                        .serverHost(host)
                        .serverPort(port);
                if (tls) {
                    if (!crt.isEmpty() && new File(crt).canRead()) {
                        TrustManagerFactory tmf = trustFactory(crt);
                        b = (Mqtt3ClientBuilder) b.sslConfig().trustManagerFactory(tmf).applySslConfig();
                    } else {
                        b = (Mqtt3ClientBuilder) b.sslConfig().applySslConfig();
                    }
                }
                // 遗嘱：异常断开（进程被杀、断网超过 keepalive）时由 broker 替我们发 offline
                final boolean isExecRole = P.isExecutor(app);
                final String selfPresence = isExecRole ? PRESENCE_EXE : PRESENCE_CTL;
                b = b.willPublish()
                        .topic(selfPresence)
                        .qos(MqttQos.AT_MOST_ONCE)
                        .retain(true)
                        .payload(presenceJson(clientId, false).getBytes("UTF-8"))
                        .applyWillPublish();
                c = b.buildAsync();
                client = c;

                boolean useAuth = !user.isEmpty();
                log("[连接] " + host + ":" + port + " tls=" + tls + " auth=" + useAuth
                        + " keepalive=" + ka + "s timeout=" + (timeout > 0 ? timeout + "s" : "off"));
                CompletableFuture<Mqtt3ConnAck> f;
                if (useAuth) {
                    f = c.connectWith().keepAlive(ka).cleanSession(true)
                            .simpleAuth().username(user).password(pass.getBytes("UTF-8"))
                            .applySimpleAuth().send();
                } else {
                    f = c.connectWith().keepAlive(ka).cleanSession(true).send();
                }

                Mqtt3ConnAck ack = timeout > 0 ? f.get(timeout, TimeUnit.SECONDS) : f.get();
                if (ack == null) throw new IllegalStateException("没有收到 CONNACK");

                connecting = false;
                retryCount = 0;
                if (!wantConnected) {
                    failQuietly(c);
                    return;
                }
                state("已连接 " + host + ":" + port, true);
                log("[已连接] " + host + ":" + port + (tls ? " (TLS)" : ""));
                subscribeAll(app);
                startWatchdog(app);
                // 自己上线：retained，让对端一订阅就能看到
                publishRetained(selfPresence, presenceJson(clientId, true));
                // 执行端：连接成功后自动跑一次设置里的启动命令
                final String boot = P.isExecutor(app) ? P.startupCmd(app) : "";
                if (!boot.isEmpty()) {
                    log("[启动命令] " + boot.replace("\n", " ⏎ "));
                    Exec.handleCommand(this, app, "startup", boot);
                }
            } catch (java.util.concurrent.TimeoutException te) {
                failQuietly(c);
                connecting = false;
                if (!wantConnected) return;
                state("连接超时（" + timeout + "s）", false);
                log("[连接失败] 连接超时（" + timeout + "s）");
                scheduleRetry(app, "timeout");
            } catch (Exception e) {
                failQuietly(c);
                connecting = false;
                if (!wantConnected) return;
                Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                state("连接失败：" + brief(cause), false);
                log("[连接失败] " + brief(cause));
                scheduleRetry(app, "fail");
            }
        }, "mqtt-connect").start();
    }

    private static String brief(Throwable e) {
        String m = e.getClass().getSimpleName();
        if (e.getMessage() != null) {
            String s = e.getMessage();
            if (s.length() > 120) s = s.substring(0, 120) + "…";
            m += ": " + s;
        }
        return m;
    }

    private static TrustManagerFactory trustFactory(String crtPath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate ca;
        FileInputStream in = new FileInputStream(crtPath);
        try {
            ca = cf.generateCertificate(in);
        } finally {
            try {
                in.close();
            } catch (Exception ignore) {
            }
        }
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("ca", ca);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return tmf;
    }

    private void failQuietly(Mqtt3AsyncClient c) {
        if (c == null) return;
        try {
            c.disconnect().get(2, TimeUnit.SECONDS);
        } catch (Exception ignore) {
        }
    }

    /** 订阅全部主题；重连成功后也会走这里，保证订阅恢复 */
    private void subscribeAll(Context app) {
        final Mqtt3AsyncClient c = client;
        if (c == null) return;
        List<String> topics = new java.util.ArrayList<>(P.subTopics(app));
        String peerPresence = P.isExecutor(app) ? PRESENCE_CTL : PRESENCE_EXE;
        if (!topics.contains(peerPresence)) topics.add(peerPresence);
        if (topics.isEmpty()) log("[订阅] 主题列表为空");
        for (final String topic : topics) {
            try {
                c.subscribeWith()
                        .topicFilter(topic)
                        .qos(toQos(qos))
                        .callback(this::onPublish)
                        .send()
                        .whenComplete((ack, ex) -> {
                            if (ex != null) log("[订阅失败] " + topic + " → " + brief(ex));
                            else log("[订阅成功] " + topic + " qos=" + qos);
                        });
            } catch (Exception e) {
                log("[订阅异常] " + topic + " → " + brief(e));
            }
        }
    }

    private void onPublish(Mqtt3Publish pub) {
        String topic;
        String payload;
        try {
            topic = pub.getTopic().toString();
            byte[] raw = pub.getPayloadAsBytes();
            boolean gz = Compress.isGzip(raw);         // gzip 魔数 1F 8B：文本消息不会撞上
            byte[] plain = Compress.unpack(raw);
            if (gz) log("[压缩] ← " + topic + " " + Fmt.size(raw.length) + " → " + Fmt.size(plain.length));
            payload = new String(plain, "UTF-8");
        } catch (Exception e) {
            return;
        }
        if (PRESENCE_CTL.equals(topic) || PRESENCE_EXE.equals(topic)) {
            // 对端在线状态：不进日志区当普通消息，单独回调
            rxCount++;
            lastRxAt = System.currentTimeMillis();
            boolean on0 = payload.contains("\"online\":true");
            String id0 = "";
            try {
                JSONObject o = new JSONObject(payload);
                on0 = o.optBoolean("online", on0);
                id0 = o.optString("id", "");
            } catch (Exception ignore) {
            }
            final boolean online = on0;
            final String id = id0;
            LogFile.write("[在线状态] " + topic + " " + payload);
            peerOnline = online;
            for (final Listener l : listeners) {
                main.post(() -> l.onPeer(online, id));
            }
            return;
        }
        deliver(topic, payload);

        // 执行端：整条 payload 就是 shell 命令，不解析 JSON
        final Context app = appCtx;
        if (app != null && P.isExecutor(app)) {
            Exec.handleCommand(this, app, topic, payload);
        }
    }

    /** 连接丢了：状态改回未连接，并按设置自动重连 */
    private void markLost(String why) {
        boolean wasConn = lastState.startsWith("已连接");
        if (!wasConn && retryPending) return;
        lastState = "已断开：" + why;
        for (final Listener l : listeners) {
            main.post(() -> l.onState(lastState, false));
        }
        log("[断线] " + why);
        final Context app = appCtx;
        if (app == null) return;
        if (!P.autoReconnect(app)) {
            state("已断开（未开启自动重连）", false);
            return;
        }
        state("断线，" + why + "，准备重连…", false);
        retryCount = 0;
        scheduleRetry(app, why);
    }

    /** 每 3 秒巡检；断了触发重连，重连成功后继续巡检 */
    private void startWatchdog(final Context app) {
        Thread w = watchdog;
        if (w != null && w.isAlive()) return;
        watchdog = new Thread(() -> {
            while (wantConnected) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
                if (!wantConnected) return;
                Mqtt3AsyncClient c = client;
                if (c == null) continue;
                MqttClientState st = c.getState();
                if (!st.isConnected() && st != MqttClientState.CONNECTING) {
                    markLost("状态=" + st);
                }
            }
        }, "mqtt-watchdog");
        watchdog.start();
    }

    private void scheduleRetry(final Context app, String why) {
        if (!wantConnected) return;
        if (!P.autoReconnect(app)) {
            state("已断开（未开启自动重连）", false);
            return;
        }
        if (retryPending) return;
        retryPending = true;
        final int n = ++retryCount;
        final int delaySec = Math.min(30, 1 << Math.min(n - 1, 5));
        log("[重连] " + why + " → " + delaySec + "s 后第 " + n + " 次尝试");
        state("等待重连（" + delaySec + "s，第 " + n + " 次）", false);
        main.postDelayed(() -> {
            retryPending = false;
            if (wantConnected) connectOnce(app);
        }, delaySec * 1000L);
    }

    public void disconnect() {
        wantConnected = false;
        connecting = false;
        retryPending = false;
        Thread w = watchdog;
        watchdog = null;
        if (w != null) w.interrupt();
        final Mqtt3AsyncClient c = client;
        client = null;
        state("未连接", false);
        final Context app = appCtx;
        if (c != null) {
            new Thread(() -> {
                try {
                    if (app != null) {
                        String self = P.isExecutor(app) ? PRESENCE_EXE : PRESENCE_CTL;
                        c.publishWith().topic(self).qos(MqttQos.AT_MOST_ONCE).retain(true)
                                .payload(presenceJson(P.clientId(app), false).getBytes("UTF-8"))
                                .send().get(1500, TimeUnit.MILLISECONDS);
                    }
                } catch (Exception ignore) {
                }
                try {
                    c.disconnect().get(3, TimeUnit.SECONDS);
                } catch (Exception ignore) {
                }
            }, "mqtt-disconnect").start();
        }
    }

    /** 发布；没连接就返回 false，并把状态改成断线 */
    public boolean publish(String topic, String payload, int qosLevel) {
        final Mqtt3AsyncClient c = client;
        if (c == null || !c.getState().isConnected()) {
            log("[发布失败] " + topic + " → 客户端未连接");
            markLost("发布时发现未连接");
            return false;
        }
        byte[] raw = null;
        byte[] out = null;
        try {
            raw = payload.getBytes("UTF-8");
            out = Compress.pack(raw);
        } catch (Exception ignore) {
        }
        if (raw != null && out != null && out.length < raw.length) {
            LogFile.write("→ " + topic + " [gzip " + raw.length + "B→" + out.length + "B] " + payload);
        } else {
            LogFile.write("→ " + topic + " " + payload);
        }
        try {
            c.publishWith()
                    .topic(topic)
                    .payload(out != null ? out : payload.getBytes("UTF-8"))
                    .qos(toQos(qosLevel))
                    .send()
                    .whenComplete((pub, ex) -> {
                        if (ex != null) {
                            Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
                            log("[发布失败] " + topic + " → " + brief(cause));
                            if (cause instanceof MqttClientStateException
                                    || cause.getClass().getSimpleName().contains("NotConnected")
                                    || c.getState() == MqttClientState.DISCONNECTED) {
                                markLost("发布失败：" + brief(cause));
                            }
                        } else {
                            txCount++;
                            lastTxAt = System.currentTimeMillis();
                        }
                    });
            return true;
        } catch (Exception e) {
            log("[发布异常] " + brief(e));
            markLost("发布异常：" + brief(e));
            return false;
        }
    }

    /** 同步发布：等发布真正完成才返回（执行端用它保证结果发出后再释放 wakelock） */
    public boolean publishSync(String topic, String payload, int qosLevel, long timeoutMs) {
        final Mqtt3AsyncClient c = client;
        if (c == null || !c.getState().isConnected()) {
            log("[发布失败] " + topic + " → 客户端未连接");
            markLost("发布时发现未连接");
            return false;
        }
        byte[] raw = null;
        byte[] out = null;
        try {
            raw = payload.getBytes("UTF-8");
            out = Compress.pack(raw);
        } catch (Exception ignore) {
        }
        if (raw != null && out != null && out.length < raw.length) {
            LogFile.write("→ " + topic + " [gzip " + raw.length + "B→" + out.length + "B] " + payload);
        } else {
            LogFile.write("→ " + topic + " " + payload);
        }
        try {
            CompletableFuture<?> f = c.publishWith()
                    .topic(topic)
                    .payload(out != null ? out : payload.getBytes("UTF-8"))
                    .qos(toQos(qosLevel))
                    .send();
            f.get(timeoutMs, TimeUnit.MILLISECONDS);
            txCount++;
            lastTxAt = System.currentTimeMillis();
            return true;
        } catch (Exception e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            log("[发布失败] " + topic + " → " + brief(cause));
            if (cause instanceof MqttClientStateException
                    || cause.getClass().getSimpleName().contains("NotConnected")) {
                markLost("发布失败：" + brief(cause));
            }
            return false;
        }
    }

    public int qosLevel() {
        return qos;
    }

    private static String presenceJson(String id, boolean online) {
        return "{\"id\":\"" + (id == null ? "" : id) + "\",\"online\":" + online
                + ",\"ts\":" + System.currentTimeMillis() + "}";
    }

    /** 发一条 retained 消息（在线状态用；普通 publish 不带 retain，broker 不会替我们保存） */
    public boolean publishRetained(String topic, String payload) {
        final Mqtt3AsyncClient c = client;
        if (c == null || !c.getState().isConnected()) return false;
        try {
            c.publishWith().topic(topic).qos(MqttQos.AT_MOST_ONCE).retain(true)
                    .payload(payload.getBytes("UTF-8")).send()
                    .whenComplete((p, ex) -> {
                        if (ex == null) {
                            txCount++;
                            lastTxAt = System.currentTimeMillis();
                        }
                    });
            LogFile.write("→ " + topic + " [retained] " + payload);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 对端是否在线（控制端视角＝执行端，执行端视角＝控制端） */
    public boolean isPeerOnline() {
        return peerOnline;
    }
}

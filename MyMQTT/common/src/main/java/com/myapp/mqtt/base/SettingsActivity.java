package com.myapp.mqtt.base;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页：一个包同时是控制端和执行端，角色在这里切换；两种角色显示不同的设置区块。
 * 没有证书路径输入（TLS 走系统信任，只需勾 Use TLS）。
 */
public class SettingsActivity extends Activity {

    private EditText sHost, sPort, sUser, sPass, sKeepalive, sQos, sTimeout, sClientId, sPub, sQuick, sStartup, sOutLimit, sEditor;
    private CheckBox cWake, cBattopt;
    private CheckBox sTls, sDebug, sLogFile, sBoot, sAuto, cRootup;
    private RadioGroup sRole;
    private TextView sInfo, sRoleHint, sSubInfo, sLogPath;
    private View sBoxQuick, sBoxExec;
    private TextView sPubLabel;

    private boolean suppressRole;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings);

        sHost = findViewById(R.id.s_host);
        sPort = findViewById(R.id.s_port);
        sUser = findViewById(R.id.s_user);
        sPass = findViewById(R.id.s_pass);
        sKeepalive = findViewById(R.id.s_keepalive);
        sQos = findViewById(R.id.s_qos);
        sTimeout = findViewById(R.id.s_timeout);
        sClientId = findViewById(R.id.s_clientid);
        sPub = findViewById(R.id.s_pub);
        sQuick = findViewById(R.id.s_quick);
        sStartup = findViewById(R.id.s_startup);
        sOutLimit = findViewById(R.id.s_outlimit);
        sEditor = findViewById(R.id.s_editor);
        cWake = findViewById(R.id.c_wake);
        cRootup = findViewById(R.id.c_rootup);
        cBattopt = findViewById(R.id.c_battopt);
        sTls = findViewById(R.id.s_tls);
        sDebug = findViewById(R.id.s_debug);
        sLogFile = findViewById(R.id.s_logfile);
        sLogPath = findViewById(R.id.s_logpath);
        sBoot = findViewById(R.id.s_boot);
        sAuto = findViewById(R.id.s_auto);
        sRole = findViewById(R.id.s_role);
        sInfo = findViewById(R.id.s_info);
        sRoleHint = findViewById(R.id.s_role_hint);
        sSubInfo = findViewById(R.id.s_sub_info);
        sBoxQuick = findViewById(R.id.s_box_quick);
        sBoxExec = findViewById(R.id.s_box_exec);
        sPubLabel = findViewById(R.id.s_pub_label);

        load();

        sRole.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressRole) return;
            String want = (checkedId == R.id.s_role_exec) ? P.ROLE_EXECUTOR : P.ROLE_CONTROL;
            if (want.equals(P.role(this))) return;
            switchRole(want);
        });

        ((Button) findViewById(R.id.s_save)).setOnClickListener(v -> save());
        ((Button) findViewById(R.id.s_back)).setOnClickListener(v -> finish());
    }

    private void load() {
        sHost.setText(P.host(this));
        sPort.setText(String.valueOf(P.port(this)));
        sUser.setText(P.user(this));
        sPass.setText(P.pass(this));
        sKeepalive.setText(String.valueOf(P.keepAlive(this)));
        sQos.setText(String.valueOf(P.qos(this)));
        sTimeout.setText(String.valueOf(P.connectTimeout(this)));
        sClientId.setText(P.clientId(this));
        sPub.setText(P.pubTopic(this));
        sQuick.setText(P.quickCmdsRaw(this));
        sStartup.setText(P.startupCmd(this));
        sOutLimit.setText(String.valueOf(P.outputLimit(this)));
        String ep = P.editorPkg(this), ec = P.editorCls(this);
        sEditor.setText((ep.isEmpty() && ec.isEmpty()) ? "" : ep + "/" + ec);
        cWake.setChecked(P.wakeLock(this));
        cRootup.setChecked(P.rootUp(this));
        boolean ign = false;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            ign = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception ignore) {
        }
        cBattopt.setChecked(ign);
        cBattopt.setOnClickListener(v -> {
            boolean want = cBattopt.isChecked();
            if (want) {
                try {
                    startActivity(new android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    startActivity(new android.content.Intent(
                            android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            } else {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
        sTls.setChecked(P.tls(this));
        sDebug.setChecked(P.debug(this));
        sLogFile.setChecked(P.logToFile(this));
        sLogPath.setText("→ " + LogFile.dirPath(this));
        sBoot.setChecked(P.autoConnectOnBoot(this));
        sAuto.setChecked(P.autoReconnect(this));

        suppressRole = true;
        sRole.check(P.isExecutor(this) ? R.id.s_role_exec : R.id.s_role_control);
        suppressRole = false;

        applyRoleView();
        sInfo.setText("当前保存值：\n" + P.summary(this));
    }

    /** 角色相关控件：控制端显示 Quick，执行端显示说明；主题说明跟着变 */
    private void applyRoleView() {
        boolean exec = P.isExecutor(this);
        sBoxQuick.setVisibility(exec ? View.GONE : View.VISIBLE);
        sBoxExec.setVisibility(exec ? View.VISIBLE : View.GONE);
        sRoleHint.setText(exec
                ? "Executor：订阅指令主题，收到什么就执行什么，结果发回结果主题"
                : "Control：发命令、收结果；Quick 快捷指令在这里改");
        List<String> subs = P.subTopics(this);
        StringBuilder b = new StringBuilder("订阅: ");
        if (subs.isEmpty()) b.append("(空)");
        for (int i = 0; i < subs.size(); i++) {
            if (i > 0) b.append("  ");
            b.append(subs.get(i));
        }
        sSubInfo.setText(b.toString());
        sPubLabel.setText(exec
                ? "Publish topic (执行结果发到这个主题)"
                : "Publish topic (SEND 默认发到这个主题)");
    }

    /** 换角色：立刻生效，主题恢复成该角色的默认值，并断开旧连接 */
    private void switchRole(String role) {
        P.setRole(this, role);
        P.setPubTopic(this, P.isExecutor(this) ? "executor/result" : "control/command");
        List<String> def = new ArrayList<>();
        def.add(P.isExecutor(this) ? "control/command" : "executor/result");
        P.setSubTopics(this, def);

        // 断开旧角色的连接，避免还挂在旧主题上
        try {
            MqttCore.get().disconnect();
            stopService(new Intent(this, MqttService.class));
        } catch (Exception ignore) {
        }

        sPub.setText(P.pubTopic(this));
        applyRoleView();
        sInfo.setText("已切换到" + (P.isExecutor(this) ? "执行端" : "控制端") + "，主题已重置为默认值。\n"
                + "回主界面点 Connect 重新连接。\n\n当前保存值：\n" + P.summary(this));
        toast("角色已切换：" + (P.isExecutor(this) ? "Executor" : "Control") + "（需重新连接）");
    }

    private void save() {
        String host = sHost.getText().toString().trim();
        if (host.isEmpty()) {
            toast("Broker host 不能为空");
            return;
        }
        int port = num(sPort, -1);
        if (port < 1 || port > 65535) {
            toast("端口必须在 1~65535");
            return;
        }
        int ka = num(sKeepalive, -1);
        if (ka < 5 || ka > 3600) {
            toast("keepalive 必须在 5~3600 秒");
            return;
        }
        int qos = num(sQos, -1);
        if (qos < 0 || qos > 2) {
            toast("QoS 只能是 0 / 1 / 2");
            return;
        }
        int timeout = num(sTimeout, -1);
        if (timeout < 0 || timeout > 120) {
            toast("连接超时必须在 0~120 秒（0=不超时）");
            return;
        }
        String clientId = sClientId.getText().toString().trim();
        if (clientId.isEmpty()) {
            toast("Client ID 不能为空");
            return;
        }
        String pub = sPub.getText().toString().trim();
        if (pub.isEmpty()) {
            toast("发布主题不能为空");
            return;
        }

        P.setHost(this, host);
        P.setPort(this, port);
        P.setTls(this, sTls.isChecked());
        P.setRootUp(this, cRootup.isChecked());
        P.setDebug(this, sDebug.isChecked());
        P.setLogToFile(this, sLogFile.isChecked());
        sLogPath.setText("→ " + LogFile.dirPath(this));
        P.setAuth(this, sUser.getText().toString().trim(), sPass.getText().toString());
        P.setKeepAlive(this, ka);
        P.setQos(this, qos);
        P.setConnectTimeout(this, timeout);
        P.setClientId(this, clientId);
        P.setPubTopic(this, pub);
        P.setQuickCmdsRaw(this, sQuick.getText().toString());
        String spec = sEditor.getText().toString().trim();
        P.setWakeLock(this, cWake.isChecked());
        int slash = spec.indexOf('/');
        if (slash < 0) P.setEditor(this, spec, "");
        else P.setEditor(this, spec.substring(0, slash), spec.substring(slash + 1));
        P.setStartupCmd(this, sStartup.getText().toString().trim());
        try {
            String ol = sOutLimit.getText().toString().trim();
            if (!ol.isEmpty()) P.setOutputLimit(this, Integer.parseInt(ol));
        } catch (Exception ignore) {
        }
        P.setAutoConnectOnBoot(this, sBoot.isChecked());
        P.setAutoReconnect(this, sAuto.isChecked());

        sInfo.setText("已保存 " + System.currentTimeMillis() / 1000 + "\n" + P.summary(this));
        toast("Saved");
    }

    private int num(EditText et, int def) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (Exception e) {
            return def;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

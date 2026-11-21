package com.myapp.mybleclient;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：广播名 / 密钥 / RSSI 轮询间隔 / 启动自动连接，全部落盘 */
public class Settings extends Activity {

    EditText sServer, sKey, sRssi, sZero, sSilent, sQuick, sEditorPkg, sEditorCls, sOnConnect;
    CheckBox sAuto, sWake, sDebug, sPipeline;
    TextView sInfo, sMac;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Settings");
        setContentView(R.layout.settings);

        sServer = (EditText) findViewById(R.id.s_server);
        sKey = (EditText) findViewById(R.id.s_key);
        sRssi = (EditText) findViewById(R.id.s_rssi);
        sZero = (EditText) findViewById(R.id.s_zero);
        sSilent = (EditText) findViewById(R.id.s_silent);
        sQuick = (EditText) findViewById(R.id.s_quick);
        sEditorPkg = (EditText) findViewById(R.id.s_editor_pkg);
        sEditorCls = (EditText) findViewById(R.id.s_editor_cls);
        sOnConnect = (EditText) findViewById(R.id.s_onconnect);
        sAuto = (CheckBox) findViewById(R.id.s_auto);
        sWake = (CheckBox) findViewById(R.id.s_wake);
        sDebug = (CheckBox) findViewById(R.id.s_debug);
        sPipeline = (CheckBox) findViewById(R.id.s_pipeline);
        sInfo = (TextView) findViewById(R.id.s_info);
        sMac = (TextView) findViewById(R.id.s_mac);
        showMac();

        sServer.setText(P.server(this));
        sKey.setText(P.psk(this));
        sRssi.setText(String.valueOf(P.rssiSec(this)));
        int zc = P.zeroChunkMs(this);
        sZero.setText(zc == 0 ? "0" : String.valueOf(zc / 1000.0f));
        sSilent.setText(String.valueOf(P.rssiSilentSec(this)));
        sQuick.setText(P.quickCmdsRaw(this));
        sEditorPkg.setText(P.editorPkg(this));
        sEditorCls.setText(P.editorCls(this));
        sOnConnect.setText(P.onConnectCmd(this));
        sWake.setChecked(P.keepWake(this));
        sAuto.setChecked(P.autoConnect(this));
        sDebug.setChecked(P.debug(this));
        sPipeline.setChecked(P.uploadPipeline(this));
        sInfo.setText("Saved: Server=" + P.server(this)
                + "  RSSI=" + P.rssiSec(this) + "s"
                + "  zero-chunk timeout=" + (P.zeroChunkMs(this) == 0 ? "off" : P.zeroChunkMs(this) + "ms")
                + "  silence death=" + (P.rssiSilentSec(this) == 0 ? "off" : P.rssiSilentSec(this) + "s")
                + "  wake-lock=" + P.keepWake(this)
                + "  auto-connect=" + P.autoConnect(this)
                + "  debug-log=" + P.debug(this) + " → Download/mybleclient.log"
                + "  upload-pipeline=" + P.uploadPipeline(this)
                + "  saved-MAC=" + (P.savedMac(this).length() == 0 ? "(none)" : P.savedMac(this))
                + "  key=" + (P.psk(this).length() == 0 ? "(empty)" : P.psk(this).length() + " chars")
                + "\n  on-connect cmd=" + (P.onConnectCmd(this).length() == 0 ? "(none)" : P.onConnectCmd(this)));

        ((Button) findViewById(R.id.s_save)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        ((Button) findViewById(R.id.s_cancel)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        ((Button) findViewById(R.id.s_clear_mac)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                P.clearMac(Settings.this);
                showMac();
                Toast.makeText(Settings.this, "MAC forgotten; will use name next time", Toast.LENGTH_SHORT).show();
            }
        });
    }

    void showMac() {
        String mac = P.savedMac(this);
        sMac.setText(mac.length() == 0 ? "(none — will learn on first connect)" : mac);
    }

    void save() {
        String server = sServer.getText().toString().trim();
        String key = sKey.getText().toString().trim();
        int rssi;
        try {
            rssi = Integer.parseInt(sRssi.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "RSSI interval must be a number 1~60", Toast.LENGTH_SHORT).show();
            return;
        }
        if (rssi < 1 || rssi > 60) {
            Toast.makeText(this, "RSSI interval limited to 1~60 seconds", Toast.LENGTH_SHORT).show();
            return;
        }
        if (server.length() == 0) {
            Toast.makeText(this, "Server cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }
        // 零片超时：秒（支持小数）→ 毫秒；0 = 关闭提示
        int zeroMs;
        String zs = sZero.getText().toString().trim();
        try {
            double sec = Double.parseDouble(zs);
            zeroMs = (int) Math.round(sec * 1000);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Zero-chunk timeout must be 0.5~120 (0=off)", Toast.LENGTH_SHORT).show();
            return;
        }
        if (zeroMs != 0 && (zeroMs < 500 || zeroMs > 120000)) {
            Toast.makeText(this, "Zero-chunk timeout limited to 0.5~120 s (0=off)", Toast.LENGTH_SHORT).show();
            return;
        }
        // RSSI 静默判死：秒；0 = 关闭判死
        int silentSec;
        try {
            silentSec = Integer.parseInt(sSilent.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Silence-death must be 5~300 (0=off)", Toast.LENGTH_SHORT).show();
            return;
        }
        if (silentSec != 0 && (silentSec < 5 || silentSec > 300)) {
            Toast.makeText(this, "Silence-death limited to 5~300 s (0=off)", Toast.LENGTH_SHORT).show();
            return;
        }
        P.save(this, server, key, sAuto.isChecked(), rssi, zeroMs, silentSec, sWake.isChecked());
        P.saveDebug(this, sDebug.isChecked());
        if (!sDebug.isChecked()) DbgLog.close();   // 关闭开关时立刻停止写文件
        P.saveUploadPipeline(this, sPipeline.isChecked());
        P.saveQuickCmds(this, sQuick.getText().toString());
        P.saveEditor(this, sEditorPkg.getText().toString(), sEditorCls.getText().toString());
        P.saveOnConnectCmd(this, sOnConnect.getText().toString());
        sInfo.setText("Saved: " + System.currentTimeMillis() / 1000
                + "\nServer=" + server + "  RSSI=" + rssi + "s"
                + "  zero-chunk timeout=" + (zeroMs == 0 ? "off" : zeroMs + "ms")
                + "  silence death=" + (silentSec == 0 ? "off" : silentSec + "s")
                + "  wake-lock=" + sWake.isChecked()
                + "  auto-connect=" + sAuto.isChecked()
                + "  debug-log=" + sDebug.isChecked() + (sDebug.isChecked() ? " → Download/mybleclient.log" : "")
                + "  upload-pipeline=" + sPipeline.isChecked()
                + "  key=" + (key.length() == 0 ? "(empty)" : key.length() + " chars")
                + "\n  on-connect cmd=" + (P.onConnectCmd(this).length() == 0 ? "(none)" : P.onConnectCmd(this)));
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    }
}

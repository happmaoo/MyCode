package com.myapp.myblecams;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.TextureView;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements CamServer.Listener {

    TextView tvStatus;
    ScrollView svLog;
    TextureView svPreview;
    View vMask;
    FrameLayout flVideo;
    Button btnStart, btnStop;
    Spinner spCam, spQuality;
    CamServer server;

    static final String[] CAMS = new String[]{"后置摄像头", "前置摄像头"};
    static final String[] QUALITIES = new String[]{"Low 160x120", "Mid 256x192", "High 320x240"};

    boolean spReady = false;   // 防止初始化 setSelection 触发监听

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        svLog = (ScrollView) findViewById(R.id.sv_log);
        svPreview = (TextureView) findViewById(R.id.sv_preview);
        vMask = findViewById(R.id.v_mask);
        flVideo = (FrameLayout) findViewById(R.id.fl_video);
        btnStart = (Button) findViewById(R.id.btn_start);
        btnStop = (Button) findViewById(R.id.btn_stop);
        spCam = (Spinner) findViewById(R.id.sp_cam);
        spQuality = (Spinner) findViewById(R.id.sp_quality);

        server = new CamServer(this, svPreview, this);

        spCam.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, CAMS));
        spQuality.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, QUALITIES));

        spCam.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (!spReady) return;
                server.switchCamera(pos);
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        spQuality.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (!spReady) return;
                server.setQuality(pos);
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        // 同步引擎默认状态（Low / 后摄）
        spCam.setSelection(server.camIdx);
        spQuality.setSelection(server.qualityIdx);
        spCam.postDelayed(new Runnable() {
            public void run() { spReady = true; }
        }, 500);

        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (server.start()) {
                    // 广播期间保持屏幕常亮，防止熄屏导致摄像头被系统关闭、推流中断
                    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    updateButtons(true);
                }
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                server.stop();
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                updateButtons(false);
            }
        });

        updateButtons(false);
        requestPerms();
        append("MyBleCamS 就绪，点 Start 开始采集广播");
    }

    // 预览比例由 CamServer.applyPreviewTransform() 的纹理矩阵负责（FIT 居中留黑边）。
    // TextureView 保持 match_parent 固定尺寸，绝不在运行中改尺寸——
    // 否则 SurfaceTexture 被重建、Camera2 session 绑定的 Surface 失效 → 黑屏。
    void applyPreviewRatio() {
        if (server != null) server.applyPreviewTransform();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus) applyPreviewRatio();
    }

    // 标题栏右侧 ⋮ 溢出菜单：设置页入口
    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "设置");
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    void updateButtons(boolean started) {
        btnStart.setEnabled(!started);
        btnStop.setEnabled(started);
        spCam.setEnabled(started);
        spQuality.setEnabled(started);
    }


    void requestPerms() {
        if (Build.VERSION.SDK_INT >= 23) {
            List<String> need = new ArrayList<String>();
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                need.add(android.Manifest.permission.CAMERA);
            if (need.isEmpty()) return;
            requestPermissions(need.toArray(new String[0]), 1);
        }
    }

    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        append("权限结果已处理");
    }

    final ArrayList<String> logLines = new ArrayList<String>();
    @Override public void onLog(String s) { append(s); }

    @Override public void onFrameSent() { }

    // 客户端断开后把下方预览画面清空（盖黑），连接后恢复显示
    @Override public void onClientState(final boolean connected) {
        runOnUiThread(new Runnable() {
            public void run() {
                vMask.setVisibility(connected ? View.GONE : View.VISIBLE);
                if (!connected) append("客户端已断开，预览画面已清空");
            }
        });
    }

    @Override public void onStateChanged(final int cam, final int quality) {
        runOnUiThread(new Runnable() {
            public void run() {
                spReady = false;
                spCam.setSelection(cam);
                spQuality.setSelection(quality);
                spReady = true;
            }
        });
    }

    void append(final String s) {
        runOnUiThread(new Runnable() {
            public void run() {
                logLines.add(s);
                while (logLines.size() > 12) logLines.remove(0);
                StringBuilder sb = new StringBuilder();
                for (String line : logLines) sb.append(line).append("\n");
                tvStatus.setText(sb.toString());
                svLog.post(new Runnable() {
                    public void run() { svLog.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    // 设置页保存后返回时应用配置
    @Override protected void onResume() {
        super.onResume();
        if (server == null) return;
        SharedPreferences sp = getSharedPreferences("myblecams", MODE_PRIVATE);
        server.setServerName(sp.getString("server_name", "MyBleCamS"));
        server.setOnConnectCmd(sp.getString("on_connect_cmd", ""));
        server.setOnDisconnectCmd(sp.getString("on_disconnect_cmd", ""));
        server.applyDefaults(sp.getInt("default_cam", 0), sp.getInt("default_quality", 0));
        server.setPreviewRotation(sp.getInt("preview_rotation", -1));
    }

    @Override protected void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }
}

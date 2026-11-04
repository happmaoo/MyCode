package com.myapp.blecams;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.IBinder;
import android.view.TextureView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.myapp.blecams.protocol.Perms;
import com.myapp.blecams.protocol.VideoProfile;
import com.myapp.blecams.service.ServerService;

import java.util.Locale;

/**
 * 服务端主界面：权限申请、档位选择、本地预览和链路状态显示。
 * 真正的采集/编码/发送都在 {@link ServerService} 里，界面关掉也不影响推流。
 */
public class MainActivity extends AppCompatActivity
        implements TextureView.SurfaceTextureListener, ServerService.UiListener {

    private static final int REQ_PERMS = 1001;

    private TextureView previewView;
    private TextView statusView;
    private Spinner profileSpinner;
    private ServerService.Client client;
    private boolean bound;
    private boolean startAfterPermission;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.preview);
        statusView = findViewById(R.id.status);
        profileSpinner = findViewById(R.id.profile);
        Button start = findViewById(R.id.btn_start);
        Button stop = findViewById(R.id.btn_stop);

        String[] labels = new String[VideoProfile.ALL.length];
        for (int i = 0; i < VideoProfile.ALL.length; i++) {
            VideoProfile p = VideoProfile.ALL[i];
            labels[i] = p.name + " " + p.describe();
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);
        profileSpinner.setSelection(indexOf(VideoProfile.DEFAULT));
        profileSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (client != null) {
                    client.switchProfile(VideoProfile.ALL[position]);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        previewView.setSurfaceTextureListener(this);

        start.setOnClickListener(v -> requestStart());
        stop.setOnClickListener(v -> ServerService.stop(this));

        if (!Perms.hasAll(this, Perms.serverPermissions())) {
            statusView.setText(R.string.need_permission);
        } else {
            statusView.setText("点击“开始推流”后本机开始广播，等待客户端连接");
        }
    }

    private static int indexOf(VideoProfile p) {
        for (int i = 0; i < VideoProfile.ALL.length; i++) {
            if (VideoProfile.ALL[i].id == p.id) {
                return i;
            }
        }
        return 0;
    }

    private VideoProfile selected() {
        int i = profileSpinner.getSelectedItemPosition();
        if (i < 0 || i >= VideoProfile.ALL.length) {
            i = indexOf(VideoProfile.DEFAULT);
        }
        return VideoProfile.ALL[i];
    }

    private void requestStart() {
        String[] missing = Perms.missing(this, Perms.serverPermissions());
        if (missing.length > 0) {
            startAfterPermission = true;
            requestPermissions(missing, REQ_PERMS);
            return;
        }
        ServerService.start(this, selected().id);
        statusView.setText("启动中：正在广播 " + bleName());
    }

    private String bleName() {
        return com.myapp.blecams.protocol.BleCamUuids.DEVICE_NAME;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS && startAfterPermission) {
            startAfterPermission = false;
            if (Perms.hasAll(this, Perms.serverPermissions())) {
                ServerService.start(this, selected().id);
            } else {
                statusView.setText(R.string.need_permission);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, ServerService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (bound) {
            try {
                if (client != null) {
                    client.detachPreview();
                }
                unbindService(connection);
            } catch (Exception ignored) {
            }
            bound = false;
            client = null;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ServerService.setUiListener(this);
    }

    @Override
    protected void onPause() {
        ServerService.setUiListener(null);
        super.onPause();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            bound = true;
            if (service instanceof ServerService.Client) {
                client = (ServerService.Client) service;
                SurfaceTexture st = previewView.getSurfaceTexture();
                if (st != null) {
                    client.attachPreview(st);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            client = null;
        }
    };

    // ---- TextureView ----

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (client != null) {
            client.attachPreview(surface);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (client != null) {
            client.detachPreview();
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    // ---- ServerService.UiListener ----

    @Override
    public void onSnapshot(final ServerService.Snapshot snapshot) {
        runOnUiThread(() -> render(snapshot));
    }

    private void render(ServerService.Snapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append("状态：").append(s.state);
        if (s.advertising) {
            sb.append("（广播中）");
        }
        sb.append('\n');
        sb.append("客户端：").append(s.client == null || s.client.isEmpty() ? "未连接" : s.client).append('\n');
        sb.append(String.format(Locale.US, "画面：%s @%dfps  码率 %dkbps\n",
                s.resolution, s.fps, s.bitrate / 1000));
        sb.append(String.format(Locale.US, "MTU %d  分包 %dB  实测 %dB/s  队列 %dB\n",
                s.mtu, s.chunkPayload, s.throughput, s.queueBytes));
        sb.append(String.format(Locale.US, "已发 %d 帧，丢帧 %d\n", s.framesSent, s.framesDropped));
        if (s.profileIdc >= 0) {
            sb.append(String.format(Locale.US, "码流 profile=%d 熵编码=%s\n",
                    s.profileIdc, s.cabac == 1 ? "CABAC" : (s.cabac == 0 ? "CAVLC" : "?")));
        }
        if (s.error != null && !s.error.isEmpty()) {
            sb.append("错误：").append(s.error);
        }
        statusView.setText(sb.toString());
    }
}

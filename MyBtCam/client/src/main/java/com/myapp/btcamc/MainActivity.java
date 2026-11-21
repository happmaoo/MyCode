package com.myapp.btcamc;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Locale;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity implements ClientSession.Callback {

    /** 断线自动重连的最大连续尝试次数。 */
    private static final int MAX_RECONNECT = 10;
    /** 两次自动重连之间的等待 */
    private static final long RECONNECT_DELAY_MS = 2000L;


    /** 码率档位，顺序与 R.array.bitrate_entries 一致。 */
    private static final int[] BITRATES = ClientSession.BITRATE_STEPS;

    private ClientSession session;
    private AspectSurfaceView surfaceView;
    private TextView statusView;
    private View toolbarView;
    private View panelView;
    private RadioGroup resGroup;
    private Spinner bitrateSpinner;
    private Button connectButton;

    private boolean fullscreen;
    private boolean binding;
    private boolean surfaceReady;
    private boolean connectRequested;
    private boolean connecting;
    private TextView latencyView;
    /** 用户主动点了「断开」，此时不再自动重连 */
    private boolean userStopped;
    /** 连续自动重连次数，连接成功后清零 */
    private int reconnectAttempts;
    /** 已排队的重连：onDisconnected / onError 可能连着触发，只算一次 */
    private boolean reconnectPending;
    private final Runnable reconnectTask = new Runnable() {
        @Override
        public void run() {
            reconnectPending = false;
            if (!Prefs.isAutoReconnect(MainActivity.this) || userStopped
                    || session.isConnected() || connecting) {
                return;
            }
            if (!surfaceReady) {
                return;
            }
            statusView.setText("自动重连中（" + reconnectAttempts + "/" + MAX_RECONNECT + "）");
            connect(false);
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_name);
        setSupportActionBar(toolbar);
        toolbarView = toolbar;

        statusView = findViewById(R.id.text_status);
        latencyView = findViewById(R.id.text_latency);
        panelView = findViewById(R.id.panel);
        surfaceView = findViewById(R.id.surface);
        resGroup = findViewById(R.id.rg_resolution);
        bitrateSpinner = findViewById(R.id.sp_bitrate);
        ArrayAdapter<CharSequence> bitrateAdapter = ArrayAdapter.createFromResource(
                this, R.array.bitrate_entries, android.R.layout.simple_spinner_item);
        bitrateAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        bitrateSpinner.setAdapter(bitrateAdapter);
        connectButton = findViewById(R.id.btn_connect);

        session = new ClientSession(this);

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                session.setSurface(holder.getSurface());
                if (Prefs.isAutoConnect(MainActivity.this)
                        && !connectRequested && !session.isConnected()) {
                    connect(false);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                session.setSurface(null);
            }
        });

        resGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (binding) {
                return;
            }
            int w = checkedId == R.id.rb_res_320 ? 320 : 640;
            int h = checkedId == R.id.rb_res_320 ? 240 : 480;
            Prefs.setResolution(this, w, h);
            if (session.isConnected()) {
                session.changeParams(w, h, currentBitrate());
            }
            updateStatus();
        });

        bitrateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int bitrate = BITRATES[position];
                Prefs.setBitrate(MainActivity.this, bitrate);
                // 只有和会话当前参数不同才重启码流，避免恢复界面状态时误触发
                if (session.isConnected() && bitrate != session.getRequestedBitrate()) {
                    session.changeParams(currentWidth(), currentHeight(), bitrate);
                }
                updateStatus();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        connectButton.setOnClickListener(v -> {
            if (connecting || session.isConnected()) {
                disconnect();
            } else {
                connect(true);
            }
        });

        syncRadios();
        updateStatus();
    }

    private void syncRadios() {
        binding = true;
        int w = Prefs.getWidth(this);
        resGroup.check(w == 320 ? R.id.rb_res_320 : R.id.rb_res_640);
        binding = false;
        syncBitrateSpinner();
    }

    private void syncBitrateSpinner() {
        int bitrate = Prefs.getBitrate(this);
        int index = 1;
        for (int i = 0; i < BITRATES.length; i++) {
            if (BITRATES[i] == bitrate) {
                index = i;
                break;
            }
        }
        bitrateSpinner.setSelection(index);
    }

    private int currentWidth() {
        return resGroup.getCheckedRadioButtonId() == R.id.rb_res_320 ? 320 : 640;
    }

    private int currentHeight() {
        return resGroup.getCheckedRadioButtonId() == R.id.rb_res_320 ? 240 : 480;
    }

    private int currentBitrate() {
        int position = bitrateSpinner == null ? 1 : bitrateSpinner.getSelectedItemPosition();
        if (position < 0 || position >= BITRATES.length) {
            position = 1;
        }
        return BITRATES[position];
    }

    private void connect(boolean fromUser) {
        if (!surfaceReady) {
            if (fromUser) {
                Toast.makeText(this, "画面还没准备好，请稍后再试", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        String mac = Prefs.getServerMac(this);
        if (mac == null || mac.trim().isEmpty()) {
            if (fromUser) {
                Toast.makeText(this, "请先在设置里选择已配对的服务端", Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, SettingsActivity.class));
            }
            return;
        }
        connectRequested = true;
        connecting = true;
        userStopped = false;
        updateButton();
        statusView.setText("正在连接 " + mac + " ...");
        session.setAutoBitrate(Prefs.isAutoBitrate(this));
        session.start(mac.trim(), currentWidth(), currentHeight(), currentBitrate());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** 未连接=「连接」，连接中=「取消连接」，已连接=「断开」。 */
    private void updateButton() {
        if (connectButton == null) {
            return;
        }
        boolean active = connecting || session.isConnected();
        connectButton.setText(connecting ? R.string.btn_cancel
                : session.isConnected() ? R.string.btn_disconnect
                : R.string.btn_connect);
        connectButton.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, active && session.isConnected()
                        ? R.color.disconnect : R.color.accent)));
    }

    private void disconnect() {
        userStopped = true;
        reconnectAttempts = 0;
        reconnectPending = false;
        if (statusView != null) {
            statusView.removeCallbacks(reconnectTask);
        }
        session.stop();
        connecting = false;
        connectRequested = false;
        updateButton();
        statusView.setText(R.string.status_idle);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void updateStatus() {
        updateButton();
        if (session.isConnected()) {
            statusView.setText("已连接：" + currentWidth() + "x" + currentHeight()
                    + " / " + (currentBitrate() / 1000) + "kbps");
        } else if (!connectRequested) {
            statusView.setText(R.string.status_idle);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 会话回调                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public void onState(final String text) {
        runOnUiThread(() -> statusView.setText(text));
    }

    @Override
    public void onConnected() {
        runOnUiThread(() -> {
            connecting = false;
            reconnectAttempts = 0;
            reconnectPending = false;
            statusView.removeCallbacks(reconnectTask);
            updateButton();
            statusView.setText("已连接");
        });
    }

    @Override
    public void onDisconnected() {
        runOnUiThread(() -> {
            connectRequested = false;
            connecting = false;
            updateButton();
            scheduleReconnect("连接已断开");
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    /**
     * 断线后自动重连：连续最多 MAX_RECONNECT 次（每次连接成功会清零）。
     * 用户主动断开、或设置里关掉开关时不重连。
     */
    private void scheduleReconnect(String why) {
        if (!Prefs.isAutoReconnect(this) || userStopped) {
            statusView.setText(why);
            return;
        }
        if (session.isConnected() || connecting) {
            return;
        }
        if (reconnectPending) {
            return;
        }
        if (reconnectAttempts >= MAX_RECONNECT) {
            statusView.setText(why + "（已自动重连 " + MAX_RECONNECT + " 次仍失败，已停止）");
            return;
        }
        if (!surfaceReady) {
            statusView.setText(why);
            return;
        }
        reconnectAttempts++;
        reconnectPending = true;
        statusView.setText(why + "，" + (RECONNECT_DELAY_MS / 1000) + " 秒后自动重连（"
                + reconnectAttempts + "/" + MAX_RECONNECT + "）");
        statusView.postDelayed(reconnectTask, RECONNECT_DELAY_MS);
    }

    @Override
    public void onLatency(final int latencyMs, final int queueDepth, final boolean stale) {
        runOnUiThread(() -> {
            if (latencyView == null) {
                return;
            }
            if (!session.isConnected()) {
                latencyView.setText("");
                return;
            }
            float fps = currentFps();
            int frames = fps > 0 ? Math.round(latencyMs * fps / 1000f) : 0;
            String text = "延迟 " + String.format(Locale.US, "%.2f", latencyMs / 1000f) + "s"
                    + (frames > 0 ? "（约 " + frames + " 帧）" : "")
                    + " · 待解码 " + queueDepth + " 帧"
                    + (stale ? " · 画面滞后" : " · 实时");
            latencyView.setText(text);
            latencyView.setTextColor(ContextCompat.getColor(MainActivity.this,
                    stale ? R.color.disconnect : R.color.text_secondary));
        });
    }

    /** 最近统计到的接收帧率，用于把延迟换算成帧数。 */
    private float currentFps() {
        return session == null ? 0 : session.getRxFps();
    }

    @Override
    public void onBitrateChanged(final int bitrate) {
        runOnUiThread(() -> {
            Prefs.setBitrate(this, bitrate);
            syncBitrateSpinner();
            statusView.setText("链路拥塞，已自动降到 " + (bitrate / 1000) + " Kbps");
        });
    }

    @Override
    public void onVideoFormat(final int width, final int height) {
        runOnUiThread(() -> {
            surfaceView.setVideoSize(width, height);
            statusView.setText("推流中 " + width + "x" + height + " / "
                    + (currentBitrate() / 1000) + " Kbps");
            surfaceView.post(() -> Log.i("ClientSession", "video " + width + "x" + height
                    + " view " + surfaceView.getWidth() + "x" + surfaceView.getHeight()));
        });
    }

    @Override
    public void onError(final String message) {
        runOnUiThread(() -> {
            connecting = false;
            updateButton();
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            scheduleReconnect("错误：" + message);
        });
    }

    /* ------------------------------------------------------------------ */
    /* 菜单 / 全屏                                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        MenuItem full = menu.findItem(R.id.action_fullscreen);
        if (full != null) {
            full.setTitle(fullscreen ? R.string.exit_fullscreen : R.string.enter_fullscreen);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_fullscreen) {
            setFullscreen(!fullscreen);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setFullscreen(boolean enable) {
        fullscreen = enable;
        toolbarView.setVisibility(enable ? View.GONE : View.VISIBLE);
        panelView.setVisibility(enable ? View.GONE : View.VISIBLE);
        int flags = enable
                ? View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                : View.SYSTEM_UI_FLAG_VISIBLE;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        invalidateOptionsMenu();
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            setFullscreen(false);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncRadios();
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        session.stop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (statusView != null) {
            statusView.removeCallbacks(reconnectTask);
        }
        super.onDestroy();
    }
}

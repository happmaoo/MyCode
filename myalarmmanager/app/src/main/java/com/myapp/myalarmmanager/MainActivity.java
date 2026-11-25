package com.myapp.myalarmmanager;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private EditText etPath;
    private EditText etInterval;
    private CheckBox cbRoot;
    private CheckBox cbStrong;
    private TextView tvStatus;
    private TextView tvIntervalHint;
    private TextView tvLog;

    private SharedPreferences pref;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pref = getSharedPreferences(Cfg.PREFS, MODE_PRIVATE);
        etPath = findViewById(R.id.et_path);
        etInterval = findViewById(R.id.et_interval);
        cbRoot = findViewById(R.id.cb_root);
        cbStrong = findViewById(R.id.cb_strong);
        tvStatus = findViewById(R.id.tv_status);
        tvIntervalHint = findViewById(R.id.tv_interval_hint);
        tvLog = findViewById(R.id.tv_log);

        String savedPath = pref.getString(Cfg.KEY_SCRIPT, Cfg.DEFAULT_SCRIPT);
        if ("/data/data/com.termux/files/home/run_gitee.sh".equals(savedPath)) {
            savedPath = Cfg.DEFAULT_SCRIPT;   // 老默认值迁移
            pref.edit().putString(Cfg.KEY_SCRIPT, savedPath).commit();
        }
        etPath.setText(savedPath);
        etInterval.setText(String.valueOf(pref.getLong(Cfg.KEY_INTERVAL, Cfg.DEFAULT_INTERVAL)));
        etInterval.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) { updateIntervalHint(); }
        });
        updateIntervalHint();

        cbRoot.setChecked(pref.getBoolean(Cfg.KEY_ROOT, true));
        cbStrong.setChecked(pref.getBoolean(Cfg.KEY_STRONG, false));

        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); toast("已保存"); }
        });
        findViewById(R.id.btn_run).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runNow(); }
        });
        findViewById(R.id.btn_start).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { start(); }
        });
        findViewById(R.id.btn_stop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stop(); }
        });
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ScriptRunner.clearLog(getFilesDir().getAbsolutePath());
                refreshLog();
            }
        });

        if (pref.getBoolean(Cfg.KEY_LOOP, false)) KeepAliveService.start(this);
        refreshStatus();
        ui.postDelayed(new Runnable() {
            @Override public void run() { refreshStatus(); }
        }, 600);
        refreshLog();
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == R.id.action_help) {
            startActivity(new android.content.Intent(this, HelpActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        selfHeal();
        refreshStatus();
        refreshLog();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save(); // 切走自动存一次
    }

    // ---------------- 设置 ----------------

    private void save() {
        long interval = num(etInterval, Cfg.DEFAULT_INTERVAL, 5);

        pref.edit()
                .putString(Cfg.KEY_SCRIPT, etPath.getText().toString().trim())
                .putLong(Cfg.KEY_INTERVAL, interval)
                .putBoolean(Cfg.KEY_ROOT, cbRoot.isChecked())
                .putBoolean(Cfg.KEY_STRONG, cbStrong.isChecked())
                .commit();
    }

    private long num(EditText et, long def, long min) {
        long v;
        try {
            v = Long.parseLong(et.getText().toString().trim());
        } catch (Exception e) {
            v = def;
            et.setText(String.valueOf(def));
        }
        if (v < min) {
            v = min;
            et.setText(String.valueOf(min));
        }
        return v;
    }

    // ---------------- 启动 / 停止 ----------------

    private void start() {
        save();
        String path = pref.getString(Cfg.KEY_SCRIPT, Cfg.DEFAULT_SCRIPT);
        if (path.isEmpty()) {
            toast("先填脚本路径");
            return;
        }
        Scheduler.start(this, true);
        KeepAliveService.tryStart(this, "start");
        refreshStatus();
        toast("已开始循环运行");
    }

    private void stop() {
        Scheduler.stop(this);

        refreshStatus();
        // 停止即收通知
        stopService(new android.content.Intent(this, KeepAliveService.class));
        toast("已停止任务，通知已关闭");
    }

    private void runNow() {
        save();
        final String path = pref.getString(Cfg.KEY_SCRIPT, Cfg.DEFAULT_SCRIPT);
        final String dir = getFilesDir().getAbsolutePath();
        if (path.isEmpty()) {
            toast("先填脚本路径");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                ScriptRunner.Result r = ScriptRunner.run(path, pref.getBoolean(Cfg.KEY_ROOT, true));
                ScriptRunner.append(dir, "manual", r, path);
                ui.post(new Runnable() {
                    @Override public void run() {
                        refreshLog();
                        toast("exit=" + r.exit);
                    }
                });
            }
        }).start();
        toast("已提交执行");
    }

    /** 被强行停止 / 闹钟被 ROM 清掉后，只要循环还开着就自动接上 */
    private void selfHeal() {
        Scheduler.ensureLoop(this);
    }

    // ---------------- 展示 ----------------

    /** 秒数换算成人能读的分钟显示 */
    private void updateIntervalHint() {
        long v;
        try {
            v = Long.parseLong(etInterval.getText().toString().trim());
        } catch (Exception e) {
            tvIntervalHint.setText("");
            return;
        }
        if (v <= 0) {
            tvIntervalHint.setText("");
            return;
        }
        if (v < 60) {
            tvIntervalHint.setText("≈ 不到 1 分钟");
        } else if (v % 60 == 0) {
            tvIntervalHint.setText("≈ " + (v / 60) + " 分钟");
        } else {
            tvIntervalHint.setText("≈ " + (v / 60) + " 分 " + (v % 60) + " 秒");
        }
    }

    private void refreshStatus() {
        long next = pref.getLong(Cfg.KEY_NEXT_AT, 0);
        boolean loop = pref.getBoolean(Cfg.KEY_LOOP, false);
        long interval = pref.getLong(Cfg.KEY_INTERVAL, Cfg.DEFAULT_INTERVAL);

        String svc = KeepAliveService.isRunning() ? "通知 开" : "通知 关";
        svc += pref.getBoolean(Cfg.KEY_ROOT, true) ? " · root" : " · 非root";
        if (next <= 0) {
            tvStatus.setText("状态：未调度 · " + svc);
        } else if (loop) {
            tvStatus.setText("状态：循环中，每 " + interval + " 秒一次，下次 "
                    + ScriptRunner.fmt(next) + " · " + svc);
        } else {
            tvStatus.setText("状态：已排定一次，下次 " + ScriptRunner.fmt(next) + " · " + svc);
        }
    }

    private void refreshLog() {
        String log = ScriptRunner.readLog(getFilesDir().getAbsolutePath());
        if (log == null || log.isEmpty()) log = "（还没有运行记录）";
        tvLog.setText(log);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

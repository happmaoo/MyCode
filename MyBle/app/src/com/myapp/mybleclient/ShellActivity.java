package com.myapp.mybleclient;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * ShellActivity —— bash 风格的命令终端。
 *
 * 与主界面共用同一个 BleService（前台服务），进入时通过 setUi() 接管输出，
 * 退出（返回主界面）后 MainActivity.onResume 会重新认领 uiCb，互不影响。
 *   - 顶部：自动换行复选框 + 字号 A-/A+ + Copy / Clear（主界面风格）
 *   - 中间：最大区域，ScrollView + TextView 渲染日志（支持自动换行 / 字号调节 / 自动贴底）
 *   - 底部：多行命令输入框 + 模板按钮（弹出模板管理）+ Send 发送
 */
public class ShellActivity extends Activity {

    EditText etCmd;
    ScrollView svOut;
    HorizontalScrollView hsvOut;
    TextView tvOut;
    Button btnSend, btnCopy, btnClear, btnFontUp, btnFontDown, btnTpl;
    CheckBox cbWrap;

    BleService svc;                 // 绑定后拿到引擎

    // ---- 日志缓冲与渲染控制 ----
    StringBuilder plain = new StringBuilder();   // 原始文本（用于 Copy / 重置）
    int fontSize = 12;                          // 当前字号(px)
    boolean wrap = true;                        // 自动换行
    static final int FONT_MIN = 8, FONT_MAX = 32, CAP = 200000;
    boolean renderScheduled = false;
    final Handler h = new Handler(Looper.getMainLooper());

    // ---- 模板弹窗 ----
    static final String KEY_TPL = "templates";  // SharedPreferences 中的模板 JSON
    LinearLayout tplList;   // 弹窗内的模板条目容器
    ScrollView tplScroll;   // 弹窗内的滚动视图
    AlertDialog tplDlg;     // 当前弹窗（写入后用于关闭）

    // ------------------------------------------------------------- 服务绑定
    final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder b) {
            svc = ((BleService.LocalBinder) b).getService();
            svc.setUi(uiCb);        // 接管输出；setUi 会回放当前日志缓冲
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            svc = null;             // 只在服务被杀时发生；重连后 binder 会再回调
        }
    };

    /** 服务 → 本界面：仅关心“服务端文字”，其余回调对终端无意义的忽略 */
    final BleService.Ui uiCb = new BleService.Ui() {
        @Override public void onInfo(String s)      { /* 状态行(含心跳刷新)不进终端，避免刷屏 */ }
        @Override public void onBatt(String s)      { /* 终端不显示电量行 */ }
        @Override public void onLog(String s)       { appendOut(s); }
        @Override public void onLogReset(String s)  { if (alive()) { plain = new StringBuilder(s); render(); } }
        @Override public void onBtn(String t, boolean en) { }
        @Override public void onDisconnectBtn(boolean en) { }
        @Override public void onToast(String s)     { if (alive()) Toast.makeText(ShellActivity.this, s, Toast.LENGTH_SHORT).show(); }
        @Override public void onProgress(String line) { /* 进度走状态行，终端忽略 */ }
        @Override public void onUploadState(boolean uploading) { }
        @Override public void onFileList(java.util.ArrayList<String> items) { }
    };

    boolean alive() { return !isFinishing() && !isDestroyed(); }

    /** 持久化 Shell 显示偏好与模板，下次进入自动恢复 */
    SharedPreferences pref() { return getSharedPreferences("shell_pref", MODE_PRIVATE); }

    int dip(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    void appendOut(String s) {
        if (!alive()) return;
        plain.append(s);
        if (plain.length() > CAP) plain.delete(0, plain.length() - CAP);   // 限长，防止内存膨胀
        scheduleRender();
    }

    /** 合并多次 append，节流渲染（约 120ms 一次），避免流式刷屏卡顿 */
    void scheduleRender() {
        if (renderScheduled) return;
        renderScheduled = true;
        h.postDelayed(new Runnable() {
            @Override public void run() { renderScheduled = false; render(); }
        }, 120);
    }

    void render() {
        if (!alive() || tvOut == null) return;
        tvOut.setText(plain);
        scrollToBottom();
    }

    /** 滚到内容最底部：用 ScrollView.fullScroll，布局稳定后即可精准贴底（比 WebView 可靠） */
    void scrollToBottom() {
        if (svOut == null) return;
        svOut.post(new Runnable() { @Override public void run() { svOut.fullScroll(View.FOCUS_DOWN); } });
    }

    /** 应用自动换行：换行时 TextView 占满宽度并自动折行；不换行时横向可滚动查看长行 */
    void applyWrap() {
        if (tvOut == null || svOut == null) return;
        tvOut.setHorizontallyScrolling(!wrap);
        int w = wrap ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        tvOut.setLayoutParams(new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        svOut.setLayoutParams(new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
        // 重新布局后补一次贴底，避免切换瞬间停在半空
        scrollToBottom();
    }

    // ---------------------------------------------------------------- 模板
    /** 读取本地模板，返回 [标题, 内容] 数组列表 */
    ArrayList<String[]> loadTemplates() {
        ArrayList<String[]> out = new ArrayList<String[]>();
        String s = pref().getString(KEY_TPL, "");
        if (s == null || s.length() == 0) return out;
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new String[]{ o.optString("title", ""), o.optString("content", "") });
            }
        } catch (JSONException ignored) { }
        return out;
    }

    /** 把弹窗内当前所有条目的文本写回本地存储 */
    void saveTemplates() {
        if (tplList == null) return;
        JSONArray arr = new JSONArray();
        for (int i = 0; i < tplList.getChildCount(); i++) {
            View row = tplList.getChildAt(i);
            Object tag = row.getTag();
            if (!(tag instanceof EditText[])) continue;
            EditText[] ets = (EditText[]) tag;
            try {
                JSONObject o = new JSONObject();
                o.put("title", ets[0].getText().toString());
                o.put("content", ets[1].getText().toString());
                arr.put(o);
            } catch (JSONException ignored) { }
        }
        pref().edit().putString(KEY_TPL, arr.toString()).apply();
    }

    /** 往模板列表添加一条：标题输入 + 多行内容输入 + 写入 / 删除按钮 */
    void addTplRow(String title, String content) {
        if (tplList == null) return;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dip(8), dip(8), dip(8), dip(8));
        row.setBackgroundColor(0xFFF2F2F2);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dip(8);
        row.setLayoutParams(rlp);

        final EditText etTitle = new EditText(this);
        etTitle.setHint("标题");
        etTitle.setSingleLine(true);
        etTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        etTitle.setText(title);
        row.addView(etTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText etBody = new EditText(this);
        etBody.setHint("模板内容（可多行）");
        etBody.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etBody.setMinLines(3);
        etBody.setGravity(Gravity.TOP);
        etBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        etBody.setText(content);
        etBody.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dip(6);
        row.addView(etBody, blp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button bWrite = new Button(this);
        bWrite.setText("写入");
        Button bDel = new Button(this);
        bDel.setText("删除");
        btns.addView(bWrite, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btns.addView(bDel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnsLp.topMargin = dip(6);
        row.addView(btns, btnsLp);

        row.setTag(new EditText[]{ etTitle, etBody });   // 便于统一收集保存

        bWrite.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String c = etBody.getText().toString();
                etCmd.setText(c);                       // 替换命令输入区内容
                etCmd.setSelection(etCmd.getText().length());
                if (tplDlg != null) tplDlg.dismiss();   // 写入后关闭弹窗
            }
        });
        bDel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tplList.removeView(row);
                saveTemplates();
            }
        });

        tplList.addView(row);
    }

    /** 弹出模板管理窗口：顶部「+」新增，下面为可滚动的模板列表 */
    void showTemplates() {
        if (!alive()) return;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);
        root.setPadding(dip(10), dip(10), dip(10), dip(4));

        // 顶部栏：标题 + 「+」新增按钮
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView lab = new TextView(this);
        lab.setText("模板列表");
        lab.setTextColor(0xFF000000);
        lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        bar.addView(lab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button bAdd = new Button(this);
        bAdd.setText("+");
        bar.addView(bAdd, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 模板列表（可滚动）
        tplList = new LinearLayout(this);
        tplList.setOrientation(LinearLayout.VERTICAL);
        tplList.setPadding(0, dip(8), 0, dip(8));
        tplScroll = new ScrollView(this);
        tplScroll.addView(tplList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(tplScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bAdd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                addTplRow("", "");
                saveTemplates();
                if (tplScroll != null) tplScroll.post(new Runnable() {
                    @Override public void run() { tplScroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });

        for (String[] t : loadTemplates()) addTplRow(t[0], t[1]);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setView(root);
        b.setNegativeButton("关闭", null);
        tplDlg = b.create();
        tplDlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                saveTemplates();                        // 关闭前保存（含键入的标题/内容）
                tplList = null; tplScroll = null; tplDlg = null;
            }
        });
        tplDlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                Window w = tplDlg.getWindow();
                if (w == null) return;
                DisplayMetrics dm = getResources().getDisplayMetrics();
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.width = (int) (dm.widthPixels * 0.94f);
                lp.height = (int) (dm.heightPixels * 0.80f);
                w.setAttributes(lp);
            }
        });
        tplDlg.show();
    }

    // ---------------------------------------------------------------- 生命周期
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 隐藏系统 ActionBar / 标题栏（该栏只显示 "Shell"，纯占空间）
        if (getActionBar() != null) getActionBar().hide();
        setContentView(R.layout.shell);

        hsvOut     = (HorizontalScrollView) findViewById(R.id.hsv_shell_out);
        svOut      = (ScrollView) findViewById(R.id.sv_shell_out);
        tvOut      = (TextView) findViewById(R.id.tv_shell_out);
        etCmd      = (EditText) findViewById(R.id.et_shell_cmd);
        btnTpl     = (Button) findViewById(R.id.btn_shell_tpl);
        btnSend    = (Button) findViewById(R.id.btn_shell_send);
        btnCopy    = (Button) findViewById(R.id.btn_shell_copy);
        btnClear   = (Button) findViewById(R.id.btn_shell_clear);
        btnFontUp  = (Button) findViewById(R.id.btn_font_up);
        btnFontDown= (Button) findViewById(R.id.btn_font_down);
        cbWrap     = (CheckBox) findViewById(R.id.cb_wrap);

        // 恢复上次保存的显示偏好（自动换行、字号）
        SharedPreferences sp = pref();
        wrap = sp.getBoolean("wrap", true);
        fontSize = sp.getInt("font", 12);
        cbWrap.setChecked(wrap);
        tvOut.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
        applyWrap();

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onSend(); }
        });
        btnTpl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTemplates(); }
        });
        btnFontUp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                fontSize = Math.min(FONT_MAX, fontSize + 2);
                tvOut.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
                pref().edit().putInt("font", fontSize).apply();
            }
        });
        btnFontDown.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                fontSize = Math.max(FONT_MIN, fontSize - 2);
                tvOut.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
                pref().edit().putInt("font", fontSize).apply();
            }
        });
        cbWrap.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                wrap = checked; applyWrap();
                pref().edit().putBoolean("wrap", wrap).apply();
            }
        });
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return;
                cm.setPrimaryClip(ClipData.newPlainText("shell", plain.toString()));
                toast("Copied");
            }
        });
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                plain.setLength(0);   // 仅清空本地显示，不动服务日志缓冲（避免牵连主界面）
                render();
            }
        });

        bindService(new Intent(this, BleService.class), conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到本界面重新接管输出
        if (svc != null) svc.setUi(uiCb);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (svc != null) svc.clearUi(uiCb);
        svc = null;
        try { unbindService(conn); } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- 动作
    void onSend() {
        String c = etCmd.getText().toString();
        if (c.trim().length() == 0) { etCmd.setText(""); return; }   // 空行不发送（bash 风格）
        if (svc == null) { toast("Engine not ready; try again later"); return; }
        svc.run(c);                 // 命令回声与结果经 addLog → onLog 回流到终端
        P.pushHist(this, c.trim()); // 记录到发送历史（与主界面共享）
        // 发送后不清空输入框，命令文本保留，便于重复发送或在此基础上微调
    }

    void toast(final String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

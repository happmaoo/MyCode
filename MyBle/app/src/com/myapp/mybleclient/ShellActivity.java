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
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Layout;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
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
    Button btnSend, btnCopy, btnClear, btnFontUp, btnFontDown, btnQuick, btnHistUp, btnHistDown;
    CheckBox cbWrap;

    // ---- 命令面板（Quick Commands / History 双 Tab，和主界面同一套数据）----
    LinearLayout llCmdpad;
    ListView lvCmdpad;
    Button tabQuick, tabHist;
    ArrayAdapter<String> padAdapter;
    String curTab = "quick";
    View cmdRow;
    final android.graphics.Rect padRect = new android.graphics.Rect();
    final android.graphics.Rect rowRect = new android.graphics.Rect();
    final android.graphics.Rect btnRect = new android.graphics.Rect();

    BleService svc;                 // 绑定后拿到引擎

    // ---- 日志缓冲与渲染控制 ----
    StringBuilder plain = new StringBuilder();   // 原始文本（用于 Copy / 重置）
    int fontSize = 14;                          // 当前字号(sp)
    boolean wrap = true;                        // 自动换行
    static final int FONT_MIN = 12, FONT_MAX = 48, FONT_STEP = 2, CAP = 200000;
    boolean renderScheduled = false;
    final Handler h = new Handler(Looper.getMainLooper());

    // ---- 模板弹窗（已移除，改用主界面 Quick Commands）----
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
        @Override public void onDownloadState(boolean downloading) { }
        @Override public void onFileList(java.util.ArrayList<String> items) { }
        @Override public void onEditFileReady(final String path, final String remote, final String mode, final String sha) {
            if (!alive()) return;
            appendOut("\n[vi] 已下载 " + remote + " → 打开编辑器…\n");
            openEditor(path, remote, mode, sha);
        }
        @Override public void onEditUploaded(boolean ok, String msg) {
            if (!alive()) return;
            appendOut("\n[vi] " + msg + "\n");
            Toast.makeText(ShellActivity.this, msg, Toast.LENGTH_SHORT).show();
        }
    };

    // ---- vi 编辑会话：本地文件 / 远端路径 / 权限 / 原始 SHA ----
    String editLocalPath = null, editRemotePath = null, editMode = null, editSha = null;
    static final int REQ_EDIT = 2001;

    // ---- 历史命令导航：▼/▲ 在 P.histCmds 数组里上下游标 ----
    String[] histArr;       // 历史命令（最近在前，来自 P.histCmds）
    int histIdx = -1;       // 当前游标：-1=未进入历史；0=最近一条
    String histDraft = "";  // 进入历史前输入框里的草稿，▼ 越过后恢复

    /** 用配置的编辑器（或系统编辑器）打开本地编辑文件 */
    void openEditor(String localPath, String remote, String mode, String sha) {
        editLocalPath = localPath;
        editRemotePath = remote;
        editMode = mode;
        editSha = sha;
        Uri uri = EditFileProvider.uriFor(new File(localPath).getName());
        Intent it = new Intent(Intent.ACTION_EDIT);
        it.setDataAndType(uri, "text/plain");
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        String pkg = P.editorPkg(this), cls = P.editorCls(this);
        boolean explicit = pkg.length() > 0 && cls.length() > 0;
        if (explicit) it.setComponent(new ComponentName(pkg, cls));
        try {
            startActivityForResult(it, REQ_EDIT);
        } catch (Throwable t) {
            // 没有配置或组件无效：回落到系统选择器
            try {
                Intent pick = new Intent(Intent.ACTION_EDIT);
                pick.setDataAndType(uri, "text/plain");
                pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                startActivityForResult(Intent.createChooser(pick, "选择编辑器"), REQ_EDIT);
            } catch (Throwable t2) {
                appendOut("[vi] 无法打开编辑器: " + t2 + "\n");
                toast("无法打开编辑器: " + t2);
            }
        }
    }

    /** 编辑器返回：比对本地文件 SHA，有改动则回写服务端并恢复权限 */
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EDIT || editLocalPath == null) return;
        // 延迟一拍：部分编辑器在 onPause 后才把内容 flush 落盘
        h.postDelayed(new Runnable() {
            @Override public void run() {
                checkEditSaved();
            }
        }, 500);
    }

    /** 编辑器可能不返回 result（如按 Home 键再回来），onResume 也检查一次 */
    @Override
    protected void onResume() {
        super.onResume();
        if (svc != null) svc.setUi(uiCb);
        applyWrap();
        if (editLocalPath != null) {
            h.postDelayed(new Runnable() {
                @Override public void run() { checkEditSaved(); }
            }, 500);
        }
    }

    void checkEditSaved() {
        if (editLocalPath == null || editSha == null) return;
        try {
            FileInputStream in = new FileInputStream(editLocalPath);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String now = sha256(bos.toByteArray());
            if (now.equalsIgnoreCase(editSha)) {
                appendOut("[vi] 文件未改动，跳过回写\n");
            } else {
                appendOut("[vi] 检测到改动，回写 " + editRemotePath + " …\n");
                if (svc != null) svc.uploadEditedFile(editLocalPath, editRemotePath, editMode);
                else toast("引擎未就绪，回写失败");
            }
        } catch (Throwable t) {
            appendOut("[vi] 读取编辑文件失败: " + t + "\n");
        } finally {
            editLocalPath = null; editRemotePath = null; editMode = null; editSha = null;
        }
    }

    static String sha256(byte[] d) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(d);
            StringBuilder s = new StringBuilder();
            for (byte x : h) s.append(String.format("%02x", x & 0xFF));
            return s.toString();
        } catch (Throwable t) { return ""; }
    }

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
        if (!wrap) applyUnwrappedWidth();   // 不换行：宽度随内容变化重算
        scrollToBottom();
    }

    /** 滚到内容最底部：用 scrollTo 而非 fullScroll，避免把焦点转移给可聚焦的日志 TextView */
    void scrollToBottom() {
        if (svOut == null) return;
        svOut.post(new Runnable() {
            @Override public void run() {
                View child = svOut.getChildAt(0);
                if (child == null) return;
                int target = child.getHeight() - svOut.getHeight();
                svOut.scrollTo(0, Math.max(0, target));
            }
        });
    }

    /**
     * 应用自动换行 / 不换行。
     *
     * 不再依赖 match_parent / wrap_content 的测量结果：直接把 TextView 的宽度写成**精确像素**。
     *   换行   -> 宽度 = 可视区宽度，文字只能在可视区内折行；
     *   不换行 -> 宽度 = 最长一行需要的宽度，超出可视区的部分由外层 HorizontalScrollView 横向滚动。
     * 这样无论 ScrollView 给出什么测量规格，都不会出现“改了参数但看起来没变”。
     */
    void applyWrap() {
        if (tvOut == null || svOut == null) return;
        // 首次调用时还没布局完，等一次布局拿到可视宽度再算
        tvOut.post(new Runnable() {
            @Override public void run() { layoutForWrap(); }
        });
    }

    private int wrapRetry = 0;

    private void layoutForWrap() {
        int viewport = viewportWidth();
        if (viewport <= 0) {
            // 还没完成布局（onCreate 阶段），过一会儿再算
            if (wrapRetry < 8) {
                wrapRetry++;
                tvOut.postDelayed(new Runnable() {
                    @Override public void run() { layoutForWrap(); }
                }, 60);
            }
            return;
        }
        wrapRetry = 0;
        int pad = tvOut.getPaddingLeft() + tvOut.getPaddingRight();
        int width;
        if (wrap) {
            width = viewport;
        } else {
            width = Math.max(viewport, desiredContentWidth() + pad + dip(6));
        }
        tvOut.setHorizontallyScrolling(!wrap);
        tvOut.setSingleLine(false);
        tvOut.setMaxLines(Integer.MAX_VALUE);
        tvOut.setLayoutParams(new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        svOut.setLayoutParams(new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT));
        tvOut.requestLayout();
        svOut.requestLayout();
        if (hsvOut != null) hsvOut.scrollTo(0, 0);
        scrollToBottom();
    }

    /** 不换行时，内容变多后把宽度补足（不重置已滚动到的位置以外的状态） */
    void applyUnwrappedWidth() {
        if (wrap || tvOut == null || svOut == null) return;
        int viewport = viewportWidth();
        if (viewport <= 0) return;
        int pad = tvOut.getPaddingLeft() + tvOut.getPaddingRight();
        int width = Math.max(viewport, desiredContentWidth() + pad + dip(6));
        tvOut.setLayoutParams(new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        svOut.setLayoutParams(new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT));
        tvOut.requestLayout();
        svOut.requestLayout();
    }

    /** 可视区宽度（像素）：HorizontalScrollView 的实际宽度减去 padding */
    int viewportWidth() {
        if (hsvOut == null) return 0;
        int v = hsvOut.getWidth() - hsvOut.getPaddingLeft() - hsvOut.getPaddingRight();
        return v > 0 ? v : 0;
    }

    /** 不换行时最长一行需要的像素宽度（不含内边距）；会按 \n 逐行取最大值 */
    int desiredContentWidth() {
        if (tvOut == null) return 0;
        float textWidth;
        try {
            textWidth = Layout.getDesiredWidth(plain, tvOut.getPaint());
        } catch (Throwable t) {
            textWidth = 0;
        }
        return (int) Math.ceil(textWidth);
    }

    // ---------------------------------------------------------------- 命令面板（Quick / History 双 Tab）
    /** 展开/收起命令面板（默认收起，避免挤压日志区） */
    void toggleCmdPad() {
        if (llCmdpad == null) return;
        boolean open = llCmdpad.getVisibility() == View.VISIBLE;
        llCmdpad.setVisibility(open ? View.GONE : View.VISIBLE);
        btnQuick.setText(open ? "⌄CMD" : "⌃CMD");
        if (!open) refreshCmdPad();
    }

    /** 收起命令面板（点条目发送后调用），复位按钮文字 */
    void collapseCmdPad() {
        if (llCmdpad != null && llCmdpad.getVisibility() == View.VISIBLE) {
            llCmdpad.setVisibility(View.GONE);
            btnQuick.setText("⌄CMD");
        }
    }

    /** 切换 Tab：左=快捷命令（设置页维护），右=发送历史（自动记录） */
    void setTab(String tab) {
        curTab = tab;
        if (tabQuick == null || tabHist == null) return;
        tabQuick.setBackgroundColor(tab.equals("quick") ? 0xFFFFFFFF : 0xFFE0E0E0);
        tabHist.setBackgroundColor(tab.equals("hist") ? 0xFFFFFFFF : 0xFFE0E0E0);
        tabQuick.setTextColor(tab.equals("quick") ? 0xFF000000 : 0xFF888888);
        tabHist.setTextColor(tab.equals("hist") ? 0xFF000000 : 0xFF888888);
        refreshCmdPad();
    }

    /** 按当前 Tab 刷新列表（数据与主界面共用 P.quickCmds / P.histCmds） */
    void refreshCmdPad() {
        if (lvCmdpad == null) return;
        String[] items = curTab.equals("quick") ? P.quickCmds(this) : P.histCmds(this);
        if (padAdapter == null) {
            padAdapter = new ArrayAdapter<String>(this, R.layout.cmdpad_item,
                    new ArrayList<String>());
            lvCmdpad.setAdapter(padAdapter);
        }
        padAdapter.clear();
        if (items == null || items.length == 0) {
            padAdapter.add(curTab.equals("quick")
                    ? "(Quick commands empty; add them in Settings -> Quick Commands)"
                    : "(No commands sent yet)");
        } else {
            for (String it : items) padAdapter.add(it);
        }
        padAdapter.notifyDataSetChanged();
    }

    /** 面板/命令行以外的地方点一下 → 收起面板 */
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN
                && llCmdpad != null && llCmdpad.getVisibility() == View.VISIBLE) {
            llCmdpad.getGlobalVisibleRect(padRect);
            if (cmdRow != null) cmdRow.getGlobalVisibleRect(rowRect);
            if (btnQuick != null) btnQuick.getGlobalVisibleRect(btnRect);
            int x = (int) ev.getRawX(), y = (int) ev.getRawY();
            // CMD 按钮自己负责开/收，不能被这里先收起（否则 toggle 又把它展开，看起来“收不起来”）
            if (!padRect.contains(x, y) && !rowRect.contains(x, y) && !btnRect.contains(x, y)) {
                collapseCmdPad();
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /** ▲ 上一条历史：游标向“更早”移动 */
    void histPrev() {
        if (histArr == null || histArr.length == 0) histArr = P.histCmds(this);
        if (histArr.length == 0) { toast("暂无历史命令"); return; }
        if (histIdx == -1) { histDraft = etCmd.getText().toString(); histIdx = 0; }
        else if (histIdx < histArr.length - 1) histIdx++;
        etCmd.setText(histArr[histIdx]);
        etCmd.setSelection(etCmd.getText().length());
    }

    /** ▼ 下一条历史：游标向“更新”移动，越过最新一条恢复输入草稿 */
    void histNext() {
        if (histIdx < 0) return;
        histIdx--;
        if (histIdx < 0) {
            etCmd.setText(histDraft);
            etCmd.setSelection(etCmd.getText().length());
        } else {
            etCmd.setText(histArr[histIdx]);
            etCmd.setSelection(etCmd.getText().length());
        }
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
        btnQuick   = (Button) findViewById(R.id.btn_shell_quick);
        btnHistUp  = (Button) findViewById(R.id.btn_shell_hist_up);
        btnHistDown= (Button) findViewById(R.id.btn_shell_hist_down);
        btnSend    = (Button) findViewById(R.id.btn_shell_send);
        btnCopy    = (Button) findViewById(R.id.btn_shell_copy);
        btnClear   = (Button) findViewById(R.id.btn_shell_clear);
        btnFontUp  = (Button) findViewById(R.id.btn_font_up);
        btnFontDown= (Button) findViewById(R.id.btn_font_down);
        cbWrap     = (CheckBox) findViewById(R.id.cb_wrap);
        llCmdpad   = (LinearLayout) findViewById(R.id.ll_cmdpad_shell);
        lvCmdpad   = (ListView) findViewById(R.id.lv_cmdpad_shell);
        tabQuick   = (Button) findViewById(R.id.tab_shell_quick);
        tabHist    = (Button) findViewById(R.id.tab_shell_hist);
        cmdRow     = findViewById(R.id.ll_shell_cmdrow);

        // 恢复上次保存的显示偏好（自动换行、字号）
        SharedPreferences sp = pref();
        wrap = sp.getBoolean("wrap", true);
        fontSize = sp.getInt("font_sp", -1);
        if (fontSize < 0) {
            // 旧版把字号当 px 存（8~32），换成 sp 并夹到新范围
            float legacyPx = sp.getInt("font", 12);
            float spd = getResources().getDisplayMetrics().scaledDensity;
            fontSize = Math.round(legacyPx / (spd > 0 ? spd : 1f));
            fontSize = Math.max(FONT_MIN, Math.min(FONT_MAX, fontSize));
            pref().edit().putInt("font_sp", fontSize).apply();
        }
        cbWrap.setChecked(wrap);
        tvOut.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        applyWrap();

        btnSend.setOnClickListener(new View.OnClickListener() {
            // Send 也走静默路径：键盘开着就保持，没开就不要弹（只把焦点留在输入框）
            @Override public void onClick(View v) { onSend(true); }
        });
        btnQuick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleCmdPad(); }
        });
        tabQuick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setTab("quick"); }
        });
        tabHist.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setTab("hist"); }
        });
        lvCmdpad.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String c = (String) parent.getItemAtPosition(position);
                if (c != null && c.length() > 0 && !c.startsWith("(")) {   // 跳过占位提示行
                    etCmd.setText(c);
                    etCmd.setSelection(c.length());
                    histIdx = -1;
                    onSend(true);      // 直接发送，且不弹输入法
                    collapseCmdPad();  // 发完收起，露出日志
                }
            }
        });
        setTab("quick");
        btnHistUp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { histPrev(); }
        });
        btnHistDown.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { histNext(); }
        });
        btnFontUp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                fontSize = Math.min(FONT_MAX, fontSize + FONT_STEP);
                tvOut.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
                pref().edit().putInt("font_sp", fontSize).apply();
                applyWrap();   // 字号变了，不换行时的长行宽度也要重算
            }
        });
        btnFontDown.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                fontSize = Math.max(FONT_MIN, fontSize - FONT_STEP);
                tvOut.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
                pref().edit().putInt("font_sp", fontSize).apply();
                applyWrap();
            }
        });
        cbWrap.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                wrap = checked; applyWrap();
                pref().edit().putBoolean("wrap", wrap).apply();
                toast(wrap ? "自动换行：开" : "自动换行：关（可横向滚动）");
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
    protected void onDestroy() {
        super.onDestroy();
        if (svc != null) svc.clearUi(uiCb);
        svc = null;
        try { unbindService(conn); } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- 动作
    /** 从 vi/vim 命令行里解析出第一个文件路径（跳过 -R/+n 等选项），无路径返回 null */
    String viPath(String cmd) {
        String[] t = cmd.trim().split("\\s+");
        if (t.length < 2) return null;
        for (int i = 1; i < t.length; i++) {
            String a = t[i];
            if (a.startsWith("-") || a.startsWith("+")) continue;  // 选项 / +行号 / +/pattern
            return a;
        }
        return null;
    }

    void onSend() { onSend(false); }

    /** quiet=true：来自 CMD 面板点选，发送后只把焦点还给输入框，绝不主动弹输入法 */
    void onSend(boolean quiet) {
        String c = etCmd.getText().toString();
        if (c.trim().length() == 0) { etCmd.setText(""); return; }   // 空行不发送（bash 风格）
        if (svc == null) { toast("Engine not ready; try again later"); return; }

        // vi/vim 拦截：下载服务端文件 → 本地编辑器打开 → 返回时比对并回写
        String cmd = c.trim();
        String[] head = cmd.split("\\s+");
        if ("vi".equals(head[0]) || "vim".equals(head[0])) {
            String path = viPath(cmd);
            if (path == null) {
                appendOut("[vi] 用法: vi <服务端文件路径>\n");
                return;
            }
            if (!path.startsWith("/")) {
                String cwd = svc.serverCwd;
                if (cwd == null || cwd.length() == 0) cwd = "/";
                path = (cwd.endsWith("/") ? cwd : cwd + "/") + path;
            }
            appendOut("$ " + cmd + "\n");
            svc.editRemoteFile(path);
            P.pushHist(this, cmd);
            etCmd.setText("");      // 发送后清空输入框
            histIdx = -1;
            histArr = null;
            if (quiet) focusCmdInputQuiet(); else focusCmdInput();
            return;
        }

        svc.run(c);                 // 命令回声与结果经 addLog → onLog 回流到终端
        P.pushHist(this, c.trim()); // 记录到发送历史（与主界面共享）
        etCmd.setText("");          // 发送后清空输入框
        histIdx = -1;
        histArr = null;             // 下次翻历史时重新读取（含刚发送的命令）
        if (quiet) focusCmdInputQuiet(); else focusCmdInput();
    }

    /** 静默版：把焦点还给命令框但不弹输入法；键盘本来就是开着的话保持原状 */
    void focusCmdInputQuiet() {
        etCmd.postDelayed(new Runnable() {
            @Override public void run() {
                View cur = getCurrentFocus();
                if (cur != null && cur != etCmd) cur.clearFocus();
                etCmd.setFocusableInTouchMode(true);
                etCmd.requestFocus();
                etCmd.setSelection(etCmd.getText().length());
                final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm == null) return;
                if (imm.isAcceptingText()) return;          // 键盘已打开：不隐藏
                imm.hideSoftInputFromWindow(etCmd.getWindowToken(), 0);
                etCmd.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!imm.isAcceptingText())
                            imm.hideSoftInputFromWindow(etCmd.getWindowToken(), 0);
                    }
                }, 220);
            }
        }, 60);
    }

    /** 发送后把焦点还给命令框并唤起软键盘（点击 Send 会抢焦点，需 post 延迟重夺） */
    void focusCmdInput() {
        etCmd.postDelayed(new Runnable() {
            @Override public void run() {
                View cur = getCurrentFocus();
                if (cur != null && cur != etCmd) cur.clearFocus();
                etCmd.setFocusableInTouchMode(true);
                etCmd.requestFocus();
                etCmd.setSelection(etCmd.getText().length());
                final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(etCmd, InputMethodManager.SHOW_IMPLICIT);
                etCmd.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (imm != null && !imm.isAcceptingText())
                            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
                    }
                }, 180);
            }
        }, 60);
    }

    void toast(final String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

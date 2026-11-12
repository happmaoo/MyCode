package com.myapp.myfilemanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 设置页（MD 扁平风格）：每行列数、字体大小、列表名称字号/字型、图标主题（5 套可选，默认 elementary）、
 *  隐藏文件、Root、自定义命令、扩展名打开方式规则。 */
public class SettingsActivity extends Activity {

    private static final String ROOT_OFF = "普通权限运行；开启后可浏览 /system、/data 等系统目录";
    private static final String ROOT_ON = "✓ 已授权，复用同一 Root 会话";
    private static final String ROOT_LOST = "⚠ 授权已失效，请关闭后重新开启";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private int cols = 5;
    private int textLv = 1;
    private int theme = IconFactory.THEME_DEFAULT;
    private String listFont = "";
    private boolean rootOn, hiddenOn, syncing = false;
    private String scriptDir = "";
    private final ArrayList<Prefs.Cmd> cmds = new ArrayList<Prefs.Cmd>();
    private final ArrayList<Prefs.OpenRule> rules = new ArrayList<Prefs.OpenRule>();

    private TextView colNum, rootState, themeHint;
    private CheckBox rootCb, hiddenCb, debugCb;
    private ImageView themeFolderPreview, themeFilePreview;
    private LinearLayout cmdList, ruleList, rxBox;
    private final Button[] textBtns = new Button[3];
    private Button fontPickBtn;
    private TextView fontPreview;
    private Button themePickBtn;
    private EditText accentEdit;
    private View accentBlock;
    private String accentHex = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.ensureDefaults(this);
        IconFactory.attach(this);   // 预览彩色位图主题前先注入 AssetManager
        load();
        setContentView(buildUi());
        if (accentEdit != null && accentHex.length() > 0) accentEdit.setText(accentHex);
        if (accentEdit != null) applyAccentPreview();
        syncUi();
        renderCmds();
        renderRules();
    }

    private void load() {
        cols = Prefs.columns(this);
        textLv = Prefs.textLevel(this);
        theme = Prefs.iconTheme(this);
        listFont = Prefs.listFont(this);
        hiddenOn = Prefs.showHidden(this);
        rootOn = Prefs.rootEnabled(this);
        scriptDir = Prefs.scriptDir(this);
        accentHex = Prefs.accent(this);
        cmds.clear();
        cmds.addAll(Prefs.commands(this));
        rules.clear();
        rules.addAll(Prefs.openRules(this));
        IconFactory.setTheme(theme);
    }

    private void saveAll() {
        Prefs.setColumns(this, cols);
        Prefs.setTextLevel(this, textLv);
        Prefs.setIconTheme(this, theme);
        Prefs.setListFont(this, listFont);
        Prefs.setShowHidden(this, hiddenOn);
        Prefs.setRootEnabled(this, rootOn);
        Prefs.setScriptDir(this, scriptDir);
        Prefs.setAccent(this, accentHex);
        Prefs.setCommands(this, cmds);
        Prefs.setOpenRules(this, rules);
    }

    /** 实时预览：解析输入框十六进制，更新色块与全局着重色（Ui.PRIMARY）。 */
    private void applyAccentPreview() {
        String raw = accentEdit.getText().toString().trim();
        int color = Ui.parseAccent(raw, Ui.PRIMARY_DEFAULT);
        accentBlock.setBackgroundColor(color);
        Ui.PRIMARY = color;
        accentHex = raw;
    }

    // ==================== UI ====================

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // 顶栏
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Util.dp(this, 6), Util.dp(this, 8), Util.dp(this, 8), Util.dp(this, 8));
        Button back = Ui.flat(this, "‹ 返回", textLv);
        back.setTextColor(Ui.SUB);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        top.addView(back);
        TextView title = text("设置", 16, 18, 20, Ui.TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button save = Ui.flat(this, "保存", textLv);
        save.setTypeface(null, android.graphics.Typeface.BOLD);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveAll();
                toast("设置已保存");
                setResult(RESULT_OK);
                finish();
            }
        });
        top.addView(save);
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(Ui.line(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 1)));

        // 内容
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int cp = Util.dp(this, 14);
        content.setPadding(cp, cp, cp, cp);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ----- 显示 -----
        content.addView(section("显示"));

        LinearLayout colRow = new LinearLayout(this);
        colRow.setOrientation(LinearLayout.HORIZONTAL);
        colRow.setGravity(Gravity.CENTER_VERTICAL);
        colRow.addView(text("每行列数", 12, 13, 15, Ui.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button minus = Ui.flat(this, "−", textLv);
        minus.setTextSize(Ui.sp(textLv, 14, 16, 18));
        minus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { changeCols(-1); }
        });
        colRow.addView(minus);
        colNum = new TextView(this);
        colNum.setTextSize(Ui.sp(textLv, 15, 17, 19));
        colNum.setTextColor(Ui.PRIMARY);
        colNum.setGravity(Gravity.CENTER);
        colNum.setMinWidth(Util.dp(this, 40));
        colRow.addView(colNum);
        Button plus = Ui.flat(this, "+", textLv);
        plus.setTextSize(Ui.sp(textLv, 14, 16, 18));
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { changeCols(1); }
        });
        colRow.addView(plus);
        content.addView(colRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(line());

        LinearLayout textRow = new LinearLayout(this);
        textRow.setOrientation(LinearLayout.HORIZONTAL);
        textRow.setGravity(Gravity.CENTER_VERTICAL);
        textRow.addView(text("字体大小", 12, 13, 15, Ui.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String[] tl = {"小", "中", "大"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Button b = Ui.flat(this, tl[i], textLv);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Prefs.setTextLevel(SettingsActivity.this, idx);
                    recreate();   // 按新档位重建，设置页文字同步变化
                }
            });
            textRow.addView(b);
            textBtns[i] = b;
        }
        content.addView(textRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(line());

        // 列表名称统一字号（文件夹与文件共用一个选项）
        content.addView(spRow("列表名称字号"), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 列表字体（内置字型 + 系统安装的字体，点按弹出选择框）
        LinearLayout fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        fontRow.setGravity(Gravity.CENTER_VERTICAL);
        fontRow.addView(text("列表字体", 12, 13, 15, Ui.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        fontPickBtn = Ui.flat(this, "默认", textLv);
        fontPickBtn.setSingleLine(false);
        fontPickBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickFont(); }
        });
        fontRow.addView(fontPickBtn);
        content.addView(fontRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(line());

        // 字体预览（实时反映所选字体）
        fontPreview = new TextView(this);
        fontPreview.setText("AaBbCc 文件管理 0123 中文预览");
        fontPreview.setTextSize(Ui.sp(textLv, 14, 16, 18));
        fontPreview.setTextColor(Ui.TEXT);
        fontPreview.setPadding(Util.dp(this, 16), Util.dp(this, 4), Util.dp(this, 16), Util.dp(this, 4));
        content.addView(fontPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(line());
        refreshFontLabel();

        // 着重色：左侧色块显示当前颜色，右侧输入框可输入十六进制（如 FD5555），实时预览
        content.addView(section("着重色"));
        LinearLayout accentRow = new LinearLayout(this);
        accentRow.setOrientation(LinearLayout.HORIZONTAL);
        accentRow.setGravity(Gravity.CENTER_VERTICAL);
        accentBlock = new View(this);
        int abSize = Util.dp(this, 36);
        accentBlock.setLayoutParams(new LinearLayout.LayoutParams(abSize, abSize));
        accentBlock.setBackgroundColor(Ui.PRIMARY);
        accentRow.addView(accentBlock);
        View abGap = new View(this);
        abGap.setLayoutParams(new LinearLayout.LayoutParams(Util.dp(this, 12), 1));
        accentRow.addView(abGap);
        accentEdit = new EditText(this);
        accentEdit.setHint("如 FD5555");
        accentEdit.setSingleLine(true);
        accentEdit.setTextColor(Ui.TEXT);
        accentEdit.setHintTextColor(Ui.HINT);
        accentEdit.setTextSize(Ui.sp(textLv, 12, 13, 15));
        accentEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        accentEdit.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(8)});
        accentRow.addView(accentEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button accentReset = Ui.flat(this, "默认", textLv);
        accentReset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                accentHex = "";
                accentEdit.setText("");
                applyAccentPreview();
            }
        });
        accentRow.addView(accentReset);
        content.addView(accentRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView accentHint = text("输入十六进制颜色值（不含 #，如 FF5555 / 00897B），留空为默认色", 11, 12, 14, Ui.SUB);
        accentHint.setPadding(Util.dp(this, 0), Util.dp(this, 4), 0, 0);
        content.addView(accentHint);
        content.addView(line());
        accentEdit.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { applyAccentPreview(); }
            public void afterTextChanged(android.text.Editable e) {}
        });

        // ----- 调试 -----
        content.addView(section("调试"));
        debugCb = new CheckBox(this);
        debugCb.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        debugCb.setText("启动时显示接收到的全部参数（打开方式/分享排查用）");
        debugCb.setTextSize(Ui.sp(textLv, 12, 13, 15));
        debugCb.setTextColor(Ui.TEXT);
        content.addView(debugCb);
        debugCb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (syncing) return;
                Prefs.setDebugRx(SettingsActivity.this, on);
                toast(on ? "已开启：下次启动/接收外部文件时会弹窗显示参数"
                         : "已关闭调试参数显示");
            }
        });
        content.addView(line());

        hiddenCb = new CheckBox(this);
        hiddenCb.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        hiddenCb.setText("显示隐藏文件（以 . 开头）");
        hiddenCb.setTextSize(Ui.sp(textLv, 12, 13, 15));
        hiddenCb.setTextColor(Ui.TEXT);
        content.addView(hiddenCb);
        hiddenCb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (syncing) return;
                hiddenOn = on;
                Prefs.setShowHidden(SettingsActivity.this, on);
            }
        });
        content.addView(line());

        // ----- 图标主题 -----
        content.addView(section("图标主题"));
        themePickBtn = Ui.flat(this, "", textLv);
        themePickBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        themePickBtn.setTextColor(Ui.PRIMARY);
        themePickBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showThemeDialog(); }
        });
        content.addView(themePickBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout previewRow = new LinearLayout(this);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        previewRow.setPadding(0, Util.dp(this, 8), 0, Util.dp(this, 4));
        int pv2 = Util.dp(this, 48);
        themeFolderPreview = new ImageView(this);
        themeFolderPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewRow.addView(themeFolderPreview, new LinearLayout.LayoutParams(pv2, pv2));
        themeFilePreview = new ImageView(this);
        themeFilePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams fplp = new LinearLayout.LayoutParams(pv2, pv2);
        fplp.setMargins(Util.dp(this, 16), 0, 0, 0);
        previewRow.addView(themeFilePreview, fplp);
        TextView pvLabel = text("预览 · 返回主页面生效", 11, 12, 14, Ui.SUB);
        pvLabel.setPadding(Util.dp(this, 16), 0, 0, 0);
        previewRow.addView(pvLabel);
        content.addView(previewRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        themeHint = note("");
        content.addView(themeHint);
        content.addView(line());

        // ----- Root -----
        content.addView(section("Root 权限"));
        rootCb = new CheckBox(this);
        rootCb.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        rootCb.setText("启用 Root 模式");
        rootCb.setTextSize(Ui.sp(textLv, 12, 13, 15));
        rootCb.setTextColor(Ui.TEXT);
        content.addView(rootCb);
        rootState = note("");
        content.addView(rootState);
        rootCb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (syncing) return;
                if (on) probeRoot(); else disableRoot();
            }
        });
        content.addView(line());

        // ----- 自定义命令 -----
        content.addView(section("自定义命令"));

        cmdList = new LinearLayout(this);
        cmdList.setOrientation(LinearLayout.VERTICAL);
        content.addView(cmdList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button add = Ui.flat(this, "＋ 添加命令", textLv);
        add.setTypeface(null, android.graphics.Typeface.BOLD);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { editCmd(-1); }
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.setMargins(0, Util.dp(this, 6), 0, Util.dp(this, 6));
        content.addView(add, addLp);

        // ----- 接收文件时执行的命令 -----
        content.addView(section("接收文件时执行的命令"));
        rxBox = new LinearLayout(this);
        rxBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(rxBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button editRx = Ui.flat(this, "编辑接收命令", textLv);
        editRx.setTypeface(null, android.graphics.Typeface.BOLD);
        editRx.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { editRxCmd(); }
        });
        LinearLayout.LayoutParams erLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        erLp.setMargins(0, Util.dp(this, 6), 0, Util.dp(this, 6));
        content.addView(editRx, erLp);
        renderRx();
        content.addView(line());

        // ----- 扩展名打开方式 -----
        content.addView(section("扩展名打开方式"));
        content.addView(note("按扩展名指定 MIME 或直接用某个应用打开；底部「打开」按钮始终弹系统选择框。"));

        ruleList = new LinearLayout(this);
        ruleList.setOrientation(LinearLayout.VERTICAL);
        content.addView(ruleList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button addRule = Ui.flat(this, "＋ 添加规则", textLv);
        addRule.setTypeface(null, android.graphics.Typeface.BOLD);
        addRule.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { editRule(-1); }
        });
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arLp.setMargins(0, Util.dp(this, 6), 0, Util.dp(this, 6));
        content.addView(addRule, arLp);
        content.addView(note("扩展名不带点；留空＝系统自动处理，后添加的同名规则优先。"));
        content.addView(line());

        // ----- 自定义脚本（侧栏 Bash 分类） -----
        content.addView(section("自定义脚本"));
        content.addView(note("在此填写一个目录，APP 启动后自动加载其中所有 .sh 脚本；\n"
                + "若脚本前 5 行含「#myfileman_script:标题」，则把冒号后的文字作为侧栏标题。"));
        LinearLayout scriptRow = new LinearLayout(this);
        scriptRow.setOrientation(LinearLayout.HORIZONTAL);
        scriptRow.setGravity(Gravity.CENTER_VERTICAL);
        scriptRow.addView(text("脚本目录", 12, 13, 15, Ui.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final EditText scriptDirEdit = new EditText(this);
        scriptDirEdit.setText(scriptDir);
        scriptDirEdit.setHint("/sdcard/scripts");
        scriptDirEdit.setSingleLine(true);
        scriptDirEdit.setTextSize(Ui.sp(textLv, 12, 13, 15));
        scriptDirEdit.setPadding(Util.dp(this, 8), Util.dp(this, 6), Util.dp(this, 8), Util.dp(this, 6));
        scriptDirEdit.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { scriptDir = s.toString().trim(); }
            public void afterTextChanged(android.text.Editable e) {}
        });
        scriptRow.addView(scriptDirEdit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scriptRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(line());

        return root;
    }

    private TextView text(String s, float s0, float m, float l, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(Ui.sp(textLv, s0, m, l));
        t.setTextColor(color);
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 10, 11, 13, Ui.SUB);
        t.setPadding(0, Util.dp(this, 4), 0, Util.dp(this, 8));
        return t;
    }

    private View line() {
        View v = Ui.line(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 1)));
        return v;
    }

    private View section(String s) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Util.dp(this, 8), 0, 0);
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(Ui.sp(textLv, 11, 12, 14));
        t.setTextColor(Color.WHITE);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setBackgroundColor(Ui.PRIMARY);
        t.setPadding(Util.dp(this, 12), Util.dp(this, 8),
                Util.dp(this, 12), Util.dp(this, 8));
        box.addView(t);
        return box;
    }

    /** 一行「标签 − 数值 +」，直接读写列表名称字号，即时保存。 */
    private View spRow(String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(label, 12, 13, 15, Ui.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int init = Prefs.listNameSp(this);
        final TextView num = new TextView(this);
        num.setText(init + " sp");
        num.setTextSize(Ui.sp(textLv, 14, 16, 18));
        num.setTextColor(Ui.PRIMARY);
        num.setGravity(Gravity.CENTER);
        num.setMinWidth(Util.dp(this, 56));
        row.addView(num);

        Button minus = Ui.flat(this, "−", textLv);
        minus.setTextSize(Ui.sp(textLv, 14, 16, 18));
        minus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { changeSp(-1, num); }
        });
        row.addView(minus);
        Button plus = Ui.flat(this, "+", textLv);
        plus.setTextSize(Ui.sp(textLv, 14, 16, 18));
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { changeSp(1, num); }
        });
        row.addView(plus);
        return row;
    }

    private void changeSp(int delta, TextView num) {
        int v = Math.max(8, Math.min(24, Prefs.listNameSp(this) + delta));
        Prefs.setListNameSp(this, v);
        num.setText(v + " sp");
    }

    /** 构建「Root 挂载方式」三选控件：自动探测 / 强制 mount-master / 普通 su。 */
    /** 刷新「列表字体」按钮文案与预览字体的显示。 */
    private void refreshFontLabel() {
        if (fontPickBtn != null) fontPickBtn.setText(fontDisplayName(listFont));
        if (fontPreview != null) fontPreview.setTypeface(typefaceFor(listFont));
    }

    /** 把字体 id 转成可读名称。 */
    private static String fontDisplayName(String id) {
        if (id == null || id.isEmpty()) return "默认（系统默认）";
        if ("serif".equals(id)) return "衬线 Serif";
        if ("monospace".equals(id)) return "等宽 Monospace";
        if ("bold".equals(id)) return "粗体 Bold";
        File f = new File(id);
        String n = f.getName().replaceAll("(?i)\\.(ttf|otf|ttc)$", "");
        return "系统：" + n;
    }

    /** 字体 id → Typeface（内置字型或系统字体文件）。 */
    private static Typeface typefaceFor(String id) {
        if (id == null || id.isEmpty()) return Typeface.DEFAULT;
        if ("serif".equals(id)) return Typeface.SERIF;
        if ("monospace".equals(id)) return Typeface.MONOSPACE;
        if ("bold".equals(id)) return Typeface.DEFAULT_BOLD;
        try {
            Typeface t = Typeface.createFromFile(id);
            if (t != null) return t;
        } catch (Throwable ignored) {}
        return Typeface.DEFAULT;
    }

    /** 字体选择弹窗：内置字型 + 系统 /system/fonts 下所有 ttf/otf/ttc 字体文件。 */
    private void pickFont() {
        final ArrayList<FontItem> items = new ArrayList<FontItem>();
        items.add(new FontItem("", "默认（系统默认）"));
        items.add(new FontItem("serif", "衬线 Serif"));
        items.add(new FontItem("monospace", "等宽 Monospace"));
        items.add(new FontItem("bold", "粗体 Bold"));
        items.addAll(listSystemFonts());
        items.add(new FontItem("__CUSTOM__", "自定义路径…"));

        ListView lv = new ListView(this);
        lv.setAdapter(new BaseAdapter() {
            public int getCount() { return items.size(); }
            public Object getItem(int p) { return items.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View cv, ViewGroup parent) {
                TextView tv = cv instanceof TextView ? (TextView) cv : new TextView(SettingsActivity.this);
                FontItem it = items.get(p);
                tv.setText(it.name);
                tv.setTextSize(16);
                tv.setPadding(Util.dp(SettingsActivity.this, 16), Util.dp(SettingsActivity.this, 12),
                        Util.dp(SettingsActivity.this, 16), Util.dp(SettingsActivity.this, 12));
                tv.setTextColor(Ui.TEXT);
                tv.setTypeface(typefaceFor(it.id));
                return tv;
            }
        });
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("列表字体")
                .setView(lv)
                .setNegativeButton("取消", null)
                .show();
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> av, View v, int pos, long id) {
                final String idSel = items.get(pos).id;
                if ("__CUSTOM__".equals(idSel)) {
                    d.dismiss();
                    promptCustomFont();
                    return;
                }
                listFont = idSel;
                Prefs.setListFont(SettingsActivity.this, listFont);
                refreshFontLabel();
                d.dismiss();
            }
        });
    }

    /** 自定义字体路径：弹输入框让用户填 /data/local/myfont.ttf 之类，校验后应用。 */
    private void promptCustomFont() {
        final EditText et = new EditText(this);
        et.setHint("/data/local/myfont.ttf");
        et.setSingleLine(true);
        et.setTextSize(14);
        et.setPadding(Util.dp(this, 8), Util.dp(this, 8), Util.dp(this, 8), Util.dp(this, 8));
        // 预填当前已是路径的字体，方便修改
        if (listFont != null && !listFont.isEmpty()
                && !"serif".equals(listFont) && !"monospace".equals(listFont)
                && !"bold".equals(listFont)) {
            et.setText(listFont);
        }
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("自定义字体路径")
                .setMessage("输入 .ttf/.otf/.ttc 字体文件的完整路径\n"
                        + "（已开启 Root 时，普通权限读不到的字体也会自动用 Root 复制后加载）")
                .setView(et)
                .setPositiveButton("应用", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface di, int w) {
                        applyCustomFont(et.getText().toString().trim());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 校验自定义字体路径：存在 + 扩展名合法 + 可解析为 Typeface，通过则保存。 */
    private void applyCustomFont(String path) {
        if (path == null || path.isEmpty()) {
            toast("路径不能为空");
            return;
        }
        if (!path.toLowerCase(java.util.Locale.US).matches(".*\\.(ttf|otf|ttc)$")) {
            toast("仅支持 .ttf / .otf / .ttc 字体文件");
            return;
        }
        File f = new File(path);
        if (!f.exists() || !f.isFile()) {
            // 普通方式访问不到：若已开启 Root，尝试把字体复制到本地缓存再加载
            if (Fs.rootMode && Fs.rootCanAccess(path, false)) {
                File dst = new File(getFilesDir(), "fonts/"
                        + System.currentTimeMillis() + "_" + new File(path).getName());
                if (Fs.rootCopy(path, dst.getAbsolutePath())) {
                    loadFontFrom(dst);
                    toast("已通过 Root 加载字体：" + dst.getName());
                    return;
                }
            }
            toast("文件不存在：" + path);
            return;
        }
        loadFontFrom(f);
    }

    /** 从本地文件加载字体并保存（普通路径与 Root 复制后的本地副本共用）。 */
    private void loadFontFrom(File f) {
        Typeface t = null;
        try { t = Typeface.createFromFile(f); } catch (Throwable ignored) {}
        if (t == null) {
            toast("无法加载该字体文件（可能已损坏或非字体）");
            return;
        }
        listFont = f.getAbsolutePath();
        Prefs.setListFont(SettingsActivity.this, listFont);
        refreshFontLabel();
        toast("已应用自定义字体：" + f.getName());
    }

    /** 递归收集 /system/fonts 下的字体文件（排除明显非界面的）。 */
    private static ArrayList<FontItem> listSystemFonts() {
        ArrayList<FontItem> out = new ArrayList<FontItem>();
        collectFonts(new File("/system/fonts"), out);
        // 按名称排序，方便浏览
        java.util.Collections.sort(out, new java.util.Comparator<FontItem>() {
            public int compare(FontItem a, FontItem b) { return a.name.compareToIgnoreCase(b.name); }
        });
        return out;
    }

    private static void collectFonts(File dir, ArrayList<FontItem> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collectFonts(f, out);
            else {
                String n = f.getName().toLowerCase(java.util.Locale.US);
                if (n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc")) {
                    String disp = f.getName().replaceAll("(?i)\\.(ttf|otf|ttc)$", "");
                    out.add(new FontItem(f.getAbsolutePath(), "系统：" + disp));
                }
            }
        }
    }

    /** 字体选择项。 */
    private static class FontItem {
        final String id, name;
        FontItem(String i, String n) { id = i; name = n; }
    }

    // ==================== 扩展名打开方式规则 ====================

    private void renderRules() {
        if (ruleList == null) return;
        ruleList.removeAllViews();
        if (rules.isEmpty()) {
            ruleList.addView(note("（暂无规则，点下方「＋ 添加规则」新建）"));
            return;
        }
        for (int i = 0; i < rules.size(); i++) {
            final int idx = i;
            final Prefs.OpenRule r = rules.get(i);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, Util.dp(this, 8), 0, Util.dp(this, 8));

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView ext = text("." + r.ext, 12, 13, 15, Ui.TEXT);
            ext.setTypeface(null, android.graphics.Typeface.BOLD);
            head.addView(ext, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button edit = Ui.flat(this, "编辑", textLv);
            edit.setTextColor(Ui.SUB);
            edit.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { editRule(idx); }
            });
            head.addView(edit);
            Button del = Ui.flat(this, "删除", textLv);
            del.setTextColor(Ui.SUB);
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { confirmDeleteRule(idx); }
            });
            head.addView(del);
            item.addView(head);

            TextView desc = text(describeRule(r), 10, 11, 12, Ui.SUB);
            desc.setTypeface(android.graphics.Typeface.MONOSPACE);
            desc.setSingleLine(true);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            desc.setPadding(0, Util.dp(this, 3), 0, Util.dp(this, 2));
            item.addView(desc);
            item.addView(line());
            ruleList.addView(item);
        }
    }

    private String describeRule(Prefs.OpenRule r) {
        String mime = (r.mime == null || r.mime.length() == 0) ? "系统推断 MIME" : r.mime;
        String pkg = (r.pkg == null || r.pkg.length() == 0) ? "系统选择框" : "直接打开 " + r.pkg;
        return mime + "   ·   " + pkg;
    }

    private void confirmDeleteRule(final int idx) {
        new AlertDialog.Builder(this)
                .setTitle("删除规则")
                .setMessage("确定删除 ." + rules.get(idx).ext + " 的打开方式规则？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        rules.remove(idx);
                        Prefs.setOpenRules(SettingsActivity.this, rules);
                        renderRules();
                        toast("已删除");
                    }
                }).show();
    }

    private void editRule(final int idx) {
        final boolean isNew = idx < 0;
        final Prefs.OpenRule old = isNew ? null : rules.get(idx);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Util.dp(this, 16);
        box.setPadding(p, Util.dp(this, 8), p, 0);

        final EditText extEt = new EditText(this);
        extEt.setHint("扩展名，如 txt / apk（不带点）");
        extEt.setSingleLine(true);
        extEt.setTextSize(Ui.sp(textLv, 12, 13, 15));
        if (old != null) extEt.setText(old.ext);
        box.addView(extEt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText mimeEt = new EditText(this);
        mimeEt.setHint("MIME 类型（可选），如 text/plain");
        mimeEt.setSingleLine(true);
        mimeEt.setTextSize(Ui.sp(textLv, 12, 13, 15));
        if (old != null) mimeEt.setText(old.mime);
        box.addView(mimeEt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText pkgEt = new EditText(this);
        pkgEt.setHint("应用包名（可选），如 com.example.editor");
        pkgEt.setSingleLine(true);
        pkgEt.setTextSize(Ui.sp(textLv, 12, 13, 15));
        if (old != null) pkgEt.setText(old.pkg);
        box.addView(pkgEt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(isNew ? "添加打开方式规则" : "编辑规则")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String e = extEt.getText().toString().trim();
                        while (e.startsWith(".")) e = e.substring(1);
                        if (e.isEmpty()) { toast("请填写扩展名"); return; }
                        String m = mimeEt.getText().toString().trim();
                        String pk = pkgEt.getText().toString().trim();
                        Prefs.OpenRule r = new Prefs.OpenRule(e, m, pk);
                        if (isNew) rules.add(r); else rules.set(idx, r);
                        Prefs.setOpenRules(SettingsActivity.this, rules);
                        renderRules();
                        toast("已保存");
                    }
                }).show();
    }

    // ==================== 状态同步 ====================

    private void syncUi() {
        paintCols();
        paintTextBtns();
        refreshFontLabel();
        paintTheme();
        syncing = true;
        hiddenCb.setChecked(hiddenOn);
        rootCb.setChecked(rootOn);
        debugCb.setChecked(Prefs.debugRx(this));
        syncing = false;
        rootState.setText(!rootOn ? ROOT_OFF
                : (Fs.rootSessionAlive() ? ROOT_ON : ROOT_LOST));
    }

    private void changeCols(int delta) {
        cols = Math.max(2, Math.min(10, cols + delta));
        Prefs.setColumns(this, cols);
        paintCols();
    }

    private void paintCols() {
        colNum.setText(String.valueOf(cols));
    }

    private void paintTextBtns() {
        for (int i = 0; i < 3; i++) {
            textBtns[i].setTextColor(i == textLv ? Ui.PRIMARY : Ui.SUB);
            textBtns[i].setTypeface(null, i == textLv
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    /** 更新当前主题按钮文字、实时预览图标与说明。 */
    private void paintTheme() {
        String cur = IconFactory.THEME_NAMES[theme];
        themePickBtn.setText("当前：" + cur + "  ▾  点击更换");
        int s = Util.dp(this, 48);
        themeFolderPreview.setImageBitmap(IconFactory.get(
                new FileItem("文件夹", "/文件夹", true, 0, 0), s));
        themeFilePreview.setImageBitmap(IconFactory.get(
                new FileItem("文档.txt", "/文档.txt", false, 2048, 0), s));
        themeHint.setText(IconFactory.THEME_DESC[theme]);
    }

    /** 单选对话框列出全部主题，点选即保存、切换并刷新预览。 */
    private void showThemeDialog() {
        new AlertDialog.Builder(this)
                .setTitle("选择图标主题")
                .setSingleChoiceItems(IconFactory.THEME_NAMES, theme,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                theme = which;
                                Prefs.setIconTheme(SettingsActivity.this, theme);
                                IconFactory.setTheme(theme);
                                paintTheme();
                                d.dismiss();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void probeRoot() {
        rootState.setText("正在获取 Root 授权…");
        io.execute(new Runnable() {
            public void run() {
                // 建立并复用持久 su 会话，而不是每条命令都 su -c
                final boolean ok = Fs.ensureRootSession(30000);
                ui.post(new Runnable() {
                    public void run() {
                        rootOn = ok;
                        Prefs.setRootEnabled(SettingsActivity.this, rootOn);
                        Fs.rootMode = ok;
                        if (!ok) {
                            syncing = true;
                            rootCb.setChecked(false);
                            syncing = false;
                            rootState.setText("✗ 未获取到 Root 权限");
                            toast("未获取到 Root 权限");
                        } else {
                            rootState.setText(ROOT_ON);
                            toast("Root 模式已开启");
                        }
                    }
                });
            }
        });
    }

    private void disableRoot() {
        rootOn = false;
        Fs.closeRootSession();
        Fs.rootMode = false;
        rootState.setText(ROOT_OFF);
        Prefs.setRootEnabled(this, false);
    }

    // ==================== 自定义命令管理 ====================

    private void renderCmds() {
        cmdList.removeAllViews();
        if (cmds.isEmpty()) {
            cmdList.addView(note("（暂无命令，点下方「＋ 添加命令」新建）"));
            return;
        }
        for (int i = 0; i < cmds.size(); i++) {
            final int idx = i;
            final Prefs.Cmd c = cmds.get(i);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, Util.dp(this, 8), 0, Util.dp(this, 8));

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(c.name, 12, 13, 15, Ui.TEXT);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (c.optRoot()) {
                TextView tag = text("ROOT", 9, 10, 11, Ui.PRIMARY);
                tag.setTypeface(null, android.graphics.Typeface.BOLD);
                head.addView(tag);
            }
            if (c.optAutoClose()) {
                TextView tag = text("自动关闭", 9, 10, 11, Ui.PRIMARY);
                tag.setTypeface(null, android.graphics.Typeface.BOLD);
                head.addView(tag);
            }
            int[] sz = c.optSize();
            if (sz != null) {
                TextView tag = text("弹窗 " + sz[0] + "x" + sz[1], 9, 10, 11, Ui.PRIMARY);
                tag.setTypeface(null, android.graphics.Typeface.BOLD);
                head.addView(tag);
            }
            item.addView(head);

            TextView tmpl = text(c.body(), 10, 11, 12, Ui.SUB);
            tmpl.setTypeface(android.graphics.Typeface.MONOSPACE);
            tmpl.setSingleLine(true);
            tmpl.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            tmpl.setPadding(0, Util.dp(this, 3), 0, Util.dp(this, 2));
            item.addView(tmpl);

            LinearLayout ops = new LinearLayout(this);
            ops.setOrientation(LinearLayout.HORIZONTAL);
            Button edit = Ui.flat(this, "编辑", textLv);
            edit.setTextColor(Ui.SUB);
            edit.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { editCmd(idx); }
            });
            ops.addView(edit);
            Button del = Ui.flat(this, "删除", textLv);
            del.setTextColor(Ui.SUB);
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { confirmDelete(idx); }
            });
            ops.addView(del);
            item.addView(ops);

            item.addView(line());
            cmdList.addView(item);
        }
    }

    private void confirmDelete(final int idx) {
        new AlertDialog.Builder(this)
                .setTitle("删除命令")
                .setMessage("确定删除「" + cmds.get(idx).name + "」？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        cmds.remove(idx);
                        Prefs.setCommands(SettingsActivity.this, cmds);
                        renderCmds();
                        toast("已删除");
                    }
                }).show();
    }

    private void editCmd(final int idx) {
        final boolean isNew = idx < 0;
        final Prefs.Cmd old = isNew ? null : cmds.get(idx);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Util.dp(this, 16);
        box.setPadding(p, Util.dp(this, 8), p, 0);

        final EditText nameEt = new EditText(this);
        nameEt.setHint("命令名称，如：安装 APK");
        nameEt.setSingleLine(true);
        nameEt.setTextSize(Ui.sp(textLv, 12, 13, 15));
        if (old != null) nameEt.setText(old.name);
        box.addView(nameEt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText tmplEt = new EditText(this);
        tmplEt.setHint("命令模板，如：pm install -r %f");
        tmplEt.setTextSize(Ui.sp(textLv, 12, 13, 15));
        tmplEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        tmplEt.setSingleLine(false);
        tmplEt.setMinLines(3);
        tmplEt.setGravity(Gravity.TOP);
        tmplEt.setTypeface(android.graphics.Typeface.MONOSPACE);
        if (old != null) {
            // 旧版命令用字段记录选项：编辑时把选项行补到前 5 行，让新格式生效；已含选项行则原样
            if (old.hasOptions()) tmplEt.setText(old.tmpl);
            else tmplEt.setText("#options://root:" + (old.root ? 1 : 0)
                    + "/printown:" + (old.showOrig ? 1 : 0)
                    + "/autoclose:" + (old.autoClose ? 1 : 0) + "/ena:2/\n" + old.tmpl);
        } else {
            tmplEt.setText("#options://root:0/printown:1/autoclose:0/ena:2/\n");
        }
        box.addView(tmplEt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(note("%n 文件名   %f 文件路径   %d 目录路径   %F 全部文件路径\n"
                + "plugin:%pid:插件id  例：%pid:archive7z %F 用压缩包插件打开选中文件；%pid:imageviewer %F 用图片查看器打开\n"
                + "选项写在前 5 行内，格式：#options://root:0/printown:1/autoclose:0/ena:1/\n"
                + "  root:1 以 Root 方式执行（强制 mount-master）；printown:1 在结果中输出原命令；autoclose:1 成功后自动关闭窗口\n"
                + "  ena:1 工具栏常启用 / 2 仅选中 1 个文件时启用 / 3 选中 1 个以上文件或文件夹（可混合）时启用\n"
                + "  size:宽x高 用固定尺寸弹窗替代全屏输出窗口（右上角 X 与下方居中「确定」按钮关闭），如 size:500x500\n"
                + "  缺省选项取默认值（root:0 / printown:1 / autoclose:0 / ena:2）；命令里不要再写 su，直接写原生命令即可。"));

        new AlertDialog.Builder(this)
                .setTitle(isNew ? "添加自定义命令" : "编辑命令")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = nameEt.getText().toString().trim();
                        String t = tmplEt.getText().toString().trim();
                        if (n.isEmpty()) { toast("请填写命令名称"); return; }
                        if (t.isEmpty()) { toast("请填写命令模板"); return; }
                        Prefs.Cmd c = new Prefs.Cmd(n, t);   // 选项已写在命令文本前 5 行的 #options:// 中
                        if (isNew) cmds.add(c); else cmds.set(idx, c);
                        Prefs.setCommands(SettingsActivity.this, cmds);
                        renderCmds();
                        toast("已保存");
                    }
                }).show();
    }

    private void renderRx() {
        if (rxBox == null) return;
        rxBox.removeAllViews();
        Prefs.Cmd c = Prefs.rxCommand(this);
        if (c == null || c.tmpl == null || c.tmpl.trim().isEmpty()) {
            rxBox.addView(note("（未设置，收到文件时不会自动执行命令）"));
            return;
        }
        TextView tmpl = text(c.tmpl, 10, 11, 12, Ui.SUB);
        tmpl.setTypeface(android.graphics.Typeface.MONOSPACE);
        tmpl.setSingleLine(true);
        tmpl.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        tmpl.setPadding(0, Util.dp(this, 3), 0, Util.dp(this, 2));
        rxBox.addView(tmpl);
        if (c.showOrig) rxBox.addView(note("结果中显示原命令"));
        if (c.autoClose) rxBox.addView(note("执行成功后自动关闭结果窗口"));
    }

    private void editRxCmd() {
        Prefs.Cmd old = Prefs.rxCommand(this);
        String oldTmpl = (old != null && old.tmpl != null) ? old.tmpl : "";
        boolean oldShow = old != null && old.showOrig;
        boolean oldAuto = old != null && old.autoClose;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Util.dp(this, 16);
        box.setPadding(p, Util.dp(this, 8), p, 0);

        final EditText tmplEt = new EditText(this);
        tmplEt.setHint("命令模板，如：cp %f /sdcard/Received/");
        tmplEt.setTextSize(Ui.sp(textLv, 12, 13, 15));
        tmplEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        tmplEt.setSingleLine(false);
        tmplEt.setMinLines(3);
        tmplEt.setGravity(Gravity.TOP);
        tmplEt.setTypeface(android.graphics.Typeface.MONOSPACE);
        tmplEt.setText(oldTmpl);
        box.addView(tmplEt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(note("%n 文件名   %f 文件路径   %d 目录路径   %F 全部文件路径\n"
                + "plugin:%pid:插件id  例：%pid:archive7z %F 用压缩包插件打开选中文件；%pid:imageviewer %F 用图片查看器打开\n"
                + "Root: su --mount-master -c 'ls -l %f'"));

        final CheckBox showCmdBox = new CheckBox(this);
        showCmdBox.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        showCmdBox.setText("在结果中输出原命令");
        showCmdBox.setTextSize(Ui.sp(textLv, 12, 13, 15));
        showCmdBox.setChecked(oldShow);
        box.addView(showCmdBox);
        box.addView(note("取消勾选后，结果窗口不再显示执行前的原命令（$ 行）。"));

        final CheckBox autoCloseBox = new CheckBox(this);
        autoCloseBox.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        autoCloseBox.setText("执行成功后自动关闭结果窗口");
        autoCloseBox.setTextSize(Ui.sp(textLv, 12, 13, 15));
        autoCloseBox.setChecked(oldAuto);
        box.addView(autoCloseBox);
        box.addView(note("成功后约 3 秒自动关闭；失败时保留窗口。"));

        // 结果窗口字体大小：小 / 中 / 大（仅作用于命令执行结果窗口，不影响界面其它文字）
        box.addView(note("结果窗口字体大小"));
        final int[] cmdLv = { Prefs.cmdTextLevel(this) };
        final String[] labels = { "小", "中", "大" };
        LinearLayout lvRow = new LinearLayout(this);
        lvRow.setOrientation(LinearLayout.HORIZONTAL);
        final Button[] lvBtns = new Button[3];
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            lvBtns[i] = Ui.flat(this, labels[i], textLv);
            lvBtns[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    cmdLv[0] = idx;
                    for (int k = 0; k < 3; k++)
                        lvBtns[k].setTextColor(k == idx ? Ui.PRIMARY : Ui.SUB);
                }
            });
            lvRow.addView(lvBtns[i]);
        }
        lvBtns[cmdLv[0]].setTextColor(Ui.PRIMARY);
        box.addView(lvRow);

        new AlertDialog.Builder(this)
                .setTitle("接收文件时执行的命令")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String t = tmplEt.getText().toString().trim();
                        // 已移除命令级 Root 复选框：Root 改为在模板里写 su --mount-master -c；
                        // 接收命令统一以普通 shell 执行（root=false）。
                        Prefs.setRxCommand(SettingsActivity.this,
                                new Prefs.Cmd("接收命令", t, false, autoCloseBox.isChecked(), showCmdBox.isChecked()));
                        Prefs.setCmdTextLevel(SettingsActivity.this, cmdLv[0]);
                        renderRx();
                        toast(t.isEmpty() ? "已清空接收命令" : "已保存");
                    }
                }).show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}

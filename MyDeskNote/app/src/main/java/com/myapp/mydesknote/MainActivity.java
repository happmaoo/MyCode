package com.myapp.mydesknote;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 定时便签：可写多条项目，每条单独设定日期时间。
 * 界面上全部项目都显示（含未到时间的）；桌面小组件只显示已到时间的。
 */
public class MainActivity extends AppCompatActivity {

    private static final long DEFAULT_DELAY_MS = 60 * 60 * 1000L; // 默认 1 小时后

    private final List<NoteStore.Item> notes = new ArrayList<>();

    private EditText input;
    private Button btnDate;
    private Button btnTime;
    private TextView emptyView;
    private TextView countLabel;
    private EditText sizeInput;
    private CheckBox enableTime;
    private View timeRow;
    private Button btnClearExpired;
    /** 正在用代码回填字号时，不要让输入监听再存一遍 */
    private boolean sizeLoading = false;

    private NoteAdapter adapter;
    private ListView listView;
    /** 顶部“新增项目”卡片：编辑时收起，退出编辑再放出来 */
    private View panelAdd;

    /** 正在原地编辑的条目 id（-1 表示没有） */
    private long editingId = -1;
    /** 键盘顶边（window 坐标），-1 表示还没测量到 */
    private int imeBottom = -1;

    /** 编辑框里的草稿，列表滚动/复用视图时靠它保住已输入的内容 */
    private String editingDraft = "";

    /** 正在编辑的这条项目的显示时间，默认“现在 + 1 小时” */
    private final Calendar dueCalendar = Calendar.getInstance();
    private boolean resumed = false;
    /** 上一次刷新时"已到时间"的条数，用来判断有没有新条目刚到点 */
    private int lastDueCount = -1;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tickTask = new Runnable() {
        @Override
        public void run() {
            // 每秒刷新：倒计时、以及“未到时间 → 已显示”的状态切换
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        input = findViewById(R.id.input);
        btnDate = findViewById(R.id.btn_date);
        btnTime = findViewById(R.id.btn_time);
        emptyView = findViewById(R.id.empty);
        countLabel = findViewById(R.id.visible_label);

        if (savedInstanceState != null) {
            dueCalendar.setTimeInMillis(savedInstanceState.getLong("due", dueCalendar.getTimeInMillis()));
        } else {
            resetDefaultDue();
        }

        // 时间默认不启用：不勾选就是一条普通便签，添加后立刻显示
        enableTime = findViewById(R.id.enable_time);
        timeRow = findViewById(R.id.time_row);
        enableTime.setChecked(false);
        // 不让系统自动恢复勾选状态：每次进/回到这个页面都默认不勾选
        enableTime.setSaveEnabled(false);
        enableTime.setOnCheckedChangeListener((buttonView, isChecked) -> applyTimeEnabled(isChecked));
        applyTimeEnabled(false);

        btnDate.setOnClickListener(v -> pickDate());
        btnTime.setOnClickListener(v -> pickTime());
        findViewById(R.id.btn_add).setOnClickListener(v -> addNote());

        // 一键清掉所有已经过时间的项目
        btnClearExpired = findViewById(R.id.btn_clear_expired);
        btnClearExpired.setOnClickListener(v -> confirmClearExpired());

        listView = findViewById(R.id.list);
        adapter = new NoteAdapter();
        listView.setAdapter(adapter);
        // 让列表里的 EditText 能拿到焦点（否则 ListView 会抢走，点不动）
        listView.setItemsCanFocus(true);
        listView.setEmptyView(emptyView);
        panelAdd = findViewById(R.id.panel_add);
        watchIme();

        sizeInput = findViewById(R.id.widget_size);
        bindSizeInput();

        updateDueLabels();
        loadNotes();
    }

    /** 勾选/取消勾选"定时显示"时，日期时间按钮跟着可用/禁用 */
    private void applyTimeEnabled(boolean enabled) {
        btnDate.setEnabled(enabled);
        btnTime.setEnabled(enabled);
        timeRow.setAlpha(enabled ? 1f : 0.45f);
    }

    /** 桌面小工具的字号：改一下就立刻应用到桌面 */
    private void bindSizeInput() {
        sizeLoading = true;
        sizeInput.setText(String.valueOf((int) NoteStore.getWidgetTextSize(this)));
        sizeLoading = false;

        sizeInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (sizeLoading) {
                    return;
                }
                float size = parseSize(s.toString());
                if (size <= 0) {
                    return; // 还没输完，等输完再说
                }
                NoteStore.setWidgetTextSize(MainActivity.this, size);
                NoteWidget.requestUpdate(MainActivity.this);
            }
        });

        // 离开输入框时把越界的值纠正成实际生效的值
        sizeInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                sizeLoading = true;
                sizeInput.setText(String.valueOf((int) NoteStore.getWidgetTextSize(MainActivity.this)));
                sizeLoading = false;
            }
        });
    }

    private float parseSize(String raw) {
        if (TextUtils.isEmpty(raw.trim())) {
            return 0;
        }
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void resetDefaultDue() {
        dueCalendar.setTimeInMillis(System.currentTimeMillis() + DEFAULT_DELAY_MS);
        dueCalendar.set(Calendar.SECOND, 0);
        dueCalendar.set(Calendar.MILLISECOND, 0);
    }

    /* ------------------------- 时间选择 ------------------------- */

    private void pickDate() {
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            dueCalendar.set(Calendar.YEAR, year);
            dueCalendar.set(Calendar.MONTH, month);
            dueCalendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            updateDueLabels();
        }, dueCalendar.get(Calendar.YEAR), dueCalendar.get(Calendar.MONTH),
                dueCalendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickTime() {
        new TimePickerDialog(this, (view, hourOfDay, minute) -> {
            dueCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
            dueCalendar.set(Calendar.MINUTE, minute);
            dueCalendar.set(Calendar.SECOND, 0);
            dueCalendar.set(Calendar.MILLISECOND, 0);
            updateDueLabels();
        }, dueCalendar.get(Calendar.HOUR_OF_DAY), dueCalendar.get(Calendar.MINUTE), true).show();
    }

    private void updateDueLabels() {
        btnDate.setText("日期  " + new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(new Date(dueCalendar.getTimeInMillis())));
        btnTime.setText("时间  " + new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date(dueCalendar.getTimeInMillis())));
    }

    /* ------------------------- 增删 ------------------------- */

    private void addNote() {
        String text = input.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, "先写点什么吧", Toast.LENGTH_SHORT).show();
            input.requestFocus();
            return;
        }

        // 没勾"定时显示"→ 时间就是现在，等于添加即显示
        long due = enableTime.isChecked() ? dueCalendar.getTimeInMillis() : System.currentTimeMillis();
        NoteStore.Item note = new NoteStore.Item();
        note.id = System.currentTimeMillis();
        note.text = text;
        note.due = due;
        notes.add(note);
        saveNotes();

        input.setText("");
        input.clearFocus();

        if (!enableTime.isChecked()) {
            Toast.makeText(this, "已添加，立即显示", Toast.LENGTH_SHORT).show();
        } else if (due <= System.currentTimeMillis()) {
            Toast.makeText(this, "这条时间已过，会立即显示", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "已添加，将于 " + formatFull(due) + " 显示", Toast.LENGTH_LONG).show();
            resetDefaultDue();
            updateDueLabels();
        }

        refresh();
    }

    private void confirmDelete(final NoteStore.Item note) {
        new AlertDialog.Builder(this)
                .setTitle("删除这条项目？")
                .setMessage("「" + preview(note.text) + "」\n时间 " + formatFull(note.due)
                        + "，删除后不会再出现。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    if (editingId == note.id) {
                        editingId = -1;
                        editingDraft = "";
                        panelAdd.setVisibility(View.VISIBLE);
                    }
                    notes.remove(note);
                    saveNotes();
                    refresh();
                })
                .show();
    }

    /** 清空已过期：只删已经到过时间的，未到时间的保留 */
    private void confirmClearExpired() {
        final long now = System.currentTimeMillis();
        final int count = countExpired(now);
        if (count == 0) {
            Toast.makeText(this, "没有已过期的项目", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空已过期项目？")
                .setMessage("将删除 " + count + " 条已经到过时间的项目，未到时间的会保留。删除后不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    editingId = -1;
                    editingDraft = "";
                    panelAdd.setVisibility(View.VISIBLE);
                    notes.removeIf(note -> note.due <= now);
                    saveNotes();
                    refresh();
                    Toast.makeText(this, "已清空 " + count + " 条", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private int countExpired(long now) {
        int count = 0;
        for (NoteStore.Item note : notes) {
            if (note.due <= now) {
                count++;
            }
        }
        return count;
    }

    /* ------------------------- 列表刷新 ------------------------- */

    private void refresh() {
        long now = System.currentTimeMillis();
        int pending = 0;
        for (NoteStore.Item n : notes) {
            if (n.due > now) {
                pending++;
            }
        }
        // 时间最新的排在最上面
        Collections.sort(notes, (a, b) -> Long.compare(b.due, a.due));

        // 有新条目刚到点（或者刚进界面）→ 把桌面小工具重画一遍，
        // 顺便把下一次闹钟重新排上（被"强行停止"后闹钟会丢，靠这里补回来）
        int dueNow = notes.size() - pending;
        if (dueNow != lastDueCount) {
            lastDueCount = dueNow;
            NoteWidget.requestUpdate(this);
        }

        adapter.notifyDataSetChanged();
        emptyView.setVisibility(notes.isEmpty() ? View.VISIBLE : View.GONE);

        // 有已过期项目时"清空已过期"才可点
        int expired = notes.size() - pending;
        btnClearExpired.setEnabled(expired > 0);
        btnClearExpired.setAlpha(expired > 0 ? 1f : 0.4f);

        if (notes.isEmpty()) {
            countLabel.setText("还没有项目");
        } else if (pending > 0) {
            countLabel.setText("共 " + notes.size() + " 条，其中 " + pending + " 条未到时间");
        } else {
            countLabel.setText("共 " + notes.size() + " 条");
        }

        // 还有未到时间的项目时才每秒刷新，全部到点后停掉
        ticker.removeCallbacks(tickTask);
        // 正在原地编辑时不刷新列表，否则每秒重绑会把输入框内容/光标冲掉
        if (pending > 0 && resumed && editingId == -1L) {
            ticker.postDelayed(tickTask, 1000);
        }
    }

    /* ------------------------- 存储 ------------------------- */

    private void loadNotes() {
        notes.clear();
        notes.addAll(NoteStore.load(this));
        refresh();
    }

    private void saveNotes() {
        NoteStore.save(this, notes);
        NoteWidget.requestUpdate(this); // 数据变了，桌面小组件同步刷新
    }

    /* ------------------------- 生命周期 ------------------------- */

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        editingId = -1;
        editingDraft = "";
        if (panelAdd != null) {
            panelAdd.setVisibility(View.VISIBLE);
        }
        // 回到页面时"定时显示"恢复成默认不勾选
        if (enableTime.isChecked()) {
            enableTime.setChecked(false);
        }
        applyTimeEnabled(false);
        // 重新从存储读一遍：避免停在旧实例上看到过期的（甚至空的）列表
        loadNotes();
        // 每次回到前台都强制同步一次桌面：闹钟可能被系统清掉（强行停止 / 省电策略）
        NoteWidget.requestUpdate(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ticker.removeCallbacks(tickTask);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong("due", dueCalendar.getTimeInMillis());
    }

    /* ------------------------- 键盘遮挡处理 ------------------------- */

    /**
     * 监听窗口可见区域变化（键盘弹出/收起都会让可见底边上移），
     * 变化时把正在编辑的那一条滚到键盘上方。
     */
    private void watchIme() {
        final View decor = getWindow().getDecorView();
        decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect frame = new Rect();
            decor.getWindowVisibleDisplayFrame(frame);
            int prev = imeBottom;
            imeBottom = frame.bottom;
            if (editingId != -1 && imeBottom != prev) {
                listView.post(this::scrollEditedRowIntoView);
            }
        });
    }

    /**
     * 让正在编辑的条目露在键盘上方。
     * 卡片比可视区还高时（键盘弹出后很常见），优先把「输入框 + 确定」所在的顶部对齐到可视区顶部，
     * 而不是死磕底边——否则输入框会被挤到列表上方看不见的地方。
     */
    private void scrollEditedRowIntoView() {
        if (editingId == -1) {
            return;
        }
        View row = findRow(editingId);
        if (row == null) {
            return;
        }
        int[] rowLoc = new int[2];
        row.getLocationInWindow(rowLoc);
        int[] listLoc = new int[2];
        listView.getLocationInWindow(listLoc);

        int margin = dp(6);
        int listTop = listLoc[1] + listView.getPaddingTop() + margin;
        int visibleEnd = imeBottom > 0 ? imeBottom : listLoc[1] + listView.getHeight();
        int listBottom = Math.min(listLoc[1] + listView.getHeight(), visibleEnd) - margin;

        int rowTop = rowLoc[1];
        int rowBottom = rowLoc[1] + row.getHeight();
        int viewportHeight = listBottom - listTop;

        if (row.getHeight() >= viewportHeight) {
            // 整条放不下：只保证行顶（输入框那一行）可见
            if (rowTop < listTop) {
                listView.smoothScrollBy(rowTop - listTop, 200);   // 负数 = 把内容往下拉
            }
            return;
        }
        if (rowTop < listTop) {
            listView.smoothScrollBy(rowTop - listTop, 200);
        } else if (rowBottom > listBottom) {
            listView.smoothScrollBy(rowBottom - listBottom, 200);
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /* ------------------------- 原地编辑 ------------------------- */

    /** 点击条目正文：原地换成输入框，右侧出现“确定” */
    private void startEdit(final NoteStore.Item note) {
        // 正在编辑别的条目：先把那一条的修改提交，别让用户手打的内容白丢
        if (editingId != -1 && editingId != note.id) {
            View oldRow = findRow(editingId);
            NoteStore.Item prev = findNote(editingId);
            if (oldRow != null && prev != null) {
                confirmEdit(prev, oldRow.findViewById(R.id.item_edit));
            }
        }
        editingId = note.id;
        editingDraft = note.text;
        panelAdd.setVisibility(View.GONE);   // 收起新增卡片，把键盘上方的高度全给列表
        ticker.removeCallbacks(tickTask);   // 编辑期间暂停每秒刷新
        adapter.notifyDataSetChanged();
        listView.post(() -> {
            View row = findRow(note.id);
            if (row == null) {
                return;
            }
            EditText edit = row.findViewById(R.id.item_edit);
            edit.requestFocus();
            edit.setSelection(edit.getText().length());
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
            }
            // 键盘有弹出动画，等它稳定后再校正一次位置
            listView.postDelayed(this::scrollEditedRowIntoView, 350);
            listView.postDelayed(this::scrollEditedRowIntoView, 700);
        });
    }

    /** 提交修改 */
    private void confirmEdit(final NoteStore.Item note, EditText edit) {
        String text = edit.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show();
            edit.requestFocus();
            return;
        }
        boolean changed = !text.equals(note.text);
        note.text = text;
        editingId = -1;
        editingDraft = "";
        panelAdd.setVisibility(View.VISIBLE);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(edit.getWindowToken(), 0);
        }
        if (changed) {
            saveNotes();
            Toast.makeText(this, "已修改", Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    /** 放弃修改（点空白处 / 返回键） */
    private void cancelEdit() {
        if (editingId == -1) {
            return;
        }
        editingId = -1;
        editingDraft = "";
        panelAdd.setVisibility(View.VISIBLE);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
        refresh();
    }

    private NoteStore.Item findNote(long id) {
        for (NoteStore.Item n : notes) {
            if (n.id == id) {
                return n;
            }
        }
        return null;
    }

    private View findRow(long id) {
        if (listView == null) {
            return null;
        }
        int first = listView.getFirstVisiblePosition();
        for (int i = 0; i < listView.getChildCount(); i++) {
            int pos = first + i;
            if (pos >= 0 && pos < notes.size() && notes.get(pos).id == id) {
                return listView.getChildAt(i);
            }
        }
        return null;
    }

    @Override
    public void onBackPressed() {
        if (editingId != -1) {
            cancelEdit();   // 编辑中先退出编辑，不直接退出 App
            return;
        }
        super.onBackPressed();
    }

    /* ------------------------- 工具 ------------------------- */

    private String formatFull(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    private String preview(String text) {
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() > 20 ? oneLine.substring(0, 20) + "…" : oneLine;
    }

    private String remaining(long deltaMs) {
        if (deltaMs <= 0) {
            return "0 分钟";
        }
        long minutes = deltaMs / 60000;
        if (minutes < 1) {
            return "不到 1 分钟";
        }
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        long hours = minutes / 60;
        long restMinutes = minutes % 60;
        if (hours < 24) {
            return restMinutes > 0 ? hours + " 小时 " + restMinutes + " 分" : hours + " 小时";
        }
        long days = hours / 24;
        long restHours = hours % 24;
        return restHours > 0 ? days + " 天 " + restHours + " 小时" : days + " 天";
    }

    /* ------------------------- 列表适配器 ------------------------- */

    private class NoteAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return notes.size();
        }

        @Override
        public NoteStore.Item getItem(int position) {
            return notes.get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_note, parent, false);
            }
            final NoteStore.Item note = getItem(position);
            long now = System.currentTimeMillis();

            TextView text = row.findViewById(R.id.item_text);
            TextView time = row.findViewById(R.id.item_time);
            TextView delete = row.findViewById(R.id.item_delete);
            EditText edit = row.findViewById(R.id.item_edit);
            Button ok = row.findViewById(R.id.item_ok);

            // 这一条处于编辑态：藏正文、露输入框和“确定”
            boolean editing = note.id == editingId;
            text.setVisibility(editing ? View.GONE : View.VISIBLE);
            edit.setVisibility(editing ? View.VISIBLE : View.GONE);
            ok.setVisibility(editing ? View.VISIBLE : View.GONE);

            // 已显示（时间到了）用 #888888，还没到时间用黑色
            boolean shown = note.due <= now;
            int textColor = row.getContext().getColor(
                    shown ? R.color.text_shown : R.color.text_pending);
            text.setTextColor(textColor);
            edit.setTextColor(textColor);   // 编辑时保持一致，不突兀

            text.setText(note.text);
            if (editing) {
                // 视图是复用的，先摘掉上一次挂的监听，再按本条内容回填草稿
                Object tag = edit.getTag();
                if (tag instanceof TextWatcher) {
                    edit.removeTextChangedListener((TextWatcher) tag);
                }
                final long noteId = note.id;
                TextWatcher watcher = new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        if (editingId == noteId) {
                            editingDraft = s.toString();   // 保住草稿，滚动复用不丢字
                            // 输入变多、行变高时可能又钻到键盘下面，跟着校正
                            listView.post(MainActivity.this::scrollEditedRowIntoView);
                        }
                    }
                };
                edit.addTextChangedListener(watcher);
                edit.setTag(watcher);
                if (!TextUtils.equals(edit.getText().toString(), editingDraft)) {
                    edit.setText(editingDraft);
                    edit.setSelection(edit.getText().length());
                }
            } else {
                Object tag = edit.getTag();
                if (tag instanceof TextWatcher) {
                    edit.removeTextChangedListener((TextWatcher) tag);
                    edit.setTag(null);
                }
                if (edit.getText().length() > 0) {
                    edit.setText("");   // 清掉复用残留
                }
            }

            text.setOnClickListener(v -> startEdit(note));
            ok.setOnClickListener(v -> confirmEdit(note, edit));

            if (note.due > now) {
                time.setText("未到时间 · 还有 " + remaining(note.due - now)
                        + " · " + formatFull(note.due) + " 显示");
            } else {
                time.setText("已于 " + formatFull(note.due) + " 显示");
            }
            delete.setOnClickListener(v -> confirmDelete(note));
            return row;
        }
    }
}

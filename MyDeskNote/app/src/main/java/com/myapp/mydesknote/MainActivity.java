package com.myapp.mydesknote;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

        ListView listView = findViewById(R.id.list);
        adapter = new NoteAdapter();
        listView.setAdapter(adapter);
        listView.setEmptyView(emptyView);

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
        if (pending > 0 && resumed) {
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

            text.setText(note.text);
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

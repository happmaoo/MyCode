package com.myapp.mqtt.base;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 订阅主题列表：可增删，连接后自动订阅。 */
public class TopicsActivity extends Activity {

    private EditText tInput;
    private ListView tList;
    private ArrayAdapter<String> adapter;
    private final List<String> data = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Subscribe topics");
        setContentView(R.layout.topics);

        tInput = findViewById(R.id.t_input);
        tInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        tList = findViewById(R.id.t_list);

        data.addAll(P.subTopics(this));
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, data);
        tList.setAdapter(adapter);

        ((Button) findViewById(R.id.t_add)).setOnClickListener(v -> add());
        ((Button) findViewById(R.id.t_back)).setOnClickListener(v -> finish());

        tList.setOnItemClickListener((parent, view, position, id) ->
                new AlertDialog.Builder(this)
                        .setTitle("删除订阅主题")
                        .setMessage(data.get(position))
                        .setPositiveButton("删除", (d, w) -> {
                            String t = data.remove(position);
                            P.removeSubTopic(this, t);
                            adapter.notifyDataSetChanged();
                            toast("已删除 " + t + "（下次连接生效）");
                        })
                        .setNegativeButton("取消", null)
                        .show());
    }

    private void add() {
        String t = tInput.getText().toString().trim();
        if (t.isEmpty()) return;
        if (data.contains(t)) {
            toast("已存在");
            return;
        }
        data.add(t);
        P.addSubTopic(this, t);
        adapter.notifyDataSetChanged();
        tInput.setText("");
        toast("已添加，下次连接自动订阅");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

package com.myapp.mydesknote;

import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

public class MainActivity extends Activity {

    EditText editText,editText_fontsize;
    String oldText;
    int font_size;
    Button btn,btn_clear;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editText = findViewById(R.id.editText);
        editText_fontsize = findViewById(R.id.editText_fontsize);

        btn = findViewById(R.id.btn);
        btn_clear = findViewById(R.id.btn_clear);

        oldText = getSharedPreferences("pref", MODE_PRIVATE).getString("note", "");
        editText.setText(oldText);

        font_size = getSharedPreferences("pref", MODE_PRIVATE).getInt("font_size", 12);
        editText_fontsize.setText(String.valueOf(font_size));


        btn.setOnClickListener(v -> {
            // 1. 保存数据
            SharedPreferences sp = getSharedPreferences("pref", MODE_PRIVATE);
            sp.edit().putString("note", editText.getText().toString()).apply();

            String sizeStr = editText_fontsize.getText().toString();
            int fontSize = Integer.parseInt(sizeStr);
            sp.edit().putInt("font_size", fontSize).apply();

            // 2. 发送广播通知 Widget 更新
            Intent intent = new Intent(this, SimpleWidget.class);
            intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            int[] ids = AppWidgetManager.getInstance(getApplication())
                    .getAppWidgetIds(new ComponentName(getApplication(), SimpleWidget.class));
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
            sendBroadcast(intent);

            finish(); // 关闭界面
        });

        btn_clear.setOnClickListener(v -> {
            editText.setText("");
        });
    }
}
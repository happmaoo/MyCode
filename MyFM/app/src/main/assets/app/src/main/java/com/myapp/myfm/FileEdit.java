package com.myapp.myfm;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class FileEdit extends AppCompatActivity {


    MyFmApp myapp;


    private EditText editText;
    private Button  btnSave;
    TextView textView_file;


    String plistfile;
    private String FILE_PATH;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fileedit);

        // 初始化UI组件
        editText = findViewById(R.id.editText);
        btnSave = findViewById(R.id.btnSave);
        textView_file = findViewById(R.id.textView_file);

        myapp = (MyFmApp) getApplicationContext();
        plistfile = myapp.getString("plistfile","myfm.txt");


        if (Build.VERSION.SDK_INT >= 29) {
            // 获取应用私有外部存储目录（不需要任何存储权限）
            File appPrivateDir = getExternalFilesDir(null);
            FILE_PATH = appPrivateDir +"/"+ plistfile;
        } else {
            FILE_PATH = Environment.getExternalStorageDirectory().getPath() + "/myapp/"+plistfile;
        }



        textView_file.setText(FILE_PATH);

        if("myfm-temp.txt".equals(plistfile)){
            textView_file.setText(FILE_PATH+"\n本列表为临时列表，将在下次搜台后删除，记得复制电台文本到其他列表。");
            textView_file.setTextColor(Color.parseColor("#ff6800"));
        }


        // 保存按钮点击事件
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveFile();
                finish();
            }
        });

        // 应用启动时尝试自动加载文件
        loadFile();
    }

    // 加载文件内容
    private void loadFile() {
        File file = new File(FILE_PATH);

        if (!file.exists()) {
            Toast.makeText(this, "文件不存在，将创建新文件", Toast.LENGTH_SHORT).show();
            editText.setText("");
            return;
        }

        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            StringBuilder content = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();

            editText.setText(content.toString());
            //Toast.makeText(this, "文件加载成功", Toast.LENGTH_SHORT).show();

        } catch (IOException e) {
            Toast.makeText(this, "读取文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    // 保存文件内容
    private void saveFile() {
        String content = editText.getText().toString();

        try {
            File file = new File(FILE_PATH);
            FileWriter writer = new FileWriter(file);
            writer.write(content);
            writer.close();

            Toast.makeText(this, "文件保存成功: " + FILE_PATH, Toast.LENGTH_LONG).show();
            // 设置返回结果
            Intent resultIntent = new Intent();
            resultIntent.putExtra("file_changed", true);
            setResult(RESULT_OK, resultIntent);

        } catch (IOException e) {
            Toast.makeText(this, "保存文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }
}

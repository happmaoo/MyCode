package com.myapp.myfm;

import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class FileEdit extends AppCompatActivity {

    private EditText editText;
    private Button  btnSave;

    // 文件路径 - 你可以修改这个路径
    private static final String FILE_PATH = Environment.getExternalStorageDirectory().getPath() + "/myapp/myfm.txt";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fileedit);

        // 初始化UI组件
        editText = findViewById(R.id.editText);
        btnSave = findViewById(R.id.btnSave);

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
            Toast.makeText(this, "文件加载成功", Toast.LENGTH_SHORT).show();

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

        } catch (IOException e) {
            Toast.makeText(this, "保存文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }
}

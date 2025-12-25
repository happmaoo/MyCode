package com.myapp.mysearch;



import android.os.Bundle;
import android.text.Html;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private TextView tvOutput;
    private Button btnRun;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvOutput = findViewById(R.id.tvOutput);
        btnRun = findViewById(R.id.btnRun);

        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runLsCommand();
            }
        });
    }

    private void runLsCommand() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder result = new StringBuilder();
                final Pattern grepPattern = Pattern.compile("^(\\/[^\\s:]+):(\\d+):(.*)$");

                try {
                    // 1. 获取 root shell
                    Process process = Runtime.getRuntime().exec("su");

                    DataOutputStream os = new DataOutputStream(process.getOutputStream());
                    BufferedReader is = new BufferedReader(new InputStreamReader(process.getInputStream()));

                    // 2. 执行命令
                    os.writeBytes("grep -r -n -I -C 5 \"rssi\" /storage/emulated/0/Download/Spirit3-FM-master 2>&1\n");
                    os.writeBytes("exit\n");
                    os.flush();

                    // 3. 使用正则解析输出
                    String line;
                    boolean firstEntry = true;
                    while ((line = is.readLine()) != null) {
                        Matcher matcher = grepPattern.matcher(line);

                        if (matcher.matches()) {
                            // 匹配成功：文件路径:行号:内容
                            String filePath = matcher.group(1);    // 文件路径
                            String lineNumber = matcher.group(2);   // 行号
                            String content = matcher.group(3);      // 内容

                            result.append(filePath);
                            result.append("<br>");
                            result.append(lineNumber);
                            result.append("<br><br>");
                            result.append("<pre>"+content+"</pre>");
                            result.append("<br><br>");


                        }
                    }

                    process.waitFor();

                } catch (Exception e) {
                    result.append("Error: ").append(e.getMessage());
                }

                // 4. 更新 UI
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvOutput.setText(result.toString());
                    }
                });
            }
        }).start();
    }
}

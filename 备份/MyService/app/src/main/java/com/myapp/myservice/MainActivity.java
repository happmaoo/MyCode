package com.myapp.myservice;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private boolean isServiceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button startStopButton = findViewById(R.id.startStopButton);
        Button sendHelloButton = findViewById(R.id.sendHelloButton);

        startStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, MyService.class);
                if (isServiceRunning) {
                    startStopButton.setText("启动服务");
                    stopService(intent);
                    isServiceRunning = false;
                    Toast.makeText(MainActivity.this, "服务已停止", Toast.LENGTH_SHORT).show();
                } else {
                    startStopButton.setText("停止服务");
                    intent.setAction("START_SERVICE");
                    startService(intent);
                    isServiceRunning = true;
                    Toast.makeText(MainActivity.this, "服务已启动", Toast.LENGTH_SHORT).show();
                }
            }
        });


        sendHelloButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isServiceRunning) {
                    Intent intent = new Intent(MainActivity.this, MyService.class);
                    intent.setAction("SEND_HELLO");
                    startService(intent);
                } else {
                    Toast.makeText(MainActivity.this, "服务未运行", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}

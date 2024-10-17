package com.myapp.mygps;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {
    private GPXTracker gpxTracker;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        Intent intent = new Intent(this, GPXTracker.class);
        startService(intent);

    }

    @Override
    protected void onDestroy() {
        Intent intent = new Intent(this, GPXTracker.class);
        stopService(intent);
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
        super.onDestroy();

    }
}
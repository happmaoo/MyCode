package com.myapp.mygps;

import android.os.Bundle;

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

        gpxTracker = new GPXTracker(this);
        gpxTracker.startTracking();

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        gpxTracker.stopTracking();
    }
}
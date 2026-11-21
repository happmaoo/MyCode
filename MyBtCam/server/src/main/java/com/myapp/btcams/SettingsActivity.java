package com.myapp.btcams;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.RadioGroup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.title_settings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        SwitchMaterial autoStart = findViewById(R.id.sw_auto_start);
        SwitchMaterial openUi = findViewById(R.id.sw_open_ui);
        RadioGroup cameraGroup = findViewById(R.id.rg_camera);
        RadioGroup rotationGroup = findViewById(R.id.rg_rotation);

        autoStart.setChecked(Prefs.isAutoStart(this));
        autoStart.setOnCheckedChangeListener((buttonView, isChecked) ->
                Prefs.get(this).edit().putBoolean(Prefs.KEY_AUTO_START, isChecked).apply());

        openUi.setChecked(Prefs.isOpenUiOnConnect(this));
        openUi.setOnCheckedChangeListener((buttonView, isChecked) ->
                Prefs.get(this).edit().putBoolean(Prefs.KEY_OPEN_UI, isChecked).apply());

        int camera = Prefs.getDefaultCamera(this);
        if (camera == 1) {
            cameraGroup.check(R.id.rb_camera_back);
        } else if (camera == 2) {
            cameraGroup.check(R.id.rb_camera_front);
        } else {
            cameraGroup.check(R.id.rb_camera_auto);
        }
        cameraGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int value = 0;
            if (checkedId == R.id.rb_camera_back) {
                value = 1;
            } else if (checkedId == R.id.rb_camera_front) {
                value = 2;
            }
            Prefs.setDefaultCamera(this, value);
        });

        int rotation = Prefs.getRotation(this);
        int rotationId = R.id.rb_rot_auto;
        if (rotation == 0) {
            rotationId = R.id.rb_rot_0;
        } else if (rotation == 90) {
            rotationId = R.id.rb_rot_90;
        } else if (rotation == 180) {
            rotationId = R.id.rb_rot_180;
        } else if (rotation == 270) {
            rotationId = R.id.rb_rot_270;
        }
        rotationGroup.check(rotationId);
        rotationGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int value = -1;
            if (checkedId == R.id.rb_rot_0) {
                value = 0;
            } else if (checkedId == R.id.rb_rot_90) {
                value = 90;
            } else if (checkedId == R.id.rb_rot_180) {
                value = 180;
            } else if (checkedId == R.id.rb_rot_270) {
                value = 270;
            }
            Prefs.setRotation(this, value);
        });

        View battery = findViewById(R.id.btn_battery);
        battery.setOnClickListener(v -> openBatterySettings());

        View bluetooth = findViewById(R.id.btn_bluetooth);
        bluetooth.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
    }

    private void openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }
    }
}

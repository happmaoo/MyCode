package com.myapp.myfm;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

public class SettingActivity extends AppCompatActivity {

    CheckBox checkBox_setting_tap_to_wake;
    EditText editText_setting_browse_pause_sec;

    private MyFmApp app;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setting);
        app = (MyFmApp) getApplicationContext();


        checkBox_setting_tap_to_wake = findViewById(R.id.checkBox_setting_tap_to_wake);
        editText_setting_browse_pause_sec = findViewById(R.id.editText_setting_browse_pause_sec);

        if(app.getBoolean("setting_tap_to_wake",false)){
            checkBox_setting_tap_to_wake.setChecked(true);
        }
        editText_setting_browse_pause_sec.setText(String.valueOf(app.getInt("setting_browse_pause_sec",5)));

        //-----------checkBox_setting_tap_to_wake----------------
        checkBox_setting_tap_to_wake.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                app.setBoolean("setting_tap_to_wake",true);
            } else {
                app.setBoolean("setting_tap_to_wake",false);
            }
            Toast.makeText(SettingActivity.this, "重新打开收音机生效.", Toast.LENGTH_SHORT).show();
        });

    }


    @Override
    protected void onDestroy() {
        app.setInt("setting_browse_pause_sec",Integer.parseInt(editText_setting_browse_pause_sec.getText().toString().trim()));
        super.onDestroy();
    }
}

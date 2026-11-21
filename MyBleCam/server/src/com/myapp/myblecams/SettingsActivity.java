package com.myapp.myblecams;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;

public class SettingsActivity extends Activity {

    EditText etName, etConnect, etDisc;
    Spinner spCam, spQuality, spRotation;
    boolean spReady = false;

    static final String[] CAMS = new String[]{"后置摄像头", "前置摄像头"};
    static final String[] QUALITIES = new String[]{"Low 160x120", "Mid 256x192", "High 320x240"};
    // 旋转选项显示文本 -> 实际角度值（-1 自动）
    static final String[] ROTATIONS = new String[]{
            "自动（按摄像头方向）", "0°", "90°", "180°", "270°"};
    static final int[] ROTATION_VALUES = new int[]{-1, 0, 90, 180, 270};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("设置");
        setContentView(R.layout.settings);

        final SharedPreferences sp = getSharedPreferences("myblecams", MODE_PRIVATE);

        etName = (EditText) findViewById(R.id.et_name);
        etConnect = (EditText) findViewById(R.id.et_connect_cmd);
        etDisc = (EditText) findViewById(R.id.et_disc_cmd);
        spCam = (Spinner) findViewById(R.id.sp_cam);
        spQuality = (Spinner) findViewById(R.id.sp_quality);
        spRotation = (Spinner) findViewById(R.id.sp_rotation);

        etName.setText(sp.getString("server_name", "MyBleCamS"));
        etConnect.setText(sp.getString("on_connect_cmd", ""));
        etDisc.setText(sp.getString("on_disconnect_cmd", ""));

        spCam.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, CAMS));
        spQuality.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, QUALITIES));
        spRotation.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, ROTATIONS));
        spCam.setSelection(sp.getInt("default_cam", 0), false);
        spQuality.setSelection(sp.getInt("default_quality", 0), false);
        // 读回保存的旋转角度值，映射到 Spinner 位置
        int savedRot = sp.getInt("preview_rotation", -1);
        int rotPos = 0;
        for (int i = 0; i < ROTATION_VALUES.length; i++) {
            if (ROTATION_VALUES[i] == savedRot) { rotPos = i; break; }
        }
        spRotation.setSelection(rotPos, false);

        Button save = (Button) findViewById(R.id.btn_save);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) name = "MyBleCamS";
                sp.edit()
                        .putString("server_name", name)
                        .putString("on_connect_cmd", etConnect.getText().toString().trim())
                        .putString("on_disconnect_cmd", etDisc.getText().toString().trim())
                        .putInt("default_cam", spCam.getSelectedItemPosition())
                        .putInt("default_quality", spQuality.getSelectedItemPosition())
                        .putInt("preview_rotation", ROTATION_VALUES[spRotation.getSelectedItemPosition()])
                        .commit();
                finish();
            }
        });
    }
}

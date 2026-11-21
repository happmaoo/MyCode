package com.myapp.btcamc;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class SettingsActivity extends AppCompatActivity {

    /** 码率档位，顺序与 R.array.bitrate_entries 一致。 */
    private static final int[] BITRATES = {1000000, 500000, 300000, 200000, 100000};

    private static final Pattern MAC_PATTERN =
            Pattern.compile("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$");

    private EditText macInput;
    private SwitchMaterial autoConnect;

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

        macInput = findViewById(R.id.et_mac);
        autoConnect = findViewById(R.id.sw_auto_connect);
        RadioGroup resGroup = findViewById(R.id.rg_resolution);
        Spinner bitrateSpinner = findViewById(R.id.sp_bitrate);

        macInput.setText(Prefs.getServerMac(this));
        CheckBox autoBitrate = findViewById(R.id.cb_auto_bitrate);
        autoBitrate.setChecked(Prefs.isAutoBitrate(this));
        autoBitrate.setOnCheckedChangeListener((buttonView, isChecked) ->
                Prefs.setAutoBitrate(this, isChecked));

        CheckBox autoReconnect = findViewById(R.id.cb_auto_reconnect);
        autoReconnect.setChecked(Prefs.isAutoReconnect(this));
        autoReconnect.setOnCheckedChangeListener((buttonView, isChecked) ->
                Prefs.setAutoReconnect(this, isChecked));

        autoConnect.setChecked(Prefs.isAutoConnect(this));
        autoConnect.setOnCheckedChangeListener((buttonView, isChecked) ->
                Prefs.setAutoConnect(this, isChecked));

        resGroup.check(Prefs.getWidth(this) == 320 ? R.id.rb_res_320 : R.id.rb_res_640);
        resGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int w = checkedId == R.id.rb_res_320 ? 320 : 640;
            int h = checkedId == R.id.rb_res_320 ? 240 : 480;
            Prefs.setResolution(this, w, h);
        });

        ArrayAdapter<CharSequence> bitrateAdapter = ArrayAdapter.createFromResource(
                this, R.array.bitrate_entries, android.R.layout.simple_spinner_item);
        bitrateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bitrateSpinner.setAdapter(bitrateAdapter);

        int bitrate = Prefs.getBitrate(this);
        int index = 0;
        for (int i = 0; i < BITRATES.length; i++) {
            if (BITRATES[i] == bitrate) {
                index = i;
                break;
            }
        }
        bitrateSpinner.setSelection(index);
        bitrateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Prefs.setBitrate(SettingsActivity.this, BITRATES[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        findViewById(R.id.btn_pick_device).setOnClickListener(v -> showPairedDevices());
        findViewById(R.id.btn_save).setOnClickListener(v -> save());
    }

    @SuppressLint("MissingPermission") // targetSdk 28 下蓝牙为安装时授权
    private void showPairedDevices() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "本机不支持蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!adapter.isEnabled()) {
            Toast.makeText(this, "请先打开蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            Toast.makeText(this, "还没有已配对的设备，请先在系统蓝牙设置里配对",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final List<BluetoothDevice> devices = new ArrayList<>(bonded);
        String[] items = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice d = devices.get(i);
            String name = d.getName();
            items[i] = (TextUtils.isEmpty(name) ? "未知设备" : name)
                    + "\n" + d.getAddress();
        }
        new AlertDialog.Builder(this)
                .setTitle("选择已配对的服务端")
                .setItems(items, (dialog, which) -> {
                    String mac = devices.get(which).getAddress().toUpperCase(Locale.US);
                    macInput.setText(mac);
                    Prefs.setServerMac(this, mac);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void save() {
        String mac = macInput.getText().toString().trim().toUpperCase(Locale.US);
        if (mac.isEmpty()) {
            Prefs.setServerMac(this, "");
            Toast.makeText(this, "已清空服务端地址", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!MAC_PATTERN.matcher(mac).matches()) {
            Toast.makeText(this, "地址格式应为 AA:BB:CC:DD:EE:FF", Toast.LENGTH_LONG).show();
            return;
        }
        Prefs.setServerMac(this, mac);
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }
}

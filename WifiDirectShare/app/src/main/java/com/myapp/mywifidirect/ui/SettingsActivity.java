package com.myapp.mywifidirect.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;

import com.myapp.mywifidirect.R;
import com.myapp.mywifidirect.transfer.TransferService;
import com.myapp.mywifidirect.util.RootWifi;
import com.myapp.mywifidirect.util.Settings;

/** Preferences: device name, auto-connect target, auto-accept, folder, GO, root. */
public final class SettingsActivity extends AppCompatActivity {

    private static final int REQ_TREE = 1;

    private Settings settings;
    private EditText etName, etAutoConnect;
    private SwitchCompat swAuto, swGo, swRoot, swWakeLock, swReconnect;
    private TextView tvFolder, tvRootStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        settings = new Settings(this);
        etName = findViewById(R.id.set_name);
        etAutoConnect = findViewById(R.id.set_auto_connect_name);
        swAuto = findViewById(R.id.set_auto_accept);
        swGo = findViewById(R.id.set_prefer_go);
        swRoot = findViewById(R.id.set_root_wifi);
        swWakeLock = findViewById(R.id.set_wake_lock);
        swReconnect = findViewById(R.id.set_reconnect);
        tvFolder = findViewById(R.id.set_folder_value);
        tvRootStatus = findViewById(R.id.set_root_status);
        Button btnPick = findViewById(R.id.btn_pick_folder);
        Button btnReset = findViewById(R.id.btn_reset_folder);
        Button btnSave = findViewById(R.id.btn_save);

        etName.setText(settings.deviceName());
        etAutoConnect.setText(settings.autoConnectName());
        swAuto.setChecked(settings.autoAccept());
        swGo.setChecked(settings.preferGo());
        swRoot.setChecked(settings.rootWifi());
        swWakeLock.setChecked(settings.wakeLock());
        swReconnect.setChecked(settings.reconnectOnDrop());
        updateFolderLabel();

        btnPick.setOnClickListener(v -> pickFolder());
        btnReset.setOnClickListener(v -> {
            settings.setReceiveTree(null);
            updateFolderLabel();
            toast(R.string.msg_folder_reset);
        });
        // 打开 Root 开关时立即主动申请一次，弹出授权对话框。
        // 用点击监听而非状态监听，避免 onCreate 里 setChecked 触发误弹窗。
        swRoot.setOnClickListener(v -> {
            if (swRoot.isChecked()) requestRoot();
        });
        btnSave.setOnClickListener(v -> {
            save();
            toast(R.string.msg_settings_saved);
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateRootStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    private void save() {
        settings.setDeviceName(etName.getText().toString());
        settings.setAutoConnectName(etAutoConnect.getText().toString());
        settings.setAutoAccept(swAuto.isChecked());
        settings.setPreferGo(swGo.isChecked());
        settings.setRootWifi(swRoot.isChecked());
        settings.setWakeLock(swWakeLock.isChecked());
        settings.setReconnectOnDrop(swReconnect.isChecked());
        // Apply immediately to the already-running foreground service.
        TransferService.syncWakeLock(this);
        setResult(RESULT_OK);
    }

    /** 主动申请 Root 权限（触发 Magisk / KernelSU 授权对话框）。 */
    private void requestRoot() {
        settings.setRootWifi(swRoot.isChecked());
        toast(R.string.msg_root_requesting);
        RootWifi.requestRootAsync((ok, detail) -> {
            toastLong(detail);
            updateRootStatus();
        });
    }

    private void updateRootStatus() {
        int s = RootWifi.cachedStatus();
        int id = s == RootWifi.ROOT_GRANTED ? R.string.set_root_status_granted
                : s == RootWifi.ROOT_DENIED ? R.string.set_root_status_denied
                : R.string.set_root_status_unknown;
        tvRootStatus.setText(getString(R.string.set_root_status, getString(id)));
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_TREE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_TREE || res != RESULT_OK || data == null) return;
        Uri tree = data.getData();
        if (tree == null) {
            toast(R.string.msg_folder_fail);
            return;
        }
        int take = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(tree, take);
        } catch (Exception e) {
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }
        settings.setReceiveTree(tree);
        updateFolderLabel();
        toast(R.string.msg_folder_picked);
    }

    private void updateFolderLabel() {
        tvFolder.setText(settings.folderLabel());
    }

    private void toast(int id) {
        Toast.makeText(this, id, Toast.LENGTH_SHORT).show();
    }

    private void toastLong(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

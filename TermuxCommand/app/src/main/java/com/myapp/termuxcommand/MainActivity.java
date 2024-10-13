package com.myapp.termuxcommand;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.termux.shared.packages.PermissionUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });


        // grant Permission for miui
        int requestCode =PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;
        if (ContextCompat.checkSelfPermission(this, "com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{"com.termux.permission.RUN_COMMAND"}, requestCode);
        }




        String LOG_TAG = "MainActivity";

        Intent intent = new Intent();
        intent.setClassName(TermuxConstants.TERMUX_PACKAGE_NAME, TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE_NAME);
        intent.setAction(RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND);
        intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_ARGUMENTS, new String[]{"top", "--help"});
        intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_WORKDIR, "/data/data/com.termux/files/home");
        intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_BACKGROUND, false);
        intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_SESSION_ACTION, "0");
        //intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_COMMAND_LABEL, "my command");
        //intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_COMMAND_DESCRIPTION, "Runs the top command to show processes using the most resources.");

        Intent pluginResultsServiceIntent = new Intent(MainActivity.this, PluginResultsService.class);
        int executionId = PluginResultsService.getNextExecutionId();
        pluginResultsServiceIntent.putExtra(PluginResultsService.EXTRA_EXECUTION_ID, executionId);

        PendingIntent pendingIntent = PendingIntent.getService(MainActivity.this, executionId,
                pluginResultsServiceIntent,
                PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0));
        intent.putExtra(TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE.EXTRA_PENDING_INTENT, pendingIntent);

        try {
            // Send command intent for execution
            Log.d("aaa", "Sending execution command with id " + executionId);
            startService(intent);
        } catch (Exception e) {
            Log.e("aaa", "Failed to start execution command with id " + executionId + ": " + e.getMessage());
        }

    }


}
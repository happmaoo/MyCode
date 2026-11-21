package com.myapp.btcams;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** 可选兜底：普通 startActivity 被 ROM 拦下时，用 root 拉起主界面。 */
final class RootShell {

    private static final String TAG = "RootShell";

    private RootShell() {
    }

    static boolean amStartMainActivity() {
        String cmd = "am start -n com.myapp.btcams/.MainActivity";
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
            reader.close();
            int code = process.waitFor();
            Log.i(TAG, "root am start exit=" + code + " " + output);
            return code == 0;
        } catch (Exception e) {
            Log.w(TAG, "root am start failed: " + e);
            return false;
        }
    }
}

package com.myapp.myfm;

import android.content.Context;
import android.content.SharedPreferences;

public class FmStateManager {

    private static final String SP_NAME = "fm_state";

    // Keys
    private static final String KEY_RUNNING   = "is_running";
    private static final String KEY_FREQUENCY = "frequency";
    private static final String KEY_INFO = "info";
    private static final String KEY_LIVEINFO  = "live_info";

    private final SharedPreferences sp;

    public FmStateManager(Context context) {
        sp = context.getApplicationContext()
                .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    /* ---------- FM running ---------- */

    public void setRunning(boolean running) {
        sp.edit().putBoolean(KEY_RUNNING, running).apply();
    }

    public boolean isRunning() {
        return sp.getBoolean(KEY_RUNNING, false);
    }

    /* ---------- Frequency ---------- */

    public void setFrequency(String freq) {
        sp.edit().putString(KEY_FREQUENCY, freq).apply();
    }

    public String getFrequency() {
        return sp.getString(KEY_FREQUENCY, "87.0");
    }


    /* ---------- info ---------- */

    public void setInfo(String freq) {
        sp.edit().putString(KEY_INFO, freq).apply();
    }

    public String getInfo() {
        return sp.getString(KEY_INFO, "");
    }

    /* ---------- Live info ---------- */

    public void setLiveInfoEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_LIVEINFO, enabled).apply();
    }

    public boolean isLiveInfoEnabled() {
        return sp.getBoolean(KEY_LIVEINFO, false);
    }
}

package com.myapp.myfm;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.Log;



public class FMStateManager {
    private FMState currentState;
    private final MainActivity activity;
    private final MyFmApp app;


    // 颜色定义
    private final String COLOR_ACCENT = "#167c80";
    private final String COLOR_DEFAULT = "#e5e5e5";
    private final String COLOR_WARNING = "#ffcc00";

    public FMStateManager(MainActivity activity, MyFmApp app) {
        this.activity = activity;
        this.app = app;

        // 从Application恢复状态
        String savedState = app.fm_state;
        Log.d("FMStateManager", "Saved state from app: " + savedState);

        if (savedState != null && !savedState.isEmpty()) {
            try {
                currentState = FMState.valueOf(savedState);
                Log.d("FMStateManager", "Successfully restored state: " + currentState);
            } catch (IllegalArgumentException e) {
                Log.e("FMStateManager", "Invalid saved state: " + savedState);
                currentState = FMState.STOPPED;
            }
        } else {
            Log.d("FMStateManager", "No saved state, default to STOPPED");
            currentState = FMState.STOPPED;
        }

    }

    public synchronized void setState(FMState newState) {
        FMState oldState = currentState;
        currentState = newState;

        // 保存状态
        app.fm_state = newState.name();

        // 更新UI
        updateUIForState(newState, oldState);

        Log.d("FMState", "State changed: " + oldState + " -> " + newState);
    }

    private void updateUIForState(FMState newState, FMState oldState) {
        // 确保在主线程更新UI
        activity.runOnUiThread(() -> {
            switch (newState) {
                case PAUSE:
                    updateUIForPause();
                    break;
                case CONNECTING:
                    updateUIForConnecting();
                    break;
                case PLAY:
                    updateUIForPlay();
                    break;
                case ERROR:
                    updateUIForError();
                    break;
                case STOPPING:
                    updateUIForStopping();
                    break;
            }
        });
    }

    private void updateUIForPause() {
        activity.btnPower.setText("ON");
        activity.btnPower.setTextColor(Color.parseColor("#000000"));
        activity.btnPower.setEnabled(true);
        activity.btnPower.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(COLOR_DEFAULT)));
        activity.btnScan.setEnabled(false);
        activity.btnNext.setEnabled(false);
        activity.btnTuneUp.setEnabled(false);
        activity.btnPre.setEnabled(false);
        activity.btnTuneDown.setEnabled(false);
        activity.tvInfo.setText("FM收音机已就绪");

        // 重置仪表
        activity.updateRSSIView(activity.rssi_meter_wrap, activity.rssi_meter, 0);
        activity.updateVolumeView(activity.vol_meter_wrap, activity.vol_meter, 0);

        // 重置频率显示（可选）
        // activity.tvFreq.setText("--.-");
    }

    private void updateUIForPlay() {
        activity.btnPower.setText("OFF");
        activity.btnPower.setTextColor(Color.parseColor("#ffffff"));
        activity.btnPower.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT)));
        activity.btnPower.setEnabled(true);
        activity.btnScan.setEnabled(true);
        activity.btnNext.setEnabled(true);
        activity.btnTuneUp.setEnabled(true);
        activity.btnPre.setEnabled(true);
        activity.btnTuneDown.setEnabled(true);
        activity.tvInfo.setText("正在播放...");
    }

    private void updateUIForConnecting() {
        activity.btnPower.setText("启动中...");
        activity.btnPower.setTextColor(Color.parseColor("#000000"));
        //activity.btnPower.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(COLOR_WARNING)));
        activity.btnPower.setEnabled(false); // 防止重复点击
        activity.btnScan.setEnabled(false);
        activity.tvInfo.setText("正在启动fm_service...");
    }

    private void updateUIForConnected() {
        activity.btnPower.setText("就绪");
        activity.btnPower.setBackgroundColor(Color.parseColor(COLOR_DEFAULT));
        activity.btnPower.setEnabled(true);
        activity.btnScan.setEnabled(false);
        activity.tvInfo.setText("已连接，可以点击打开收音机");
    }


    private void updateUIForError() {
        activity.btnPower.setText("重试");
        activity.btnPower.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT)));
        activity.btnPower.setEnabled(true);
        activity.btnScan.setEnabled(false);
        activity.tvInfo.setText("连接出错，请重试");
    }

    private void updateUIForStopping() {
        activity.btnPower.setText("停止中...");
        activity.btnPower.setBackgroundColor(Color.parseColor(COLOR_WARNING));
        activity.btnPower.setEnabled(false);
        activity.btnScan.setEnabled(false);
        activity.tvInfo.setText("正在停止服务...");
    }

    public FMState getCurrentState() {
        return currentState;
    }



    public boolean isConnected() {
        return currentState == FMState.PAUSE;
    }

    public boolean canOperate() {
        return currentState != FMState.STOPPING ||currentState != FMState.CONNECTING||currentState != FMState.ERROR;
    }
}
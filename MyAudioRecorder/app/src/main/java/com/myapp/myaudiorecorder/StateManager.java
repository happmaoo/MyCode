package com.myapp.myaudiorecorder;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

public class StateManager {
    private MyState currentState;
    private final MainActivity activity;
    private final MyApp app;

    TextView textView_log,textView_local,textView_net;
    EditText editText_net,editText_local;

    Button btn_start;

    private RadioGroup radioGroup;
    RadioButton radioButton_loc,radioButton_net;
    LinearLayout layout_local,layout_net;
    // 颜色定义
    private final String COLOR_ACCENT = "#167c80";
    private final String COLOR_DEFAULT = "#e5e5e5";
    private final String COLOR_WARNING = "#ffcc00";

    public StateManager(MainActivity activity, MyApp app) {
        this.activity = activity;
        this.app = app;



        // 从Application恢复状态
        String savedState = app.my_state;
        Log.d("FMStateManager", "Saved state from app: " + savedState);

        if (savedState != null && !savedState.isEmpty()) {
            try {
                currentState = MyState.valueOf(savedState);
                Log.d("FMStateManager", "Successfully restored state: " + currentState);
            } catch (IllegalArgumentException e) {
                Log.e("FMStateManager", "Invalid saved state: " + savedState);
                currentState = MyState.STOPPED;
            }
        } else {
            Log.d("FMStateManager", "No saved state, default to STOPPED");
            currentState = MyState.STOPPED;
        }

    }

    public synchronized void setState(MyState newState) {
        MyState oldState = currentState;
        currentState = newState;

        // 保存状态
        app.my_state = newState.name();

        // 更新UI
        updateUIForState(newState, oldState);

        Log.d("FMState", "State changed: " + oldState + " -> " + newState);
    }

    private void updateUIForState(MyState newState, MyState oldState) {
        // 确保在主线程更新UI
        activity.runOnUiThread(() -> {
            switch (newState) {
                case RECORDDING:
                    updateUIForRECORDDING();
                    break;
                case STOPPED:
                    updateUIForSTOPPED();
                    break;
                case STOPPING:
                    //updateUIForPlay();
                    break;
            }
        });
    }


    public MyState getCurrentState() {
        return currentState;
    }

public void updateUIForSTOPPED(){
    activity.btn_start.setText("开始");
    activity.btn_start.setTextColor(Color.parseColor("#000000"));
    activity.btn_start.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(COLOR_DEFAULT)));
}


    public void updateUIForRECORDDING(){
        activity.btn_start.setText("停止");
        activity.btn_start.setTextColor(Color.parseColor("#ffffff"));
        activity.btn_start.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT)));
    }


}
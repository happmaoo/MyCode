package com.myapp.myalarmmanager;

import android.app.Activity;
import android.os.Bundle;

/**
 * 不可见入口：只做两件事——清掉系统的“已强行停止”标志、拉起前台服务，然后立刻结束。
 * 用 am start -n com.myapp.myalarmmanager/.StarterActivity 即可，不会显示任何界面。
 */
public class StarterActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        KeepAliveService.start(this);
        // 自动开始循环：打开循环开关，按“首次延迟”排第一轮，
        // 之后每次跑完由 AlarmTriggerReceiver 按“循环间隔”续排
        Scheduler.startLoop(this);
        KeepAliveService.refresh(this);
        finish(); // 必须在 onResume 之前结束，NoDisplay 主题才不会被系统判违规
    }
}

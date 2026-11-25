package com.myapp.myalarmmanager;

import android.app.ActionBar;
import android.app.Activity;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;

/** 帮助页：说明启动方式、广播参数、排查命令 */
public class HelpActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setDisplayHomeAsUpEnabled(true);
            bar.setTitle("帮助");
        }

        ((TextView) findViewById(R.id.tv_help)).setText(HELP);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static final String HELP =
            "一、启动定时（无界面）\n"
            + "am start -n com.myapp.myalarmmanager/.StarterActivity\n\n"
            + "会做三件事：清掉系统的“已强行停止”标志、拉起前台服务、按“循环间隔”排好下一次。\n\n"
            + "二、从脚本/其他 App 触发一次\n"
            + "am broadcast -a com.myapp.myalarmmanager.SET_ALARM \\\n"
            + "  --el delay_sec 60 -p com.myapp.myalarmmanager\n\n"
            + "delay_sec 是“多少秒后触发一次”，不影响循环节奏。\n\n"
            + "三、查看当前状态\n"
            + "su -c 'dumpsys alarm | grep -A2 com.myapp.myalarmmanager'\n"
            + "su -c 'dumpsys activity services com.myapp.myalarmmanager'\n\n"
            + "四、运行规则\n"
            + "· 按“循环间隔”对齐时间轴：间隔 600 秒就跑在 :00 :10 :20，300 秒就跑在 :00 :05 :10。\n"
            + "· 首轮也要等边界，不会启动就立刻跑；需要立刻验证请点“立即执行”。\n"
            + "· 勾“用 root 运行”走 su -c；不勾走 /system/bin/sh。\n"
            + "· 非 root 模式下读不到 /data/data/com.termux，脚本请放 /data/local/tmp。\n"
            + "· “停止”会取消闹钟并关闭前台通知；再点“启动”会重新拉起。\n\n"
            + "五、运行日志\n"
            + "/data/data/com.myapp.myalarmmanager/files/last_run.log\n"
            + "su -c 'cat /data/data/com.myapp.myalarmmanager/files/last_run.log'\n\n"
            + "六、省电与唤醒\n"
            + "· 两次触发之间进程完全睡眠，不持 wake lock。\n"
            + "· Doze 下精确闹钟每 App 最低 9 分钟一次，间隔小于 9 分钟会被合并。\n"
            + "· 建议把 App 加入电池优化白名单，否则待机分组会把闹钟推迟几十分钟。\n";
}

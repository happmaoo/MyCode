package com.myapp.mqtt.base;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * 日志专用 ScrollView。
 *
 * 原生 ScrollView 在 onLayout 时会调用 computeScrollDeltaToGetChildRectOnScreen()，
 * 把「有焦点/有光标的子 View」的位置滚进可视区。tv_result 是可选中（bufferType=editable）、
 * 光标可能停在任何位置，于是新内容 append 或上方状态栏高度变化触发重排时，
 * 系统就会自作主张把页面拉回光标处 —— 接着我们的 pinToBottom() 会误判「用户翻上去了」而停止跟随。
 * 这里直接返回 0，掐掉这条自动滚动路径；长按选择、COPY 都不受影响。
 */
public class LogScrollView extends ScrollView {

    public LogScrollView(Context context) {
        super(context);
    }

    public LogScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public LogScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }
}

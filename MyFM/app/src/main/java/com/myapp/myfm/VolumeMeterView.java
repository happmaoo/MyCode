package com.myapp.myfm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class VolumeMeterView extends View {
    private float currentLevel = 0; // 0-100
    private float peakLevel = 0;
    private long lastPeakTime = 0;

    private Paint paint = new Paint();

    public VolumeMeterView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setLevel(int level) {
        this.currentLevel = level;
        if (level > peakLevel) {
            peakLevel = level;
            lastPeakTime = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - lastPeakTime > 100) {
            peakLevel *= 0.01f;
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();

        // 1. 绘制背景（浅灰色槽）
        paint.setColor(Color.parseColor("#E0E0E0"));
        canvas.drawRect(0, 0, width, height, paint);

        // 2. 计算音量条的高度
        // 注意：Canvas 坐标系中 0 是顶部，height 是底部
        float fillHeight = (currentLevel / 100f) * height;
        float top = height - fillHeight; // 向上增长

        // 3. 绘制音量条
        paint.setColor(Color.parseColor("#65A45E"));
        canvas.drawRect(0, top, width, height, paint);

        // 4. 绘制峰值保持线（横线）
//        paint.setColor(Color.BLACK);
//        paint.setStrokeWidth(8f);
//        float peakY = height - (peakLevel / 100f) * height;
//        canvas.drawLine(0, peakY, width, peakY, paint);
    }
}

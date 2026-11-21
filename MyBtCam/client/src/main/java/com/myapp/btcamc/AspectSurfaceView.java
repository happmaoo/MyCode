package com.myapp.btcamc;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** 按视频宽高比自动适配的 SurfaceView，多余区域留黑边居中。 */
public class AspectSurfaceView extends SurfaceView {

    private int videoWidth = 640;
    private int videoHeight = 480;

    public AspectSurfaceView(Context context) {
        super(context);
    }

    public AspectSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setVideoSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (width == videoWidth && height == videoHeight) {
            return;
        }
        videoWidth = width;
        videoHeight = height;
        requestLayout();
    }

    /**
     * 把 Surface 的缓冲区固定成视频本身的尺寸。
     *
     * 这样无论解码器内部是「等比留黑边」还是「拉伸铺满」，它渲染的目标都是一块和视频同比例的
     * 缓冲区，再由系统合成器等比缩放到控件大小，所以永远不会被拉宽压扁。
     */
    public void applySurfaceSize() {
        SurfaceHolder holder = getHolder();
        if (holder == null) {
            return;
        }
        if (videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        try {
            holder.setFixedSize(videoWidth, videoHeight);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (width <= 0 || height <= 0) {
            setMeasuredDimension(width, height);
            return;
        }
        float scale = Math.min(width / (float) videoWidth, height / (float) videoHeight);
        setMeasuredDimension((int) (videoWidth * scale), (int) (videoHeight * scale));
    }
}

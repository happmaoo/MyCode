package com.myapp.myfilemanager.plugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * 支持双指缩放、拖拽平移、双击放大/还原的图片视图。
 * 用 Matrix 模式手动处理变换，不依赖任何第三方库。
 */
public class ZoomImageView extends ImageView {
    private final Matrix matrix = new Matrix();
    private final float[] m = new float[9];
    private float minScale = 1f;
    private final float maxScale = 8f;
    private ScaleGestureDetector sg;
    private GestureDetector gd;
    private float lastX, lastY;
    private boolean dragging = false;

    public ZoomImageView(Context c) { super(c); init(c); }
    public ZoomImageView(Context c, AttributeSet a) { super(c, a); init(c); }

    private void init(Context c) {
        setScaleType(ScaleType.MATRIX);
        sg = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            public boolean onScale(ScaleGestureDetector d) {
                float scale = getScale();
                float factor = d.getScaleFactor();
                float next = Math.min(maxScale, Math.max(minScale, scale * factor));
                matrix.postScale(next / scale, next / scale, d.getFocusX(), d.getFocusY());
                clamp();
                setImageMatrix(matrix);
                return true;
            }
        });
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            public boolean onDoubleTap(MotionEvent e) {
                float scale = getScale();
                float target = (scale > minScale + 0.02f) ? minScale : Math.min(maxScale, minScale * 3f);
                matrix.postScale(target / scale, target / scale, e.getX(), e.getY());
                clamp();
                setImageMatrix(matrix);
                return true;
            }
        });
    }

    /** 设置位图并按当前视图尺寸做「居中适应」初始化。 */
    public void setBitmap(Bitmap b) {
        setImageBitmap(b);
        post(new Runnable() { public void run() { fit(); } });
    }

    private void fit() {
        Drawable d = getDrawable();
        if (d == null) return;
        int dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        int vw = getWidth() - getPaddingLeft() - getPaddingRight();
        int vh = getHeight() - getPaddingTop() - getPaddingBottom();
        if (vw <= 0 || vh <= 0) return;
        float s = Math.min((float) vw / dw, (float) vh / dh);
        if (s > 1f) s = 1f;   // 图片尺寸未超过界面时按实际像素显示，不做放大
        if (s <= 0) s = 1f;
        minScale = s;
        matrix.setScale(s, s);
        matrix.postTranslate((vw - dw * s) / 2f, (vh - dh * s) / 2f);
        setImageMatrix(matrix);
    }

    private float getScale() {
        matrix.getValues(m);
        return m[Matrix.MSCALE_X];
    }

    /** 约束平移范围，使图片在放大时可自由拖动、缩到小于视图时居中。 */
    private void clamp() {
        Drawable d = getDrawable();
        if (d == null) return;
        int dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        RectF r = new RectF(0, 0, dw, dh);
        matrix.mapRect(r);
        int vw = getWidth() - getPaddingLeft() - getPaddingRight();
        int vh = getHeight() - getPaddingTop() - getPaddingBottom();
        float dx = 0, dy = 0;
        if (r.width() <= vw) dx = (vw - r.width()) / 2f - r.left;
        else { if (r.left > 0) dx = -r.left; if (r.right < vw) dx = vw - r.right; }
        if (r.height() <= vh) dy = (vh - r.height()) / 2f - r.top;
        else { if (r.top > 0) dy = -r.top; if (r.bottom < vh) dy = vh - r.bottom; }
        matrix.postTranslate(dx, dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        sg.onTouchEvent(e);
        gd.onTouchEvent(e);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            lastX = e.getX(); lastY = e.getY(); dragging = true;
        } else if (a == MotionEvent.ACTION_MOVE && dragging) {
            matrix.postTranslate(e.getX() - lastX, e.getY() - lastY);
            lastX = e.getX(); lastY = e.getY();
            clamp();
            setImageMatrix(matrix);
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            dragging = false;
        }
        return true;
    }
}

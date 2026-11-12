package com.myapp.myfilemanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GridView 图标网格适配器：图标 + 名称。
 *
 * 对齐关键：所有格子使用「与字号无关的固定像素高度」——图标区、名称区、
 * 整格高度全部固定。这样即使名称字号变化、文件名 1 行/2 行不等，
 * 每个 item 的测量高度也完全一致，GridView 滚动与 View 复用时不会错位、不会对不齐。
 *
 * 早期版本在名称下方留了一行固定高度的副标题位（后来已不显示任何文字），
 * 现在整行移除以收紧上下间距；「返回」项靠名称与图标辨识。
 * APK 文件的内嵌图标在后台线程解析，用 View tag 校验路径，避免复用错位。
 */
public class FileAdapter extends BaseAdapter {
    private final Context ctx;
    private List<FileItem> items = new ArrayList<FileItem>();
    private Set<String> checked = new HashSet<String>();
    private int iconPx;
    private float nameSp = 13;
    private String fontId = "";   // 列表字体：""=默认 / serif / monospace / bold / 字体文件路径

    // 固定像素高度（不随字号/文字行数变化），保证所有格子逐像素等高
    private final int namePx, padTop, padBottom, padSide;

    // APK 内嵌图标异步解析
    private final ExecutorService apkIo = Executors.newSingleThreadExecutor();
    private final Handler apkUi = new Handler(Looper.getMainLooper());
    private final Set<String> pending = new HashSet<String>();

    // 图片缩略图异步解析（多并发，避免滑动时排队）
    private final ExecutorService thumbIo = Executors.newFixedThreadPool(3);
    private final Set<String> thumbPending = new HashSet<String>();

    public FileAdapter(Context ctx, int iconPx) {
        this.ctx = ctx;
        this.iconPx = iconPx;
        namePx = Util.dp(ctx, 32);      // 名称区：容纳最多 2 行，超出省略
        padTop = Util.dp(ctx, 4);
        padBottom = Util.dp(ctx, 3);
        padSide = Util.dp(ctx, 4);
    }

    public void setData(List<FileItem> data) {
        items = data == null ? new ArrayList<FileItem>() : data;
        notifyDataSetChanged();
    }

    /** 增量追加（供搜索流式结果逐批显示）。 */
    public void addItems(List<FileItem> more) {
        if (more == null || more.isEmpty()) return;
        items.addAll(more);
        notifyDataSetChanged();
    }

    public void setChecked(Set<String> s) { checked = s; notifyDataSetChanged(); }

    /** 图标尺寸与列表名称统一字号；副标题行已移除，不再需要其字号。 */
    public void setSizes(int iconPx, float nameSp) {
        this.iconPx = iconPx;
        this.nameSp = nameSp;
        notifyDataSetChanged();
    }

    /** 列表字体：空串=系统默认，serif/monospace/bold=内置字型，其余解析为字体文件路径。 */
    public void setFont(String id) {
        this.fontId = (id == null) ? "" : id;
        notifyDataSetChanged();
    }

    // 字体缓存：按路径缓存 Typeface，避免列表每格（getView）重复从磁盘解析
    // 大字体文件（如中文/emoji 字体）导致的卡顿；每个字体仅解析一次后复用。
    private static final HashMap<String, Typeface> FONT_CACHE = new HashMap<String, Typeface>();

    private Typeface typeface() {
        if (fontId.isEmpty()) return Typeface.DEFAULT;
        if ("serif".equals(fontId)) return Typeface.SERIF;
        if ("monospace".equals(fontId)) return Typeface.MONOSPACE;
        if ("bold".equals(fontId)) return Typeface.DEFAULT_BOLD;
        synchronized (FONT_CACHE) {
            Typeface cached = FONT_CACHE.get(fontId);
            if (cached != null) return cached;
            try {
                Typeface t = Typeface.createFromFile(fontId);
                if (t != null) {
                    FONT_CACHE.put(fontId, t);
                    return t;
                }
            } catch (Throwable ignored) {}
        }
        return Typeface.DEFAULT;
    }

    /** Activity 销毁时释放后台线程，避免空闲线程泄漏。 */
    public void release() { apkIo.shutdownNow(); thumbIo.shutdownNow(); }

    @Override public int getCount() { return items.size(); }
    @Override public FileItem getItem(int p) { return items.get(p); }
    @Override public long getItemId(int p) { return p; }

    private static class Holder {
        ImageView icon;
        NameText name;
    }

    /**
     * 名称 TextView：根据自身真实宽度判断文字是否只占一行，
     * 单行→水平+垂直居中，多行换行→左对齐。
     * 用自身宽度而非 GridView.getColumnWidth()：后者在 getView 期间可能仍为 0，
     * 导致 avail 为负、所有文件名被错判为多行而一律左对齐。
     */
    private static class NameText extends TextView {
        NameText(Context c) { super(c); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            apply(w);
        }
        @Override protected void onTextChanged(CharSequence t, int s, int b, int a) {
            super.onTextChanged(t, s, b, a);
            apply(getWidth());
        }
        private void apply(int w) {
            if (w <= 0) return;   // 尚未测量到宽度，等 onSizeChanged 再处理
            boolean oneLine = getPaint().measureText(getText().toString()) <= w;
            setGravity(oneLine
                    ? Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL
                    : Gravity.START | Gravity.TOP);
        }
    }

    @Override
    public View getView(int pos, View cv, ViewGroup parent) {
        Holder h;
        LinearLayout cell;
        if (cv instanceof LinearLayout && ((LinearLayout) cv).getTag() instanceof Holder) {
            cell = (LinearLayout) cv;
            h = (Holder) cell.getTag();
        } else {
            cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            cell.setPadding(padSide, padTop, padSide, padBottom);

            ImageView icon = new ImageView(ctx);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(icon, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            NameText name = new NameText(ctx);
            name.setGravity(Gravity.START | Gravity.TOP);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // 固定高度：文字行数或字号变化都不会改变格子高度
            cell.addView(name, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, namePx));

            h = new Holder();
            h.icon = icon; h.name = name;
            cell.setTag(h);
        }

        // 整格固定高度 = padding + 图标 + 名称区（不再有空的副标题行）
        int cellH = padTop + iconPx + namePx + padBottom;
        cell.setLayoutParams(new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cellH));

        FileItem it = items.get(pos);
        boolean sel = checked.contains(it.path);
        Typeface tf = typeface();
        // 隐藏文件（以 . 开头，且非「返回」项）用灰色字体，与正常文件区分
        boolean hidden = !it.parent && it.name.startsWith(".");

        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconPx, iconPx);
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        h.icon.setLayoutParams(ilp);
        h.icon.setTag(it.path);        // APK 异步回调据此校验，避免 View 复用后贴错图标

        h.name.setText(it.name);
        h.name.setTextSize(nameSp);
        h.name.setTypeface(tf);
        h.name.setTextColor(hidden ? Ui.HINT : Ui.TEXT);

        cell.setBackgroundColor(sel ? Ui.PRIMARY_SOFT : Color.TRANSPARENT);

        int kind = Util.kind(it.name, it.dir, it.parent);
        if (kind == Util.T_APK && !it.parent && !it.dir) {
            final String key = IconFactory.apkKey(it, iconPx);
            Bitmap ab = IconFactory.apkCached(key);
            if (ab != null) {
                h.icon.setImageBitmap(ab);
            } else {
                h.icon.setImageBitmap(IconFactory.get(it, iconPx));   // 主题图标占位
                if (!IconFactory.apkFailed(key)) requestApk(key, it.path, iconPx, h.icon);
            }
        } else if (kind == Util.T_IMAGE && !it.parent && !it.dir) {
            // 图片：先显示主题图标占位，后台解码真实缩略图后替换
            final String key = IconFactory.thumbKey(it, iconPx);
            Bitmap tb = IconFactory.thumbCached(key);
            if (tb != null) {
                h.icon.setImageBitmap(tb);
            } else {
                h.icon.setImageBitmap(IconFactory.get(it, iconPx));   // 主题图标占位
                if (!IconFactory.thumbFailed(key)) requestThumb(key, it.path, iconPx, h.icon);
            }
        } else {
            h.icon.setImageBitmap(IconFactory.get(it, iconPx));
        }
        return cell;
    }

    /** 后台解析 APK 图标；只更新 tag 仍等于该路径的 ImageView。 */
    private void requestApk(final String key, final String path, final int px, final ImageView iv) {
        synchronized (pending) { if (!pending.add(key)) return; }
        apkIo.execute(new Runnable() {
            public void run() {
                final Bitmap b = IconFactory.loadApkIcon(path, px);
                apkUi.post(new Runnable() {
                    public void run() {
                        synchronized (pending) { pending.remove(key); }
                        if (b == null) { IconFactory.markApkFailed(key); return; }
                        IconFactory.putApk(key, b);
                        if (path.equals(iv.getTag())) iv.setImageBitmap(b);
                    }
                });
            }
        });
    }

    /** 后台解码图片缩略图；只更新 tag 仍等于该路径的 ImageView（View 复用后不会贴错图）。 */
    private void requestThumb(final String key, final String path, final int px, final ImageView iv) {
        synchronized (thumbPending) { if (!thumbPending.add(key)) return; }
        thumbIo.execute(new Runnable() {
            public void run() {
                final Bitmap b = IconFactory.loadThumb(path, px);
                apkUi.post(new Runnable() {
                    public void run() {
                        synchronized (thumbPending) { thumbPending.remove(key); }
                        if (b == null) { IconFactory.markThumbFailed(key); return; }
                        IconFactory.putThumb(key, b);
                        if (path.equals(iv.getTag())) iv.setImageBitmap(b);
                    }
                });
            }
        });
    }
}

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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RecyclerView 版图标网格适配器（取代 GridView 的 FileAdapter）。
 *
 * <p>迁移到 RecyclerView 的核心收益：{@code notifyDataSetChanged()} 不会重置滚动位置，
 * 配合 {@code GridLayoutManager.scrollToPositionWithOffset()} 可可靠地「返回记住位置」，
 * 不再出现 GridView 恢复后被布局覆盖的问题。
 *
 * <p>对齐关键沿用旧版：所有格子「与字号无关的固定像素高度」，保证逐像素等高、滚动/复用不错位。
 * 点击/长按通过 {@link Listener} 回调给 Activity（RecyclerView 无内置 item click）。
 */
public class RvFileAdapter extends RecyclerView.Adapter<RvFileAdapter.Holder> {

    public interface Listener {
        void onItemClick(FileItem it, View cell);
        void onItemLongClick(FileItem it, View cell);
    }

    private final Context ctx;
    private List<FileItem> items = new ArrayList<FileItem>();
    private Set<String> checked = new HashSet<String>();
    private int iconPx;
    private float nameSp = 13;
    private String fontId = "";
    private Listener listener;
    private boolean listMode = false;   // true=列表视图（单列、图标在左、名称+大小/时间两行）

    private final int namePx, padTop, padBottom, padSide;

    private final ExecutorService apkIo = Executors.newSingleThreadExecutor();
    private final Handler apkUi = new Handler(Looper.getMainLooper());
    private final Set<String> pending = new HashSet<String>();
    private final ExecutorService thumbIo = Executors.newFixedThreadPool(3);
    private final Set<String> thumbPending = new HashSet<String>();

    public RvFileAdapter(Context ctx, int iconPx) {
        this.ctx = ctx;
        this.iconPx = iconPx;
        namePx = Util.dp(ctx, 32);
        padTop = Util.dp(ctx, 4);
        padBottom = Util.dp(ctx, 3);
        padSide = Util.dp(ctx, 4);
    }

    public void setListener(Listener l) { listener = l; }

    public void setData(List<FileItem> data) {
        items = data == null ? new ArrayList<FileItem>() : data;
        notifyDataSetChanged();   // RecyclerView：不重置滚动
    }

    public void addItems(List<FileItem> more) {
        if (more == null || more.isEmpty()) return;
        int start = items.size();
        items.addAll(more);
        notifyItemRangeInserted(start, more.size());
    }

    public void setChecked(Set<String> s) { checked = s; notifyDataSetChanged(); }

    public void setSizes(int iconPx, float nameSp) {
        this.iconPx = iconPx; this.nameSp = nameSp; notifyDataSetChanged();
    }

    public void setFont(String id) { fontId = (id == null) ? "" : id; notifyDataSetChanged(); }

    /** 切换网格 / 列表视图。两种布局用不同 viewType，RecyclerView 不会混用它们的 ViewHolder。 */
    public void setViewMode(boolean list) {
        if (listMode == list) return;
        listMode = list;
        notifyDataSetChanged();
    }

    @Override public int getItemViewType(int position) { return listMode ? 1 : 0; }

    public void release() { apkIo.shutdownNow(); thumbIo.shutdownNow(); }

    @Override public int getItemCount() { return items.size(); }
    /** 兼容旧调用。 */
    public int getCount() { return items.size(); }
    public FileItem getItem(int p) { return items.get(p); }

    public static class Holder extends RecyclerView.ViewHolder {
        ImageView icon; NameText name; TextView listName, meta; LinearLayout cell;
        Holder(LinearLayout c) { super(c); cell = c; }
    }

    private static class NameText extends TextView {
        NameText(Context c) { super(c); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); apply(w); }
        @Override protected void onTextChanged(CharSequence t, int s, int b, int a) { super.onTextChanged(t, s, b, a); apply(getWidth()); }
        private void apply(int w) {
            if (w <= 0) return;
            boolean one = getPaint().measureText(getText().toString()) <= w;
            setGravity(one ? Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL : Gravity.START | Gravity.TOP);
        }
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == 1) {
            // 列表视图：整宽单行，图标在左，右侧名称 + 副标题（大小 / 修改时间）
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(Util.dp(ctx, 10), Util.dp(ctx, 3), Util.dp(ctx, 10), Util.dp(ctx, 3));

            ImageView icon = new ImageView(ctx);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(icon, new LinearLayout.LayoutParams(iconPx, iconPx));

            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(ctx);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView meta = new TextView(ctx);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            meta.setTextSize(11);
            meta.setTextColor(Ui.HINT);
            texts.addView(name, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            texts.addView(meta, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.leftMargin = Util.dp(ctx, 10);
            cell.addView(texts, tlp);

            Holder h = new Holder(cell);
            h.icon = icon; h.listName = name; h.meta = meta;
            return h;
        }
        // 网格视图：图标在上、名称在下，固定像素高度保证逐格等高
        LinearLayout cell = new LinearLayout(ctx);
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
        cell.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, namePx));

        Holder h = new Holder(cell);
        h.icon = icon; h.name = name;
        return h;
    }

    @Override
    public void onBindViewHolder(Holder h, int pos) {
        final FileItem it = items.get(pos);
        boolean sel = checked.contains(it.path);
        Typeface tf = typeface();
        boolean hidden = !it.parent && it.name.startsWith(".");

        h.cell.setBackgroundColor(sel ? Ui.PRIMARY_SOFT : Color.TRANSPARENT);
        h.icon.setTag(it.path);

        if (listMode) {
            int rowH = Math.max(iconPx, Util.dp(ctx, 40)) + Util.dp(ctx, 8);
            h.cell.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowH));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconPx, iconPx);
            ilp.gravity = Gravity.CENTER_VERTICAL;
            h.icon.setLayoutParams(ilp);
            h.listName.setText(it.name);
            h.listName.setTextSize(nameSp + 1);
            h.listName.setTypeface(tf);
            h.listName.setTextColor(hidden ? Ui.HINT : Ui.TEXT);
            h.meta.setText(metaText(it));
        } else {
            int cellH = padTop + iconPx + namePx + padBottom;
            h.cell.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, cellH));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconPx, iconPx);
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
            h.icon.setLayoutParams(ilp);
            h.name.setText(it.name);
            h.name.setTextSize(nameSp);
            h.name.setTypeface(tf);
            h.name.setTextColor(hidden ? Ui.HINT : Ui.TEXT);
        }

        bindIcon(h.icon, it);

        h.cell.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (listener != null) listener.onItemClick(it, v); }
        });
        h.cell.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (listener != null) listener.onItemLongClick(it, v);
                return true;
            }
        });
    }

    /** 网格 / 列表共用的图标加载：APK 图标、图片缩略图走异步缓存，其余直接取主题图标。 */
    private void bindIcon(ImageView iv, FileItem it) {
        int kind = Util.kind(it.name, it.dir, it.parent);
        if (kind == Util.T_APK && !it.parent && !it.dir) {
            final String key = IconFactory.apkKey(it, iconPx);
            Bitmap ab = IconFactory.apkCached(key);
            if (ab != null) iv.setImageBitmap(ab);
            else {
                iv.setImageBitmap(IconFactory.get(it, iconPx));
                if (!IconFactory.apkFailed(key)) requestApk(key, it.path, iconPx, iv);
            }
        } else if (kind == Util.T_IMAGE && !it.parent && !it.dir) {
            final String key = IconFactory.thumbKey(it, iconPx);
            Bitmap tb = IconFactory.thumbCached(key);
            if (tb != null) iv.setImageBitmap(tb);
            else {
                iv.setImageBitmap(IconFactory.get(it, iconPx));
                if (!IconFactory.thumbFailed(key)) requestThumb(key, it.path, iconPx, iv);
            }
        } else {
            iv.setImageBitmap(IconFactory.get(it, iconPx));
        }
    }

    /** 列表视图副标题：返回项 / 文件夹显示类型，文件显示大小与修改时间。 */
    private String metaText(FileItem it) {
        if (it.parent) return "返回";
        if (it.dir) return "文件夹";
        String t = Util.time(it.modified);
        return t.isEmpty() ? Util.size(it.size) : Util.size(it.size) + "   " + t;
    }

    private static final HashMap<String, Typeface> FONT_CACHE = new HashMap<String, Typeface>();
    private Typeface typeface() {
        if (fontId.isEmpty()) return Typeface.DEFAULT;
        if ("serif".equals(fontId)) return Typeface.SERIF;
        if ("monospace".equals(fontId)) return Typeface.MONOSPACE;
        if ("bold".equals(fontId)) return Typeface.DEFAULT_BOLD;
        synchronized (FONT_CACHE) {
            Typeface c = FONT_CACHE.get(fontId);
            if (c != null) return c;
            try {
                Typeface t = Typeface.createFromFile(fontId);
                if (t != null) { FONT_CACHE.put(fontId, t); return t; }
            } catch (Throwable ignored) {}
        }
        return Typeface.DEFAULT;
    }

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

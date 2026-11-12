package com.myapp.myfilemanager;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按路径持久化的列表滚动位置记忆（「返回记住位置」）。
 *
 * <p>背景：旧实现只有一个 {@code archiveExitScroll} 临时变量 + 每标签一个 {@code Tab.scroll}，
 * 既不按路径区分、也不落盘，导致从 7z 压缩包插件返回、切标签、或重启应用后列表跳回顶部。
 * 这里改为「路径 -> (第一可见项下标, 首项像素偏移)」的持久化映射：
 * <ul>
 *   <li>离开某个目录（进入子目录 / 进入压缩包 / 返回上级 / 切标签 / 退后台）前记录当前位置；</li>
 *   <li>再次导航到该路径时恢复记录的位置，像素级对齐（{@code setSelectionFromTop(pos, top)}）；</li>
 *   <li>用 SharedPreferences 落盘，进程被回收 / 重启后依然记得。</li>
 * </ul>
 *
 * <p>容量控制：LRU（accessOrder）上限 {@link #MAX_ENTRIES}，超出淘汰最久未用，避免无限增长。
 * 存档为单个 JSON 对象 {@code {path:[pos,top]}}，读写都在主线程调用，方法加锁兜底。
 */
final class ScrollMemory {

    private static final String FILE = "scroll_memory";
    private static final String KEY = "map";
    private static final int MAX_ENTRIES = 512;

    /** accessOrder=true：get/put 都会把条目移到队尾，队首即最久未用。 */
    private final LinkedHashMap<String, int[]> map =
            new LinkedHashMap<String, int[]>(16, 0.75f, true);

    private ScrollMemory() {
    }

    /** 从磁盘加载；损坏 / 不存在时返回空记忆。 */
    static ScrollMemory load(Context ctx) {
        ScrollMemory m = new ScrollMemory();
        try {
            SharedPreferences sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            String json = sp.getString(KEY, null);
            if (json == null || json.isEmpty()) return m;
            JSONObject o = new JSONObject(json);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String path = it.next();
                org.json.JSONArray a = o.optJSONArray(path);
                if (a == null || a.length() < 2) continue;
                int pos = a.optInt(0, 0);
                int top = a.optInt(1, 0);
                if (pos < 0) pos = 0;
                m.map.put(path, new int[]{pos, top});
            }
            m.trim();
        } catch (Throwable ignored) {
            // 存档损坏：当作空记忆，不影响主流程
        }
        return m;
    }

    /** 记录某路径的滚动位置（第一可见项下标 + 首项像素偏移）。 */
    synchronized void remember(String path, int pos, int top) {
        if (path == null || path.isEmpty()) return;
        if (pos < 0) pos = 0;
        map.put(path, new int[]{pos, top});
        trim();
    }

    /** 取某路径的记忆位置；没有返回 null。返回 {pos, top}。 */
    synchronized int[] recall(String path) {
        if (path == null) return null;
        int[] v = map.get(path);
        return v == null ? null : new int[]{v[0], v[1]};
    }

    /** 是否已有某路径的记忆。 */
    synchronized boolean has(String path) {
        return path != null && map.containsKey(path);
    }

    private void trim() {
        while (map.size() > MAX_ENTRIES) {
            Iterator<String> it = map.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            } else {
                break;
            }
        }
    }

    /** 异步落盘。 */
    synchronized void persist(Context ctx) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, int[]> e : map.entrySet()) {
                org.json.JSONArray a = new org.json.JSONArray();
                a.put(e.getValue()[0]);
                a.put(e.getValue()[1]);
                o.put(e.getKey(), a);
            }
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putString(KEY, o.toString()).apply();
        } catch (Throwable ignored) {
            // 落盘失败不影响内存记忆
        }
    }
}

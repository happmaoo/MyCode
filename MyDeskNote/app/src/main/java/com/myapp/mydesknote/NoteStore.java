package com.myapp.mydesknote;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 便签数据的读写。界面和桌面小工具共用这一份，避免两边格式不一致。
 */
public final class NoteStore {

    public static final String PREFS = "notes";
    public static final String KEY_ITEMS = "items";
    public static final String KEY_WIDGET_SIZE = "widget_font_size";

    /** 桌面小工具字号默认值 / 取值范围（sp） */
    public static final float DEFAULT_WIDGET_SIZE = 12f;
    public static final float MIN_WIDGET_SIZE = 8f;
    public static final float MAX_WIDGET_SIZE = 40f;

    private static final String TAG = "NoteStore";

    private NoteStore() {
    }

    public static class Item {
        public long id;
        public String text;
        public long due;
    }

    public static List<Item> load(Context context) {
        List<Item> items = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_ITEMS, null);
        if (raw == null) {
            return items;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                Item item = new Item();
                item.id = obj.optLong("id");
                item.text = obj.optString("text", "");
                item.due = obj.optLong("due");
                if (!TextUtils.isEmpty(item.text) && item.due > 0) {
                    items.add(item);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "读取项目失败", e);
        }
        return items;
    }

    public static float getWidgetTextSize(Context context) {
        float size = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_WIDGET_SIZE, DEFAULT_WIDGET_SIZE);
        return clampSize(size);
    }

    public static void setWidgetTextSize(Context context, float size) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putFloat(KEY_WIDGET_SIZE, clampSize(size)).apply();
    }

    private static float clampSize(float size) {
        if (size < MIN_WIDGET_SIZE) {
            return MIN_WIDGET_SIZE;
        }
        return Math.min(size, MAX_WIDGET_SIZE);
    }

    public static void save(Context context, List<Item> items) {
        try {
            JSONArray array = new JSONArray();
            for (Item item : items) {
                JSONObject obj = new JSONObject();
                obj.put("id", item.id);
                obj.put("text", item.text);
                obj.put("due", item.due);
                array.put(obj);
            }
            // 便签数据不能丢，用 commit 同步落盘
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_ITEMS, array.toString()).commit();
        } catch (Exception e) {
            Log.e(TAG, "保存项目失败", e);
        }
    }
}

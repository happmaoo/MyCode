package com.myapp.myfilemanager.plugin.sevenzip;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 字符串小工具：集中实现 Kotlin 标准库扩展函数的等价物。 */
public final class StrUtil {
    private StrUtil() {}

    /** 等价 Kotlin lineSequence()：按 \n / \r\n / \r 切分，末尾换行不产生额外空行 */
    static List<String> lines(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                out.add(s.substring(start, i));
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(s.substring(start));
        return out;
    }

    static String join(List<String> parts, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) b.append(sep);
            b.append(parts.get(i));
        }
        return b.toString();
    }

    /** 等价 Kotlin take(n)：取前 n 个字符，不足则原样返回 */
    static String take(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** 等价 Kotlin trimEnd(c)：只去尾部指定字符 */
    static String trimEndChar(String s, char c) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == c) end--;
        return s.substring(0, end);
    }

    /** 等价 Kotlin trim(c)：去首尾指定字符 */
    static String trimChar(String s, char c) {
        int start = 0, end = s.length();
        while (start < end && s.charAt(start) == c) start++;
        while (end > start && s.charAt(end - 1) == c) end--;
        return s.substring(start, end);
    }

    /** 等价 Kotlin toLongOrNull() ?: def */
    static long parseLongOr(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 等价 Kotlin lowercase()：与设备 locale 无关 */
    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** 等价 Kotlin text.split(delim).filter { it.isNotEmpty() }，用于包内路径分段 */
    static List<String> splitNonEmpty(String s, char delim) {
        List<String> out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == delim) {
                String part = s.substring(start, i);
                if (!part.isEmpty()) out.add(part);
                start = i + 1;
            }
        }
        return out;
    }
}

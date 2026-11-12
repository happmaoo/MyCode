package com.myapp.myfilemanager.plugin.sevenzip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/** Kotlin 的顶层函数 buildTree / sortedChildren 在 Java 里归入工具类 */
public final class ArchiveTree {
    private ArchiveTree() {}

    /**
     * 压缩包里经常没有显式的目录条目（zip 尤其常见），
     * 所以中间层级要自动补建目录节点。
     */
    public static TreeNode buildTree(List<SevenZipEntry> entries) {
        TreeNode root = new TreeNode("");
        for (SevenZipEntry e : entries) {
            List<String> segs = StrUtil.splitNonEmpty(StrUtil.trimChar(e.path, '/'), '/');
            if (segs.isEmpty()) continue;
            TreeNode node = root;
            for (int i = 0; i < segs.size(); i++) {
                boolean last = (i == segs.size() - 1);
                String name = segs.get(i);
                TreeNode child = node.getChildren().get(name);
                if (child == null) {
                    child = new TreeNode(name, !last || e.isDir());
                    child.setParent(node);
                    node.getChildren().put(name, child);
                }
                if (last) {
                    child.setIsDir(e.isDir());
                    child.setEntry(e);
                } else {
                    child.setIsDir(true);
                }
                node = child;
            }
        }
        return root;
    }

    /** 目录在前、同层按名称排序，接近文件管理器的习惯。稳定排序与 Kotlin sortedWith 一致 */
    public static List<TreeNode> sortedChildren(TreeNode node) {
        List<TreeNode> list = new ArrayList<TreeNode>(node.getChildren().values());
        Collections.sort(list, new Comparator<TreeNode>() {
            public int compare(TreeNode a, TreeNode b) {
                if (a.isDir() != b.isDir()) return a.isDir() ? -1 : 1;
                return StrUtil.lower(a.getName()).compareTo(StrUtil.lower(b.getName()));
            }
        });
        return list;
    }

    /** 按规范内层路径（无首尾斜杠）在树中定位节点；找不到返回 null。 */
    public static TreeNode find(TreeNode root, String inner) {
        String s = (inner == null) ? "" : inner;
        while (s.startsWith("/")) s = s.substring(1);
        TreeNode node = root;
        for (String seg : StrUtil.splitNonEmpty(s, '/')) {
            node = node.getChildren().get(seg);
            if (node == null) return null;
        }
        return node;
    }

    /**
     * 把选中节点展开成两类：
     *  files = 交给 7z x 的文件 rawPath（原样 Path，可能含反斜杠，不要改写）；
     *  dirs  = 需要显式创建的目录（相对包内根、'/' 分隔），主要覆盖空目录。
     * 目录名不作为 7z 的文件名参数：7z 会把参数当通配模式，目录匹配行为随
     * 归档格式 / 版本而异；改用 mkdir -p 创建。7z x 会按文件参数自动重建父层级，
     * 因此只有空目录需要 mkdir。LinkedHashSet 去重保序，避免「同时选了父目录和子文件」重复传参。
     */
    public static void collect(TreeNode node, LinkedHashSet<String> files, LinkedHashSet<String> dirs) {
        if (node == null) return;
        if (node.isDir()) {
            String full = node.getFullPath();
            if (!full.isEmpty()) dirs.add(full);
            for (TreeNode c : node.getChildren().values()) collect(c, files, dirs);
        } else {
            SevenZipEntry e = node.getEntry();
            if (e != null) files.add(e.rawPath);
        }
    }
}

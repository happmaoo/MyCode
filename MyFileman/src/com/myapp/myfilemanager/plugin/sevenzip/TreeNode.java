package com.myapp.myfilemanager.plugin.sevenzip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** 由扁平的 Path 列表还原出的树节点，用于资源管理器式逐级导航 */
public final class TreeNode {
    final String name;
    boolean isDir;
    TreeNode parent;
    SevenZipEntry entry;               // 只有叶子文件才有
    final LinkedHashMap<String, TreeNode> children = new LinkedHashMap<String, TreeNode>();

    TreeNode(String name) { this(name, true); }

    TreeNode(String name, boolean isDir) {
        this.name = name;
        this.isDir = isDir;
    }

    public String getName() { return name; }
    public boolean isDir() { return isDir; }
    public void setIsDir(boolean v) { isDir = v; }
    public TreeNode getParent() { return parent; }
    public void setParent(TreeNode p) { parent = p; }
    public SevenZipEntry getEntry() { return entry; }
    public void setEntry(SevenZipEntry e) { entry = e; }
    public LinkedHashMap<String, TreeNode> getChildren() { return children; }

    /** 相对包内根目录的完整路径 */
    public String getFullPath() {
        List<String> parts = new ArrayList<String>();
        TreeNode cur = this;
        while (true) {
            TreeNode p = cur.parent;
            if (p == null) break;
            parts.add(cur.name);
            cur = p;
        }
        Collections.reverse(parts);
        StringBuilder b = new StringBuilder();
        for (String s : parts) {
            if (b.length() > 0) b.append('/');
            b.append(s);
        }
        return b.toString();
    }
}

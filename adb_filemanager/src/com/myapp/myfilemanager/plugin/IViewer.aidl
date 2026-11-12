package com.myapp.myfilemanager.plugin;

import android.content.Intent;
import android.os.Bundle;

/**
 * 查看器能力（为图片查看器等预留）。
 * 方式A：插件自带 Activity（exported），主机 startActivity 把文件 URI 丢进去 —— 适合带缩放/平移的查看器。
 * 方式B：插件解码后通过 ParcelFileDescriptor 把渲染结果回传，主机内嵌显示。
 */
interface IViewer {
    boolean canView(String path, String mime, String ext);
    Intent getViewIntent(String path, String mime);
    ParcelFileDescriptor render(String path, in Bundle options);
}

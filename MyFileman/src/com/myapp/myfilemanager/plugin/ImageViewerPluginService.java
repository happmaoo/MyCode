package com.myapp.myfilemanager.plugin;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;

/**
 * 图片查看器插件，运行在独立进程 :plugin.imageviewer。
 * 通过 IViewer 能力对外暴露「能否预览」与「预览 Intent」；预览界面（ImageViewerActivity）
 * 同进程运行，自带缩放/平移，不依赖主界面进程，崩溃不影响主程序。
 */
public class ImageViewerPluginService extends Service {
    private static final String[] IMG_EXT = {
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tiff", "tif", "avif"
    };

    private final IPlugin.Stub pluginStub = new IPlugin.Stub() {
        public int getProtocolVersion() { return PluginManager.PROTOCOL; }

        public PluginInfo getInfo() {
            return new PluginInfo(
                    "imageviewer", "图片查看器", 1, 2500000,
                    IMG_EXT, new String[]{"image/*"},
                    new String[]{PluginManager.CAP_VIEWER});
        }

        public boolean hasCapability(String cap) {
            return PluginManager.CAP_VIEWER.equals(cap);
        }

        public IBinder getCapability(String cap) {
            return PluginManager.CAP_VIEWER.equals(cap) ? viewerStub : null;
        }
    };

    private final IViewer.Stub viewerStub = new IViewer.Stub() {
        public boolean canView(String path, String mime, String ext) {
            if (mime != null && mime.toLowerCase().startsWith("image/")) return true;
            if (ext != null) {
                ext = ext.toLowerCase();
                for (String e : IMG_EXT) if (e.equals(ext)) return true;
            }
            return false;
        }

        public Intent getViewIntent(String path, String mime) {
            Intent it = new Intent(ImageViewerPluginService.this, ImageViewerActivity.class);
            it.putExtra(ImageViewerActivity.EXTRA_PATH, path);
            it.putExtra(ImageViewerActivity.EXTRA_MIME, mime);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return it;
        }

        public ParcelFileDescriptor render(String path, Bundle options) {
            return null;   // 方式B（内嵌渲染）预留；当前采用方式A（独立 Activity 预览）
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return pluginStub;
    }
}

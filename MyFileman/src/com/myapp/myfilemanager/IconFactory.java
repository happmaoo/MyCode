package com.myapp.myfilemanager;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import com.caverock.androidsvg.SVG;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * 文件图标工厂。
 *  - 全部主题均为真实位图：按 Util.T_* 类型编号从 assets/&lt;dir&gt;/&lt;kind&gt;.png 加载
 *    （128×128 透明 PNG，已统一居中，双线性平滑缩放）。
 *  - 现仅保留两套：elementary（默认）与 Adwaita；其它主题及其资源已移除。
 *  - 两套主题均自带 mimes/ 子目录，按扩展名精确匹配文件类型图标；缺失时回退该主题的
 *    粗分类位图，仍缺失再回退默认主题，最后用代码绘制的中性占位图兜底。
 * 主题注册表（名称/目录/说明）集中在此，Prefs 与设置页直接引用，避免多处维护。
 * APK 文件额外支持解析其自身的 application icon（异步解析 + 内存缓存，失败回退主题图标）。
 */
public final class IconFactory {
    private IconFactory() {}
    private static final HashMap<String, Bitmap> CACHE = new HashMap<String, Bitmap>();

    /** 应用数据目录（/data/data、/data/user/0、内置存储 …/Android/data）下的应用图标缓存：包名 → 位图。 */
    private static final HashMap<String, Bitmap> APP_ICON_CACHE = new HashMap<String, Bitmap>();
    /** 查不到（残留/共享 uid 名）的包名集合，避免反复查 PackageManager。 */
    private static final HashSet<String> APP_ICON_MISSING = new HashSet<String>();

    // 主题下标即设置页单选列表顺序，也作为持久化值；改动顺序须同步 Prefs 的旧值迁移。
    public static final int THEME_ELEMENTARY = 0;
    public static final int THEME_ADWAITA    = 1;

    /** 默认主题：elementary。全新安装与越界值均落到这里。 */
    public static final int THEME_DEFAULT = THEME_ELEMENTARY;

    /** 主题总数（合法范围 0 .. THEME_COUNT-1）。 */
    public static final int THEME_COUNT = 2;

    /** 主题显示名（设置页单选列表顺序）。 */
    public static final String[] THEME_NAMES = {
        "elementary（默认）",
        "Adwaita (GNOME)"
    };

    /** 主题风格与许可说明（设置页主题提示）。 */
    public static final String[] THEME_DESC = {
        "简洁彩色，应用默认主题；内置按扩展名精确匹配的文件类型图标。",
        "GNOME 柔和彩色；内置按扩展名精确匹配的文件类型图标。"
    };

    /** 各主题对应的 assets 子目录。 */
    private static final String[] THEME_DIRS = {
        "elementary", "adwaita"
    };

    // ==================== 按文件类型精确匹配的图标（两套主题各一份） ====================

    /** 各主题 mimes/ 子目录名（相对 THEME_DIRS[theme]），两套主题均内置按扩展名精确匹配的图标。 */
    private static final String MIME_SUBDIR = "mimes";

    /** 扩展名 → 图标名（assets/&lt;theme&gt;/mimes/&lt;name&gt;.png）。elementary 与 adwaita 均查询。 */
    private static final HashMap<String, String> MIME_MAP = new HashMap<String, String>();
    static {
        String[] pairs = {
            // 文档 / 办公 / 电子书
            "pdf","application-pdf",
            "doc","application-msword", "docx","x-office-document", "dot","x-office-document-template",
            "dotx","application-vnd.openxmlformats-officedocument.wordprocessingml.template", "rtf","application-msword",
            "xls","application-vnd.ms-excel", "xlsx","x-office-spreadsheet", "xlt","x-office-spreadsheet-template",
            "xltx","application-vnd.openxmlformats-officedocument.spreadsheetml.template",
            "csv","x-office-spreadsheet", "tsv","x-office-spreadsheet",
            "ppt","application-vnd.ms-powerpoint", "pptx","x-office-presentation", "pot","x-office-presentation-template",
            "potx","application-vnd.openxmlformats-officedocument.presentationml.template",
            "odt","x-office-document", "ods","x-office-spreadsheet", "odp","x-office-presentation", "odg","x-office-drawing",
            "odc","application-vnd.oasis.opendocument.chart", "odf","application-vnd.oasis.opendocument.formula",
            "mdb","application-vnd.ms-access", "accdb","application-vnd.ms-access",
            "db","office-database", "sqlite","office-database", "sql","application-sql",
            "wps","x-office-document", "et","x-office-spreadsheet", "dps","x-office-presentation", "vcf","office-contact",
            "epub","application-epub+zip", "mobi","application-x-mobipocket-ebook",
            "azw","application-x-mobipocket-ebook", "azw3","application-x-mobipocket-ebook",
            "fb2","application-x-fictionbook+xml", "cbz","application-vnd.comicbook+zip",
            "cbr","application-vnd.comicbook+zip", "cb7","application-vnd.comicbook+zip",
            "djvu","image-vnd.djvu", "djv","image-vnd.djvu",
            "tex","text-x-tex", "latex","text-x-tex", "bib","text-x-bibtex", "cls","text-x-tex", "sty","text-x-tex",
            // 压缩 / 归档 / 镜像 / 安装包
            "zip","application-zip", "7z","application-x-7z-compressed", "rar","application-vnd.rar", "tar","application-x-tar",
            "gz","application-gzip", "tgz","application-x-compressed-tar",
            "bz2","application-x-bzip2", "tbz","application-x-bzip2-compressed-tar", "tbz2","application-x-bzip2-compressed-tar",
            "xz","application-x-xz", "txz","application-x-xz-compressed-tar",
            "lzma","application-x-lzma", "tlz","application-x-lzma-compressed-tar",
            "lz","application-x-lzop", "lzo","application-x-lzop", "zst","application-x-archive", "zstd","application-x-archive",
            "lzh","application-x-lha", "lha","application-x-lhz", "arj","application-x-arj", "ace","application-x-ace",
            "zoo","application-x-zoo", "sit","application-x-stuffit", "pak","application-x-pak",
            "shar","application-x-shar", "cpio","application-x-cpio", "cab","application-vnd.ms-cab-compressed",
            "iso","application-vnd.efi.iso", "img","application-vnd.efi.img", "efi","application-vnd.efi.img",
            "vdi","application-x-vdi-disk", "vhd","application-x-vdi-disk", "vhdx","application-x-vdi-disk",
            "vmdk","application-x-vdi-disk", "qcow2","application-x-vdi-disk",
            "deb","application-vnd.debian.binary-package", "rpm","application-x-rpm",
            "appimage","application-vnd.appimage", "snap","application-vnd.snap",
            "flatpak","application-vnd.flatpak", "flatpakref","application-vnd.flatpak.ref",
            "apk","package-x-generic", "apks","package-x-generic", "xapk","package-x-generic",
            "jar","application-java-archive", "war","application-java-archive", "ear","application-java-archive",
            "par2","application-x-par2", "crdownload","application-x-partial-download", "part","application-x-partial-download",
            "ovf","application-ovf", "ova","application-ovf", "theme","application-x-theme",
            "addon","application-x-addon", "xpi","application-x-addon",
            "exe","application-x-msdownload", "msi","application-x-msdownload",
            "dll","application-x-sharedlib", "so","application-x-sharedlib", "a","application-x-archive",
            "torrent","application-x-bittorrent", "cue","application-x-cue",
            "ardour","application-x-ardour", "aup","application-x-audacity-project",
            "p12","application-certificate", "pfx","application-certificate", "crt","application-certificate",
            "cer","application-certificate", "der","application-certificate", "pem","application-certificate",
            "pgp","application-pgp", "gpg","application-pgp", "asc","application-pgp", "kdbx","application-x-keepassxc",
            // 图片
            "png","image-png", "jpg","image-jpeg", "jpeg","image-jpeg", "jpe","image-jpeg",
            "gif","image-gif", "bmp","image-bmp", "tif","image-tiff", "tiff","image-tiff",
            "psd","image-vnd.adobe.photoshop", "xcf","image-x-xcf", "ico","image-vnd.microsoft.icon",
            "ai","application-illustrator",
            "svg","image-x-generic", "svgz","image-x-generic", "webp","image-x-generic",
            "heic","image-x-generic", "heif","image-x-generic", "avif","image-x-generic", "jxl","image-x-generic",
            "tga","image-x-generic", "raw","image-x-generic", "cr2","image-x-generic",
            "nef","image-x-generic", "dng","image-x-generic", "xpm","image-x-generic", "ppm","image-x-generic",
            // 音频 / 视频 / 字幕
            "flac","audio-flac", "mp3","audio-x-generic", "wav","audio-x-generic", "aac","audio-x-generic",
            "m4a","audio-x-generic", "ogg","audio-x-generic", "oga","audio-x-generic", "opus","audio-x-generic",
            "wma","audio-x-generic", "amr","audio-x-generic", "aiff","audio-x-generic", "aif","audio-x-generic",
            "mid","audio-x-generic", "midi","audio-x-generic", "ape","audio-x-generic",
            "m3u","audio-x-playlist", "m3u8","audio-x-playlist", "pls","audio-x-playlist", "xspf","audio-x-playlist",
            "wpl","audio-x-smart-playlist", "asx","audio-x-smart-playlist",
            "mp4","video-x-generic", "mkv","video-x-generic", "avi","video-x-generic", "mov","video-x-generic",
            "wmv","video-x-generic", "flv","video-x-generic", "webm","video-x-generic", "3gp","video-x-generic",
            "m2ts","video-x-generic", "mpg","video-x-generic", "mpeg","video-x-generic", "m4v","video-x-generic",
            "vob","video-x-generic", "rmvb","video-x-generic", "ogv","video-x-generic",
            "swf","application-vnd.adobe.flash.movie",
            "ssa","text-x-ssa", "ass","text-x-ssa", "srt","text-x-ssa", "vtt","text-x-ssa", "sub","text-x-ssa",
            // 文本 / 标记 / 配置
            "txt","text-x-generic", "log","text-x-generic", "nfo","text-x-generic",
            "md","text-markdown", "markdown","text-markdown", "rst","text-x-generic",
            "html","text-html", "htm","text-html", "xhtml","text-html", "mhtml","text-html",
            "css","text-css", "scss","text-css", "less","text-css",
            "js","text-javascript", "mjs","text-javascript", "jsx","text-javascript",
            "tsx","application-typescript", "json","application-json", "jsonl","application-json", "json5","application-json",
            "yaml","application-yaml", "yml","application-yaml", "toml","text-x-generic",
            "ini","text-x-generic", "cfg","text-x-generic", "conf","text-x-generic", "properties","text-x-generic",
            "xml","text-x-generic", "xsd","text-x-generic", "xsl","text-x-generic",
            "plist","text-x-generic", "reg","text-x-generic",
            "rss","internet-feed", "atom","internet-feed",
            "po","text-x-gettext-translation", "mo","text-x-gettext-translation",
            "desktop","application-x-desktop", "service","text-x-systemd-unit", "unit","text-x-systemd-unit",
            "patch","text-x-generic", "diff","text-x-generic", "mbox","application-mbox", "eml","application-mbox",
            // 源码
            "c","text-x-csrc", "h","text-x-chdr", "cc","text-x-c++src", "cpp","text-x-c++src",
            "cxx","text-x-c++src", "hpp","text-x-chdr", "hh","text-x-chdr",
            "cs","text-x-csharp", "d","text-x-dsrc", "di","text-x-dsrc",
            "java","text-x-java", "kt","text-x-kotlin", "kts","text-x-kotlin",
            "scala","text-x-scala", "groovy","text-x-groovy",
            "py","text-x-python", "pyw","text-x-python", "pyi","text-x-python", "ipynb","text-x-python",
            "rb","application-x-ruby", "php","application-x-php", "pl","application-x-perl", "pm","application-x-perl",
            "go","text-x-go", "rs","text-rust", "lua","text-x-lua",
            "sh","application-x-shellscript", "bash","application-x-shellscript",
            "zsh","application-x-shellscript", "fish","application-x-shellscript",
            "bat","text-x-script", "cmd","text-x-script", "ps1","text-x-script",
            "hs","text-x-haskell", "lisp","text-x-common-lisp", "el","text-x-common-lisp",
            "scm","text-x-scheme", "ss","text-x-scheme",
            "pas","text-x-pascal", "pp","text-x-pascal",
            "f","text-x-fortran", "f90","text-x-fortran", "for","text-x-fortran",
            "cr","text-x-crystal", "vala","text-x-vala", "vapi","text-x-vala",
            "gradle","text-x-groovy", "mk","text-x-makefile", "mak","text-x-makefile",
            // 字体 / 3D 模型
            "ttf","font-x-generic", "otf","font-x-generic", "woff","font-x-generic",
            "woff2","font-x-generic", "eot","font-x-generic",
            "stl","model", "obj","model", "fbx","model", "dae","model", "blend","model",
            "gltf","model", "glb","model", "3mf","model",
            "step","model", "stp","model", "iges","model", "igs","model"
        };
        for (int i = 0; i + 1 < pairs.length; i += 2) MIME_MAP.put(pairs[i], pairs[i + 1]);
    }

    /**
     * 旧版主题下标迁移到现行 2 套（elementary / adwaita）。
     * 历史下标有两代，Prefs 以 K_ICON_THEME_VER 区分后传入（ver 为旧值版本）：
     *  · ver=1（v2.7 及以前 8 套）：0 扁平 / 1 Win98 / 2 Papirus / 3 Oxygen / 4 Breeze /
     *    5 Adwaita / 6 Qogir / 7 elementary —— 只有 5→adwaita、7→elementary，其余回默认。
     *  · ver=2（中间 5 套）：0 elementary / 1 Win98 / 2 Oxygen / 3 Breeze / 4 Adwaita ——
     *    只有 4→adwaita，其余回默认 elementary。
     * 越界一律回退默认 elementary。
     */
    public static int migrateTheme(int old, int ver) {
        if (ver >= 3) {   // 已是现行下标，仅做越界保护
            return (old >= 0 && old < THEME_COUNT) ? old : THEME_DEFAULT;
        }
        if (ver == 2) {   // 5 套下标
            return old == 4 ? THEME_ADWAITA : THEME_DEFAULT;
        }
        // ver<=1：8 套下标
        if (old == 5) return THEME_ADWAITA;
        if (old == 7) return THEME_ELEMENTARY;
        return THEME_DEFAULT;
    }

    /** 兼容旧签名：按最早的 8 套下标迁移。 */
    public static int migrateTheme(int old) {
        return migrateTheme(old, 1);
    }

    private static int theme = THEME_DEFAULT;
    private static AssetManager assets = null;
    private static volatile Context appCtx = null;

    // APK 内嵌图标缓存：LruCache 按字节 LRU 淘汰（不再超限全清），切换主题不影响已解析 APK 图标。
    private static final LruCache<String, Bitmap> APK_CACHE = new LruCache<String, Bitmap>(maxKb(16)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
    };
    private static final HashSet<String> APK_FAIL = new HashSet<String>();

    // 图片缩略图缓存：LruCache 按字节 LRU 淘汰，按 路径+大小+修改时间+目标像素 失效。
    private static final LruCache<String, Bitmap> THUMB_CACHE = new LruCache<String, Bitmap>(maxKb(8)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
    };
    private static final HashSet<String> THUMB_FAIL = new HashSet<String>();

    /** 缓存上限：进程可用内存的 1/n（KB），至少 2MB，低内存设备不致 OOM。 */
    private static int maxKb(int n) {
        long max = Runtime.getRuntime().maxMemory() / 1024;
        return (int) Math.max(2048, max / n);
    }

    /** 注入 AssetManager / Application Context（幂等）。读取真实图标或解析 APK 前必须先调用。 */
    public static synchronized void attach(Context c) {
        if (c != null) {
            Context app = c.getApplicationContext();
            if (assets == null) assets = app.getAssets();
            if (appCtx == null) appCtx = app;
        }
    }

    /** 切换主题；越界值回退到默认 elementary。变化即清空位图缓存。 */
    public static synchronized void setTheme(int t) {
        int v = (t < 0 || t >= THEME_COUNT) ? THEME_DEFAULT : t;
        if (v == theme) return;
        theme = v;
        CACHE.clear();
    }

    public static synchronized int theme() { return theme; }

    public static synchronized Bitmap get(FileItem item, int px) {
        // 应用数据目录下的子目录（目录名即包名）：直接显示该 App 的图标
        if (item.dir && !item.parent) {
            Bitmap a = appDataIcon(item, px);
            if (a != null) return a;
        }
        int kind = Util.kind(item.name, item.dir, item.parent);
        String badge = Util.badge(kind, item.name);
        // 两套主题均按扩展名精确匹配（各自的 mimes/ 图标包）
        String mime = mimeIcon(item.name, kind, item.dir, item.parent);

        String key = theme + "|" + (mime == null ? "" : mime) + "|" + kind + "|" + badge + "|" + px;
        Bitmap b = CACHE.get(key);
        if (b == null) {
            if (mime != null) b = drawMime(mime, px);            // 当前主题：精确文件类型图标
            if (b == null) {
                b = drawAsset(THEME_DIRS[theme], kind, px, false);   // 回退该主题粗分类位图
            }
            if (b == null && theme != THEME_DEFAULT) {
                // 当前主题资源异常时，先回退默认主题位图，保证仍是彩色图标
                b = drawAsset(THEME_DIRS[THEME_DEFAULT], kind, px, false);
            }
            if (b == null) b = drawFallback(kind, badge, px);   // 极端情况兜底，绝不空白
            CACHE.put(key, b);
        }
        return b;
    }

    /**
     * 当前主题的文件夹位图（目录型条目专用的左侧图标，如收藏夹菜单）。
     * 与文件列表里的文件夹保持同一套主题风格；当前主题资源异常时回退默认主题。
     */
    public static synchronized Bitmap folder(int px) {
        if (px <= 0) px = 48;
        Bitmap b = drawAsset(THEME_DIRS[theme], Util.T_FOLDER, px, false);
        if (b == null && theme != THEME_DEFAULT)
            b = drawAsset(THEME_DIRS[THEME_DEFAULT], Util.T_FOLDER, px, false);
        return b;
    }

    /**
     * 加载真实位图主题：assets/&lt;dir&gt;/&lt;kind&gt;.png（文件名即 Util.T_* 类型编号）。
     * 源图统一为 128×128 透明 PNG 并已居中，这里只做一次缩放到目标 px。
     * nearest=true 时关闭双线性，保留原始像素硬边；否则平滑缩放得到抗锯齿效果。
     */
    private static Bitmap drawAsset(String dir, int kind, int px, boolean nearest) {
        if (assets == null || dir == null) return null;
        InputStream in = null;
        try {
            in = assets.open(dir + "/" + kind + ".png");
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inScaled = false;              // 不按屏幕密度自动缩放
            Bitmap src = BitmapFactory.decodeStream(in, null, opt);
            if (src == null) return null;

            Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint();
            p.setFilterBitmap(!nearest);
            p.setAntiAlias(!nearest);
            c.drawBitmap(src, null, new Rect(0, 0, px, px), p);
            src.recycle();
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 解析精确文件类型图标名（两套主题共用同一套扩展名映射）。目录 / 上级目录返回 null（仍用主题自带的文件夹位图，
     * 保持整套风格一致）；未收录的扩展名按 Util.kind 粗分类回退到对应的通用图标。
     */
    private static String mimeIcon(String name, int kind, boolean dir, boolean parent) {
        if (dir || parent || name == null) return null;
        String lower = name.toLowerCase(Locale.US);
        // 无扩展名但语义明确的常见工程文件名
        if (lower.startsWith("readme")) return "text-x-readme";
        if (lower.startsWith("authors")) return "text-x-authors";
        if (lower.startsWith("copying") || lower.startsWith("license") || lower.startsWith("licence"))
            return "text-x-copying";
        if (lower.startsWith("install")) return "text-x-install";
        if (lower.startsWith("changelog") || lower.startsWith("news") || lower.startsWith("history"))
            return "text-x-changelog";
        if (lower.startsWith("makefile") || lower.startsWith("cmakelists")) return "text-x-makefile";
        if (lower.startsWith("todo")) return "text-x-generic-template";

        String hit = MIME_MAP.get(Util.ext(name));
        if (hit != null) return hit;
        switch (kind) {
            case Util.T_IMAGE: return "image-x-generic";
            case Util.T_VIDEO: return "video-x-generic";
            case Util.T_AUDIO: return "audio-x-generic";
            case Util.T_ZIP:   return "application-x-archive";
            case Util.T_DOC:   return "x-office-document";
            case Util.T_TEXT:  return "text-x-generic";
            case Util.T_CODE:  return "text-x-script";
            case Util.T_PDF:   return "application-pdf";
            case Util.T_APK:   return "package-x-generic";
            default:           return "application-octet-stream";
        }
    }

    /** 加载当前主题的精确文件类型图标：assets/&lt;theme&gt;/mimes/&lt;name&gt;.png（128×128 透明 PNG）。
     *  当前主题缺该图标时回退默认主题的 mimes，保证两套主题都尽量显示精确图标。 */
    private static Bitmap drawMime(String name, int px) {
        if (assets == null || name == null) return null;
        Bitmap b = drawMimeFrom(THEME_DIRS[theme] + "/" + MIME_SUBDIR, name, px);
        if (b == null && theme != THEME_DEFAULT)
            b = drawMimeFrom(THEME_DIRS[THEME_DEFAULT] + "/" + MIME_SUBDIR, name, px);
        return b;
    }

    /** 从指定 assets 目录读取一张 128×128 透明 PNG 并缩放到 px。 */
    private static Bitmap drawMimeFrom(String dir, String name, int px) {
        if (assets == null || name == null) return null;
        InputStream in = null;
        try {
            in = assets.open(dir + "/" + name + ".png");
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inScaled = false;
            Bitmap src = BitmapFactory.decodeStream(in, null, opt);
            if (src == null) return null;
            Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint();
            p.setFilterBitmap(true);
            p.setAntiAlias(true);
            c.drawBitmap(src, null, new Rect(0, 0, px, px), p);
            src.recycle();
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static Bitmap canvas(int px) {
        return Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
    }

    // ==================== 资源异常兜底（不是可选主题） ====================

    /**
     * 仅当 assets 位图全部读取失败时使用的中性占位图，避免出现空白图标。
     * 它不是图标主题，设置页不提供该选项，正常运行下不会显示。
     */
    private static Bitmap drawFallback(int kind, String badge, int px) {
        Bitmap b = canvas(px);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFE6E9EC);
        c.drawRoundRect(new RectF(0, 0, px, px), px * .22f, px * .22f, p);
        String t = (badge == null || badge.isEmpty()) ? "?" : badge;
        p.setColor(0xFF8A9096);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(t.length() >= 3 ? px * .24f : px * .32f);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(t, px * .5f, px * .5f - (fm.ascent + fm.descent) / 2, p);
        return b;
    }

    // ==================== APK 内嵌图标 ====================

    /** APK 图标缓存键：路径 + 大小 + 修改时间 + 目标像素，APK 被替换后自动失效。 */
    public static String apkKey(FileItem it, int px) {
        return "apk|" + it.path + "|" + it.size + "|" + it.modified + "|" + px;
    }

    public static synchronized Bitmap apkCached(String key) { return APK_CACHE.get(key); }
    public static synchronized boolean apkFailed(String key) { return APK_FAIL.contains(key); }

    public static synchronized void markApkFailed(String key) {
        APK_FAIL.add(key);
        if (APK_FAIL.size() > 240) APK_FAIL.clear();
    }

    public static synchronized void putApk(String key, Bitmap b) {
        if (b == null) return;
        APK_CACHE.put(key, b);   // LruCache 自动按字节淘汰最久未用
    }

    // ==================== 图片缩略图 ====================

    /** 缩略图缓存键：路径 + 大小 + 修改时间 + 目标像素，图片被替换后自动失效。 */
    public static String thumbKey(FileItem it, int px) {
        return "thumb|" + it.path + "|" + it.size + "|" + it.modified + "|" + px;
    }

    public static synchronized Bitmap thumbCached(String key) { return THUMB_CACHE.get(key); }
    public static synchronized boolean thumbFailed(String key) { return THUMB_FAIL.contains(key); }

    public static synchronized void markThumbFailed(String key) {
        THUMB_FAIL.add(key);
        if (THUMB_FAIL.size() > 480) THUMB_FAIL.clear();
    }

    public static synchronized void putThumb(String key, Bitmap b) {
        if (b == null) return;
        THUMB_CACHE.put(key, b);   // LruCache 自动按字节淘汰最久未用
    }

    /**
     * 在后台线程把图片文件解码为缩略图位图（最长边约 px*2，先按原图尺寸 inSampleSize 降采样，
     * 避免大图 OOM；FIT_CENTER 的 ImageView 会再按格子尺寸等比居中显示）。
     * 解码失败（如 gif 动图）返回 null，调用方回退主题图标。
     * SVG / SVGZ 走 AndroidSVG 矢量分支，不再依赖 BitmapFactory。
     */
    public static Bitmap loadThumb(String path, int px) {
        if (path == null || px <= 0) return null;
        String low = path.toLowerCase(Locale.US);
        if (low.endsWith(".svg") || low.endsWith(".svgz")) return loadSvgThumb(path, px, low.endsWith(".svgz"));
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int w = o.outWidth, h = o.outHeight;
            if (w <= 0 || h <= 0) return null;
            int target = px * 2;
            int s = 1;
            int longer = Math.max(w, h);
            while (longer / (s * 2) >= target) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeFile(path, o);
            if (b == null || b.getWidth() <= 0 || b.getHeight() <= 0) return null;
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** SVG 缩略图：按 viewBox 等比渲染到 px*2 的透明位图；失败返回 null 回退主题图标。
     *  只处理本地普通路径（root 专属路径读不到，交由预览界面的 su cat 兜底）。 */
    private static Bitmap loadSvgThumb(String path, int px, boolean gzip) {
        InputStream in = null;
        try {
            in = new FileInputStream(path);
            if (gzip) in = new GZIPInputStream(in);   // 关闭时级联关底层文件流，释放 Inflater
            SVG svg = SVG.getFromInputStream(in);
            int side = Math.max(1, px * 2);
            // renderToPicture()（无参）走库的标准路径，文档尺寸/viewBox/百分比单位由库按规范解析；
            // 之后由我们自己等比缩放居中绘制到正方形位图，不依赖库的 viewport 推导（对特殊文档会算错）。
            Picture pic = svg.renderToPicture();
            int pw = pic.getWidth(), ph = pic.getHeight();
            if (pw <= 0 || ph <= 0) { pw = 512; ph = 512; }
            float k = Math.min(side / (float) pw, side / (float) ph);
            Bitmap b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.translate((side - pw * k) / 2f, (side - ph * k) / 2f);
            c.scale(k, k);
            c.drawPicture(pic);
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 把任意 Drawable（含 Android 8.0+ 自适应图标）等比居中绘制成 px×px 位图，背景透明。
     * APK 归档图标与应用数据目录图标复用同一套绘制逻辑。
     */
    private static Bitmap drawableToBitmap(Drawable d, int px) {
        if (d == null) return null;
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0) iw = px;
        if (ih <= 0) ih = px;
        float k = Math.min((float) px / iw, (float) px / ih);
        int dw = Math.max(1, Math.round(iw * k));
        int dh = Math.max(1, Math.round(ih * k));
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int left = (px - dw) / 2, top = (px - dh) / 2;
        d.setBounds(left, top, left + dw, top + dh);
        d.draw(c);
        return b;
    }

    /**
     * 判断目录是否位于「应用数据目录」下，是则返回查到的应用图标位图，否则 null。
     * 命中条件：item 为普通目录；其父目录为 /data/data、/data/user/0，或任意以 /Android/data 结尾的路径
     * （覆盖内置存储与可移动存储的应用专属目录）。目录名即包名，向 PackageManager 查图标。
     * 结果按包名缓存；查不到的包名进入 MISSING 集合，下次直接回退普通文件夹图标，不再查 PM。
     */
    private static Bitmap appDataIcon(FileItem item, int px) {
        if (item == null || item.path == null || px <= 0) return null;
        Context ctx = appCtx;
        if (ctx == null) return null;
        String dir = item.path;
        int cut = dir.lastIndexOf('/');
        if (cut < 0) return null;
        String parent = dir.substring(0, cut);
        boolean isAppData = parent.equals("/data/data")
                || parent.equals("/data/user/0")
                || parent.endsWith("/Android/data");
        if (!isAppData) return null;

        String pkg = item.name;
        synchronized (APP_ICON_CACHE) {
            Bitmap c = APP_ICON_CACHE.get(pkg);
            if (c != null) return c;
            if (APP_ICON_MISSING.contains(pkg)) return null;
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.GET_UNINSTALLED_PACKAGES);
            Drawable d = ai.loadIcon(pm);
            Bitmap b = drawableToBitmap(d, px);
            synchronized (APP_ICON_CACHE) {
                if (b != null) APP_ICON_CACHE.put(pkg, b);
                else APP_ICON_MISSING.add(pkg);
            }
            return b;
        } catch (Throwable t) {
            synchronized (APP_ICON_CACHE) { APP_ICON_MISSING.add(pkg); }
            return null;
        }
    }

    /**
     * 在后台线程解析 APK 自身的 application icon，等比居中绘制成 px×px 位图。
     * 用 getPackageArchiveInfo 读归档包，并显式设置 sourceDir/publicSourceDir，
     * 否则 loadIcon 拿不到包内资源。任何异常都返回 null，调用方回退主题图标。
     */
    public static Bitmap loadApkIcon(String path, int px) {
        if (path == null || px <= 0) return null;
        Context ctx = appCtx;
        if (ctx == null) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(path, 0);
            if (pi == null || pi.applicationInfo == null) return null;
            ApplicationInfo ai = pi.applicationInfo;
            ai.sourceDir = path;
            ai.publicSourceDir = path;
            Drawable d = ai.loadIcon(pm);
            if (d == null) return null;
            return drawableToBitmap(d, px);
        } catch (Throwable t) {
            return null;
        }
    }
}

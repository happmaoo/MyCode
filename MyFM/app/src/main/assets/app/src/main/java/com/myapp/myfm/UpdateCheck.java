package com.myapp.myfm;

import android.os.AsyncTask;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;


    /*


    public static int compareVersion(String online, String local) {
        if (online == null || local == null) return 0;
        String[] v1 = online.split("\\.");
        String[] v2 = local.split("\\.");
        int len = Math.max(v1.length, v2.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < v1.length ? Integer.parseInt(v1[i].replaceAll("[^0-9]", "")) : 0;
            int n2 = i < v2.length ? Integer.parseInt(v2[i].replaceAll("[^0-9]", "")) : 0;
            if (n1 > n2) return 1;
            if (n1 < n2) return -1;
        }
        return 0;
    }

    private void checkAppUpdate() {
        UpdateCheck.fetchVersionName(new UpdateCheck.VersionCallback() {
            @Override
            public void onResult(final String onlineVersion) {
                // 获取当前本地版本
                String currentVersion = BuildConfig.VERSION_NAME;

                Log.d("UpdateCheck", "当前版本: " + currentVersion + " | 线上版本: " + onlineVersion);

                // 比较版本 (此处使用简单的字符串对比，或调用下方的 compareVersion)
                if (compareVersion(onlineVersion, currentVersion) > 0) {
                    // 必须在 UI 线程弹出对话框，AsyncTask 的 onResult 已在 UI 线程
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("发现新版本")
                            .setMessage("检测到新版本 " + onlineVersion + "，是否前往查看？")
                            .setPositiveButton("确定", (dialog, which) -> {
                                // 定义你要跳转的 GitHub 项目地址或下载页
                                String url = "https://github.com/happmaoo/MyCode";

                                try {
                                    Intent intent = new Intent(Intent.ACTION_VIEW);
                                    intent.setData(android.net.Uri.parse(url));
                                    if (intent.resolveActivity(getPackageManager()) != null) {
                                        startActivity(intent);
                                    } else {
                                        Toast.makeText(MainActivity.this, "未找到浏览器应用", Toast.LENGTH_SHORT).show();
                                    }
                                } catch (Exception e) {
                                    Log.e("UpdateCheck", "无法打开链接: " + e.getMessage());
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                }
            }

            @Override
            public void onError(Exception e) {
                Log.e("UpdateCheck", "检查更新失败: " + e.getMessage());
            }
        });
    }
*/





public class UpdateCheck {

    private static final String TAG = "UpdateCheck";
    private static final String TARGET_URL = "https://raw.githubusercontent.com/happmaoo/MyCode/refs/heads/main/MyFM/app/build.gradle";

    public interface VersionCallback {
        void onResult(String versionName);
        void onError(Exception e);
    }

    /**
     * 异步获取 VersionName
     */
    public static void fetchVersionName(final VersionCallback callback) {
        new AsyncTask<Void, Void, Object>() {
            @Override
            protected Object doInBackground(Void... voids) {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(TARGET_URL);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);

                    // 兼容旧版本 TLS 握手（如果需要）
                    if (connection instanceof HttpsURLConnection) {
                        // 在 Android 7.0+ 上，TLS 1.2 默认是开启的，无需额外配置
                    }

                    int responseCode = connection.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            // 逐行匹配，节省内存
                            String version = extractVersionName(line);
                            if (version != null) {
                                return version;
                            }
                        }
                    } else {
                        return new Exception("HTTP Error: " + responseCode);
                    }
                } catch (Exception e) {
                    return e;
                } finally {
                    if (connection != null) connection.disconnect();
                }
                return null;
            }

            @Override
            protected void onPostExecute(Object result) {
                if (callback == null) return;
                if (result instanceof String) {
                    callback.onResult((String) result);
                } else if (result instanceof Exception) {
                    callback.onError((Exception) result);
                } else {
                    callback.onError(new Exception("VersionName not found in file"));
                }
            }
        }.execute();
    }

    /**
     * 正则表达式匹配：versionName "x.x.x" 或 versionName 'x.x.x'
     */
    private static String extractVersionName(String line) {
        String regex = "versionName\\s+[\"']([^\"']+)[\"']";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
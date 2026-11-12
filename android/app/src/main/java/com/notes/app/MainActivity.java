package com.notes.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 便签 App —— 原生壳
 * 页面本体是 assets/index.html（单文件 Web 应用），这里只做三件事：
 *   1. 提供 WebView 容器与本地存储
 *   2. 通过 JavascriptInterface 把导出的文本写进系统「下载」目录
 *   3. 把系统返回键交给网页处理（逐层关闭编辑器 / 菜单 / 搜索）
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    private static final String HOST = "notes.local";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);       // localStorage
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);                 // 不跟随系统字体缩放，避免布局错乱
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                fileCallback = cb;
                // 导入数据包时网页用的是 <input multiple>，这里要透传，
                // 否则用户选了一个文件就被踢回页面，得反复点好几次
                boolean multi = params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                if (multi) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(intent,
                            multi ? "选择要导入的文件" : "选择文件"), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }
        });

        web.setWebViewClient(new WebViewClient() {

            /**
             * 把 https://notes.local/xxx 映射到 assets 里的文件。
             * 相比 file:// ，用虚拟 https 域名的好处是 localStorage 的 origin 稳定，
             * 不会因为 WebView 版本差异导致数据读不到。
             */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u != null && HOST.equals(u.getHost())) {
                    String path = u.getPath();
                    if (path == null || path.equals("/")) path = "/index.html";
                    String name = path.startsWith("/") ? path.substring(1) : path;
                    try {
                        return new WebResourceResponse(
                                mimeOf(name), "utf-8", getAssets().open(name));
                    } catch (IOException e) {
                        return new WebResourceResponse("text/plain", "utf-8",
                                new ByteArrayInputStream("Not found".getBytes()));
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u == null) return false;
                if (HOST.equals(u.getHost())) return false;   // 站内跳转，交给 WebView
                // 正文里的外链交给系统浏览器
                if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ignored) { }
                    return true;
                }
                return true;
            }
        });

        web.loadUrl("https://" + HOST + "/index.html");
    }

    private String mimeOf(String name) {
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".md")) return "text/markdown";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null) {
                    // 多选时 URI 在 ClipData 里，单选才走 getData()
                    ClipData clip = data.getClipData();
                    if (clip != null && clip.getItemCount() > 0) {
                        result = new Uri[clip.getItemCount()];
                        for (int i = 0; i < clip.getItemCount(); i++) {
                            result[i] = clip.getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        result = new Uri[]{data.getData()};
                    }
                }
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** 返回键：先让网页逐层关闭，网页说没处理才退出 App */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            web.evaluateJavascript(
                    "(function(){try{return window.androidBack?window.androidBack():false}catch(e){return false}})()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (!"true".equals(value)) finish();
                        }
                    });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 暴露给 JS 的原生能力 */
    public class Bridge {

        @JavascriptInterface
        public boolean saveFile(String fileName, String content, String mime) {
            try {
                byte[] bytes = content.getBytes("UTF-8");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    writeViaMediaStore(fileName, mime, bytes);
                } else {
                    writeToAppDownload(fileName, bytes);
                }
                return true;
            } catch (Exception e) {
                final String msg = "保存失败：" + e.getMessage();
                runOnUiThread(new Runnable() {
                    @Override public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
                });
                return false;
            }
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        /**
         * 按文件夹结构导出：网页把整棵树拍平成 JSON 传进来，这里照着建目录、写文件。
         * 入参形如 {"root":"便签-20261112-0233","dirs":["工作","工作/周报"],
         *          "files":[{"dir":"工作/周报","name":"本周周报.md","content":"..."}]}
         * 返回 "ok|文件数|略过的空目录数" 或 "err|原因"，空目录没法在 MediaStore 里单独创建。
         */
        @JavascriptInterface
        public String saveTree(String json) {
            try {
                JSONObject o = new JSONObject(json);
                String root = o.getString("root");
                JSONArray dirs = o.optJSONArray("dirs");
                JSONArray files = o.getJSONArray("files");
                int count = 0, skipped = 0;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (dirs != null) {
                        for (int i = 0; i < dirs.length(); i++) {
                            if (!dirHasFile(dirs.getString(i), files)) skipped++;
                        }
                    }
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject f = files.getJSONObject(i);
                        String name = f.getString("name");
                        String sub = root;
                        String dir = f.optString("dir", "");
                        if (dir.length() > 0) sub += "/" + dir;
                        writeViaMediaStoreTo(sub, name, mimeOf(name),
                                f.getString("content").getBytes("UTF-8"));
                        count++;
                    }
                } else {
                    File base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (base == null) base = getFilesDir();
                    File rootDir = new File(base, root);
                    for (int i = 0; dirs != null && i < dirs.length(); i++) {
                        new File(rootDir, dirs.getString(i)).mkdirs();
                    }
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject f = files.getJSONObject(i);
                        File dir = new File(rootDir, f.optString("dir", ""));
                        dir.mkdirs();
                        FileOutputStream fos = new FileOutputStream(new File(dir, f.getString("name")));
                        fos.write(f.getString("content").getBytes("UTF-8"));
                        fos.flush();
                        fos.close();
                        count++;
                    }
                    final String path = rootDir.getAbsolutePath();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "已保存：" + path, Toast.LENGTH_LONG).show();
                        }
                    });
                }
                return "ok|" + count + "|" + skipped;
            } catch (Exception e) {
                return "err|" + e;
            }
        }
    }

    /** 判断某个目录（含其子目录）里是否有文件，用来统计哪些空目录会丢 */
    private boolean dirHasFile(String dir, JSONArray files) throws Exception {
        for (int i = 0; i < files.length(); i++) {
            String d = files.getJSONObject(i).optString("dir", "");
            if (d.equals(dir) || d.startsWith(dir + "/")) return true;
        }
        return false;
    }

    /** Android 10+：写进公共「下载」目录，其它 App 也能看到 */
    private void writeViaMediaStore(String fileName, String mime, byte[] bytes) throws Exception {
        writeViaMediaStoreTo("便签", fileName, mime, bytes);
    }

    /** subPath 用 / 分隔，MediaStore 会按需把中间目录一起建出来 */
    private void writeViaMediaStoreTo(String subPath, String fileName, String mime, byte[] bytes) throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + subPath);

        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new Exception("无法创建文件");
        OutputStream os = getContentResolver().openOutputStream(uri);
        if (os == null) throw new Exception("无法写入文件");
        os.write(bytes);
        os.flush();
        os.close();
    }

    /** Android 8/9：没有免权限的公共目录，写到应用专属 Download 下 */
    private void writeToAppDownload(String fileName, byte[] bytes) throws Exception {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = getFilesDir();
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, fileName);
        FileOutputStream fos = new FileOutputStream(out);
        fos.write(bytes);
        fos.flush();
        fos.close();
        final String path = out.getAbsolutePath();
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this, "已保存：" + path, Toast.LENGTH_LONG).show();
            }
        });
    }
}

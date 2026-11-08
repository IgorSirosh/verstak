package ru.verstak.harness;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Оболочка Верстака: показывает интерфейс харнеса (assets/index.html) и даёт ему
 * «нативный мост» — сетевые запросы без ограничений браузера, буфер обмена и запуск Termux.
 */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 1;
    private static final int REQ_PERM = 2;
    private static final String TERMUX = "com.termux";
    private static final String RUN_PERM = "com.termux.permission.RUN_COMMAND";

    private WebView web;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<String, HttpURLConnection> active = new ConcurrentHashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0F1115"));
        getWindow().setNavigationBarColor(Color.parseColor("#0F1115"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0F1115"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        WebView.setWebContentsDebuggingEnabled(true);

        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                openExternal(u);
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_PERM) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            emit("window.__nativePermission && window.__nativePermission(" + ok + ")");
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onNativeBack ? window.onNativeBack() : false", value -> {
            if (!"true".equals(value)) reallyBack();
        });
    }

    private void reallyBack() {
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        for (HttpURLConnection c : active.values()) c.disconnect();
        pool.shutdownNow();
        super.onDestroy();
    }

    private void emit(String js) {
        ui.post(() -> web.evaluateJavascript(js, null));
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception ignored) {
        }
    }

    private boolean hasTermux() {
        try {
            getPackageManager().getPackageInfo(TERMUX, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Методы, которые вызывает интерфейс через window.Native */
    class Bridge {
        @JavascriptInterface
        public void request(String id, String method, String url, String headersJson, String body) {
            pool.execute(() -> {
                HttpURLConnection c = null;
                String q = JSONObject.quote(id);
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    active.put(id, c);
                    c.setRequestMethod(method);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(900000);
                    JSONObject h = new JSONObject(headersJson == null || headersJson.isEmpty() ? "{}" : headersJson);
                    Iterator<String> keys = h.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        c.setRequestProperty(k, h.getString(k));
                    }
                    if (body != null && !body.isEmpty()) {
                        byte[] b = body.getBytes(StandardCharsets.UTF_8);
                        c.setDoOutput(true);
                        c.setFixedLengthStreamingMode(b.length);
                        OutputStream os = c.getOutputStream();
                        os.write(b);
                        os.close();
                    }
                    int code = c.getResponseCode();
                    emit("window.__nativeStart(" + q + "," + code + ")");
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    if (in != null) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                        String line;
                        while ((line = r.readLine()) != null) {
                            emit("window.__nativeChunk(" + q + "," + JSONObject.quote(line) + ")");
                        }
                        r.close();
                    }
                    emit("window.__nativeDone(" + q + ",null)");
                } catch (Exception e) {
                    emit("window.__nativeDone(" + q + "," + JSONObject.quote(String.valueOf(e.getMessage() != null ? e.getMessage() : e)) + ")");
                } finally {
                    active.remove(id);
                    if (c != null) c.disconnect();
                }
            });
        }

        @JavascriptInterface
        public void abort(String id) {
            HttpURLConnection c = active.remove(id);
            if (c != null) pool.execute(c::disconnect);
        }

        @JavascriptInterface
        public void copy(String text) {
            ui.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Верстак", text));
                Toast.makeText(MainActivity.this, "Скопировано", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void toast(String text) {
            ui.post(() -> Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public boolean hasTermux() {
            return MainActivity.this.hasTermux();
        }

        @JavascriptInterface
        public void openTermux() {
            ui.post(() -> {
                Intent i = getPackageManager().getLaunchIntentForPackage(TERMUX);
                if (i != null) startActivity(i);
                else openExternal(Uri.parse("https://f-droid.org/packages/com.termux/"));
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            ui.post(() -> openExternal(Uri.parse(url)));
        }

        @JavascriptInterface
        public String readAsset(String name) {
            try (InputStream in = getAssets().open(name)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        /** Запуск команды в Termux. Ответ: ok | no_termux | need_permission | ошибка */
        @JavascriptInterface
        public String termuxRun(String command) {
            if (!MainActivity.this.hasTermux()) return "no_termux";
            if (checkSelfPermission(RUN_PERM) != PackageManager.PERMISSION_GRANTED) {
                ui.post(() -> requestPermissions(new String[]{RUN_PERM}, REQ_PERM));
                return "need_permission";
            }
            try {
                Intent i = new Intent();
                i.setClassName(TERMUX, "com.termux.app.RunCommandService");
                i.setAction("com.termux.RUN_COMMAND");
                i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
                i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", command});
                i.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
                i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
                startService(i);
                return "ok";
            } catch (Exception e) {
                return String.valueOf(e.getMessage());
            }
        }
    }
}

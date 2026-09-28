package com.ipc19.videodownloader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

public class MainActivity extends Activity {

    private static final String HOME_URL = "https://www.google.com";
    private static final int REQ_PERMISSIONS = 1;

    /** Recolecta las URLs de las etiquetas <video>/<source> de la página (incluye iframes del mismo origen). */
    private static final String COLLECT_VIDEOS_JS =
            "(function(){"
            + "function scan(doc){try{"
            + "doc.querySelectorAll('video,video source,source[type^=\"video\"]').forEach(function(v){"
            + "[v.currentSrc,v.src,v.getAttribute&&v.getAttribute('src')].forEach(function(s){"
            + "if(s){try{VDBridge.report(new URL(s,doc.baseURI).href);}catch(e){}}});});"
            + "doc.querySelectorAll('iframe').forEach(function(f){try{if(f.contentDocument)scan(f.contentDocument);}catch(e){}});"
            + "}catch(e){}}"
            + "scan(document);"
            + "if(!window.__vdHooked){window.__vdHooked=true;"
            + "document.addEventListener('play',function(){scan(document);},true);"
            + "document.addEventListener('loadedmetadata',function(){scan(document);},true);}"
            + "})();";

    private WebView webView;
    private EditText urlBar;
    private Button videosButton;
    private ProgressBar progressBar;
    private FrameLayout fullscreenContainer;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    /** Videos detectados en la página actual. Solo se toca desde el hilo principal. */
    private final LinkedHashMap<String, MediaItem> found = new LinkedHashMap<>();

    /** Descarga pendiente de que el usuario conceda el permiso de almacenamiento (Android 8/9). */
    private MediaItem pendingDownload;

    static final class MediaItem {
        final String url;
        final String type;
        final String referer;

        MediaItem(String url, String type, String referer) {
            this.url = url;
            this.type = type;
            this.referer = referer;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        urlBar = findViewById(R.id.url_bar);
        videosButton = findViewById(R.id.btn_videos);
        progressBar = findViewById(R.id.progress);
        fullscreenContainer = findViewById(R.id.fullscreen_container);

        findViewById(R.id.btn_back).setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        findViewById(R.id.btn_go).setOnClickListener(v -> loadFromBar());
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                loadFromBar();
                return true;
            }
            return false;
        });
        videosButton.setOnClickListener(v -> {
            // Relee la página por si el video apareció después de cargar.
            webView.evaluateJavascript(COLLECT_VIDEOS_JS, null);
            webView.postDelayed(this::showVideoList, 300);
        });
        updateVideosButton();

        setupWebView();
        requestNeededPermissions();

        String initial = extractUrl(getIntent());
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(initial != null ? initial : HOME_URL);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String url = extractUrl(intent);
        if (url != null) webView.loadUrl(url);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- WebView

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportMultipleWindows(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Bridge(), "VDBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                // intent://, market://, etc.: se ignoran para no sacar al usuario de la app.
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                urlBar.setText(url);
                found.clear();
                updateVideosButton();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                urlBar.setText(url);
                view.evaluateJavascript(COLLECT_VIDEOS_JS, null);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Se llama en un hilo de fondo por cada recurso que pide la página,
                // incluidos los reproductores embebidos en iframes de otros dominios.
                String url = request.getUrl().toString();
                String type = MediaDetector.detect(url);
                if (type != null) {
                    Map<String, String> headers = request.getRequestHeaders();
                    String referer = headers == null ? null : headers.get("Referer");
                    runOnUiThread(() -> addMedia(url, type, referer));
                }
                return null; // la página sigue funcionando normalmente
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                fullscreenContainer.addView(view);
                fullscreenContainer.setVisibility(View.VISIBLE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });

        // Archivos que la página ofrece como descarga (Content-Disposition, etc.).
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            startDirectDownload(url, webView.getUrl(), name);
        });
    }

    private void hideCustomView() {
        if (customView == null) return;
        fullscreenContainer.removeView(customView);
        fullscreenContainer.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customView = null;
        customViewCallback = null;
    }

    private final class Bridge {
        @JavascriptInterface
        public void report(String url) {
            if (url == null || url.startsWith("blob:") || url.startsWith("data:")) return;
            String type = MediaDetector.detect(url);
            final String finalType = type != null ? type : MediaDetector.TYPE_VIDEO;
            runOnUiThread(() -> addMedia(url, finalType, webView.getUrl()));
        }
    }

    private void addMedia(String url, String type, String referer) {
        if (found.containsKey(url)) return;
        found.put(url, new MediaItem(url, type, referer));
        updateVideosButton();
    }

    private void updateVideosButton() {
        videosButton.setText(getString(R.string.videos_found, found.size()));
    }

    // ------------------------------------------------------------- Navegación

    private void loadFromBar() {
        String input = urlBar.getText().toString().trim();
        if (input.isEmpty()) return;
        String url;
        if (input.startsWith("http://") || input.startsWith("https://")) {
            url = input;
        } else if (!input.contains(" ") && input.contains(".")) {
            url = "https://" + input;
        } else {
            url = "https://www.google.com/search?q=" + Uri.encode(input);
        }
        webView.loadUrl(url);
        webView.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
    }

    /** Obtiene una URL de un enlace abierto o compartido con la app. */
    private static String extractUrl(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return intent.getData().toString();
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                Matcher m = Patterns.WEB_URL.matcher(text);
                if (m.find()) {
                    String url = m.group();
                    return url.startsWith("http") ? url : "https://" + url;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- Listado

    private void showVideoList() {
        if (found.isEmpty()) {
            Toast.makeText(this, R.string.no_videos, Toast.LENGTH_LONG).show();
            return;
        }
        final List<MediaItem> items = new ArrayList<>(found.values());
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            MediaItem it = items.get(i);
            labels[i] = "[" + it.type + "]\n" + shorten(it.url);
        }
        new AlertDialog.Builder(this)
                .setTitle("Videos detectados")
                .setItems(labels, (d, which) -> showItemActions(items.get(which)))
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void showItemActions(MediaItem item) {
        String[] actions = {"Descargar", "Copiar enlace", "Abrir en otra app"};
        new AlertDialog.Builder(this)
                .setTitle(item.type)
                .setMessage(item.url)
                .setItems(actions, (d, which) -> {
                    if (which == 0) download(item);
                    else if (which == 1) copyToClipboard(item.url);
                    else openExternally(item.url);
                })
                .show();
    }

    private static String shorten(String url) {
        return url.length() > 120 ? url.substring(0, 117) + "…" : url;
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("url", text));
            Toast.makeText(this, "Enlace copiado", Toast.LENGTH_SHORT).show();
        }
    }

    private void openExternally(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(url), "video/*");
        try {
            startActivity(Intent.createChooser(intent, "Abrir con"));
        } catch (Exception e) {
            Toast.makeText(this, "No hay apps para abrir este video", Toast.LENGTH_SHORT).show();
        }
    }

    // -------------------------------------------------------------- Descargas

    private String baseName() {
        String title = webView.getTitle();
        return MediaDetector.safeFileName(TextUtils.isEmpty(title) ? null : title);
    }

    private void download(MediaItem item) {
        if (needsStoragePermission()) {
            pendingDownload = item;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERMISSIONS);
            return;
        }
        String referer = item.referer != null ? item.referer : webView.getUrl();
        switch (item.type) {
            case MediaDetector.TYPE_HLS:
                Intent intent = new Intent(this, HlsDownloadService.class)
                        .putExtra(HlsDownloadService.EXTRA_URL, item.url)
                        .putExtra(HlsDownloadService.EXTRA_REFERER, referer)
                        .putExtra(HlsDownloadService.EXTRA_USER_AGENT, webView.getSettings().getUserAgentString())
                        .putExtra(HlsDownloadService.EXTRA_COOKIES, CookieManager.getInstance().getCookie(item.url))
                        .putExtra(HlsDownloadService.EXTRA_NAME, baseName());
                startForegroundService(intent);
                Toast.makeText(this, "Descarga iniciada (ver notificación)", Toast.LENGTH_SHORT).show();
                break;
            case MediaDetector.TYPE_DASH:
                new AlertDialog.Builder(this)
                        .setTitle("Formato DASH")
                        .setMessage("Este video usa el formato DASH (.mpd), que separa audio y video en "
                                + "muchas partes y todavía no se puede descargar desde la app. "
                                + "Busca en la lista si hay otra versión MP4 o HLS del mismo video.")
                        .setPositiveButton("Entendido", null)
                        .show();
                break;
            default:
                startDirectDownload(item.url, referer, baseName() + MediaDetector.extensionFor(item.url));
        }
    }

    private void startDirectDownload(String url, String referer, String fileName) {
        if (needsStoragePermission()) {
            pendingDownload = new MediaItem(url, MediaDetector.TYPE_VIDEO, referer);
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERMISSIONS);
            return;
        }
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) req.addRequestHeader("Cookie", cookies);
            req.addRequestHeader("User-Agent", webView.getSettings().getUserAgentString());
            if (referer != null) req.addRequestHeader("Referer", referer);
            req.setTitle(fileName);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    HlsDownloadService.FOLDER + "/" + fileName);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(req);
            Toast.makeText(this, "Descargando en Descargas/" + HlsDownloadService.FOLDER, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo iniciar la descarga: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // -------------------------------------------------------------- Permisos

    private boolean needsStoragePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (needsStoragePermission()) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (!perms.isEmpty()) requestPermissions(perms.toArray(new String[0]), REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (pendingDownload == null) return;
        MediaItem item = pendingDownload;
        pendingDownload = null;
        if (needsStoragePermission()) {
            Toast.makeText(this, "Se necesita permiso de almacenamiento para descargar", Toast.LENGTH_LONG).show();
        } else {
            download(item);
        }
    }
}

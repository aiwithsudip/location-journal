package app.locationjournal;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private boolean pendingCapture;
    private boolean pendingReplaceHourly;
    private TorchController torch;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (hasFineLocation()) {
                    TrackService.start(this, pendingCapture, pendingReplaceHourly);
                    pendingCapture = false;
                    askBackground();
                } else {
                    getSharedPreferences("journal", MODE_PRIVATE).edit().putBoolean("tracking", false).apply();
                    tellPage("window.__nativeTrackingResult && window.__nativeTrackingResult(false, 'Location permission is required.')");
                }
            });

    private final ActivityResultLauncher<String> backgroundLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> { });

    private void askBackground() {
        if (Build.VERSION.SDK_INT >= 29
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }
    }

    private PermissionRequest pendingWebPermission;

    private final ActivityResultLauncher<String> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (pendingWebPermission != null) {
                    if (granted) pendingWebPermission.grant(pendingWebPermission.getResources());
                    else pendingWebPermission.deny();
                    pendingWebPermission = null;
                }
                tellPage("window.__onCamera && window.__onCamera()");
            });

    public void askCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            tellPage("window.__onCamera && window.__onCamera()");
            return;
        }
        cameraLauncher.launch(Manifest.permission.CAMERA);
    }

    private final ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> photoPicker =
            registerForActivityResult(new ActivityResultContracts.PickMultipleVisualMedia(20), this::deliverPicked);

    private final ActivityResultLauncher<Intent> fileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (fileCallback == null) return;
                Uri[] uris = null;
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        uris = new Uri[count];
                        for (int i = 0; i < count; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) {
                        uris = new Uri[]{data.getData()};
                    }
                }
                fileCallback.onReceiveValue(uris);
                fileCallback = null;
            });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setGeolocationEnabled(false);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        torch = new TorchController(this);
        torch.setListener(this::tellTorch);
        torch.start();
        webView.addJavascriptInterface(new JournalBridge(this), "AndroidJournal");

        WebViewAssetLoader assets = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assets.shouldInterceptRequest(request.getUrl());
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.getResources());
                        return;
                    }
                    pendingWebPermission = request;
                    cameraLauncher.launch(Manifest.permission.CAMERA);
                });
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                        .setOnCancelListener(dialog -> result.confirm())
                        .show();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                boolean capture = params != null && params.isCaptureEnabled();
                if (!capture && ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(MainActivity.this)) {
                    photoPicker.launch(new androidx.activity.result.PickVisualMediaRequest.Builder()
                            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                            .build());
                    return true;
                }
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                if (!capture) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                fileLauncher.launch(Intent.createChooser(intent, "Photos"));
                return true;
            }
        });
        TrackService.onPointsChanged = () -> runOnUiThread(() ->
                tellPage("window.__onNativePoints && window.__onNativePoints()"));
        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
    }

    public void beginTracking() {
        pendingCapture = true;
        pendingReplaceHourly = true;
        askAndStart();
    }

    public void captureOnce(boolean replaceHourly) {
        pendingCapture = true;
        pendingReplaceHourly = replaceHourly;
        askAndStart();
    }

    private void askAndStart() {
        List<String> needed = new ArrayList<>();
        if (!hasFineLocation()) needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (needed.isEmpty()) {
            TrackService.start(this, pendingCapture, pendingReplaceHourly);
            pendingCapture = false;
            return;
        }
        permissionLauncher.launch(needed.toArray(new String[0]));
    }

    private boolean hasFineLocation() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void tellPage(String script) {
        if (webView != null) webView.evaluateJavascript(script, null);
    }

    private void deliverPicked(List<Uri> uris) {
        if (fileCallback == null) return;
        if (uris == null || uris.isEmpty()) fileCallback.onReceiveValue(null);
        else fileCallback.onReceiveValue(uris.toArray(new Uri[0]));
        fileCallback = null;
    }

    public String torchState() {
        return torch == null ? "unsupported" : torch.state();
    }

    public String requestTorch(boolean on) {
        if (torch == null) return "unsupported";
        try {
            return torch.request(on);
        } catch (SecurityException error) {
            tellTorch("denied");
            return "denied";
        }
    }

    private void tellTorch(String state) {
        String safe = state == null ? "error" : state.replace("'", "");
        runOnUiThread(() -> tellPage("window.__onTorch && window.__onTorch('" + safe + "')"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        tellPage("window.__onNativePoints && window.__onNativePoints()");
        if (torch != null) tellTorch(torch.state());
    }

    @Override
    protected void onPause() {
        if (torch != null) torch.turnOff();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (torch != null) torch.stop();
        if (TrackService.onPointsChanged != null) TrackService.onPointsChanged = null;
        super.onDestroy();
    }
}

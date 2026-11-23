package app.locationjournal;

import android.webkit.JavascriptInterface;

public final class JournalBridge {
    private final MainActivity activity;

    public JournalBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public boolean isTracking() {
        return activity.getSharedPreferences("journal", MainActivity.MODE_PRIVATE).getBoolean("tracking", false);
    }

    @JavascriptInterface
    public void setTracking(boolean on) {
        activity.getSharedPreferences("journal", MainActivity.MODE_PRIVATE).edit().putBoolean("tracking", on).apply();
        activity.runOnUiThread(() -> {
            if (on) activity.beginTracking();
            else activity.stopService(new android.content.Intent(activity, TrackService.class));
        });
    }

    @JavascriptInterface
    public void requestFix(String reason) {
        boolean replace = "now".equals(reason) || "this hour".equals(reason);
        activity.runOnUiThread(() -> activity.captureOnce(replace));
    }

    @JavascriptInterface
    public String pullFixes() {
        return new PointStore(activity).allJson();
    }

    @JavascriptInterface
    public void ensureCamera() {
        activity.runOnUiThread(activity::askCamera);
    }
}

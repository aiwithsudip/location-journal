package app.locationjournal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean tracking = context.getSharedPreferences("journal", Context.MODE_PRIVATE).getBoolean("tracking", false);
        if (!tracking) return;
        try {
            TrackService.start(context, false, false);
        } catch (Exception ignored) {
            /* Android may block a foreground start from boot. Opening the app resumes it. */
        }
    }
}

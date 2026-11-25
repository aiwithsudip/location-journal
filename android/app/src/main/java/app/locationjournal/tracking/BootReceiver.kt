package app.locationjournal.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.locationjournal.domain.TrackingState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: TrackingController

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (controller.current() != TrackingState.RUNNING) return
        try {
            context.startForegroundService(Intent(context, JournalTrackingService::class.java).setAction(JournalTrackingService.ACTION_RESUME))
        } catch (_: Exception) {
            // Android can block foreground starts from boot; opening the app resumes tracking.
        }
    }
}

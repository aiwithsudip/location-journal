package app.locationjournal.tracking

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.locationjournal.MainActivity
import app.locationjournal.R
import app.locationjournal.data.JournalRepository
import app.locationjournal.domain.GeoPoint
import app.locationjournal.domain.TrackingState
import app.locationjournal.domain.hourKey
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class JournalTrackingService : Service() {
    @Inject lateinit var repository: JournalRepository
    @Inject lateinit var controller: TrackingController

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + job + CoroutineExceptionHandler { _, _ -> })
    private lateinit var locations: LocationManager
    private var listener: LocationListener? = null
    private var updatesRunning = false
    private var notificationState: TrackingState = TrackingState.STOPPED
    private var lastSampleTime = 0L
    private var lastHour = ""

    override fun onCreate() {
        super.onCreate()
        locations = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
        controller.syncFromSystem(if (controller.current() == TrackingState.STOPPED) TrackingState.STOPPED else controller.current())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_STOP -> {
                stopUpdates()
                controller.syncFromSystem(TrackingState.STOPPED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                stopUpdates()
                controller.syncFromSystem(TrackingState.PAUSED)
                notificationState = TrackingState.PAUSED
                startForeground(NOTICE_ID, buildNotification())
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                controller.syncFromSystem(TrackingState.RUNNING)
                notificationState = TrackingState.RUNNING
                startForeground(NOTICE_ID, buildNotification())
                startUpdates()
                return START_STICKY
            }
            ACTION_FIX -> {
                notificationState = controller.current().takeIf { it != TrackingState.STOPPED } ?: TrackingState.RUNNING
                startForeground(NOTICE_ID, buildNotification())
                val replace = intent?.getBooleanExtra(EXTRA_REPLACE_HOURLY, false) ?: false
                requestSingleFix(replace)
                return if (controller.current() == TrackingState.RUNNING) START_STICKY else START_NOT_STICKY
            }
            else -> {
                notificationState = TrackingState.RUNNING
                startForeground(NOTICE_ID, buildNotification())
                startUpdates()
                return START_STICKY
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (updatesRunning || !hasLocationPermission()) {
            if (!hasLocationPermission()) {
                controller.syncFromSystem(TrackingState.STOPPED)
                notificationState = TrackingState.STOPPED
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        scope.launch {
            val settings = repository.settings.first()
            val provider = bestProvider() ?: return@launch
            val interval = settings.sampleIntervalSeconds * 1000L
            listener = LocationListener { location -> onLocation(location, interval) }
            try {
                locations.requestLocationUpdates(provider, interval, 5f, listener!!, Looper.getMainLooper())
                updatesRunning = true
            } catch (_: SecurityException) {
                controller.syncFromSystem(TrackingState.STOPPED)
                stopSelf()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleFix(replaceHourly: Boolean) {
        if (!hasLocationPermission()) return
        scope.launch {
            val provider = bestProvider() ?: return@launch
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locations.getCurrentLocation(provider, null, applicationContext.mainExecutor) { location ->
                    if (location != null) onLocation(location, 0L, replaceHourly)
                }
            } else {
                val oneShot = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        locations.removeUpdates(this)
                        onLocation(location, 0L, replaceHourly)
                    }
                }
                try {
                    locations.requestSingleUpdate(provider, oneShot, Looper.getMainLooper())
                } catch (_: SecurityException) {
                    // Permission was revoked while the app was running.
                }
            }
        }
    }

    private fun onLocation(location: Location, intervalMs: Long, forceHourly: Boolean = false) {
        val t = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        if (t == lastSampleTime || (intervalMs > 0 && t - lastSampleTime < intervalMs / 2)) return
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
        lastSampleTime = t
        val hour = hourKey(t)
        val replaceHourly = forceHourly || hour != lastHour
        lastHour = hour
        val point = GeoPoint(
            id = "sample:$t",
            t = t,
            lat = location.latitude,
            lng = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else 0f,
            altitude = if (location.hasAltitude()) location.altitude else null,
            speed = if (location.hasSpeed()) location.speed else null,
            bearing = if (location.hasBearing()) location.bearing else null,
            kind = "sample",
            hour = hour,
        )
        scope.launch { repository.saveFix(point, replaceHourly) }
    }

    private fun stopUpdates() {
        listener?.let { locations.removeUpdates(it) }
        listener = null
        updatesRunning = false
    }

    private fun bestProvider(): String? = when {
        locations.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        else -> null
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_tracking), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.channel_tracking_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                if (notificationState == TrackingState.PAUSED) getString(R.string.tracking_paused_notice)
                else getString(R.string.tracking_notice)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (notificationState == TrackingState.RUNNING) {
            builder.addAction(R.drawable.ic_stat_track, getString(R.string.action_pause), actionIntent(ACTION_PAUSE, 1))
            builder.addAction(R.drawable.ic_stat_track, getString(R.string.action_stop), actionIntent(ACTION_STOP, 2))
        } else if (notificationState == TrackingState.PAUSED) {
            builder.addAction(R.drawable.ic_stat_track, getString(R.string.action_resume), actionIntent(ACTION_RESUME, 3))
            builder.addAction(R.drawable.ic_stat_track, getString(R.string.action_stop), actionIntent(ACTION_STOP, 2))
        }
        return builder.build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, JournalTrackingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    override fun onDestroy() {
        stopUpdates()
        job.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "app.locationjournal.START"
        const val ACTION_PAUSE = "app.locationjournal.PAUSE"
        const val ACTION_RESUME = "app.locationjournal.RESUME"
        const val ACTION_STOP = "app.locationjournal.STOP"
        const val ACTION_FIX = "app.locationjournal.FIX"
        const val EXTRA_REPLACE_HOURLY = "replaceHourly"
        private const val CHANNEL_ID = "tracking"
        private const val NOTICE_ID = 7
    }
}

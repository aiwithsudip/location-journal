package app.locationjournal;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class TrackService extends Service {
    public static final String EXTRA_ONCE = "once";
    public static final String EXTRA_REPLACE_HOURLY = "replaceHourly";
    public static volatile Runnable onPointsChanged;

    private static final String CHANNEL = "tracking";
    private static final int NOTICE = 7;
    private static final long SAMPLE_MS = 20 * 1000L;

    private LocationManager locations;
    private LocationListener listener;
    private boolean updatesRunning;

    @Override
    public void onCreate() {
        super.onCreate();
        locations = (LocationManager) getSystemService(LOCATION_SERVICE);
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTICE, buildNotice());
        if (!hasLocationPermission()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean once = intent != null && intent.getBooleanExtra(EXTRA_ONCE, false);
        boolean replaceHourly = intent != null && intent.getBooleanExtra(EXTRA_REPLACE_HOURLY, false);
        boolean tracking = getSharedPreferences("journal", MODE_PRIVATE).getBoolean("tracking", false);
        requestImmediate(replaceHourly);
        if (tracking) {
            startUpdates();
            return START_STICKY;
        }
        if (!once) stopSelf();
        return START_NOT_STICKY;
    }

    @SuppressLint("MissingPermission")
    private void requestImmediate(boolean replaceHourly) {
        LocationListener one = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                store(location, replaceHourly);
                locations.removeUpdates(this);
                boolean tracking = getSharedPreferences("journal", MODE_PRIVATE).getBoolean("tracking", false);
                if (!tracking) stopSelf();
            }
        };
        String provider = provider();
        if (provider == null) return;
        if (Build.VERSION.SDK_INT >= 30) {
            locations.getCurrentLocation(provider, null, getMainExecutor(), location -> {
                if (location != null) one.onLocationChanged(location);
            });
        } else {
            locations.requestSingleUpdate(provider, one, Looper.getMainLooper());
        }
    }

    @SuppressLint("MissingPermission")
    private void startUpdates() {
        if (updatesRunning) return;
        String provider = provider();
        if (provider == null) return;
        listener = location -> store(location, false);
        locations.requestLocationUpdates(provider, SAMPLE_MS, 0f, listener, Looper.getMainLooper());
        updatesRunning = true;
    }

    private void store(Location location, boolean replaceHourly) {
        if (location == null) return;
        new PointStore(this).saveFix(location.getLatitude(), location.getLongitude(), location.getAccuracy(), replaceHourly);
        Runnable callback = onPointsChanged;
        if (callback != null) callback.run();
    }

    private String provider() {
        if (locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) return LocationManager.GPS_PROVIDER;
        if (locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return LocationManager.NETWORK_PROVIDER;
        return null;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Location tracking", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while hourly location tracking is on.");
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotice() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Location journal")
                .setContentText(getString(R.string.tracking_notice))
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }

    @Override
    public void onDestroy() {
        if (listener != null && locations != null) locations.removeUpdates(listener);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context, boolean once, boolean replaceHourly) {
        Intent intent = new Intent(context, TrackService.class);
        intent.putExtra(EXTRA_ONCE, once);
        intent.putExtra(EXTRA_REPLACE_HOURLY, replaceHourly);
        ContextCompat.startForegroundService(context, intent);
    }
}

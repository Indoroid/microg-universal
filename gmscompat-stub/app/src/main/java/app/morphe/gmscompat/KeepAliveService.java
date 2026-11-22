package app.morphe.gmscompat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground service that holds an important binding to microG, so OEM task killers do not stop
 * it and push messages keep arriving. Same idea as GmsCompat's PersistentFgService +
 * GmsCorePersistentService, but without privileged permissions.
 */
public final class KeepAliveService extends Service {
    private static final String TAG = "GmsCompatStub";
    static final String CHANNEL = "keepalive";
    private static final int NOTIFICATION_ID = 1;

    private ServiceConnection connection;
    static volatile boolean running;

    static void start(Context ctx) {
        Intent i = new Intent(ctx, KeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception e) {
            // Background start restriction (Android 12+): the next boot or opening the stub starts it.
            Log.w(TAG, "keep-alive start deferred: " + e);
        }
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, KeepAliveService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Detector.GmsCore gms = Detector.preferredMicroG(this);
        goForeground(gms);
        if (gms == null || !Prefs.keepAlive(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        bind(gms);
        running = true;
        return START_STICKY;
    }

    private void bind(Detector.GmsCore gms) {
        if (connection != null) return;
        // microG's push register service returns a Messenger and does nothing until messaged.
        Intent target = new Intent(gms.vendorPrefix() + ".android.c2dm.intent.REGISTER").setPackage(gms.packageName);
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n, IBinder b) { Log.i(TAG, "bound " + n); }
            @Override public void onServiceDisconnected(ComponentName n) { Log.w(TAG, "microG disconnected " + n); }
        };
        int flags = BIND_AUTO_CREATE | BIND_IMPORTANT;
        if (!bindService(target, connection, flags)) {
            Log.w(TAG, "could not bind " + target);
            connection = null;
        }
    }

    private void goForeground(Detector.GmsCore gms) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Keep microG alive",
                    NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(gms != null ? "Keeping " + gms.label() + " running" : "microG not found")
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, b.build());
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (connection != null) {
            try {
                unbindService(connection);
            } catch (Exception ignored) {
            }
            connection = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

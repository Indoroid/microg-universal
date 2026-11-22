package app.morphe.gmscompat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

/**
 * Status broker for patched apps. Contract (must match MicroGBridge in the patch extension):
 * <ul>
 *   <li>call("status", callerPackage) and call("register", callerPackage) return a Bundle with
 *   gms_package, gms_installed, gms_version, gms_battery_optimized, keepalive_running,
 *   prompts_handled.</li>
 * </ul>
 * Only non-sensitive state is exposed; "register" is accepted only for the calling package itself.
 */
public final class BrokerProvider extends ContentProvider {
    private static final String CHANNEL = "setup";
    private static final int NOTIFICATION_SETUP = 2;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        String caller = Build.VERSION.SDK_INT >= 19 ? getCallingPackage() : arg;
        if ("register".equals(method) && caller != null && caller.equals(arg)) {
            Prefs.addClient(ctx, caller);
        } else if (!"status".equals(method)) {
            return null;
        }

        Detector.GmsCore gms = Detector.preferredMicroG(ctx);
        boolean batteryOptimized = gms != null && Detector.isBatteryOptimized(ctx, gms.packageName);
        if ("register".equals(method)) {
            if (gms != null && Prefs.keepAlive(ctx) && !KeepAliveService.running) KeepAliveService.start(ctx);
            if (gms == null || batteryOptimized) notifySetup(ctx, gms == null);
        }

        Bundle b = new Bundle();
        b.putString("gms_package", gms != null ? gms.packageName : null);
        b.putBoolean("gms_installed", gms != null);
        b.putLong("gms_version", gms != null ? gms.versionCode : 0);
        b.putBoolean("gms_battery_optimized", batteryOptimized);
        b.putBoolean("keepalive_running", KeepAliveService.running);
        b.putBoolean("prompts_handled", notificationsEnabled(ctx));
        return b;
    }

    private static boolean notificationsEnabled(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        return nm != null && (Build.VERSION.SDK_INT < 24 || nm.areNotificationsEnabled());
    }

    /** One notification for all patched apps instead of a dialog in each of them. */
    private static void notifySetup(Context ctx, boolean missing) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "microG setup",
                    NotificationManager.IMPORTANCE_DEFAULT));
            b = new Notification.Builder(ctx, CHANNEL);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(missing ? "microG is not installed" : "microG is battery optimized")
                .setContentText(missing ? "Patched apps need microG for sign-in, push and maps."
                        : "Push notifications of patched apps may be delayed.")
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(ctx, 0,
                        new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        nm.notify(NOTIFICATION_SETUP, b.build());
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}

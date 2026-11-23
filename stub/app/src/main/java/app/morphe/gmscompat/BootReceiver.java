package app.morphe.gmscompat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** BOOT_COMPLETED is exempt from background foreground-service start restrictions. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Prefs.keepAlive(ctx) && !Prefs.clients(ctx).isEmpty() && Detector.preferredMicroG(ctx) != null) {
            KeepAliveService.start(ctx);
        }
    }
}

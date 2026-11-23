package app.morphe.extension.microg;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;

import app.morphe.extension.microg.contract.MicroGContract;

/**
 * Runtime half of the universal microG patch: the in-app equivalent of GrapheneOS'
 * GmsCompat client hooks, injected into the patched app instead of the OS.
 * <p>
 * Every PackageManager.getPackageInfo call site of the app is redirected here, so the
 * play-services client library sees the installed microG (and an optional Play Store
 * companion) with the Google certificate it expects, regardless of obfuscation.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class MicroGBridge {
    private static final String TAG = "MorpheMicroG";

    // Replaced during patching. Never written as com.google.* so the patch's string rewrite
    // can not touch them.
    static String GMS_PACKAGE = "MICROG_GMS_PACKAGE_PLACEHOLDER";
    static String STUB_ENABLED = "MICROG_STUB_ENABLED_PLACEHOLDER";
    static String PROMPTS_ENABLED = "MICROG_PROMPTS_ENABLED_PLACEHOLDER";

    private static final String VENDING_PACKAGE = "com.android.vending";
    private static final String CLIENT_VERSION_METADATA = "com.google.android.gms" + ".version";


    private static final String PREFS = "morphe_microg";
    private static final String PREF_DONT_ASK = "dont_ask";

    private static volatile Context appContext;
    private static volatile Signature googleSignature;
    private static volatile Boolean microGInstalled;
    private static volatile int requiredClientVersion = -1;

    private MicroGBridge() {
    }

    // region PackageManager call-site replacements.

    public static PackageInfo getPackageInfo(PackageManager pm, String packageName, int flags)
            throws PackageManager.NameNotFoundException {
        return spoof(pm, packageName, pm.getPackageInfo(packageName, flags));
    }

    public static PackageInfo getPackageInfo(PackageManager pm, String packageName,
                                             PackageManager.PackageInfoFlags flags)
            throws PackageManager.NameNotFoundException {
        return spoof(pm, packageName, pm.getPackageInfo(packageName, flags));
    }

    private static PackageInfo spoof(PackageManager pm, String packageName, PackageInfo info) {
        if (info == null || packageName == null) return info;
        boolean gms = packageName.equals(GMS_PACKAGE);
        if (!gms && !packageName.equals(VENDING_PACKAGE)) return info;

        Signature google = googleSignature();
        if (info.signatures != null) {
            info.signatures = new Signature[]{google};
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            // Arrays are returned by reference; for a single signer without lineage both
            // getters return the same array.
            replaceFirst(info.signingInfo.getApkContentsSigners(), google);
            replaceFirst(info.signingInfo.getSigningCertificateHistory(), google);
        }
        if (gms) {
            int required = requiredClientVersion(pm);
            if (required > 0 && info.versionCode < required) {
                // microG implements the APIs it advertises; an older version code only makes the
                // client library refuse to connect.
                info.versionCode = required;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.setLongVersionCode(required);
            }
        }
        return info;
    }

    private static void replaceFirst(Signature[] array, Signature signature) {
        if (array != null && array.length > 0) array[0] = signature;
    }

    private static Signature googleSignature() {
        Signature s = googleSignature;
        if (s == null) {
            s = new Signature(Base64.decode(MicroGContract.GOOGLE_CERT_DER_BASE64, Base64.DEFAULT));
            googleSignature = s;
        }
        return s;
    }

    private static int requiredClientVersion(PackageManager pm) {
        int v = requiredClientVersion;
        if (v >= 0) return v;
        v = 0;
        Context ctx = context();
        if (ctx != null) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(ctx.getPackageName(), PackageManager.GET_META_DATA);
                if (ai.metaData != null) v = ai.metaData.getInt(CLIENT_VERSION_METADATA, 0);
            } catch (Exception ignored) {
            }
        }
        requiredClientVersion = v;
        return v;
    }

    // endregion

    // region Availability (used by the isGooglePlayServicesAvailable fingerprint hook).

    /**
     * @return true if the availability check may report SUCCESS without running Google's checks.
     */
    public static boolean forcePlayServicesAvailable() {
        return isMicroGInstalled();
    }

    public static boolean isMicroGInstalled() {
        Boolean installed = microGInstalled;
        if (installed != null) return installed;
        Context ctx = context();
        if (ctx == null) return false;
        boolean result;
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(GMS_PACKAGE, 0);
            result = ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            result = false;
        }
        microGInstalled = result;
        return result;
    }

    // endregion

    // region Init and prompts.

    /**
     * Called by {@link MicroGInitProvider} before Application.onCreate in every process.
     */
    static void init(Context context) {
        Context app = context.getApplicationContext();
        appContext = app != null ? app : context;

        if (!isMainProcess()) return;

        boolean installed = isMicroGInstalled();
        Bundle stub = Boolean.parseBoolean(STUB_ENABLED) ? callStub(MicroGContract.CALL_REGISTER) : null;
        boolean stubHandlesPrompts = stub != null && stub.getBoolean(MicroGContract.KEY_PROMPTS_HANDLED, false);
        Log.i(TAG, "microG " + GMS_PACKAGE + " installed=" + installed + ", stub=" + (stub != null));

        if (!Boolean.parseBoolean(PROMPTS_ENABLED) || stubHandlesPrompts) return;

        String message = null;
        if (!installed) {
            message = "This app uses microG instead of Google Play services. Install MicroG-RE ("
                    + GMS_PACKAGE + ") for sign-in, push notifications and maps.";
        } else if (isBatteryOptimized()) {
            message = "Battery optimization limits microG, so push notifications may be delayed "
                    + "or missing. Allow microG to run in the background.";
        }
        if (message != null && !prefs().getBoolean(PREF_DONT_ASK, false)) {
            showOnFirstActivity(message, !installed);
        }
    }

    private static Bundle callStub(String method) {
        Context ctx = context();
        try {
            return ctx.getContentResolver().call(Uri.parse(MicroGContract.BROKER_URI), method, ctx.getPackageName(), null);
        } catch (Exception e) {
            // Stub not installed or not visible. Patched apps work without it.
            return null;
        }
    }

    private static boolean isBatteryOptimized() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        PowerManager pm = (PowerManager) context().getSystemService(Context.POWER_SERVICE);
        return pm != null && !pm.isIgnoringBatteryOptimizations(GMS_PACKAGE);
    }

    private static boolean isMainProcess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true;
        return context().getPackageName().equals(Application.getProcessName());
    }

    private static SharedPreferences prefs() {
        return context().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void showOnFirstActivity(String message, boolean missing) {
        Context ctx = context();
        if (!(ctx instanceof Application)) return;
        Application app = (Application) ctx;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                app.unregisterActivityLifecycleCallbacks(this);
                // Delay so the activity finishes its first layout and theme setup.
                new Handler(Looper.getMainLooper()).postDelayed(() -> show(activity, message, missing), 300);
            }

            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    private static void show(Activity activity, String message, boolean missing) {
        if (activity.isFinishing()) return;
        try {
            M3Dialog.show(activity, missing ? "Install microG" : "microG is restricted", message,
                    missing ? "Install" : "Fix", () -> openFix(activity, missing),
                    "Later",
                    "Don't ask again", () -> prefs().edit().putBoolean(PREF_DONT_ASK, true).apply());
        } catch (Exception e) {
            Log.w(TAG, "Could not show microG dialog", e);
        }
    }

    private static void openFix(Activity activity, boolean missing) {
        Intent stub = new Intent(MicroGContract.ACTION_SETUP).setPackage(MicroGContract.STUB_PACKAGE);
        try {
            activity.startActivity(stub);
            return;
        } catch (Exception ignored) {
            // No stub: fall through.
        }
        Intent intent;
        if (missing) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(MicroGContract.MICROG_DOWNLOAD_URL));
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intent = new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        } else {
            return;
        }
        try {
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Could not open " + intent, e);
        }
    }

    private static Context context() {
        Context ctx = appContext;
        if (ctx != null) return ctx;
        try {
            // Fallback if a hooked method runs before the init provider (static initializers).
            ctx = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            appContext = ctx;
        } catch (Exception ignored) {
        }
        return ctx;
    }

    // endregion
}

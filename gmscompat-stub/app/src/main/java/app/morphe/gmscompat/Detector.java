package app.morphe.gmscompat;

import app.morphe.extension.microg.contract.MicroGContract;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.PowerManager;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Finds the installed GmsCore implementation (the stub's view of "which microG is here"). */
final class Detector {
    static final String MICROG_RE_SHA256 = MicroGContract.MICROG_RE_SHA256;
    static final String GOOGLE_ORIGINAL_SHA256 = MicroGContract.GOOGLE_ORIGINAL_SHA256;
    static final String GOOGLE_ROTATED_SHA256 = MicroGContract.GOOGLE_ROTATED_SHA256;
    static final String[] CANDIDATES = MicroGContract.gmsCandidates();

    static final class GmsCore {
        final String packageName;
        final String versionName;
        final long versionCode;
        final String signerSha256;
        final boolean enabled;

        GmsCore(String packageName, String versionName, long versionCode, String signerSha256, boolean enabled) {
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.signerSha256 = signerSha256;
            this.enabled = enabled;
        }

        boolean isRealGoogle() {
            return GOOGLE_ORIGINAL_SHA256.equals(signerSha256) || GOOGLE_ROTATED_SHA256.equals(signerSha256);
        }

        boolean isOfficialMicroGRe() {
            return MICROG_RE_SHA256.equals(signerSha256);
        }

        String vendorPrefix() {
            return packageName.substring(0, packageName.length() - ".android.gms".length());
        }

        String label() {
            if (isRealGoogle()) return "Google Play services";
            switch (packageName) {
                case "app.revanced.android.gms": return "MicroG-RE";
                case "com.mgoogle.android.gms": return "Vanced microG";
                default: return "microG";
            }
        }
    }

    private Detector() {
    }

    /** Every installed candidate, preferred first. */
    static List<GmsCore> findAll(Context ctx) {
        List<GmsCore> out = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : CANDIDATES) {
            try {
                @SuppressWarnings("deprecation")
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                @SuppressWarnings("deprecation")
                Signature[] sigs = pi.signatures;
                long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                out.add(new GmsCore(pkg, pi.versionName, code,
                        sigs != null && sigs.length > 0 ? sha256(sigs[0].toByteArray()) : null,
                        pi.applicationInfo.enabled));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return out;
    }

    /** The microG patched apps should talk to, or null. Real Google Play services is never chosen. */
    static GmsCore preferredMicroG(Context ctx) {
        for (GmsCore g : findAll(ctx)) {
            if (g.enabled && !g.isRealGoogle()) return g;
        }
        return null;
    }

    static boolean isBatteryOptimized(Context ctx, String pkg) {
        if (Build.VERSION.SDK_INT < 23) return false;
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        return pm != null && !pm.isIgnoringBatteryOptimizations(pkg);
    }

    static String sha256(byte[] data) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}

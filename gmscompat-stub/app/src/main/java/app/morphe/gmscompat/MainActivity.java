package app.morphe.gmscompat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/** Status and one-tap fixes (the sideloadable counterpart of GmsCompat's config UI). */
public final class MainActivity extends Activity {
    private static final String MICROG_DOWNLOAD = "https://github.com/MorpheApp/MicroG-RE/releases/latest";

    private LinearLayout root;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 0);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        root.removeAllViews();
        List<Detector.GmsCore> all = Detector.findAll(this);
        Detector.GmsCore gms = Detector.preferredMicroG(this);

        StringBuilder sb = new StringBuilder();
        if (all.isEmpty()) sb.append("No GmsCore implementation installed.\n");
        for (Detector.GmsCore g : all) {
            sb.append("• ").append(g.label()).append(" ").append(g.versionName)
                    .append(g.enabled ? "" : " (disabled)").append('\n');
        }
        if (gms != null) {
            sb.append("\nPatched apps use: ").append(gms.packageName).append('\n')
                    .append("Battery optimized: ").append(Detector.isBatteryOptimized(this, gms.packageName) ? "yes ⚠" : "no")
                    .append('\n').append("Keep-alive running: ").append(KeepAliveService.running ? "yes" : "no").append('\n');
            if (!gms.isOfficialMicroGRe() && gms.packageName.startsWith("app.revanced")) {
                sb.append("⚠ Signed with an unofficial key: updates from MicroG-RE releases will not install.\n");
            }
        }
        sb.append("\nRegistered patched apps:\n");
        for (String c : Prefs.clients(this)) sb.append("• ").append(c).append('\n');
        text(sb.toString());

        if (gms == null) {
            button("Install MicroG-RE", v -> open(new Intent(Intent.ACTION_VIEW, Uri.parse(MICROG_DOWNLOAD))));
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && Detector.isBatteryOptimized(this, gms.packageName)) {
            button("Allow microG in background", v -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", gms.packageName, null))));
        }
        if (Build.VERSION.SDK_INT >= 23 && Detector.isBatteryOptimized(this, getPackageName())) {
            button("Allow this stub in background", v -> open(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", getPackageName(), null))));
        }
        button("Open microG settings", v -> open(new Intent().setClassName(gms.packageName, "org.microg.gms.ui.SettingsActivity")));

        Switch keepAlive = new Switch(this);
        keepAlive.setText("Keep microG alive (foreground service)");
        keepAlive.setChecked(Prefs.keepAlive(this));
        keepAlive.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            Prefs.setKeepAlive(this, on);
            if (on) KeepAliveService.start(this); else KeepAliveService.stop(this);
            root.postDelayed(this::render, 500);
        });
        root.addView(keepAlive);
        if (Prefs.keepAlive(this) && !KeepAliveService.running) KeepAliveService.start(this);
    }

    private void text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextIsSelectable(true);
        root.addView(t);
    }

    private void button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        root.addView(b);
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            text("Could not open: " + e.getMessage());
        }
    }
}

package app.morphe.gmscompat;

import app.morphe.extension.microg.contract.MicroGContract;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;

/** Status and one-tap fixes; the sideloadable counterpart of GrapheneOS' GmsCompat settings. */
public final class MainActivity extends AppCompatActivity {
    private static final String MICROG_DOWNLOAD = MicroGContract.MICROG_DOWNLOAD_URL;

    private LinearLayout healthRows;
    private LinearLayout gmsRows;
    private LinearLayout clientRows;

    @Override
    protected void onCreate(Bundle state) {
        EdgeToEdge.enable(this);
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        setSupportActionBar(findViewById(R.id.toolbar));
        setTitle(R.string.title);
        healthRows = findViewById(R.id.health_rows);
        gmsRows = findViewById(R.id.gms_rows);
        clientRows = findViewById(R.id.client_rows);
        // Edge-to-edge: keep the last rows clear of the navigation bar.
        View scroll = findViewById(R.id.scroll);
        int basePadding = scroll.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, v.getPaddingTop(), bars.right, basePadding + bars.bottom);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        if (Prefs.keepAlive(this) && Detector.preferredMicroG(this) != null && !KeepAliveService.running) {
            KeepAliveService.start(this);
            findViewById(R.id.scroll).postDelayed(this::render, 800);
        }
    }

    private void render() {
        Detector.GmsCore gms = Detector.preferredMicroG(this);
        boolean gmsOptimized = gms != null && Detector.isBatteryOptimized(this, gms.packageName);
        boolean stubOptimized = Detector.isBatteryOptimized(this, getPackageName());
        boolean notifications = NotificationManagerCompat.from(this).areNotificationsEnabled();

        renderStatus(gms, gmsOptimized || stubOptimized);

        healthRows.removeAllViews();
        if (gms != null) {
            row(healthRows, R.drawable.ic_battery, getString(R.string.row_battery_microg),
                    getString(gmsOptimized ? R.string.row_battery_bad : R.string.row_battery_ok),
                    gmsOptimized ? tonalButton(R.string.action_fix, v -> open(new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", gms.packageName, null))))
                            : okIcon());
        }
        row(healthRows, R.drawable.ic_battery, getString(R.string.row_battery_stub),
                getString(stubOptimized ? R.string.row_battery_bad : R.string.row_battery_ok),
                stubOptimized ? tonalButton(R.string.action_allow, v -> open(new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", getPackageName(), null))))
                        : okIcon());
        row(healthRows, R.drawable.ic_notifications, getString(R.string.row_notifications),
                getString(notifications ? R.string.row_notifications_on : R.string.row_notifications_off),
                notifications ? okIcon() : tonalButton(R.string.action_allow, v -> requestNotifications()));
        MaterialSwitch keepAlive = new MaterialSwitch(this);
        keepAlive.setChecked(Prefs.keepAlive(this));
        keepAlive.setEnabled(gms != null);
        View keepAliveRow = row(healthRows, R.drawable.ic_bolt, getString(R.string.row_keepalive),
                getString(KeepAliveService.running ? R.string.row_keepalive_on : R.string.row_keepalive_off), keepAlive);
        keepAliveRow.setOnClickListener(v -> keepAlive.toggle());
        keepAlive.setOnCheckedChangeListener((b, on) -> {
            Prefs.setKeepAlive(this, on);
            if (on) KeepAliveService.start(this); else KeepAliveService.stop(this);
            b.postDelayed(this::render, 800);
        });

        gmsRows.removeAllViews();
        List<Detector.GmsCore> all = Detector.findAll(this);
        if (all.isEmpty()) row(gmsRows, R.drawable.ic_warning, getString(R.string.none_installed), null, null);
        for (Detector.GmsCore g : all) {
            int chip = !g.enabled ? R.string.chip_disabled : g.isRealGoogle() ? R.string.chip_google
                    : g.isOfficialMicroGRe() ? R.string.chip_official : R.string.chip_unofficial;
            row(gmsRows, R.drawable.ic_shield, g.label(), g.packageName + " · " + g.versionName, chip(chip,
                    g.isOfficialMicroGRe() || g.isRealGoogle()));
        }

        clientRows.removeAllViews();
        PackageManager pm = getPackageManager();
        for (String pkg : Prefs.clients(this)) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                View r = row(clientRows, 0, pm.getApplicationLabel(ai).toString(), pkg, null);
                ImageView icon = r.findViewById(R.id.leading);
                icon.setImageTintList(null);
                icon.setImageDrawable(pm.getApplicationIcon(ai));
                r.setOnClickListener(v -> open(pm.getLaunchIntentForPackage(pkg)));
            } catch (PackageManager.NameNotFoundException e) {
                // Uninstalled since it registered, or not visible: still list it.
                row(clientRows, R.drawable.ic_shield, pkg, null, null);
            }
        }
        if (clientRows.getChildCount() == 0) row(clientRows, R.drawable.ic_check_circle, getString(R.string.no_clients), null, null);
    }

    private void renderStatus(Detector.GmsCore gms, boolean needsAttention) {
        MaterialCardView card = findViewById(R.id.status_card);
        ImageView icon = findViewById(R.id.status_icon);
        TextView title = findViewById(R.id.status_title);
        TextView detail = findViewById(R.id.status_detail);
        MaterialButton primary = findViewById(R.id.primary_action);
        MaterialButton secondary = findViewById(R.id.secondary_action);

        boolean ok = gms != null && !needsAttention;
        int container = color(ok ? com.google.android.material.R.attr.colorPrimaryContainer
                : com.google.android.material.R.attr.colorErrorContainer);
        int onContainer = color(ok ? com.google.android.material.R.attr.colorOnPrimaryContainer
                : com.google.android.material.R.attr.colorOnErrorContainer);
        card.setCardBackgroundColor(container);
        icon.setImageResource(ok ? R.drawable.ic_check_circle : R.drawable.ic_warning);
        icon.setImageTintList(ColorStateList.valueOf(onContainer));
        title.setTextColor(onContainer);
        detail.setTextColor(onContainer);

        if (gms == null) {
            title.setText(R.string.status_missing);
            detail.setText(R.string.status_missing_detail);
        } else if (needsAttention) {
            title.setText(R.string.status_attention);
            detail.setText(R.string.status_attention_detail);
        } else {
            title.setText(R.string.status_ready);
            detail.setText(getString(R.string.status_ready_detail, gms.label(), gms.versionName));
        }
        primary.setVisibility(gms == null ? View.VISIBLE : View.GONE);
        primary.setOnClickListener(v -> open(new Intent(Intent.ACTION_VIEW, Uri.parse(MICROG_DOWNLOAD))));
        secondary.setVisibility(gms != null ? View.VISIBLE : View.GONE);
        if (gms != null) {
            secondary.setOnClickListener(v -> open(new Intent().setClassName(gms.packageName, "org.microg.gms.ui.SettingsActivity")));
        }
    }

    private View row(LinearLayout parent, @DrawableRes int icon, CharSequence headline, CharSequence supporting, View trailing) {
        if (parent.getChildCount() > 0) {
            MaterialDivider divider = new MaterialDivider(this);
            divider.setDividerInsetStart(dp(56));
            divider.setDividerInsetEnd(dp(24));
            parent.addView(divider);
        }
        View r = LayoutInflater.from(this).inflate(R.layout.item_row, parent, false);
        ImageView leading = r.findViewById(R.id.leading);
        if (icon != 0) leading.setImageResource(icon);
        ((TextView) r.findViewById(R.id.headline)).setText(headline);
        TextView sup = r.findViewById(R.id.supporting);
        sup.setText(supporting);
        sup.setVisibility(supporting == null ? View.GONE : View.VISIBLE);
        if (trailing != null) ((ViewGroup) r.findViewById(R.id.trailing)).addView(trailing);
        parent.addView(r);
        return r;
    }

    private Button tonalButton(int text, View.OnClickListener l) {
        MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    private Chip chip(int text, boolean positive) {
        Chip c = new Chip(this);
        c.setText(text);
        c.setClickable(false);
        c.setChipBackgroundColor(ColorStateList.valueOf(color(positive
                ? com.google.android.material.R.attr.colorSecondaryContainer
                : com.google.android.material.R.attr.colorTertiaryContainer)));
        c.setTextColor(color(positive ? com.google.android.material.R.attr.colorOnSecondaryContainer
                : com.google.android.material.R.attr.colorOnTertiaryContainer));
        c.setChipStrokeWidth(0);
        return c;
    }

    private ImageView okIcon() {
        ImageView v = new ImageView(this);
        v.setImageResource(R.drawable.ic_check_circle);
        v.setImageTintList(ColorStateList.valueOf(color(androidx.appcompat.R.attr.colorPrimary)));
        v.setContentDescription(getString(R.string.row_battery_ok));
        return v;
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 0);
        } else {
            open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        render();
    }

    private int color(@AttrRes int attr) {
        return MaterialColors.getColor(healthRows, attr);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void open(Intent i) {
        if (i == null) return;
        try {
            startActivity(i);
        } catch (Exception e) {
            com.google.android.material.snackbar.Snackbar.make(findViewById(R.id.scroll),
                    String.valueOf(e.getMessage()), com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
        }
    }
}

package app.morphe.extension.microg;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Material 3 basic dialog drawn without any library, so it looks the same in every patched app
 * whatever theme or toolkit that app uses, and matches the GmsCompat-Stub app.
 * Colors: the system's Material You tonal palettes on Android 12+, the M3 baseline scheme before.
 * Spec: 28dp corners, 24dp padding, headline small title, body medium supporting text,
 * end-aligned text buttons, 280-560dp width.
 */
final class M3Dialog {
    interface Action {
        void run();
    }

    private M3Dialog() {
    }

    private static final class Scheme {
        final int surface, onSurface, onSurfaceVariant, primary;

        Scheme(int surface, int onSurface, int onSurfaceVariant, int primary) {
            this.surface = surface;
            this.onSurface = onSurface;
            this.onSurfaceVariant = onSurfaceVariant;
            this.primary = primary;
        }
    }

    private static Scheme scheme(Context ctx) {
        boolean dark = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (Build.VERSION.SDK_INT >= 31) {
            // surfaceContainerHigh ≈ N92 / N17, primary A40 / A80, onSurface N10 / N90, onSurfaceVariant NV30 / NV80.
            return dark
                    ? new Scheme(c(ctx, android.R.color.system_neutral1_800), c(ctx, android.R.color.system_neutral1_100),
                    c(ctx, android.R.color.system_neutral2_200), c(ctx, android.R.color.system_accent1_200))
                    : new Scheme(c(ctx, android.R.color.system_neutral1_50), c(ctx, android.R.color.system_neutral1_900),
                    c(ctx, android.R.color.system_neutral2_700), c(ctx, android.R.color.system_accent1_600));
        }
        return dark
                ? new Scheme(0xFF2B2930, 0xFFE6E0E9, 0xFFCAC4D0, 0xFFD0BCFF)
                : new Scheme(0xFFECE6F0, 0xFF1D1B20, 0xFF49454F, 0xFF65558F);
    }

    private static int c(Context ctx, int id) {
        return ctx.getColor(id);
    }

    static void show(Activity activity, String title, String message,
                     String confirm, Action onConfirm,
                     String dismiss,
                     String neutral, Action onNeutral) {
        Scheme s = scheme(activity);
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(activity, 24);
        root.setPadding(p, p, p, dp(activity, 18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(s.surface);
        bg.setCornerRadius(dp(activity, 28));
        root.setBackground(bg);

        TextView titleView = text(activity, title, 24, s.onSurface, false);
        root.addView(titleView);

        TextView body = text(activity, message, 14, s.onSurfaceVariant, false);
        body.setLineSpacing(0f, 1.43f); // 20sp line height
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(activity, 16);
        root.addView(body, bodyLp);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsLp.topMargin = dp(activity, 24);
        root.addView(buttons, buttonsLp);

        if (neutral != null) {
            buttons.addView(button(activity, s, neutral, () -> {
                dialog.dismiss();
                if (onNeutral != null) onNeutral.run();
            }));
            // Neutral action sits at the start, the others at the end.
            buttons.addView(new android.view.View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        if (dismiss != null) buttons.addView(button(activity, s, dismiss, dialog::dismiss));
        buttons.addView(button(activity, s, confirm, () -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        }));

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screen = activity.getResources().getDisplayMetrics().widthPixels;
            int width = Math.max(dp(activity, 280), Math.min(dp(activity, 560), screen - dp(activity, 48)));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private static TextView text(Context ctx, String value, int sp, int color, boolean medium) {
        TextView t = new TextView(ctx);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private static TextView button(Context ctx, Scheme s, String label, Action action) {
        TextView b = text(ctx, label, 14, s.primary, true);
        b.setLetterSpacing(0.00714f);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(ctx, 40));
        b.setMinWidth(dp(ctx, 48));
        b.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(ctx, 20));
        int pressed = (s.primary & 0x00FFFFFF) | 0x1F000000; // 12% state layer
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(pressed), null, mask));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 40));
        lp.setMarginStart(dp(ctx, 8));
        b.setLayoutParams(lp);
        return b;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}

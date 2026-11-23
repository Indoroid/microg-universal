/*
 * SPDX-FileCopyrightText: 2026 microg-universal
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.tools.selfcheck;

import static org.microg.tools.selfcheck.SelfCheckGroup.Result.Positive;
import static org.microg.tools.selfcheck.SelfCheckGroup.Result.Unknown;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;

import com.google.android.gms.BuildConfig;
import com.google.android.gms.R;

import org.microg.tools.ui.AbstractSelfCheckFragment.ChipInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists apps patched for this microG build. Detection is generic: any app carrying the
 * SPOOFED_PACKAGE_NAME meta-data written by the universal microG patch, whatever patcher made it.
 */
public class InstalledPatcherChecks implements SelfCheckGroup {
    private static final String META_SPOOFED = BuildConfig.BASE_PACKAGE_NAME + ".android.gms.SPOOFED_PACKAGE_NAME";

    @Override
    public String getGroupName(Context context) {
        return context.getString(R.string.self_check_cat_patched_apps);
    }

    @Override
    public void doChecks(Context context, ResultCollector collector) {
        PackageManager pm = context.getPackageManager();
        List<ChipInfo> chips = new ArrayList<>();
        for (ApplicationInfo app : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            Bundle meta = app.metaData;
            if (meta == null || meta.getString(META_SPOOFED) == null) continue;
            String pkg = app.packageName;
            Drawable icon = app.loadIcon(pm);
            View.OnClickListener open = v -> {
                Intent intent = pm.getLaunchIntentForPackage(pkg);
                if (intent != null) context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            };
            chips.add(new ChipInfo(app.loadLabel(pm).toString(), icon, open));
        }
        String title = context.getString(R.string.self_check_patched_app_installed, chips.size());
        String resolution = chips.isEmpty() ? context.getString(R.string.self_check_resolution_patched_app_installed) : "";
        collector.addResult(title, chips.isEmpty() ? Unknown : Positive, resolution, false, chips, null);
    }
}

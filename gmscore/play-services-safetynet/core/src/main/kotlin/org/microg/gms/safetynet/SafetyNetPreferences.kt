/*
 * SPDX-FileCopyrightText: 2021 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.safetynet

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import org.microg.gms.settings.SettingsContract
import org.microg.gms.settings.SettingsContract.SafetyNet.ENABLED

object SafetyNetPreferences {
    private fun <T> getSettings(context: Context, projection: String, def: T, f: (Cursor) -> T): T {
        return try {
            SettingsContract.getSettings(context, SettingsContract.SafetyNet.getContentUri(context), arrayOf(projection), f)
        } catch (e: Exception) {
            def
        }
    }

    private fun setSettings(context: Context, f: ContentValues.() -> Unit) =
            SettingsContract.setSettings(context, SettingsContract.SafetyNet.getContentUri(context), f)

    @JvmStatic
    fun isEnabled(context: Context): Boolean = getSettings(context, ENABLED, false) { it.getInt(0) != 0 }

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) = setSettings(context) { put(ENABLED, enabled) }
}

/** Per-app SafetyNet access (the app screen's "Allow requests" switch); apps are allowed unless denied. */
object SafetyNetAppAccess {
    private const val PREFS = "safetynet_app_access"
    private const val KEY_DENIED = "denied"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun isAllowed(context: Context, packageName: String?): Boolean =
        packageName == null || packageName !in prefs(context).getStringSet(KEY_DENIED, emptySet()).orEmpty()

    @JvmStatic
    fun setAllowed(context: Context, packageName: String, allowed: Boolean) {
        val denied = prefs(context).getStringSet(KEY_DENIED, emptySet()).orEmpty().toMutableSet()
        if (allowed) denied -= packageName else denied += packageName
        prefs(context).edit().putStringSet(KEY_DENIED, denied).apply()
    }

    /** SafetyNet as a whole is on and this app may use it. */
    @JvmStatic
    fun mayUse(context: Context, packageName: String?) = SafetyNetPreferences.isEnabled(context) && isAllowed(context, packageName)
}

/*
 * SPDX-FileCopyrightText: 2020, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import android.os.Bundle
import android.text.format.DateUtils
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.microg.gms.gcm.GcmDatabase

class PushNotificationAllAppsFragment : PreferenceFragmentCompat() {
    private lateinit var database: GcmDatabase
    private lateinit var registered: PreferenceCategory
    private lateinit var unregistered: PreferenceCategory
    private lateinit var registeredNone: Preference
    private lateinit var unregisteredNone: Preference
    private lateinit var progress: Preference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = GcmDatabase(context)
    }

    override fun onResume() {
        super.onResume()
        updateContent()
    }

    override fun onPause() {
        super.onPause()
        database.close()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_push_notifications_all_apps)
        registered = preferenceScreen.findPreference("prefcat_push_apps_registered") ?: registered
        unregistered = preferenceScreen.findPreference("prefcat_push_apps_unregistered") ?: unregistered
        registeredNone = preferenceScreen.findPreference("pref_push_apps_registered_none") ?: registeredNone
        unregisteredNone = preferenceScreen.findPreference("pref_push_apps_unregistered_none") ?: unregisteredNone
        progress = preferenceScreen.findPreference("pref_push_apps_all_progress") ?: progress
    }

    private fun updateContent() {
        val context = requireContext()
        lifecycleScope.launchWhenResumed {
            val apps = withContext(Dispatchers.IO) {
                val known = database.appList.associateBy { it.packageName }
                // Every installed app able to receive push (declares the C2DM receive permission) is listed,
                // not only those that already registered.
                val pushCapable = pushCapablePackages(context)
                val res = (known.keys + pushCapable).distinct().map { packageName ->
                    val app = known[packageName]
                    val pref = AppIconPreference(context)
                    pref.packageName = packageName
                    pref.summary = when {
                        app != null && app.lastMessageTimestamp > 0 -> getString(R.string.gcm_last_message_at, DateUtils.getRelativeTimeSpanString(app.lastMessageTimestamp))
                        else -> null
                    }
                    pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                        findNavController().navigate(requireContext(), R.id.openGcmAppDetailsFromAll, bundleOf(
                                "package" to packageName
                        ))
                        true
                    }
                    pref.key = "pref_push_app_" + packageName
                    pref to (database.getRegistrationsByApp(packageName))
                }.sortedBy {
                    it.first.title.toString().lowercase()
                }.mapIndexed { idx, pair ->
                    pair.first.order = idx
                    pair
                }
                database.close()
                res
            }
            registered.removeAll()
            unregistered.removeAll()

            var hadRegistered = false
            var hadUnregistered = false

            val registeredList = mutableListOf<Preference>()
            val unregisteredList = mutableListOf<Preference>()

            for ((pref, registrations) in apps) {
                if (registrations.isEmpty()) {
                    unregisteredList.add(pref)
                    hadUnregistered = true
                } else {
                    registeredList.add(pref)
                    hadRegistered = true
                }
            }

            if (!hadRegistered) registeredList.add(registeredNone)
            if (!hadUnregistered) unregisteredList.add(unregisteredNone)

            registeredList.forEachIndexed { index, pref ->
                pref.layoutResource = chooseLayoutForPosition(index, registeredList.size)
                registered.addPreference(pref)
            }

            unregisteredList.forEachIndexed { index, pref ->
                pref.layoutResource = chooseLayoutForPosition(index, unregisteredList.size)
                unregistered.addPreference(pref)
            }

            registered.isVisible = true
            unregistered.isVisible = true
            progress.isVisible = false
        }
    }

    private fun chooseLayoutForPosition(index: Int, total: Int): Int {
        return when {
            total <= 1 -> R.layout.preference_material_secondary_single
            total == 2 -> if (index == 0) {
                R.layout.preference_material_secondary_top
            } else {
                R.layout.preference_material_secondary_bottom
            }

            else -> when (index) {
                0 -> R.layout.preference_material_secondary_top
                total - 1 -> R.layout.preference_material_secondary_bottom
                else -> R.layout.preference_material_secondary_middle
            }
        }
    }
}

/** Installed apps that request a C2DM/FCM receive permission (Google's or this microG build's). */
internal fun pushCapablePackages(context: android.content.Context): List<String> {
    val pm = context.packageManager
    val permissions = setOf("com.google.android.c2dm.permission.RECEIVE", org.microg.gms.common.Constants.GMS_PACKAGE_NAME.removeSuffix(".gms") + ".c2dm.permission.RECEIVE")
    @Suppress("DEPRECATION")
    return runCatching { pm.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS) }.getOrDefault(emptyList())
        .filter { info -> info.packageName != context.packageName && info.requestedPermissions?.any { it in permissions } == true }
        .map { it.packageName }
}

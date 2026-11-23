/*
 * SPDX-FileCopyrightText: 2026 microg-universal
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.safetynet

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build.VERSION.SDK_INT
import android.util.Log
import com.google.android.gms.safetynet.HarmfulAppsData
import java.io.File
import java.security.MessageDigest
import java.util.UUID

private const val TAG = "GmsVerifyApps"
private const val PREFS = "safetynet_verify_apps"
private const val KEY_ENABLED = "verify_apps_enabled"
private const val KEY_SHARED_UUID = "snet_shared_uuid"
private const val KEY_LAST_SCAN = "last_scan"
private const val KEY_LAST_HARMFUL = "last_harmful_scan"
private const val KEY_HARMFUL = "harmful"
private const val DIGEST_PREFIX = "digest:"
const val SHARED_UUID_UNSET = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

/** Threat id SafetyNet reports for harmful apps (HarmfulAppsData.apkCategory). */
private const val CATEGORY_PHA = 4

/**
 * "Verify apps" (Play Protect) state and scan.
 *
 * Verify Apps checks each installed APK by looking up the Safe Browsing expression
 * `safe.safebrowsing.google.com/<sha256 hex>/` in the potentially-harmful-application list; this does
 * the same on demand, caching APK digests per (package, lastUpdateTime) so a rescan only hashes
 * changed APKs.
 */
object VerifyApps {
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    /** Stable random id shared with Google apps (one per device until reset). */
    @Synchronized
    fun sharedUuid(context: Context): String {
        val stored = prefs(context).getString(KEY_SHARED_UUID, null)
        if (!stored.isNullOrEmpty() && stored != SHARED_UUID_UNSET) return stored
        return UUID.randomUUID().toString().also { prefs(context).edit().putString(KEY_SHARED_UUID, it).apply() }
    }

    fun lastScanTime(context: Context) = prefs(context).getLong(KEY_LAST_SCAN, 0)

    /** Hours since the last scan that found something, or -1. */
    fun hoursSinceLastHarmful(context: Context): Int {
        val t = prefs(context).getLong(KEY_LAST_HARMFUL, 0)
        return if (t == 0L) -1 else ((System.currentTimeMillis() - t) / 3_600_000).toInt()
    }

    fun lastResult(context: Context): List<HarmfulAppsData> =
        prefs(context).getStringSet(KEY_HARMFUL, emptySet()).orEmpty().mapNotNull { entry ->
            val (pkg, hex) = entry.split('|').takeIf { it.size == 2 } ?: return@mapNotNull null
            HarmfulAppsData(pkg, hex.hexToBytes(), CATEGORY_PHA)
        }

    fun isHarmful(context: Context, packageName: String, digest: ByteArray?): Boolean =
        lastResult(context).any { it.apkPackageName == packageName && (digest == null || it.apkSha256.contentEquals(digest)) }

    /** Scans user-installed apps; network errors keep the previous result. */
    @Synchronized
    fun scan(context: Context, apiKey: String): List<HarmfulAppsData> {
        val p = prefs(context)
        val packages = installedUserPackages(context)
        val harmful = mutableListOf<HarmfulAppsData>()
        val editor = p.edit()
        for (info in packages) {
            val apk = info.applicationInfo?.sourceDir ?: continue
            val cacheKey = DIGEST_PREFIX + info.packageName + "@" + info.lastUpdateTime
            val hex = p.getString(cacheKey, null) ?: runCatching { File(apk).sha256().toHex() }.getOrNull()?.also { editor.putString(cacheKey, it) }
            ?: continue
            try {
                val hit = SafeBrowsing.lookup(apiKey, "safe.safebrowsing.google.com/$hex/", listOf(CATEGORY_PHA))
                if (hit.isNotEmpty()) harmful += HarmfulAppsData(info.packageName, hex.hexToBytes(), CATEGORY_PHA)
            } catch (e: Exception) {
                Log.w(TAG, "Scan aborted: ${e.message}")
                editor.apply()
                return lastResult(context)
            }
        }
        val now = System.currentTimeMillis()
        editor.putLong(KEY_LAST_SCAN, now)
        editor.putStringSet(KEY_HARMFUL, harmful.map { it.apkPackageName + "|" + it.apkSha256.toHex() }.toSet())
        if (harmful.isNotEmpty()) editor.putLong(KEY_LAST_HARMFUL, now)
        editor.apply()
        return harmful
    }

    private fun installedUserPackages(context: Context): List<PackageInfo> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val all = if (SDK_INT >= 33) pm.getInstalledPackages(android.content.pm.PackageManager.PackageInfoFlags.of(0)) else pm.getInstalledPackages(0)
        return all.filter { it.applicationInfo?.let { a -> a.flags and ApplicationInfo.FLAG_SYSTEM == 0 } == true && it.packageName != context.packageName }
    }

    private fun File.sha256(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

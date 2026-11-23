/*
 * SPDX-FileCopyrightText: 2026 microg-universal
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.patcher

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkMerger
import app.morphe.patcher.apk.ApkUtils
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patches.all.misc.microg.MICROG_CLONE_DEFAULT_SUFFIX
import app.morphe.patches.all.misc.microg.MICROG_DEFAULT_VENDOR
import app.morphe.patches.all.misc.microg.MicroGSupportSettings
import app.morphe.patches.all.misc.microg.microGClonePatch
import app.morphe.patches.all.misc.microg.universalMicroGSupportPatch
import com.aurora.store.data.room.download.Download
import com.aurora.store.util.PathUtil
import com.aurora.store.util.Preferences
import com.aurora.store.util.Preferences.PREFERENCE_MICROG_CLONE
import com.aurora.store.util.Preferences.PREFERENCE_MICROG_PATCH
import java.io.File

/**
 * Patches a finished download for microG before it is handed to an installer: merges base and
 * config splits into one APK, applies the universal microG patch (and the clone patch), signs it
 * with this store's persistent key and leaves only the patched APK in the download directory.
 */
object MicroGPatcher {

    private const val TAG = "MicroGPatcher"
    private const val MARKER = ".microg-patched"
    private const val KEY_ALIAS = "microg-universal"
    private const val KEY_PASSWORD = "microg-universal"
    private const val META_SPOOFED = "$MICROG_DEFAULT_VENDOR.android.gms.SPOOFED_PACKAGE_NAME"

    /** Packages never patched: microG itself and its companions. */
    private val EXCLUDED = setOf(
        "$MICROG_DEFAULT_VENDOR.android.gms",
        "$MICROG_DEFAULT_VENDOR.android.gsf",
        "app.morphe.gmscompat",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
    )

    fun isEnabled(context: Context) = Preferences.getBoolean(context, PREFERENCE_MICROG_PATCH, true)

    fun isCloneMode(context: Context) = Preferences.getBoolean(context, PREFERENCE_MICROG_CLONE, true)

    fun shouldPatch(context: Context, download: Download): Boolean =
        isEnabled(context) && download.packageName !in EXCLUDED &&
            (download.requiresGMS || download.packageName.startsWith("com.google."))

    /** Package the patched APK installs as, or [packageName] when it was not patched. */
    fun installedPackageName(context: Context, packageName: String, versionCode: Long): String =
        File(PathUtil.getAppDownloadDir(context, packageName, versionCode), MARKER)
            .takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null } ?: packageName

    /** Play package an installed (possibly cloned) app came from, via the patch's meta-data. */
    fun playPackageName(context: Context, installedPackage: String): String = runCatching {
        context.packageManager.getApplicationInfo(installedPackage, PackageManager.GET_META_DATA)
            .metaData?.getString(META_SPOOFED)
    }.getOrNull() ?: installedPackage

    /** Installed package that tracks [playPackage]: its clone when one is installed. */
    fun installedFor(context: Context, playPackage: String): String? {
        val pm = context.packageManager
        val clone = playPackage + MICROG_CLONE_DEFAULT_SUFFIX
        return listOf(clone, playPackage).firstOrNull {
            runCatching { pm.getPackageInfo(it, 0) }.isSuccess
        }
    }

    /** A patched, signed APK and the package it installs as. */
    data class PatchedApk(val file: File, val packageName: String)

    /**
     * Patches the download in place.
     * @return the package name the patched APK installs as.
     */
    fun patch(context: Context, download: Download, clone: Boolean = isCloneMode(context)): String {
        val dir = PathUtil.getAppDownloadDir(context, download.packageName, download.versionCode)
        File(dir, MARKER).takeIf { it.isFile }?.let { return it.readText().trim() }

        val apks = dir.listFiles { f -> f.name.endsWith(".apk") }.orEmpty().sortedBy { it.name }
        require(apks.isNotEmpty()) { "No APKs in $dir" }
        val work = File(context.cacheDir, "microg-patch/${download.packageName}")
        try {
            val patched = patchApks(context, apks, work, clone)
            apks.forEach { it.delete() }
            patched.file.copyTo(File(dir, "base.apk"), overwrite = true)
            File(dir, MARKER).writeText(patched.packageName)
            return patched.packageName
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Merges [apks] (base + splits) when needed, applies the universal microG patch (and the clone
     * patch) and signs the result with this store's key. Output lives in [work].
     */
    fun patchApks(context: Context, apks: List<File>, work: File, clone: Boolean): PatchedApk {
        work.deleteRecursively(); work.mkdirs()
        val input = if (apks.size == 1) apks.single() else File(work, "merged.apk").also { merged ->
            val splits = File(work, "splits").apply { mkdirs() }
            apks.forEach { it.copyTo(File(splits, it.name), overwrite = true) }
            Log.i(TAG, "Merging ${apks.size} splits")
            ApkMerger().merge(inputFile = splits, outputFile = merged)
            splits.deleteRecursively()
        }

        val output = File(work, "patched.apk")
        val patches = buildSet {
            add(universalMicroGSupportPatch(MicroGSupportSettings()))
            if (clone) add(microGClonePatch())
        }
        val original: String
        Patcher(PatcherConfig(input, File(work, "tmp"))).use { patcher ->
            original = patcher.context.packageMetadata.packageName
            patcher += patches
            kotlinx.coroutines.runBlocking {
                patcher().collect { result ->
                    result.exception?.let { throw IllegalStateException("${result.patch} failed", it) }
                }
            }
            input.copyTo(output, overwrite = true)
            patcher.get().applyTo(output)
        }
        File(work, "tmp").deleteRecursively()

        ApkUtils.signApk(
            output, output, KEY_ALIAS,
            ApkUtils.KeyStoreDetails(File(context.filesDir, "microg-patch.keystore"), null, KEY_ALIAS, KEY_PASSWORD)
        )
        val target = if (clone) original + MICROG_CLONE_DEFAULT_SUFFIX else original
        Log.i(TAG, "Patched $original -> $target")
        return PatchedApk(output, target)
    }
}

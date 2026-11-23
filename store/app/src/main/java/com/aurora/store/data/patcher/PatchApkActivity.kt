/*
 * SPDX-FileCopyrightText: 2026 microg-universal
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.patcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.aurora.store.BuildConfig
import com.aurora.store.R
import com.aurora.store.compose.theme.AuroraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Patches an APK the user already has (opened or shared from a file manager) for microG and hands
 * it to the system installer. Same pipeline as store installs ([MicroGPatcher.patchApks]).
 */
class PatchApkActivity : ComponentActivity() {

    private sealed interface State {
        data object Ask : State
        data object Working : State
        data class Failed(val message: String) : State
    }

    private var state by mutableStateOf<State>(State.Ask)
    private var clone by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clone = MicroGPatcher.isCloneMode(this)
        val uri = sourceUri() ?: return finish()
        // Scripted use (tests, adb): skip the dialog.
        if (intent.hasExtra(EXTRA_CLONE)) {
            clone = intent.getBooleanExtra(EXTRA_CLONE, clone)
            start(uri)
        }
        setContent {
            AuroraTheme {
                when (val s = state) {
                    State.Ask -> AlertDialog(
                        onDismissRequest = ::finish,
                        title = { Text(stringResource(R.string.patch_apk_title)) },
                        text = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.pref_microg_clone_title), Modifier.weight(1f))
                                Switch(checked = clone, onCheckedChange = { clone = it })
                            }
                        },
                        confirmButton = { TextButton(onClick = { start(uri) }) { Text(stringResource(R.string.patch_apk_action)) } },
                        dismissButton = { TextButton(onClick = ::finish) { Text(stringResource(android.R.string.cancel)) } }
                    )
                    State.Working -> AlertDialog(
                        onDismissRequest = {},
                        title = { Text(stringResource(R.string.installer_status_patching)) },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                CircularProgressIndicator(Modifier.padding(8.dp))
                                Text(stringResource(R.string.patch_apk_working))
                            }
                        },
                        confirmButton = {}
                    )
                    is State.Failed -> AlertDialog(
                        onDismissRequest = ::finish,
                        title = { Text(stringResource(R.string.installer_status_patch_failed)) },
                        text = { Text(s.message) },
                        confirmButton = { TextButton(onClick = ::finish) { Text(stringResource(android.R.string.ok)) } }
                    )
                }
            }
        }
    }

    private fun sourceUri(): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        else -> intent.data
    }

    private fun start(uri: Uri) {
        if (state == State.Working) return
        state = State.Working
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val work = File(cacheDir, "microg-local")
                    val input = File(cacheDir, "microg-local-input.apk")
                    contentResolver.openInputStream(uri)!!.use { i -> input.outputStream().use { i.copyTo(it) } }
                    val patched = MicroGPatcher.patchApks(this@PatchApkActivity, listOf(input), work, clone)
                    input.delete()
                    val out = File(cacheDir, "microg-patched/${patched.packageName}.apk").apply { parentFile?.mkdirs() }
                    patched.file.copyTo(out, overwrite = true)
                    work.deleteRecursively()
                    out
                }
            }
            result.onSuccess { apk ->
                val apkUri = FileProvider.getUriForFile(this@PatchApkActivity, "${BuildConfig.APPLICATION_ID}.fileProvider", apk)
                @Suppress("DEPRECATION")
                startActivity(
                    Intent(Intent.ACTION_INSTALL_PACKAGE, apkUri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                )
                finish()
            }.onFailure {
                Log.e(TAG, "Patching failed", it)
                state = State.Failed(it.message ?: it.javaClass.simpleName)
            }
        }
    }

    companion object {
        private const val TAG = "PatchApkActivity"
        const val EXTRA_CLONE = "clone"
    }
}

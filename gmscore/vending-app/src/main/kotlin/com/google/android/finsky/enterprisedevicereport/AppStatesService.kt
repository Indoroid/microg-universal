/*
 * SPDX-FileCopyrightText: 2026 microg-universal
 * SPDX-License-Identifier: Apache-2.0
 */
package com.google.android.finsky.enterprisedevicereport

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log

private const val TAG = "AppStatesService"
private const val KEY_STATES = "androidx.enterprise.feedback.APP_STATES"
private const val KEY_STATE_KEY = "androidx.enterprise.feedback.APP_STATE_KEY"

/**
 * androidx.enterprise.feedback keyed app states (`androidx.enterprise.feedback.action.APP_STATES`).
 * Same Messenger protocol as the Play Store's KeyedAppStatesService: what 1 = states, 2 = immediate
 * states, no reply. The Play Store forwards states to the EMM only on a managed device; a rootless store is
 * never the device's managed store, so states are validated and dropped, as the Play Store does when the
 * device is not managed.
 */
class AppStatesService : Service() {
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != 1 && msg.what != 2) return super.handleMessage(msg)
            val states = runCatching {
                @Suppress("DEPRECATION")
                (msg.obj as? Bundle)?.getParcelableArrayList<Bundle>(KEY_STATES)
            }.getOrNull().orEmpty().filter { it.getString(KEY_STATE_KEY) != null }
            Log.i(TAG, "${states.size} app states from uid ${msg.sendingUid}: device is not managed, ignoring")
        }
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder
}

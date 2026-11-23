/*
 * SPDX-FileCopyrightText: 2026 microg-universal
 * SPDX-License-Identifier: Apache-2.0
 */
package com.google.android.finsky.dataprojectionapiservice

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log

private const val TAG = "GroupingApiService"
private const val DESCRIPTOR = "com.google.android.play.core.grouping.protocol.IGroupingApiService"
private const val CALLBACK = "com.google.android.play.core.grouping.protocol.IGroupingApiServiceCallback"

/** DataProjectionApiException code the Play Store uses for apps not enrolled in the Grouping API. */
private const val ERROR_APP_NOT_ALLOWED = 12

/**
 * Play Grouping API (`com.google.android.play.core.grouping.BIND_GROUPING_API_SERVICE`).
 * Binder protocol: 2 = requestGroupingApiToken(Bundle, callback); the reply
 * is callback code 2 with a Bundle, holding the token or `error`. Tokens are minted by Google for
 * enrolled apps, which needs Play's server session, so every caller gets the not-allowed error that
 * the Play Store returns for apps outside the program.
 */
class DataProjectionApiService : Service() {
    override fun onBind(intent: Intent): IBinder = object : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            if (code != 2) return false
            data.enforceInterface(DESCRIPTOR)
            if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data)
            val cb = data.readStrongBinder() ?: return true
            val p = Parcel.obtain()
            try {
                p.writeInterfaceToken(CALLBACK)
                p.writeInt(1)
                Bundle().apply { putInt("error", ERROR_APP_NOT_ALLOWED) }.writeToParcel(p, 0)
                cb.transact(2, p, null, FLAG_ONEWAY)
            } catch (e: Exception) {
                Log.w(TAG, "callback failed", e)
            } finally {
                p.recycle()
            }
            return true
        }
    }
}

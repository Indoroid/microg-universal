/*
 * SPDX-FileCopyrightText: 2026 microg-universal
 * SPDX-License-Identifier: Apache-2.0
 */
package com.google.android.finsky.appcontentservice.engage

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log

private const val TAG = "AppEngageServiceV1"
private const val DESCRIPTOR = "com.google.android.play.engage.protocol.IAppEngageService"
private const val PUBLISH_CALLBACK = "com.google.android.play.engage.protocol.IAppEngageServicePublishClustersCallback"
private const val DELETE_CALLBACK = "com.google.android.play.engage.protocol.IAppEngageServiceDeleteClustersCallback"
private const val AVAILABLE_CALLBACK = "com.google.android.play.engage.protocol.IAppEngageServiceAvailableCallback"
private const val ERROR_MESSAGE = "EngageService is not available for old SDK versions."

/**
 * Engage v1 (`com.google.android.play.engage.BIND_APP_ENGAGE_SERVICE`), used by pre-1.x Engage SDKs.
 * The Play Store answers every call with service_error_code 2, so SDKs fall back to v2; this
 * does the same, including the callback transaction codes (isServiceAvailable replies on code 2).
 */
class AppEngageService : Service() {
    override fun onBind(intent: Intent): IBinder = object : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            val (callbackDescriptor, callbackCode) = when (code) {
                1 -> PUBLISH_CALLBACK to 1
                2 -> DELETE_CALLBACK to 2
                3 -> AVAILABLE_CALLBACK to 2
                else -> return false
            }
            data.enforceInterface(DESCRIPTOR)
            val cb = data.readStrongBinder() ?: return true
            Log.d(TAG, "call $code: not available for old SDK versions")
            val result = Bundle().apply {
                putInt("service_error_code", 2)
                putString("service_error_message", ERROR_MESSAGE)
            }
            val p = Parcel.obtain()
            try {
                p.writeInterfaceToken(callbackDescriptor)
                p.writeInt(1)
                result.writeToParcel(p, 0)
                cb.transact(callbackCode, p, null, FLAG_ONEWAY)
            } catch (e: Exception) {
                Log.w(TAG, "callback failed", e)
            } finally {
                p.recycle()
            }
            return true
        }
    }
}

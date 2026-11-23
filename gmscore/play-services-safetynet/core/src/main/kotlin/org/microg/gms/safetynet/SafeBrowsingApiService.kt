/*
 * SPDX-FileCopyrightText: 2026 microg-universal
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.safetynet

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.microg.gms.BaseService
import org.microg.gms.common.GmsService
import org.microg.gms.common.PackageUtils

private const val TAG = "GmsSafeBrowsingApi"
private const val DESCRIPTOR = "com.google.android.gms.safebrowsing.internal.ISafeBrowsingService"
private const val CALLBACKS = "com.google.android.gms.safebrowsing.internal.ISafeBrowsingCallbacks"
private const val THREAT_TYPES_CALLBACKS = "com.google.android.gms.safebrowsing.internal.ISupportedThreatTypesCallbacks"
private const val DEFAULT_API_KEY = "AIzaSyDqVnJBjE5ymo--oBJt3On7HQx9xNm1RHA"

/** Threat types answerable through the public Safe Browsing lists: potentially harmful app, social engineering. */
private val SUPPORTED = intArrayOf(4, 5)

/**
 * The Safe Browsing API Chrome and WebView use (service 354, `SafeBrowsingService.START`), backed by the
 * same hash-prefix client as SafetyNet lookupUri. Binder protocol:
 * 2 = lookupUri(SafeBrowsingRequest{2 url, 3 List<Integer> threatTypes, 4 protocol}, String, callbacks, boolean),
 * 3 = getSupportedThreatTypes(callbacks). Results: SafeBrowsingResponse{2 status, 3 threatType, 4 int[] attributes}.
 */
class SafeBrowsingApiService : BaseService(TAG, GmsService.SAFE_BROWSING) {
    override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
        val packageName = PackageUtils.getAndCheckCallingPackage(this, request.packageName)
        callback.onPostInitComplete(ConnectionResult.SUCCESS, Impl(packageName), null)
    }

    private inner class Impl(private val packageName: String?) : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            if (code != 2 && code != 3) return false
            data.enforceInterface(DESCRIPTOR)
            if (code == 3) {
                val cb = data.readStrongBinder()
                cb?.let { sendTypes(it) }
            } else {
                val request = if (data.readInt() != 0) readRequest(data) else null
                val apiKey = data.readString()
                val cb = data.readStrongBinder()
                if (cb != null) lookup(request, apiKey, cb)
            }
            reply?.writeNoException()
            return true
        }

        private fun lookup(request: Request?, apiKey: String?, cb: IBinder) {
            if (request?.url.isNullOrEmpty()) return sendError(cb, CommonStatusCodes.DEVELOPER_ERROR)
            val types = request!!.threatTypes.filter { it in SUPPORTED }
            if (!SafetyNetAppAccess.mayUse(this@SafeBrowsingApiService, packageName) || types.isEmpty()) return sendResponse(cb, 0, 0)
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { SafeBrowsing.lookup(apiKey?.takeIf { it.startsWith("AIza") } ?: DEFAULT_API_KEY, request.url!!, types) }
                    .onSuccess { sendResponse(cb, 0, it.firstOrNull() ?: 0) }
                    .onFailure {
                        Log.w(TAG, "lookup for $packageName failed: ${it.message}")
                        sendError(cb, CommonStatusCodes.NETWORK_ERROR)
                    }
            }
        }
    }

    private class Request(val url: String?, val threatTypes: List<Int>)

    private fun readRequest(p: Parcel): Request {
        val (end) = beginObject(p)
        var url: String? = null
        val types = mutableListOf<Int>()
        while (p.dataPosition() < end) {
            val header = p.readInt()
            val id = header and 0xffff
            val size = if (header and -0x10000 != -0x10000) header ushr 16 else p.readInt()
            val fieldEnd = p.dataPosition() + size
            when (id) {
                2 -> url = p.readString()
                3 -> repeat(p.readInt()) { types += p.readInt() }
            }
            p.setDataPosition(fieldEnd)
        }
        p.setDataPosition(end)
        return Request(url, types)
    }

    private fun beginObject(p: Parcel): IntArray {
        val header = p.readInt()
        val size = if (header and -0x10000 != -0x10000) header ushr 16 else p.readInt()
        return intArrayOf(p.dataPosition() + size)
    }

    private fun Parcel.writeObject(fields: Parcel.() -> Unit) {
        writeInt(-0x10000 or 0x4f45)
        writeInt(0)
        val body = dataPosition()
        fields()
        val end = dataPosition()
        setDataPosition(body - 4); writeInt(end - body); setDataPosition(end)
    }

    private fun Parcel.writeIntField(id: Int, value: Int) { writeInt((4 shl 16) or id); writeInt(value) }

    private fun Parcel.writeIntArrayField(id: Int, value: IntArray) {
        writeInt(-0x10000 or id); writeInt(0)
        val start = dataPosition()
        writeIntArray(value)
        val end = dataPosition()
        setDataPosition(start - 4); writeInt(end - start); setDataPosition(end)
    }

    private fun transact(cb: IBinder, descriptor: String, code: Int, body: Parcel.() -> Unit) {
        val p = Parcel.obtain()
        try {
            p.writeInterfaceToken(descriptor)
            p.body()
            p.writeInt(0) // ApiMetadata: none
            cb.transact(code, p, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.w(TAG, "callback failed", e)
        } finally {
            p.recycle()
        }
    }

    private fun sendResponse(cb: IBinder, status: Int, threatType: Int) = transact(cb, CALLBACKS, 1) {
        writeInt(1)
        writeObject { writeIntField(2, status); writeIntField(3, threatType); writeIntArrayField(4, IntArray(0)) }
    }

    private fun sendError(cb: IBinder, status: Int) = transact(cb, CALLBACKS, 2) { writeInt(status) }

    private fun sendTypes(cb: IBinder) = transact(cb, THREAT_TYPES_CALLBACKS, 2) {
        writeInt(1)
        writeObject { writeIntArrayField(1, if (SafetyNetPreferences.isEnabled(this@SafeBrowsingApiService)) SUPPORTED else IntArray(0)) }
    }
}

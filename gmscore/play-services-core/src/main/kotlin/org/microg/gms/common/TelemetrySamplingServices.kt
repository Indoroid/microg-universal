/*
 * SPDX-FileCopyrightText: 2026 microg-universal
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.common

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.Feature
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import org.microg.gms.BaseService

private const val TAG = "GmsTelemetry"

private const val NOTIFICATION_TELEMETRY_DESCRIPTOR = "com.google.android.gms.common.internal.service.IClientNotificationTelemetryService"
private const val SAMPLING_DESCRIPTOR = "com.google.android.gms.clearcut.sampler.IClearcutLoggerSamplingService"
private const val SAMPLER_CALLBACK_DESCRIPTOR = "com.google.android.gms.clearcut.sampler.IDeviceSamplerCallback"

/** Client notification telemetry (service 449): events are accepted and dropped, nothing leaves the device. */
class ClientNotificationTelemetryService : BaseService(TAG, GmsService.TELEMETRY_NOTIFICATION) {
    override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
        callback.onPostInitCompleteWithConnectionInfo(ConnectionResult.SUCCESS, object : Binder() {
            init { attachInterface(null, NOTIFICATION_TELEMETRY_DESCRIPTOR) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
                // 1 = log(ClientNotificationEvent), one-way.
                if (code != 1) return false
                data.enforceInterface(NOTIFICATION_TELEMETRY_DESCRIPTOR)
                return true
            }
        }, ConnectionInfo().apply { features = arrayOf(Feature("CLIENT_NOTIFICATION_TELEMETRY", 1)) })
    }
}

/**
 * Clearcut logger sampling (service 414). microG never uploads Clearcut logs, so every config is
 * answered "not sampled" with no expiry, i.e. a sampling rate of 0.
 */
class ClearcutLoggerSamplingService : BaseService(TAG, GmsService.CLEARCUT_LOGGER_SAMPLING) {
    override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
        callback.onPostInitComplete(ConnectionResult.SUCCESS, object : Binder() {
            init { attachInterface(null, SAMPLING_DESCRIPTOR) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
                // 2 = getSamplingDecision(SamplerConfigParcelable, IDeviceSamplerCallback)
                if (code != 2) return false
                data.enforceInterface(SAMPLING_DESCRIPTOR)
                if (data.readInt() != 0) skipSafeParcelable(data)
                val cb = data.readStrongBinder() ?: return true
                runCatching { sendDecision(cb) }.onFailure { Log.w(TAG, "sampler callback failed", it) }
                reply?.writeNoException()
                return true
            }
        }, null)
    }

    private fun skipSafeParcelable(data: Parcel) {
        val header = data.readInt()
        val size = if (header and -0x10000 != -0x10000) header ushr 16 else data.readInt()
        data.setDataPosition(data.dataPosition() + size)
    }

    private fun sendDecision(cb: IBinder) {
        val p = Parcel.obtain()
        try {
            p.writeInterfaceToken(SAMPLER_CALLBACK_DESCRIPTOR)
            p.writeInt(1)
            Status.SUCCESS.writeToParcel(p, 0)
            // SamplerDecisionParcelable { 1: boolean sampled = false, 2: long expiry = Long.MAX_VALUE }
            p.writeInt(1)
            p.writeInt(-0x10000 or 0x4f45)
            p.writeInt(0)
            val body = p.dataPosition()
            p.writeInt((4 shl 16) or 1); p.writeInt(0)
            p.writeInt((8 shl 16) or 2); p.writeLong(Long.MAX_VALUE)
            val end = p.dataPosition()
            p.setDataPosition(body - 4); p.writeInt(end - body); p.setDataPosition(end)
            cb.transact(2, p, null, IBinder.FLAG_ONEWAY)
        } finally {
            p.recycle()
        }
    }
}

/*
 * SPDX-FileCopyrightText: 2026 microg-universal
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.time

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.Feature
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.common.internal.safeparcel.SafeParcelWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.microg.gms.BaseService
import org.microg.gms.common.GmsService
import org.microg.gms.common.GoogleNetworkPolicy
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

private const val TAG = "GmsTrustedTime"
private const val DESCRIPTOR = "com.google.android.gms.time.trustedtime.internal.ITrustedTimeService"
private const val SIGNAL_CALLBACK = "com.google.android.gms.time.trustedtime.internal.IOnLatestTimeSignalCallback"
private const val STATE_CALLBACK = "com.google.android.gms.time.trustedtime.internal.IOnRetrieveGlobalStateCallback"
private const val NEW_TIME_SIGNAL = "com.google.android.gms.time.NEW_TIME_SIGNAL"
private const val NTP_HOST = "time.google.com"
private const val NTP_PORT = 123
private const val NTP_TIMEOUT_MS = 5_000
/** A signal is reused for this long before a new SNTP query. */
private const val MAX_SIGNAL_AGE_MS = 60 * 60 * 1000L
/** 1900-01-01 to 1970-01-01 in seconds. */
private const val NTP_EPOCH_OFFSET = 2_208_988_800L

/**
 * Trusted Time (service 326, `play-services-time` TrustedTimeClient). Binder protocol:
 * 1 = retrieveGlobalState(callback), 4 = getLatestTimeSignal(callback);
 * both reply on callback code 1 with (Status, parcelable, ApiMetadata).
 *
 * The time comes from SNTP against time.google.com: TimeSignalResult carries the server
 * instant at the elapsedRealtime tick it was received, with half the round trip as error estimate, so
 * the client can extrapolate trusted time without the device clock. It obeys "Allow connecting to
 * Google servers"; NTP is UDP and can't go through Tor, so with Tor on no query is made either.
 */
class TrustedTimeService : BaseService(TAG, GmsService.TIME) {
    override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
        callback.onPostInitCompleteWithConnectionInfo(ConnectionResult.SUCCESS, Impl(),
            ConnectionInfo().apply { features = arrayOf(Feature("trusted_time", 1)) })
    }

    private inner class Impl : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            if (code != 1 && code != 4) return false
            data.enforceInterface(DESCRIPTOR)
            val cb = data.readStrongBinder()
            if (cb != null) {
                if (code == 1) sendState(cb) else lifecycleScope.launch(Dispatchers.IO) { sendSignal(cb) }
            }
            reply?.writeNoException()
            return true
        }
    }

    private fun sendSignal(cb: IBinder) {
        val signal = if (GoogleNetworkPolicy.isGoogleAllowed(this) && !GoogleNetworkPolicy.isTorEnabled(this)) {
            runCatching { Sntp.latest() }.onFailure { Log.w(TAG, "SNTP failed: ${it.message}") }.getOrNull()
        } else null
        transact(cb, SIGNAL_CALLBACK) {
            if (signal == null) {
                writeStatus(Status(CommonStatusCodes.INTERNAL_ERROR))
                writeInt(0)
            } else {
                writeStatus(Status.SUCCESS)
                writeInt(1)
                writeObject {
                    writeObjectField(1) { writeDuration(signal.errorMillis) }      // estimatedError
                    writeObjectField(2) { writeInstant(signal.serverTimeMillis) }  // currentTime
                    writeObjectField(3) {                                          // acquisitionTicks
                        writeIntField(1, 1)                                        // ticker type: elapsedRealtime millis
                        writeLongField(2, signal.ticks)
                    }
                    writeEmptyListField(4)                                         // past clock adjustments
                    writeEmptyListField(5)                                         // future clock adjustments
                }
            }
        }
    }

    private fun sendState(cb: IBinder) = transact(cb, STATE_CALLBACK) {
        writeStatus(Status.SUCCESS)
        writeInt(1)
        writeObject {
            // 1 Long basicPhysicalTickerErrorRateMicrosPerSecond: unknown here, left out (null) without calibration
            SafeParcelWriter.write(this, 2, NEW_TIME_SIGNAL, false)
            writeIntField(3, 0)
        }
    }

    // ---- SafeParcel helpers ----

    private fun transact(cb: IBinder, descriptor: String, body: Parcel.() -> Unit) {
        val p = Parcel.obtain()
        try {
            p.writeInterfaceToken(descriptor)
            p.body()
            p.writeInt(0) // ApiMetadata: none
            cb.transact(1, p, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.w(TAG, "callback failed", e)
        } finally {
            p.recycle()
        }
    }

    private fun Parcel.writeStatus(status: Status) { writeInt(1); status.writeToParcel(this, 0) }
    private fun Parcel.writeObject(fields: Parcel.() -> Unit) {
        val start = SafeParcelWriter.writeObjectHeader(this)
        fields()
        SafeParcelWriter.finishObjectHeader(this, start)
    }
    private fun Parcel.writeObjectField(id: Int, fields: Parcel.() -> Unit) {
        writeInt(-0x10000 or id); writeInt(0)
        val start = dataPosition()
        writeObject(fields)
        SafeParcelWriter.finishObjectHeader(this, start)
    }
    private fun Parcel.writeIntField(id: Int, value: Int) { writeInt((4 shl 16) or id); writeInt(value) }
    private fun Parcel.writeLongField(id: Int, value: Long) { writeInt((8 shl 16) or id); writeLong(value) }
    private fun Parcel.writeEmptyListField(id: Int) { writeInt(-0x10000 or id); writeInt(4); writeInt(0) }
    /** ParcelableDuration / ParcelableInstant: {1 long seconds, 2 int nanos}. */
    private fun Parcel.writeSecondsNanos(millis: Long) {
        writeLongField(1, Math.floorDiv(millis, 1000L))
        writeIntField(2, (Math.floorMod(millis, 1000L) * 1_000_000).toInt())
    }
    private fun Parcel.writeDuration(millis: Long) = writeSecondsNanos(millis)
    private fun Parcel.writeInstant(millis: Long) = writeSecondsNanos(millis)

    private class Signal(val serverTimeMillis: Long, val ticks: Long, val errorMillis: Long)

    /** Minimal SNTP (RFC 4330) client; the result is anchored to elapsedRealtime, not the wall clock. */
    private object Sntp {
        @Volatile private var cached: Signal? = null

        @Synchronized
        fun latest(): Signal {
            cached?.takeIf { SystemClock.elapsedRealtime() - it.ticks < MAX_SIGNAL_AGE_MS }?.let { return it }
            return query().also { cached = it }
        }

        private fun query(): Signal = DatagramSocket().use { socket ->
            socket.soTimeout = NTP_TIMEOUT_MS
            val address = InetAddress.getByName(NTP_HOST)
            val buffer = ByteArray(48)
            buffer[0] = 0x23 // LI 0, version 4, mode 3 (client)
            val requestTicks = SystemClock.elapsedRealtime()
            val requestTime = System.currentTimeMillis()
            writeTimestamp(buffer, 40, requestTime)
            socket.send(DatagramPacket(buffer, buffer.size, address, NTP_PORT))
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            val responseTicks = SystemClock.elapsedRealtime()
            val responseTime = requestTime + (responseTicks - requestTicks)

            val mode = buffer[0].toInt() and 0x7
            val stratum = buffer[1].toInt() and 0xff
            val leap = (buffer[0].toInt() shr 6) and 0x3
            if (mode != 4 && mode != 5) throw IllegalStateException("bad mode $mode")
            if (stratum == 0 || stratum > 15) throw IllegalStateException("bad stratum $stratum")
            if (leap == 3) throw IllegalStateException("server unsynchronized")
            val originate = readTimestamp(buffer, 24)
            if (originate != requestTime) throw IllegalStateException("originate mismatch")
            val receive = readTimestamp(buffer, 32)
            val transmit = readTimestamp(buffer, 40)
            if (transmit == 0L) throw IllegalStateException("zero transmit time")

            val roundTrip = (responseTicks - requestTicks) - (transmit - receive)
            val offset = ((receive - requestTime) + (transmit - responseTime)) / 2
            Signal(serverTimeMillis = responseTime + offset, ticks = responseTicks, errorMillis = maxOf(roundTrip / 2, 1))
        }

        private fun readTimestamp(b: ByteArray, at: Int): Long {
            fun u32(o: Int) = ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
                    ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)
            val seconds = u32(at)
            val fraction = u32(at + 4)
            if (seconds == 0L && fraction == 0L) return 0
            return (seconds - NTP_EPOCH_OFFSET) * 1000 + fraction * 1000 / 0x100000000L
        }

        private fun writeTimestamp(b: ByteArray, at: Int, millis: Long) {
            val seconds = millis / 1000 + NTP_EPOCH_OFFSET
            val fraction = (millis % 1000) * 0x100000000L / 1000
            for (i in 0..3) b[at + i] = (seconds shr (24 - 8 * i)).toByte()
            for (i in 0..3) b[at + 4 + i] = (fraction shr (24 - 8 * i)).toByte()
        }
    }
}

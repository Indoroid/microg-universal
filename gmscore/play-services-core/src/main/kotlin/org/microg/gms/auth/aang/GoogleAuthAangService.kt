/*
 * SPDX-FileCopyrightText: 2026 microg-universal
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.auth.aang

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.HasCapabilitiesRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.Feature
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.common.internal.safeparcel.SafeParcelReader
import com.google.android.gms.common.internal.safeparcel.SafeParcelWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.microg.gms.BaseService
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.capabilities.HasCapabilitiesHandler
import org.microg.gms.common.GmsService
import org.microg.gms.common.GooglePackagePermission
import org.microg.gms.common.PackageUtils
import java.util.concurrent.TimeUnit

private const val TAG = "GmsAuthAang"
private const val DESCRIPTOR = "com.google.android.gms.auth.aang.internal.IGoogleAuthAangService"
private const val CALLBACKS = "com.google.android.gms.auth.aang.internal.IGoogleAuthAangCallbacks"
private const val STATUS_CALLBACK = "com.google.android.gms.common.api.internal.IStatusCallback"
private const val KEY_GAIA_ID = "GoogleUserId"

/**
 * Google Auth "AANG" account API (service 343), used by Gmail, Drive, Maps, Keep and Chrome.
 * Binder protocol:
 *  1 getAccounts(cb, GetAccountsRequest{1 type, 2 features, 3, 4, 5}) -> cb 1 (Status, GetAccountsResponse{1 List<GoogleAccount>, 2 List})
 *  2 getToken(cb, GetTokenRequest)                                   -> cb 2 (Status, GetTokenResponse)
 *  3 (IStatusCallback, String), 8 (IStatusCallback, GoogleAccount)   -> IStatusCallback 1 (Status)
 *  4 hasCapabilities(cb, {1 GoogleAccount, 2 List<String>})          -> cb 3 (Status, int)
 *  5 fetchAppRestriction -> cb 4, 6 syncAccountState -> cb 5, 7 reauth -> cb 6
 * GoogleAccount = {1 obfuscatedGaiaId, 2 accountType, 3 email}. Accounts without a gaia id are not
 * returned. getAccounts and hasCapabilities are served from microG's accounts and capability
 * store; the others answer API_NOT_CONNECTED, which
 * clients treat as "fall back to AccountManager / GoogleAuthUtil".
 * The API is first-party only (Google package permission ACCOUNT).
 */
class GoogleAuthAangService : BaseService(TAG, GmsService.GOOGLE_AUTH_AANG) {
    override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
        // Features the AANG client library checks for; calls we can't serve still get an error status.
        val features = arrayOf("google_auth_api", "sync_account_state_api", "embedded_reauth", "remove_account_api")
            .map { Feature(it, 1) }.toTypedArray()
        callback.onPostInitCompleteWithConnectionInfo(ConnectionResult.SUCCESS, Impl(), ConnectionInfo().apply { this.features = features })
    }

    private inner class Impl : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            if (code !in 1..8) return false
            data.enforceInterface(DESCRIPTOR)
            val cb = data.readStrongBinder()
            val allowed = PackageUtils.callerHasGooglePackagePermission(this@GoogleAuthAangService, GooglePackagePermission.ACCOUNT)
            val notConnected = Status(CommonStatusCodes.API_NOT_CONNECTED)
            if (cb != null) when {
                !allowed -> {
                    Log.w(TAG, "call $code from uid ${getCallingUid()} without ACCOUNT permission")
                    replyFailure(cb, code, Status(CommonStatusCodes.DEVELOPER_ERROR))
                }
                code == 1 -> {
                    val request = if (data.readInt() != 0) readGetAccounts(data) else null
                    lifecycleScope.launch(Dispatchers.IO) { getAccounts(cb, request) }
                }
                code == 4 -> {
                    val request = if (data.readInt() != 0) readHasCapabilities(data) else null
                    lifecycleScope.launch(Dispatchers.IO) { hasCapabilities(cb, request) }
                }
                else -> replyFailure(cb, code, notConnected)
            }
            reply?.writeNoException()
            return true
        }
    }

    private class GoogleAccount(val gaiaId: String, val type: String, val email: String)

    private class GetAccounts(val type: String?, val features: List<String>)

    private fun getAccounts(cb: IBinder, request: GetAccounts?) {
        val am = AccountManager.get(this)
        val type = request?.type?.takeIf { it.isNotEmpty() } ?: AuthConstants.DEFAULT_ACCOUNT_TYPE
        val accounts = runCatching {
            if (request?.features.isNullOrEmpty()) am.getAccountsByType(type)
            else am.getAccountsByTypeAndFeatures(type, request!!.features.toTypedArray(), null, null).getResult(10, TimeUnit.SECONDS)
        }.getOrElse {
            Log.w(TAG, "getAccounts failed", it)
            return transact(cb, CALLBACKS, 1) { writeStatus(Status(CommonStatusCodes.INTERNAL_ERROR)); writeInt(0) }
        }
        val result = accounts.mapNotNull { account ->
            val gaia = runCatching { am.getUserData(account, KEY_GAIA_ID) }.getOrNull()
            if (gaia.isNullOrEmpty()) null else GoogleAccount(gaia, account.type, account.name)
        }
        transact(cb, CALLBACKS, 1) {
            writeStatus(Status.SUCCESS)
            writeInt(1)
            val start = SafeParcelWriter.writeObjectHeader(this)
            writeListField(1, result) { a ->
                val s = SafeParcelWriter.writeObjectHeader(this)
                SafeParcelWriter.write(this, 1, a.gaiaId, false)
                SafeParcelWriter.write(this, 2, a.type, false)
                SafeParcelWriter.write(this, 3, a.email, false)
                SafeParcelWriter.finishObjectHeader(this, s)
            }
            writeListField(2, emptyList<Unit>()) {}
            SafeParcelWriter.finishObjectHeader(this, start)
        }
    }

    private fun hasCapabilities(cb: IBinder, request: Pair<GoogleAccount?, List<String>>?) {
        val account = request?.first
        val result = if (account == null) null else runCatching {
            HasCapabilitiesHandler(this).handle(HasCapabilitiesRequest().apply {
                this.account = Account(account.email, account.type)
                capabilities = request.second.toTypedArray()
            })
        }.onFailure { Log.w(TAG, "hasCapabilities failed", it) }.getOrNull()
        transact(cb, CALLBACKS, 3) {
            writeStatus(if (result != null) Status.SUCCESS else Status(CommonStatusCodes.DEVELOPER_ERROR))
            writeInt(result ?: 0)
        }
    }

    private fun replyFailure(cb: IBinder, code: Int, status: Status) = when (code) {
        1 -> transact(cb, CALLBACKS, 1) { writeStatus(status); writeInt(0) }
        2 -> transact(cb, CALLBACKS, 2) { writeStatus(status); writeInt(0) }
        3, 8 -> transact(cb, STATUS_CALLBACK, 1, withMetadata = false) { writeStatus(status) }
        4 -> transact(cb, CALLBACKS, 3) { writeStatus(status); writeInt(0) }
        5 -> transact(cb, CALLBACKS, 4) { writeStatus(status); writeInt(0) }
        6 -> transact(cb, CALLBACKS, 5) { writeStatus(status) }
        7 -> transact(cb, CALLBACKS, 6) { writeStatus(status); writeInt(0) }
        else -> Unit
    }

    // ---- parcel helpers ----

    private fun readGetAccounts(p: Parcel): GetAccounts {
        val end = SafeParcelReader.readObjectHeader(p)
        var type: String? = null
        var features: List<String> = emptyList()
        while (p.dataPosition() < end) {
            val header = SafeParcelReader.readHeader(p)
            when (SafeParcelReader.getFieldId(header)) {
                1 -> type = SafeParcelReader.readString(p, header)
                2 -> features = SafeParcelReader.readStringList(p, header).orEmpty()
                else -> SafeParcelReader.skip(p, header)
            }
        }
        p.setDataPosition(end)
        return GetAccounts(type, features)
    }

    private fun readGoogleAccount(p: Parcel): GoogleAccount? {
        val end = SafeParcelReader.readObjectHeader(p)
        val f = arrayOfNulls<String>(3)
        while (p.dataPosition() < end) {
            val header = SafeParcelReader.readHeader(p)
            val id = SafeParcelReader.getFieldId(header)
            if (id in 1..3) f[id - 1] = SafeParcelReader.readString(p, header) else SafeParcelReader.skip(p, header)
        }
        p.setDataPosition(end)
        return if (f[1] != null && f[2] != null) GoogleAccount(f[0].orEmpty(), f[1]!!, f[2]!!) else null
    }

    private fun readHasCapabilities(p: Parcel): Pair<GoogleAccount?, List<String>> {
        val end = SafeParcelReader.readObjectHeader(p)
        var account: GoogleAccount? = null
        var caps: List<String> = emptyList()
        while (p.dataPosition() < end) {
            val header = SafeParcelReader.readHeader(p)
            when (SafeParcelReader.getFieldId(header)) {
                1 -> {
                    val size = if (header and -0x10000 != -0x10000) header ushr 16 else p.readInt()
                    val fieldEnd = p.dataPosition() + size
                    account = readGoogleAccount(p)
                    p.setDataPosition(fieldEnd)
                }
                2 -> caps = SafeParcelReader.readStringList(p, header).orEmpty()
                else -> SafeParcelReader.skip(p, header)
            }
        }
        p.setDataPosition(end)
        return account to caps
    }

    /** Typed list field: header, size, count, then each element as presence int + object. */
    private fun <T> Parcel.writeListField(id: Int, items: List<T>, write: Parcel.(T) -> Unit) {
        writeInt(-0x10000 or id); writeInt(0)
        val start = dataPosition()
        writeInt(items.size)
        for (item in items) { writeInt(1); write(item) }
        SafeParcelWriter.finishObjectHeader(this, start)
    }

    private fun Parcel.writeStatus(status: Status) { writeInt(1); status.writeToParcel(this, 0) }

    private fun transact(cb: IBinder, descriptor: String, code: Int, withMetadata: Boolean = false, body: Parcel.() -> Unit) {
        val p = Parcel.obtain()
        try {
            p.writeInterfaceToken(descriptor)
            p.body()
            if (withMetadata) p.writeInt(0)
            cb.transact(code, p, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.w(TAG, "callback failed", e)
        } finally {
            p.recycle()
        }
    }
}

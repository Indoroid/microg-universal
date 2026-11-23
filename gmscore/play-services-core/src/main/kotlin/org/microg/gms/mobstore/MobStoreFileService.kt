/*
 * SPDX-FileCopyrightText: 2026 microg-universal
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.mobstore

import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.Feature
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.common.internal.safeparcel.SafeParcelReader
import com.google.android.gms.common.internal.safeparcel.SafeParcelWriter
import org.microg.gms.BaseService
import org.microg.gms.common.Constants
import org.microg.gms.common.GmsService
import org.microg.gms.common.PackageUtils
import java.io.File
import java.io.FileNotFoundException

private const val TAG = "GmsMobStore"
private const val DESCRIPTOR = "com.google.android.gms.mobstore.IMobStoreFileService"
private const val CALLBACKS = "com.google.android.gms.mobstore.IMobStoreFileCallbacks"
private const val GMS_AUTHORITY = "com.google.android.gms"

private const val STATUS_FILE_NOT_FOUND = 33500
private const val STATUS_DEVELOPER_ERROR = 10
private const val STATUS_INTERNAL_ERROR = 13

private const val OPEN_READ = 0
private const val OPEN_WRITE = 1

/**
 * MobStore file access (service 160), the file side of Mobile Data Download.
 *
 * Same contract as Play services: URIs are
 * `android://com.google.android.gms/<location>/<path>` with location files, cache, managed, external,
 * directboot-files or directboot-cache; 1 = openFileDescriptor (read or write), 2 = delete, 3 = rename;
 * a missing file is status 33500. Play services decides access with a server-pushed directory allowlist that
 * microG doesn't have, so instead every caller gets its own tree under microG's storage: an app reads
 * and writes only what it wrote itself, never another app's files.
 */
class MobStoreFileService : BaseService(TAG, GmsService.MOBSTORE_FILE) {
    override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
        val packageName = PackageUtils.getAndCheckCallingPackage(this, request.packageName)
            ?: return callback.onPostInitComplete(ConnectionResult.DEVELOPER_ERROR, null, null)
        // Feature set Play services advertises for this service; clients refuse calls without them.
        val features = arrayOf("mdd_download_right_now", "mdd_delayed_download", "mobstore_write_api", "mobstore_rename", "icing_get_document")
            .map { Feature(it, 1) }.toTypedArray()
        callback.onPostInitCompleteWithConnectionInfo(ConnectionResult.SUCCESS, Impl(packageName), ConnectionInfo().apply { this.features = features })
    }

    private inner class Impl(private val packageName: String) : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            if (code !in 1..3) return false
            data.enforceInterface(DESCRIPTOR)
            val cb = data.readStrongBinder()
            val fields = if (data.readInt() != 0) readUris(data) else Fields(emptyList(), 0)
            if (cb != null) {
                when (code) {
                    1 -> open(cb, fields.uris.getOrNull(0), fields.mode)
                    2 -> delete(cb, fields.uris.getOrNull(0))
                    3 -> rename(cb, fields.uris.getOrNull(0), fields.uris.getOrNull(1))
                }
            }
            reply?.writeNoException()
            return true
        }

        private fun open(cb: IBinder, uri: Uri?, mode: Int) {
            val file = resolve(uri) ?: return sendOpen(cb, Status(STATUS_DEVELOPER_ERROR, "Malformed URI: $uri"), null)
            val pfdMode = when (mode) {
                OPEN_READ -> ParcelFileDescriptor.MODE_READ_ONLY
                OPEN_WRITE -> {
                    file.parentFile?.mkdirs()
                    ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
                }
                else -> return sendOpen(cb, Status(STATUS_DEVELOPER_ERROR, "Open Type not supported (yet): $uri"), null)
            }
            val pfd = try {
                ParcelFileDescriptor.open(file, pfdMode)
            } catch (e: FileNotFoundException) {
                return sendOpen(cb, if (!file.exists()) Status(STATUS_FILE_NOT_FOUND, "File not found: $uri")
                else Status(STATUS_DEVELOPER_ERROR, "Access denied to uri (fnf but exists): $uri"), null)
            }
            try {
                sendOpen(cb, Status.SUCCESS, pfd)
            } finally {
                pfd.close()
            }
        }

        private fun delete(cb: IBinder, uri: Uri?) {
            val file = resolve(uri)
            val status = when {
                file == null -> Status(STATUS_DEVELOPER_ERROR, "Malformed URI: $uri")
                !file.exists() -> Status(STATUS_DEVELOPER_ERROR, "File not found: $uri")
                file.isDirectory -> Status(STATUS_DEVELOPER_ERROR, "Uri is not a file: $uri")
                file.delete() -> Status.SUCCESS
                else -> Status(STATUS_INTERNAL_ERROR, "Unable to delete file: $uri")
            }
            sendStatus(cb, 2, status)
        }

        private fun rename(cb: IBinder, from: Uri?, to: Uri?) {
            val source = resolve(from)
            val target = resolve(to)
            val status = when {
                source == null || target == null -> Status(STATUS_DEVELOPER_ERROR, "Malformed URI")
                !source.exists() -> Status(STATUS_FILE_NOT_FOUND, "File not found: $from")
                target.parentFile?.let { it.isDirectory || it.mkdirs() } != false && source.renameTo(target) -> Status.SUCCESS
                else -> Status(STATUS_INTERNAL_ERROR, "Unable to rename $from to $to")
            }
            sendStatus(cb, 3, status)
        }

        /** Maps a MobStore URI into the caller's own tree, rejecting malformed and escaping paths. */
        private fun resolve(uri: Uri?): File? {
            if (uri == null || uri.scheme != "android") return null
            if (uri.authority != GMS_AUTHORITY && uri.authority != Constants.GMS_PACKAGE_NAME) return null
            if (!uri.query.isNullOrEmpty() || !uri.fragment.isNullOrEmpty()) return null
            val segments = uri.pathSegments
            if (segments.size < 2 || segments.any { it.isEmpty() || it == "." || it == ".." || '/' in it }) return null
            val base = when (segments[0]) {
                "files", "managed", "external" -> filesDir
                "cache" -> cacheDir
                "directboot-files" -> createDeviceProtectedStorageContext().filesDir
                "directboot-cache" -> createDeviceProtectedStorageContext().cacheDir
                else -> return null
            }
            val root = File(File(base, "mobstore"), packageName)
            val file = File(File(root, segments[0]), segments.drop(1).joinToString(File.separator))
            return file.takeIf { it.canonicalPath.startsWith(root.canonicalPath + File.separator) }
        }
    }

    /** OpenFileDescriptorRequest {1 Uri, 2 int mode}, DeleteFileRequest {1 Uri}, RenameRequest {1 Uri, 2 Uri}. */
    private class Fields(val uris: List<Uri>, val mode: Int)

    private fun readUris(p: Parcel): Fields {
        val end = SafeParcelReader.readObjectHeader(p)
        val uris = arrayOfNulls<Uri>(2)
        var mode = OPEN_READ
        while (p.dataPosition() < end) {
            val header = SafeParcelReader.readHeader(p)
            when (val id = SafeParcelReader.getFieldId(header)) {
                1 -> uris[0] = SafeParcelReader.readParcelable(p, header, Uri.CREATOR)
                2 -> {
                    // Field 2 is the mode int in an open request and the target Uri in a rename request.
                    if (header and -0x10000 != -0x10000) mode = SafeParcelReader.readInt(p, header)
                    else uris[1] = SafeParcelReader.readParcelable(p, header, Uri.CREATOR)
                }
                else -> { Log.d(TAG, "skip field $id"); SafeParcelReader.skip(p, header) }
            }
        }
        p.setDataPosition(end)
        return Fields(uris.filterNotNull(), mode)
    }

    private fun transact(cb: IBinder, code: Int, body: Parcel.() -> Unit) {
        val p = Parcel.obtain()
        try {
            p.writeInterfaceToken(CALLBACKS)
            p.body()
            p.writeInt(0) // ApiMetadata: none
            cb.transact(code, p, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.w(TAG, "callback failed", e)
        } finally {
            p.recycle()
        }
    }

    private fun Parcel.writeStatus(status: Status) { writeInt(1); status.writeToParcel(this, 0) }

    private fun sendStatus(cb: IBinder, code: Int, status: Status) = transact(cb, code) { writeStatus(status) }

    private fun sendOpen(cb: IBinder, status: Status, pfd: ParcelFileDescriptor?) = transact(cb, 1) {
        writeStatus(status)
        if (pfd == null) {
            writeInt(0)
        } else {
            writeInt(1)
            val start = SafeParcelWriter.writeObjectHeader(this)
            SafeParcelWriter.write(this, 1, pfd, 0, false)
            SafeParcelWriter.finishObjectHeader(this, start)
        }
    }
}

package com.vknxworks.shutterctrl.camera

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.vknxworks.shutterctrl.ptp.ObjectInfo
import java.io.OutputStream

/**
 * Saves downloads to Pictures/ShutterCtrl through MediaStore. The file stays hidden (IS_PENDING) until
 * it's complete, and is removed again if the download fails.
 */
class Saver(private val context: Context) {
    class Pending(val uri: Uri, val out: OutputStream)

    fun begin(info: ObjectInfo): Pending {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, info.safeFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, info.mime)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val video = info.mime.startsWith("video/")
        val uri = try {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/ShutterCtrl" else "Pictures/ShutterCtrl")
            val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            resolver.insert(collection, values)
        } catch (_: IllegalArgumentException) {
            null
        } ?: run {
            // A RAW type this phone's media library doesn't know: keep it as a plain file in Downloads.
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ShutterCtrl")
            resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } ?: throw java.io.IOException("No room in the media library")
        val out = resolver.openOutputStream(uri) ?: run {
            resolver.delete(uri, null, null)
            throw java.io.IOException("Can't write")
        }
        return Pending(uri, out)
    }

    fun finish(p: Pending) {
        p.out.close()
        context.contentResolver.update(p.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun abort(p: Pending) {
        runCatching { p.out.close() }
        runCatching { context.contentResolver.delete(p.uri, null, null) }
    }

    companion object {
        const val LUMEN_PACKAGE = "com.vknxworks.lumenctrl"

        fun lumenInstalled(context: Context): Boolean =
            runCatching { context.packageManager.getPackageInfo(LUMEN_PACKAGE, 0) }.isSuccess

        /** Opens a saved photo in LumenCtrl (VIEW, or SEND if LumenCtrl doesn't take VIEW for that type). */
        fun lumenIntent(context: Context, uri: Uri, mime: String): Intent? {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).setPackage(LUMEN_PACKAGE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (view.resolveActivity(context.packageManager) != null) return view
            val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).setPackage(LUMEN_PACKAGE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return send.takeIf { it.resolveActivity(context.packageManager) != null }
        }
    }
}

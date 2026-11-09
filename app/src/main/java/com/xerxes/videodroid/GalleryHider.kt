package com.xerxes.videodroid

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Stage 4: the "Hide from Gallery" switch.
 *
 * What it actually does: drops a `.nomedia` marker into `Download/VideoDroid`, which tells
 * the media scanner and most gallery apps to ignore that folder.
 *
 * What it can NOT do: hide entries that were inserted through MediaStore on Android 10+.
 * Those rows belong to the Downloads collection and are indexed already; a dedicated gallery
 * that queries the collection directly can still list them. The toggle reports honestly
 * whether the marker was written, and never claims more than that.
 */
object GalleryHider {

    private const val RELATIVE = "Download/VideoDroid"
    private const val MARKER = ".nomedia"

    fun isHidden(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "VideoDroid",
            )
            return File(dir, MARKER).exists()
        }
        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(MARKER, "%VideoDroid%")
        return runCatching {
            ctx.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
                c.count > 0
            } ?: false
        }.getOrDefault(false)
    }

    /** @return null on success, or a short human reason on failure. */
    fun setHidden(ctx: Context, hide: Boolean): String? {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "VideoDroid",
            )
            runCatching {
                if (hide) {
                    dir.mkdirs()
                    File(dir, MARKER).writeText("")
                } else {
                    File(dir, MARKER).delete()
                }
                null
            }.getOrElse { it.message ?: "could not write the marker" }
        } else {
            if (!hide) {
                return deleteMarker(ctx)
            }
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, MARKER)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val item = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values,
                ) ?: return "MediaStore refused the marker"
                ctx.contentResolver.openOutputStream(item)?.use { it.write(ByteArray(0)) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(item, values, null, null)
                null
            }.getOrElse { it.message ?: "could not write the marker" }
        }
    }

    private fun deleteMarker(ctx: Context): String? = runCatching {
        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val n = ctx.contentResolver.delete(uri, selection, arrayOf(MARKER, "%VideoDroid%"))
        if (n >= 0) null else "nothing removed"
    }.getOrElse { it.message ?: "could not remove the marker" }
}

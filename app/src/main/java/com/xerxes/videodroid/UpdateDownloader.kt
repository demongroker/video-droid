package com.xerxes.videodroid

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resumable APK download for the in-app updater.
 *
 * Rules:
 *  - A partial file + its ETag are kept between attempts.
 *  - Resume sends `Range: bytes=<len>-` and `If-Range: <etag>`.
 *  - 206 -> append. 200 -> server ignored the range, restart from zero.
 *  - GitHub asset URLs are pre-signed and expire; on 403/404 the caller's
 *    resolver is asked for a fresh URL and the transfer continues from the
 *    same byte offset against the new URL.
 *  - The partial file is only promoted to [dest] when the byte count matches
 *    the advertised total (when the server gives one).
 *
 * All network access goes through [opener] so the logic is unit-testable
 * without Android or the real GitHub CDN.
 */
class UpdateDownloader(
    private val opener: (String) -> HttpURLConnection = { url ->
        (URL(url).openConnection() as HttpURLConnection)
    },
    private val resolver: (String) -> String? = { null },
) {

    data class Result(val file: File?, val bytes: Long, val resumed: Boolean, val error: String? = null)

    fun download(
        url: String,
        dest: File,
        etag: String? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Result {
        val part = File(dest.absolutePath + ".part")
        val etagFile = File(dest.absolutePath + ".etag")

        var startAt = if (part.exists()) part.length() else 0L
        var usedUrl = url
        var resumed = startAt > 0

        var attempt = 0
        while (true) {
            if (isCancelled()) return Result(null, startAt, resumed, "cancelled")
            val conn = try {
                opener(usedUrl)
            } catch (e: Throwable) {
                return Result(null, startAt, resumed, e.javaClass.simpleName)
            }
            try {
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "VideoDroid")
                conn.setRequestProperty("Accept", "application/octet-stream")
                if (startAt > 0 && !etag.isNullOrBlank()) {
                    conn.setRequestProperty("Range", "bytes=$startAt-")
                    conn.setRequestProperty("If-Range", etag)
                }

                val code = conn.responseCode
                if ((code == 403 || code == 404) && startAt > 0) {
                    // Expired pre-signed URL: ask for a fresh one, keep the offset.
                    val fresh = resolver(usedUrl)
                    if (fresh != null && fresh != usedUrl && ++attempt <= 3) {
                        usedUrl = fresh
                        continue
                    }
                }
                if (code != 200 && code != 206) {
                    return Result(null, startAt, resumed, "HTTP $code")
                }

                val append = code == 206 && startAt > 0
                val total = totalBytes(conn, startAt, append)

                RandomAccessFile(part, "rw").use { out ->
                    if (!append) {
                        out.setLength(0)
                        startAt = 0
                        resumed = false
                    }
                    out.seek(startAt)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var written = startAt
                        while (true) {
                            if (isCancelled()) return Result(null, written, resumed, "cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            onProgress(written, total)
                        }
                    }
                }

                val newEtag = conn.getHeaderField("ETag")
                if (!newEtag.isNullOrBlank()) etagFile.writeText(newEtag)

                val finalLen = part.length()
                if (total > 0 && finalLen != total) {
                    return Result(null, finalLen, resumed, "short read ($finalLen/$total)")
                }
                if (finalLen < 1024) {
                    return Result(null, finalLen, resumed, "too small")
                }
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                etagFile.delete()
                return Result(dest, finalLen, resumed, null)
            } catch (e: Throwable) {
                return Result(null, startAt, resumed, e.javaClass.simpleName)
            } finally {
                try { conn.disconnect() } catch (_: Throwable) {}
            }
        }
    }

    /** Total size of the whole file, or -1 when unknown. */
    private fun totalBytes(conn: HttpURLConnection, startAt: Long, append: Boolean): Long {
        if (append) {
            parseContentRangeTotal(conn.getHeaderField("Content-Range"))?.let { return it }
            val len = conn.contentLengthLong
            return if (len > 0) startAt + len else -1
        }
        return conn.contentLengthLong
    }

    companion object {
        /** Parses `bytes 100-999/1000` -> 1000. */
        fun parseContentRangeTotal(header: String?): Long? {
            if (header.isNullOrBlank()) return null
            val slash = header.lastIndexOf('/')
            if (slash < 0 || slash == header.length - 1) return null
            return header.substring(slash + 1).trim().toLongOrNull()
        }
    }
}

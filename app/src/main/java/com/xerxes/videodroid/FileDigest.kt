package com.xerxes.videodroid

import java.io.File
import java.security.MessageDigest

/** Stage 5: content identity for a downloaded file. */
object FileDigest {

    private const val CHUNK = 256 * 1024

    /**
     * Streaming SHA-256 of [file], or null when it cannot be read.
     *
     * Hashing 400 MB costs a couple of seconds on a phone, which is why this only ever runs
     * on a download worker thread, never on the UI thread.
     */
    /** Hash an arbitrary stream — MediaStore rows are URIs, not files. */
    fun sha256(stream: java.io.InputStream): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            stream.use { input ->
                val buf = ByteArray(CHUNK)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            null
        }
    }

    /** Hash a content URI through the resolver. */
    fun sha256(resolver: android.content.ContentResolver, uri: android.net.Uri): String? =
        try {
            resolver.openInputStream(uri)?.let { sha256(it) }
        } catch (t: Throwable) {
            null
        }

    fun sha256(file: File): String? {
        if (!file.isFile || file.length() <= 0) return null
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(CHUNK)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            null
        }
    }
}

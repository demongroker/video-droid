package com.xerxes.videodroid

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Canonical link keys for everything already saved.
 *
 * The hash gate can only run after a download, and by then 425 MB have already crossed the ship
 * Wi-Fi. This index lets the app refuse a duplicate **before** the transfer starts: the canonical
 * key (host + path / X status id) is recorded on every successful save and checked when a job is
 * queued.
 *
 * The SHA-256 check stays as the second safety net for the same bytes under a different link.
 * Nothing is ever deleted here — this is a read/write list of strings.
 */
object SavedLinkIndex {

    private const val FILE = "saved_links.json"
    private const val MAX_ENTRIES = 4000

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /**
     * Identity for duplicate purposes: canonical host + path, and only the query parameter that
     * identifies the video (`v=`, YouTube). Share payloads add `?a=…&s=19`, and treating those as
     * different videos is exactly how the same 425 MB file came down five times.
     */
    fun keyFor(url: String): String? {
        val canonical = MediaIdentity.canonicalUrl(url) ?: return null
        val (base, query) = canonical.split("?", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val idParam = query.split("&").firstOrNull { it.startsWith("v=") }
        return if (idParam.isNullOrBlank()) base else "$base?$idParam"
    }

    @Synchronized
    fun keys(ctx: Context): MutableList<String> {
        val out = ArrayList<String>()
        runCatching {
            val f = file(ctx)
            if (!f.isFile) return out
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(out::add)
        }
        return out
    }

    @Synchronized
    fun contains(ctx: Context, url: String): Boolean {
        val key = keyFor(url) ?: return false
        return keys(ctx).contains(key)
    }

    @Synchronized
    fun remember(ctx: Context, url: String) {
        val key = keyFor(url) ?: return
        runCatching {
            val current = keys(ctx)
            if (current.contains(key)) return
            current.add(key)
            val trimmed = if (current.size > MAX_ENTRIES) {
                current.subList(current.size - MAX_ENTRIES, current.size)
            } else {
                current
            }
            val arr = JSONArray()
            trimmed.forEach { arr.put(it) }
            file(ctx).writeText(arr.toString())
        }
    }

    fun size(ctx: Context): Int = keys(ctx).size
}

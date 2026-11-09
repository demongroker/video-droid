package com.xerxes.videodroid

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Stage 5: hashes of everything this app has already saved.
 *
 * The link check catches "same URL again"; this catches the harder case — the same bytes
 * arriving under a different name, from a mirror, a re-upload, or a link with tracking
 * parameters. Entries are `sha256 → MediaStore id`, so a hit can still hand back a URI.
 *
 * Scope, honestly: the index only knows files saved **after** it shipped. Media already in
 * the library when the app updated is matched by link identity and file name, not by hash.
 */
object SavedHashIndex {

    private const val FILE = "saved_hashes.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun read(ctx: Context): MutableMap<String, Long> {
        val out = HashMap<String, Long>()
        runCatching {
            val f = file(ctx)
            if (!f.isFile) return out
            val json = JSONObject(f.readText())
            json.keys().forEach { k ->
                out[k] = json.optLong(k, -1L)
            }
        }
        return out
    }

    @Synchronized
    fun contains(ctx: Context, hash: String?): Long? {
        if (hash.isNullOrBlank()) return null
        return read(ctx)[hash]?.takeIf { it > 0 }
    }

    @Synchronized
    fun remember(ctx: Context, hash: String?, mediaId: Long) {
        if (hash.isNullOrBlank() || mediaId <= 0) return
        runCatching {
            val current = read(ctx)
            current[hash] = mediaId
            val json = JSONObject()
            current.forEach { (k, v) -> json.put(k, v) }
            file(ctx).writeText(json.toString())
        }
    }

    fun size(ctx: Context): Int = read(ctx).size
}

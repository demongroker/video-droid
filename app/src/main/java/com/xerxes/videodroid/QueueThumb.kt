package com.xerxes.videodroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Stage 3: a real picture on every queue row.
 *
 * Two sources, cheapest first:
 *  1. a video file already on disk (local import, or a job that kept its output) —
 *     decoded offline with [ThumbnailUtils];
 *  2. the page's own preview image (`og:image` / `twitter:image`), fetched once per job and
 *     kept in an in-memory cache.
 *
 * No image library is pulled in: one background thread, one small LRU, and the request is
 * matched against the view tag so a recycled row never shows another job's picture.
 */
object QueueThumb {

    private val cache = LruCache<String, Bitmap>(48)
    /** Attempt count per job: a dead page is retried once, then left alone. */
    private val attempts = HashMap<String, Int>()
    private const val MAX_ATTEMPTS = 2
    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "queue-thumb").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val main = Handler(Looper.getMainLooper())

    /** Show whatever is known now, then fill in asynchronously. Never blocks the binder. */
    fun load(ctx: Context, job: QueuedJob, view: ImageView) {
        val key = job.id.ifBlank { job.url }
        view.tag = key
        view.setImageDrawable(null)
        view.setBackgroundColor(0xFF23202B.toInt())

        cache.get(key)?.let { view.setImageBitmap(it); return }

        val file = File(job.localPath.ifBlank { job.partialPath })
        if (job.localPath.isNotBlank() && file.isFile() && file.length() > 0) {
            pool.execute {
                val bmp = runCatching {
                    ThumbnailUtils.createVideoThumbnail(
                        file.absolutePath,
                        android.provider.MediaStore.Video.Thumbnails.MINI_KIND,
                    )
                }.getOrNull()
                if (bmp != null) {
                    cache.put(key, bmp)
                    main.post { if (view.tag == key) view.setImageBitmap(bmp) }
                }
            }
            return
        }

        val pageUrl = job.url
        if (pageUrl.isBlank()) return
        if ((attempts[key] ?: 0) >= MAX_ATTEMPTS) return
        attempts[key] = (attempts[key] ?: 0) + 1
        pool.execute {
            val poster = runCatching { posterFor(ctx, pageUrl) }.getOrNull()
            if (poster == null) return@execute // attempts already counted; one retry max
            cache.put(key, poster)
            main.post { if (view.tag == key) view.setImageBitmap(poster) }
        }
    }

    private fun posterFor(ctx: Context, pageUrl: String): Bitmap? {
        val html = when {
            pageUrl.startsWith("http") -> runCatching {
                StaticPageFetcher.fetch(ctx, pageUrl).html
            }.getOrNull()
            else -> null
        } ?: return null
        val posterUrl = MediaUrlParser.poster(html, pageUrl) ?: return null
        return download(posterUrl)
    }

    private fun download(imageUrl: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) VideoDroid")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}

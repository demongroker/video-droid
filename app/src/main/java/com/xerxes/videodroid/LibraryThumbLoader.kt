package com.xerxes.videodroid

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Stage 5b: Library thumbnails off the UI thread.
 *
 * The grid used to call `loadThumbnail()` inside `getView()`, which decodes on the main
 * thread and stutters on a large library. Same shape as [QueueThumb]: one background thread,
 * a small LRU, and the tag check so a recycled cell never shows the wrong video.
 */
object LibraryThumbLoader {

    private val cache = LruCache<String, Bitmap>(64)
    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "library-thumb").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val main = Handler(Looper.getMainLooper())

    fun load(ctx: Context, uri: Uri, view: ImageView) {
        val key = uri.toString()
        view.tag = key
        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        // Purple shimmer placeholder while the frame is decoded — never a black box.
        view.setImageResource(R.drawable.bg_shimmer)
        pool.execute {
            val bitmap = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    ctx.contentResolver.loadThumbnail(uri, Size(256, 256), null)
                } else {
                    null
                }
            }.getOrNull() ?: return@execute
            cache.put(key, bitmap)
            main.post { if (view.tag == key) view.setImageBitmap(bitmap) }
        }
    }
}

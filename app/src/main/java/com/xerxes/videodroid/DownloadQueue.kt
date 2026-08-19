package com.xerxes.videodroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class QueuedJob(val url: String, val opts: FormatOptions)

/**
 * FIFO job list persisted under filesDir/download_queue.json so a process death
 * can resume remaining URLs (current job restarts from scratch).
 */
object DownloadQueue {
    private const val FILE = "download_queue.json"
    private val lock = Any()

    fun enqueue(ctx: Context, job: QueuedJob): Int = synchronized(lock) {
        val jobs = loadLocked(ctx)
        jobs.add(job)
        saveLocked(ctx, jobs)
        jobs.size
    }

    fun peek(ctx: Context): QueuedJob? = synchronized(lock) {
        loadLocked(ctx).firstOrNull()
    }

    fun completeCurrent(ctx: Context): QueuedJob? = synchronized(lock) {
        val jobs = loadLocked(ctx)
        if (jobs.isEmpty()) return null
        val done = jobs.removeAt(0)
        saveLocked(ctx, jobs)
        done
    }

    fun size(ctx: Context): Int = synchronized(lock) { loadLocked(ctx).size }

    fun waiting(ctx: Context): Int = synchronized(lock) {
        (loadLocked(ctx).size - 1).coerceAtLeast(0)
    }

    fun clear(ctx: Context) = synchronized(lock) {
        saveLocked(ctx, mutableListOf())
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun loadLocked(ctx: Context): MutableList<QueuedJob> {
        val f = file(ctx)
        if (!f.isFile || f.length() == 0L) return mutableListOf()
        return try {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("jobs") ?: JSONArray()
            val out = mutableListOf<QueuedJob>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    QueuedJob(
                        url = o.getString("url"),
                        opts = FormatOptions(
                            dlQuality = o.optString("dlq", "720p"),
                            exportHeight = o.optInt("height", 1080),
                            aspect = o.optString("aspect", "original"),
                            fps = o.optInt("fps", 30),
                            crf = o.optInt("qv", 6),
                            trimEnabled = o.optBoolean("trim", true),
                            trimStart = o.optInt("tstart", 1),
                            trimEnd = o.optInt("tend", 1),
                            exportEnabled = o.optBoolean("export", true),
                        ),
                    ),
                )
            }
            out
        } catch (_: Throwable) {
            mutableListOf()
        }
    }

    private fun saveLocked(ctx: Context, jobs: List<QueuedJob>) {
        val arr = JSONArray()
        for (j in jobs) {
            arr.put(
                JSONObject().apply {
                    put("url", j.url)
                    put("dlq", j.opts.dlQuality)
                    put("height", j.opts.exportHeight)
                    put("aspect", j.opts.aspect)
                    put("fps", j.opts.fps)
                    put("qv", j.opts.crf)
                    put("trim", j.opts.trimEnabled)
                    put("tstart", j.opts.trimStart)
                    put("tend", j.opts.trimEnd)
                    put("export", j.opts.exportEnabled)
                },
            )
        }
        val tmp = File(file(ctx).absolutePath + ".tmp")
        tmp.writeText(JSONObject().put("jobs", arr).toString())
        if (!tmp.renameTo(file(ctx))) {
            file(ctx).writeText(tmp.readText())
            tmp.delete()
        }
    }
}

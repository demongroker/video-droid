package com.xerxes.videodroid

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class QueuedJob(
    val url: String,
    val opts: FormatOptions,
    val localPath: String = "",
    /** Last known download percentage (0-100); survives restarts. */
    val progress: Int = 0,
    /** Directory holding the resumable .part file for this item. */
    val partialPath: String = "",
    /** Stable id so parallel workers and the UI can address one item. */
    val id: String = "",
    /** True when the last attempt failed; such rows are not auto-retried. */
    val failed: Boolean = false,
    /** Why the last attempt failed (shown on the row). */
    val failReason: String = "",
) {
    val displayName: String
        get() = url.ifBlank { localPath.substringAfterLast('/') }
}

/**
 * FIFO job list persisted under filesDir/download_queue.json.
 *
 * With parallel downloads more than one item can be in flight, so the queue
 * hands out **claims**: [claimNext] marks an item in-flight and returns it, and
 * the worker calls [finish] when done. Claims live in memory only — a process
 * death releases them all, which is exactly what a restart wants.
 */
object DownloadQueue {
    private const val FILE = "download_queue.json"
    private const val TAG = "VideoDroid.Queue"
    private const val CORRUPT_MSG = "Queue file corrupt. Backup kept."
    private val lock = Any()

    /** ids currently held by a worker. */
    private val claimed = LinkedHashSet<String>()

    @Volatile
    var lastCorruptNotice: String? = null
        private set

    fun consumeCorruptNotice(): String? {
        val m = lastCorruptNotice
        lastCorruptNotice = null
        return m
    }

    /** New queue size, or **-1** when this URL is already queued. */
    fun enqueue(ctx: Context, job: QueuedJob): Int = synchronized(lock) {
        val jobs = loadLocked(ctx)
        val url = job.url.trim()
        if (url.isNotEmpty() && jobs.any { it.url.trim() == url }) {
            return@synchronized -1
        }
        jobs.add(job.copy(id = job.id.ifEmpty { newId() }))
        saveLocked(ctx, jobs)
        jobs.size
    }

    fun peek(ctx: Context): QueuedJob? = synchronized(lock) {
        loadLocked(ctx).firstOrNull { it.id !in claimed && !it.failed }
    }

    /** Next item not already in flight, claimed for the calling worker. */
    fun claimNext(ctx: Context): QueuedJob? = synchronized(lock) {
        val next = loadLocked(ctx).firstOrNull { it.id !in claimed && !it.failed }
            ?: return@synchronized null
        claimed.add(next.id)
        next
    }

    /** Keep the item visible as failed instead of silently dropping it. */
    fun markFailed(ctx: Context, id: String, reason: String) = synchronized(lock) {
        claimed.remove(id)
        val jobs = loadLocked(ctx)
        val idx = jobs.indexOfFirst { it.id == id }
        if (idx >= 0) {
            jobs[idx] = jobs[idx].copy(failed = true, failReason = reason.take(200))
            saveLocked(ctx, jobs)
        }
    }

    /** Clear the failed flag so the pump picks the item up again. */
    fun retry(ctx: Context, id: String): Boolean = synchronized(lock) {
        val jobs = loadLocked(ctx)
        val idx = jobs.indexOfFirst { it.id == id }
        if (idx < 0) return@synchronized false
        jobs[idx] = jobs[idx].copy(failed = false, failReason = "", progress = 0, partialPath = "")
        saveLocked(ctx, jobs)
        claimed.remove(id)
        true
    }

    /** Release the claim and drop the item (finished, failed or cancelled). */
    fun finish(ctx: Context, id: String) = synchronized(lock) {
        claimed.remove(id)
        val jobs = loadLocked(ctx)
        jobs.removeAll { it.id == id }
        saveLocked(ctx, jobs)
    }

    /** Drop one item without running it (per-item cancel). */
    fun remove(ctx: Context, id: String) = synchronized(lock) {
        claimed.remove(id)
        val jobs = loadLocked(ctx)
        jobs.removeAll { it.id == id }
        saveLocked(ctx, jobs)
    }

    fun releaseAllClaims() = synchronized(lock) { claimed.clear() }

    fun inFlightIds(): List<String> = synchronized(lock) { claimed.toList() }

    fun isClaimed(id: String): Boolean = synchronized(lock) { id in claimed }

    fun get(ctx: Context, id: String): QueuedJob? = synchronized(lock) {
        loadLocked(ctx).firstOrNull { it.id == id }
    }

    fun all(ctx: Context): List<QueuedJob> = synchronized(lock) { loadLocked(ctx).toList() }

    fun size(ctx: Context): Int = synchronized(lock) { loadLocked(ctx).size }

    /** Items not yet claimed — what the UI shows as "queued". */
    fun waiting(ctx: Context): Int = synchronized(lock) {
        loadLocked(ctx).count { it.id !in claimed && !it.failed }
    }

    fun setProgress(ctx: Context, id: String, progress: Int, partialPath: String) =
        synchronized(lock) {
            val jobs = loadLocked(ctx)
            val idx = jobs.indexOfFirst { it.id == id }
            if (idx < 0) return@synchronized
            val cur = jobs[idx]
            val p = progress.coerceIn(0, 100)
            if (cur.progress == p && cur.partialPath == partialPath) return@synchronized
            jobs[idx] = cur.copy(progress = p, partialPath = partialPath)
            saveLocked(ctx, jobs)
        }

    fun clear(ctx: Context) = synchronized(lock) {
        claimed.clear()
        saveLocked(ctx, mutableListOf())
    }

    fun newId(): String = java.util.UUID.randomUUID().toString()

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
                            crf = o.optInt("qv", 3),
                            trimEnabled = o.optBoolean("trim", true),
                            trimStart = FormatOptions.clampTrimSeconds(o.optInt("tstart", 1)),
                            trimEnd = FormatOptions.clampTrimSeconds(o.optInt("tend", 1)),
                            exportEnabled = o.optBoolean("export", true),
                        ),
                        localPath = o.optString("local", ""),
                        progress = o.optInt("progress", 0),
                        partialPath = o.optString("partial", ""),
                        // Queue files written before 1.9.4 have no id.
                        id = o.optString("id", "").ifEmpty { newId() },
                        failed = o.optBoolean("failed", false),
                        failReason = o.optString("failReason", ""),
                    ),
                )
            }
            out
        } catch (_: Throwable) {
            backupCorrupt(f)
            lastCorruptNotice = CORRUPT_MSG
            JobDiag.noteStatus(CORRUPT_MSG)
            JobDiag.noteException(CORRUPT_MSG)
            Log.e(TAG, CORRUPT_MSG)
            mutableListOf()
        }
    }

    private fun backupCorrupt(src: File) {
        val stamp = System.currentTimeMillis()
        val dest = File(src.parentFile, "${src.name}.bad.$stamp")
        try {
            src.copyTo(dest, overwrite = false)
        } catch (_: Throwable) {
            try {
                dest.writeText(src.readText())
            } catch (_: Throwable) { }
        }
    }

    private fun saveLocked(ctx: Context, jobs: List<QueuedJob>) {
        val arr = JSONArray()
        for (j in jobs) {
            arr.put(
                JSONObject().apply {
                    put("id", j.id)
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
                    put("local", j.localPath)
                    put("progress", j.progress)
                    put("partial", j.partialPath)
                    put("failed", j.failed)
                    put("failReason", j.failReason)
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

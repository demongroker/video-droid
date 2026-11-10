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
    /**
     * True when the owner paused this item. Paused rows keep their partial file and
     * progress, are skipped by the pump, and are not counted as waiting.
     */
    val paused: Boolean = false,
    /** How many times this job has been attempted; drives the retry backoff. */
    val attempts: Int = 0,
    /** Epoch ms before which this job must not be picked up again (retry backoff). */
    val retryAfter: Long = 0L,
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

    /** Attempts before a transient failure is given up on (1 initial + 3 retries is too many; 3 total). */
    private const val MAX_ATTEMPTS = 3

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

    /** Is this URL already queued (live, paused, failed or waiting out a retry)? */
    fun has(ctx: Context, url: String): Boolean = synchronized(lock) {
        val u = url.trim()
        u.isNotEmpty() && loadLocked(ctx).any { sameLink(it.url, u) }
    }

    /**
     * Same media, however the link was pasted. X share links carry `?s=09`, and the same post
     * can arrive as x.com / twitter.com / i/web/status — compared as raw strings those were all
     * different, so the queue happily took the same video twice.
     */
    private fun sameLink(a: String, b: String): Boolean {
        val ta = a.trim()
        val tb = b.trim()
        if (ta == tb) return true
        return MediaIdentity.sameMedia(ta, tb)
    }

    /** New queue size, or **-1** when this URL is already queued. */
    fun enqueue(ctx: Context, job: QueuedJob): Int = synchronized(lock) {
        val jobs = loadLocked(ctx)
        val url = job.url.trim()
        if (url.isNotEmpty() && jobs.any { sameLink(it.url, url) }) {
            return@synchronized -1
        }
        jobs.add(job.copy(id = job.id.ifEmpty { newId() }))
        saveLocked(ctx, jobs)
        jobs.size
    }

    /**
     * Eligible for the pump: not claimed, not failed, not paused, and past its retry backoff.
     * A job waiting out a backoff stays visible as "Waiting" and is picked up on a later pump.
     */
    private fun eligible(j: QueuedJob): Boolean =
        j.id !in claimed && !j.failed && !j.paused && j.retryAfter <= System.currentTimeMillis()

    fun peek(ctx: Context): QueuedJob? = synchronized(lock) {
        loadLocked(ctx).firstOrNull { eligible(it) }
    }

    /** Next item not already in flight and not paused, claimed for the calling worker. */
    fun claimNext(ctx: Context): QueuedJob? = synchronized(lock) {
        val next = loadLocked(ctx).firstOrNull { eligible(it) }
            ?: return@synchronized null
        claimed.add(next.id)
        next
    }

    /** Earliest epoch ms at which some job becomes eligible again (0 if none waiting). */
    fun nextRetryAt(ctx: Context): Long = synchronized(lock) {
        val now = System.currentTimeMillis()
        loadLocked(ctx)
            .filter { it.id !in claimed && !it.failed && !it.paused && it.retryAfter > now }
            .minOfOrNull { it.retryAfter } ?: 0L
    }

    /** Release the claim without changing the row (used for a user cancellation). */
    fun releaseClaim(id: String) = synchronized(lock) { claimed.remove(id) }

    /**
     * Record a failure. Network-shaped failures are retried with backoff
     * ([RETRY_BACKOFF_MS]) instead of being parked as permanently failed — a dropped radio
     * should not need the owner to come back and tap retry. After [MAX_ATTEMPTS] the job is
     * marked failed for real so a dead link cannot spin forever.
     *
     * Anything that is not a network problem (bad URL, private post, unsupported site) fails
     * immediately: retrying those just burns battery and re-downloads nothing.
     */
    fun markFailure(ctx: Context, id: String, reason: String) = synchronized(lock) {
        claimed.remove(id)
        val jobs = loadLocked(ctx)
        val idx = jobs.indexOfFirst { it.id == id }
        if (idx < 0) return@synchronized
        val cur = jobs[idx]
        val attempts = cur.attempts + 1
        val retryable = isRetryableReason(reason) && attempts <= MAX_ATTEMPTS
        jobs[idx] = if (retryable) {
            cur.copy(
                attempts = attempts,
                retryAfter = System.currentTimeMillis() + retryDelayMs(attempts),
                failReason = reason.take(200),
            )
        } else {
            cur.copy(
                attempts = attempts,
                retryAfter = 0L,
                failed = true,
                failReason = reason.take(200),
            )
        }
        saveLocked(ctx, jobs)
    }

    /** Network-shaped failures are worth retrying; bad URLs and auth problems are not. */
    fun isRetryableReason(reason: String): Boolean {
        val s = reason.lowercase()
        if (s.isBlank()) return false
        val permanent = listOf(
            "profile link", "no video", "unsupported", "404", "file gone",
            "need x login", "private", "format is gone", "tls bad",
        )
        if (permanent.any { s.contains(it) }) return false
        val transient = listOf(
            "no network", "timeout", "timed out", "connection", "reset",
            "unreachable", "enotconn", "econnrefused", "offline", "network",
            "rate limit", "x refused", "incomplete", "connection dropped",
        )
        return transient.any { s.contains(it) }
    }

    private fun retryDelayMs(attempts: Int): Long = when (attempts) {
        1 -> 30_000L          // 30 s
        2 -> 120_000L         // 2 min
        else -> 600_000L      // 10 min
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

    /**
     * Pause one item. Its claim is released so the pump stops immediately, but the
     * row, progress and partial file are all kept so [resume] continues where it left off.
     */
    fun pause(ctx: Context, id: String) = synchronized(lock) {
        claimed.remove(id)
        val jobs = loadLocked(ctx)
        val idx = jobs.indexOfFirst { it.id == id }
        if (idx >= 0) {
            jobs[idx] = jobs[idx].copy(paused = true)
            saveLocked(ctx, jobs)
        }
    }

    /** Resume one paused item; it becomes eligible for the pump again. */
    fun resume(ctx: Context, id: String) = synchronized(lock) {
        val jobs = loadLocked(ctx)
        val idx = jobs.indexOfFirst { it.id == id }
        if (idx >= 0) {
            jobs[idx] = jobs[idx].copy(paused = false)
            saveLocked(ctx, jobs)
        }
    }

    /**
     * Pause everything that is pending or in flight. Failed rows are left alone so they
     * stay as visible failures. Returns the ids that were paused, so the service can stop
     * any worker currently running those jobs.
     */
    fun pauseAll(ctx: Context): List<String> = synchronized(lock) {
        val jobs = loadLocked(ctx)
        val ids = ArrayList<String>()
        for (i in jobs.indices) {
            val j = jobs[i]
            if (j.failed || j.paused) continue
            ids.add(j.id)
            jobs[i] = j.copy(paused = true)
        }
        if (ids.isNotEmpty()) saveLocked(ctx, jobs)
        ids
    }

    /** Unpause everything. */
    fun resumeAll(ctx: Context): Int = synchronized(lock) {
        val jobs = loadLocked(ctx)
        var n = 0
        for (i in jobs.indices) {
            val j = jobs[i]
            if (!j.paused) continue
            n++
            jobs[i] = j.copy(paused = false)
        }
        if (n > 0) saveLocked(ctx, jobs)
        n
    }

    fun pausedCount(ctx: Context): Int = synchronized(lock) {
        loadLocked(ctx).count { it.paused }
    }

    /**
     * Drop every failed row, keeping live downloads and paused items untouched.
     * Returns how many were removed.
     */
    fun clearFailed(ctx: Context): Int = synchronized(lock) {
        val jobs = loadLocked(ctx)
        val before = jobs.size
        jobs.removeAll { it.failed }
        val removed = before - jobs.size
        for (id in claimed.toList()) if (jobs.none { it.id == id }) claimed.remove(id)
        if (removed > 0) saveLocked(ctx, jobs)
        removed
    }

    /**
     * Clear the failed flag on every failed row so the pump picks them up again.
     * Returns how many were reset.
     */
    fun retryAllFailed(ctx: Context): Int = synchronized(lock) {
        val jobs = loadLocked(ctx)
        var n = 0
        for (i in jobs.indices) {
            val j = jobs[i]
            if (!j.failed) continue
            n++
            jobs[i] = j.copy(failed = false, failReason = "", progress = 0, partialPath = "")
        }
        if (n > 0) saveLocked(ctx, jobs)
        n
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

    /** Items not yet claimed and not paused — what the UI shows as "queued". */
    fun waiting(ctx: Context): Int = synchronized(lock) {
        loadLocked(ctx).count { it.id !in claimed && !it.failed && !it.paused }
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
                            audioOnly = o.optBoolean("audio", false),
                        ),
                        localPath = o.optString("local", ""),
                        progress = o.optInt("progress", 0),
                        partialPath = o.optString("partial", ""),
                        // Queue files written before 1.9.4 have no id.
                        id = o.optString("id", "").ifEmpty { newId() },
                        failed = o.optBoolean("failed", false),
                        failReason = o.optString("failReason", ""),
                        paused = o.optBoolean("paused", false),
                        // Retry bookkeeping. Not persisting these was the 2.1.4 bug:
                        // every load reset attempts to 0, so MAX_ATTEMPTS never fired
                        // and a retryable failure re-looped every 30 s forever.
                        attempts = o.optInt("attempts", 0),
                        retryAfter = o.optLong("retryAfter", 0L),
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
                    put("audio", j.opts.audioOnly)
                    put("local", j.localPath)
                    put("progress", j.progress)
                    put("partial", j.partialPath)
                    put("failed", j.failed)
                    put("failReason", j.failReason)
                    put("paused", j.paused)
                    put("attempts", j.attempts)
                    put("retryAfter", j.retryAfter)
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

package com.xerxes.videodroid

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-memory diagnostics for the current job (and last finished job), plus a redacted
 * on-disk history.
 *
 * The ring buffer alone was useless the moment the process died — which is exactly when
 * someone wants to know what happened — so every finished job is also appended to
 * `filesDir/diag.log` (rotated at [MAX_BYTES]). No cookies, no tokenized URLs.
 */
object JobDiag {
    private const val RING = 40
    private const val FILE = "diag.log"
    private const val MAX_BYTES = 64 * 1024

    private val lock = Any()
    private val ring = ArrayDeque<String>(RING)
    private var lastFinished: List<String> = emptyList()

    @Volatile
    private var app: Context? = null

    @Volatile var lastFfmpegRc: String = ""
    @Volatile var lastFfmpegTail: String = ""
    @Volatile var lastException: String = ""
    @Volatile var durationKnown: Boolean? = null
    @Volatile var durationValue: Double = 0.0
    @Volatile var lastOpts: FormatOptions? = null
    @Volatile var lastStatusLine: String = ""

    /** Called once from the Application so a finished job can be written without a Context. */
    fun install(context: Context) {
        app = context.applicationContext
    }

    fun beginJob(opts: FormatOptions) {
        synchronized(lock) {
            if (ring.isNotEmpty()) lastFinished = ring.toList()
            ring.clear()
        }
        lastFfmpegRc = ""
        lastFfmpegTail = ""
        lastException = ""
        durationKnown = null
        durationValue = 0.0
        lastOpts = opts
        lastStatusLine = ""
    }

    fun noteStatus(raw: String) {
        val s = redact(raw).trim()
        if (s.isEmpty()) return
        lastStatusLine = s
        synchronized(lock) {
            if (ring.isNotEmpty() && ring.last() == s) return
            if (ring.size >= RING) ring.removeFirst()
            ring.addLast(s)
        }
    }

    fun noteFfmpeg(rc: String, output: String?) {
        lastFfmpegRc = rc
        lastFfmpegTail = redact((output ?: "").takeLast(400))
    }

    fun noteException(msg: String?) {
        lastException = redact(msg ?: "")
    }

    fun noteDuration(known: Boolean, value: Double) {
        durationKnown = known
        durationValue = value
    }

    fun finishJob() {
        synchronized(lock) {
            if (ring.isNotEmpty()) lastFinished = ring.toList()
        }
        persistSnapshot()
    }

    fun lastStatuses(n: Int = 30): List<String> {
        synchronized(lock) {
            val src = if (ring.isNotEmpty()) ring.toList() else lastFinished
            return if (src.size <= n) src else src.takeLast(n)
        }
    }

    // ---- persisted history -------------------------------------------------------

    /** One redacted block per finished job; appended to diag.log with a single rotation. */
    private fun persistSnapshot() {
        val ctx = app ?: return
        val block = snapshotBlock()
        if (block.isBlank()) return
        runCatching {
            val f = File(ctx.filesDir, FILE)
            if (f.isFile && f.length() > MAX_BYTES) {
                val old = File(ctx.filesDir, "$FILE.1")
                if (old.exists()) old.delete()
                f.renameTo(old)
            }
            synchronized(lock) { f.appendText(block) }
        }
    }

    /** The block written for the job that just ended. */
    fun snapshotBlock(): String {
        val opts = lastOpts
        val statuses = synchronized(lock) {
            if (ring.isNotEmpty()) ring.toList() else lastFinished
        }
        if (statuses.isEmpty() && lastException.isEmpty() && lastFfmpegRc.isEmpty()) return ""
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("=== ").append(stamp).append('\n')
        if (opts != null) {
            sb.append("opts: ").append(opts.statusDetail())
                .append(" export=").append(opts.exportEnabled).append('\n')
        }
        sb.append("duration: ").append(
            when (durationKnown) {
                true -> "${durationValue}s"
                false -> "unknown (probe found none)"
                null -> "not reached"
            },
        ).append('\n')
        sb.append("ffmpeg rc: ").append(lastFfmpegRc.ifEmpty { "(none)" }).append('\n')
        if (lastFfmpegTail.isNotBlank()) sb.append("ffmpeg tail: ").append(lastFfmpegTail).append('\n')
        sb.append("exception: ").append(lastException.ifEmpty { "(none)" }).append('\n')
        for (line in statuses.takeLast(30)) sb.append("  ").append(line).append('\n')
        return sb.toString()
    }

    /** Persisted history (newest last), tail-limited for a clipboard payload. */
    fun history(context: Context, maxChars: Int = 6000): String = runCatching {
        val f = File(context.filesDir, FILE)
        if (!f.isFile) "" else {
            val text = f.readText()
            if (text.length <= maxChars) text else text.takeLast(maxChars)
        }
    }.getOrDefault("")

    fun redact(raw: String): String {
        var s = raw
        s = s.replace(Regex("(?im)^.*cookie.*$"), "[redacted cookie line]")
        s = s.replace(Regex("(?i)(cookie|authorization|auth_token|ct0|guest_token)\\s*[:=]\\s*\\S+"), "$1=[redacted]")
        s = s.replace(
            Regex("https?://\\S+"),
        ) { m ->
            val u = m.value
            val lower = u.lowercase()
            if (lower.contains("cookie") || lower.contains("token") || lower.contains("auth") ||
                lower.contains("sig=") || lower.contains("signature") || u.contains("?")
            ) {
                val host = try {
                    val after = u.substringAfter("://")
                    after.substringBefore("/").substringBefore("?")
                } catch (_: Throwable) {
                    "url"
                }
                "https://$host/[redacted]"
            } else {
                u
            }
        }
        return s
    }
}

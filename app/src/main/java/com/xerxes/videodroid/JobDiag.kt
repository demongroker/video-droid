package com.xerxes.videodroid

import java.util.ArrayDeque

/**
 * In-memory diagnostics for the current job (and last finished job).
 * No cookies, no tokenized URLs.
 */
object JobDiag {
    private const val RING = 40

    private val lock = Any()
    private val ring = ArrayDeque<String>(RING)
    private var lastFinished: List<String> = emptyList()

    @Volatile var lastFfmpegRc: String = ""
    @Volatile var lastFfmpegTail: String = ""
    @Volatile var lastException: String = ""
    @Volatile var durationKnown: Boolean? = null
    @Volatile var durationValue: Double = 0.0
    @Volatile var lastOpts: FormatOptions? = null
    @Volatile var lastStatusLine: String = ""

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
    }

    fun lastStatuses(n: Int = 30): List<String> {
        synchronized(lock) {
            val src = if (ring.isNotEmpty()) ring.toList() else lastFinished
            return if (src.size <= n) src else src.takeLast(n)
        }
    }

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

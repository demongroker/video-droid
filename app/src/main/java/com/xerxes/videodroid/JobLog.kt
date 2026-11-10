package com.xerxes.videodroid

/**
 * Per-job log sections.
 *
 * Field report: parallel jobs wrote into one stream (88% → 8% → 89% → 9%), which is unreadable.
 * Each job now gets one block — short id, host only (never a full URL, so no query string or
 * token can leak), start/end, result — followed by that job's own lines.
 *
 * Pure: the caller supplies the data, so the format is unit tested and every block goes through
 * the same redaction.
 */
object JobLog {

    data class Job(
        val id: String,
        val url: String,
        val started: String,
        val ended: String = "",
        val result: String = "",
        val lines: List<String> = emptyList(),
    )

    /** Host only: `https://site/watch/1?t=abc` → `site`. */
    fun hostOf(url: String): String = try {
        val withScheme = if (url.startsWith("http", true)) url else "https://$url"
        java.net.URI(withScheme).host?.removePrefix("www.")?.ifBlank { "(unknown)" } ?: "(unknown)"
    } catch (t: Throwable) {
        "(unknown)"
    }

    fun format(jobs: List<Job>, maxChars: Int = 12000): String {
        if (jobs.isEmpty()) return "(no jobs recorded)"
        val sb = StringBuilder()
        for (job in jobs) {
            sb.append("=== job ").append(job.id.take(6)).append(" · ").append(hostOf(job.url))
            sb.append(" · started ").append(job.started.ifBlank { "?" })
            if (job.ended.isNotBlank()) sb.append(" · ended ").append(job.ended)
            if (job.result.isNotBlank()) sb.append(" · ").append(job.result)
            sb.append(" ===\n")
            if (job.lines.isEmpty()) sb.append("(no lines)\n") else job.lines.forEach { sb.append(it).append('\n') }
            sb.append('\n')
        }
        val text = sb.toString().trimEnd()
        val clipped = if (text.length > maxChars) "…(older lines trimmed)\n" + text.takeLast(maxChars) else text
        return Privacy.redact(clipped)
    }
}

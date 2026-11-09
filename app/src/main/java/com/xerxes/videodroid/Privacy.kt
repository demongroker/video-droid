package com.xerxes.videodroid

/**
 * Redaction for anything that leaves the app in a file or on the clipboard.
 *
 * A crash log is meant to be pasted into a chat, so it must never carry a session or a
 * tracking parameter. Two jobs:
 *  1. drop URL query strings and fragments, keeping scheme + host + path;
 *  2. blank out anything that looks like a credential, whatever its name.
 */
object Privacy {

    /** `https://site/watch/123?token=abc&t=9` → `https://site/watch/123` */
    private val URL_WITH_QUERY =
        Regex("""(https?://[^\s?#"']+)[?#][^\s"']*""", RegexOption.IGNORE_CASE)

    private val SECRET_PAIRS = listOf(
        Regex("""(?i)(auth_token|ct0|csrf_token|sessionid|session_id|bearer|api[_-]?key|password|passwd|secret)\s*[=:]\s*[^\s&"';,]+"""),
        Regex("""(?i)(cookie|set-cookie)\s*:\s*[^\n]+"""),
        Regex("""(?i)authorization\s*:\s*[^\n]+"""),
    )

    fun stripUrls(text: String): String = URL_WITH_QUERY.replace(text) { it.groupValues[1] }

    fun redact(text: String): String {
        var out = stripUrls(text)
        for (r in SECRET_PAIRS) {
            out = r.replace(out) { m ->
                val key = m.value.substringBefore('=').substringBefore(':')
                "$key=<redacted>"
            }
        }
        return out
    }
}

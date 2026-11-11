package com.xerxes.videodroid

/**
 * Chooses between several media URLs found on a scanned page.
 *
 * Field bug: the page fallback picked a 5 MB `video.twimg.com` ad instead of the main video.
 * Rules, in order:
 *  1. drop candidates that are clearly clips (under [NotificationState.CLIP_MAX_BYTES] or
 *     [NotificationState.CLIP_MAX_SECONDS]) — unless every candidate is a clip;
 *  2. prefer a candidate on the page's own host (the main video is served from the site, an ad
 *     usually is not);
 *  3. otherwise take the largest known size, then the longest duration, then the first one.
 *
 * Pure and side-effect free: the caller does the HEAD requests and passes what it learned.
 */
object CandidatePicker {

    data class Candidate(
        val url: String,
        val sizeBytes: Long = -1L,
        val durationSeconds: Double = -1.0,
        val sameHostAsPage: Boolean = false,
    )

    private fun isClip(c: Candidate): Boolean {
        if (c.sizeBytes in 1 until NotificationState.CLIP_MAX_BYTES) return true
        if (c.durationSeconds in 0.1..NotificationState.CLIP_MAX_SECONDS) return true
        return false
    }

    fun choose(candidates: List<Candidate>): Candidate? {
        if (candidates.isEmpty()) return null
        val full = candidates.filterNot { isClip(it) }
        val pool = if (full.isEmpty()) candidates else full
        if (pool.size == 1) return pool.first()

        val sameHost = pool.filter { it.sameHostAsPage }
        val shortlist = if (sameHost.isNotEmpty()) sameHost else pool

        return shortlist.sortedWith(
            compareByDescending<Candidate> { it.sizeBytes }
                .thenByDescending { it.durationSeconds },
        ).firstOrNull()
    }

    /**
     * 2.5.1 field fix: when the only things on offer are small clips from a foreign CDN, the page
     * almost certainly plays its real video through an embedded player that the static sniffer
     * cannot see. Auto-downloading one of those clips is worse than doing nothing, so the caller
     * should offer the Scan page sheet instead.
     *
     * True when: every candidate is a clip, and none of them is served from the page's own host.
     */
    fun onlyTinyForeign(candidates: List<Candidate>): Boolean =
        candidates.isNotEmpty() &&
            candidates.all { isClip(it) } &&
            candidates.none { it.sameHostAsPage }
}

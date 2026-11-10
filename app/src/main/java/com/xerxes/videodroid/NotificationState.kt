package com.xerxes.videodroid

/**
 * What the download notification is allowed to say.
 *
 * Field bug (2.3.6): after a duplicate skip and a cancel, the shade kept showing
 * "Downloading 1 · Waiting for connection… (resumes automatically)" with nothing running. The
 * notification is a mirror of the queue, so it has to be derived from the queue — never from a
 * remembered network event.
 *
 * `null` means "nothing to show": the caller clears the notification and stops the foreground
 * service.
 */
object NotificationState {

    /** Below this, a candidate found on a scanned page is treated as a clip/ad. */
    const val CLIP_MAX_BYTES = 3L * 1024 * 1024
    const val CLIP_MAX_SECONDS = 30.0

    /**
     * @param inFlight jobs currently transferring
     * @param waiting  jobs that could still run (not failed, not paused, not cancelled)
     * @param online   what ConnectivityManager says right now
     */
    fun text(inFlight: Int, waiting: Int, online: Boolean): String? {
        val total = inFlight + waiting
        if (total <= 0) return null
        if (!online) return "Waiting for connection… (resumes automatically)"
        if (inFlight <= 0) return "Queued · " + if (total == 1) "1 download" else "$total downloads"
        return if (total == 1) "Downloading 1" else "Downloading $total"
    }

    /**
     * True when the app has active work but nothing in the queue — the state that has to be
     * reconciled on launch (stale foreground service, stale WorkManager job).
     */
    fun needsReconcile(runnableJobs: Int, workActive: Boolean): Boolean =
        runnableJobs <= 0 && workActive
}

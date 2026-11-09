package com.xerxes.videodroid

/**
 * When may the foreground download service terminate?
 *
 * Kept as a pure object so the rule is unit tested without an Android service. The bug this
 * fixes: the service counted **failed and paused rows** as work, so after a batch of failures
 * (or a Cancel) it stayed in the foreground with a notification stuck on "Cancelling…" and
 * live Pause/Cancel buttons, while the Queue screen correctly showed nothing running.
 */
object ServiceLifecycle {

    /**
     * True when the service has nothing left that could actually run: no claimed item and no
     * waiting row. Rows parked as *failed*, *paused*, or **cancelled** do not count — they
     * need an explicit tap to run again, so keeping a foreground service alive for them is
     * what leaves the notification hanging.
     */
    fun shouldStop(inFlight: Int, waiting: Int): Boolean = inFlight <= 0 && waiting <= 0

    /**
     * Text for the shade when everything is being stopped. Empty string means "keep whatever
     * is shown" — used while a worker is still unwinding.
     */
    fun stopMessage(pausedRows: Int): String =
        if (pausedRows > 0) "Stopped · $pausedRows paused in the queue" else "Stopped"
}

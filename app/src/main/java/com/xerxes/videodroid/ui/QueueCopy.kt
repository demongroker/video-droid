package com.xerxes.videodroid.ui

import com.xerxes.videodroid.ErrorText

/** Semantic tone of a queue row's state line. The UI maps tone → colour; copy never carries one. */
enum class QueueTone { NEUTRAL, GOOD, WARN, BAD, BUSY }

data class QueueStateLine(val text: String, val tone: QueueTone)

/**
 * Pure copy + action rules for one queue row.
 *
 * The wording is a contract with the field logs and with the owner: the failure reason lives in
 * the STATE line (never in the title), "Waiting for connection" is the offline wording, and a
 * failed row says Remove rather than Cancel. Keeping it here makes those rules unit-testable
 * instead of buried in a RecyclerView binder.
 */
object QueueCopy {

  fun state(
    failed: Boolean,
    failReason: String,
    url: String,
    paused: Boolean,
    running: Boolean,
    online: Boolean,
    progress: Int,
  ): QueueStateLine = when {
    failed -> QueueStateLine(
      "Failed — " + ErrorText.human(failReason.ifBlank { "tap retry" }, url),
      QueueTone.BAD,
    )
    paused -> QueueStateLine(
      if (progress > 0) "Paused · $progress%" else "Paused",
      QueueTone.WARN,
    )
    running -> QueueStateLine(
      if (progress > 0) "Downloading $progress%" else "Downloading…",
      QueueTone.BUSY,
    )
    !online -> QueueStateLine("Waiting for connection", QueueTone.WARN)
    else -> QueueStateLine(
      if (progress > 0) "Queued · $progress%" else "Queued",
      QueueTone.NEUTRAL,
    )
  }

  /** Quality tag + live speed/ETA, never the state (that is its own line). */
  fun detail(quality: String, liveDetail: String): String =
    listOf(quality, liveDetail).filter { it.isNotBlank() }.joinToString(" · ")

  fun title(displayName: String, url: String): String = displayName.ifBlank { url }.trim()

  /** Pause is meaningless on a failed row. */
  fun showPause(failed: Boolean): Boolean = !failed

  fun pauseLabel(paused: Boolean): String = if (paused) "Resume" else "Pause"

  /** Same call, clearer word: a failed row is removed, a live one is cancelled. */
  fun cancelLabel(failed: Boolean): String = if (failed) "Remove" else "Cancel"

  /** Retry only when the error is actually retryable — "no video in this post" is not. */
  fun showRetry(failed: Boolean, failReason: String): Boolean =
    failed && ErrorText.retryable(failReason)

  /** A progress bar is worth showing once there is progress or an active transfer. */
  fun showProgress(progress: Int, running: Boolean): Boolean = progress > 0 || running

  fun bulkPauseLabel(pausedCount: Int): String =
    if (pausedCount > 0) "Resume all ($pausedCount)" else "Pause all"
}

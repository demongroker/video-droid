package com.xerxes.videodroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Keeps the persisted download queue draining after the process dies.
 *
 * The queue itself already lives in `filesDir/download_queue.json`, which
 * survives process death and app updates. What does not survive is the
 * foreground service that was driving it, so WorkManager re-launches that when
 * there is still work left.
 *
 * Partial files are kept on disk (yt-dlp writes `<name>.part` and resumes with
 * `continuedl`), so a restarted job continues from where it stopped instead of
 * downloading from zero — where the server supports range requests.
 */
class QueueResumeWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val next = DownloadQueue.peek(ctx) ?: return Result.success()
        if (DownloadService.isRunning) return Result.success()
        // Android 12+ can refuse a foreground-service start from the background;
        // never let that crash the worker — the next app launch retries.
        runCatching {
            DownloadService.start(ctx, next.url, next.opts, next.localPath)
        }
        return Result.success()
    }

    companion object {
        const val NAME = "videodroid-queue-resume"

        /** Unique work: enqueueing twice does not stack resume attempts. */
        fun schedule(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<QueueResumeWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}

/** Re-arm the queue after a reboot, when nothing else has started us. */
class QueueResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> QueueResumeWorker.schedule(context)
        }
    }
}

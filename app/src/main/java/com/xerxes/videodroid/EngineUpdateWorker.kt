package com.xerxes.videodroid

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Stage 5: refresh the download engine once a week, on an unmetered network only — the owner
 * is often on ship Wi-Fi, so a silent multi-megabyte fetch on cellular is not acceptable.
 */
class EngineUpdateWorker(ctx: Context, params: WorkerParameters) :
    CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val outcome = EngineUpdater.update(applicationContext)
        JobDiag.noteStatus(
            "engine: " + (outcome.getOrNull() ?: ("update failed — " + (outcome.exceptionOrNull()?.message ?: "unknown"))),
        )
        return Result.success()
    }

    companion object {
        const val NAME = "videodroid-engine-update"

        fun schedule(ctx: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<EngineUpdateWorker>(7, TimeUnit.DAYS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .build(),
                    )
                    .build()
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
        }
    }
}

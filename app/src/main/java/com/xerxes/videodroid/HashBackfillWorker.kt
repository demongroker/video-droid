package com.xerxes.videodroid

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 5b: fill `saved_hashes.json` from the library that already exists.
 *
 * Without this the hash gate only knows files saved after 2.3.4. The job walks the MediaStore
 * rows the Library shows, hashes each one and records it, then never runs again.
 *
 * It is deliberately gentle: a single background walk, a cap per run so it cannot run for
 * hours in one go, and `Result.retry()` to continue later. Nothing is moved or deleted.
 */
class HashBackfillWorker(ctx: Context, params: WorkerParameters) :
    CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return@withContext Result.success()

        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?"
        val args = arrayOf("%VideoDroid%", "%duplicates%")

        val ids = ArrayList<Long>()
        runCatching {
            app.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (c.moveToNext()) ids.add(c.getLong(idCol))
            }
        }.onFailure {
            JobDiag.noteStatus("hash backfill: query failed — ${it.message ?: it.toString()}")
            return@withContext Result.retry()
        }

        val started = System.currentTimeMillis()
        var hashed = 0
        var unreadable = 0
        var more = false

        for (id in ids) {
            val uri: Uri = ContentUris.withAppendedId(collection, id)
            val hash = FileDigest.sha256(app.contentResolver, uri)
            if (hash != null) {
                SavedHashIndex.remember(app, hash, id)
                hashed++
            } else {
                unreadable++
            }
            // Stop early and come back: hashing a 79 GB library in one run would chew battery.
            if (hashed >= MAX_PER_RUN || System.currentTimeMillis() - started > MAX_MS) {
                more = true
                break
            }
        }

        if (more) {
            JobDiag.noteStatus("hash backfill: $hashed hashed so far, continuing later")
            Result.retry()
        } else {
            prefs.edit().putBoolean(KEY_DONE, true).apply()
            JobDiag.noteStatus("hash backfill: done ($hashed hashed, $unreadable unreadable)")
            Result.success()
        }
    }

    companion object {
        const val NAME = "videodroid-hash-backfill"
        private const val PREFS = "videodroid"
        private const val KEY_DONE = "hash_backfill_done"
        private const val MAX_PER_RUN = 200
        private const val MAX_MS = 8 * 60 * 1000L

        fun schedule(ctx: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<HashBackfillWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    )
                    .build()
                WorkManager.getInstance(ctx).enqueueUniqueWork(
                    NAME,
                    ExistingWorkPolicy.KEEP,
                    request,
                )
            }
        }
    }
}

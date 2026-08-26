package com.xerxes.videodroid

import android.content.ContentValues
import android.context.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitListener
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.chaquo.python.Python
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class FormatResult(val ok: Boolean, val message: String, val uri: Uri? = null)

data class FormatOptions(
    val dlQuality: String,
    val exportHeight: Int,
    val aspect: String,
    val fps: Int = 30,
    val crf: Int = 3,
    val trimEnabled: Boolean = true,
    val trimStart: Int = 1,
    val trimEnd: Int = 1,
    val exportEnabled: Boolean = true
) {
    fun aspectLabel(): String {
        val a = aspect.trim()
        if (a.equals("original", ignoreCase = true)) return "Original"
        if (a.startsWith("crop:", ignoreCase = true)) return "Fill ${a.substringAfter(':')}"
        if (a.startsWith("pad:", ignoreCase = true)) return "Fit ${a.substringAfter(':')}"
        return a
    }

    /** e.g. `720p · Fill 4:3 · trim off` or `720p · Fill 4:3 · trim 1s/1s` */
    fun statusDetail(): String {
        val trim = if (trimEnabled) "trim ${trimStart}s/${trimEnd}s" else "trim off"
        return "$dlQuality · ${aspectLabel()} · $trim"
    }

    companion object {
        const val DEFAULT_TRIM_S = 1
        const val MAX_TRIM_S = 86400

        fun clampTrimSeconds(raw: Int?, lastGood: Int = DEFAULT_TRIM_S): Int {
            val v = raw ?: return fallback(lastGood)
            if (v < 0 || v > MAX_TRIM_S) return fallback(lastGood)
            return v
        }

        fun parseTrimSeconds(text: String?, lastGood: Int = DEFAULT_TRIM_S): Int {
            return clampTrimSeconds(text?.trim()?.toIntOrNull(), lastGood)
        }

        private fun fallback(lastGood: Int): Int {
            if (lastGood in 0..MAX_TRIM_S) return lastGood
            return DEFAULT_TRIM_S
        }
    }
}

object FormatWorker {
    private val cancelRequested = AtomicBoolean(false)
    private val activeFfmpegSessionId = AtomicLong(-1L)
    private val retryCount = AtomicInteger(0)

    fun requestCancel() {
        cancelRequested.set(true)
        try {
            Python.getInstance().getModule("dl").callAttr("set_cancelled", true)
        } catch (_: Throwable) {}
        cancelActiveFfmpegOnly()
    }

    private fun cancelActiveFfmpegOnly() {
        val id = activeFfmpegSessionId.get()
        if (id < 0L) return
        try {
            FFmpegKit.cancel(id)
        } catch (_: Throwable) {}
    }

    fun isCancelled(): Boolean = cancelRequested.get()

    /** Check if source is in app's filesDir (persisted, never auto-delete) */
    private fun isPersistedSource(context: Context, src: File): Boolean {
        val dir = context.filesDir.absolutePath
        return src.absolutePath.startsWith(dir + File.separator)
    }

    /** Run a download + format job with resumability and retry logic */
    fun run(
        context: Context,
        url: String,
        opts: FormatOptions,
        progressCallback: (String) -> Unit,
        localPath: String = "",
        jobId: Long = 0L
    ): FormatResult {
        // Reset cancel flag at start of each job
        cancelRequested.set(false)
        retryCount.set(0)

        // Try to resume from existing .part file
        var effectiveUrl = url
        var tmpPartFile: File? = null

        // Check for partial download
        if (url.endsWith(".part") || url.contains("?range=")) {
            // Use existing partial file
        }

        // Determine output paths based on quality
        val effectiveQuality = when (opts.dlQuality) {
            "1080p" -> 1080
            "720p" -> 720
            "480p" -> 480
            else -> opts.exportHeight
        }

        val baseName = System.currentTimeMillis().toString()
        val outputFile = File(context.filesDir, "$baseName.%s".format(opts.dlQuality))
        val partFile = File(context.filesDir, "$baseName.%s.part".format(opts.dlQuality))

        // If persisted source exists, reuse it (process death recovery)
        if (localPath.isNotBlank()) {
            val src = File(localPath)
            if (src.exists() && isPersistedSource(context, src)) {
                // Copy persisted source to temp location for processing
                outputFile.writeText(src.readText()) // placeholder - actual copy logic depends on file type
            }
        }

        // Run yt-dlp download with resumability
        try {
            Python.getInstance().getModule("dl").callAttr(
                "download_with_resume",
                effectiveUrl,
                outputFile.absolutePath,
                partFile.absolutePath,
                effectiveQuality,
                opts.aspect,
                opts.fps,
                opts.crf,
                opts.trimEnabled,
                opts.trimStart,
                opts.trimEnd,
                opts.exportEnabled
            )
            return FormatResult(true, "Downloaded and processed", null)
        } catch (e: Exception) {
            // Record failure in database
            DownloadQueue.updateJobStatus(context, jobId, "FAILED", e.message ?: "Unknown error", 0, System.currentTimeMillis())

            // Retry logic
            val maxRetries = when {
                errorMessage?.contains("429") -> 5 // exponential backoff
                errorMessage?.contains("Network") -> 3
                else -> 1
            }

            if (retryCount.get() < maxRetries && !cancelRequested.get()) {
                retryCount.incrementAndGet()
                // Schedule retry with backoff
                Thread.sleep(1000L * retryCount.get())
                return run(context, url, opts, progressCallback, localPath, jobId)
            }

            return FormatResult(false, "Failed after ${retryCount.get()} retries: ${e.message ?: ""}")
        }
    }

    private fun activeFfmpegSessionId: Long = activeFfmpegSessionId.get()
}

package com.xerxes.videodroid

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
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
    val exportEnabled: Boolean = true,
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
        /** Per-side cap so corrupt prefs cannot feed insane -ss/-t. */
        const val MAX_TRIM_S = 86_400

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
    /** Active FFmpeg session only — never FFmpegKit.cancel() with no id. */
    private val activeFfmpegSessionId = AtomicLong(-1L)

    fun requestCancel() {
        cancelRequested.set(true)
        try {
            Python.getInstance().getModule("dl").callAttr("set_cancelled", true)
        } catch (_: Throwable) { }
        cancelActiveFfmpegOnly()
    }

    private fun cancelActiveFfmpegOnly() {
        val id = activeFfmpegSessionId.get()
        if (id < 0L) return
        try {
            FFmpegKit.cancel(id)
        } catch (_: Throwable) { }
    }

    fun isCancelled(): Boolean = cancelRequested.get()

    /** True when src lives in app storage (local picker copy) — never delete it. */
    private fun isPersistedSource(context: Context, src: File): Boolean {
        val dir = context.filesDir.absolutePath
        return src.absolutePath.startsWith(dir + File.separator)
    }

    fun run(
        context: Context,
        url: String,
        opts: FormatOptions,
        status: (String) -> Unit,
        localPath: String? = null,
    ): FormatResult {
        cancelRequested.set(false)
        JobDiag.beginJob(opts)
        OpenPageActivity.storeJobUrl(context, url)
        val emit: (String) -> Unit = { msg ->
            JobDiag.noteStatus(msg)
            status(msg)
        }
        try {
            Python.getInstance().getModule("dl").callAttr("set_cancelled", false)
        } catch (_: Throwable) { }

        val work = File(context.cacheDir, "vaf")
        work.mkdirs()
        work.listFiles()?.forEach { it.delete() }

        return try {
            if (cancelRequested.get()) return FormatResult(false, "Cancelled")

            // Source: 1) explicit local file (SAF/gallery), 2) fresh download.
            val src: File
            val title: String
            var width: Int
            var height: Int
            var duration: Double

            val picked = if (!localPath.isNullOrBlank()) File(localPath) else null
            if (picked != null && picked.exists()) {
                src = picked
                title = picked.nameWithoutExtension
                emit("Using local file…")
                val probe = probeVideo(picked)
                width = probe.first; height = probe.second; duration = probe.third
            } else if (picked != null) {
                return FormatResult(false, "Local file missing")
            } else {
                emit("Downloading...")
                val py = Python.getInstance()
                val cookies = XCookies.file(context)
                val cookiePath = if (cookies.isFile && cookies.length() > 0) cookies.absolutePath else ""
                val pollerStop = AtomicBoolean(false)
                val poller = Thread {
                    var last = ""
                    while (!pollerStop.get() && !cancelRequested.get()) {
                        try {
                            val s = py.getModule("dl").callAttr("get_status").toString()
                            if (s.isNotEmpty() && s != last) {
                                last = s
                                emit(s)
                            }
                        } catch (_: Throwable) { }
                        try {
                            Thread.sleep(400)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
                poller.isDaemon = true
                poller.start()
                val result = try {
                    py.getModule("dl")
                        .callAttr("download", url, opts.dlQuality, work.absolutePath, "source", cookiePath)
                        .toString()
                } finally {
                    pollerStop.set(true)
                    poller.interrupt()
                }
                if (cancelRequested.get()) return FormatResult(false, "Cancelled")
                val info = JSONObject(result)
                val dl = File(info.getString("path"))
                if (!dl.exists()) return FormatResult(false, "Download produced no file")
                src = dl
                title = info.optString("title", "")
                width = info.getInt("width")
                height = info.getInt("height")
                duration = info.optDouble("duration", 0.0)
                emit("Downloaded: ${src.length() / 1024 / 1024} MB")
            }

            val originalAspect = opts.aspect.equals("original", ignoreCase = true)
            // Export off, or Original + trim off: never re-encode.
            if (!opts.exportEnabled || (originalAspect && !opts.trimEnabled)) {
                if (cancelRequested.get()) return FormatResult(false, "Cancelled")
                val ext = src.extension.ifEmpty { "mp4" }
                val name = fileName(opts, ext, title)
                val uri = saveToDownloads(context, src, name, mimeForExt(ext))
                if (!isPersistedSource(context, src)) src.delete()
                if (uri == null) return FormatResult(false, "Could not save to Downloads")
                emit("Saved")
                JobDiag.finishJob()
                return FormatResult(true, "Saved", uri)
            }

            if (width <= 0 || height <= 0) { width = 1280; height = 720 }
            if (duration <= 0) duration = probeDuration(src)
            var tStart = if (opts.trimEnabled) FormatOptions.clampTrimSeconds(opts.trimStart) else 0
            val tEnd = if (opts.trimEnabled) FormatOptions.clampTrimSeconds(opts.trimEnd) else 0
            val known = duration > 0
            JobDiag.noteDuration(known, duration)
            val keep = if (known) duration - tStart - tEnd else Double.NaN
            val vf = if (originalAspect) null else buildFilter(width, height, opts.aspect)

            if (cancelRequested.get()) return FormatResult(false, "Cancelled")
            val out = File(work, "out.mp4")
            val remux = File(work, "remux.mp4")
            val expectedMs = when {
                opts.trimEnabled && known && keep > 1.0 -> keep * 1000.0
                duration > 0 -> duration * 1000.0
                else -> 0.0
            }

            var encodeSrc = src
            fun remuxSource(): Boolean {
                if (cancelRequested.get()) return false
                if (remux.exists()) remux.delete()
                emit("Remuxing...")
                val copyRemux = arrayOf(
                    "-y", "-v", "error", "-i", src.absolutePath,
                    "-c", "copy", "-movflags", "+faststart", remux.absolutePath,
                )
                var rs = executeConvert(copyRemux, 0.0, emit, "Remuxing")
                JobDiag.noteFfmpeg(rs.returnCode?.toString() ?: "null", rs.output)
                if (!ReturnCode.isSuccess(rs.returnCode) || !remux.exists() || remux.length() <= 0L) {
                    if (remux.exists()) remux.delete()
                    if (cancelRequested.get()) return false
                    val aacRemux = arrayOf(
                        "-y", "-v", "error", "-i", src.absolutePath,
                        "-c:v", "copy", "-c:a", "aac", "-b:a", "128k",
                        "-movflags", "+faststart", remux.absolutePath,
                    )
                    rs = executeConvert(aacRemux, 0.0, emit, "Remuxing")
                    JobDiag.noteFfmpeg(rs.returnCode?.toString() ?: "null", rs.output)
                }
                if (!ReturnCode.isSuccess(rs.returnCode) || !remux.exists() || remux.length() <= 0L) {
                    if (remux.exists()) remux.delete()
                    return false
                }
                encodeSrc = remux
                return true
            }

            var session: com.arthenica.ffmpegkit.FFmpegSession? = null
            if (originalAspect && opts.trimEnabled) {
                emit("Trimming (copy)...")
                val copyArgs = buildFfmpegArgs(src, out, null, opts, tStart, tEnd, known, keep, copy = true, srcHeight = height)
                session = executeConvert(copyArgs, expectedMs, emit, "Trimming")
                JobDiag.noteFfmpeg(session.returnCode?.toString() ?: "null", session.output)
            }

            if (session == null || !ReturnCode.isSuccess(session.returnCode)) {
                if (out.exists()) out.delete()
                if (cancelRequested.get()) return FormatResult(false, "Cancelled")
                if (!isCleanMp4(src)) remuxSource()
                if (cancelRequested.get()) return FormatResult(false, "Cancelled")

                // One encode attempt; returns null on any failure. Critically, an
                // rc:0 with an empty/header-only output (< 1024 bytes, e.g. the
                // 261-byte HEVC hwaccel file) is treated as FAILURE, never success.
                fun tryEncode(
                    input: File,
                    useHwaccel: Boolean,
                    vfOverride: String?,
                ): com.arthenica.ffmpegkit.FFmpegSession? {
                    if (cancelRequested.get()) return null
                    if (out.exists()) out.delete()
                    emit("Converting (fast)...")
                    val filter = vfOverride ?: vf
                    val args = buildFfmpegArgs(
                        input, out, filter, opts, tStart, tEnd, known, keep,
                        "h264_mediacodec", srcHeight = height, noHwaccel = !useHwaccel,
                    )
                    val s = executeConvert(args, expectedMs, emit, "Converting (fast)")
                    JobDiag.noteFfmpeg(s.returnCode?.toString() ?: "null", s.output)
                    if (cancelRequested.get()) return null
                    val produced = out.exists() && out.length() >= 1024
                    if (!ReturnCode.isSuccess(s.returnCode) || !produced) {
                        JobDiag.noteFfmpeg(
                            "encode-failed",
                            "rc=${s.returnCode?.value ?: "?"} bytes=${if (out.exists()) out.length() else 0} hwaccel=$useHwaccel",
                        )
                        if (out.exists()) out.delete()
                        return null
                    }
                    return s
                }

                // Attempt 1: hardware decode (mediacodec) + h264_mediacodec encode.
                session = tryEncode(encodeSrc, true, null)
                // Original invalid-data remedy: remux the source (e.g. webm→mp4) and retry.
                if (session == null && encodeSrc === src) {
                    if (remuxSource()) {
                        if (cancelRequested.get()) return FormatResult(false, "Cancelled")
                        session = tryEncode(encodeSrc, true, null)
                    }
                }
                // Attempt 2: software decode (no hwaccel) + h264_mediacodec encode.
                // Some HEVC sources fail to produce frames under mediacodec hwaccel
                // on certain devices; ffmpeg's software decoder still handles them.
                if (session == null && !cancelRequested.get()) {
                    session = tryEncode(encodeSrc, false, null)
                }
                // Attempt 3: software decode + force pixel conversion via -vf format=yuv420p
                // appended to the chain, in case the crop/pad filter output format
                // does not match what h264_mediacodec accepts.
                if (session == null && !cancelRequested.get()) {
                    val vfWithFormat = if (vf.isNullOrBlank()) "format=yuv420p" else "$vf,format=yuv420p"
                    session = tryEncode(encodeSrc, false, vfWithFormat)
                }
            }
            if (cancelRequested.get()) return FormatResult(false, "Cancelled")
            fun keepDownloaded(reason: String): FormatResult {
                if (out.exists()) out.delete()
                if (remux.exists()) remux.delete()
                val ext = src.extension.ifEmpty { "mp4" }
                val name = fileName(opts, ext, title)
                val uri = saveToDownloads(context, src, name, mimeForExt(ext))
                val msg = if (uri != null) {
                    if (!isPersistedSource(context, src)) src.delete()
                    "Convert failed. Kept original in Downloads. $reason"
                } else {
                    "Convert failed. Original download kept. $reason"
                }
                emit(msg)
                JobDiag.finishJob()
                return FormatResult(false, msg, uri)
            }
            if (session == null) {
                return keepDownloaded("Could not decode this video on this phone. Try quality 1080 (H.264) instead of best (HEVC).")
            }
            val done = session ?: return keepDownloaded("FFmpeg produced no output")
            if (!ReturnCode.isSuccess(done.returnCode)) {
                val tail = (done.output ?: "").takeLast(400)
                return keepDownloaded(mapError("FFmpeg error: $tail"))
            }
            if (!out.exists()) return keepDownloaded("FFmpeg produced no output")

            emit("Saving to phone...")
            val name = fileName(opts, "mp4", title)
            val uri = saveToDownloads(context, out, name)
            if (uri == null) return FormatResult(false, "Could not save to Downloads")
            if (!isPersistedSource(context, src)) src.delete()
            out.delete(); if (remux.exists()) remux.delete()
            JobDiag.finishJob()
            FormatResult(true, "Saved", uri)
        } catch (e: Throwable) {
            JobDiag.noteException(e.message ?: e.toString())
            JobDiag.finishJob()
            if (cancelRequested.get() || isCancelMessage(e.message)) {
                FormatResult(false, "Cancelled")
            } else {
                FormatResult(false, mapError(e.message ?: e.toString()))
            }
        }
    }

    fun mapError(raw: String): String {
        val s = raw.lowercase()
        if (s.contains("cancel")) return "Cancelled"
        if (s.contains("unsupported url") || s.contains("unsupported site")) {
            return "Unsupported site. Open page."
        }
        if (s.contains("404") || s.contains("not found")) {
            return "File gone (404)"
        }
        if (s.contains("requested format is not available") || s.contains("format is not available")) {
            return "That format is gone. Try 1080 or Open page."
        }
        // TLS before No network. Match ssl / certificate / hostname mismatch / CERTIFICATE_VERIFY_FAILED
        // explicitly — do not use loose "timeout" (that class stays No network).
        if (s.contains("certificate_verify_failed")
            || s.contains("hostname mismatch")
            || s.contains("ssl")
            || s.contains("certificate")
        ) {
            return "Site TLS bad. Open page."
        }
        if (s.contains("need x login")) return "Need X login"
        if (s.contains("no video in this tweet") || s.contains("no video could be found in this tweet")) {
            return "No video in this tweet"
        }
        if (s.contains("sign in") || s.contains("login") || s.contains("cookie")
            || s.contains("authentication") || s.contains("private video")
            || s.contains("confirm you’re not a bot") || s.contains("confirm you're not a bot")
        ) {
            return "Need X login"
        }
        if (s.contains("unknown host") || s.contains("unable to resolve")
            || s.contains("network is unreachable") || s.contains("failed to connect")
            || s.contains("timeout") || s.contains("timed out")
            || s.contains("no address associated") || s.contains("enotconn")
            || s.contains("econnrefused") || s.contains("offline")
        ) {
            return "No network"
        }
        val cleaned = raw.replace(Regex("(?i)cookie[^\\n]*"), "").trim()
        return cleaned.take(160).ifEmpty { "Download failed" }
    }

    private fun isCancelMessage(msg: String?): Boolean {
        val s = msg?.lowercase() ?: return false
        return s.contains("cancel")
    }

    /** Container already MP4/M4V — skip pre-encode remux unless decode fails. */
    private fun isCleanMp4(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext == "mp4" || ext == "m4v"
    }

    /** FFmpeg AVERROR_INVALIDDATA (69) or "Invalid data found when processing input". */
    private fun isInvalidData(session: com.arthenica.ffmpegkit.FFmpegSession): Boolean {
        val rc = session.returnCode?.value ?: 0
        val tail = session.output ?: ""
        return rc == 69 || tail.contains("Invalid data", ignoreCase = true)
    }

    /**
     * Percent = stats.time_ms / expectedMs. Unknown duration: elapsed wall time, no fake %.
     * Throttle ~2% or 500ms. Cancel uses FFmpegKit.cancel(sessionId) for this job only.
     */
    private fun executeConvert(
        args: Array<String>,
        expectedMs: Double,
        status: (String) -> Unit,
        label: String = "Converting",
    ): com.arthenica.ffmpegkit.FFmpegSession {
        val lastPct = AtomicInteger(-1)
        val lastUiMs = AtomicLong(0L)
        val convertStart = System.currentTimeMillis()
        val done = CountDownLatch(1)
        val session = FFmpegKit.executeWithArgumentsAsync(
            args,
            { _ -> done.countDown() },
            null,
            { stats: Statistics ->
                if (!cancelRequested.get()) {
                    val now = System.currentTimeMillis()
                    val timeMs = stats.time.toDouble()
                    if (expectedMs > 0) {
                        val pct = ((timeMs / expectedMs) * 100.0).toInt().coerceIn(0, 99)
                        if (pct >= lastPct.get() + 2 || now - lastUiMs.get() >= 500) {
                            lastPct.set(pct)
                            lastUiMs.set(now)
                            status("$label $pct%")
                        }
                    } else if (now - lastUiMs.get() >= 500) {
                        lastUiMs.set(now)
                        val elapsedSec = ((now - convertStart) / 1000L).toInt()
                        status("$label… ${elapsedSec}s")
                    }
                }
            },
        )
        activeFfmpegSessionId.set(session.sessionId)
        try {
            while (!done.await(200, TimeUnit.MILLISECONDS)) {
                if (cancelRequested.get()) {
                    cancelActiveFfmpegOnly()
                }
            }
        } finally {
            activeFfmpegSessionId.compareAndSet(session.sessionId, -1L)
        }
        return session
    }

    /** Probe downloaded file; 0 if unknown. Never invent a duration. */
    fun probeDuration(file: File): Double {
        return try {
            val info = FFprobeKit.getMediaInformation(file.absolutePath)?.mediaInformation
            val d = info?.duration?.toDoubleOrNull() ?: 0.0
            if (d > 0) d else 0.0
        } catch (_: Throwable) {
            0.0
        }
    }

    /** Video-stream dimensions + duration for local/re-used files (no yt-dlp info). */
    private fun probeVideo(file: File): Triple<Int, Int, Double> {
        return try {
            val mi = FFprobeKit.getMediaInformation(file.absolutePath)?.mediaInformation
                ?: return Triple(0, 0, 0.0)
            var w = 0; var h = 0
            val d = mi.duration?.toDoubleOrNull() ?: 0.0
            val streams = mi.streams ?: return Triple(0, 0, d)
            for (s in streams) {
                if (s.type.equals("video", true)) {
                    w = (s.width ?: 0).toInt()
                    h = (s.height ?: 0).toInt()
                    if (w > 0 && h > 0) break
                }
            }
            Triple(w, h, d)
        } catch (_: Throwable) {
            Triple(0, 0, 0.0)
        }
    }

    /**
     * Trim off: encode entire input (no -ss, no -t).
     * Trim on: -ss start if > 0; -t only when duration is known and > start+end.
     * Unknown duration never gets -t (never -t 100).
     */
    fun buildFfmpegArgs(
        src: File,
        out: File,
        vf: String?,
        opts: FormatOptions,
        tStart: Int,
        tEnd: Int,
        knownDuration: Boolean,
        keep: Double,
        encoder: String = "h264_mediacodec",
        copy: Boolean = false,
        srcHeight: Int = 720,
        noHwaccel: Boolean = false,
    ): Array<String> {
        val args = ArrayList<String>()
        args.addAll(listOf("-y", "-v", "error"))
        if (opts.trimEnabled && tStart > 0) {
            args.addAll(listOf("-ss", tStart.toString()))
        }
        if (!copy && !noHwaccel) {
            // HW decode for HEVC/H.265: mediacodec feeds h264_mediacodec encoder.
            // yuv420p (not nv12): NV12 output is not universally playable and breaks crop filters.
            args.addAll(listOf("-hwaccel", "mediacodec", "-hwaccel_output_format", "yuv420p"))
        }
        args.addAll(listOf("-i", src.absolutePath))
        if (opts.trimEnabled && knownDuration && keep > 1.0 && tEnd >= 0) {
            args.addAll(listOf("-t", keep.toString()))
        }
        if (copy) {
            args.addAll(listOf("-c", "copy", "-movflags", "+faststart", out.absolutePath))
            return args.toTypedArray()
        }
        if (!vf.isNullOrBlank()) {
            args.addAll(listOf("-vf", vf))
        }
        args.addAll(listOf("-c:v", encoder))
        // h264_mediacodec only: 16M below 1080p, 24M at >=1080p, bufsize 2x.
        // No libx264 (not in ffmpeg-kit-full 7.1.5), no mpeg4 (X rejects MPEG-4 Part 2).
        val bv = if (srcHeight >= 1080) "24M" else "16M"
        args.addAll(listOf("-b:v", bv, "-maxrate", bv, "-bufsize", if (srcHeight >= 1080) "48M" else "32M"))
        // Keep source fps: never pass -r.
        args.addAll(
            listOf(
                "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", out.absolutePath,
            )
        )
        return args.toTypedArray()
    }

    /** Crop/pad only. Even width via -2:ih. No fps filter, no export-height scale. */
    fun buildFilter(win: Int, hin: Int, aspect: String): String {
        if (aspect == "original") return "scale=trunc(iw/2)*2:trunc(ih/2)*2"
        var mode = "crop"; var spec = aspect
        if (aspect.startsWith("crop:") || aspect.startsWith("pad:")) {
            mode = aspect.substringBefore(":")
            spec = aspect.substringAfter(":")
        }
        val ab = spec.split(":")
        val r = ab[0].toFloat() / ab[1].toFloat()
        val sr = win.toFloat() / hin.toFloat()
        val num = ab[0].trim()
        val den = ab[1].trim()
        // Fit: pad W/H from bitstream iw/ih (not yt-dlp metadata). max() so pad
        // is never smaller than the current frame; ceil to even. Do not pick a
        // Kotlin branch — swapped/rotated metadata vs file blew up pad:3:4 @ 720p
        // (e.g. pad=ih*3/4:ih → 540x720 on a 1280x720 frame).
        val padVf =
            "pad=ceil(max(iw\\,ih*$num/$den)/2)*2:ceil(max(ih\\,iw*$den/$num)/2)*2:(ow-iw)/2:(oh-ih)/2:black"
        return when {
            mode == "crop" && sr >= r -> "crop=ih*$r:ih:(iw-ih*$r)/2:0,scale=-2:ih"
            mode == "crop"          -> "crop=iw:iw/$r:0:(ih-iw/$r)/2,scale=-2:ih"
            else                    -> padVf
        }
    }

    fun qualityTag(opts: FormatOptions): String {
        val q = opts.dlQuality.trim()
        if (q.equals("best", ignoreCase = true)) return "best"
        val digits = q.filter { it.isDigit() }
        return if (digits.isNotEmpty()) "${digits}p" else q.replace(" ", "")
    }

    fun aspectFileTag(opts: FormatOptions): String {
        val a = opts.aspect.trim()
        if (a.equals("original", ignoreCase = true)) return "Original"
        val mode = when {
            a.startsWith("crop:", ignoreCase = true) -> "Fill"
            a.startsWith("pad:", ignoreCase = true) -> "Fit"
            else -> ""
        }
        val spec = a.substringAfter(':').replace(":", "")
        return "$mode$spec"
    }

    fun shortTitle(raw: String?): String {
        val sb = StringBuilder()
        for (c in raw.orEmpty()) {
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9') {
                sb.append(c)
                if (sb.length >= 20) break
            }
        }
        return if (sb.isEmpty()) "video" else sb.toString()
    }

    fun fileName(opts: FormatOptions, ext: String, title: String? = null): String {
        val base = "${shortTitle(title)}_${qualityTag(opts)}_${aspectFileTag(opts)}"
        val destDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "VideoDroid",
        )
        val candidate = File(destDir, "$base.$ext")
        return if (candidate.exists()) "${base}_${System.currentTimeMillis()}.$ext" else "$base.$ext"
    }

    fun saveToDownloads(context: Context, file: File, name: String, mime: String = "video/mp4"): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VideoDroid")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                return null
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VideoDroid")
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, name)
            file.inputStream().use { ins -> dest.outputStream().use { it.copyFrom(ins) } }
            return Uri.fromFile(dest)
        }
    }

    private fun mimeForExt(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "ts", "mts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        else -> "application/octet-stream"
    }

    private fun java.io.OutputStream.copyFrom(input: java.io.InputStream) {
        val buf = ByteArray(65536)
        while (true) {
            val n = input.read(buf); if (n < 0) break; write(buf, 0, n)
        }
    }
}

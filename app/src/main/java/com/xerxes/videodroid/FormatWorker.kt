package com.xerxes.videodroid

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.chaquo.python.Python
import org.json.JSONObject
import java.io.File

class FormatResult(val ok: Boolean, val message: String, val uri: Uri? = null)

data class FormatOptions(
    val dlQuality: String,
    val exportHeight: Int,
    val aspect: String,
    val fps: Int = 30,
    val crf: Int = 23,
    val trimEnabled: Boolean = true,
    val trimStart: Int = 5,
    val trimEnd: Int = 3,
)

object FormatWorker {
    fun run(context: Context, url: String, opts: FormatOptions, status: (String) -> Unit): FormatResult {
        val work = File(context.cacheDir, "vaf")
        work.mkdirs()
        work.listFiles()?.forEach { it.delete() }

        return try {
            // 1. download via yt-dlp (bundled Python / Chaquopy) -> returns JSON with path + dims
            status("Downloading...")
            val py = Python.getInstance()
            val result = py.getModule("dl")
                .callAttr("download", url, opts.dlQuality, work.absolutePath, "source")
                .toString()
            val info = JSONObject(result)
            val src = File(info.getString("path"))
            if (!src.exists()) return FormatResult(false, "Download produced no file")
            status("Downloaded: ${src.length() / 1024 / 1024} MB")

            var width = info.getInt("width")
            var height = info.getInt("height")
            if (width <= 0 || height <= 0) { width = 1280; height = 720 }
            var duration = info.getDouble("duration")
            if (duration <= 0) duration = 100.0

            // 2. trim + aspect/export filter
            val tStart = if (opts.trimEnabled) opts.trimStart else 0
            val tEnd = if (opts.trimEnabled) opts.trimEnd else 0
            var keep = duration - tStart - tEnd
            if (keep <= 1) keep = duration
            val vf = buildFilter(width, height, opts.aspect, opts.exportHeight, opts.fps)

            // 3. ffmpeg
            status("Converting...")
            val out = File(work, "out.mp4")
            val args = arrayOf(
                "-y", "-v", "error", "-ss", tStart.toString(),
                "-i", src.absolutePath, "-t", keep.toString(), "-vf", vf,
                "-c:v", "libx264", "-preset", "fast", "-crf", opts.crf.toString(),
                "-pix_fmt", "yuv420p", "-r", opts.fps.toString(),
                "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", out.absolutePath,
            )
            val session = FFmpegKit.executeWithArguments(args)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                val tail = (session.output ?: "").takeLast(400)
                return FormatResult(false, "FFmpeg error: $tail")
            }
            if (!out.exists()) return FormatResult(false, "FFmpeg produced no output")

            // 4. save to phone Downloads
            status("Saving to phone...")
            val name = "videodroid_${System.currentTimeMillis()}.mp4"
            val uri = saveToDownloads(context, out, name)
            if (uri == null) return FormatResult(false, "Could not save to Downloads")
            src.delete(); out.delete()
            FormatResult(true, "Saved", uri)
        } catch (e: Throwable) {
            FormatResult(false, e.message ?: e.toString())
        }
    }

    fun buildFilter(win: Int, hin: Int, aspect: String, height: Int, fps: Int): String {
        if (aspect == "original") return "scale=-2:$height,fps=$fps"
        var mode = "crop"; var spec = aspect
        if (aspect.startsWith("crop:") || aspect.startsWith("pad:")) {
            mode = aspect.substringBefore(":")
            spec = aspect.substringAfter(":")
        }
        val ab = spec.split(":")
        val r = ab[0].toFloat() / ab[1].toFloat()
        val sr = win.toFloat() / hin.toFloat()
        return when {
            mode == "crop" && sr >= r -> "crop=ih*$r:ih:(iw-ih*$r)/2:0,scale=-2:$height,fps=$fps"
            mode == "crop"          -> "crop=iw:iw/$r:0:(ih-iw/$r)/2,scale=-2:$height,fps=$fps"
            sr >= r                 -> "pad=iw:iw/$r:0:(ih-iw/$r)/2:black,scale=-2:$height,fps=$fps"
            else                    -> "pad=ih*$r:ih:(iw-ih*$r)/2:0:black,scale=-2:$height,fps=$fps"
        }
    }

    fun saveToDownloads(context: Context, file: File, name: String): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
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

    private fun java.io.OutputStream.copyFrom(input: java.io.InputStream) {
        val buf = ByteArray(65536)
        while (true) {
            val n = input.read(buf); if (n < 0) break; write(buf, 0, n)
        }
    }
}

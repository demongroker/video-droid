package com.xerxes.videodroid

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves uncaught exceptions to a file so the next launch can show them.
 *
 * v2.0.0 crashes before the first frame on Xiaomi/HyperOS and we have no adb
 * access to that phone. This turns "keeps stopping" into a readable stack
 * trace the owner can paste.
 */
object CrashHandler {

    private const val FILE = "last_crash.txt"
    private const val TAG = "VideoDroidCrash"

    fun crashFile(context: Context): File = File(context.filesDir, FILE)

    fun hasCrash(context: Context): Boolean =
        runCatching { crashFile(context).let { it.exists() && it.length() > 0 } }.getOrDefault(false)

    fun read(context: Context): String =
        runCatching { crashFile(context).readText() }.getOrDefault("(unreadable)")

    fun clear(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, error, thread) }
            // Still let the platform do its normal thing.
            if (previous != null) previous.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, error: Throwable, thread: Thread) {
        val sw = StringWriter()
        PrintWriter(sw).use { error.printStackTrace(it) }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val header = buildString {
            appendLine("VideoDroid crash report")
            appendLine("time     : $stamp")
            appendLine("thread   : ${thread.name}")
            appendLine("version  : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device   : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("android  : ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("---------")
        }
        runCatching {
            crashFile(context).writeText(header + sw.toString())
            Log.e(TAG, header + sw.toString())
        }
    }
}

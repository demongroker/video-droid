package com.xerxes.videodroid

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The local crash log.
 *
 * Nothing leaves the phone: reports are written under `filesDir/crashes`, listed in Settings →
 * About → Crash logs, and only shared if the owner taps Share. Every line is passed through
 * [Privacy] first, so no cookie, token or query string can end up in a file that gets pasted
 * into a chat.
 *
 * Two kinds of entry, same folder:
 *  - `crash_…`     an uncaught exception (the app died);
 *  - `nonfatal_…`  a worker error that was caught and handled (a download that failed).
 *
 * The newest [MAX_FILES] entries are kept; older ones are deleted automatically, non-fatal
 * entries first so a real crash is never pushed out by noise.
 */
object CrashLog {

    const val MAX_FILES = 20
    const val MAX_LOG_LINES = 200

    private const val CRASH_PREFIX = "crash_"
    private const val NONFATAL_PREFIX = "nonfatal_"
    private const val PENDING = ".pending_crash"

    private val NAME_STAMP = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val HUMAN_STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)

    fun dir(context: Context): File = File(context.filesDir, "crashes").apply { mkdirs() }

    fun files(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    fun latest(context: Context): File? = files(context).firstOrNull()

    fun isCrashFile(file: File): Boolean = file.name.startsWith(CRASH_PREFIX)

    // ---- writing ------------------------------------------------------------------

    /** Pure renderer, so the exact file format can be unit tested. */
    fun format(
        kind: String,
        timestamp: String,
        appVersion: String,
        versionCode: Int,
        androidVersion: String,
        device: String,
        threadName: String,
        stack: String,
        logLines: List<String>,
    ): String = buildString {
        appendLine("VideoDroid ${kind.lowercase()}")
        appendLine("time     : $timestamp")
        appendLine("version  : $appVersion ($versionCode)")
        appendLine("android  : $androidVersion")
        appendLine("device   : $device")
        appendLine("thread   : $threadName")
        appendLine("---------")
        appendLine(stack.trimEnd())
        appendLine("---------")
        appendLine("last ${logLines.size} log lines (oldest first):")
        if (logLines.isEmpty()) appendLine("(none)") else logLines.forEach { appendLine(it) }
    }

    fun writeCrash(context: Context, thread: Thread, error: Throwable): File? = write(
        context = context,
        prefix = CRASH_PREFIX,
        kind = "crash",
        threadName = thread.name,
        error = error,
        pending = true,
    )

    fun writeNonFatal(context: Context, where: String, error: Throwable): File? = write(
        context = context,
        prefix = NONFATAL_PREFIX,
        kind = "non-fatal error",
        threadName = "$where (${Thread.currentThread().name})",
        error = error,
        pending = false,
    )

    private fun write(
        context: Context,
        prefix: String,
        kind: String,
        threadName: String,
        error: Throwable,
        pending: Boolean,
    ): File? = runCatching {
        val now = Date()
        val dir = dir(context)
        var target = File(dir, prefix + NAME_STAMP.format(now) + ".txt")
        var n = 2
        while (target.exists()) {
            target = File(dir, prefix + NAME_STAMP.format(now) + "_$n" + ".txt")
            n++
        }

        val stack = StringWriter().also { sw -> PrintWriter(sw).use { error.printStackTrace(it) } }
            .toString()

        val body = format(
            kind = kind,
            timestamp = HUMAN_STAMP.format(now),
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            threadName = threadName,
            stack = stack,
            logLines = AppLog.lines().takeLast(MAX_LOG_LINES),
        )

        target.writeText(Privacy.redact(body))
        if (pending) runCatching { File(dir, PENDING).writeText(target.name) }
        rotate(dir, MAX_FILES)
        target
    }.getOrNull()

    /**
     * Keep the newest [max] entries. Non-fatal records go first, so a real crash is the last
     * thing to be thrown away.
     */
    fun rotate(dir: File, max: Int = MAX_FILES): Int {
        val all = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }
            ?: return 0
        if (all.size <= max) return 0
        var deleted = 0
        val overflow = all.size - max
        val victims = (all.filterNot { it.name.startsWith(CRASH_PREFIX) } +
            all.filter { it.name.startsWith(CRASH_PREFIX) }.asReversed())
            .take(overflow)
        for (f in victims) if (runCatching { f.delete() }.getOrDefault(false)) deleted++
        return deleted
    }

    // ---- pending-crash banner ---------------------------------------------------------

    fun pendingFile(context: Context): File = File(dir(context), PENDING)

    fun hasPending(context: Context): Boolean =
        runCatching { pendingFile(context).exists() }.getOrDefault(false)

    fun clearPending(context: Context) {
        runCatching { pendingFile(context).delete() }
    }

    // ---- maintenance -------------------------------------------------------------------

    fun clearAll(context: Context): Int {
        var n = 0
        for (f in files(context)) if (runCatching { f.delete() }.getOrDefault(false)) n++
        clearPending(context)
        return n
    }

    fun read(file: File): String =
        runCatching { file.readText() }.getOrDefault("(unreadable)")
}

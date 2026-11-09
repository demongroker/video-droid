package com.xerxes.videodroid

import android.content.Context
import java.io.File

/**
 * Installs the global uncaught-exception handler.
 *
 * Kept as a thin wrapper over [CrashLog] so the rest of the app (and the emergency crash
 * screen) has one entry point. Nothing is swallowed: the record is written first, then the
 * platform's own handler runs, so the app still dies exactly the way Android expects.
 */
object CrashHandler {

    fun crashFile(context: Context): File? = CrashLog.latest(context)

    fun hasCrash(context: Context): Boolean = CrashLog.latest(context) != null

    fun read(context: Context): String =
        CrashLog.latest(context)?.let { CrashLog.read(it) } ?: "(no crash log)"

    fun clear(context: Context) {
        CrashLog.clearAll(context)
    }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // CrashLog writes filesDir/crashes/crash_<stamp>.txt, redacted, rotated to 20.
            runCatching { write(app, error, thread) }
            // Still let the platform do its normal thing.
            if (previous != null) previous.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, error: Throwable, thread: Thread) {
        // One breadcrumb in the in-memory log, then the full record through CrashLog.
        AppLog.add("FATAL on ${thread.name}: ${error.javaClass.simpleName}")
        CrashLog.writeCrash(context, thread, error)
    }
}

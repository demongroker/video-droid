package com.xerxes.videodroid

import android.content.Intent
import android.os.Bundle
import com.chaquo.python.android.PyApplication

/**
 * Application subclass that extends Chaquopy's PyApplication. This automatically calls
 * Python.start(new AndroidPlatform(this)) before any Python use, so that
 * Python.getInstance() (called in FormatWorker) works on Android.
 */
class App : PyApplication() {
    override fun onCreate() {
        super.onCreate()

        // Catch anything that kills the process, so the next launch can show
        // the stack trace instead of just "keeps stopping".
        CrashHandler.install(this)

        if (CrashHandler.hasCrash(this)) {
            runCatching {
                startActivity(
                    Intent(this, CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            }
        }
    }
}

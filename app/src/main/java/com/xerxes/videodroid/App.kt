package com.xerxes.videodroid

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
        // Material You: on Android 12+ every activity picks up the wallpaper
        // palette. Below 12 this is a no-op and the indigo #3949AB theme
        // colours apply as before.
    }
}

package com.xerxes.videodroid

import com.chaquo.python.android.PyApplication

/**
 * Application subclass that extends Chaquopy's PyApplication. This automatically calls
 * Python.start(new AndroidPlatform(this)) before any Python use, so that
 * Python.getInstance() (called in FormatWorker) works on Android.
 */
class App : PyApplication()

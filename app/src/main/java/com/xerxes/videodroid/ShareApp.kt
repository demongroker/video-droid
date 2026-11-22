package com.xerxes.videodroid

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Share the installed APK itself, so another phone can sideload the exact same build.
 *
 * The APK lives under /data/app, which [FileProvider] cannot expose directly, so it is copied
 * into `cacheDir/shared/` first — that is the `shared_apk` cache-path declared in
 * `res/xml/file_paths.xml`. Nothing leaves the device except what the user picks in the share
 * sheet, and the copy is a normal cache file the OS can reclaim.
 */
object ShareApp {

    fun share(activity: Activity) {
        val name = "VideoDroid-${BuildConfig.VERSION_NAME}.apk"
        try {
            val info = activity.packageManager.getApplicationInfo(activity.packageName, 0)
            val src = File(info.sourceDir)
            if (!src.isFile) throw IllegalStateException("installed APK not found")
            val out = File(File(activity.cacheDir, "shared"), name)
            out.parentFile?.mkdirs()
            src.copyTo(out, overwrite = true)

            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", out
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, "Share VideoDroid"))
            AppLog.add("share app ${BuildConfig.VERSION_NAME} -> $name (${out.length()} bytes)")
        } catch (e: Exception) {
            AppLog.add("share app failed: ${e.javaClass.simpleName}: ${e.message}")
            Toast.makeText(
                activity, "Could not share the app: ${e.message}", Toast.LENGTH_LONG
            ).show()
        }
    }
}

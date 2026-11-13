package com.xerxes.videodroid

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Persisted state of the in-app updater: which version is being downloaded, how
 * far it got, what failed, when the user snoozed the prompt, and whether
 * downloads are Wi-Fi only.
 *
 * This exists so the dialog can be suppressed while a download is already
 * running — previously every launch re-prompted and each "Download" tap started
 * another download thread against the same `.part` file.
 *
 * Survives process death, so a relaunch picks up a download that is still going
 * (the foreground service keeps running).
 */
object UpdateState {
    private const val PREFS = "videodroid_update_state"

    private const val KEY_SNOOZE_UNTIL = "snoozeUntil"
    private const val KEY_JOB_VERSION = "jobVersion"
    private const val KEY_JOB_STATE = "jobState"
    private const val KEY_JOB_PERCENT = "jobPercent"
    private const val KEY_JOB_ERROR = "jobError"
    private const val KEY_WIFI_ONLY = "wifiOnly"

    /** "Download on Wi-Fi only" — ON by default: at sea, mobile data is expensive. */
    const val DEFAULT_WIFI_ONLY = true

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun snoozeUntil(context: Context): Long = prefs(context).getLong(KEY_SNOOZE_UNTIL, 0L)

    /** "Later" means 24 hours of silence, not "ask me again in a second". */
    fun snooze(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_SNOOZE_UNTIL, nowMillis + UpdatePrompt.SNOOZE_MILLIS).apply()
    }

    fun clearSnooze(context: Context) {
        prefs(context).edit().remove(KEY_SNOOZE_UNTIL).apply()
    }

    fun wifiOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_WIFI_ONLY, DEFAULT_WIFI_ONLY)

    fun setWifiOnly(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
    }

    fun jobVersion(context: Context): String? = prefs(context).getString(KEY_JOB_VERSION, null)

    fun jobState(context: Context): UpdateJobState =
        UpdateJobState.parse(prefs(context).getString(KEY_JOB_STATE, null))

    fun jobPercent(context: Context): Int = prefs(context).getInt(KEY_JOB_PERCENT, 0)

    fun jobError(context: Context): String? = prefs(context).getString(KEY_JOB_ERROR, null)

    fun beginDownload(context: Context, version: String) {
        prefs(context).edit()
            .putString(KEY_JOB_VERSION, version)
            .putString(KEY_JOB_STATE, UpdateJobState.DOWNLOADING.name)
            .putInt(KEY_JOB_PERCENT, 0)
            .remove(KEY_JOB_ERROR)
            .apply()
    }

    fun progress(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_JOB_PERCENT, percent.coerceIn(0, 100)).apply()
    }

    fun markVerified(context: Context, version: String) {
        prefs(context).edit()
            .putString(KEY_JOB_VERSION, version)
            .putString(KEY_JOB_STATE, UpdateJobState.VERIFIED.name)
            .putInt(KEY_JOB_PERCENT, 100)
            .remove(KEY_JOB_ERROR)
            .apply()
    }

    fun markFailed(context: Context, version: String, error: String?) {
        prefs(context).edit()
            .putString(KEY_JOB_VERSION, version)
            .putString(KEY_JOB_STATE, UpdateJobState.FAILED.name)
            .putString(KEY_JOB_ERROR, error)
            .apply()
    }

    fun reset(context: Context) {
        prefs(context).edit()
            .remove(KEY_JOB_VERSION)
            .putString(KEY_JOB_STATE, UpdateJobState.IDLE.name)
            .putInt(KEY_JOB_PERCENT, 0)
            .remove(KEY_JOB_ERROR)
            .apply()
    }

    /** True when a download for [version] is in flight (also true across a relaunch). */
    fun isDownloadActive(context: Context, version: String? = null): Boolean {
        if (UpdateService.isDownloadRunning()) return version == null || jobVersion(context) == version
        return jobState(context) == UpdateJobState.DOWNLOADING &&
            (version == null || jobVersion(context) == version)
    }

    fun isOnline(context: Context): Boolean = networkState(context).first

    /** True when the active network is Wi-Fi/Ethernet (i.e. not metered mobile data). */
    fun isUnmetered(context: Context): Boolean = networkState(context).second

    private fun networkState(context: Context): Pair<Boolean, Boolean> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false to false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                ?: return false to false
            val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // Wi-Fi, Ethernet or VPN over either — anything that is not metered mobile data.
            val unmetered = (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) &&
                !cm.isActiveNetworkMetered
            online to unmetered
        } catch (_: Throwable) {
            false to false
        }
    }
}

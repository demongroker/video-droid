package com.xerxes.videodroid

/**
 * What the UI should do about an available update — pure logic, no Android, so
 * the rules are unit-testable.
 *
 * The behaviour this replaces: the update dialog was shown unconditionally on
 * every launch and every resume, even while the download for that same version
 * was already running, and tapping "Download" a second time started a second
 * download thread against the same `.part` file. Two threads in one
 * RandomAccessFile, plus a second foreground start, is what killed the app.
 */
enum class UpdateAction {
    /** Nothing to say. */
    NONE,

    /** Ask the user to download (dialog / banner). */
    PROMPT,

    /** A download for this version is running — report progress, do not ask again. */
    SHOW_DOWNLOADING,

    /** The APK is downloaded and verified — offer install. */
    SHOW_READY,

    /** No connection: say so plainly, keep the retry path. */
    SHOW_OFFLINE,

    /** "Wi-Fi only" is on and we are on mobile data. */
    WAIT_FOR_WIFI,

    /** The last attempt failed; show the reason and a Retry button. */
    SHOW_FAILED,
}

/** Where a download job for a version stands. Persisted in SharedPreferences. */
enum class UpdateJobState {
    IDLE,
    DOWNLOADING,
    VERIFIED,
    FAILED;

    companion object {
        fun parse(raw: String?): UpdateJobState =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: IDLE
    }
}

data class UpdatePromptInput(
    val latestVersion: String,
    val installedVersion: String,
    val nowMillis: Long,
    val snoozeUntilMillis: Long,
    val jobVersion: String?,
    val jobState: UpdateJobState,
    val jobPercent: Int,
    val jobError: String?,
    val online: Boolean,
    val unmetered: Boolean,
    val wifiOnly: Boolean,
    val userInitiated: Boolean,
)

data class UpdateDecision(val action: UpdateAction, val message: String)

object UpdatePrompt {

    const val SNOOZE_MILLIS: Long = 24L * 60 * 60 * 1000

    fun decide(input: UpdatePromptInput): UpdateDecision {
        val jobMatches = !input.jobVersion.isNullOrBlank() && input.jobVersion == input.latestVersion

        // A running or finished job for THIS version always wins over prompting:
        // asking again (and starting a second download) is the bug being fixed.
        if (jobMatches) {
            when (input.jobState) {
                UpdateJobState.DOWNLOADING ->
                    return UpdateDecision(UpdateAction.SHOW_DOWNLOADING, downloadingMessage(input.jobPercent))
                UpdateJobState.VERIFIED ->
                    return UpdateDecision(UpdateAction.SHOW_READY, "Update ${input.latestVersion} ready — tap Install")
                UpdateJobState.FAILED ->
                    return UpdateDecision(
                        UpdateAction.SHOW_FAILED,
                        "Update failed${input.jobError?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""} — tap Retry",
                    )
                UpdateJobState.IDLE -> Unit
            }
        }

        if (!UpdateChecker.isNewer(input.latestVersion, input.installedVersion)) {
            return UpdateDecision(
                UpdateAction.NONE,
                if (input.userInitiated) "Up to date (${input.installedVersion})" else "",
            )
        }

        if (!input.userInitiated && input.snoozeUntilMillis > input.nowMillis) {
            val hours = ((input.snoozeUntilMillis - input.nowMillis) / 3_600_000L).coerceAtLeast(1)
            return UpdateDecision(UpdateAction.NONE, "Update ${input.latestVersion} snoozed for ${hours}h")
        }

        if (!input.online) {
            return UpdateDecision(UpdateAction.SHOW_OFFLINE, "No internet — update will retry when you're back online")
        }

        if (input.wifiOnly && !input.unmetered) {
            return UpdateDecision(
                UpdateAction.WAIT_FOR_WIFI,
                "Wi-Fi only is on — update ${input.latestVersion} will download when you're on Wi-Fi",
            )
        }

        return UpdateDecision(
            UpdateAction.PROMPT,
            "Version ${input.latestVersion} is available. You have ${input.installedVersion}.",
        )
    }

    fun downloadingMessage(percent: Int): String =
        if (percent > 0) "Update downloading ($percent%) — progress is in the status bar"
        else "Update downloading — progress is in the status bar"
}

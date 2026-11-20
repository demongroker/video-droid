package com.xerxes.videodroid.ui

/**
 * Snapshot of the Download screen's settings. Pure data — the Activity persists it under the
 * same preference keys QuickShareActivity and the Options sheet already read, so nothing in the
 * job-options chain (JobSettings → FormatOptions → intent extras → dl.py) changes shape.
 */
data class DownloadSettings(
    val quality: String = "1080p",
    val exportOn: Boolean = true,
    val mode: String = "Original",
    val ratio: String = "Portrait 9:16",
    val trimOn: Boolean = false,
    val trimStart: Int = 0,
    val trimEnd: Int = 0,
) {
    /** Mode + grouped ratio label → FormatWorker token, or "original" when export is off. */
    fun aspectToken(): String =
        if (exportOn) MainCopy.aspectToken(mode, ratio) else "original"

    fun isRecommended(): Boolean = MainCopy.isRecommended(quality, mode, trimOn)
}

/** One social preset: applying it sets quality/mode/ratio and turns trim off. Export is always on. */
data class SocialPreset(
    val name: String,
    val quality: String,
    val mode: String,
    val ratio: String,
)

/**
 * Pure copy + rules for the Download (Main) screen, mirroring [QueueCopy].
 *
 * The state pill must say what is actually true (a drained queue is "ready", never "working"),
 * the primary button's label follows busy/export state, and the aspect token mapping is the
 * contract FormatWorker parses — all pinned here instead of buried in widget listeners.
 */
object MainCopy {

    val QUALITIES = listOf("4K", "1080p", "720p", "480p")
    val MODES = listOf("Original", "Fill", "Fit")
    val RATIOS = listOf(
        "Landscape 16:9", "Landscape 4:3", "Landscape 21:9",
        "Portrait 9:16", "Portrait 3:4", "Portrait 4:5",
        "Square 1:1",
    )

    val PRESETS = listOf(
        SocialPreset("X", "1080p", "Fit", "Portrait 9:16"),
        SocialPreset("TikTok", "1080p", "Fill", "Portrait 9:16"),
        SocialPreset("Shorts", "1080p", "Fill", "Portrait 9:16"),
        SocialPreset("Reels", "1080p", "Fill", "Portrait 9:16"),
        SocialPreset("YouTube", "1080p", "Original", "Portrait 9:16"),
        SocialPreset("Instagram", "1080p", "Fill", "Portrait 4:5"),
        SocialPreset("Facebook", "1080p", "Fit", "Landscape 16:9"),
    )

    const val EMPTY_URL_NOTICE = "Paste a link or pick a local video"

    /**
     * "ready" used to be a hardcoded label, so the header claimed everything was fine even with
     * a queue full of failed rows. Say what is actually true.
     */
    fun statePill(busy: Boolean, queueCount: Int, waiting: Int, progressVisible: Boolean): String = when {
        progressVisible && waiting > 0 -> "working · $waiting waiting"
        progressVisible -> "working"
        busy && waiting > 0 -> "working · $waiting waiting"
        busy -> "working"
        queueCount > 0 -> "$queueCount in queue — tap Queue"
        else -> "ready"
    }

    fun goLabel(busy: Boolean, exportOn: Boolean): String = when {
        busy -> "Add to queue"
        exportOn -> "Download & Convert"
        else -> "Download"
    }

    /** Cancel is only offered while something is actually running. */
    fun showCancel(busy: Boolean): Boolean = busy

    fun showJobCard(statusText: String, progressVisible: Boolean): Boolean =
        statusText.isNotEmpty() || progressVisible

    /** Mode + grouped ratio labels → FormatWorker tokens (original / crop:W:H / pad:W:H). */
    fun aspectToken(mode: String, ratioLabel: String): String {
        val m = mode.trim()
        if (m.equals("Original", ignoreCase = true)) return "original"
        val spec = ratioLabel.trim().substringAfterLast(' ').replace(" ", "")
        if (m.equals("Fill", ignoreCase = true)) return "crop:$spec"
        if (m.equals("Fit", ignoreCase = true)) return "pad:$spec"
        return spec
    }

    /** The recommended selection is 1080p / Original / trim off — export state is irrelevant. */
    fun isRecommended(quality: String, mode: String, trimOn: Boolean): Boolean =
        quality == "1080p" && mode.equals("Original", ignoreCase = true) && !trimOn

    fun presetNotice(preset: SocialPreset): String =
        "${preset.name}: ${preset.quality} / ${preset.mode}" +
            (if (preset.mode == "Original") "" else " ${preset.ratio}") +
            " / trim off"
}
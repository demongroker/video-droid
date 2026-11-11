package com.xerxes.videodroid

/**
 * Nothing reaches MediaStore — and therefore the Library — unless it is a complete, readable
 * media file.
 *
 * The failure this exists for: cutting Wi-Fi/data mid-download left the app reporting
 * **"Saved"** for a truncated file, because the save path trusted whatever the downloader
 * returned (`File(info.getString("path"))`) and never looked at its size or whether the
 * container could be read at all.
 *
 * Pure Kotlin (no Android imports) so the rule is unit tested on the JVM.
 */
object DownloadIntegrity {

    /** Smaller than this cannot be a video no matter what the site claims. */
    const val MIN_BYTES = 1024L

    /**
     * 2.5.1 field fix: a "Saved" job that wrote 0 bytes (and the 20-40 KB HTML error pages that
     * servers hand back instead of a video) is an EMPTY download, not a short one. It gets its
     * own reason so the queue can say "Download was empty — try Scan page" instead of
     * "incomplete".
     */
    const val EMPTY_BYTES = 64L * 1024

    /** A download may legitimately be slightly under an estimate, never far under. */
    private const val SHORT_FRACTION_NUM = 9
    private const val SHORT_FRACTION_DEN = 10

    /**
     * Why this file must not be saved, or null when it is acceptable.
     *
     * @param expectedBytes advertised size (filesize/filesize_approx), 0 when unknown
     * @param probeWidth/probeHeight/probeDuration FFprobe results, 0 when unknown
     */
    fun rejection(
        name: String,
        sizeBytes: Long,
        expectedBytes: Long,
        probeWidth: Int,
        probeHeight: Int,
        probeDuration: Double,
    ): String? {
        val lower = name.lowercase()
        if (lower.endsWith(".part") || lower.contains(".part.")) return "partial file"
        if (sizeBytes < EMPTY_BYTES) return "empty: $sizeBytes bytes"
        if (sizeBytes < MIN_BYTES) return "only $sizeBytes bytes"
        if (expectedBytes > 0 && sizeBytes * SHORT_FRACTION_DEN < expectedBytes * SHORT_FRACTION_NUM) {
            return "short: $sizeBytes of ~$expectedBytes bytes"
        }
        // A truncated container usually cannot be probed at all — no stream, no duration.
        if (probeWidth <= 0 && probeHeight <= 0 && probeDuration <= 0.0) {
            return "container unreadable"
        }
        return null
    }

    /** The message shown on the queue row. */
    fun failureMessage(reason: String): String =
        if (reason.startsWith("empty")) "Download was empty — try Scan page"
        else "Download incomplete — nothing was saved ($reason)"
}

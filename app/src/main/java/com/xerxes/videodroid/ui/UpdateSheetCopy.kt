package com.xerxes.videodroid.ui

import com.xerxes.videodroid.UpdateAction
import java.util.Locale

/**
 * The verification sub-steps of an update download, persisted so a relaunch (or the progress
 * sheet opening mid-verify) shows the real step instead of a stuck percentage.
 */
enum class UpdateVerifyStep {
    NONE,
    SIGNATURE,
    CHECKSUM;

    companion object {
        fun parse(raw: String?): UpdateVerifyStep =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: NONE
    }
}

/** Semantic tone of the sheet's state line. The UI maps tone → colour; copy never carries one. */
enum class UpdateSheetTone { NEUTRAL, GOOD, WARN, BAD, BUSY }

data class UpdateSheetLine(val text: String, val tone: UpdateSheetTone)

/** The one forward action the sheet offers, if any. */
enum class UpdateSheetAction { DOWNLOAD, INSTALL, RETRY }

data class UpdateSheetModel(
    val title: String,
    val line: UpdateSheetLine,
    val showProgress: Boolean,
    /** null = indeterminate (unknown total, or a verification step is running). */
    val progressPercent: Int?,
    val sizeText: String,
    val speedText: String,
    val primary: UpdateSheetAction?,
    val primaryLabel: String,
    val showCancel: Boolean,
    val showLater: Boolean,
    val showWifiToggle: Boolean,
    val wifiOnly: Boolean,
)

/**
 * Pure copy + rules for the updater progress sheet, mirroring [QueueCopy].
 *
 * Contracts pinned here:
 *  - the failure reason lives in the STATE line, never in the title;
 *  - while a download is in flight there is NO Download/Retry action (single flight — a second
 *    tap used to start a second thread on the same `.part` file and kill the process);
 *  - fail-closed refusals keep their exact wording ("signature mismatch — refused",
 *    "no published checksum", "checksum mismatch");
 *  - an unknown total is an indeterminate bar, never an invented percentage.
 */
object UpdateSheetCopy {

    fun bytes(bytes: Long): String {
        if (bytes <= 0) return ""
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 1) return String.format(Locale.US, "%.1f GB", gb)
        val mb = bytes / (1024.0 * 1024.0)
        if (mb >= 1) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    fun speed(bytesPerSec: Long): String =
        if (bytesPerSec <= 0) "" else "${bytes(bytesPerSec)}/s"

    fun sizeLine(doneBytes: Long, totalBytes: Long): String = when {
        doneBytes <= 0 && totalBytes <= 0 -> ""
        totalBytes > 0 -> "${bytes(doneBytes.coerceAtLeast(0))} of ${bytes(totalBytes)}"
        else -> bytes(doneBytes)
    }

    fun downloadingLine(percent: Int, step: UpdateVerifyStep): UpdateSheetLine = when (step) {
        UpdateVerifyStep.SIGNATURE -> UpdateSheetLine("Verifying signature…", UpdateSheetTone.BUSY)
        UpdateVerifyStep.CHECKSUM -> UpdateSheetLine("Checking checksum…", UpdateSheetTone.BUSY)
        UpdateVerifyStep.NONE -> UpdateSheetLine(
            if (percent > 0) "Downloading $percent%" else "Downloading…",
            UpdateSheetTone.BUSY,
        )
    }

    fun model(
        action: UpdateAction,
        decisionMessage: String,
        version: String,
        percent: Int,
        doneBytes: Long,
        totalBytes: Long,
        speedBps: Long,
        step: UpdateVerifyStep,
        jobError: String?,
        wifiOnly: Boolean,
    ): UpdateSheetModel {
        val title = "Update $version"
        return when (action) {
            UpdateAction.SHOW_DOWNLOADING -> UpdateSheetModel(
                title = title,
                line = downloadingLine(percent, step),
                showProgress = true,
                progressPercent =
                    if (step == UpdateVerifyStep.NONE && totalBytes > 0) percent.coerceIn(0, 100)
                    else null,
                sizeText = sizeLine(doneBytes, totalBytes),
                speedText = if (step == UpdateVerifyStep.NONE) speed(speedBps) else "",
                primary = null,
                primaryLabel = "",
                showCancel = true,
                showLater = false,
                showWifiToggle = false,
                wifiOnly = wifiOnly,
            )
            UpdateAction.SHOW_READY -> UpdateSheetModel(
                title = title,
                line = UpdateSheetLine("Verified — ready to install", UpdateSheetTone.GOOD),
                showProgress = true,
                progressPercent = 100,
                sizeText = bytes(if (totalBytes > 0) totalBytes else doneBytes),
                speedText = "",
                primary = UpdateSheetAction.INSTALL,
                primaryLabel = "Install",
                showCancel = false,
                showLater = false,
                showWifiToggle = false,
                wifiOnly = wifiOnly,
            )
            UpdateAction.SHOW_FAILED -> UpdateSheetModel(
                title = title,
                line = UpdateSheetLine(
                    "Failed — " + (jobError?.takeIf { it.isNotBlank() } ?: "unknown error"),
                    UpdateSheetTone.BAD,
                ),
                showProgress = false,
                progressPercent = null,
                sizeText = "",
                speedText = "",
                primary = UpdateSheetAction.RETRY,
                primaryLabel = "Retry",
                showCancel = false,
                showLater = true,
                showWifiToggle = true,
                wifiOnly = wifiOnly,
            )
            UpdateAction.PROMPT -> UpdateSheetModel(
                title = title,
                line = UpdateSheetLine(decisionMessage, UpdateSheetTone.NEUTRAL),
                showProgress = false,
                progressPercent = null,
                sizeText = "",
                speedText = "",
                primary = UpdateSheetAction.DOWNLOAD,
                primaryLabel = "Download",
                showCancel = false,
                showLater = true,
                showWifiToggle = true,
                wifiOnly = wifiOnly,
            )
            UpdateAction.WAIT_FOR_WIFI -> UpdateSheetModel(
                title = title,
                line = UpdateSheetLine(decisionMessage, UpdateSheetTone.WARN),
                showProgress = false,
                progressPercent = null,
                sizeText = "",
                speedText = "",
                primary = null,
                primaryLabel = "",
                showCancel = false,
                showLater = true,
                showWifiToggle = true,
                wifiOnly = wifiOnly,
            )
            // Offline stays a message with a way out, never a dead end (same rule as UpdatePrompt).
            UpdateAction.SHOW_OFFLINE -> UpdateSheetModel(
                title = title,
                line = UpdateSheetLine(decisionMessage, UpdateSheetTone.WARN),
                showProgress = false,
                progressPercent = null,
                sizeText = "",
                speedText = "",
                primary = null,
                primaryLabel = "",
                showCancel = false,
                showLater = true,
                showWifiToggle = false,
                wifiOnly = wifiOnly,
            )
            UpdateAction.NONE -> UpdateSheetModel(
                title = title,
                line = UpdateSheetLine(decisionMessage, UpdateSheetTone.NEUTRAL),
                showProgress = false,
                progressPercent = null,
                sizeText = "",
                speedText = "",
                primary = null,
                primaryLabel = "",
                showCancel = false,
                showLater = true,
                showWifiToggle = false,
                wifiOnly = wifiOnly,
            )
        }
    }
}
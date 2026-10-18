package com.xerxes.videodroid.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xerxes.videodroid.DownloadQueue
import com.xerxes.videodroid.DownloadService
import com.xerxes.videodroid.FormatOptions
import com.xerxes.videodroid.FormatResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Snapshot of the settings the user picked, read from the views and handed to
 * the ViewModel. Keeping this a plain data class means the ViewModel never
 * touches widgets.
 */
data class JobSettings(
    val quality: String,
    val exportOn: Boolean,
    val aspect: String,
    val trimOn: Boolean,
    val trimStart: Int,
    val trimEnd: Int,
) {
    fun toFormatOptions(): FormatOptions =
        FormatOptions(quality, 0, aspect, 0, 3, trimOn, trimStart, trimEnd, exportOn)
}

/** Everything the screen renders. Immutable so the Activity can diff cheaply. */
data class MainUiState(
    val busy: Boolean = false,
    val queueCount: Int = 0,
    val waiting: Int = 0,
    val statusText: String = "",
    val progressVisible: Boolean = false,
    val progressIndeterminate: Boolean = false,
    val progressPercent: Int? = null,
    val resultUri: Uri? = null,
    val lastFailUrl: String? = null,
    val openFailedVisible: Boolean = false,
)

/**
 * Screen logic for MainActivity: job state machine, background IO and
 * DownloadService callbacks. The Activity only binds views and forwards
 * user intent here.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    /** Re-read the persisted queue after returning to the screen. */
    fun refreshFromQueue() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val q = DownloadQueue.size(getApplication())
                val waiting = if (q > 0) DownloadQueue.waiting(getApplication()) else 0
                val corrupt = DownloadQueue.consumeCorruptNotice()
                Triple(q, waiting, corrupt)
            }
            val (q, waiting, corrupt) = snapshot
            _state.update {
                when {
                    corrupt != null -> it.copy(statusText = corrupt)
                    q > 0 -> it.copy(
                        busy = true,
                        queueCount = q,
                        waiting = waiting,
                        statusText = if (waiting > 0) "Queued ($waiting)\nWorking..." else "Working...",
                        progressVisible = false,
                    )
                    else -> it
                }
            }
        }
    }

    fun setStatus(msg: String) = _state.update { it.copy(statusText = msg) }

    /** Copy a SAF content:// video into app storage so FFmpeg can read it. */
    fun importLocalVideo(uri: Uri, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(statusText = "Reading local video…") }
            val path = withContext(Dispatchers.IO) {
                try {
                    val ctx = getApplication<Application>()
                    val mime = ctx.contentResolver.getType(uri) ?: "video/mp4"
                    val ext = when {
                        mime.contains("quicktime") -> "mov"
                        mime.contains("webm") -> "webm"
                        mime.contains("mkv") -> "mkv"
                        mime.contains("mp2t") -> "ts"
                        mime.contains("avi") -> "avi"
                        else -> "mp4"
                    }
                    val dest = File(ctx.filesDir, "local_input.$ext")
                    ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        dest.outputStream().use { outs -> ins.copyTo(outs) }
                    }
                    if (dest.exists() && dest.length() > 0) dest.absolutePath else null
                } catch (_: Throwable) {
                    null
                }
            }
            if (path == null) _state.update { it.copy(statusText = "Could not read that video") }
            onResult(path)
        }
    }

    /**
     * Queue a job. [url] may be empty when [localPath] points at an imported file.
     */
    fun startJob(url: String, localPath: String?, settings: JobSettings) {
        val ctx = getApplication<Application>()
        _state.update {
            it.copy(
                busy = true,
                resultUri = null,
                lastFailUrl = url.ifEmpty { null },
                openFailedVisible = false,
                progressVisible = false,
                progressIndeterminate = false,
                progressPercent = null,
            )
        }
        viewModelScope.launch {
            val queued = withContext(Dispatchers.IO) {
                val already = DownloadQueue.size(ctx)
                if (url.isNotEmpty()) {
                    runCatching { com.xerxes.videodroid.OpenPageActivity.storeJobUrl(ctx, url) }
                }
                DownloadService.start(ctx, url, settings.toFormatOptions(), localPath.orEmpty())
                already
            }
            _state.update {
                it.copy(
                    queueCount = queued,
                    statusText = if (queued > 0) "Queued ($queued)\nStarting..." else "Starting...",
                )
            }
        }
    }

    fun cancel() {
        val ctx = getApplication<Application>()
        _state.update {
            it.copy(statusText = "Cancelling...", progressVisible = false, openFailedVisible = false)
        }
        DownloadService.cancel(ctx)
    }

    /** DownloadService.Listener callback — download progress / stage text. */
    fun onServiceStatus(msg: String) {
        val active = msg.contains("Downloading") ||
            msg.contains("Converting") ||
            msg.contains("Trimming")
        if (!active) {
            _state.update { it.copy(statusText = msg, progressVisible = false) }
            return
        }
        val pct = JOB_PCT_RE.find(msg)?.groupValues?.get(1)?.toIntOrNull()
        _state.update {
            it.copy(
                statusText = msg,
                progressVisible = true,
                progressIndeterminate = pct == null || pct !in 0..100,
                progressPercent = pct?.takeIf { p -> p in 0..100 },
            )
        }
    }

    /** DownloadService.Listener callback — job finished (ok / cancelled / failed). */
    fun onServiceFinished(result: FormatResult) {
        viewModelScope.launch {
            val (remaining, waiting) = withContext(Dispatchers.IO) {
                val r = DownloadQueue.size(getApplication())
                Pair(r, if (r > 0) DownloadQueue.waiting(getApplication()) else 0)
            }
            val prefix = if (remaining > 0) {
                if (waiting > 0) "Queued ($waiting)\n" else ""
            } else ""
            _state.update {
                if (result.ok && result.uri != null) {
                    it.copy(
                        busy = remaining > 0,
                        queueCount = remaining,
                        waiting = waiting,
                        resultUri = result.uri,
                        lastFailUrl = null,
                        openFailedVisible = false,
                        statusText = prefix + "Saved. Tap to open\n${result.uri}",
                        progressVisible = false,
                        progressIndeterminate = false,
                        progressPercent = null,
                    )
                } else {
                    val cancelled = result.message.equals("Cancelled", ignoreCase = true)
                    it.copy(
                        busy = remaining > 0,
                        queueCount = remaining,
                        waiting = waiting,
                        resultUri = null,
                        openFailedVisible = !cancelled && remaining == 0,
                        statusText = prefix + result.message,
                        progressVisible = false,
                        progressIndeterminate = false,
                        progressPercent = null,
                    )
                }
            }
        }
    }

    companion object {
        /** Honest bar: only real N% values; never invent a percentage. */
        private val JOB_PCT_RE =
            Regex("""(?:Downloading|Converting(?: \([^)]+\))?|Trimming) (\d+)%""")
    }
}

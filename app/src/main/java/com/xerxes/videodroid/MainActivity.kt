package com.xerxes.videodroid

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.regex.Pattern
import java.util.concurrent.CompletableFuture

class MainActivity : AppCompatActivity(), DownloadService.Listener {

    private lateinit var urlInput: EditText
    private lateinit var dlQuality: Spinner
    private lateinit var localButton: Button
    private lateinit var exportSwitch: Switch
    private lateinit var exportOptions: LinearLayout
    private lateinit var moreSection: LinearLayout
    private lateinit var moreToggle: TextView
    private lateinit var aspectMode: Spinner
    private lateinit var aspectRatio: Spinner
    private lateinit var aspectRatioRow: LinearLayout
    private lateinit var trimSwitch: Switch
    private lateinit var trimOnOff: TextView
    private lateinit var trimFields: LinearLayout
    private lateinit var trimStart: EditText
    private lateinit var trimEnd: EditText
    private lateinit var goButton: Button
    private lateinit var cancelButton: Button
    private lateinit var openPageButton: Button
    private lateinit var jobProgress: LinearProgressIndicator
    private lateinit var status: TextView
    private lateinit var presetsToggle: MaterialButton
    private lateinit var presetsExpanded: ChipGroup
    private var busy = false
    private var lastResultUri: Uri? = null
    private var pendingStart = false
    private var lastFailUrl: String? = null
    private var currentJobId: Long = 0L

    // Database reference
    private lateinit var database: com.xerxes.videodroid.QueueDatabase
    private lateinit var jobDao: com.xerxes.videodroid.QueuedJobDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Room database
        database = com.xerxes.videodroid.QueueDatabase.getInstance(applicationContext)
        jobDao = database.queuedJobDao()

        urlInput = findViewById(R.id.urlInput)
        dlQuality = findViewById(R.id.dlQuality)
        localButton = findViewById(R.id.localButton)
        exportSwitch = findViewById(R.id.exportSwitch)
        exportOptions = findViewById(R.id.exportOptions)
        moreSection = findViewById(R.id.moreSection)
        moreToggle = findViewById(R.id.moreToggle)
        aspectMode = findViewById(R.id.aspectMode)
        aspectRatio = findViewById(R.id.aspectRatio)
        aspectRatioRow = findViewById(R.id.aspectRatioRow)
        trimSwitch = findViewById(R.id.trimSwitch)
        trimOnOff = findViewById(R.id.trimOnOff)
        trimFields = findViewById(R.id.trimFields)
        trimStart = findViewById(R.id.trimStart)
        trimEnd = findViewById(R.id.trimEnd)
        goButton = findViewById(R.id.goButton)
        cancelButton = findViewById(R.id.cancelButton)
        openPageButton = findViewById(R.id.openPageButton)
        jobProgress = findViewById(R.id.jobProgress)
        status = findViewById(R.id.status)
        presetsToggle = findViewById(R.id.presetsToggle)
        presetsExpanded = findViewById(R.id.presetsExpanded)
        hideJobProgress()
        collapsePresets()

        findViewById<Button>(R.id.loginXButton).setOnClickListener {
            startActivity(Intent(this, XLoginActivity::class.java))
        }
        findViewById<Button>(R.id.clearLoginButton).setOnClickListener { clearXCookies(); Toast.makeText(this, "X cookies cleared", Toast.LENGTH_SHORT).show() }

        // Load any pending jobs from database on startup
        loadPendingJobs()

        findViewById<Button>(R.id.goButton).setOnClickListener { startNewJob() }
        findViewById<Button>(R.id.cancelButton).setOnClickListener { cancelCurrentJob() }
        findViewById<Button>(R.id.openPageButton).setOnClickListener { openPage() }
    }

    private fun loadPendingJobs() {
        val pending = jobDao.waiting(this)
        Log.d("MainActivity", "Pending jobs on startup: $pending")
        // If there are queued jobs from previous session, start the first one
        // (the DownloadService will handle this)
    }

    private fun startNewJob() {
        val url = urlInput.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, "Enter a URL", Toast.LENGTH_SHORT).show()
            return
        }

        // Format options from UI
        val dlQuality = dlQuality.selectedItem?.toString() ?: "720p"
        val exportHeight = exportSwitch.isChecked ? exportHeightField.text.toString().toIntOrNull() ?: 1080
        val aspect = aspectMode.selectedItem?.toString() ?: "original"
        val fps = fpsSelector.selectedItem?.toString()?.toIntOrNull() ?: 30
        val crf = crfSelector.selectedItem?.toString()?.toIntOrNull() ?: 3
        val trimEnabled = trimSwitch.isChecked
        val trimStartVal = FormatOptions.parseTrimSeconds(trimStart.text.toString())
        val trimEndVal = FormatOptions.parseTrimSeconds(trimEnd.text.toString())
        val exportEnabled = exportSwitch.isChecked

        val opts = FormatOptions(
            dlQuality = dlQuality,
            exportHeight = exportHeight,
            aspect = aspect,
            fps = fps,
            crf = crf,
            trimEnabled = trimEnabled,
            trimStart = trimStartVal,
            trimEnd = trimEndVal,
            exportEnabled = exportEnabled
        )

        // Enqueue job in database
        val job = com.xerxes.videodroid.QueuedJob(
            url = url,
            dlQuality = dlQuality,
            exportHeight = exportHeight,
            aspect = aspect,
            fps = fps,
            crf = crf,
            trimEnabled = trimEnabled,
            trimStart = trimStartVal,
            trimEnd = trimEndVal,
            exportEnabled = exportEnabled,
            jobType = "download"
        )
        currentJobId = DownloadQueue.enqueue(this, job)

        // Start download service if not already running
        startDownloadService(url, opts, currentJobId)

        urlInput.setText("")
    }

    private fun startDownloadService(url: String, opts: FormatOptions, jobId: Long) {
        val intent = Intent(this, DownloadService::class.java).apply {
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_DLQ, opts.dlQuality)
            putExtra(DownloadService.EXTRA_HEIGHT, opts.exportHeight)
            putExtra(DownloadService.EXTRA_ASPECT, opts.aspect)
            putExtra(DownloadService.EXTRA_QV, opts.crf)
            putExtra(DownloadService.EXTRA_TRIM, opts.trimEnabled)
            putExtra(DownloadService.EXTRA_TSTART, opts.trimStart)
            putExtra(DownloadService.EXTRA_TEND, opts.trimEnd)
            putExtra(DownloadService.EXTRA_EXPORT, opts.exportEnabled)
            putExtra(DownloadService.EXTRA_JOB_ID, jobId)
        }
        startService(intent)
    }

    private fun cancelCurrentJob() {
        DownloadService.requestCancel()
        // Update job status in database
        DownloadQueue.updateJobStatus(this, currentJobId, "CANCELLED", "", 0, System.currentTimeMillis())
        // Stop the notification and service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Toast.makeText(this, "Job cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun clearXCookies() {
        // Clear stored X login cookies
        // Implementation depends on how cookies are stored
        SharedPreferencesManager.clearXCookies(applicationContext)
        Toast.makeText(this, "X cookies cleared", Toast.LENGTH_SHORT).show()
    }

    private fun openPage() {
        // Open page in WebView with security restrictions
        val url = urlInput.text.toString().trim()
        if (url.isBlank()) return
        startActivity(Intent(this, OpenPageActivity::class.java).putExtra("url", url))
    }

    private fun hideJobProgress() {
        jobProgress.visibility = android.view.View.GONE
    }

    private fun showJobProgress(visible: Boolean) {
        jobProgress.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onStatus(msg: String) {
        runOnUiThread { status.text = msg }
    }

    override fun onFinished(result: FormatResult) {
        runOnUiThread {
            if (result.ok) {
                status.text = "Completed"
                Toast.makeText(this, "Download complete", Toast.LENGTH_SHORT).show()
                // Show completed job in UI
                currentJobId = 0L
            } else {
                status.text = "Failed: ${result.message}"
                // Offer retry
                showRetryDialog(result.message)
            }
            hideJobProgress()
        }
    }

    private fun showRetryDialog(error: String) {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Download Failed")
        builder.setMessage("${error}\n\nRetry?")
        builder.setPositiveButton("Retry") {
            // Retry current job
            restartCurrentJob()
        }
        builder.setNegativeButton("Cancel") { /* dismiss */ }
        builder.show()
    }

    private fun restartCurrentJob() {
        // Reset job status to QUEUED and restart
        DownloadQueue.updateJobStatus(this, currentJobId, "QUEUED", "", 0, 0L)
        // Re-enqueue and start service
        startNewJob()
    }

    override fun onTrimChanged(enabled: Boolean, start: Int, end: Int) {
        // Update trim fields visibility/enabled state
        trimFields.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
        trimStart.text = start.toString()
        trimEnd.text = end.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't stop service on activity destroy - it continues in background
        // But clean up any activity-specific state
        currentJobId = 0L
    }
}

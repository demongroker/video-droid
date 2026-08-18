package com.xerxes.videodroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var dlQuality: Spinner
    private lateinit var exportHeight: Spinner
    private lateinit var aspectRatio: Spinner
    private lateinit var trimSwitch: Switch
    private lateinit var trimStart: EditText
    private lateinit var trimEnd: EditText
    private lateinit var goButton: Button
    private lateinit var status: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        dlQuality = findViewById(R.id.dlQuality)
        exportHeight = findViewById(R.id.exportHeight)
        aspectRatio = findViewById(R.id.aspectRatio)
        trimSwitch = findViewById(R.id.trimSwitch)
        trimStart = findViewById(R.id.trimStart)
        trimEnd = findViewById(R.id.trimEnd)
        goButton = findViewById(R.id.goButton)
        status = findViewById(R.id.status)

        fun spinner(s: Spinner, values: Array<String>) {
            ArrayAdapter(this, android.R.layout.simple_spinner_item, values).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                s.adapter = it
            }
        }
        spinner(dlQuality, arrayOf("best", "1080p", "720p", "480p"))
        spinner(exportHeight, arrayOf("720p (HD)", "480p", "1080p"))
        spinner(aspectRatio, arrayOf(
            "original", "crop 4:3", "crop 16:9", "crop 1:1",
            "pad 4:3", "pad 16:9", "pad 9:16"
        ))

        dlQuality.setSelection(2)      // 720p
        exportHeight.setSelection(0)   // 720p
        aspectRatio.setSelection(1)    // crop 4:3

        goButton.setOnClickListener { start() }

        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { urlInput.setText(firstUrl(it)) }
        }

        if (Build.VERSION.SDK_INT < 29) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { urlInput.setText(firstUrl(it)) }
        }
    }

    private fun start() {
        if (busy) return
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) { status.text = "Paste a link first"; return }

        val dlQ = dlQuality.selectedItem.toString()
        val expH = exportHeight.selectedItem.toString().substringBefore(" ").toInt()
        val aspectRaw = aspectRatio.selectedItem.toString()
        val aspect = if (aspectRaw == "original") "original" else aspectRaw.replace(" ", ":")
        val trimOn = trimSwitch.isChecked
        val tStart = trimStart.text.toString().toIntOrNull() ?: 5
        val tEnd = trimEnd.text.toString().toIntOrNull() ?: 3

        val opts = FormatOptions(dlQ, expH, aspect, 30, 23, trimOn, tStart, tEnd)
        busy = true
        goButton.isEnabled = false
        status.text = "Starting..."
        val ctx = applicationContext
        Thread {
            val res = FormatWorker.run(ctx, url, opts) { msg -> runOnUiThread { status.text = msg } }
            runOnUiThread {
                busy = false
                goButton.isEnabled = true
                status.text = if (res.ok && res.uri != null)
                    "Done. Saved to Downloads/VideoDroid\n${res.uri}"
                else
                    "Error: ${res.message}"
            }
        }.start()
    }

    private fun firstUrl(t: String): String {
        val m = Pattern.compile("https?://\\S+").matcher(t)
        return if (m.find()) m.group() else t
    }
}

package com.xerxes.videodroid

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Deliberately plain: no custom theme, no Material components, no helper code
 * that could itself throw. If the rest of the app is broken, this still opens.
 */
class CrashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { setContentView(R.layout.activity_crash) }.onFailure {
            finish()
            return
        }

        val text = CrashHandler.read(this)
        findViewById<TextView>(R.id.crashText)?.text = text

        findViewById<android.widget.Button>(R.id.crashCopy)?.setOnClickListener {
            runCatching {
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("VideoDroid crash", text))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<android.widget.Button>(R.id.crashDismiss)?.setOnClickListener {
            CrashHandler.clear(this)
            finish()
            runCatching { startActivity(android.content.Intent(this, MainActivity::class.java)) }
        }
    }
}

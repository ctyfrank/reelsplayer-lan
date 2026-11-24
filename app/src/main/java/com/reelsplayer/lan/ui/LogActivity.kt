package com.reelsplayer.lan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.reelsplayer.lan.R
import com.reelsplayer.lan.logging.AppLog

/**
 * Shows the in-app log and lets the user export it (share sheet) or copy it.
 *
 * The export is a single text file with a device/app header followed by the log, so it can be
 * pasted or attached to a bug report as-is.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var scroll: ScrollView
    private lateinit var output: TextView
    private lateinit var verboseCheck: MaterialCheckBox

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        scroll = findViewById(R.id.logScroll)
        output = findViewById(R.id.logText)
        verboseCheck = findViewById(R.id.verboseCheck)

        verboseCheck.isChecked = AppLog.verbose
        verboseCheck.setOnCheckedChangeListener { _, checked ->
            AppLog.setVerbose(this, checked)
            render()
        }

        findViewById<Button>(R.id.shareButton).setOnClickListener { shareLog() }
        findViewById<Button>(R.id.copyButton).setOnClickListener { copyLog() }
        findViewById<Button>(R.id.clearButton).setOnClickListener {
            AppLog.clear()
            render()
            Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show()
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun render() {
        val lines = AppLog.snapshot().lines()
        val visible = if (lines.size > MAX_VISIBLE_LINES) {
            lines.takeLast(MAX_VISIBLE_LINES)
        } else {
            lines
        }
        output.text = visible.joinToString("\n").ifBlank { getString(R.string.log_empty) }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun shareLog() {
        val file = AppLog.export(this)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_share_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.log_share_title)))
    }

    private fun copyLog() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("ReelsPlayer LAN log", AppLog.snapshot()))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MAX_VISIBLE_LINES = 2000
    }
}
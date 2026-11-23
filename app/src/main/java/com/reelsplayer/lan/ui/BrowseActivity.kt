package com.reelsplayer.lan.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.reelsplayer.lan.R
import com.reelsplayer.lan.smb.ServerStore
import com.reelsplayer.lan.smb.SmbErrors
import com.reelsplayer.lan.smb.SmbServer
import com.reelsplayer.lan.smb.SmbSession

/**
 * Browses the folders of one SMB server and hands the chosen folder back to [ServersActivity],
 * which forwards it to the player.
 */
class BrowseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_PATH = "path"
        const val RESULT_SERVER_ID = "result_server_id"
        const val RESULT_PATH = "result_path"

        fun intent(context: Context, serverId: String, path: String): Intent =
            Intent(context, BrowseActivity::class.java)
                .putExtra(EXTRA_SERVER_ID, serverId)
                .putExtra(EXTRA_PATH, path)
    }

    private lateinit var store: ServerStore
    private lateinit var server: SmbServer
    private var currentPath: String = ""

    private lateinit var toolbar: MaterialToolbar
    private lateinit var pathView: TextView
    private lateinit var listView: ListView
    private lateinit var progress: View
    private lateinit var emptyView: TextView

    private val entries = ArrayList<SmbSession.SmbDirectory>()
    private lateinit var adapter: DirectoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)

        store = ServerStore.get(this)
        server = store.find(intent.getStringExtra(EXTRA_SERVER_ID)) ?: run {
            finish()
            return
        }

        toolbar = findViewById(R.id.toolbar)
        pathView = findViewById(R.id.pathView)
        listView = findViewById(R.id.dirList)
        progress = findViewById(R.id.progress)
        emptyView = findViewById(R.id.emptyView)

        toolbar.title = server.label.ifBlank { server.host }
        toolbar.setNavigationOnClickListener { navigateUp() }

        adapter = DirectoryAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = entries.getOrNull(position) ?: return@setOnItemClickListener
            currentPath = entry.path
            load()
        }

        findViewById<MaterialButton>(R.id.playButton).setOnClickListener { finishWithResult() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (atRoot()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    navigateUp()
                }
            }
        })

        currentPath = intent.getStringExtra(EXTRA_PATH).orEmpty()
        if (currentPath.isBlank() && server.share.isNotBlank()) currentPath = server.share
        load()
    }

    private fun atRoot(): Boolean =
        currentPath.isBlank() || (server.share.isNotBlank() && currentPath == server.share)

    private fun navigateUp() {
        if (atRoot()) {
            finish()
            return
        }
        currentPath = currentPath.substringBeforeLast('/', "")
        load()
    }

    private fun finishWithResult() {
        store.rememberPath(server.id, currentPath)
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(RESULT_SERVER_ID, server.id)
                .putExtra(RESULT_PATH, currentPath)
        )
        finish()
    }

    private fun load() {
        val path = currentPath
        progress.isVisible = true
        emptyView.isVisible = false
        listView.isVisible = false
        pathView.text = if (path.isBlank()) getString(R.string.browse_share_root) else "/$path"

        Thread {
            val outcome = runCatching {
                if (path.isBlank()) {
                    SmbSession.shares(server).map { SmbSession.SmbDirectory(it, it) }
                } else {
                    SmbSession.directories(server, path)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || currentPath != path) return@runOnUiThread
                progress.isVisible = false
                outcome.onSuccess { result ->
                    entries.clear()
                    entries.addAll(result)
                    adapter.notifyDataSetChanged()
                    listView.isVisible = true
                    emptyView.isVisible = result.isEmpty()
                }.onFailure { error ->
                    entries.clear()
                    adapter.notifyDataSetChanged()
                    listView.isVisible = true
                    emptyView.isVisible = true
                    Toast.makeText(this, SmbErrors.message(this, error), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private inner class DirectoryAdapter : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): SmbSession.SmbDirectory = entries[position]
        override fun getItemId(position: Int): Long = entries[position].path.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_directory, parent, false)
            view.findViewById<TextView>(R.id.directoryName).text = entries[position].name
            return view
        }
    }
}
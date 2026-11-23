package com.reelsplayer.lan.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.reelsplayer.lan.R
import com.reelsplayer.lan.smb.ServerStore
import com.reelsplayer.lan.smb.SmbErrors
import com.reelsplayer.lan.smb.SmbServer
import com.reelsplayer.lan.smb.SmbSession
import java.util.UUID

/** Lists the saved LAN servers and opens the folder browser for the one you tap. */
class ServersActivity : AppCompatActivity() {

    private companion object {
        const val MENU_EDIT = 1
        const val MENU_TEST = 2
        const val MENU_DELETE = 3
    }

    private lateinit var store: ServerStore
    private lateinit var listView: ListView
    private lateinit var emptyView: View
    private lateinit var adapter: ServerAdapter
    private val servers = ArrayList<SmbServer>()

    private val browseLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_servers)
        store = ServerStore.get(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        listView = findViewById(R.id.serverList)
        emptyView = findViewById(R.id.emptyView)
        adapter = ServerAdapter()
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val server = servers.getOrNull(position) ?: return@setOnItemClickListener
            browseLauncher.launch(BrowseActivity.intent(this, server.id, server.lastPath))
        }
        listView.setOnItemLongClickListener { _, view, position, _ ->
            servers.getOrNull(position)?.let { showServerMenu(view, it) }
            true
        }

        findViewById<FloatingActionButton>(R.id.addServer).setOnClickListener {
            showEditDialog(null)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        servers.clear()
        servers.addAll(store.all())
        adapter.notifyDataSetChanged()
        emptyView.isVisible = servers.isEmpty()
        listView.isVisible = servers.isNotEmpty()
    }

    private fun showServerMenu(anchor: View, server: SmbServer) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_EDIT, 0, R.string.edit_server)
            menu.add(0, MENU_TEST, 1, R.string.test_connection)
            menu.add(0, MENU_DELETE, 2, R.string.delete_server)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EDIT -> {
                        showEditDialog(server)
                        true
                    }

                    MENU_TEST -> {
                        testServer(server)
                        true
                    }

                    MENU_DELETE -> {
                        confirmDelete(server)
                        true
                    }

                    else -> false
                }
            }
            show()
        }
    }

    private fun confirmDelete(server: SmbServer) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_server_title)
            .setMessage(
                getString(R.string.delete_server_message, server.label.ifBlank { server.host })
            )
            .setPositiveButton(R.string.delete_server) { _, _ ->
                store.delete(server.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @Suppress("ClickableViewAccessibility")
    private fun showEditDialog(existing: SmbServer?) {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_server_edit, null)

        val labelInput = content.findViewById<TextInputEditText>(R.id.inputLabel)
        val hostInput = content.findViewById<TextInputEditText>(R.id.inputHost)
        val portInput = content.findViewById<TextInputEditText>(R.id.inputPort)
        val shareInput = content.findViewById<TextInputEditText>(R.id.inputShare)
        val userInput = content.findViewById<TextInputEditText>(R.id.inputUser)
        val passwordInput = content.findViewById<TextInputEditText>(R.id.inputPassword)
        val domainInput = content.findViewById<TextInputEditText>(R.id.inputDomain)
        val guestCheck = content.findViewById<MaterialCheckBox>(R.id.checkGuest)
        val smb1Check = content.findViewById<MaterialCheckBox>(R.id.checkSmb1)

        existing?.let { server ->
            labelInput.setText(server.label)
            hostInput.setText(server.host)
            portInput.setText(server.port.toString())
            shareInput.setText(server.share)
            userInput.setText(server.username)
            passwordInput.setText(server.password)
            domainInput.setText(server.domain)
            guestCheck.isChecked = server.guest
            smb1Check.isChecked = server.smb1
        }

        fun refreshFieldState() {
            val credentialsEnabled = !guestCheck.isChecked
            userInput.isEnabled = credentialsEnabled
            passwordInput.isEnabled = credentialsEnabled
            domainInput.isEnabled = credentialsEnabled
        }

        guestCheck.setOnCheckedChangeListener { _, _ -> refreshFieldState() }
        refreshFieldState()

        fun readForm(): SmbServer? {
            val host = hostInput.text?.toString()?.trim().orEmpty()
            if (host.isEmpty()) {
                hostInput.error = getString(R.string.host_required)
                return null
            }
            val port = portInput.text?.toString()?.trim()?.toIntOrNull()
                ?.coerceIn(1, 65535) ?: SmbServer.DEFAULT_PORT
            val guest = guestCheck.isChecked
            val label = labelInput.text?.toString()?.trim().orEmpty()
            val share = shareInput.text?.toString()?.trim().orEmpty()
            // A different share invalidates the remembered folder.
            val lastPath = if (existing != null && existing.share == share) existing.lastPath else ""
            return SmbServer(
                id = existing?.id ?: UUID.randomUUID().toString(),
                label = label.ifBlank { host },
                host = host,
                port = port,
                share = share,
                username = if (guest) "" else userInput.text?.toString()?.trim().orEmpty(),
                password = if (guest) "" else passwordInput.text?.toString().orEmpty(),
                domain = domainInput.text?.toString()?.trim().orEmpty(),
                guest = guest,
                smb1 = smb1Check.isChecked,
                lastPath = lastPath
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.add_server else R.string.edit_server)
            .setView(content)
            .setPositiveButton(R.string.save, null)
            .setNeutralButton(R.string.test_connection, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val server = readForm() ?: return@setOnClickListener
                store.save(server)
                refresh()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                readForm()?.let { testServer(it) }
            }
        }
        dialog.show()
    }

    private fun testServer(server: SmbServer) {
        Toast.makeText(this, R.string.connecting, Toast.LENGTH_SHORT).show()
        Thread {
            val outcome = runCatching {
                if (server.share.isNotBlank()) {
                    SmbSession.directories(server, server.share).size
                } else {
                    SmbSession.shares(server).size
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.onSuccess { count ->
                    Toast.makeText(
                        this,
                        getString(R.string.connection_ok, count),
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        getString(R.string.connection_failed, SmbErrors.message(this, error)),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private inner class ServerAdapter : BaseAdapter() {
        override fun getCount(): Int = servers.size
        override fun getItem(position: Int): SmbServer = servers[position]
        override fun getItemId(position: Int): Long = servers[position].id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false)
            val server = servers[position]
            view.findViewById<TextView>(R.id.serverLabel).text =
                server.label.ifBlank { server.host }
            view.findViewById<TextView>(R.id.serverAddress).text = server.address
            return view
        }
    }
}
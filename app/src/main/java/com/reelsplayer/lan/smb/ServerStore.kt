package com.reelsplayer.lan.smb

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * App-wide list of saved LAN servers.
 *
 * The whole list — passwords included — is serialized to JSON and stored encrypted by
 * [CredentialCipher], which keeps the key in the Android keystore.
 */
class ServerStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val servers = LinkedHashMap<String, SmbServer>()

    init {
        load()
    }

    @Synchronized
    fun all(): List<SmbServer> = ArrayList(servers.values)

    @Synchronized
    fun find(id: String?): SmbServer? = id?.let { servers[it] }

    @Synchronized
    fun save(server: SmbServer) {
        servers[server.id] = server
        // Host, share or credentials may have changed: drop the cached session.
        SmbSession.disconnect(server.id)
        persist()
    }

    @Synchronized
    fun delete(id: String) {
        if (servers.remove(id) == null) return
        SmbSession.disconnect(id)
        persist()
    }

    /** Remembers the last browsed folder so reopening a server resumes where you left off. */
    @Synchronized
    fun rememberPath(id: String, path: String) {
        val current = servers[id] ?: return
        if (current.lastPath == path) return
        servers[id] = current.copy(lastPath = path)
        persist()
    }

    private fun load() {
        val encoded = prefs.getString(KEY_SERVERS, null) ?: return
        try {
            SmbServer.decodeList(CredentialCipher.decrypt(encoded)).forEach { servers[it.id] = it }
        } catch (e: Exception) {
            Log.w(TAG, "Saved servers could not be decrypted", e)
        }
    }

    private fun persist() {
        try {
            val payload = CredentialCipher.encrypt(SmbServer.encodeList(all()))
            prefs.edit().putString(KEY_SERVERS, payload).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Saved servers could not be written", e)
        }
    }

    companion object {
        private const val TAG = "ServerStore"
        private const val PREFS_NAME = "lan_servers"
        private const val KEY_SERVERS = "servers_v1"

        @Volatile
        private var instance: ServerStore? = null

        fun get(context: Context): ServerStore =
            instance ?: synchronized(this) {
                instance ?: ServerStore(context.applicationContext).also { instance = it }
            }
    }
}
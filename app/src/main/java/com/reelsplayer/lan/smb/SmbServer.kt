package com.reelsplayer.lan.smb

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A saved LAN / SMB server.
 *
 * Instances are immutable; [id] is stable so playback URIs keep pointing at the right
 * server even after the profile is edited. The password is part of the model but only
 * ever written to the encrypted store (see [ServerStore] / [CredentialCipher]).
 */
data class SmbServer(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
    val share: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val guest: Boolean = false,
    val smb1: Boolean = false,
    val lastPath: String = ""
) {

    /** `192.168.1.10/media` — shown under the label in the server list. */
    val address: String
        get() = buildString {
            append(host)
            if (port != DEFAULT_PORT) append(':').append(port)
            if (share.isNotBlank()) append('/').append(share)
        }

    /** Identity of the login, used to drop a cached SMB session when credentials change. */
    internal fun sessionKey(): String =
        "$host|$port|$domain|$username|${guest}|${smb1}|${password.hashCode()}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("host", host)
        put("port", port)
        put("share", share)
        put("username", username)
        put("password", password)
        put("domain", domain)
        put("guest", guest)
        put("smb1", smb1)
        put("lastPath", lastPath)
    }

    companion object {
        const val DEFAULT_PORT = 445

        fun fromJson(json: JSONObject): SmbServer = SmbServer(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            label = json.optString("label"),
            host = json.optString("host"),
            port = json.optInt("port", DEFAULT_PORT),
            share = json.optString("share"),
            username = json.optString("username"),
            password = json.optString("password"),
            domain = json.optString("domain"),
            guest = json.optBoolean("guest"),
            smb1 = json.optBoolean("smb1"),
            lastPath = json.optString("lastPath")
        )

        fun encodeList(servers: List<SmbServer>): String =
            JSONArray().apply { servers.forEach { put(it.toJson()) } }.toString()

        fun decodeList(raw: String): List<SmbServer> {
            val array = JSONArray(raw)
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }
    }
}
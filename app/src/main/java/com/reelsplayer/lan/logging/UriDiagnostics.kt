package com.reelsplayer.lan.logging

import android.content.Context
import android.net.Uri
import com.reelsplayer.lan.smb.ServerStore
import com.reelsplayer.lan.smb.SmbUri

/** Turns a playback URI into a log line that says which server and file it points at. */
object UriDiagnostics {

    fun describe(context: Context, uri: Uri): String {
        if (!SmbUri.isSmb(uri)) return uri.toString()
        val serverId = SmbUri.serverId(uri) ?: return uri.toString()
        val path = SmbUri.path(uri)
        val server = ServerStore.get(context).find(serverId)
            ?: return "server=$serverId (missing) path=/$path"
        return "server=${server.label.ifBlank { server.host }} host=${server.host}:${server.port} " +
            "share=${server.share.ifBlank { "-" }} user=${server.username.ifBlank { "-" }} " +
            "guest=${server.guest} smb1=${server.smb1} path=/$path"
    }
}
package com.reelsplayer.lan.smb

import android.net.Uri

/**
 * Playback URIs for LAN files.
 *
 * The URI only carries the server id and the share-relative path:
 * `reelssmb://<server-id>/<share>/<dir>/<file>.mp4`
 *
 * Credentials stay out of the URI on purpose — they are looked up by id when the
 * data source opens the file, so a URI can safely be put in a MediaItem or a log.
 */
object SmbUri {

    const val SCHEME = "reelssmb"

    fun build(serverId: String, path: String): Uri =
        Uri.parse("$SCHEME://$serverId/${encodePath(path)}")

    fun isSmb(uri: Uri): Boolean = uri.scheme == SCHEME

    fun serverId(uri: Uri): String? = if (isSmb(uri)) uri.authority else null

    /** Decoded `share/dir/file` path, or "" for the share root. */
    fun path(uri: Uri): String = uri.pathSegments.joinToString("/")

    internal fun encodePath(path: String): String =
        path.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { Uri.encode(it, "") }
}
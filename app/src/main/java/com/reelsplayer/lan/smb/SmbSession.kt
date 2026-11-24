package com.reelsplayer.lan.smb

import android.net.Uri
import com.reelsplayer.lan.logging.AppLog
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin wrapper around jcifs-ng: one authenticated [CIFSContext] per saved server,
 * directory listing, recursive scanning and random-access streaming reads.
 *
 * All methods block on network I/O and must be called off the main thread.
 */
object SmbSession {

    private const val TAG = "SMB"
    private const val READ_TIMEOUT_MS = 30_000
    private const val CONNECT_TIMEOUT_MS = 15_000

    /** Read-ahead window used for streaming; larger windows mean fewer SMB round trips. */
    private const val STREAM_BUFFER_BYTES = 1 shl 20

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "3g2", "ts", "m2ts",
        "mts", "flv", "wmv", "mpg", "mpeg", "ogv", "rmvb", "rm"
    )

    private val IGNORED_DIRECTORIES = setOf(
        "system volume information", "\$recycle.bin", "@eadir", "#recycle", "lost+found"
    )

    private data class CachedContext(val loginKey: String, val context: CIFSContext)

    private val contexts = ConcurrentHashMap<String, CachedContext>()

    /** Returns the (cached) authenticated context for [server]. */
    fun context(server: SmbServer): CIFSContext {
        val loginKey = server.sessionKey()
        val cached = contexts[server.id]
        if (cached != null && cached.loginKey == loginKey) return cached.context
        if (cached != null) closeQuietly(cached.context)

        val created = createContext(server)
        contexts[server.id] = CachedContext(loginKey, created)
        AppLog.i(
            TAG,
            "session created host=${server.host}:${server.port} " +
                "user=${server.username.ifBlank { "-" }} guest=${server.guest} smb1=${server.smb1}"
        )
        return created
    }

    private fun createContext(server: SmbServer): CIFSContext {
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", if (server.smb1) "SMB1" else "SMB202")
            setProperty("jcifs.smb.client.maxVersion", if (server.smb1) "SMB1" else "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", READ_TIMEOUT_MS.toString())
            setProperty("jcifs.smb.client.connTimeout", CONNECT_TIMEOUT_MS.toString())
            setProperty("jcifs.smb.client.attrExpirationPeriod", "5000")
            setProperty("jcifs.smb.client.dfs.disabled", "true")
        }
        val base = BaseContext(PropertyConfiguration(properties))
        return when {
            server.guest && server.username.isBlank() -> base.withGuestCrendentials()
            server.username.isBlank() -> base.withAnonymousCredentials()
            else -> base.withCredentials(
                NtlmPasswordAuthenticator(
                    server.domain.ifBlank { null },
                    server.username,
                    server.password
                )
            )
        }
    }

    /** Closes and forgets the session of one server (call after editing or deleting it). */
    fun disconnect(serverId: String) {
        contexts.remove(serverId)?.let {
            closeQuietly(it.context)
            AppLog.d(TAG, "session closed server=$serverId")
        }
    }

    fun disconnectAll() {
        contexts.values.forEach { closeQuietly(it.context) }
        contexts.clear()
    }

    private fun closeQuietly(context: CIFSContext) {
        try {
            context.close()
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- paths

    private fun authorityOf(server: SmbServer): String =
        if (server.port == SmbServer.DEFAULT_PORT) server.host else "${server.host}:${server.port}"

    internal fun urlFor(server: SmbServer, path: String, directory: Boolean): String {
        // jcifs-ng does not percent-decode SMB URLs, so the path has to go in raw:
        // "My%20Movie.mp4" would be looked up as a file literally named that.
        val normalized = path.split('/').filter { it.isNotEmpty() }.joinToString("/")
        val suffix = if (directory && normalized.isNotEmpty()) "/" else ""
        return "smb://${authorityOf(server)}/$normalized$suffix"
    }

    fun file(server: SmbServer, path: String, directory: Boolean = false): SmbFile =
        SmbFile(urlFor(server, path, directory), context(server))

    /** Opens a seekable stream with a read-ahead buffer, used by the media data source. */
    fun openStream(server: SmbServer, path: String): SmbRandomAccessFile =
        SmbRandomAccessFile(urlFor(server, path, directory = false), "r", STREAM_BUFFER_BYTES, context(server))

    private fun joinPath(parent: String, name: String): String =
        if (parent.isBlank()) name else "$parent/$name"

    // ------------------------------------------------------------- browsing

    data class SmbDirectory(val name: String, val path: String)

    /** Lists the shares exported by [server] (needs a server that permits share enumeration). */
    fun shares(server: SmbServer): List<String> {
        val root = file(server, "", directory = true)
        return try {
            val result = root.listFiles()
                .mapNotNull { child ->
                    val name = child.name.trim().trimEnd('/')
                    if (child.isDirectory && name.isNotEmpty() && !isIgnoredDirectory(name)) name else null
                }
                .distinct()
                .sortedBy { it.lowercase() }
            AppLog.i(TAG, "shares host=${server.host} -> ${result.size} (${result.joinToString(",")})")
            result
        } catch (e: Exception) {
            AppLog.e(TAG, "shares failed host=${server.host}:${server.port}", e)
            throw e
        } finally {
            try {
                root.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Sub-folders of [path], sorted case-insensitively. */
    fun directories(server: SmbServer, path: String): List<SmbDirectory> {
        val dir = file(server, path, directory = true)
        return try {
            val result = dir.listFiles()
                .mapNotNull { child ->
                    val name = child.name.trim().trimEnd('/')
                    if (!child.isDirectory || name.isEmpty() || isIgnoredDirectory(name)) null
                    else SmbDirectory(name, joinPath(path, name))
                }
                .sortedBy { it.name.lowercase() }
            AppLog.d(TAG, "list path=/$path -> ${result.size} folders")
            result
        } catch (e: Exception) {
            AppLog.e(TAG, "list failed host=${server.host} path=/$path", e)
            throw e
        } finally {
            try {
                dir.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Depth-first scan of [path] collecting every video below it.
     *
     * @param relativePrefix prefix prepended to each result name, e.g. "Films/"
     * @param isStale polled between entries so a cancelled scan stops quickly
     * @param onFound invoked with the running count, for progress UI
     */
    fun scanVideos(
        server: SmbServer,
        path: String,
        relativePrefix: String = "",
        isStale: () -> Boolean = { false },
        onFound: (Int) -> Unit = {}
    ): List<Pair<String, Uri>> {
        val found = ArrayList<Pair<String, Uri>>()
        val visited = HashSet<String>()
        val startedAt = System.currentTimeMillis()
        AppLog.i(
            TAG,
            "scan start host=${server.host} path=/${path.ifBlank { server.share }} " +
                "user=${server.username.ifBlank { "-" }}"
        )

        fun walk(currentPath: String, relative: String, failOnError: Boolean) {
            if (isStale()) return
            val dir = file(server, currentPath, directory = true)
            try {
                if (!visited.add(dir.uncPath.lowercase())) return
                val children = try {
                    dir.listFiles()
                } catch (e: Exception) {
                    // The first listing must surface errors (wrong password, missing share);
                    // failures deeper in the tree only skip that sub-folder.
                    if (failOnError) throw e
                    AppLog.w(TAG, "skip folder /$currentPath: ${AppLog.describe(e)}")
                    return
                }
                AppLog.d(TAG, "scan dir /$currentPath -> ${children.size} entries")
                for (child in children) {
                    if (isStale()) return
                    val name = child.name.trim().trimEnd('/')
                    if (name.isEmpty() || name.startsWith(".")) continue
                    if (child.isDirectory) {
                        if (isIgnoredDirectory(name)) continue
                        walk(joinPath(currentPath, name), "$relative$name/", failOnError = false)
                    } else if (isVideo(name)) {
                        found.add(
                            Pair(
                                "$relative$name",
                                SmbUri.build(server.id, joinPath(currentPath, name))
                            )
                        )
                        onFound(found.size)
                    }
                }
            } finally {
                try {
                    dir.close()
                } catch (_: Exception) {
                }
            }
        }

        walk(path, relativePrefix, failOnError = true)
        val sorted = found.sortedBy { it.first.lowercase() }
        AppLog.i(
            TAG,
            "scan done path=/${path.ifBlank { server.share }} videos=${sorted.size} " +
                "in ${System.currentTimeMillis() - startedAt}ms" +
                if (isStale()) " (cancelled)" else ""
        )
        return sorted
    }

    // ---------------------------------------------------------------- utils

    private fun isVideo(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension.isNotEmpty() && extension in VIDEO_EXTENSIONS
    }

    private fun isIgnoredDirectory(name: String): Boolean =
        name.lowercase() in IGNORED_DIRECTORIES

    /** File size without opening a stream; returns 0 when the server does not report it. */
    fun sizeOf(server: SmbServer, path: String): Long =
        try {
            file(server, path).use { it.length() }
        } catch (_: Exception) {
            0L
        }
}
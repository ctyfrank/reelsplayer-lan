package com.reelsplayer.lan.smb

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.reelsplayer.lan.logging.AppLog
import jcifs.smb.NtStatus
import jcifs.smb.SmbException
import jcifs.smb.SmbRandomAccessFile

/**
 * Streams `reelssmb://` files straight from an SMB share into ExoPlayer.
 *
 * The source is seekable (the player needs that for MP4 and for scrubbing) and every
 * open keeps a 1 MiB read-ahead window, so playback does one round trip per megabyte
 * instead of one per read call.
 *
 * jcifs opens SMB files unshared, so two handles on the same path make the server answer
 * `STATUS_SHARING_VIOLATION`. Opens are therefore reported to [SmbSession] and callers such as
 * the prefetcher check that registry before touching a file a player already holds.
 */
@UnstableApi
class SmbDataSource(
    private val serverResolver: (String) -> SmbServer?
) : BaseDataSource(/* isNetwork = */ true) {

    private var stream: SmbRandomAccessFile? = null
    private var currentUri: Uri? = null
    private var bytesRemaining: Long = 0

    private var descriptor: String = ""
    private var bytesRead: Long = 0
    private var seekCount: Int = 0
    private var openedAt: Long = 0

    /** True once [open] succeeded, i.e. a [DataSpec] reached [transferStarted]. */
    private var started = false
    private var openKeyOwner: String? = null
    private var openKeyPath: String? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)

        val serverId = SmbUri.serverId(dataSpec.uri)
            ?: throw DataSourceException(
                IllegalArgumentException("Not a LAN uri: ${dataSpec.uri}"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        val server = serverResolver(serverId)
            ?: throw DataSourceException(
                IllegalStateException("Saved server $serverId no longer exists"),
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            )

        val path = SmbUri.path(dataSpec.uri)
        descriptor = describe(server, path)
        val startedAt = SystemClock.elapsedRealtime()

        val opened = try {
            SmbSession.openStream(server, path)
        } catch (e: Exception) {
            if (isCancellation(e)) {
                // ExoPlayer cancels a load by interrupting it; that is not an error worth showing.
                AppLog.d(TAG, "open cancelled $descriptor")
            } else {
                AppLog.e(
                    TAG,
                    "open failed $descriptor pos=${dataSpec.position} length=${dataSpec.length}",
                    e
                )
            }
            throw DataSourceException(e, errorCodeOf(e))
        }

        val length: Long
        try {
            length = opened.length()
        } catch (e: Exception) {
            closeQuietly(opened)
            AppLog.e(TAG, "stat failed $descriptor", e)
            throw DataSourceException(e, errorCodeOf(e))
        }

        if (dataSpec.position > length) {
            closeQuietly(opened)
            AppLog.e(
                TAG,
                "position out of range $descriptor pos=${dataSpec.position} size=$length"
            )
            throw DataSourceException(
                IllegalStateException(
                    "Position ${dataSpec.position} is beyond the end of $length bytes"
                ),
                @Suppress("DEPRECATION") DataSourceException.POSITION_OUT_OF_RANGE
            )
        }

        try {
            if (dataSpec.position > 0) {
                opened.seek(dataSpec.position)
                seekCount++
            }
        } catch (e: Exception) {
            closeQuietly(opened)
            AppLog.e(TAG, "seek failed $descriptor pos=${dataSpec.position}", e)
            throw DataSourceException(e, errorCodeOf(e))
        }

        stream = opened
        currentUri = dataSpec.uri
        bytesRemaining = if (dataSpec.length >= 0) {
            minOf(dataSpec.length, length - dataSpec.position)
        } else {
            length - dataSpec.position
        }
        bytesRead = 0
        openedAt = SystemClock.elapsedRealtime()
        openKeyOwner = serverId
        openKeyPath = path
        SmbSession.markOpen(serverId, path)

        AppLog.i(
            TAG,
            "open $descriptor pos=${dataSpec.position} request=${dataSpec.length} " +
                "size=$length connect=${SystemClock.elapsedRealtime() - startedAt}ms"
        )

        started = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val source = stream ?: return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val read = try {
            source.read(buffer, offset, toRead)
        } catch (e: Exception) {
            if (isCancellation(e)) {
                AppLog.d(TAG, "read cancelled $descriptor at=$bytesRead")
            } else {
                AppLog.e(
                    TAG,
                    "read failed $descriptor at=${bytesRead} remaining=$bytesRemaining",
                    e
                )
            }
            throw DataSourceException(e, errorCodeOf(e))
        }

        if (read == -1) {
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }

        bytesRead += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        val wasOpen = stream != null
        stream?.let { closeQuietly(it) }
        stream = null
        currentUri = null

        // transferEnded() must only follow a successful open: BaseDataSource keeps the
        // DataSpec from transferStarted(), and calling it after a failed open feeds a null
        // DataSpec to the bandwidth meter, which crashes the playback with an NPE.
        if (started) {
            started = false
            transferEnded()
        }

        openKeyOwner?.let { owner ->
            openKeyPath?.let { path -> SmbSession.markClosed(owner, path) }
        }
        openKeyOwner = null
        openKeyPath = null

        if (wasOpen) {
            val elapsed = (SystemClock.elapsedRealtime() - openedAt).coerceAtLeast(1)
            val kbps = bytesRead / 1024.0 / (elapsed / 1000.0)
            AppLog.i(
                TAG,
                "close $descriptor read=${bytesRead}B in ${elapsed}ms " +
                    "(${"%.0f".format(kbps)} KB/s) seeks=$seekCount"
            )
        }
    }

    private fun describe(server: SmbServer, path: String): String =
        "host=${server.host}:${server.port} share=${server.share.ifBlank { "-" }} " +
            "user=${server.username.ifBlank { "-" }} guest=${server.guest} " +
            "smb1=${server.smb1} path=/$path"

    private fun closeQuietly(target: SmbRandomAccessFile) {
        try {
            target.close()
        } catch (_: Exception) {
        }
    }

    /** ExoPlayer cancels loads by interrupting the loading thread; jcifs reports that as a failure. */
    private fun isCancellation(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is InterruptedException }

    private fun errorCodeOf(error: Throwable): Int {
        val smbError = generateSequence(error) { it.cause }
            .filterIsInstance<SmbException>()
            .firstOrNull()
        return when {
            smbError == null -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            smbError.ntStatus == NtStatus.NT_STATUS_OBJECT_NAME_NOT_FOUND ->
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            smbError.ntStatus == NtStatus.NT_STATUS_ACCESS_DENIED ->
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION
            else -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        }
    }

    /** Creates [SmbDataSource] instances for the player. */
    class Factory(
        private val serverResolver: (String) -> SmbServer?
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(serverResolver)
    }

    private companion object {
        const val TAG = "SMB"
    }
}
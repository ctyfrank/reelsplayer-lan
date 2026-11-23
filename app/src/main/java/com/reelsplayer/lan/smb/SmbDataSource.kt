package com.reelsplayer.lan.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import jcifs.smb.NtStatus
import jcifs.smb.SmbException
import jcifs.smb.SmbRandomAccessFile

/**
 * Streams `reelssmb://` files straight from an SMB share into ExoPlayer.
 *
 * The source is seekable (the player needs that for MP4 and for scrubbing) and every
 * open keeps a 1 MiB read-ahead window, so playback does one round trip per megabyte
 * instead of one per read call.
 */
@UnstableApi
class SmbDataSource(
    private val serverResolver: (String) -> SmbServer?
) : BaseDataSource(/* isNetwork = */ true) {

    private var stream: SmbRandomAccessFile? = null
    private var currentUri: Uri? = null
    private var bytesRemaining: Long = 0

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

        val opened = try {
            SmbSession.openStream(server, SmbUri.path(dataSpec.uri))
        } catch (e: Exception) {
            throw DataSourceException(e, errorCodeOf(e))
        }

        val length: Long
        try {
            length = opened.length()
        } catch (e: Exception) {
            closeQuietly(opened)
            throw DataSourceException(e, errorCodeOf(e))
        }

        if (dataSpec.position > length) {
            closeQuietly(opened)
            throw DataSourceException(
                IllegalStateException("Position ${dataSpec.position} is beyond the end of $length bytes"),
                @Suppress("DEPRECATION") DataSourceException.POSITION_OUT_OF_RANGE
            )
        }

        try {
            if (dataSpec.position > 0) opened.seek(dataSpec.position)
        } catch (e: Exception) {
            closeQuietly(opened)
            throw DataSourceException(e, errorCodeOf(e))
        }

        stream = opened
        currentUri = dataSpec.uri
        bytesRemaining = if (dataSpec.length >= 0) {
            minOf(dataSpec.length, length - dataSpec.position)
        } else {
            length - dataSpec.position
        }

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
            throw DataSourceException(e, errorCodeOf(e))
        }

        if (read == -1) {
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }

        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        stream?.let { closeQuietly(it) }
        stream = null
        currentUri = null
        transferEnded()
    }

    private fun closeQuietly(target: SmbRandomAccessFile) {
        try {
            target.close()
        } catch (_: Exception) {
        }
    }

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
}
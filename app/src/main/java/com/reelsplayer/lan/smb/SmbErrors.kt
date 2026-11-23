package com.reelsplayer.lan.smb

import android.content.Context
import com.reelsplayer.lan.R
import jcifs.smb.NtStatus
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Turns SMB/network exceptions into short, user-facing messages. */
object SmbErrors {

    private val accessDeniedStatuses = setOf(
        NtStatus.NT_STATUS_ACCESS_DENIED,
        NtStatus.NT_STATUS_NETWORK_ACCESS_DENIED,
        NtStatus.NT_STATUS_LOGON_FAILURE
    )

    private val missingShareStatuses = setOf(
        NtStatus.NT_STATUS_BAD_NETWORK_NAME,
        NtStatus.NT_STATUS_OBJECT_NAME_NOT_FOUND,
        NtStatus.NT_STATUS_OBJECT_PATH_NOT_FOUND
    )

    fun message(context: Context, error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val smbError = causes.filterIsInstance<SmbException>().firstOrNull()

        return when {
            causes.any { it is SmbAuthException } ->
                context.getString(R.string.error_auth)

            smbError != null && smbError.ntStatus in accessDeniedStatuses ->
                context.getString(R.string.error_access_denied)

            smbError != null && smbError.ntStatus in missingShareStatuses ->
                context.getString(R.string.error_share_not_found)

            causes.any { it is UnknownHostException } ->
                context.getString(R.string.error_unknown_host)

            causes.any { it is SocketTimeoutException } ->
                context.getString(R.string.error_timeout)

            causes.any { it is ConnectException || it is NoRouteToHostException } ->
                context.getString(R.string.error_unreachable)

            else -> error.message ?: context.getString(R.string.error_generic)
        }
    }
}
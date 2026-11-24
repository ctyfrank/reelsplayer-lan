package com.reelsplayer.lan.logging

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.reelsplayer.lan.BuildConfig
import com.reelsplayer.lan.smb.ServerStore
import jcifs.smb.NtStatus
import jcifs.smb.SmbException
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * In-app log buffer with file mirror and export.
 *
 * Everything interesting is written here instead of relying on logcat: playback errors with their
 * full cause chain, SMB connection/listing/streaming details, scan results. The buffer is also
 * appended to `filesDir/reelsplayer-lan.log` so a crash report survives the process.
 *
 * Passwords are never logged: callers pass host/user/share, not credentials.
 */
object AppLog {

    private const val MAX_ENTRIES = 4000
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val FILE_NAME = "reelsplayer-lan.log"
    private const val PREFS = "logging"
    private const val KEY_VERBOSE = "verbose"

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    private val stampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)

    private class Entry(val time: LocalTime, val level: Char, val tag: String, val body: String) {
        fun format(): String = "${timeFormat.format(time)} $level/$tag: $body"
    }

    private val entries = ArrayDeque<Entry>()
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "app-log").apply { isDaemon = true }
    }

    private var logFile: File? = null
    private var appContext: Context? = null

    /** When on, per-item debug lines (bindings, listings, prefetch) are recorded too. */
    @Volatile
    var verbose: Boolean = false
        private set

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        logFile = File(app.filesDir, FILE_NAME)
        verbose = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VERBOSE, false)
        rotateIfNeeded()
    }

    fun setVerbose(context: Context, enabled: Boolean) {
        verbose = enabled
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VERBOSE, enabled)
            .apply()
        i("Log", "verbose logging ${if (enabled) "on" else "off"}")
    }

    // ------------------------------------------------------------- levels

    fun d(tag: String, message: String) {
        if (verbose) log('D', tag, message, null)
    }

    fun i(tag: String, message: String) = log('I', tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = log('W', tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = log('E', tag, message, error)

    private fun log(level: Char, tag: String, message: String, error: Throwable?) {
        val line = Entry(LocalTime.now(), level, tag, message)
        synchronized(lock) {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        appendToFile(line.format())

        if (error != null) {
            val trace = describe(error) + "\n" + error.stackTraceToString()
            val traceEntry = Entry(LocalTime.now(), level, tag, trace)
            synchronized(lock) {
                entries.addLast(traceEntry)
                while (entries.size > MAX_ENTRIES) entries.removeFirst()
            }
            appendToFile(traceEntry.format())
        }
    }

    /** One-line cause chain, with the SMB NT status decoded when present. */
    fun describe(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            if (depth > 0) append(" <- ")
            append(current.javaClass.simpleName)
            current.message?.let { append(": ").append(it) }
            if (current is SmbException) {
                append(" [ntStatus=").append(ntStatusName(current.ntStatus)).append(']')
            }
            current = current.cause
            depth++
        }
    }

    private fun ntStatusName(status: Int): String = when (status) {
        NtStatus.NT_STATUS_ACCESS_DENIED -> "ACCESS_DENIED 0xC0000022"
        NtStatus.NT_STATUS_NETWORK_ACCESS_DENIED -> "NETWORK_ACCESS_DENIED 0xC00000CA"
        NtStatus.NT_STATUS_LOGON_FAILURE -> "LOGON_FAILURE 0xC000006D"
        NtStatus.NT_STATUS_BAD_NETWORK_NAME -> "BAD_NETWORK_NAME 0xC00000CC"
        NtStatus.NT_STATUS_OBJECT_NAME_NOT_FOUND -> "OBJECT_NAME_NOT_FOUND 0xC0000034"
        NtStatus.NT_STATUS_OBJECT_PATH_NOT_FOUND -> "OBJECT_PATH_NOT_FOUND 0xC000003A"
        NtStatus.NT_STATUS_CONNECTION_REFUSED -> "CONNECTION_REFUSED 0xC0000236"
        NtStatus.NT_STATUS_PIPE_BROKEN -> "PIPE_BROKEN 0xC000014B"
        else -> "0x" + Integer.toHexString(status).uppercase().padStart(8, '0')
    }

    // ------------------------------------------------------------- storage

    private fun appendToFile(line: String) {
        val file = logFile ?: return
        writer.execute {
            runCatching {
                if (file.length() > MAX_FILE_BYTES) {
                    val previous = File(file.parentFile, "$FILE_NAME.1")
                    previous.delete()
                    file.renameTo(previous)
                }
                file.appendText(line + "\n")
            }
        }
    }

    private fun rotateIfNeeded() {
        val file = logFile ?: return
        if (file.length() > MAX_FILE_BYTES) {
            val previous = File(file.parentFile, "$FILE_NAME.1")
            previous.delete()
            file.renameTo(previous)
        }
    }

    fun snapshot(): String = synchronized(lock) {
        entries.joinToString("\n") { it.format() }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
        writer.execute { runCatching { logFile?.writeText("") } }
    }

    /** Blocks until queued writes are on disk, so a crash report is complete. */
    fun flush() {
        runCatching { writer.submit { }.get(5, TimeUnit.SECONDS) }
    }

    /** Writes a self-contained report (device header + whole log) into the cache dir. */
    fun export(context: Context): File {
        flush()
        val directory = File(context.cacheDir, "logs").apply { mkdirs() }
        val target = File(
            directory,
            "reelsplayer-lan-${stampFormat.format(LocalDateTime.now())}.log"
        )
        val body = buildString {
            append(header(context))
            append("\n\n===== log =====\n")
            val file = logFile
            if (file != null && file.exists()) {
                append(file.readText())
            } else {
                append(snapshot())
            }
        }
        target.writeText(body)
        i("Log", "exported to ${target.name} (${target.length()} bytes)")
        return target
    }

    private fun header(context: Context): String = buildString {
        appendLine("ReelsPlayer LAN ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine(
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.firstOrNull()}, " +
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        )
        appendLine(
            "Locale: ${Locale.getDefault()}, Time zone: ${java.util.TimeZone.getDefault().id}, " +
                "App started: ${LocalDateTime.now()}"
        )
        appendLine("Network: ${networkSummary(context)}")
        appendLine("Stream cache: ${cacheSize(context)}")
        val servers = ServerStore.get(context).all()
        appendLine("Saved servers: ${servers.size}")
        servers.forEach { server ->
            appendLine(
                "  - ${server.label.ifBlank { server.host }} " +
                    "host=${server.host}:${server.port} share=${server.share.ifBlank { "-" }} " +
                    "user=${server.username.ifBlank { "-" }} domain=${server.domain.ifBlank { "-" }} " +
                    "guest=${server.guest} smb1=${server.smb1}"
            )
        }
    }

    private fun networkSummary(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val network = manager.activeNetwork ?: return "offline"
        val capabilities = manager.getNetworkCapabilities(network) ?: return "unknown"
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val metered = if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            "unmetered"
        } else {
            "metered"
        }
        return "$transport, $metered"
    }

    private fun cacheSize(context: Context): String {
        val root = File(context.cacheDir, "stream_cache")
        if (!root.exists()) return "empty"
        var total = 0L
        root.walkTopDown().filter { it.isFile }.forEach { total += it.length() }
        return "%.1f MB".format(total / 1024.0 / 1024.0)
    }
}
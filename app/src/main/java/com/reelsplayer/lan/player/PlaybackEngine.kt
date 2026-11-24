package com.reelsplayer.lan.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.reelsplayer.lan.logging.AppLog
import com.reelsplayer.lan.smb.ServerStore
import com.reelsplayer.lan.smb.SmbDataSource
import com.reelsplayer.lan.smb.SmbUri
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

/**
 * One place for everything that makes LAN playback smooth:
 *
 * - a 512 MB LRU disk cache, so a re-watched clip or a scrubbed range comes from local
 *   storage instead of the share;
 * - an ExoPlayer load control tuned for a LAN (buffer far ahead, small start threshold);
 * - a background prefetch of the next video's first megabytes, so a swipe starts at once.
 */
@UnstableApi
object PlaybackEngine {

    private const val TAG = "Player"
    private const val CACHE_BYTES = 512L * 1024 * 1024
    private const val PREFETCH_BYTES = 8L * 1024 * 1024

    private var cache: SimpleCache? = null
    private var cacheDataSourceFactory: CacheDataSource.Factory? = null
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null
    private var loadControl: DefaultLoadControl? = null

    private val prefetchExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "reels-prefetch").apply { isDaemon = true }
    }
    private val prefetchedUris = Collections.synchronizedSet(HashSet<String>())

    fun init(context: Context) {
        appContext = context.applicationContext
        if (cache != null) return
        val app = context.applicationContext

        val simpleCache = SimpleCache(
            File(app.cacheDir, "stream_cache"),
            LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
            StandaloneDatabaseProvider(app)
        )

        // Unrecognised schemes (reelssmb://) fall through to the SMB source; content://
        // and file:// picked from local storage keep using the platform resolver.
        val upstream = DefaultDataSource.Factory(app, SmbDataSource.Factory { id -> ServerStore.get(app).find(id) })

        val cached = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        cache = simpleCache
        cacheDataSourceFactory = cached
        mediaSourceFactory = DefaultMediaSourceFactory(cached)
        loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 180_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        AppLog.i(
            TAG,
            "cache ready dir=${File(app.cacheDir, "stream_cache").absolutePath} " +
                "limit=${CACHE_BYTES / 1024 / 1024}MB prefetch=${PREFETCH_BYTES / 1024 / 1024}MB"
        )
    }

    fun newPlayer(context: Context): ExoPlayer {
        init(context)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(requireNotNull(mediaSourceFactory))
            .setLoadControl(requireNotNull(loadControl))
            .setSeekBackIncrementMs(5_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    /**
     * Reads the head of [uri] through the cache in the background, so the cache already
     * holds the first seconds of the clip when the user swipes to it.
     */
    fun prefetch(uri: Uri) {
        // Only LAN playback benefits: local files are already fast and prefetching a
        // metered connection would burn the user's data plan.
        if (!SmbUri.isSmb(uri)) return
        val context = appContext ?: return
        if (isMetered(context)) return
        val factory = cacheDataSourceFactory ?: return
        if (!prefetchedUris.add(uri.toString())) return

        prefetchExecutor.execute {
            var dataSource: DataSource? = null
            val startedAt = android.os.SystemClock.elapsedRealtime()
            try {
                dataSource = factory.createDataSource()
                dataSource.open(DataSpec.Builder().setUri(uri).setLength(PREFETCH_BYTES).build())
                val buffer = ByteArray(256 * 1024)
                var total = 0L
                while (total < PREFETCH_BYTES) {
                    val read = dataSource.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    total += read
                }
                AppLog.d(
                    TAG,
                    "prefetch $uri -> ${total / 1024}KB in " +
                        "${android.os.SystemClock.elapsedRealtime() - startedAt}ms"
                )
            } catch (e: Exception) {
                // Prefetch is best effort: failures only mean no head start.
                AppLog.d(TAG, "prefetch failed $uri: ${AppLog.describe(e)}")
            } finally {
                try {
                    dataSource?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private var appContext: Context? = null

    private fun isMetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
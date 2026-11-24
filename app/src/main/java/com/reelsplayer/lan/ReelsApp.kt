package com.reelsplayer.lan

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.reelsplayer.lan.logging.AppLog
import com.reelsplayer.lan.player.PlaybackEngine
import com.reelsplayer.lan.smb.ServerStore

class ReelsApp : Application() {

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // Log first: everything below (and later crashes) should land in the report.
        AppLog.init(this)
        installCrashHandler()
        AppLog.i("App", "started ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

        // Warm up the encrypted server store and the streaming cache before the first play.
        ServerStore.get(this)
        PlaybackEngine.init(this)
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.e("Crash", "uncaught exception on ${thread.name}", throwable)
            AppLog.flush()
            previous?.uncaughtException(thread, throwable)
        }
    }
}
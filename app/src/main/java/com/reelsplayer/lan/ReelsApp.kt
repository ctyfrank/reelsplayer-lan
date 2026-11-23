package com.reelsplayer.lan

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.reelsplayer.lan.player.PlaybackEngine
import com.reelsplayer.lan.smb.ServerStore

class ReelsApp : Application() {

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Warm up the encrypted server store and the streaming cache before the first play.
        ServerStore.get(this)
        PlaybackEngine.init(this)
    }
}
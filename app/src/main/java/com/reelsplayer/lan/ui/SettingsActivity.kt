package com.reelsplayer.lan.ui

import android.os.Bundle
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.reelsplayer.lan.R
import com.reelsplayer.lan.logging.AppLog
import com.reelsplayer.lan.settings.PlaybackSettings

/** Play order and what happens when a video reaches its end. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val orderGroup = findViewById<RadioGroup>(R.id.orderGroup)
        val onEndGroup = findViewById<RadioGroup>(R.id.onEndGroup)

        when (PlaybackSettings.order(this)) {
            PlaybackSettings.Order.SEQUENTIAL -> orderGroup.check(R.id.orderSequential)
            PlaybackSettings.Order.SHUFFLE -> orderGroup.check(R.id.orderShuffle)
        }
        when (PlaybackSettings.onEnd(this)) {
            PlaybackSettings.OnEnd.LOOP -> onEndGroup.check(R.id.endLoop)
            PlaybackSettings.OnEnd.PAUSE -> onEndGroup.check(R.id.endPause)
            PlaybackSettings.OnEnd.NEXT -> onEndGroup.check(R.id.endNext)
        }

        orderGroup.setOnCheckedChangeListener { _, checkedId ->
            val order = if (checkedId == R.id.orderShuffle) {
                PlaybackSettings.Order.SHUFFLE
            } else {
                PlaybackSettings.Order.SEQUENTIAL
            }
            PlaybackSettings.setOrder(this, order)
            AppLog.i("Settings", "play order=$order")
        }

        onEndGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.endPause -> PlaybackSettings.OnEnd.PAUSE
                R.id.endNext -> PlaybackSettings.OnEnd.NEXT
                else -> PlaybackSettings.OnEnd.LOOP
            }
            PlaybackSettings.setOnEnd(this, mode)
            AppLog.i("Settings", "on end=$mode")
        }
    }
}
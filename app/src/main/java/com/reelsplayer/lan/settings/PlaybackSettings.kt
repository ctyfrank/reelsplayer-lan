package com.reelsplayer.lan.settings

import android.content.Context

/** User preferences that change how the feed behaves. */
object PlaybackSettings {

    enum class Order { SEQUENTIAL, SHUFFLE }

    enum class OnEnd { LOOP, PAUSE, NEXT }

    private const val PREFS = "playback_settings"
    private const val KEY_ORDER = "order"
    private const val KEY_ON_END = "on_end"

    fun order(context: Context): Order =
        runCatching { Order.valueOf(prefs(context).getString(KEY_ORDER, null) ?: Order.SEQUENTIAL.name) }
            .getOrDefault(Order.SEQUENTIAL)

    fun onEnd(context: Context): OnEnd =
        runCatching { OnEnd.valueOf(prefs(context).getString(KEY_ON_END, null) ?: OnEnd.LOOP.name) }
            .getOrDefault(OnEnd.LOOP)

    fun setOrder(context: Context, order: Order) {
        prefs(context).edit().putString(KEY_ORDER, order.name).apply()
    }

    fun setOnEnd(context: Context, onEnd: OnEnd) {
        prefs(context).edit().putString(KEY_ON_END, onEnd.name).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
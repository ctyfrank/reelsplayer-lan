package com.reelsplayer.lan.settings

import android.content.Context
import org.json.JSONObject

/** Where playback stopped, so the next launch can offer to continue right there. */
data class ResumePoint(
    val sourceType: String,
    val sourceId: String,
    val sourcePath: String,
    val itemName: String,
    val index: Int,
    val positionMs: Long,
    val total: Int
)

/**
 * Persists the last played file (a local tree URI or a LAN server + path) together with the
 * item and its position. Stored in plain preferences: it holds no credentials.
 */
object ResumeStore {

    const val SOURCE_LOCAL = "local"
    const val SOURCE_LAN = "lan"

    private const val PREFS = "resume_state"
    private const val KEY_POINT = "point"
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    fun load(context: Context): ResumePoint? {
        val raw = prefs(context).getString(KEY_POINT, null) ?: return null
        val point = runCatching {
            val json = JSONObject(raw)
            ResumePoint(
                sourceType = json.optString("sourceType"),
                sourceId = json.optString("sourceId"),
                sourcePath = json.optString("sourcePath"),
                itemName = json.optString("itemName"),
                index = json.optInt("index"),
                positionMs = json.optLong("positionMs"),
                total = json.optInt("total")
            )
        }.getOrNull() ?: return null

        if (point.sourcePath.isBlank() || point.total <= 0) return null
        val savedAt = prefs(context).getLong("savedAt", 0L)
        if (savedAt > 0 && System.currentTimeMillis() - savedAt > MAX_AGE_MS) {
            clear(context)
            return null
        }
        return point
    }

    fun save(context: Context, point: ResumePoint) {
        val json = JSONObject().apply {
            put("sourceType", point.sourceType)
            put("sourceId", point.sourceId)
            put("sourcePath", point.sourcePath)
            put("itemName", point.itemName)
            put("index", point.index)
            put("positionMs", point.positionMs)
            put("total", point.total)
        }
        prefs(context).edit()
            .putString(KEY_POINT, json.toString())
            .putLong("savedAt", System.currentTimeMillis())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_POINT).remove("savedAt").apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
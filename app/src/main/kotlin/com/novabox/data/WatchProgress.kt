package com.novabox.data

import com.novabox.data.Ctx.prefs
import org.json.JSONObject

/**
 * M2: per site+vod playback position, kept in shared_preferences
 * (对照 Flutter 版 watch_progress.dart).
 * Key: "wp_#<siteKey>#<vodId>" -> {"site","vod","vodName","ep","pos","dur","t"}
 */
object WatchProgress {

    private const val MAX_ENTRIES = 300
    private const val SWEEP_INTERVAL_MS = 86_400_000L

    private fun key(siteKey: String, vodId: String) = "wp_#$siteKey#$vodId"

    fun load(siteKey: String, vodId: String): JSONObject? {
        val raw = prefs.getString(key(siteKey, vodId), null)
        if (raw.isNullOrEmpty()) return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun save(
        siteKey: String,
        vodId: String,
        vodName: String,
        ep: String,
        posMs: Int,
        durMs: Int,
    ) {
        if (durMs <= 0 || posMs <= 0) return
        try {
            val last = prefs.getString("wp_last_sweep", "") ?: ""
            // Sweep occasionally to cap storage size.
            if (last.isEmpty() ||
                System.currentTimeMillis() - (last.toLongOrNull() ?: 0L) > SWEEP_INTERVAL_MS
            ) {
                sweep()
                prefs.edit().putString("wp_last_sweep", System.currentTimeMillis().toString()).apply()
            }
            val now = System.currentTimeMillis()
            val obj = JSONObject().apply {
                put("site", siteKey)
                put("vod", vodId)
                put("vodName", vodName)
                put("ep", ep)
                put("pos", posMs)
                put("dur", durMs)
                put("t", now)
            }
            prefs.edit().putString(key(siteKey, vodId), obj.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun clear(siteKey: String, vodId: String) {
        prefs.edit().remove(key(siteKey, vodId)).apply()
    }

    fun clearAll() {
        val editor = prefs.edit()
        for (k in prefs.all.keys) {
            if (k.startsWith("wp_#")) editor.remove(k)
        }
        editor.apply()
    }

    private fun sweep() {
        val keys = prefs.all.keys.filter { it.startsWith("wp_#") }
        if (keys.size <= MAX_ENTRIES) return
        val entries = mutableListOf<Pair<String, Long>>()
        for (key in keys) {
            val t = try {
                JSONObject(prefs.getString(key, "{}") ?: "{}").optLong("t", 0L)
            } catch (_: Exception) {
                0L
            }
            entries.add(Pair(key, t))
        }
        entries.sortBy { it.second }
        val editor = prefs.edit()
        for (entry in entries.take(entries.size - MAX_ENTRIES)) {
            editor.remove(entry.first)
        }
        editor.apply()
    }
}

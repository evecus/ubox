package com.novabox.data

import com.novabox.data.Ctx.prefs
import org.json.JSONArray
import org.json.JSONObject

/** 观看历史条目 (对照 Flutter 版 HistoryItem). */
data class HistoryItem(
    val siteKey: String,
    val siteName: String,
    val vodId: String,
    val name: String,
    val cover: String,
    val ep: String,
    val url: String,
    val pos: Int,
    val dur: Int,
    val ts: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("siteKey", siteKey)
        put("siteName", siteName)
        put("vodId", vodId)
        put("name", name)
        put("cover", cover)
        put("ep", ep)
        put("url", url)
        put("pos", pos)
        put("dur", dur)
        put("ts", ts)
    }

    companion object {
        fun from(map: JSONObject) = HistoryItem(
            siteKey = map.optString("siteKey") ?: "",
            siteName = map.optString("siteName") ?: "",
            vodId = map.optString("vodId") ?: "",
            name = map.optString("name") ?: "",
            cover = map.optString("cover") ?: "",
            ep = map.optString("ep") ?: "",
            url = map.optString("url") ?: "",
            pos = map.optInt("pos", 0),
            dur = map.optInt("dur", 0),
            ts = map.optLong("ts", 0L),
        )
    }
}

/** M3: watch history, shared_preferences backed, capped entries (对照 Flutter 版 History). */
object History {

    private const val PREF_KEY = "history"
    private const val MAX_ENTRIES = 50

    fun list(): List<HistoryItem> {
        val raw = prefs.getString(PREF_KEY, null)
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<HistoryItem>()
            for (i in 0 until arr.length()) {
                (arr.get(i) as? JSONObject)?.let { out.add(HistoryItem.from(it)) }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAll(items: List<HistoryItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(PREF_KEY, arr.toString()).apply()
    }

    fun record(
        siteKey: String,
        siteName: String,
        vodId: String,
        name: String,
        cover: String,
        ep: String,
        url: String,
        pos: Int,
        dur: Int,
    ) {
        try {
            val items = list().toMutableList()
            val entry = HistoryItem(
                siteKey = siteKey,
                siteName = siteName,
                vodId = vodId,
                name = name,
                cover = cover,
                ep = ep,
                url = url,
                pos = pos,
                dur = dur,
                ts = System.currentTimeMillis(),
            )
            items.removeAll { it.siteKey == siteKey && it.vodId == vodId }
            items.add(0, entry)
            if (items.size > MAX_ENTRIES) {
                while (items.size > MAX_ENTRIES) items.removeAt(items.size - 1)
            }
            saveAll(items)
        } catch (_: Exception) {
        }
    }

    fun remove(siteKey: String, vodId: String) {
        val items = list().toMutableList()
        items.removeAll { it.siteKey == siteKey && it.vodId == vodId }
        saveAll(items)
    }

    fun clearAll() {
        prefs.edit().remove(PREF_KEY).apply()
    }

    fun siteOf(siteKey: String): Site? {
        val loaded = ConfigStore.loaded.value ?: return null
        return loaded.sites.firstOrNull { it.key == siteKey }
    }
}

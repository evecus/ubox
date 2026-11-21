package com.me.ubox.data

import com.me.ubox.data.Ctx.prefs
import org.json.JSONArray
import org.json.JSONObject

/** 收藏条目 (对照 Flutter 版 FavoriteItem). */
data class FavoriteItem(
    val siteKey: String,
    val siteName: String,
    val vodId: String,
    val name: String,
    val cover: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("siteKey", siteKey)
        put("siteName", siteName)
        put("vodId", vodId)
        put("name", name)
        put("cover", cover)
    }

    companion object {
        fun from(map: JSONObject) = FavoriteItem(
            siteKey = map.optString("siteKey") ?: "",
            siteName = map.optString("siteName") ?: "",
            vodId = map.optString("vodId") ?: "",
            name = map.optString("name") ?: "",
            cover = map.optString("cover") ?: "",
        )
    }
}

/** M3: favorites, shared_preferences backed (对照 Flutter 版 Favorites). */
object Favorites {

    private const val PREF_KEY = "favorites"

    fun list(): List<FavoriteItem> {
        val raw = prefs.getString(PREF_KEY, null)
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<FavoriteItem>()
            for (i in 0 until arr.length()) {
                (arr.get(i) as? JSONObject)?.let { out.add(FavoriteItem.from(it)) }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAll(items: List<FavoriteItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(PREF_KEY, arr.toString()).apply()
    }

    fun isFav(siteKey: String, vodId: String): Boolean =
        list().any { it.siteKey == siteKey && it.vodId == vodId }

    fun toggle(item: FavoriteItem) {
        val items = list().toMutableList()
        val remain = items.filterNot { it.siteKey == item.siteKey && it.vodId == item.vodId }
        if (remain.size == items.size) {
            // Not in favorites -> add in front.
            items.add(0, item)
        } else {
            items.clear()
            items.addAll(remain)
        }
        saveAll(items)
    }

    fun remove(siteKey: String, vodId: String) {
        val items = list().toMutableList()
        items.removeAll { it.siteKey == siteKey && it.vodId == vodId }
        saveAll(items)
    }

    fun siteOf(siteKey: String): Site? {
        val loaded = ConfigStore.loaded.value ?: return null
        return loaded.sites.firstOrNull { it.key == siteKey }
    }
}

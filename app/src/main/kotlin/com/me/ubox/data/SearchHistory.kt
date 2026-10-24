package com.me.ubox.data

import com.me.ubox.data.Ctx.prefs
import org.json.JSONArray

/**
 * 搜索历史 (对照原版 HawkConfig.SEARCH_HISTORY / HistoryHelper.setSearchHistory):
 * 最新在前, 提交时去重, 最多保留 20 条; 与观看历史 ([History]) 分开存.
 */
object SearchHistory {

    private const val PREF_KEY = "search_history"
    private const val MAX_ENTRIES = 20

    /** 最新在前. */
    fun list(): List<String> {
        val raw = prefs.getString(PREF_KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotEmpty()) out.add(s)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 记录一次搜索: 同词去重提到最前 (对照原版 setSearchHistory). */
    fun record(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        val items = list().toMutableList()
        items.removeAll { it == w }
        items.add(0, w)
        saveAll(items.take(MAX_ENTRIES))
    }

    fun clear() {
        prefs.edit().remove(PREF_KEY).apply()
    }

    private fun saveAll(items: List<String>) {
        val arr = JSONArray()
        for (s in items) arr.put(s)
        prefs.edit().putString(PREF_KEY, arr.toString()).apply()
    }
}

package com.me.ubox.data

import com.me.ubox.data.Ctx.prefs
import org.json.JSONArray

/**
 * 设置页"配置 / 直播"地址历史: 最新在前, 同址去重, 每类最多 30 条.
 * 保存成功后记录; 设置页"历史"按钮弹出, 点选回填输入框, 可逐条删除.
 */
object UrlHistory {

    const val KIND_CONFIG = "config"
    const val KIND_LIVE = "live"

    private const val MAX_ENTRIES = 30
    /** 超长内容 (直接粘贴的配置 JSON) 不记录, 避免撑大 prefs. */
    private const val MAX_LEN = 2000

    private fun key(kind: String) = "url_history_$kind"

    fun list(kind: String): List<String> {
        val raw = prefs.getString(key(kind), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun record(kind: String, value: String) {
        val v = value.trim()
        if (v.isEmpty() || v.length > MAX_LEN) return
        val items = list(kind).toMutableList()
        items.removeAll { it == v }
        items.add(0, v)
        saveAll(kind, items.take(MAX_ENTRIES))
    }

    fun remove(kind: String, value: String) {
        saveAll(kind, list(kind).filter { it != value })
    }

    private fun saveAll(kind: String, items: List<String>) {
        val arr = JSONArray()
        for (s in items) arr.put(s)
        prefs.edit().putString(key(kind), arr.toString()).apply()
    }
}

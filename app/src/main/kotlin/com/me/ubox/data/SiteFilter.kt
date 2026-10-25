package com.me.ubox.data

import com.me.ubox.data.Ctx.prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * 站源黑名单 (关键词模糊匹配).
 *
 * 导入 TVBox 配置会加载全部站源. 这里保存用户设置的屏蔽关键词:
 * 站源名称包含任一关键词 (忽略大小写) 就不在首页站源列表、搜索、详情换源里使用.
 *
 * 规则只由用户在设置里增删, 换配置/切线路/清除配置都不会清空.
 * 收藏/历史/播放等"按 key 回查站源"的路径不受影响, 保证旧记录仍可打开.
 */
object SiteFilter {

    private const val KEY = "site_blacklist_keywords"

    private val _keywords = MutableStateFlow<List<String>>(emptyList())

    /** 屏蔽关键词 (保持用户添加顺序); 空 = 不屏蔽. */
    val keywords: StateFlow<List<String>> = _keywords.asStateFlow()

    fun load() {
        val raw = prefs.getString(KEY, null)
        _keywords.value = if (raw.isNullOrEmpty()) {
            emptyList()
        } else {
            try {
                val arr = JSONArray(raw)
                normalize((0 until arr.length()).map { arr.optString(it) })
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** 整体保存 (设置页增删后调用). 去空白/去重. */
    fun set(keywords: List<String>) {
        val list = normalize(keywords)
        _keywords.value = list
        if (list.isEmpty()) {
            prefs.edit().remove(KEY).apply()
        } else {
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            prefs.edit().putString(KEY, arr.toString()).apply()
        }
    }

    fun add(keyword: String) = set(_keywords.value + keyword)

    fun remove(keyword: String) = set(_keywords.value.filter { it != keyword })

    private fun normalize(list: List<String>): List<String> {
        val seen = HashSet<String>()
        return list.map { it.trim() }
            .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
    }

    /** 站源名称是否命中某个关键词 (模糊: 包含, 忽略大小写). */
    fun matches(site: Site, keyword: String): Boolean =
        site.displayName.contains(keyword, ignoreCase = true)

    /** 站源是否被任一关键词屏蔽. */
    fun isBlocked(site: Site, keywords: List<String> = _keywords.value): Boolean =
        keywords.any { matches(site, it) }

    /**
     * 对站源列表套用黑名单. 若所有源都被屏蔽则回退为不屏蔽,
     * 避免首页/搜索变成一个源都没有.
     */
    fun apply(sites: List<Site>, keywords: List<String> = _keywords.value): List<Site> {
        if (keywords.isEmpty()) return sites
        val kept = sites.filter { !isBlocked(it, keywords) }
        return kept.ifEmpty { sites }
    }
}

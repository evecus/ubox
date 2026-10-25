package com.me.ubox.data

import com.me.ubox.data.Ctx.prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * 站源过滤: 导入 TVBox 配置会加载全部站源, 首页站源列表与搜索默认都用全部源.
 * 这里保存用户在设置里勾选保留的源 key:
 *   - 空集合 = 不过滤 (使用全部源)
 *   - 非空   = 首页站源选择 / 搜索 / 详情换源 只使用这些源
 *
 * 配置地址变化 (导入新配置 / 切线路 / 清除配置) 时清空, 见 [ConfigStore.saveUrl].
 * 收藏/历史/播放等"按 key 回查站源"的路径不受过滤影响, 保证旧记录仍可打开.
 */
object SiteFilter {

    private const val KEY = "site_filter_keys"

    private val _keys = MutableStateFlow<Set<String>>(emptySet())

    /** 当前保留的源 key; 空 = 不过滤. */
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    val active: Boolean get() = _keys.value.isNotEmpty()

    fun load() {
        val raw = prefs.getString(KEY, null)
        if (raw.isNullOrEmpty()) {
            _keys.value = emptySet()
            return
        }
        _keys.value = try {
            val arr = JSONArray(raw)
            buildSet { for (i in 0 until arr.length()) add(arr.optString(i)) }
                .filter { it.isNotEmpty() }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** 保存过滤集合; 传空集合等同于清除过滤. */
    fun set(keys: Set<String>) {
        _keys.value = keys
        if (keys.isEmpty()) {
            prefs.edit().remove(KEY).apply()
        } else {
            val arr = JSONArray()
            keys.forEach { arr.put(it) }
            prefs.edit().putString(KEY, arr.toString()).apply()
        }
    }

    fun clear() = set(emptySet())

    /**
     * 对站源列表套用过滤. 过滤结果为空 (例如保存的 key 在当前配置里都不存在)
     * 时回退为不过滤, 避免首页/搜索变成一个源都没有.
     */
    fun apply(sites: List<Site>, keys: Set<String> = _keys.value): List<Site> {
        if (keys.isEmpty()) return sites
        val filtered = sites.filter { it.key in keys }
        return filtered.ifEmpty { sites }
    }
}

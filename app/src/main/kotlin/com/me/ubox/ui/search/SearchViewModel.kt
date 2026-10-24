package com.me.ubox.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.ubox.TvMode
import com.me.ubox.data.ConfigStore
import com.me.ubox.data.DoubanHot
import com.me.ubox.data.Site
import com.me.ubox.data.SpiderData
import com.me.ubox.data.VodItem
import com.me.ubox.data.vodList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 搜索结果条目 (带来源源站, 对照 Flutter 版 MapEntry<Site, VodItem>). */
data class SearchHit(
    val site: Site,
    val item: VodItem,
)

/**
 * 全站搜索 (对照 Flutter 版 search_page.dart):
 * 并行搜索所有源, 精确命中置顶, 源标签展示.
 *
 * 注意 [initialSiteKey] 只保留路由参数 (对照原版 SearchPage.initialSite),
 * 原版同样不拿它限定搜索范围 —— 初始 _searchSite 为 null = 并行搜索全部源,
 * 指定搜索源完全由用户在「指定搜索源」弹窗里选择.
 */
class SearchViewModel(
    initialSiteKey: String?,
    initialQuery: String,
) : ViewModel() {

    private val _sites = MutableStateFlow<List<Site>>(emptyList())
    val sites: StateFlow<List<Site>> = _sites.asStateFlow()

    private val _results = MutableStateFlow<Map<String, List<VodItem>>>(emptyMap())
    val results: StateFlow<Map<String, List<VodItem>>> = _results.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _done = MutableStateFlow(0)
    val done: StateFlow<Int> = _done.asStateFlow()

    /** 左侧选中的源 key; null = 全部. */
    private val _filter = MutableStateFlow<String?>(null)
    val filter: StateFlow<String?> = _filter.asStateFlow()

    /** 搜索前指定的源 key (对照原版 tvSearchCheckboxBtn "指定搜索源"); null = 并行搜索全部源. */
    private val _searchSite = MutableStateFlow<String?>(null)
    val searchSite: StateFlow<String?> = _searchSite.asStateFlow()

    private val _hotWords = MutableStateFlow<List<String>>(emptyList())
    val hotWords: StateFlow<List<String>> = _hotWords.asStateFlow()

    private val _hotLoading = MutableStateFlow(true)
    val hotLoading: StateFlow<Boolean> = _hotLoading.asStateFlow()

    /** 搜索完成后把焦点移到第一条结果 (TV 遥控器流程). */
    private val _focusFirstResult = MutableStateFlow(false)
    val focusFirstResult: StateFlow<Boolean> = _focusFirstResult.asStateFlow()

    init {
        _sites.value = ConfigStore.loaded.value?.sites?.filter { it.isRealSource } ?: emptyList()
        // 初始为 null: 并行搜索全部源 (对照 Flutter _searchSite 初始 null,
        // 原版 initialSite 参数不使用). 指定搜索源由 setSearchSite 设置.
        viewModelScope.launch {
            _hotWords.value = DoubanHot.fetch().map { it.name }.filter { it.isNotEmpty() }.take(20)
            _hotLoading.value = false
        }
        if (initialQuery.isNotEmpty()) {
            search(initialQuery)
        }
    }

    fun search(q: String) {
        val query = q.trim()
        if (query.isEmpty() || _running.value) return
        _query.value = query
        _running.value = true
        _done.value = 0
        _filter.value = null
        _results.value = emptyMap()
        _focusFirstResult.value = false
        viewModelScope.launch {
            val targets = _searchSite.value
                ?.let { key -> _sites.value.filter { it.key == key } }
                ?: _sites.value
            targets.map { site ->
                async {
                    try {
                        val json = SpiderData.search(site.key, query, "1")
                        val list = vodList(json.opt("list"))
                        // 过滤明显不相关的无效 entry (空名已过滤), 最多保留 20 个.
                        _results.update { it + (site.key to list.take(20)) }
                    } catch (_: Exception) {
                    } finally {
                        _done.update { it + 1 }
                    }
                }
            }.awaitAll()
            _running.value = false
            // TV 模式才把焦点移到第一条结果 (对照 Flutter TvMode.isTV.value 判断).
            if (TvMode.isTV.value && _results.value.values.any { it.isNotEmpty() }) {
                _focusFirstResult.value = true
            }
        }
    }

    fun clearQuery() {
        _query.value = ""
        _results.value = emptyMap()
        _focusFirstResult.value = false
    }

    fun setFilter(key: String?) {
        _filter.value = key
    }

    fun setSearchSite(key: String?) {
        _searchSite.value = key
        // 已有查询时自动重搜 (对照原版选中源后立即刷新).
        if (_query.value.isNotEmpty() && !_running.value) {
            search(_query.value)
        }
    }

    fun consumeFocusFirstResult() {
        _focusFirstResult.value = false
    }

    /** 精确匹配优先: 若某源中结果名 == 搜索词则排前; 选中某个源时只看该源. */
    fun flatten(): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        val rest = mutableListOf<SearchHit>()
        val sitesByKey = _sites.value.associateBy { it.key }
        for ((key, list) in _results.value) {
            val site = sitesByKey[key] ?: continue
            if (_filter.value != null && key != _filter.value) continue
            for (vod in list) {
                if (vod.name == _query.value) {
                    hits.add(SearchHit(site, vod))
                } else {
                    rest.add(SearchHit(site, vod))
                }
            }
        }
        return hits + rest
    }

    /** 有结果的源列表 (按到达顺序). */
    fun sitesWithResults(): List<Site> {
        val sitesByKey = _sites.value.associateBy { it.key }
        return _results.value.entries
            .filter { it.value.isNotEmpty() }
            .mapNotNull { sitesByKey[it.key] }
    }
}

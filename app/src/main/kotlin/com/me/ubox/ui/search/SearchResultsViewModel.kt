package com.me.ubox.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.ubox.TvMode
import com.me.ubox.data.ConfigStore
import com.me.ubox.data.Site
import com.me.ubox.data.SpiderData
import com.me.ubox.data.VodItem
import com.me.ubox.data.vodList
import com.me.ubox.engine.EngineClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
 * 搜索结果页 VM (搜索执行逻辑从原搜索页 VM 迁出, 页签分离后各自独立):
 * 并行搜索所有源 (或指定源), 精确命中置顶, 源标签展示.
 *
 * @param searchSiteKey 指定搜索源 key; null = 并行搜索全部源
 *                      (对照原版: 初始不指定, 由用户在搜索页选择后随路由带入).
 */
class SearchResultsViewModel(
    private val searchSiteKey: String?,
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

    /** 搜索完成后把焦点移到第一条结果 (TV 遥控器流程). */
    private val _focusFirstResult = MutableStateFlow(false)
    val focusFirstResult: StateFlow<Boolean> = _focusFirstResult.asStateFlow()

    /**
     * 本次搜索使用的源: 指定了单个源 (搜索页的源站选择) 就只用它;
     * 否则用套用了站源过滤的源列表.
     */
    private fun resolveSites(): List<Site> {
        val all = ConfigStore.loaded.value?.sites?.filter { it.isRealSource } ?: emptyList()
        return searchSiteKey?.let { key -> all.filter { it.key == key } }
            ?: com.me.ubox.data.SiteFilter.apply(all)
    }

    /** 当前搜索协程 (进入详情页时用于停止搜索). */
    private var searchJob: Job? = null

    init {
        _sites.value = resolveSites()
        if (initialQuery.isNotEmpty()) {
            search(initialQuery)
        }
    }

    fun search(q: String) {
        val query = q.trim()
        if (query.isEmpty() || _running.value) return
        _query.value = query
        _sites.value = resolveSites()
        _done.value = 0
        _filter.value = null
        _results.value = emptyMap()
        _focusFirstResult.value = false
        runSites(_sites.value, query)
    }

    private fun runSites(targets: List<Site>, query: String) {
        if (targets.isEmpty()) {
            _running.value = false
            return
        }
        _running.value = true
        searchJob = viewModelScope.launch {
            targets.map { site ->
                async {
                    try {
                        val json = SpiderData.search(site.key, query, "1")
                        val list = vodList(json.opt("list"))
                        // 过滤明显不相关的无效 entry (空名已过滤), 最多保留 20 个.
                        _results.update { it + (site.key to list.take(20)) }
                        _done.update { it + 1 }
                    } catch (e: CancellationException) {
                        // 被 stopSearch 取消: 不计入完成, 直接结束.
                        throw e
                    } catch (_: Exception) {
                        _done.update { it + 1 }
                    }
                }
            }.awaitAll()
            _running.value = false
            // 遥控器 (TV) 模式才把焦点移到第一条结果 (对照 Flutter TvMode.isTV.value 判断).
            if (TvMode.isRemote.value && _results.value.values.any { it.isNotEmpty() }) {
                _focusFirstResult.value = true
            }
        }
    }

    /**
     * 立即停止搜索 (进入详情/播放页时调用), 保留已有结果, 返回结果页后不再补搜:
     *   1. 取消搜索协程: 还在排队的源直接丢弃, 不再占线程;
     *   2. 取消进行中的网络请求: 阻塞在 socket 上的搜索线程立刻释放, 详情/播放解析不用等它们超时.
     * 顺序不能反: 先取消协程, 被打断的请求返回空结果时才不会被当成"该源无结果"记入完成.
     */
    fun stopSearch() {
        if (!_running.value) return
        searchJob?.cancel()
        searchJob = null
        _running.value = false
        EngineClient.cancelSearch()
    }

    fun setFilter(key: String?) {
        _filter.value = key
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

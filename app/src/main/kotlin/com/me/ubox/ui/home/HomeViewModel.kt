package com.me.ubox.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.ubox.data.Config
import com.me.ubox.data.ConfigException
import com.me.ubox.data.ConfigStore
import com.me.ubox.data.DoubanHot
import com.me.ubox.data.History
import com.me.ubox.data.HomePrefs
import com.me.ubox.data.JsonUtil
import com.me.ubox.data.Site
import com.me.ubox.data.SiteFilter
import com.me.ubox.data.SpiderData
import com.me.ubox.data.VodItem
import com.me.ubox.data.classList
import com.me.ubox.data.doubanHotList
import com.me.ubox.data.vodList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 首页 Tab 规格: 'my0' = 主页 (对照原版 DefaultConfig.adjustSort 插入的 my0); 其余为分类 type_id. */
data class HomeTab(val id: String, val name: String)

/** 单个 Tab 的数据状态 (对照 Flutter 版 _TabState). */
data class HomeTabState(
    val items: List<VodItem> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String = "",
    val page: Int = 1,
    val hasMore: Boolean = true,
)

/**
 * 原版首页的 Compose 移植 (HomeFragment + UserFragment + GridFragment, 对照 Flutter 版 home_page.dart):
 * 顶部 线路/站源/搜索/收藏/历史 + 横向分类 Tab + 每 Tab 海报网格.
 * "主页" Tab 的数据来源由 HomePrefs.homeRec 决定:
 *   0 豆瓣热播 (直连豆瓣抓取, 失败回退 spider.homeVideoContent)
 *   1 站点推荐 (homeContent 的 list)
 *   2 观看历史 (本地观看记录)
 */
class HomeViewModel : ViewModel() {

    val config: StateFlow<Config?> = ConfigStore.loaded

    private val _site = MutableStateFlow<Site?>(null)
    val site: StateFlow<Site?> = _site.asStateFlow()

    private val _tabs = MutableStateFlow<List<HomeTab>>(emptyList())
    val tabs: StateFlow<List<HomeTab>> = _tabs.asStateFlow()

    private val _tabStates = MutableStateFlow<Map<String, HomeTabState>>(emptyMap())
    val tabStates: StateFlow<Map<String, HomeTabState>> = _tabStates.asStateFlow()

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _bootLoading = MutableStateFlow(false)
    val bootLoading: StateFlow<Boolean> = _bootLoading.asStateFlow()

    private val _bootError = MutableStateFlow("")
    val bootError: StateFlow<String> = _bootError.asStateFlow()

    /** 面向用户的一次性消息 (SnackBar). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /** homeContent 内存缓存 (对照原版 SourceViewModel.sortCache, 最多 5 个源). */
    private val homeCache = LinkedHashMap<String, JSONObject>()

    private var loadedConfigUrl: String? = null
    private var started = false

    val hasConfig: Boolean
        get() = ConfigStore.loaded.value != null &&
            ConfigStore.loaded.value!!.sites.any { it.isRealSource }

    private val realSites: List<Site>
        get() = ConfigStore.visibleSites()

    init {
        viewModelScope.launch {
            // 首页列表数据来源变化 (设置页切换) → 重载"主页" Tab.
            HomePrefs.recNotifier.collect {
                if (started) reloadHomeTab()
            }
        }
        viewModelScope.launch {
            // 站源黑名单变化 → 重新解析首页源 (当前源被屏蔽时切到第一个未屏蔽的源).
            SiteFilter.keywords.collect {
                if (started && hasConfig) onConfigChanged(first = false)
            }
        }
        viewModelScope.launch {
            ConfigStore.loaded.collect {
                if (!started) {
                    started = true
                    onConfigChanged(first = true)
                } else {
                    onConfigChanged(first = false)
                }
            }
        }
    }

    private suspend fun onConfigChanged(first: Boolean) {
        if (!hasConfig) {
            bootstrap()
            return
        }
        val configUrl = ConfigStore.loaded.value!!.url
        // 解析首页源: 优先 Hawk HOME_API 保存的 key (原版 ApiConfig.parseJson 末尾逻辑),
        // 不存在则退回第一个源.
        var site: Site? = null
        val savedKey = HomePrefs.homeApi
        if (savedKey.isNotEmpty()) {
            site = realSites.firstOrNull { it.key == savedKey }
        }
        site = site ?: realSites.first()
        HomePrefs.setHomeApi(site.key)
        val sameSite = _site.value?.key == site.key
        val sameConfig = loadedConfigUrl == configUrl
        loadedConfigUrl = configUrl
        _site.value = site
        if (!sameSite || !sameConfig || first) {
            loadHomeContent()
        }
    }

    private suspend fun bootstrap() {
        if (_bootLoading.value || ConfigStore.loaded.value != null) return
        val url = ConfigStore.loadUrl()
        if (url.isEmpty()) {
            _bootError.value = "请先在\"设置\"导入 TVBox 配置"
            return
        }
        _bootLoading.value = true
        _bootError.value = ""
        val (ok, msg) = ConfigStore.applyInput(url)
        _bootLoading.value = false
        if (!ok) _bootError.value = msg
    }

    // ---------------------------------------------------------------- 数据加载

    private suspend fun loadHomeContent() {
        val site = _site.value ?: return
        _tabs.value = listOf(HomeTab("my0", "主页"))
        _selectedTab.value = 0
        _tabStates.value = emptyMap()

        // 豆瓣热播 / 观看历史 与站点 homeContent 无关 (原版 UserFragment 独立加载),
        // 不能排在 home 之后: home 慢/失败时, 热播也跟着白屏. 站点推荐(1)才依赖 home 结果.
        if (HomePrefs.homeRec != 1) {
            reloadHomeTab()
        }

        var json = homeCache[site.key]
        if (json == null) {
            _bootLoading.value = true
            // 对照原版 SourceViewModel.getSort: homeContent(true); 空结果不缓存 (否则一次失败
            // 首页就永久空白), 失败时重试一次 (jar/js 爬虫首次创建偶发超时).
            for (attempt in 0 until 3) {
                if (attempt > 0) delay(2000)
                try {
                    val r = SpiderData.home(site.key, true)
                    if (r.length() > 0) {
                        json = r
                        synchronized(homeCache) {
                            homeCache[site.key] = r
                            if (homeCache.size > 5) {
                                homeCache.remove(homeCache.keys.first())
                            }
                        }
                        break
                    }
                } catch (_: Exception) {
                }
            }
            _bootLoading.value = false
        }
        val classes = classList(json?.opt("class"))
        _tabs.value = buildList {
            add(HomeTab("my0", "主页"))
            classes.forEach { c ->
                add(
                    HomeTab(
                        id = (JsonUtil.optStringOrNull(c, "type_id") ?: ""),
                        name = (JsonUtil.optStringOrNull(c, "type_name") ?: ""),
                    )
                )
            }
        }
        val st = _tabStates.value["my0"]
        if (HomePrefs.homeRec == 1 || st == null || (!st.loaded && !st.loading)) {
            reloadHomeTab()
        }
    }

    /** "主页" Tab: 按 HomePrefs.homeRec 取数. */
    private suspend fun reloadHomeTab() {
        val site = _site.value ?: return
        val st = _tabStates.value["my0"] ?: HomeTabState()
        if (st.loading) return
        updateTab("my0") { it.copy(loading = true, error = "") }
        try {
            val items = fetchHomeRecItems(site)
            updateTab("my0") {
                it.copy(
                    items = items,
                    loading = false,
                    loaded = true,
                    // "主页" Tab 没有分页 (豆瓣热播/站点推荐/历史都是一页),
                    // 必须显式关闭, 否则触底会误触发 categoryContent('my0').
                    hasMore = false,
                )
            }
        } catch (_: Exception) {
            updateTab("my0") { it.copy(loading = false, error = "加载失败") }
        }
    }

    private suspend fun fetchHomeRecItems(site: Site): List<VodItem> {
        return when (HomePrefs.homeRec) {
            1 -> {
                // 站点推荐: homeContent 的 list (对照原版 UserFragment homeSourceRec).
                val json = homeCache[site.key] ?: SpiderData.home(site.key, true)
                vodList(json.opt("list"), site.key)
            }
            2 -> {
                // 观看历史: 本地记录 (对照原版 UserFragment RoomDataManger.getAllVodRecord).
                History.list()
                    .map { h ->
                        VodItem(
                            id = h.vodId,
                            siteKey = h.siteKey,
                            name = h.name,
                            cover = h.cover,
                            remark = if (h.ep.isEmpty()) null else "上次看到${h.ep}",
                        )
                    }
                    .filter { it.name.isNotEmpty() }
            }
            else -> {
                // 豆瓣热播: 直连豆瓣 (对照原版 UserFragment.setDouBanData);
                // 失败回退 spider.homeVideoContent (对照 SourceViewModel.getHomeRecList).
                val hot = DoubanHot.fetch()
                if (hot.isNotEmpty()) return hot
                val json = SpiderData.homeVod(site.key)
                val vod = vodList(json.opt("list"), site.key)
                if (vod.isNotEmpty()) return vod
                // 再退一步: 站点 homeContent 自带的推荐列表 (很多 jar 源只在这里给数据).
                vodList(homeCache[site.key]?.opt("list"), site.key)
            }
        }
    }

    private suspend fun loadCategory(tab: HomeTab, more: Boolean) {
        val site = _site.value ?: return
        if (tab.id.isEmpty()) return
        val st = _tabStates.value[tab.id] ?: HomeTabState()
        if (st.loading) return
        updateTab(tab.id) { it.copy(loading = true) }
        try {
            val json = SpiderData.category(site.key, tab.id, st.page.toString(), emptyMap())
            val next = vodList(json.opt("list"), site.key)
            val pc = (json.opt("pagecount")?.toString() ?: "0").toIntOrNull() ?: 0
            updateTab(tab.id) {
                it.copy(
                    items = if (more) it.items + next else next,
                    page = it.page + 1,
                    // 对照原版 GridFragment: pagecount<=0 时不限制, 否则 page>maxPage 即结束;
                    // 本页无数据也直接结束, 避免无限转圈.
                    hasMore = next.isNotEmpty() && (pc <= 0 || it.page + 1 <= pc),
                    loading = false,
                    loaded = true,
                    error = "",
                )
            }
        } catch (_: Exception) {
            updateTab(tab.id) { it.copy(loading = false, error = "加载失败") }
        }
    }

    fun selectTab(i: Int) {
        if (i < 0 || i >= _tabs.value.size) return
        _selectedTab.value = i
        val tab = _tabs.value[i]
        viewModelScope.launch {
            if (tab.id == "my0") {
                val st = _tabStates.value["my0"]
                if (st == null || !st.loaded) reloadHomeTab()
                return@launch
            }
            val st = _tabStates.value[tab.id]
            if (st == null || !st.loaded) loadCategory(tab, false)
        }
    }

    fun loadMore() {
        val tab = _tabs.value.getOrNull(_selectedTab.value) ?: return
        val st = _tabStates.value[tab.id] ?: return
        if (!st.hasMore || st.loading) return
        viewModelScope.launch { loadCategory(tab, more = true) }
    }

    fun retryTab() {
        val tab = _tabs.value.getOrNull(_selectedTab.value) ?: return
        viewModelScope.launch {
            if (tab.id == "my0") reloadHomeTab() else loadCategory(tab, false)
        }
    }

    fun retryHomeContent() {
        val site = _site.value ?: return
        synchronized(homeCache) { homeCache.remove(site.key) }
        viewModelScope.launch { loadHomeContent() }
    }

    // ---------------------------------------------------------------- 交互

    fun selectSite(site: Site) {
        if (site.key == _site.value?.key) return
        HomePrefs.setHomeApi(site.key)
        _site.value = site
        viewModelScope.launch { loadHomeContent() }
    }

    private fun updateTab(id: String, update: (HomeTabState) -> HomeTabState) {
        _tabStates.update { current ->
            val st = current[id] ?: HomeTabState()
            current + (id to update(st))
        }
    }
}

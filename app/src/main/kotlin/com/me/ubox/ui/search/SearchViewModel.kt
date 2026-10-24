package com.me.ubox.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.ubox.data.ConfigStore
import com.me.ubox.data.DoubanHot
import com.me.ubox.data.SearchHistory
import com.me.ubox.data.SearchSuggest
import com.me.ubox.data.Site
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 搜索页 VM (对照原版 SearchActivity 的词面状态):
 * 热门 / 猜你想搜 / 搜索历史 + 可搜索源列表 (供"指定搜索源"弹窗).
 *
 * 搜索执行与结果状态在 [SearchResultsViewModel] (结果页独立 VM),
 * 本 VM 只负责输入页的词面数据; 提交搜索由页面回调推结果页路由.
 */
class SearchViewModel : ViewModel() {

    private val _sites = MutableStateFlow<List<Site>>(emptyList())
    val sites: StateFlow<List<Site>> = _sites.asStateFlow()

    private val _hotWords = MutableStateFlow<List<String>>(emptyList())
    val hotWords: StateFlow<List<String>> = _hotWords.asStateFlow()

    private val _hotLoading = MutableStateFlow(true)
    val hotLoading: StateFlow<Boolean> = _hotLoading.asStateFlow()

    /** 猜你想搜候选 (输入首字母后); 为空表示回退到热门. */
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _history = MutableStateFlow(SearchHistory.list())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private var suggestJob: Job? = null

    init {
        _sites.value = ConfigStore.loaded.value?.sites?.filter { it.isRealSource } ?: emptyList()
        viewModelScope.launch {
            val names = DoubanHot.fetch().map { it.name }.filter { it.isNotEmpty() }.take(20)
            // 豆瓣抓取失败时回退原版默认热词 (对照原版 useDefaultHotWords).
            _hotWords.value = names.ifEmpty { DEFAULT_HOT_WORDS }
            _hotLoading.value = false
        }
    }

    /**
     * 输入变化 (仅 TV 屏显键盘路径调用; 手机/平板走软键盘, 不需要联想):
     * 原版每次按键都请求 loadRec, 这里加 250ms 防抖合并快速连续按键.
     */
    fun onInputChanged(text: String) {
        suggestJob?.cancel()
        if (text.isBlank()) {
            _suggestions.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250)
            // 失败/空结果保持原状 (为空即回退热门, 与原版异常不清空一致).
            val list = SearchSuggest.fetch(text)
            if (list.isNotEmpty()) _suggestions.value = list
        }
    }

    /** 清空输入时同时清掉联想候选. */
    fun clearSuggestions() {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
    }

    /** 提交搜索: 记搜索历史 (对照原版 HistoryHelper.setSearchHistory). */
    fun recordHistory(q: String) {
        SearchHistory.record(q)
        _history.value = SearchHistory.list()
    }

    fun clearHistory() {
        SearchHistory.clear()
        _history.value = emptyList()
    }

    companion object {
        /** 原版 SearchActivity.DEFAULT_HOT_WORDS (豆瓣热词抓取失败时的兜底). */
        val DEFAULT_HOT_WORDS = listOf(
            "家业",
            "主角",
            "低智商犯罪",
            "苏超",
            "书卷一梦",
            "美人余",
            "藏海传",
            "长安的荔枝",
            "庆余年",
            "凡人修仙传",
        )
    }
}

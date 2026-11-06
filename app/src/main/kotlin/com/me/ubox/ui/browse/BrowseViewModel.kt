package com.me.ubox.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.ubox.data.ConfigStore
import com.me.ubox.data.Site
import com.me.ubox.data.SpiderData
import com.me.ubox.data.VodItem
import com.me.ubox.data.classList
import com.me.ubox.data.vodList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 分类浏览页 (对照 Flutter 版 browse_page.dart):
 * 站内分类 chip 行 + 海报网格 + 下一页按钮; 支持站内搜索.
 */
class BrowseViewModel(
    private val siteKey: String,
    initialTid: String,
) : ViewModel() {

    private val _site = MutableStateFlow<Site?>(null)
    val site: StateFlow<Site?> = _site.asStateFlow()

    private val _classes = MutableStateFlow<List<JSONObject>>(emptyList())
    val classes: StateFlow<List<JSONObject>> = _classes.asStateFlow()

    private val _items = MutableStateFlow<List<VodItem>>(emptyList())
    val items: StateFlow<List<VodItem>> = _items.asStateFlow()

    private val _tid = MutableStateFlow(initialTid)
    val tid: StateFlow<String> = _tid.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _searchMode = MutableStateFlow(false)
    val searchMode: StateFlow<Boolean> = _searchMode.asStateFlow()

    private var page = "1"
    private var homeFetched = false

    init {
        _site.value = ConfigStore.loaded.value?.sites?.firstOrNull { it.key == siteKey }
        // 并行启动 (对照 Flutter initState: _bootstrapClasses() 与 _reload() 均 unawaited);
        // 串行执行会让首页 home 请求双倍耗时 (先拉分类再拉内容).
        viewModelScope.launch { bootstrapClasses() }
        viewModelScope.launch { reload() }
    }

    private suspend fun bootstrapClasses() {
        if (homeFetched) return
        try {
            val json = SpiderData.home(siteKey, true)
            val classes = classList(json.opt("class"))
            homeFetched = classes.isNotEmpty()
            if (classes.isNotEmpty()) _classes.value = classes
        } catch (_: Exception) {
        }
    }

    fun reload() {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                val next: List<VodItem>
                when {
                    _searchMode.value -> {
                        val json = SpiderData.search(siteKey, searchKeyword, page)
                        next = vodList(json.opt("list"))
                    }
                    _tid.value.isEmpty() -> {
                        val json = SpiderData.home(siteKey, true)
                        next = vodList(json.opt("list"))
                        homeFetched = true
                        val classes = classList(json.opt("class"))
                        if (classes.isNotEmpty()) _classes.value = classes
                    }
                    else -> {
                        val json = SpiderData.category(siteKey, _tid.value, page, emptyMap())
                        next = vodList(json.opt("list"))
                    }
                }
                _items.value = if (page == "1") next else _items.value + next
            } finally {
                _loading.value = false
            }
        }
    }

    private var searchKeyword = ""

    /** 搜索图标: 仅进入输入模式 (对照 Flutter onPressed: _searchMode=true, _items=[]; 回车才提交). */
    fun enterSearch() {
        _searchMode.value = true
        _items.value = emptyList()
    }

    fun search(keyword: String) {
        searchKeyword = keyword.trim()
        _searchMode.value = true
        page = "1"
        _items.value = emptyList()
        reload()
    }

    fun exitSearch() {
        _searchMode.value = false
        _tid.value = ""
        page = "1"
        _items.value = emptyList()
        reload()
    }

    fun setClass(tid: String) {
        _tid.value = tid
        page = "1"
        _items.value = emptyList()
        reload()
    }

    fun nextPage() {
        page = ((page.toIntOrNull() ?: 1) + 1).toString()
        reload()
    }
}

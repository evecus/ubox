package com.me.ubox.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.ubox.data.ConfigStore
import com.me.ubox.data.Favorites
import com.me.ubox.data.FavoriteItem
import com.me.ubox.data.JsonUtil
import com.me.ubox.data.PlayUrl
import com.me.ubox.data.Site
import com.me.ubox.data.SpiderData
import com.me.ubox.data.asList
import com.me.ubox.data.parsePlayGroups
import com.me.ubox.data.vodList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 详情页 (对照 Flutter 版 detail_page.dart):
 * 海报 + 元信息 + 选集 (多 play 分组) + 收藏 + 换源.
 */
class DetailViewModel(
    private val siteKey: String,
    private val vodId: String,
    private val vodName: String,
) : ViewModel() {

    private val _site = MutableStateFlow<Site?>(null)
    val site: StateFlow<Site?> = _site.asStateFlow()

    private val _json = MutableStateFlow<JSONObject?>(null)
    val json: StateFlow<JSONObject?> = _json.asStateFlow()

    private val _groups = MutableStateFlow<List<PlayUrl>>(emptyList())
    val groups: StateFlow<List<PlayUrl>> = _groups.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow(false)
    val error: StateFlow<Boolean> = _error.asStateFlow()

    private val _fav = MutableStateFlow(false)
    val fav: StateFlow<Boolean> = _fav.asStateFlow()

    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    /** 面向用户的一次性消息. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    init {
        _site.value = ConfigStore.loaded.value?.sites?.firstOrNull { it.key == siteKey }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = false
            try {
                val json = SpiderData.detail(siteKey, vodId)
                val list = asList(json.opt("list"))
                if (list.isEmpty()) {
                    _error.value = true
                    _loading.value = false
                    return@launch
                }
                val vod = list.first()
                val groups = parsePlayGroups(
                    JsonUtil.optStringOrNull(vod, "vod_play_url") ?: "",
                    JsonUtil.optStringOrNull(vod, "vod_play_from") ?: "",
                )
                _json.value = vod
                _groups.value = groups
                _loading.value = false
                syncFav()
            } catch (_: Exception) {
                _error.value = true
                _loading.value = false
            }
        }
    }

    private fun syncFav() {
        viewModelScope.launch {
            _fav.value = Favorites.isFav(siteKey, vodId)
        }
    }

    fun toggleFav() {
        val site = _site.value ?: return
        val vod = _json.value
        viewModelScope.launch {
            Favorites.toggle(
                FavoriteItem(
                    siteKey = site.key,
                    siteName = site.name,
                    vodId = vodId,
                    name = (JsonUtil.optStringOrNull(vod, "vod_name") ?: vodName),
                    cover = (JsonUtil.optStringOrNull(vod, "vod_pic") ?: ""),
                )
            )
            syncFav()
        }
    }

    /** 换源候选: 除当前源外的可用源 (对照 Flutter 版 _showSwitchMenu). */
    fun switchCandidates(): List<Site> {
        val loaded = ConfigStore.loaded.value ?: return emptyList()
        return loaded.sites.filter { it.isRealSource && it.key != siteKey }
    }

    /**
     * 换源: 在目标源搜索同名影片, 精确命中则回调 [onMatched].
     * 对照 Flutter 版 _showSwitchMenu: 结果名 == 当前片名才算命中.
     */
    fun switchSource(target: Site, onMatched: (String, String, String) -> Unit) {
        val name = (JsonUtil.optStringOrNull(_json.value, "vod_name") ?: vodName)
        viewModelScope.launch {
            _switching.value = true
            try {
                val result = SpiderData.search(target.key, name, "1")
                val list = vodList(result.opt("list"))
                val match = list.firstOrNull { it.name == name }
                if (match != null) {
                    onMatched(target.key, match.id, match.name)
                } else {
                    _message.value = "${target.name}未找到 \"$name\""
                }
            } finally {
                _switching.value = false
            }
        }
    }
}

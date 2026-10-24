package com.me.ubox.data.live

import com.me.ubox.data.Ctx
import com.me.ubox.engine.EngineClient
import com.me.ubox.data.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 直播数据层: 直播源地址/上次频道持久化, 拉取解析, 归一化数组转模型
 * (对照 Flutter 版 live_store.dart).
 *
 * 对照原版:
 * - 拉取链路: ApiConfig.loadLiveApi (type 0 直链) + LocalServer proxy 直拉
 * - 解析: util/live/TxtSubscribe.parseToJsonArray
 * - 建模: ApiConfig.loadLives (分组/频道, 同名频道合并线路)
 */
object LiveStore {

    private const val PREF_URL_KEY = "live_url"
    private const val PREF_CHANNEL_KEY = "live_channel"

    /** 缓冲超时自动换源的默认时长 (毫秒), 对照原版 LiveFragment 默认 10s. */
    const val DEFAULT_TIMEOUT_MS = 10_000

    /** 播放出错后自动换源的延迟 (毫秒), 对照原版 3.5s. */
    const val ERROR_RETRY_DELAY_MS = 3_500

    private val _liveUrl = MutableStateFlow("")
    val liveUrl: StateFlow<String> = _liveUrl.asStateFlow()

    private val _lastChannelName = MutableStateFlow("")
    val lastChannelName: StateFlow<String> = _lastChannelName.asStateFlow()

    /** 直播源地址变化 (设置页保存后直播页重载). */
    private val _liveUrlChanged = MutableStateFlow("")
    val liveUrlChanged: StateFlow<String> = _liveUrlChanged.asStateFlow()

    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        _liveUrl.value = Ctx.prefs.getString(PREF_URL_KEY, "") ?: ""
        _lastChannelName.value = Ctx.prefs.getString(PREF_CHANNEL_KEY, "") ?: ""
        _liveUrlChanged.value = _liveUrl.value
    }

    /** 保存直播源地址 (空地址 = 清除). 返回是否发生变化. */
    fun setLiveUrl(url: String): Boolean {
        val target = url.trim()
        if (target == _liveUrl.value) return false
        _liveUrl.value = target
        Ctx.prefs.edit().putString(PREF_URL_KEY, target).apply()
        _liveUrlChanged.value = target
        return true
    }

    /** 记录上次播放的频道名 (下次进入直播页自动定位). */
    fun setLastChannelName(name: String) {
        if (name == _lastChannelName.value) return
        _lastChannelName.value = name
        Ctx.prefs.edit().putString(PREF_CHANNEL_KEY, name).apply()
    }

    /** 非 http 开头补 http:// (对照 Config.normalizeUrl 的轻量版). */
    fun normalizeUrl(input: String): String {
        val u = input.trim()
        if (u.isEmpty()) return u
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        return "http://$u"
    }

    /** 拉取并解析直播源. 返回 (是否成功, 面向用户的消息, 分组列表). */
    suspend fun fetchAndParse(url: String): Triple<Boolean, String, List<LiveChannelGroup>> {
        val target = normalizeUrl(url)
        if (target.isEmpty()) return Triple(false, "直播源地址为空", emptyList())
        var body = Http.getText(target)
        if (body.isNullOrEmpty()) {
            body = EngineClient.getText(target).ifEmpty { null }
        }
        if (body.isNullOrEmpty()) {
            return Triple(false, "拉取直播源失败: $target\n(网络错误或地址无效)", emptyList())
        }
        val groups = loadLives(TxtSubscribe.parseToJsonArray(body))
        if (groups.isEmpty()) {
            return Triple(false, "直播源解析失败: $target\n(内容不是有效的直播列表)", emptyList())
        }
        val channelCount = groups.sumOf { it.channels.size }
        return Triple(true, "已载入 $channelCount 个频道", groups)
    }

    /**
     * 归一化分组数组 → 直播模型, 对照原版 ApiConfig.loadLives.
     *
     * - 分组名 "_" 前半段为名称, 后半段为分组密码
     * - url 中 "$线路名" 的 $ 后半段为线路名, 缺省 "源N"
     * - 同名频道合并线路 (对照 mergeLiveChannel)
     */
    fun loadLives(livesArray: List<org.json.JSONObject>): List<LiveChannelGroup> {
        val liveChannelGroupList = mutableListOf<LiveChannelGroup>()
        var groupIndex = 0
        var channelIndex = 0
        var channelNum = 0
        for (groupElement in livesArray) {
            val liveChannelGroup = LiveChannelGroup()
            liveChannelGroup.groupIndex = groupIndex++
            val groupName = optString(groupElement, "group").trim()
            val splitGroupName = split2(groupName, '_')
            liveChannelGroup.groupName = splitGroupName[0]
            liveChannelGroup.groupPassword = if (splitGroupName.size > 1) splitGroupName[1] else ""
            channelIndex = 0
            val channelsRaw = groupElement.opt("channels")
            val channels: List<Any?> = when (channelsRaw) {
                is org.json.JSONArray -> (0 until channelsRaw.length()).map { channelsRaw.get(it) }
                else -> emptyList()
            }
            for (channelElement in channels) {
                if (channelElement !is org.json.JSONObject) continue
                val obj = channelElement
                val liveChannelItem = LiveChannelItem()
                liveChannelItem.channelName = optString(obj, "name").trim()
                liveChannelItem.channelLogo = optString(obj, "logo").trim()
                liveChannelItem.channelUa = optString(obj, "ua").trim()
                liveChannelItem.channelClick = optString(obj, "click").trim()
                liveChannelItem.channelFormat = optString(obj, "format").trim()
                liveChannelItem.channelOrigin = optString(obj, "origin").trim()
                liveChannelItem.channelReferer = optString(obj, "referer").trim()
                liveChannelItem.channelTvgId = optString(obj, "tvg-id").trim()
                liveChannelItem.channelTvgName = optString(obj, "tvg-name").trim()
                val parseRaw = obj.opt("parse")
                liveChannelItem.channelParse = when (parseRaw) {
                    is Number -> parseRaw.toInt()
                    is String -> parseRaw.trim().toIntOrNull() ?: 0
                    else -> 0
                }
                val catchupRaw = obj.opt("catchup")
                if (catchupRaw != null) {
                    if (catchupRaw is org.json.JSONObject) {
                        liveChannelItem.channelCatchup = catchupRaw
                    } else {
                        val catchupObj = org.json.JSONObject()
                        catchupObj.put("type", safeStr(catchupRaw))
                        if (obj.has("catchup-source")) {
                            catchupObj.put("source", optString(obj, "catchup-source").trim())
                        }
                        if (obj.has("catchup-replace")) {
                            catchupObj.put("replace", optString(obj, "catchup-replace").trim())
                        }
                        liveChannelItem.channelCatchup = catchupObj
                    }
                }
                val headerRaw = obj.opt("header")
                if (headerRaw is org.json.JSONObject) {
                    val channelHeader = mutableMapOf<String, String>()
                    for (k in headerRaw.keys()) {
                        channelHeader[k] = safeStr(headerRaw.opt(k))
                    }
                    liveChannelItem.channelHeader = channelHeader
                }
                // urls 单元素为对象时取其 JSON 文本 (对照 safeJsonStringList).
                val urlsRaw = obj.opt("urls")
                val urls = mutableListOf<String>()
                when (urlsRaw) {
                    is org.json.JSONObject -> urls.add(safeStr(urlsRaw))
                    is org.json.JSONArray -> {
                        for (i in 0 until urlsRaw.length()) urls.add(safeStr(urlsRaw.get(i)))
                    }
                }
                val sourceNames = mutableListOf<String>()
                val sourceUrls = mutableListOf<String>()
                var sourceIndex = 1
                for (url in urls) {
                    val splitText = split2(url, '$')
                    sourceUrls.add(splitText[0])
                    if (splitText.size > 1) {
                        sourceNames.add(splitText[1])
                    } else {
                        sourceNames.add("源$sourceIndex")
                    }
                    sourceIndex++
                }
                liveChannelItem.channelSourceNames = sourceNames
                liveChannelItem.channelUrls = sourceUrls
                if (mergeLiveChannel(liveChannelGroup.channels, liveChannelItem)) {
                    liveChannelItem.channelIndex = channelIndex++
                    liveChannelItem.channelNum = ++channelNum
                }
            }
            liveChannelGroupList.add(liveChannelGroup)
        }
        return liveChannelGroupList
    }

    /** 同名频道合并线路, 返回 false 表示是重复频道 (不分配新序号). */
    private fun mergeLiveChannel(
        channelItems: MutableList<LiveChannelItem>,
        newItem: LiveChannelItem,
    ): Boolean {
        for (item in channelItems) {
            if (item.channelName == newItem.channelName) {
                mergeLiveChannelUrls(item, newItem)
                return false
            }
        }
        channelItems.add(newItem)
        return true
    }

    /** 对照原版 mergeLiveChannelUrls: 去重追加线路, 补全缺失的线路名. */
    private fun mergeLiveChannelUrls(oldItem: LiveChannelItem, newItem: LiveChannelItem) {
        val oldUrls = oldItem.channelUrls
        val oldSourceNames = oldItem.channelSourceNames
        while (oldSourceNames.size < oldUrls.size) {
            oldSourceNames.add("源${oldSourceNames.size + 1}")
        }
        val newUrls = newItem.channelUrls
        val newSourceNames = newItem.channelSourceNames
        for (i in newUrls.indices) {
            val url = newUrls[i]
            if (oldUrls.contains(url)) continue
            oldUrls.add(url)
            if (i < newSourceNames.size) {
                oldSourceNames.add(newSourceNames[i])
            } else {
                oldSourceNames.add("源${oldSourceNames.size + 1}")
            }
        }
    }

    /** 对照 Dart `_safeStr(obj['key'])`: 键缺失/JSON null (JSONObject.NULL) 都是空串
     *  (org.json 的 optString 对 JSON null 会返回 "null" 字面量, 不能用). */
    private fun optString(obj: org.json.JSONObject, key: String): String = safeStr(obj.opt(key))

    /** 对照 DefaultConfig.safeJsonString 的字符串形式 (对象/数组取 JSON 文本). */
    private fun safeStr(value: Any?): String {
        return when (value) {
            null -> ""
            is String -> value
            is Number, is Boolean -> value.toString()
            is org.json.JSONObject, is org.json.JSONArray -> value.toString()
            else -> ""
        }
    }

    /** 对照 Java `split(sep, 2)`: 最多切两段, 无分隔符时返回单元素列表. */
    private fun split2(value: String, separator: Char): List<String> {
        val index = value.indexOf(separator)
        if (index < 0) return listOf(value)
        return listOf(value.substring(0, index), value.substring(index + 1))
    }
}

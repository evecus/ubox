package com.me.ubox.data.live

import org.json.JSONObject

/**
 * 直播频道 (对照原版 com.mobile.ubox.bean.LiveChannelItem / Flutter 版 live_models.dart).
 *
 * 一个频道可带多条播放地址 (线路): urls 中 "url$线路名" 的 $ 后半段
 * 作为线路名, 缺省为 "源N" (对照原版 loadLives 的 split("\\$", 2)).
 */
class LiveChannelItem {
    var channelIndex = 0

    /** 全局频道序号 (跨分组累加, 对照原版 channelNum). */
    var channelNum = 0
    var channelName = ""
    var channelLogo = ""
    var channelUa = ""
    var channelClick = ""
    var channelFormat = ""
    var channelOrigin = ""
    var channelReferer = ""
    var channelTvgId = ""
    var channelTvgName = ""
    var channelCatchup: JSONObject? = null
    var channelHeader: MutableMap<String, String> = mutableMapOf()
    var channelParse = 0
    var channelSourceNames: MutableList<String> = mutableListOf()
    var channelUrls: MutableList<String> = mutableListOf()

    /** 当前线路下标. */
    var sourceIndex = 0

    val sourceNum: Int get() = channelUrls.size

    val hasCatchup: Boolean get() = channelCatchup != null && channelCatchup!!.length() > 0

    val url: String get() = channelUrls[sourceIndex]

    val sourceName: String
        get() = if (sourceIndex >= 0 && sourceIndex < channelSourceNames.size) channelSourceNames[sourceIndex] else ""

    fun preSource() {
        sourceIndex--
        if (sourceIndex < 0) sourceIndex = sourceNum - 1
    }

    fun nextSource() {
        sourceIndex++
        if (sourceIndex >= sourceNum) sourceIndex = 0
    }

    /** 播放请求头: 频道自身 header + ua/origin/referer (对照原版 getHeaders()). */
    fun getHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers.putAll(channelHeader)
        if (channelUa.isNotEmpty()) headers["User-Agent"] = channelUa
        if (channelOrigin.isNotEmpty()) headers["Origin"] = channelOrigin
        if (channelReferer.isNotEmpty()) headers["Referer"] = channelReferer
        return headers
    }
}

/**
 * 直播分组 (对照原版 com.mobile.ubox.bean.LiveChannelGroup / Flutter 版).
 *
 * 分组名中的 "_" 后半段是分组密码 (对照原版 loadLives:
 * groupName.split("_", 2) → [0]=名称, [1]=密码).
 */
class LiveChannelGroup {
    var groupIndex = 0
    var groupName = ""
    var groupPassword = ""
    val channels: MutableList<LiveChannelItem> = mutableListOf()
}

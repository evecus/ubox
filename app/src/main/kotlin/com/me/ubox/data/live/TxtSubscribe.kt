package com.me.ubox.data.live

import org.json.JSONArray
import org.json.JSONObject

/**
 * 直播源文本解析 (对照原版 com.mobile.novabox.util.live.TxtSubscribe / Flutter 版 txt_subscribe.dart).
 *
 * 输入三种格式, 统一归一化为同一结构:
 * ```json
 * [{"group": "分组名", "channels": [{"name": "CCTV1", "urls": ["http://..."]}]}]
 * ```
 * - JSON 数组: 原样归一化 (兼容 channels/channel 字段, 同名频道自动合并线路)
 * - M3U: `#EXTM3U` 开头, `#EXTINF` 属性行 (group-title / tvg-* / http-user-agent / catchup 等)
 * - TXT: `分组名,#genre#` 定义分组, `频道名,url1#url2` 定义频道
 *
 * 全局设置行 (ua/parse/click/header/format/origin/referer/#EXTHTTP:/#EXTVLCOPT:/#KODIPROP:)
 * 会被合并进其后所有频道 (对照原版 mergeMeta 语义).
 */
object TxtSubscribe {

    const val DEFAULT_GROUP_NAME = "直播"
    private const val LEGACY_DEFAULT_GROUP_NAME = "Ungrouped"

    // 频道名: 取最后一个逗号后的内容 (对照原版 NAME_PATTERN ".*,(.+?)$").
    private val NAME_PATTERN = Regex(".*,(.+?)$")
    private val GROUP_PATTERN = Regex("group-title=\"(.*?)\"")
    private val TVG_CHNO_PATTERN = Regex("tvg-chno=\"(.*?)\"")
    private val TVG_LOGO_PATTERN = Regex("tvg-logo=\"(.*?)\"")
    private val TVG_NAME_PATTERN = Regex("tvg-name=\"(.*?)\"")
    private val TVG_URL_PATTERN = Regex("tvg-url=\"(.*?)\"")
    private val TVG_ID_PATTERN = Regex("tvg-id=\"(.*?)\"")
    private val HTTP_USER_AGENT_PATTERN = Regex("http-user-agent=\"(.*?)\"")
    private val CATCHUP_PATTERN = Regex("catchup=\"(.*?)\"")
    private val CATCHUP_SOURCE_PATTERN = Regex("catchup-source=\"(.*?)\"")
    private val CATCHUP_REPLACE_PATTERN = Regex("catchup-replace=\"(.*?)\"")

    /** 解析直播源文本为归一化分组数组. 依次尝试: JSON 数组 → M3U (`#EXTM3U`) → TXT (`#genre#`). */
    fun parseToJsonArray(str: String?): List<JSONObject> {
        if (str == null) return emptyList()
        var s = str.trim()
        if (s.isEmpty()) return emptyList()
        try {
            val element = JSONArray(s)
            if (element.length() > 0 || s.startsWith("[")) return normalizeJsonArray(element)
        } catch (_: Exception) {
        }
        if (s.startsWith("#EXTM3U")) return parseM3uToJsonArray(s)
        return parseTxtToJsonArray(s)
    }

    private fun normalizeJsonArray(groups: JSONArray): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        for (g in 0 until groups.length()) {
            val groupObj = asObject(groups.get(g))
            val outGroup = JSONObject()
            var groupName = safeJsonString(groupObj, "group", "")
            if (groupName.isEmpty()) {
                groupName = safeJsonString(groupObj, "name", DEFAULT_GROUP_NAME)
            }
            outGroup.put("group", normalizeGroupName(groupName))
            val channelsValue = groupObj.opt("channels")
            val channels: JSONArray? = when (channelsValue) {
                is JSONArray -> channelsValue
                else -> (groupObj.opt("channel") as? JSONArray)
            }
            if (channels != null) {
                for (c in 0 until channels.length()) {
                    val channelObj = asObject(channels.get(c))
                    val outChannel = JSONObject()
                    copyIfExists(channelObj, outChannel, "name")
                    copyIfExists(channelObj, outChannel, "urls")
                    copyIfExists(channelObj, outChannel, "logo")
                    copyIfExists(channelObj, outChannel, "epg")
                    copyIfExists(channelObj, outChannel, "ua")
                    copyIfExists(channelObj, outChannel, "click")
                    copyIfExists(channelObj, outChannel, "format")
                    copyIfExists(channelObj, outChannel, "origin")
                    copyIfExists(channelObj, outChannel, "referer")
                    copyIfExists(channelObj, outChannel, "tvg-id")
                    copyIfExists(channelObj, outChannel, "tvg-name")
                    copyIfExists(channelObj, outChannel, "tvg-chno")
                    copyIfExists(channelObj, outChannel, "parse")
                    copyIfExists(channelObj, outChannel, "header")
                    copyIfExists(channelObj, outChannel, "catchup")
                    copyIfExists(channelObj, outChannel, "catchup-source")
                    copyIfExists(channelObj, outChannel, "catchup-replace")
                    addChannel(outGroup, outChannel)
                }
            }
            if (!outGroup.has("channels")) outGroup.put("channels", JSONArray())
            result.add(outGroup)
        }
        return result
    }

    private fun copyIfExists(src: JSONObject, dst: JSONObject, key: String) {
        if (src.has(key)) dst.put(key, src.opt(key))
    }

    private fun parseM3uToJsonArray(str: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        try {
            val lines = str.replace("\r\n", "\n").replace("\r", "").split('\n')
            var currentGroup: JSONObject? = null
            var pendingChannel: JSONObject? = null
            var pendingMeta = JSONObject()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#EXTM3U")) {
                    mergeMeta(pendingMeta, buildMeta(line))
                    continue
                }
                if (isSetting(line)) {
                    mergeMeta(pendingMeta, buildSetting(line))
                    continue
                }
                if (line.startsWith("#EXTINF") || line.contains("#EXTINF")) {
                    val groupName = normalizeGroupName(get(line, GROUP_PATTERN))
                    currentGroup = findOrCreateGroup(result, groupName)
                    pendingChannel = JSONObject()
                    pendingChannel.put("name", get(line, NAME_PATTERN))
                    mergeMeta(pendingChannel, buildMeta(line))
                    mergeMeta(pendingChannel, pendingMeta)
                    pendingMeta = JSONObject()
                    continue
                }
                if (line.startsWith("#")) continue
                if (currentGroup == null) currentGroup = findOrCreateGroup(result, DEFAULT_GROUP_NAME)
                if (pendingChannel == null) pendingChannel = JSONObject()
                // "url|header" 形式: | 后半段为请求头 (对照原版 split("\\|", 2)).
                val parts = split2(line, '|')
                val url = parts[0].trim()
                if (!isUrl(url)) continue
                if (parts.size > 1) mergeMeta(pendingMeta, parseHeaderString(parts[1]))
                mergeMeta(pendingChannel, pendingMeta)
                var urls = pendingChannel.optJSONArray("urls") ?: JSONArray().also { pendingChannel.put("urls", it) }
                if (!containsUrl(urls, url)) urls.put(url)
                addChannel(currentGroup, pendingChannel)
                pendingChannel = null
                pendingMeta = JSONObject()
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun parseTxtToJsonArray(str: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        try {
            val lines = str.replace("\r\n", "\n").replace("\r", "").split('\n')
            var currentGroup: JSONObject? = null
            var pendingMeta = JSONObject()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#")) {
                    if (isSetting(line)) mergeMeta(pendingMeta, buildSetting(line))
                    continue
                }
                if (line.contains("#genre#")) {
                    // "分组名_密码,#genre#" 的 _ 后半段为分组密码 (loadLives 时再拆).
                    val groupName = split2(line, ',')[0].trim()
                    currentGroup = findOrCreateGroup(result, groupName)
                    pendingMeta = JSONObject()
                    continue
                }
                val split = split2(line, ',')
                if (split.size < 2) continue
                if (currentGroup == null) currentGroup = findOrCreateGroup(result, DEFAULT_GROUP_NAME)
                val channel = JSONObject()
                channel.put("name", split[0].trim())
                mergeMeta(channel, pendingMeta)
                val urls = JSONArray()
                for (part in split[1].trim().split('#')) {
                    val url = part.trim()
                    if (isUrl(url) && !containsUrl(urls, url)) urls.put(url)
                }
                if (urls.length() == 0) continue
                channel.put("urls", urls)
                addChannel(currentGroup, channel)
                pendingMeta = JSONObject()
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun parseHeaderString(text: String): JSONObject {
        val wrapper = JSONObject()
        val obj = JSONObject()
        for (param in text.split('&')) {
            if (!param.contains('=')) continue
            val a = split2(param, '=')
            obj.put(a[0].trim().replace("\"", ""), a[1].trim().replace("\"", ""))
        }
        if (obj.length() > 0) wrapper.put("header", obj)
        return wrapper
    }

    private fun findOrCreateGroup(result: MutableList<JSONObject>, name: String): JSONObject {
        val n = normalizeGroupName(name)
        for (group in result) {
            if (n == group.optString("group")) return group
        }
        val group = JSONObject()
        group.put("group", n)
        group.put("channels", JSONArray())
        result.add(group)
        return group
    }

    /** 空分组名 / 旧版 "Ungrouped" 归一为默认分组 "直播". */
    fun normalizeGroupName(name: String?): String {
        if (name == null) return DEFAULT_GROUP_NAME
        val n = name.trim()
        if (n.isEmpty() || n.lowercase() == LEGACY_DEFAULT_GROUP_NAME.lowercase()) {
            return DEFAULT_GROUP_NAME
        }
        return n
    }

    private fun addChannel(group: JSONObject, channel: JSONObject) {
        var channels = group.optJSONArray("channels") ?: JSONArray().also { group.put("channels", it) }
        val name = safeJsonString(channel, "name", "")
        var exists: JSONObject? = null
        if (name.isNotEmpty()) exists = findChannel(channels, name)
        if (exists == null) {
            channels.put(channel)
        } else {
            // 同名频道: 合并线路并补齐缺失字段 (对照原版 mergeChannel).
            mergeChannel(exists, channel)
        }
        group.put("channels", channels)
    }

    private fun findChannel(channels: JSONArray, name: String): JSONObject? {
        for (i in 0 until channels.length()) {
            val element = channels.get(i)
            if (element !is JSONObject) continue
            if (name == safeJsonString(element, "name", "")) return element
        }
        return null
    }

    private fun mergeChannel(dst: JSONObject, src: JSONObject) {
        mergeUrls(dst, src)
        for (key in src.keys()) {
            if (key == "urls") continue
            if (!dst.has(key) || isEmptyValue(dst.opt(key))) {
                dst.put(key, src.opt(key))
            }
        }
    }

    private fun mergeUrls(dst: JSONObject, src: JSONObject) {
        val srcUrls = src.opt("urls")
        if (srcUrls !is JSONArray) return
        var dstUrls = dst.optJSONArray("urls") ?: JSONArray().also { dst.put("urls", it) }
        for (i in 0 until srcUrls.length()) {
            val element = srcUrls.get(i)
            if (element !is String && element !is Number && element !is Boolean) continue
            val url = jsonElementToString(element).trim()
            if (isUrl(url) && !containsUrl(dstUrls, url)) dstUrls.put(url)
        }
        dst.put("urls", dstUrls)
    }

    private fun isEmptyValue(element: Any?): Boolean {
        return when (element) {
            null -> true
            is String -> element.trim().isEmpty()
            is Number, is Boolean -> false
            is JSONArray -> element.length() == 0
            is JSONObject -> element.length() == 0
            else -> false
        }
    }

    private fun containsUrl(urls: JSONArray, url: String): Boolean {
        for (i in 0 until urls.length()) {
            if (url == jsonElementToString(urls.get(i))) return true
        }
        return false
    }

    private fun mergeMeta(dst: JSONObject, src: JSONObject) {
        for (key in src.keys()) {
            dst.put(key, src.opt(key))
        }
    }

    private fun buildMeta(line: String): JSONObject {
        val obj = JSONObject()
        put(obj, "logo", get(line, TVG_LOGO_PATTERN))
        put(obj, "epg", get(line, TVG_URL_PATTERN))
        put(obj, "tvg-id", get(line, TVG_ID_PATTERN))
        put(obj, "tvg-name", get(line, TVG_NAME_PATTERN))
        put(obj, "tvg-chno", get(line, TVG_CHNO_PATTERN))
        put(obj, "ua", get(line, HTTP_USER_AGENT_PATTERN))
        val catchup = get(line, CATCHUP_PATTERN)
        val source = get(line, CATCHUP_SOURCE_PATTERN)
        val replace = get(line, CATCHUP_REPLACE_PATTERN)
        if (catchup.isNotEmpty() || source.isNotEmpty() || replace.isNotEmpty()) {
            val catchupObj = JSONObject()
            put(catchupObj, "type", catchup)
            put(catchupObj, "source", source)
            put(catchupObj, "replace", replace)
            obj.put("catchup", catchupObj)
        }
        return obj
    }

    private fun buildSetting(line: String): JSONObject {
        val obj = JSONObject()
        if (line.startsWith("ua")) put(obj, "ua", getValue(line, "ua"))
        if (line.startsWith("parse")) put(obj, "parse", getValue(line, "parse"))
        if (line.startsWith("click")) put(obj, "click", getValue(line, "click"))
        if (line.startsWith("header")) {
            val value = getValue(line, "header")
            if (value.isNotEmpty()) {
                try {
                    val parsed = JSONObject(value)
                    obj.put("header", parsed)
                } catch (_: Exception) {
                }
            }
        }
        if (line.startsWith("format")) put(obj, "format", getValue(line, "format"))
        if (line.startsWith("origin")) put(obj, "origin", getValue(line, "origin"))
        if (line.startsWith("referer")) {
            put(obj, "referer", getValue(line, "referer"))
        }
        if (line.startsWith("#EXTHTTP:")) {
            try {
                val parsed = JSONObject(line.substring("#EXTHTTP:".length).trim())
                obj.put("header", parsed)
            } catch (_: Exception) {
            }
        }
        if (line.startsWith("#EXTVLCOPT:")) {
            if (line.contains("http-user-agent")) {
                put(obj, "ua", getValue(line, "http-user-agent"))
            }
            if (line.contains("http-origin")) {
                put(obj, "origin", getValue(line, "http-origin"))
            }
            if (line.contains("http-referrer")) {
                put(obj, "referer", getValue(line, "http-referrer"))
            }
        }
        if (line.startsWith("#KODIPROP:") && line.contains("manifest_type=")) {
            put(obj, "format", getValue(line, "manifest_type"))
        }
        return obj
    }

    private fun isSetting(line: String): Boolean {
        return line.startsWith("ua") ||
            line.startsWith("parse") ||
            line.startsWith("click") ||
            line.startsWith("player") ||
            line.startsWith("header") ||
            line.startsWith("format") ||
            line.startsWith("origin") ||
            line.startsWith("referer") ||
            line.startsWith("#EXTHTTP:") ||
            line.startsWith("#EXTVLCOPT:") ||
            line.startsWith("#KODIPROP:")
    }

    private fun isUrl(url: String): Boolean {
        return url.isNotEmpty() &&
            (url.startsWith("http") ||
                url.startsWith("rtp") ||
                url.startsWith("rtsp") ||
                url.startsWith("rtmp"))
    }

    private fun get(line: String, pattern: Regex): String {
        val match = pattern.find(line)
        if (match != null) {
            val value = match.groupValues.getOrNull(1)
            if (value != null) return value.trim()
        }
        return ""
    }

    private fun getValue(line: String, key: String): String {
        val index = line.indexOf("$key=")
        if (index < 0) return ""
        return line.substring(index + key.length + 1).trim().replace("\"", "")
    }

    private fun put(obj: JSONObject, key: String, value: String) {
        if (value.isNotEmpty()) obj.put(key, value)
    }

    /** 对照 DefaultConfig.safeJsonString: 对象/数组取 JSON 文本, 其余取字符串形式. */
    private fun safeJsonString(obj: JSONObject, key: String, defaultVal: String): String {
        return try {
            if (obj.has(key)) {
                val value = obj.opt(key)
                if (value is JSONObject || value is JSONArray) return value.toString().trim()
                return asPrimitiveString(value).trim()
            }
            defaultVal
        } catch (_: Exception) {
            defaultVal
        }
    }

    private fun asPrimitiveString(value: Any?): String {
        return when (value) {
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> throw IllegalStateException("not a json primitive")
        }
    }

    private fun jsonElementToString(value: Any?): String {
        return when (value) {
            is String -> value
            is Number, is Boolean -> value.toString()
            is JSONObject, is JSONArray -> value.toString()
            else -> ""
        }
    }

    private fun asObject(value: Any?): JSONObject {
        return value as? JSONObject ?: throw IllegalStateException("not a json object")
    }

    /** 对照 Java `split(sep, 2)`: 最多切两段, 无分隔符时返回单元素列表. */
    private fun split2(value: String, separator: Char): List<String> {
        val index = value.indexOf(separator)
        if (index < 0) return listOf(value)
        return listOf(value.substring(0, index), value.substring(index + 1))
    }
}

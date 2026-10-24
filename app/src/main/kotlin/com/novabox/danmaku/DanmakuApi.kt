package com.novabox.danmaku

import com.novabox.data.ConfigStore
import com.novabox.data.Http
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** 云搜候选弹幕结果 (对照 Flutter 版 DanmuSearchResult). */
class DanmuSearchResult(
    val name: String,
    val url: String,
    /** true = 内置接口的剧集条目 (url 指向 comment JSON, 需再转 xml); false = 自定义接口直接返回的弹幕地址. */
    val builtIn: Boolean = false,
)

/**
 * 云搜结果包装: 区分"成功(可能为空列表)"与"失败", 便于弹窗给出准确提示
 * (对齐 NovaBox DanmakuApi.SearchListCallback 的 onSuccess/onError).
 */
class DanmuSearchListResult private constructor(
    val results: List<DanmuSearchResult>,
    /** 失败原因; 空串表示成功. */
    val error: String,
) {
    val isError: Boolean get() = error.isNotEmpty()

    companion object {
        fun ok(results: List<DanmuSearchResult>) = DanmuSearchListResult(results, "")

        fun error(error: String) = DanmuSearchListResult(emptyList(), error)
    }
}

/**
 * 弹幕搜索 API (移植自 NovaBox DanmakuApi, 对照 Flutter 版 danmaku_api.dart):
 *   - 内置 echo-danmaku 服务: search/episodes → comment JSON → 转 bilibili XML
 *   - 自定义接口: 支持 {name}/{episode} 占位 GET, 或 POST name/episode 表单
 *   - 云搜 (searchList): 返回候选列表供用户手动选择
 *
 * 关键行为与 NovaBox 保持一致:
 *   - 内置流程的每一步都带 2 次重试 (退避 1.5s/3s), HTTP 非 2xx 同样重试;
 *   - search/episodes 先带"集数数字"查, 未命中再用不带集数的全量结果查一次;
 *   - search/episodes 返回的是电影但没匹配上时不回落到 search/anime (避免张冠李戴).
 */
object DanmakuApi {

    const val BUILTIN_API = "https://logvardanmu.konfan.cn/87654321"

    /** 与 NovaBox BUILTIN_MAX_RETRY / 1500ms * (retry + 1) 对齐. */
    private const val MAX_RETRY = 2
    private const val RETRY_BASE_DELAY_MS = 1500L

    /** 集数查询模式: 先带数字, 再不带. */
    private const val QUERY_NUMBER = 0
    private const val QUERY_EMPTY = 1

    /** 取消标记: 每次搜索前自增, 回调里比对以丢弃过期响应. */
    @Volatile
    private var searchSeq = 0

    fun cancel() {
        searchSeq++
    }

    /** 是否具备弹幕搜索条件 (对齐 NovaBox DanmakuApi.canSearch). */
    fun canSearch(): Boolean = DanmuSettings.open && getApiUrl().trim().isNotEmpty()

    /** 当前生效的弹幕接口地址: 自定义 > 配置里的 danmaku 字段 > 内置. */
    fun getApiUrl(): String {
        if (!DanmuSettings.useDefaultApi) {
            val custom = DanmuSettings.customApi.trim()
            if (custom.isNotEmpty()) return custom
            val fromConfig = ConfigStore.danmakuApi.value.trim()
            if (fromConfig.isNotEmpty()) return fromConfig
        }
        return BUILTIN_API
    }

    /** 设置页展示用: 未自定义时为空. */
    fun getDisplayApiUrl(): String {
        if (DanmuSettings.useDefaultApi) return ""
        val custom = DanmuSettings.customApi.trim()
        if (custom.isNotEmpty()) return custom
        return ConfigStore.danmakuApi.value.trim()
    }

    private fun hasPlaceholder(url: String): Boolean =
        url.contains("{name}") || url.contains("{episode}")

    private fun isDanmakuSearchApi(url: String): Boolean {
        var u = url.trim()
        while (u.endsWith("/")) u = u.substring(0, u.length - 1)
        return u.endsWith("/danmaku")
    }

    private fun normalizeBaseUrl(url: String): String {
        var u = url.trim()
        while (u.endsWith("/")) u = u.substring(0, u.length - 1)
        if (u.endsWith("/87654321")) u = u.substring(0, u.length - "/87654321".length)
        return u
    }

    private fun encode(text: String): String =
        java.net.URLEncoder.encode(text, "UTF-8")

    /** 自动搜索弹幕: 返回弹幕内容 (bilibili XML 或可下载地址), 失败返回 null. */
    suspend fun search(name: String, episode: String): String? {
        val apiUrl = getApiUrl()
        if (apiUrl.trim().isEmpty()) return null
        val seq = ++searchSeq
        return try {
            if (hasPlaceholder(apiUrl)) {
                val url = apiUrl
                    .replace("{name}", encode(name))
                    .replace("{episode}", encode(episode))
                val body = getString(url) ?: return null
                if (seq != searchSeq) return null
                val found = parseUrl(body)
                return found.ifEmpty { null }
            }
            if (isDanmakuSearchApi(apiUrl)) {
                // 自定义检索接口: POST name/episode, 响应里取 url.
                val body = postForm(apiUrl, mapOf("name" to name, "episode" to episode)) ?: return null
                if (seq != searchSeq) return null
                val found = parseUrl(body)
                return found.ifEmpty { null }
            }
            searchBuiltin(apiUrl, name, episode, seq)
        } catch (_: Exception) {
            null
        }
    }

    /** 内置 echo-danmaku 全流程. */
    private suspend fun searchBuiltin(apiUrl: String, name: String, episode: String, seq: Int): String? {
        val base = normalizeBaseUrl(apiUrl)

        // 1) search/episodes: 先带集数数字查; 未命中再用不带集数的全量结果查一次.
        var queryMode = QUERY_NUMBER
        while (true) {
            val epQuery = episodeQuery(episode, queryMode)
            val url = "$base/api/v2/search/episodes?anime=${encode(name)}" +
                if (epQuery.isEmpty()) "" else "&episode=${encode(epQuery)}"
            val body = try {
                getWithRetry(url)
            } catch (_: Exception) {
                null
            }
            if (seq != searchSeq) return null
            if (body != null) {
                val match = findEpisodeFromSearchEpisodes(body, episode)
                if (match != null && match.id.isNotEmpty()) {
                    return loadBuiltinComment(base, match.id, seq)
                }
                // 返回的是电影但没匹配上: 不再回落到 search/anime.
                if (isSearchEpisodesMovieResult(body)) return null
            }
            val next = nextQueryMode(episode, queryMode)
            if (next < 0) break
            queryMode = next
        }

        // 2) 兜底: search/anime → bangumi
        try {
            val body = getWithRetry("$base/api/v2/search/anime?keyword=${encode(name)}")
            if (seq != searchSeq) return null
            val animeId = findAnimeId(body)
            if (animeId.isEmpty()) return null
            val bangumi = getWithRetry("$base/api/v2/bangumi/$animeId")
            if (seq != searchSeq) return null
            val match = findEpisode(bangumi, episode)
            if (match != null && match.id.isNotEmpty()) {
                return loadBuiltinComment(base, match.id, seq)
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** 带重试的 comment 拉取 (对齐 NovaBox loadBuiltinComment 的重试路径). */
    private suspend fun loadBuiltinComment(base: String, episodeId: String, seq: Int): String? {
        return try {
            val body = getWithRetry("$base/api/v2/comment/$episodeId?format=json")
            if (seq != searchSeq) return null
            val xml = commentJsonToXml(body)
            xml.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /** 云搜: 返回候选弹幕列表. */
    suspend fun searchList(name: String, episode: String): DanmuSearchListResult {
        val apiUrl = getApiUrl()
        if (apiUrl.trim().isEmpty()) {
            return DanmuSearchListResult.error("弹幕搜索接口为空")
        }
        return try {
            if (!hasPlaceholder(apiUrl) && !isDanmakuSearchApi(apiUrl)) {
                val base = normalizeBaseUrl(apiUrl)
                val body = getWithRetry("$base/api/v2/search/episodes?anime=${encode(name)}")
                return DanmuSearchListResult.ok(parseBuiltinSearchList(body, base))
            }
            val body: String = if (hasPlaceholder(apiUrl)) {
                val url = apiUrl
                    .replace("{name}", encode(name))
                    .replace("{episode}", encode(episode))
                getString(url) ?: return DanmuSearchListResult.ok(emptyList())
            } else {
                postForm(apiUrl, mapOf("name" to name, "episode" to episode))
                    ?: return DanmuSearchListResult.ok(emptyList())
            }
            DanmuSearchListResult.ok(parseSearchList(body))
        } catch (e: Exception) {
            DanmuSearchListResult.error(errorMessage(e))
        }
    }

    /** 加载云搜中用户选中的条目: 内置条目需再取 comment JSON 转 xml, 自定义条目本身就是可用地址. */
    suspend fun loadSearchResult(result: DanmuSearchResult): String {
        if (result.url.trim().isEmpty()) return ""
        if (!result.builtIn) return result.url
        return try {
            commentJsonToXml(getWithRetry(result.url))
        } catch (_: Exception) {
            ""
        }
    }

    // ---------------- HTTP ----------------

    /** 网络失败 / HTTP 非 2xx 都按可重试错误处理, 退避 1.5s * (retry + 1), 最多 2 次. */
    private suspend fun getWithRetry(url: String): String {
        for (retry in 0..MAX_RETRY) {
            try {
                return getString(url) ?: throw IllegalStateException("HTTP error")
            } catch (_: Exception) {
                if (retry >= MAX_RETRY) throw Exception("弹幕接口请求失败")
                delay(RETRY_BASE_DELAY_MS * (retry + 1))
            }
        }
        throw Exception("弹幕接口请求失败")
    }

    private suspend fun getString(url: String): String? = Http.getText(url)

    private suspend fun postForm(url: String, fields: Map<String, String>): String? =
        Http.postForm(url, fields)

    private fun errorMessage(e: Any?): String {
        val message = e.toString().trim()
        return message.ifEmpty { "弹幕搜索失败" }
    }

    // ---------------- 响应解析 ----------------

    /** 从响应体解析弹幕地址: JSON 数组/对象取 url 字段, 或纯文本地址. */
    private fun parseUrl(body: String): String {
        val text = body.trim()
        if (text.isEmpty()) return ""
        if (text.startsWith("[")) {
            return try {
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = (item.optString("url") ?: "").trim()
                    if (url.isNotEmpty()) return url
                }
                ""
            } catch (_: Exception) {
                ""
            }
        }
        if (text.startsWith("{")) {
            return try {
                val obj = JSONObject(text)
                (obj.optString("url") ?: "").trim()
            } catch (_: Exception) {
                ""
            }
        }
        if (text.startsWith("http") || text.startsWith("file")) return text
        return ""
    }

    private fun parseSearchList(body: String): List<DanmuSearchResult> {
        val results = mutableListOf<DanmuSearchResult>()
        val text = body.trim()
        if (text.isEmpty()) return results
        try {
            if (text.startsWith("[")) {
                appendSearchResults(results, JSONArray(text))
                return results
            }
            if (!text.startsWith("{")) return results
            val obj = JSONObject(text)
            var items = obj.opt("data") ?: obj.opt("list") ?: obj.opt("results")
            if (items is JSONArray) {
                appendSearchResults(results, items)
                return results
            }
            val url = (obj.optString("url") ?: "").trim()
            if (url.isNotEmpty()) {
                val name = (obj.optString("name") ?: obj.optString("title") ?: "").toString()
                results.add(DanmuSearchResult(name, url))
            }
        } catch (_: Exception) {
        }
        return results
    }

    private fun appendSearchResults(results: MutableList<DanmuSearchResult>, items: JSONArray) {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val url = (item.optString("url") ?: "").trim()
            if (url.isEmpty()) continue
            val name = firstString(
                item,
                listOf("name", "title", "episode", "vod_name"),
            )
            results.add(DanmuSearchResult(name, url))
        }
    }

    private fun parseBuiltinSearchList(body: String, base: String): List<DanmuSearchResult> {
        val results = mutableListOf<DanmuSearchResult>()
        if (body.trim().isEmpty()) return results
        try {
            val obj = JSONObject(body)
            appendBuiltinResults(results, base, obj, "")
            val bangumi = obj.opt("bangumi")
            if (bangumi is JSONObject) {
                appendBuiltinResults(
                    results, base, bangumi,
                    firstString(bangumi, listOf("animeTitle", "title", "name")),
                )
            }
            // 与 NovaBox 一致: animes / anime / data 三个键都遍历一遍.
            for (key in listOf("animes", "anime", "data")) {
                appendBuiltinAnimeResults(results, base, obj.opt(key))
            }
        } catch (_: Exception) {
        }
        return results
    }

    private fun appendBuiltinAnimeResults(results: MutableList<DanmuSearchResult>, base: String, animes: Any?) {
        if (animes !is JSONArray) return
        for (i in 0 until animes.length()) {
            val anime = animes.optJSONObject(i) ?: continue
            val animeName = firstString(anime, listOf("animeTitle", "title", "name"))
            appendBuiltinResults(results, base, anime, animeName)
            val bangumi = anime.opt("bangumi")
            if (bangumi is JSONObject) {
                appendBuiltinResults(results, base, bangumi, animeName)
            }
        }
    }

    private fun appendBuiltinResults(
        results: MutableList<DanmuSearchResult>,
        base: String,
        obj: JSONObject,
        animeName: String,
    ) {
        val episodes = obj.opt("episodes")
        if (episodes !is JSONArray) return
        for (i in 0 until episodes.length()) {
            val ep = episodes.optJSONObject(i) ?: continue
            val id = firstString(ep, listOf("episodeId", "id"))
            if (id.isEmpty()) continue
            val epName = firstString(ep, listOf("episodeTitle", "title", "name"))
            var name = if (animeName.isEmpty()) epName else "$animeName $epName"
            if (name.trim().isEmpty()) name = id
            results.add(DanmuSearchResult(name, "$base/api/v2/comment/$id?format=json", builtIn = true))
        }
    }

    private fun findAnimeId(body: String): String {
        return try {
            val obj = JSONObject(body)
            var array = obj.opt("animes") ?: obj.opt("anime") ?: obj.opt("data")
            if (array is JSONArray && array.length() > 0) {
                val item = array.optJSONObject(0)
                if (item != null) {
                    return firstString(item, listOf("animeId", "id"))
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun findEpisodeFromSearchEpisodes(body: String, episode: String): EpisodeMatch? {
        findEpisode(body, episode, allowMovieFallback = false)?.let { return it }
        return findMovieFallback(body, episode)
    }

    /** search/episodes 返回的是电影类型时, 说明片名命中了电影库; 若这一轮没匹配上, 不应再回落 search/anime. */
    private fun isSearchEpisodesMovieResult(body: String): Boolean {
        return try {
            val obj = JSONObject(body)
            val animes = obj.opt("animes") ?: obj.opt("anime") ?: obj.opt("data")
            if (animes !is JSONArray) return false
            for (i in 0 until animes.length()) {
                val anime = animes.optJSONObject(i)
                if (anime != null && isMovieType(anime) && anime.has("episodes")) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun findEpisode(body: String, episode: String, allowMovieFallback: Boolean = true): EpisodeMatch? {
        return try {
            val obj = JSONObject(body)
            val episodeList = findEpisodeList(obj) ?: return null
            if (episodeList.episodes.isEmpty()) return null
            val targetNumber = extractNumber(episode)
            var first: EpisodeMatch? = null
            var firstMandarin: EpisodeMatch? = null
            for (item in episodeList.episodes) {
                val id = firstString(item, listOf("episodeId", "id"))
                if (id.isEmpty()) continue
                val title = firstString(item, listOf("episodeTitle", "title", "name"))
                val number = parseEpisodeNumber(firstString(item, listOf("episodeNumber", "number", "sort")))
                val match = EpisodeMatch(id, title, number)
                if (first == null) first = match
                if (firstMandarin == null && isMandarinTitle(title)) firstMandarin = match
                if (episode.isNotEmpty() && title.isNotEmpty() && title.contains(episode)) return match
                if (targetNumber > 0 && number == targetNumber) return match
                if (targetNumber > 0 && extractNumber(title) == targetNumber) return match
            }
            if (episode.isEmpty()) return first
            if (allowMovieFallback) firstMandarin ?: first else null
        } catch (_: Exception) {
            null
        }
    }

    private fun findEpisodeList(obj: JSONObject): EpisodeList? {
        val episodes = obj.opt("episodes")
        if (episodes is JSONArray) {
            return EpisodeList(episodes.toMapList(), isMovieType(obj))
        }
        val bangumi = obj.opt("bangumi")
        if (bangumi is JSONObject) {
            val eps = bangumi.opt("episodes")
            if (eps is JSONArray) {
                return EpisodeList(eps.toMapList(), isMovieType(bangumi))
            }
        }
        val animes = obj.opt("animes") ?: obj.opt("anime") ?: obj.opt("data")
        if (animes is JSONArray) {
            for (i in 0 until animes.length()) {
                val anime = animes.optJSONObject(i) ?: continue
                val eps = anime.opt("episodes")
                if (eps is JSONArray && eps.length() > 0) {
                    return EpisodeList(eps.toMapList(), isMovieType(anime))
                }
            }
        }
        return null
    }

    private fun JSONArray.toMapList(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for (i in 0 until length()) {
            (opt(i) as? JSONObject)?.let { out.add(it) }
        }
        return out
    }

    private fun findMovieFallback(body: String, episode: String): EpisodeMatch? {
        return try {
            val obj = JSONObject(body)
            val animes = obj.opt("animes") ?: obj.opt("anime") ?: obj.opt("data")
            if (animes !is JSONArray) return null
            val preferCantonese = episode.contains("粤语")
            var firstMovie: EpisodeMatch? = null
            for (i in 0 until animes.length()) {
                val anime = animes.optJSONObject(i) ?: continue
                if (!isMovieType(anime)) continue
                val episodes = anime.opt("episodes")
                if (episodes !is JSONArray) continue
                for (j in 0 until episodes.length()) {
                    val ep = episodes.optJSONObject(j) ?: continue
                    val id = firstString(ep, listOf("episodeId", "id"))
                    if (id.isEmpty()) continue
                    val title = firstString(ep, listOf("episodeTitle", "title", "name"))
                    val match = EpisodeMatch(
                        id, title,
                        parseEpisodeNumber(firstString(ep, listOf("episodeNumber", "number", "sort"))),
                    )
                    if (firstMovie == null) firstMovie = match
                    val preferred = if (preferCantonese) title.contains("粤语") else isMandarinTitle(title)
                    if (preferred) return match
                }
            }
            firstMovie
        } catch (_: Exception) {
            null
        }
    }

    private fun isMovieType(obj: JSONObject): Boolean {
        if ((obj.opt("type") ?: obj.opt("typeDescription") ?: "").toString() == "电影") return true
        val bangumi = obj.opt("bangumi")
        if (bangumi is JSONObject && (bangumi.optString("type") ?: "").toString() == "电影") return true
        val animes = obj.opt("animes") ?: obj.opt("anime") ?: obj.opt("data")
        if (animes is JSONArray) {
            for (i in 0 until animes.length()) {
                val anime = animes.optJSONObject(i)
                if (anime != null && (anime.optString("type") ?: "").toString() == "电影") return true
            }
        }
        return false
    }

    private fun isMandarinTitle(title: String): Boolean =
        title.isNotEmpty() && (title.contains("国语") || title.contains("普通话"))

    /** 集数查询值: QUERY_NUMBER → 取文本里的数字; QUERY_EMPTY → 空 (不带 episode 参数). */
    private fun episodeQuery(episode: String, queryMode: Int): String {
        if (queryMode == QUERY_EMPTY) return ""
        val number = extractNumber(episode)
        return if (number > 0) "$number" else ""
    }

    private fun nextQueryMode(episode: String, queryMode: Int): Int {
        if (queryMode == QUERY_NUMBER && episodeQuery(episode, QUERY_NUMBER).isNotEmpty()) {
            return QUERY_EMPTY
        }
        return -1
    }

    private fun parseEpisodeNumber(value: String): Int {
        if (value.isEmpty()) return -1
        val parsed = value.toDoubleOrNull()
        if (parsed != null) return parsed.toInt()
        return extractNumber(value)
    }

    private fun extractNumber(text: String): Int {
        val digits = text.filter { it.isDigit() }
        if (digits.isEmpty()) return -1
        return digits.toIntOrNull() ?: -1
    }

    private fun firstString(obj: JSONObject, keys: List<String>): String {
        for (key in keys) {
            val value = (obj.opt(key)?.toString() ?: "")
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    // ---------------- comment JSON → bilibili XML ----------------

    /** 将内置接口的 comment JSON ({comments: [{p, m}]}) 转为 bilibili XML. */
    fun commentJsonToXml(body: String): String {
        if (body.trim().isEmpty()) return ""
        return try {
            val obj = JSONObject(body)
            var comments = obj.opt("comments") ?: obj.opt("data")
            if (comments !is JSONArray || comments.length() == 0) return ""
            val sb = StringBuilder("<i>")
            for (i in 0 until comments.length()) {
                val item = comments.optJSONObject(i) ?: continue
                var param = (item.opt("p")?.toString() ?: "")
                var text = (item.opt("m")?.toString() ?: "")
                if (text.isEmpty()) text = (item.opt("text")?.toString() ?: "")
                if (param.isEmpty() || text.isEmpty()) continue
                sb.append("<d p=\"${escapeXml(normalizeDanmakuParam(param))}\">${escapeXml(text)}</d>")
            }
            sb.append("</i>")
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }

    /** 兼容部分接口把 color 放在 size 位 (或 color 非法) 的情况. */
    private fun normalizeDanmakuParam(param: String): String {
        val values = param.split(',')
        if (values.size < 4) return param
        val time = values[0]
        val type = values[1]
        var size = values[2]
        var color = values[3]
        if (isColorValue(size)) {
            color = size
            size = "25"
        } else if (!isColorValue(color)) {
            color = "16777215"
        }
        return "$time,$type,$size,${normalizeColor(color)}"
    }

    private fun isColorValue(value: String): Boolean {
        val text = value.trim()
        if (text.isEmpty()) return false
        if (text.startsWith("#")) return true
        if (text.startsWith("0x") || text.startsWith("0X")) return true
        val color = text.toIntOrNull() ?: return false
        return color >= 0 && color <= 0x00FFFFFF
    }

    private fun normalizeColor(color: String): String {
        if (color.isEmpty()) return "16777215"
        val text = color.trim()
        return try {
            when {
                text.startsWith("#") -> Integer.parseInt(text.substring(1), 16).toString()
                text.startsWith("0x") || text.startsWith("0X") ->
                    Integer.parseInt(text.substring(2), 16).toString()
                else -> text
            }
        } catch (_: Exception) {
            text
        }
    }

    private fun escapeXml(text: String): String {
        if (text.isEmpty()) return ""
        return text
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
            .replace(">", "&gt;")
            .replace("<", "&lt;")
    }
}

private class EpisodeMatch(
    val id: String,
    val title: String,
    val number: Int,
)

private class EpisodeList(
    val episodes: List<JSONObject>,
    val isMovie: Boolean,
)

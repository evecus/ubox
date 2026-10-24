package com.me.ubox.data

import com.me.ubox.engine.EngineClient
import org.json.JSONArray
import org.json.JSONObject

/** Runtime state + typed wrappers for engine calls (对照 Flutter 版 spider_data.dart). */
object SpiderData {

    /** home: {"class":[{"type_name":.., "type_id":..}],"list":[...]} */
    suspend fun home(siteKey: String, filter: Boolean): JSONObject =
        asMap(EngineClient.invoke(siteKey, "home", if (filter) "true" else "false"))

    suspend fun homeVod(siteKey: String): JSONObject =
        asMap(EngineClient.invoke(siteKey, "homeVod", ""))

    suspend fun category(siteKey: String, tid: String, page: String, extend: Map<String, String>): JSONObject {
        val ext = JSONObject()
        for ((k, v) in extend) ext.put(k, v)
        val arg = JSONObject().apply {
            put("tid", tid)
            put("pg", page)
            put("filter", false)
            put("ext", ext)
        }
        return asMap(EngineClient.invoke(siteKey, "category", arg.toString()))
    }

    suspend fun detail(siteKey: String, id: String): JSONObject {
        val arg = JSONObject().apply { put("id", id) }
        return asMap(EngineClient.invoke(siteKey, "detail", arg.toString()))
    }

    suspend fun search(siteKey: String, key: String, page: String): JSONObject {
        val arg = JSONObject().apply {
            put("wd", key)
            put("pg", page)
        }
        return asMap(EngineClient.invoke(siteKey, "search", arg.toString()))
    }

    suspend fun play(siteKey: String, flag: String, id: String): JSONObject {
        val arg = JSONObject().apply {
            put("flag", flag)
            put("id", id)
        }
        return asMap(EngineClient.invoke(siteKey, "player", arg.toString()))
    }

    private fun asMap(raw: String): JSONObject {
        if (raw.isEmpty()) return JSONObject()
        var text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) text = text.substring(start, end + 1)
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }
    }
}

/** 列表行数据 (对照 Flutter 版 VodItem). */
data class VodItem(
    val id: String,
    val name: String,
    val cover: String,
    val remark: String? = null,
    val score: String? = null,
    val year: String? = null,
    /** 所属源 key. 豆瓣热播/观看历史等条目可能不属于当前首页源. */
    val siteKey: String = "",
) {
    /** id 为空的条目 (豆瓣直连抓取的热播没有 vod_id): 跳搜索页. */
    val searchOnly: Boolean get() = id.isEmpty()
}

/** 播放地址分组 (对照 Flutter 版 PlayUrl). */
class PlayUrl(
    val flag: String,
    val eps: List<String>,
    val urls: List<String>,
    val playFrom: String,
)

fun asList(raw: Any?): List<JSONObject> {
    if (raw is JSONArray) {
        val out = mutableListOf<JSONObject>()
        for (i in 0 until raw.length()) {
            (raw.get(i) as? JSONObject)?.let { out.add(it) }
        }
        return out
    }
    return emptyList()
}

fun vodList(raw: Any?, siteKey: String = ""): List<VodItem> {
    return asList(raw).mapNotNull { m ->
        val name = JsonUtil.optStringOrNull(m, "vod_name") ?: ""
        if (name.isEmpty()) return@mapNotNull null
        VodItem(
            id = JsonUtil.optStringOrNull(m, "vod_id") ?: "",
            siteKey = siteKey,
            name = name,
            cover = JsonUtil.optStringOrNull(m, "vod_pic") ?: "",
            remark = JsonUtil.optStringOrNull(m, "vod_remarks"),
            score = scoreOf(m),
            year = yearOf(m),
        )
    }
}

/** 评分字段: mac-cms 风格 vod_score, 豆瓣风格 vod_douban_score. */
private fun scoreOf(m: JSONObject): String? {
    for (key in listOf("vod_score", "vod_douban_score")) {
        val v = (JsonUtil.optStringOrNull(m, key) ?: "").trim()
        if (v.isNotEmpty() && v != "0" && v != "0.0" && v != "暂无" && v != "暂无评分") return v
    }
    return null
}

/** 年份角标 (对照原版 GridAdapter tvYear: year <= 0 时隐藏). */
private fun yearOf(m: JSONObject): String? {
    val v = (JsonUtil.optStringOrNull(m, "vod_year") ?: "").trim()
    if (v.isEmpty() || v == "0") return null
    val n = v.toIntOrNull()
    if (n != null && n <= 0) return null
    return v
}

/** 原版 UserFragment.loadHots: 豆瓣直连抓取的热播 JSON {"data":[{"title","rate","cover"}]}. */
const val K_DOUBAN_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

fun doubanHotList(raw: Any?): List<VodItem> {
    return asList(raw).mapNotNull { m ->
        val rate = (JsonUtil.optStringOrNull(m, "rate") ?: "").trim()
        val cover = JsonUtil.optStringOrNull(m, "cover") ?: ""
        val name = JsonUtil.optStringOrNull(m, "title") ?: ""
        if (name.isEmpty()) return@mapNotNull null
        VodItem(
            id = "",
            name = name,
            // 对照原版 loadHots: 豆瓣图床有防盗链, 封面必须带 UA + Referer (由 picUrlAndHeaders 拆成请求头).
            cover = if (cover.isEmpty()) "" else "$cover@User-Agent=$K_DOUBAN_UA@Referer=https://www.douban.com/",
            remark = if (rate.isNotEmpty() && rate != "0") "$rate 分" else null,
            score = if (rate.isNotEmpty() && rate != "0") rate else null,
        )
    }
}

fun classList(raw: Any?): List<JSONObject> {
    return asList(raw).filter { (JsonUtil.optStringOrNull(it, "type_id") ?: "").isNotEmpty() }
}

/** "flag1#!#第1集$url#第2集$url2" style groups are separated by $$$. */
fun parsePlayGroups(playUrl: String, playFrom: String): List<PlayUrl> {
    val flags = playFrom.split("$$$")
    val groups = playUrl.split("$$$")
    val result = mutableListOf<PlayUrl>()
    for (i in groups.indices) {
        val flag = if (i < flags.size && flags[i].isNotEmpty()) flags[i] else "playlist"
        val eps = mutableListOf<String>()
        val links = mutableListOf<String>()
        for (ep in groups[i].split('#')) {
            if (ep.isEmpty()) continue
            val sep = ep.indexOf('$')
            if (sep > 0) {
                eps.add(ep.substring(0, sep).trim())
                links.add(ep.substring(sep + 1).trim())
            } else {
                eps.add("第${eps.size + 1}集")
                links.add(ep.trim())
            }
        }
        if (links.isNotEmpty()) result.add(PlayUrl(flag, eps, links, flag))
    }
    return result
}

/** detail 页 vod_play_url 兜底解析 (对照 Flutter 版 playUrlOf). */
fun playUrlOf(json: JSONObject): String {
    var url = JsonUtil.optStringOrNull(json, "url") ?: ""
    val parse = json.opt("parse")
    if (parse is JSONArray && parse.length() > 0 && !url.startsWith("http")) {
        val first = parse.opt(0)
        if (first is String) url = first
    }
    if (url.isEmpty()) {
        val jx = JsonUtil.optStringOrNull(json, "jx")
        if (jx != null && jx.startsWith("http")) url = jx
    }
    return url
}

/**
 * FongMi 风格的图片地址后缀: "url@User-Agent=xx@Referer=yy".
 * 注意第一个 @ 段是 UA 的值本身 (无键名), 其余段才是 key=value.
 * 网络图片加载不走这套约定, 拆成干净 URL + headers.
 */
fun picUrlAndHeaders(url: String): Pair<String, Map<String, String>> {
    var u = url.trim()
    val headers = mutableMapOf<String, String>()
    val at = u.indexOf("@User-Agent=")
    if (at > 0) {
        val rest = u.substring(at + 12)
        u = u.substring(0, at)
        val segs = rest.split('@')
        if (segs.isNotEmpty() && segs[0].isNotEmpty()) headers["User-Agent"] = segs[0]
        for (i in 1 until segs.size) {
            val eq = segs[i].indexOf('=')
            if (eq > 0) headers[segs[i].substring(0, eq)] = segs[i].substring(eq + 1)
        }
    }
    return Pair(u, headers)
}

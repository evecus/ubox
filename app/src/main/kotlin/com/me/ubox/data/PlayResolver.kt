package com.me.ubox.data

import com.me.ubox.engine.EngineClient
import org.json.JSONObject

/** 播放信息 (对照 Flutter 版 PlayInfo). */
class PlayInfo(
    val url: String,
    val headers: Map<String, String>,
    /** 爬虫 playerContent 自带的弹幕地址 (原版 info.optString("danmaku", "")). */
    val danmaku: String = "",
)

/**
 * M2: full play chain resolution for js/jar sources (对照 Flutter 版 play_resolver.dart).
 *  - direct url
 *  - proxy://do=js&... (js spider own proxy, muxed through local server)
 *  - parse == 1 + jx fallback
 *  - headers from spider playerContent (UA / Referer / etc.)
 */
object PlayResolver {

    suspend fun resolve(site: Site, flag: String, id: String): PlayInfo? {
        return try {
            // 对照原版播放页 initData: 先声明当前播放源, 再 playerContent.
            // jar 源 playerContent 内部会同步探测 http://127.0.0.1:9978/proxy?do=ck (不带 siteKey),
            // 引擎靠 currentPlaySourceKey 回退定位到本源的 spider 实例, 探测才能 200.
            EngineClient.setPlaySource(site.key)
            val json = SpiderData.play(site.key, flag, id)
            if (json.length() == 0) return null
            var url = JsonUtil.optStringOrNull(json, "url") ?: ""

            val proxyAddress = EngineClient.proxyUrl(true)
            val serverAddress = if (proxyAddress.contains("/proxy")) {
                proxyAddress.substring(0, proxyAddress.indexOf("/proxy"))
            } else ""

            // js spider own proxy pass-through (proxy://do=js&...)
            // 原版 DefaultConfig.checkReplaceProxy: proxy:// → http://127.0.0.1:9978/proxy?
            // 引擎未就绪时 serverAddress 为空, 此时拼出来的是相对地址必然转圈, 直接判失败.
            if (url.startsWith("proxy://")) {
                if (serverAddress.isEmpty()) return null
                url = "$serverAddress/proxy?${url.substring("proxy://".length)}"
            }

            // parse@jx chain
            if (!url.startsWith("http")) {
                val jx = JsonUtil.optStringOrNull(json, "jx") ?: ""
                val parse = json.opt("parse")
                val parseFlag = parse == 1 || parse == true
                if (jx.startsWith("http") && parseFlag) {
                    url = jx
                } else if (serverAddress.isNotEmpty() && url.isNotEmpty()) {
                    // relative / pseudo-protocol url -> js own proxy
                    url = "$serverAddress/proxy?do=js&siteType=${site.type}" +
                        "&siteKey=${java.net.URLEncoder.encode(site.key, "UTF-8")}" +
                        "&url=${java.net.URLEncoder.encode(url, "UTF-8")}"
                }
            }

            if (!url.startsWith("http")) return null

            val headers = headerMap(json)
            if (!headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] = Http.DEFAULT_UA
            }
            PlayInfo(url, headers, danmaku = normalizeDanmaku(JsonUtil.optStringOrNull(json, "danmaku") ?: ""))
        } catch (_: Exception) {
            null
        }
    }

    private fun headerMap(json: JSONObject): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        var header: Any? = json.opt("header")
        if (header is String && header.isNotEmpty()) {
            val decoded = decodeJson(header)
            if (decoded != null) {
                header = decoded
            } else {
                // "k1=v1;k2=v2" blob
                for (pair in header.split(';')) {
                    val eq = pair.indexOf('=')
                    if (eq > 0) out[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
                }
                return out
            }
        }
        if (header is JSONObject) {
            for (k in header.keys()) {
                val v = header.opt(k)
                if (v is JSONObject && v.length() > 0) {
                    for (k2 in v.keys()) {
                        out[k2] = (v.opt(k2)?.toString() ?: "")
                    }
                } else {
                    out[k] = (v?.toString() ?: "")
                }
            }
        }
        return out
    }

    /**
     * playerContent 的 danmaku 字段可能是: 地址 / 内联 XML 字符串 (原版只支持这种),
     * 也可能是 [{"name":..,"url":..}] 数组或 {"url":..} 对象 (部分站源/新版规范).
     * 上游用 optString 取值, 数组/对象会变成一段 JSON 文本, 原样当弹幕源只会解析出 0 条;
     * 这里统一取出首个可用地址.
     */
    private fun normalizeDanmaku(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return try {
            when {
                t.startsWith("[") -> {
                    val arr = org.json.JSONArray(t)
                    for (i in 0 until arr.length()) {
                        val u = when (val item = arr.opt(i)) {
                            is String -> item
                            is JSONObject -> item.optString("url").ifEmpty { item.optString("danmaku") }
                            else -> ""
                        }
                        if (u.isNotBlank()) return u.trim()
                    }
                    ""
                }
                t.startsWith("{") -> {
                    val o = JSONObject(t)
                    o.optString("url").ifEmpty { o.optString("danmaku") }.trim()
                }
                else -> t
            }
        } catch (_: Exception) {
            t
        }
    }

    private fun decodeJson(text: String): JSONObject? {
        val t = text.trim()
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(t.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }
}

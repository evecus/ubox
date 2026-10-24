package com.novabox.data

import org.json.JSONObject

/** 配置线路 (对应原版 "配置地址" 列表中的一行, 来自 {"urls":[...]} 集合配置). */
data class ConfigLine(
    val name: String,
    val url: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("url", url)
    }

    companion object {
        fun from(raw: Any?): ConfigLine? {
            if (raw !is JSONObject) return null
            val name = (raw.optString("name") ?: "").trim()
            val url = (raw.optString("url") ?: "").trim()
            if (url.isEmpty()) return null
            return ConfigLine(if (name.isEmpty()) url else name, url)
        }
    }
}

/** 配置导入/解析过程中的可预期错误, 带上可读原因. */
class ConfigException(message: String) : Exception(message)

/**
 * TVBox 站点 (对照原版 SourceBean / Flutter 版 config.dart Site).
 */
class Site(
    val key: String,
    val name: String,
    val type: Int,
    val api: String,
    val ext: String,
    val jar: String,
) {

    /**
     * 引擎 (SpiderLoader) 实际支持的源: type 0/4 = jar/csp, type 3 = js 或 csp_.
     * 与原版 ApiConfig.getCSP 一致: 不按 type 卡死, 只要 api 非空即可路由.
     * type 1 (xml 聚合) 与 .py 源引擎不支持, 不算可用源.
     */
    val isRealSource: Boolean
        get() = (type == 0 || type == 3 || type == 4) && api.isNotEmpty() && !api.contains(".py")

    /** 需要主 jar (config.spider) 才能工作的 csp_ 源之外的源 (自带 jar 或 js). */
    val needsMainJar: Boolean
        get() = jar.isEmpty() && !(api.endsWith(".js") || api.contains(".js?"))

    val displayName: String
        get() = if (name.isEmpty()) key else name

    /** 引擎 loadSites 用的 JSON (key/name/type/api/ext/jar, 对照 Dart toMap). */
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("name", displayName)
        put("type", type)
        put("api", api)
        put("ext", ext)
        put("jar", jar)
    }

    companion object {

        fun fromMap(map: JSONObject, base: String): Site? {
            val key = map.optString("key") ?: ""
            if (key.isEmpty()) return null
            val api = absApi(map.optString("api") ?: "", base)
            return Site(
                key = key,
                name = map.optString("name") ?: "",
                type = map.optInt("type", 0),
                api = api,
                ext = pickExt(map, base, api),
                jar = abs(map.optString("jar") ?: "", base),
            )
        }

        fun abs(path: String, base: String): String {
            val t = path.trim()
            if (t.isEmpty() || t.startsWith("http")) return t
            if (base.isEmpty() || !base.startsWith("http")) return t
            val scheme = base.substringBefore("://")
            val host = base.substringAfter("://").substringBefore('/')
            val root = "$scheme://$host"
            val dir = base.substring(0, base.lastIndexOf('/') + 1)
            val p = t.replace("\r", "").replace("\n", "")
            return when {
                p.startsWith("//") -> "$scheme:$p"
                p.startsWith("/") -> root + p
                p.startsWith("./") -> dir + p.substring(2)
                else -> dir + p
            }
        }

        /**
         * api 的绝对化必须放过 csp_ 前缀:
         * csp_XXX 是主 jar 内爬虫类引用 (com.github.catvod.spider.XXX), 不是相对路径.
         * 若按 abs 拼成 http://<配置域名>/csp_XXX, Kotlin 侧 removePrefix("csp_")
         * 后得到 http://<域名>/XXX, loadClass 必然 ClassNotFound —— 所有 jar 源全灭.
         * 对照原版 ApiConfig: sb.setApi(obj.get("api").getAsString().trim()) 原样透传.
         */
        fun absApi(path: String, base: String): String {
            val t = path.trim()
            if (t.startsWith("csp_")) return t
            return abs(t, base)
        }

        /**
         * ext 原样透传 (对照原版 safeJsonString: 对象/数组 → JSON 文本, 字符串 → trim).
         * 只对 js 源补全 ./ ../ 相对路径 (jar 源的 ext 常是 base64/加密串, 天然带 "/",
         * 拼域名会让 jar 解析出空密钥).
         */
        fun pickExt(map: JSONObject, base: String, api: String): String {
            if (!map.has("ext")) return ""
            val ext = map.opt("ext")
            if (ext is JSONObject || ext is org.json.JSONArray) {
                return ext.toString().trim()
            }
            val s = (ext?.toString() ?: "").trim()
            val isJsApi = api.endsWith(".js") || api.contains(".js?")
            if (isJsApi && (s.startsWith("./") || s.startsWith("../"))) return abs(s, base)
            return s
        }
    }
}

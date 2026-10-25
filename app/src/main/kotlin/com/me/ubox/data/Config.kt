package com.me.ubox.data

import android.app.Application
import com.me.ubox.engine.EngineClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** 全局 Application 持有者 (替代 Flutter 的 path_provider / SharedPreferences.getInstance). */
object Ctx {
    lateinit var app: Application

    val prefs by lazy { app.getSharedPreferences("ubox", Application.MODE_PRIVATE) }

    val filesDir: File get() = app.filesDir
}

/**
 * 配置导入/解析 (对照 Flutter 版 config.dart, 逐行为端口).
 *
 * 支持:
 *  - 远程 URL (自动补 http://) / 直接粘贴的 JSON 文本
 *  - {"urls":[...]} 多线路集合 (原版 switchApiCollectionIfNeeded: 自动切到第一条)
 *  - {"url":"..."} 单线包装跳转 (最多 6 层)
 *  - 加密配置: 8位** + base64 / 2423 AES-CBC / ;pk; AES-ECB (引擎侧解密)
 *  - 整体 base64 / @ 拼接多段 base64 / 带 // 注释的轻量 JSON
 */
class Config(var url: String) {

    var base: String = ""
    val sites = mutableListOf<Site>()
    var spiderRaw: String = ""

    /** 主 jar 加载失败的可读原因 (空 = 未尝试或成功). 供设置页展示. */
    var jarError: String = ""

    /** 集合配置 ({"urls":[...]}) 的线路列表; 当前生效的是 [url] 对应那条. */
    var lines: List<ConfigLine> = emptyList()
    var lineSource: String = ""

    /** 配置里的 "danmaku" 字段 (原版 ApiConfig.getDanmaku()), fetch 时填充. */
    var danmakuApi: String = ""

    /** 解析出站点的那份配置文本 (解密/解码后), 供本地缓存保存. */
    var rawText: String = ""

    /** 本次配置来自本地缓存 (未联网拉取); 此时 spider 主 jar 本地已有则直接复用. */
    var fromCache: Boolean = false

    // ---------------------------------------------------------------- 本地缓存

    /** 从本地缓存还原 (地址 = [url]); 命中返回 true, 此时不联网. */
    fun loadFromCache(): Boolean {
        val entry = ConfigCache.read(ConfigCache.KIND_TVBOX, url.trim()) ?: return false
        val map = decodeMap(entry.text) ?: return false
        val sitesRaw = map.optJSONArray("sites") ?: return false
        if (sitesRaw.length() == 0) return false
        val meta = entry.meta
        base = meta.optString("base")
        spiderRaw = JsonUtil.optStringOrNull(map, "spider") ?: ""
        danmakuApi = (JsonUtil.optStringOrNull(map, "danmaku") ?: "").trim()
        val cachedLines = mutableListOf<ConfigLine>()
        val arr = meta.optJSONArray("lines")
        if (arr != null) {
            for (i in 0 until arr.length()) ConfigLine.from(arr.opt(i))?.let { cachedLines.add(it) }
        }
        lines = cachedLines
        lineSource = meta.optString("lineSource")
        parseSites(sitesRaw)
        if (sites.isEmpty()) return false
        meta.optString("url").takeIf { it.isNotEmpty() }?.let { url = it }
        rawText = entry.text
        fromCache = true
        return true
    }

    /** 写入本地缓存 (开关开启时才生效). */
    fun saveToCache() {
        if (rawText.isEmpty()) return
        val meta = JSONObject().apply {
            put("url", url)
            put("base", base)
            put("lineSource", lineSource)
            put("lines", JSONArray().also { a -> lines.forEach { a.put(it.toJson()) } })
        }
        ConfigCache.write(ConfigCache.KIND_TVBOX, url.trim(), rawText, meta)
    }

    // ---------------------------------------------------------------- 拉取解析

    /** 拉取并解析配置. 失败抛 [ConfigException], 原因可读. */
    suspend fun fetch() {
        lines = emptyList()
        lineSource = ""
        val pk = pkKeyOf(url)
        val engineTried = mutableSetOf<String>()
        // 先按原始输入分流 (对照原版 configUrl: 非 http 一律补 http://;
        // 但粘贴的配置内容是文本, 不能补 scheme 再去请求):
        //   - '{'/'[' 开头 → JSON 文本
        //   - 纯 base64 长串 (无 '.'/空格, 字符集受限) → 整体 base64 配置
        //   - 其余 (含裸域名 www.xxx.net/tv) → 按原版补 http:// 走网络
        val raw = url.trim()
        val isPasted = raw.startsWith("{") ||
            raw.startsWith("[") ||
            (raw.length > 100 &&
                !raw.contains(".") &&
                !Regex("\\s").containsMatchIn(raw) &&
                Regex("^[A-Za-z0-9+/]+={0,2}$").containsMatchIn(raw))
        var current = if (isPasted) raw else normalizeUrl(raw)
        // 直接粘贴的文本不走网络; 集合/跳转目标一定是 http 地址.
        var body: String? = if (isPasted) raw else fetchBody(current)
        if (body == null && !isPasted) {
            // Dart/Compose http 失败 (非 ASCII 域名/证书/被墙): 引擎侧 OkHttp 兜底, 支持 IDN 中文域名.
            body = EngineClient.getText(current).ifEmpty { null }
            engineTried.add(current)
        }
        for (hop in 0 until 6) {
            if (body == null) {
                throw ConfigException("拉取配置失败: $current\n(网络错误或地址无效)")
            }
            var text = decodeConfigText(body!!, pk)
            var map = decodeMap(text)
            if (map == null && current.startsWith("http") && engineTried.add(current)) {
                // 拿到了内容但解析不了 (IDN 域名被路由到错误页面/反爬/编码):
                // 用引擎 OkHttp 按原版 UA 再拉一次.
                val alt = EngineClient.getText(current)
                if (alt.isNotEmpty()) {
                    text = decodeConfigText(alt, pk)
                    map = decodeMap(text)
                }
            }
            if (map == null) {
                throw ConfigException("配置解析失败: $current\n(内容不是有效的 TVBox 配置)")
            }
            val sitesRaw = map.optJSONArray("sites")
            if (sitesRaw != null && sitesRaw.length() > 0) {
                spiderRaw = JsonUtil.optStringOrNull(map, "spider") ?: ""
                danmakuApi = (JsonUtil.optStringOrNull(map, "danmaku") ?: "").trim()
                parseSites(sitesRaw)
                rawText = text
                fromCache = false
                url = current
                return
            }
            // 多线路集合: 自动切第一条, 同时保留列表供设置页切换.
            val urlsRaw = map.optJSONArray("urls")
            if (urlsRaw != null && urlsRaw.length() > 0) {
                val parsed = mutableListOf<ConfigLine>()
                for (i in 0 until urlsRaw.length()) {
                    when (val item = urlsRaw.get(i)) {
                        is JSONObject -> {
                            val name = (JsonUtil.optStringOrNull(item, "name") ?: "").trim()
                            var u = (JsonUtil.optStringOrNull(item, "url") ?: "").trim()
                            if (u.isEmpty()) u = (JsonUtil.optStringOrNull(item, "api") ?: "").trim()
                            if (u.isNotEmpty()) {
                                parsed.add(ConfigLine(if (name.isEmpty()) u else name, normalizeUrl(u)))
                            }
                        }
                        is String -> {
                            val t = item.trim()
                            if (t.isNotEmpty()) parsed.add(ConfigLine(t, normalizeUrl(t)))
                        }
                    }
                }
                if (parsed.isEmpty()) throw ConfigException("配置解析失败: urls 线路集合为空")
                lines = parsed
                lineSource = current
                current = parsed.first().url
                body = fetchBody(current)
                if (body == null) {
                    body = EngineClient.getText(current).ifEmpty { null }
                    engineTried.add(current)
                }
                continue
            }
            // 单线包装: {"url": "http://..."}
            val redirect = JsonUtil.optStringOrNull(map, "url") ?: ""
            if (redirect.startsWith("http")) {
                current = redirect
                body = fetchBody(current)
                if (body == null) {
                    body = EngineClient.getText(current).ifEmpty { null }
                    engineTried.add(current)
                }
                continue
            }
            throw ConfigException("配置中没有可用源 (sites 为空)")
        }
        throw ConfigException("配置跳转层级过多, 已终止 (超过 6 层)")
    }

    private suspend fun fetchBody(u: String): String? {
        base = u.trim()
        return Http.getText(u)
    }

    /** 原版 ApiConfig.FindResult: 加密配置解密 (Compose 做 base64 部分, AES 走引擎). */
    private suspend fun decodeConfigText(body: String, pk: String?): String {
        var text = body
        if (text.startsWith('\uFEFF')) text = text.substring(1)
        text = text.trim()
        if (startsWithJson(text)) return text
        // 8 位随机字符 + ** 标记, 之后是 base64
        val m = Regex("[A-Za-z0-9]{8}\\*\\*").find(text)
        if (m != null) {
            val decoded = JsonUtil.tryBase64(text.substring(m.range.last + 1).replace(Regex("\\s"), ""))
            if (decoded != null) text = decoded.trim()
        }
        val t = text.trim()
        // 2423 开头 = AES-CBC; 带 ;pk; 密钥 = AES-ECB
        if (t.startsWith("2423") || (pk != null && !startsWithJson(t))) {
            val dec = EngineClient.decrypt(t, pk ?: "")
            if (dec.isNotEmpty()) text = dec
        }
        return text
    }

    private fun startsWithJson(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("{") || t.startsWith("[")
    }

    /**
     * TVBox 风格的多层解析. org.json 严格解析, 带 // 注释时先剥离再重试
     * (注释行会让整份有效配置被判"内容不是有效的 TVBox 配置").
     */
    fun decodeMap(text: String): JSONObject? {
        val direct = decodeMapOnce(text)
        if (direct != null) return direct
        if (!text.contains("//")) return null
        val stripped = JsonUtil.stripJsonComments(text)
        if (stripped == text) return null
        return decodeMapOnce(stripped)
    }

    private fun decodeMapOnce(text: String): JSONObject? {
        val t = if (text.startsWith("{") || text.startsWith("[")) {
            text
        } else {
            JsonUtil.clean(JsonUtil.extractConfig(text))
        }
        try {
            val obj = JSONObject(t)
            val sites = obj.optJSONArray("sites")
            if (sites != null && sites.length() > 0) return obj
            val urls = obj.optJSONArray("urls")
            if (urls != null && urls.length() > 0) return obj
            val u = JsonUtil.optStringOrNull(obj, "url") ?: ""
            if (u.isNotEmpty()) return JSONObject().put("url", u)
            return null
        } catch (_: Exception) {
            // 不是对象, 尝试数组
        }
        try {
            val arr = JSONArray(t)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val sites = item.optJSONArray("sites")
                if (sites != null && sites.length() > 0) return item
                // Dart: item['spider'] is String && isNotEmpty — JSON null 不算命中.
                val spider = JsonUtil.optStringOrNull(item, "spider") ?: ""
                if (spider.isNotEmpty()) return item
            }
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val u = JsonUtil.optStringOrNull(item, "url") ?: ""
                if (u.isNotEmpty()) return JSONObject().put("url", u)
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun parseSites(raw: JSONArray) {
        val found = mutableListOf<Site>()
        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: continue
            try {
                Site.fromMap(item, base)?.let { found.add(it) }
            } catch (_: Exception) {
            }
        }
        sites.clear()
        sites.addAll(found)
    }

    // ---------------------------------------------------------------- 应用

    /** Load the main jar and push sites to the engine. */
    suspend fun apply(): Boolean {
        var jarOk = true
        if (spiderRaw.isNotEmpty()) jarOk = loadSpiderJar(spiderRaw)
        val realSites = sites.filter { it.isRealSource }.map { it.toJson() }
        if (realSites.isEmpty()) return false
        val loaded = EngineClient.loadSites(realSites)
        // jar 加载失败时, 只要存在不依赖主 jar 的源 (自带 jar 的 csp 或 js), 仍然可用.
        return loaded && (jarOk || realSites.any { !needsMainJar(it) })
    }

    private fun needsMainJar(site: JSONObject): Boolean {
        val api = site.optString("api") ?: ""
        val jar = site.optString("jar") ?: ""
        return jar.isEmpty() && !(api.endsWith(".js") || api.contains(".js?"))
    }

    // ---------------------------------------------------------------- spider jar

    companion object {

        const val SPIDER_JAR_IN_IMG_PREFIX = "img+"

        /** 原版 loadJar 的 img+ 前缀常量: 标记 spider 是"图片里藏 jar". */
        fun spiderJarInImg(raw: String): Boolean = raw.trim().startsWith(SPIDER_JAR_IN_IMG_PREFIX)

        /** 从 spider 字段提取 md5 校验值 ("url;md5;<32hex>"), 没有则返回 null. */
        fun spiderJarMd5(raw: String): String? {
            val idx = raw.indexOf(";md5;")
            if (idx < 0) return null
            val rest = raw.substring(idx + 5).trim()
            if (rest.isEmpty()) return null
            val tok = rest.split(';').first().trim()
            return if (Regex("^[0-9a-fA-F]{32}$").matches(tok)) tok.lowercase() else null
        }

        /**
         * 从 spider 字段提取主 jar 地址.
         * raw 形如 "url;md5;xxx" / "url1;url2" / "img+url;md5;xxx" / "./spider.jar".
         * 注意: 不能按扩展名过滤——现代配置常把 jar 伪装成 .jpg 等后缀
         * (实测 9280.kstore.vip 的 spider 就是 .jpg URL, 实际内容是带
         * classes.dex 的 APK, md5 校验一致), 按 ".jar" 过滤会全部误杀.
         */
        fun spiderJarUrl(raw: String, base: String): String {
            var jarUrl: String? = null
            for (part in raw.split(';')) {
                var t = part.trim()
                if (t.isEmpty()) continue
                if (Regex("^[0-9a-fA-F]{32}$").matches(t)) continue // md5 段
                if (t.lowercase() == "md5") continue // 分隔符段
                if (t.startsWith(SPIDER_JAR_IN_IMG_PREFIX)) t = t.substring(4) // img+
                jarUrl = jarUrl ?: absPath(t, base)
                if (jarUrl!!.startsWith("http")) break
            }
            val url = jarUrl ?: ""
            if (url.isEmpty() || !url.startsWith("http")) return ""
            return url
        }

        private fun absPath(path: String, base: String): String {
            val t = path.trim().replace("\r", "").replace("\n", "")
            if (t.startsWith("http")) return t
            if (base.isEmpty() || !base.startsWith("http")) return t
            return Site.abs(t, base)
        }

        /**
         * 原版 getImgJar: img+ 的 spider 响应是图片, jar 是 EOI 之后
         * "8位随机字符** + base64" 的那一段, 提取出来才是真正的 jar.
         */
        fun extractJarFromImage(body: ByteArray): ByteArray? {
            val text = String(body, Charsets.ISO_8859_1)
            val m = Regex("[A-Za-z0-9]{8}\\*\\*").find(text) ?: return null
            var b64 = text.substring(m.range.last + 1).replace(Regex("\\s"), "")
            // 只保留 base64 字母表 (图片尾巴可能带垃圾字符, 剥掉才能解出完整 dex).
            b64 = b64.replace(Regex("[^A-Za-z0-9+/=]"), "")
            while (b64.length % 4 != 0) b64 += "="
            // 长度下限只排掉短垃圾 (真实 dex/jar 的 base64 远长于此);
            // 内容是否合法由加载前的 PK/dex 魔数预检负责.
            if (b64.length < 12) return null
            return JsonUtil.decodeBase64OrNull(b64)
        }

        /** 有效 jar 文件: zip/apk (PK) 或裸 dex (dex\n), 前 4 字节判定. */
        fun isValidJarFile(file: File): Boolean {
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val head = ByteArray(4)
                    if (raf.read(head) < 4) return false
                    if (head[0] == 0x50.toByte() && head[1] == 0x4b.toByte()) return true // PK (zip/apk)
                    if (head[0] == 0x64.toByte() && head[1] == 0x65.toByte() && head[2] == 0x78.toByte()) {
                        return true // dex
                    }
                    false
                }
            } catch (_: Exception) {
                false
            }
        }

        /** 原版 ApiConfig.configUrl: 缺省补 http://, 保留 ;pk; 密钥供解密使用. */
        fun normalizeUrl(input: String): String {
            var u = input.trim()
            if (u.isEmpty()) return u
            if (u.startsWith("http://") || u.startsWith("https://")) return u
            if (u.contains(";pk;")) {
                val idx = u.indexOf(";pk;")
                val head = normalizeUrl(u.substring(0, idx))
                return "$head;pk;${u.substring(idx + 4).trim()}"
            }
            return "http://$u"
        }

        fun pkKeyOf(input: String): String? {
            val idx = input.indexOf(";pk;")
            if (idx < 0) return null
            val key = input.substring(idx + 4).trim()
            return key.ifEmpty { null }
        }
    }

    // ---------------------------------------------------------------- 主 jar 加载

    /**
     * 最多两轮: 下载 (Compose OkHttp → 引擎 OkHttp 兜底) → 校验, 失败重拉一次
     * (对照原版 loadJar 的 LOAD_JAR_MAX_RETRY=1 retryLoad).
     */
    private suspend fun loadSpiderJar(raw: String): Boolean {
        jarError = ""
        val url = spiderJarUrl(raw, base)
        if (url.isEmpty()) {
            jarError = "spider 字段无可加载的 jar 地址"
            return false
        }
        val expectMd5 = spiderJarMd5(raw)
        val jarInImg = spiderJarInImg(raw)
        try {
            val support = Ctx.filesDir
            val file = File(support, "main_${JsonUtil.shortHash(url)}.jar")
            var ok = false
            var dlErr: String? = null
            // 本地缓存模式: 主 jar 已下载且校验通过就直接复用, 不再联网下载.
            if (fromCache && file.exists() && file.length() > 0L && isValidJarFile(file) &&
                (expectMd5 == null || JsonUtil.md5Hex(file.readBytes()) == expectMd5)
            ) {
                ok = true
            }
            for (attempt in 0 until 2) {
                if (ok) break
                // 无论哪一轮, 写文件前先删旧文件: 上次成功加载后 jar 被引擎侧
                // 置为只读 (Android 14+ DCL 要求), 写在只读文件上会失败.
                if (file.exists()) file.delete()
                try {
                    val bytes = Http.getBytes(url)
                    if (bytes != null && bytes.isNotEmpty()) {
                        val jarBytes = if (jarInImg) extractJarFromImage(bytes) else bytes
                        if (jarBytes == null || jarBytes.isEmpty()) {
                            dlErr = if (jarInImg) "img+ 图片中未找到有效 jar" else "响应体为空"
                        } else {
                            file.writeBytes(jarBytes)
                            ok = true
                        }
                    } else {
                        dlErr = "响应体为空"
                    }
                } catch (e: Exception) {
                    dlErr = e.message ?: "网络错误"
                }
                if (!ok) {
                    // Compose OkHttp 失败 (中文域名/超时等): 走引擎 OkHttp 下载, 它支持 IDN.
                    if (EngineClient.download(url, file.absolutePath)) {
                        ok = true
                        if (jarInImg) {
                            // 引擎下载的是原始图片字节, img+ 场景要提取后再写回.
                            val jarBytes = extractJarFromImage(file.readBytes())
                            if (jarBytes == null || jarBytes.isEmpty()) {
                                ok = false
                                dlErr = "img+ 图片中未找到有效 jar"
                            } else {
                                file.writeBytes(jarBytes)
                            }
                        }
                    }
                }
                // 配置带了 md5 就校验 (原版 loadJar 对缓存做同样校验): 运营商劫持 /
                // CDN 错误页会返回 200 + HTML, 不校验的话错误内容会被当 jar 加载.
                if (ok && expectMd5 != null) {
                    val got = JsonUtil.md5Hex(file.readBytes())
                    if (got != expectMd5) {
                        ok = false
                        dlErr = "md5 校验失败 (期望 $expectMd5, 实际 $got)"
                    }
                }
            }
            if (!ok || !file.exists() || file.length() == 0L) {
                jarError = "jar 下载失败: $url${dlErr?.let { " ($it)" } ?: ""}"
                return false
            }
            // 内容魔数预检: 合法 jar 是 zip (PK) 或裸 dex. 不预检的话 HTML 错误页
            // 会进 DexClassLoader, 报出误导性的"DexClassLoader 失败".
            if (!isValidJarFile(file)) {
                jarError = "jar 内容无效 (非 APK/dex): $url"
                return false
            }
            val loadErr = EngineClient.loadMainJar(file.absolutePath)
            if (loadErr.isNotEmpty()) {
                jarError = "引擎加载 jar 失败: $url ($loadErr)"
                return false
            }
            return true
        } catch (e: Exception) {
            // 典型: 存储目录不可用 / 插件未注册.
            jarError = "jar 加载异常: ${e.message}"
            return false
        }
    }
}

/**
 * 全局配置状态 (对照 Flutter 版 Config.loaded / Config.changeNotifier).
 * 页面通过 [loaded] StateFlow 订阅配置变化 (导入成功 / 清除 / 切线路).
 */
object ConfigStore {

    private val _loaded = MutableStateFlow<Config?>(null)
    val loaded: StateFlow<Config?> = _loaded.asStateFlow()

    /** 配置里的 "danmaku" 字段 (原版 ApiConfig.getDanmaku()). */
    private val _danmakuApi = MutableStateFlow("")
    val danmakuApi: StateFlow<String> = _danmakuApi.asStateFlow()

    private const val PREF_URL = "configUrl"
    private const val PREF_LINES = "configLines"

    /** 可用源 (引擎支持) 套用"站源过滤"后的列表: 首页站源选择 / 搜索 / 换源共用. */
    fun visibleSites(): List<Site> =
        SiteFilter.apply(loaded.value?.sites?.filter { it.isRealSource } ?: emptyList())

    fun markLoaded(config: Config?) {
        _loaded.value = config
    }

    /**
     * 拉取并应用一个配置 (URL 或直接粘贴的 JSON 文本).
     * 设置页"更换配置/导入"与首页"线路"切换共用同一链路, 避免两处逻辑漂移.
     * 返回 (是否成功, 面向用户的消息); 成功后 loaded 已更新.
     */
    suspend fun applyInput(
        input: String,
        keepLines: List<ConfigLine> = emptyList(),
        useCache: Boolean = false,
    ): Pair<Boolean, String> {
        val text = input.trim()
        if (text.isEmpty()) {
            saveUrl("")
            saveLines(emptyList(), "")
            _danmakuApi.value = ""
            markLoaded(null)
            return Pair(false, "配置已清除")
        }
        val config = Config(text)
        return try {
            // 启动自动加载走本地缓存 (开关开启且命中时不联网); 其余情况在线拉取.
            val hitCache = useCache && ConfigCache.enabled.value && config.loadFromCache()
            if (!hitCache) config.fetch()
            if (config.sites.isEmpty()) return Pair(false, "配置中没有可用源")
            if (keepLines.isNotEmpty() && config.lines.isEmpty()) {
                config.lines = keepLines
                config.lineSource = config.url
            }
            val ok = config.apply()
            if (!ok) {
                if (config.jarError.isNotEmpty()) {
                    return Pair(false, "${config.jarError}\ncsp 源不可用")
                }
                return Pair(false, "引擎初始化失败")
            }
            saveUrl(config.url)
            saveLines(config.lines, config.lineSource)
            if (!hitCache) config.saveToCache()
            _danmakuApi.value = config.danmakuApi
            markLoaded(config)
            val count = config.sites.count { it.isRealSource }
            val lineTip = if (config.lines.isNotEmpty()) ", ${config.lines.size} 条线路" else ""
            val jarTip = if (config.jarError.isNotEmpty()) "\n${config.jarError} (已跳过)" else ""
            val cacheTip = if (hitCache) " (本地缓存)" else ""
            Pair(true, "成功, 已应用 $count 个源$lineTip$cacheTip$jarTip")
        } catch (e: ConfigException) {
            Pair(false, e.message ?: "载入失败")
        } catch (e: Exception) {
            Pair(false, "载入失败: ${e.message}")
        }
    }

    fun loadUrl(): String = Ctx.prefs.getString(PREF_URL, "") ?: ""

    fun saveUrl(url: String) {
        Ctx.prefs.edit().putString(PREF_URL, url).apply()
    }

    /** 恢复上次保存的线路列表 (原版 HawkConfig.API_LINE_LIST 的等价物). */
    fun loadLines(): List<ConfigLine> {
        val raw = Ctx.prefs.getString(PREF_LINES, null)
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val map = JSONObject(raw)
            val arr = map.optJSONArray("lines") ?: return emptyList()
            val out = mutableListOf<ConfigLine>()
            for (i in 0 until arr.length()) {
                ConfigLine.from(arr.get(i))?.let { out.add(it) }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveLines(lines: List<ConfigLine>, source: String) {
        if (lines.isEmpty() || source.isEmpty()) {
            Ctx.prefs.edit().remove(PREF_LINES).apply()
            return
        }
        val arr = JSONArray()
        lines.forEach { arr.put(it.toJson()) }
        val map = JSONObject().apply {
            put("source", source)
            put("lines", arr)
        }
        Ctx.prefs.edit().putString(PREF_LINES, map.toString()).apply()
    }
}

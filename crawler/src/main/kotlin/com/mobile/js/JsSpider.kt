package com.mobile.js

import android.content.Context
import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.github.catvod.crawler.Spider
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * JS 爬虫 (drpy) — 基于 dokar3/quickjs-kt 的移植版.
 *
 * 与原 com.whl.quickjs wrapper 版的关键差异:
 *   1. 运行时: QuickJs.create(jobDispatcher), so 由依赖自带 (四架构), 不再手动 QuickJSLoader.init.
 *   2. 模块加载: alpha13 无动态 module loader, evaluate 前必须把 import 的模块
 *      预先 addModule 注册 (registerModuleTree 递归扫描 import, 对齐 QuickJS
 *      native 只解析 "./"/"../" 相对名的规则); cheerio/crypto-js 仍做字节码缓存,
 *      缓存 key 按模块规范化名 Md5 (compile 的 filename 必须与 import 名一致).
 *   3. 调用: JS 函数值不能跨 JNI, spider 方法统一经
 *      Promise.resolve(globalThis.key.func(...__args())).then(__result/__error)
 *      桥接; evaluate 会等 promise 队列跑完再返回 (awaitAsyncJobs).
 *   4. 线程模型: evaluate 在 Spider 框架调用的同步线程上跑 JS (runBlocking),
 *      asyncFunction 绑定 (_httpAsync/__timer) 在独立的 jobExecutor 上跑协程,
 *      两个线程必须分开, 否则互相等待死锁.
 *   5. dex/jsapi 绑定已移除: 该路径的 jar 类以 QuickJSContext 为构造参数,
 *      引擎替换后无法实例化, 属于已知的 jar 兼容性破坏点.
 */
class JsSpider(rawKey: String, val api: String, val dex: Class<*>?) : Spider() {

    private val key: String = "J" + Md5.encode(rawKey)
    // JS 执行线程: 序列化所有 evaluate 调用 (QuickJS 运行时非并发安全).
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    // 协程 job dispatcher: asyncFunction 绑定在这条线程上执行.
    private val jobExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var rt: QuickJs? = null
    private var cat = false
    private val destroyed = AtomicBoolean(false)
    // 调用参数/结果槽: __args/__result 绑定跨线程读写, 用原子引用.
    // 参数用 List (quickjs-kt 只对 List 做数组映射, Kotlin Array 不保证).
    private val argsSlot = AtomicReference<List<Any?>>(emptyList())
    private val resultSlot = AtomicReference<Any?>(null)

    init {
        try {
            initializeJS()
        } catch (e: Throwable) {
            Loge.e("echo-JsSpider-init " + e.message)
        }
    }

    override fun cancelByTag() {
        Connect.cancelByTag("js_okhttp_tag")
    }

    private fun <T> submit(callable: Callable<T>): java.util.concurrent.Future<T> {
        return executor.submit(callable)
    }

    /** 在 JS 执行线程上同步调用 spider 方法, 结果经 __result 槽取回. */
    private fun callInline(runtime: QuickJs, func: String, vararg args: Any?): Any? {
        return runBlocking {
            argsSlot.set(args.toList())
            resultSlot.set(null)
            runtime.evaluate<Any?>(
                "Promise.resolve(globalThis.$key.$func(...__args()))" +
                        ".then(r => __result(r), e => __error(e)); void 0;",
                filename = "spider_call.js"
            )
            resultSlot.get()
        }
    }

    /** 框架线程入口: 提交到 JS 执行线程执行 (不可在 JS 执行线程上调用, 会自等死锁). */
    private fun call(func: String, vararg args: Any?): Any? {
        if (destroyed.get()) return null
        val runtime = rt ?: return null
        return try {
            submit(Callable { callInline(runtime, func, *args) }).get()
        } catch (e: Throwable) {
            Loge.i("echo-call-error " + e.message)
            null
        }
    }

    private fun cfg(ext: String?): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["stype"] = 3
        map["skey"] = key
        map["ext"] = if (Json.invalid(ext)) ext else JsJson.parse(ext)
        return map
    }

    override fun init(context: Context?, extend: String?) {
        mContext = context
        try {
            if (cat) call("init", cfg(extend))
            else call("init", if (Json.valid(extend)) JsJson.parse(extend) else extend)
        } catch (ignored: Exception) {
        }
    }

    override fun homeContent(filter: Boolean): String {
        return try {
            (call("home", filter) as? String) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    override fun homeVideoContent(): String {
        return try {
            (call("homeVod") as? String) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    override fun categoryContent(tid: String?, pg: String?, filter: Boolean, extend: HashMap<String, String>?): String {
        return try {
            (call("category", tid, pg, filter, extend ?: emptyMap<String, String>()) as? String) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    override fun detailContent(ids: List<String>?): String {
        return try {
            (call("detail", if (ids.isNullOrEmpty()) "" else ids[0]) as? String) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    override fun searchContent(key: String?, quick: Boolean): String {
        return try {
            (call("search", key, quick) as? String) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    override fun playerContent(flag: String?, id: String?, vipFlags: List<String>?): String {
        return try {
            (call("play", flag, id, vipFlags ?: emptyList<String>()) as? String) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    override fun liveContent(url: String?): String {
        return try {
            (call("live", url) as? String) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    override fun manualVideoCheck(): Boolean {
        return try {
            (call("sniffer") as? Boolean) ?: false
        } catch (e: Exception) {
            false
        }
    }

    override fun isVideoFormat(url: String?): Boolean {
        return try {
            (call("isVideo", url) as? Boolean) ?: false
        } catch (e: Exception) {
            false
        }
    }

    override fun proxyLocal(params: Map<String, String>): Array<Any?>? {
        return try {
            if ("catvod" == params["from"]) proxy2(params)
            else submit(Callable { proxy1(params) }).get()
        } catch (e: Throwable) {
            emptyProxy()
        }
    }

    override fun action(action: String?): String? {
        return try {
            call("action", action) as? String
        } catch (e: Exception) {
            null
        }
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        try {
            executor.submit {
                try {
                    rt?.close()
                    rt = null
                } catch (th: Throwable) {
                    Loge.i("echo-js-destroy-error " + th.message)
                } finally {
                    jobExecutor.shutdown()
                    executor.shutdown()
                }
            }
        } catch (th: Throwable) {
            executor.shutdownNow()
            jobExecutor.shutdownNow()
        }
    }

    // ---------------------------------------------------------------- 初始化

    private fun initializeJS() {
        try {
            submit(Callable {
                runBlocking {
                    try {
                        initializeRuntime()
                    } catch (th: Throwable) {
                        Loge.e("echo-initializeJS-error " + th.message)
                    }
                }
                null
            }).get()
        } catch (th: Throwable) {
            Loge.e("echo-initializeJS-wait " + th.message)
        }
    }

    private suspend fun initializeRuntime() {
        val runtime = QuickJs.create(jobExecutor.asCoroutineDispatcher())
        rt = runtime
        try {
            registerBindings(runtime)
            evalGlobalScripts(runtime)
            preloadTemplate(runtime)
            loadSpider(runtime)
        } catch (th: Throwable) {
            Loge.e("echo-initializeJS-setup " + th.message)
        }
    }

    private fun registerBindings(runtime: QuickJs) {
        Global.register(runtime)
        runtime.function("__args") { argsSlot.get() }
        runtime.function("__result") { args -> resultSlot.set(args.getOrNull(0)) }
        runtime.function("__error") { args ->
            Loge.i("echo-js-call-error " + JsJson.stringify(args.getOrNull(0)))
        }
    }

    /** 定时器垫片 + net.js: JS 函数值无法跨 JNI, 定时器用 promise 链在事件循环里调度. */
    private suspend fun evalGlobalScripts(runtime: QuickJs) {
        runtime.evaluate<Any?>(
            "globalThis.setTimeout = (fn, ms) => __timer(ms || 0).then(() => { try { if (fn) fn() } catch (e) { console.error(e) } });\n" +
                    "globalThis.setInterval = globalThis.setTimeout;\n" +
                    "globalThis.clearTimeout = () => undefined;\n" +
                    "globalThis.clearInterval = () => undefined;\n" +
                    "void 0;"
        )
        val net = JSRuntime.getAsOpen("js/lib/net.js")
        if (net.isNotEmpty()) runtime.evaluate<Any?>(net, filename = "net.js")
    }

    private suspend fun preloadTemplate(runtime: QuickJs) {
        try {
            val tpl = JSRuntime.loadModule("模板.js")
            if (tpl != null && !isInvalidModuleContent(tpl)) {
                registerModuleTree(runtime, "模板.js", tpl, HashSet())
            }
            runtime.evaluate<Any?>(
                "import tpl from '模板.js';\n" +
                        "globalThis.muban = tpl.muban;\n" +
                        "globalThis.getMubans = tpl.getMubans;",
                filename = "tv_box_template.js",
                asModule = true
            )
        } catch (th: Throwable) {
            Loge.i("echo-preloadTemplate-error " + th.message)
        }
    }

    private suspend fun loadSpider(runtime: QuickJs) {
        val loaded = JSRuntime.loadModule(api)
        if (loaded == null || isInvalidModuleContent(loaded)) {
            Loge.i("echo-loadSpider-invalid " + api)
            return
        }
        val content = loaded
        if (content.startsWith("//bb")) {
            cat = true
            // 打包字节码: 无法扫描 import, 模块名以打包时 filename 为准, 逐个候选名试.
            val bytecode = byteFF(Base64.decode(content.replace("//bb", ""), Base64.DEFAULT))
            runtime.addModule(bytecode)
            evalSpiderBridge(runtime, listOf(key + ".js", api))
        } else {
            var src = content
            if (src.contains("__JS_SPIDER__")) {
                src = src.replace("__JS_SPIDER__\\s*=".toRegex(), "export default ")
            }
            if (src.contains("__jsEvalReturn") && !src.contains("export default")) {
                cat = true
            }
            registerModuleTree(runtime, api, src, HashSet())
            evalSpiderBridge(runtime, listOf(api))
        }
    }

    /** SPIDER_STRING_CODE 导入 spider 模块并把实例挂到 globalThis.key. */
    private suspend fun evalSpiderBridge(runtime: QuickJs, candidates: List<String>) {
        var lastError: Throwable? = null
        for (cand in candidates) {
            try {
                runtime.evaluate<Any?>(
                    String.format(SPIDER_STRING_CODE, cand) + "globalThis." + key + " = globalThis.__JS_SPIDER__;",
                    filename = "tv_box_root.js",
                    asModule = true
                )
                return
            } catch (th: Throwable) {
                lastError = th
                Loge.i("echo-spiderBridge-retry " + cand + ", msg=" + th.message)
            }
        }
        throw lastError ?: IllegalStateException("spider bridge failed: $api")
    }

    // ---------------------------------------------------------------- 模块注册

    /**
     * alpha13 无动态 module loader: 递归扫描 import, 把依赖模块在 evaluate 前
     * 全部 addModule 注册. 规范化对齐 QuickJS native 规则: 只有 "./"/"../"
     * 开头的相对名才做解析, 其余保持原样.
     */
    private suspend fun registerModuleTree(runtime: QuickJs, name: String, content: String, visited: HashSet<String>) {
        if (!visited.add(name)) return
        if (content.startsWith("//DRPY") || content.startsWith("//bb")) {
            val bytecode = if (content.startsWith("//DRPY")) {
                Base64.decode(content.replace("//DRPY", ""), Base64.URL_SAFE)
            } else {
                byteFF(Base64.decode(content.replace("//bb", ""), Base64.DEFAULT))
            }
            try {
                runtime.addModule(bytecode)
            } catch (th: Throwable) {
                Loge.e("echo-addModuleBytecode-error " + name + ", msg=" + th.message)
            }
            return
        }
        val cacheKey = moduleCacheKey(name)
        if (cacheKey != null) {
            val cached = JSRuntime.getCacheByte(cacheKey)
            if (cached.isNotEmpty()) {
                try {
                    runtime.addModule(cached)
                    return
                } catch (ignored: Throwable) {
                    // 缓存字节码损坏/模块名不匹配, 退回源码编译.
                }
            }
        }
        try {
            runtime.addModule(name, content)
        } catch (th: Throwable) {
            Loge.e("echo-addModule-error " + name + ", msg=" + th.message)
            try {
                runtime.addModule(name, EMPTY_MODULE_CODE)
            } catch (ignored: Throwable) {
            }
            return
        }
        if (cacheKey != null) {
            try {
                val bytecode = runtime.compile(content, filename = name, asModule = true)
                JSRuntime.setCacheByte(cacheKey, bytecode)
            } catch (ignored: Throwable) {
            }
        }
        for (spec in scanImports(content)) {
            val child = normalizeModule(name, spec)
            val childContent = JSRuntime.loadModule(child)
            if (childContent == null || isInvalidModuleContent(childContent)) {
                Loge.i("echo-module-empty " + child)
                try {
                    runtime.addModule(child, EMPTY_MODULE_CODE)
                } catch (ignored: Throwable) {
                }
                continue
            }
            registerModuleTree(runtime, child, childContent, visited)
        }
    }

    /** QuickJS native js_module_normalize_name 同款规则: 仅相对名解析. */
    private fun normalizeModule(base: String, spec: String): String {
        if (!spec.startsWith("./") && !spec.startsWith("../")) return spec
        return UriUtil.resolve(base, spec)
    }

    /** cheerio/crypto-js 按规范化名 Md5 做字节码缓存 (compile filename 必须与 import 名一致). */
    private fun moduleCacheKey(name: String): String? = when {
        name.contains("cheerio.min.js") -> "cheerio_" + Md5.encode(name)
        name.contains("crypto-js.js") -> "cryptojs_" + Md5.encode(name)
        else -> null
    }

    private val IMPORT_REGEX = Regex(
        "(?:^|[\\r\\n;])\\s*(?:import|export)\\s+(?:[\\w$*{},\\s]+?\\s+from\\s+)?[\"']([^\"'\\n]+)[\"']"
    )
    private val DYNAMIC_IMPORT_REGEX = Regex("import\\s*\\(\\s*[\"']([^\"'\\n]+)[\"']\\s*\\)")

    private fun scanImports(code: String): List<String> {
        val statics = IMPORT_REGEX.findAll(code).map { it.groupValues[1] }
        val dynamics = DYNAMIC_IMPORT_REGEX.findAll(code).map { it.groupValues[1] }
        return (statics + dynamics).distinct().toList()
    }

    private fun isInvalidModuleContent(content: String?): Boolean {
        if (content == null || content.isEmpty()) return true
        var trim = content.trim()
        if (trim.startsWith("\uFEFF")) trim = trim.substring(1).trim()
        val lower = trim.lowercase()
        return lower.startsWith("<") ||
                lower.startsWith("{\"code\":404") ||
                lower.startsWith("404") ||
                lower.startsWith("not found")
    }

    fun byteFF(bytes: ByteArray): ByteArray {
        val newBt = ByteArray(bytes.size - 4)
        newBt[0] = 1
        System.arraycopy(bytes, 5, newBt, 1, bytes.size - 5)
        return newBt
    }

    // ---------------------------------------------------------------- 代理

    /** catvod js 代理: spider.proxy(params) 返回数组 [code, type, content, header, opt]. */
    private fun proxy1(params: Map<String, String>): Array<Any?>? {
        val runtime = rt ?: return null
        val result = callInline(runtime, "proxy", params) as? List<*> ?: return null
        val headerAvailable = result.size > 3 && result[3] != null
        val out = arrayOfNulls<Any>(4)
        out[0] = (result.getOrNull(0) as? Number)?.toInt() ?: 200
        out[1] = result.getOrNull(1)
        out[2] = getStream(result.getOrNull(2))
        if (headerAvailable) out[3] = getHeader(result[3])
        if (result.size > 4) {
            try {
                if ((result.getOrNull(4) as? Number)?.toInt() == 1) {
                    val raw = result.getOrNull(2)
                    var content = when (raw) {
                        is String -> raw
                        is Map<*, *> -> JsJson.stringify(raw)
                        else -> raw?.toString() ?: ""
                    }
                    if (content.contains("base64,")) content = content.substring(content.indexOf("base64,") + 7)
                    out[2] = ByteArrayInputStream(Base64.decode(content, Base64.DEFAULT))
                }
            } catch (ignored: Exception) {
            }
        }
        return out
    }

    private fun getStream(source: Any?): Any? {
        return when (source) {
            is InputStream -> source
            is ByteArray -> ByteArrayInputStream(source)
            is UByteArray -> ByteArrayInputStream(source.toByteArray())
            is List<*> -> ByteArrayInputStream(source.map { (it as? Number)?.toByte() ?: 0.toByte() }.toByteArray())
            is String -> ByteArrayInputStream(source.toByteArray(Charsets.UTF_8))
            else -> source
        }
    }

    private fun getHeader(headerRaw: Any?): Map<String, String>? {
        val headers = HashMap<String, String>()
        if (headerRaw is Map<*, *>) {
            for ((k, v) in headerRaw) {
                headers[k.toString()] = v.toString()
            }
        } else if (headerRaw is JSONObject) {
            val json = headerRaw
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                headers[k] = json.optString(k)
            }
        } else if (headerRaw is String) {
            try {
                val json = JSONObject(headerRaw)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    headers[k] = json.optString(k)
                }
            } catch (ignored: org.json.JSONException) {
            }
        }
        return headers
    }

    /** drpy js 代理: spider.proxy(urlSegs, header) 返回 JSON 字符串. */
    private fun proxy2(params: Map<String, String>): Array<Any?>? {
        val url = params["url"] ?: return null
        val header = params["header"] ?: ""
        val json = (call("proxy", url.split("/"), JsJson.parse(header)) as? String) ?: return null
        val res = Res.objectFrom(json)
        var contentType = res.getContentType()
        if (contentType.isEmpty()) contentType = "application/octet-stream"
        val result = arrayOfNulls<Any>(3)
        result[0] = 200
        result[1] = contentType
        if (res.getBuffer() == 2) {
            result[2] = ByteArrayInputStream(Base64.decode(res.getContent(), Base64.DEFAULT))
        } else {
            result[2] = ByteArrayInputStream(res.getContent().toByteArray())
        }
        return result
    }

    private fun emptyProxy(): Array<Any?> {
        return arrayOfNulls(0)
    }

    companion object {
        private const val EMPTY_MODULE_CODE =
            "const empty = null;\n" +
                    "export default empty;\n" +
                    "export const JSEncrypt = empty;\n" +
                    "export const NodeRSA = empty;\n" +
                    "export const pako = empty;\n" +
                    "export const JSON5 = empty;\n" +
                    "export const mb = empty;\n" +
                    "export const parse = empty;\n" +
                    "export const stringify = empty;\n" +
                    "export const inflate = empty;\n" +
                    "export const deflate = empty;\n" +
                    "export const gzip = empty;\n" +
                    "export const ungzip = empty;\n" +
                    "export const encrypt = empty;\n" +
                    "export const decrypt = empty;"

        private const val SPIDER_STRING_CODE =
            "import * as spider from '%s'\n\n" +
                    "if (!globalThis.__JS_SPIDER__) {\n" +
                    "    if (spider.__jsEvalReturn) {\n" +
                    "        globalThis.req = http\n" +
                    "        globalThis.__JS_SPIDER__ = spider.__jsEvalReturn()\n" +
                    "        globalThis.__JS_SPIDER__.is_cat = true\n" +
                    "    } else if (spider.default) {\n" +
                    "        globalThis.__JS_SPIDER__ = typeof spider.default === 'function' ? spider.default() : spider.default\n" +
                    "    }\n" +
                    "}\n"
    }
}

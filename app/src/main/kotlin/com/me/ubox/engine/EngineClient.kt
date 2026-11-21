package com.me.ubox.engine

import com.github.catvod.crawler.SpiderLoader
import com.github.catvod.net.CallTracker
import com.github.catvod.net.OkHttp
import com.github.catvod.server.LocalServer
import com.mobile.js.App
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 引擎客户端: Flutter 版 `ubox/engine` MethodChannel 的 Kotlin 直连替代.
 *
 * 语义与原 EngineChannel (Dart) 完全一致, 去掉序列化开销:
 *   - loadSites  登记站点 (异步, 登记完成才返回, 保证随后的 home/search 看得到站点表)
 *   - loadMainJar 同步加载主 jar, 返回空串 = 成功, 非空 = 可读失败原因
 *   - invoke      spider 调用 (home/homeVod/category/detail/search/player/live/action)
 *   - setPlaySource 进入/退出播放页声明当前播放源 (jar 源 do=ck 探测回退定位)
 *   - proxyUrl   本地代理基址 `http://127.0.0.1:9978/proxy?do=js`
 *   - getText    OkHttp 拉文本 (标准 UA + charset 解码, Dart/Compose 侧被反爬时兜底)
 *   - decrypt    原版 FindResult 的配置解密
 *   - download   OkHttp 下载二进制 (jar), 支持 IDN 中文域名
 *
 * 线程模型 (对照原 SpiderEngine 的固定线程池, 但搜索与其他调用分池):
 *   - [runner]       home/category/detail/player 等交互类调用, 6 线程;
 *   - [searchRunner] 搜索专用, 12 线程. 搜索会并行打几十个源且每个源可能阻塞很久,
 *                    若与详情/播放解析共用一个池, 点进详情会排在整队搜索任务后面 (转圈很久).
 *   QuickJS/JNI 回调与 DexClassLoader 不宜丢进 IO 的弹性池, 所以仍用固定线程池.
 */
object EngineClient {

    /** 交互类调用线程数 (详情/播放解析/分类/首页). */
    private const val RUNNER_THREADS = 6

    /** 搜索并发数: 搜索是 IO 等待为主, 适当调大可明显缩短多源搜索总耗时. */
    private const val SEARCH_THREADS = 12

    private val runner: CoroutineDispatcher =
        Executors.newFixedThreadPool(RUNNER_THREADS).asCoroutineDispatcher()

    // 线程名必须以 CallTracker.THREAD_PREFIX 开头: 取消搜索时靠它找到并 cancel 这些线程上的 HTTP 调用.
    private val searchRunner: CoroutineDispatcher = run {
        val index = AtomicInteger()
        Executors.newFixedThreadPool(SEARCH_THREADS) { r ->
            Thread(r, "${CallTracker.THREAD_PREFIX}-${index.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /**
     * 登记站点. [sites] 为 TVBox sites 条目 (key/type/api/ext/jar).
     * 返回是否成功.
     */
    suspend fun loadSites(sites: List<JSONObject>): Boolean = withContext(runner) {
        try {
            SpiderLoader.setConfig(sites)
            true
        } catch (th: Throwable) {
            false
        }
    }

    /**
     * 加载主 spider jar. 返回空串表示成功, 非空为失败原因 (透传给用户).
     * 同步加载: 调用方先 await 本方法再 loadSites, 异步会让 setConfig 抢在
     * 主 jar 加载前跑, csp 源拿不到 classloader.
     */
    fun loadMainJar(jarPath: String): String {
        return try {
            val ok = SpiderLoader.loadJar(jarPath)
            if (ok) "" else SpiderLoader.jarError()
        } catch (th: Throwable) {
            "loadMainJar 异常: ${th.javaClass.simpleName}: ${th.message}"
        }
    }

    /** spider 调用, 返回 JSON 字符串 (空串 = 失败). */
    suspend fun invoke(siteKey: String, method: String, arg: String, search: Boolean = false): String =
        withContext(if (search) searchRunner else runner) {
            try {
                SpiderLoader.invoke(siteKey, method, arg)
            } catch (_: Throwable) {
                ""
            }
        }

    /**
     * 进入/退出播放页时声明当前播放源 (对照原版 setCurrentPlaySourceKey).
     * 传空串 = 退出播放页.
     */
    fun setPlaySource(siteKey: String) {
        SpiderLoader.setCurrentPlaySourceKey(siteKey)
    }

    /** 本地代理基址, 始终 `.../proxy?do=js`. */
    fun proxyUrl(local: Boolean = true): String {
        // 播放链路入口兜底: Application 阶段启动失败/进程重启后在此自愈
        LocalServer.ensureStarted()
        return LocalServer.getAddress(local) + "proxy?do=js"
    }

    fun getIp(): String = App.getIp()

    /** 引擎侧 OkHttp 拉取文本 (标准 UA + content-type charset 解码). */
    suspend fun getText(url: String): String = withContext(runner) {
        try {
            OkHttp.newCall(url, null).execute().use { it.body?.string() ?: "" }
        } catch (_: Throwable) {
            ""
        }
    }

    /** 原版 ApiConfig.FindResult 的加密配置解密. 解密失败原样返回. */
    suspend fun decrypt(text: String, key: String?): String = withContext(runner) {
        try {
            ConfigCrypto.findResult(text, key?.ifEmpty { null })
        } catch (_: Throwable) {
            text
        }
    }

    /** 引擎侧 OkHttp 下载二进制 (jar), 支持 IDN 中文域名. */
    suspend fun download(url: String, path: String): Boolean = withContext(runner) {
        try {
            OkHttp.download(url, File(path))
        } catch (_: Throwable) {
            false
        }
    }

    fun stopAll() {
        SpiderLoader.cancelAll()
    }

    /**
     * 立即中断进行中的搜索网络请求 (进入详情/播放页时调用), 让被占住的线程释放:
     *   - js 源: 取消带 js_okhttp_tag 的请求 (SpiderLoader.cancelAll);
     *   - jar 源: 取消搜索线程上登记的 HTTP 调用 (CallTracker).
     * 需在取消搜索协程之后调用; 不影响搜索线程之外的请求 (播放/图片/详情).
     */
    fun cancelSearch() {
        SpiderLoader.cancelAll()
        CallTracker.cancelTracked()
    }

    fun unload() {
        SpiderLoader.unloadAll()
    }
}

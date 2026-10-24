package com.novabox.engine

import com.github.catvod.crawler.SpiderLoader
import com.github.catvod.net.OkHttp
import com.github.catvod.server.LocalServer
import com.mobile.js.App
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 引擎客户端: Flutter 版 `novabox/engine` MethodChannel 的 Kotlin 直连替代.
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
 * 线程模型对照原 SpiderEngine: 固定 4 线程池 (QuickJS/JNI 回调与 DexClassLoader
 * 不宜丢进 IO 的弹性池).
 */
object EngineClient {

    private val runner: CoroutineDispatcher =
        Executors.newFixedThreadPool(4).asCoroutineDispatcher()

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
    suspend fun invoke(siteKey: String, method: String, arg: String): String =
        withContext(runner) {
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

    fun unload() {
        SpiderLoader.unloadAll()
    }
}

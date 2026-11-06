package com.mobile.js

import android.net.Uri
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.github.catvod.server.LocalServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * JS 全局绑定层 (原 @Function 注解 + JSObject.bind 方案的 quickjs-kt 移植).
 *
 * quickjs-kt 的 JS 函数值无法跨 JNI 回调 (JsFunction 只是元数据), 所以:
 *   - 原回调式 http (complete 回调) 改为 _httpAsync asyncFunction 绑定,
 *     JS 侧由 net.js 直接拿到 Promise;
 *   - setTimeout/setInterval 改为 __timer + JS 垫片 (见 JsSpider 的 shim),
 *     通过 promise 链在 evaluate 的事件循环里调度.
 */
object Global {

    fun register(rt: QuickJs) {
        rt.function("getProxy") { args -> getProxy(args.getOrNull(0)) }
        rt.function("js2Proxy") { args -> js2Proxy(args) }
        rt.function("joinUrl") { args ->
            HtmlParser.joinUrl(args.getOrNull(0) as? String, args.getOrNull(1) as? String)
        }
        rt.function("pd") { args ->
            HtmlParser.parseDomForUrl(
                args.getOrNull(0) as? String,
                args.getOrNull(1) as? String,
                args.getOrNull(2) as? String ?: ""
            )
        }
        rt.function("pdfh") { args ->
            HtmlParser.parseDomForUrl(args.getOrNull(0) as? String, args.getOrNull(1) as? String, "")
        }
        rt.function("pdfa") { args ->
            HtmlParser.parseDomForArray(args.getOrNull(0) as? String, args.getOrNull(1) as? String)
        }
        rt.function("pdfla") { args ->
            HtmlParser.parseDomForList(
                args.getOrNull(0) as? String,
                args.getOrNull(1) as? String,
                args.getOrNull(2) as? String,
                args.getOrNull(3) as? String,
                args.getOrNull(4) as? String ?: ""
            )
        }
        rt.function("s2t") { args ->
            try {
                Trans.s2t(false, args.getOrNull(0) as? String)
            } catch (e: Exception) {
                ""
            }
        }
        rt.function("t2s") { args ->
            try {
                Trans.t2s(false, args.getOrNull(0) as? String)
            } catch (e: Exception) {
                ""
            }
        }
        rt.function("aesX") { args ->
            Crypto.aes(
                args.getOrNull(0) as? String ?: "AES/CBC",
                (args.getOrNull(1) as? Boolean) ?: true,
                args.getOrNull(2) as? String ?: "",
                (args.getOrNull(3) as? Boolean) ?: false,
                args.getOrNull(4) as? String ?: "0123456789abcdef",
                args.getOrNull(5) as? String,
                (args.getOrNull(6) as? Boolean) ?: true
            )
        }
        rt.function("rsaX") { args ->
            Crypto.rsa(
                (args.getOrNull(1) as? Boolean) ?: false,
                (args.getOrNull(2) as? Boolean) ?: false,
                args.getOrNull(3) as? String ?: "",
                (args.getOrNull(4) as? Boolean) ?: false,
                args.getOrNull(5) as? String ?: "",
                (args.getOrNull(6) as? Boolean) ?: true
            )
        }
        rt.function("rsaEncrypt") { args -> rsaEncrypt(args) }
        rt.function("rsaDecrypt") { args -> rsaDecrypt(args) }
        rt.function("_http") { args -> http(args) }
        rt.asyncFunction("_httpAsync") { args -> withContext(Dispatchers.IO) { http(args) } }
        rt.asyncFunction("__timer") { args ->
            delay((args.getOrNull(0) as? Number)?.toLong() ?: 0L)
        }
        rt.define("local") {
            function("get") { args ->
                local.get(args.getOrNull(0) as? String, args.getOrNull(1) as? String)
            }
            function("set") { args ->
                local.set(
                    args.getOrNull(0) as? String,
                    args.getOrNull(1) as? String,
                    args.getOrNull(2) as? String
                )
                null
            }
            function("delete") { args ->
                local.delete(args.getOrNull(0) as? String, args.getOrNull(1) as? String)
                null
            }
        }
        rt.define("console") {
            function("log") { args -> Loge.i("QuJs" + fmt(args)) }
            function("info") { args -> Loge.i("QuJs" + fmt(args)) }
            function("warn") { args -> Loge.i("QuJs" + fmt(args)) }
            function("error") { args -> Loge.i("QuJs" + fmt(args)) }
        }
    }

    private fun getProxy(localArg: Any?): String {
        val l = localArg is Boolean && localArg
        return LocalServer.getAddress(l) + "proxy?do=js"
    }

    private fun js2Proxy(args: Array<Any?>): String {
        val dynamic = args.getOrNull(0) as? Boolean
        val siteType = (args.getOrNull(1) as? Number)?.toInt() ?: 0
        val siteKey = args.getOrNull(2) as? String ?: ""
        val url = args.getOrNull(3) as? String ?: ""
        val headers = args.getOrNull(4) as? Map<*, *>
        val local = dynamic == null || !dynamic
        val headerJson = try {
            if (headers != null) JsJson.stringify(headers) else ""
        } catch (e: Exception) {
            ""
        }
        return getProxy(local) + "&from=catvod" + "&siteType=" + siteType + "&siteKey=" + siteKey +
                "&header=" + Uri.encode(headerJson) + "&url=" + Uri.encode(url)
    }

    private fun rsaEncrypt(args: Array<Any?>): String {
        val data = args.getOrNull(0) as? String ?: return ""
        val key = args.getOrNull(1) as? String ?: return ""
        val op = args.getOrNull(2) as? Map<*, *>
        var mLong = 1
        var mType = 1
        var mBlock = true
        var mConfig: String? = null
        if (op != null) {
            mConfig = op["config"]?.toString()
            (op["type"] as? Number)?.let { mType = it.toInt() }
            (op["long"] as? Number)?.let { mLong = it.toInt() }
            (op["block"] as? Boolean)?.let { mBlock = it }
        }
        return try {
            when (mType) {
                1 -> {
                    if (mConfig != null) Rsa.encryptByPublicKey(data, key, mConfig, mLong, mBlock)
                    else Rsa.encryptByPublicKey(data, key, mLong, mBlock)
                }
                2 -> {
                    if (mConfig != null) Rsa.encryptByPrivateKey(data, key, mConfig, mLong, mBlock)
                    else Rsa.encryptByPrivateKey(data, key, mLong, mBlock)
                }
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun rsaDecrypt(args: Array<Any?>): String {
        val data = args.getOrNull(0) as? String ?: return ""
        val key = args.getOrNull(1) as? String ?: return ""
        val op = args.getOrNull(2) as? Map<*, *>
        var mLong = 1
        var mType = 1
        var mBlock = true
        var mConfig: String? = null
        if (op != null) {
            mConfig = op["config"]?.toString()
            (op["type"] as? Number)?.let { mType = it.toInt() }
            (op["long"] as? Number)?.let { mLong = it.toInt() }
            (op["block"] as? Boolean)?.let { mBlock = it }
        }
        return try {
            when (mType) {
                1 -> {
                    if (mConfig != null) Rsa.decryptByPrivateKey(data, key, mConfig, mLong, mBlock)
                    else Rsa.decryptByPrivateKey(data, key, mLong, mBlock)
                }
                2 -> {
                    if (mConfig != null) Rsa.decryptByPublicKey(data, key, mConfig, mLong, mBlock)
                    else Rsa.decryptByPublicKey(data, key, mLong, mBlock)
                }
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 同步 http (net.js 的 req 走这里). 原回调式 complete 分支已由
     * _httpAsync + net.js 承接, 这里不再处理 complete.
     */
    private fun http(args: Array<Any?>): Map<String, Any?> {
        val url = args.getOrNull(0) as? String
        if (url.isNullOrEmpty()) return Connect.error()
        val options = args.getOrNull(1) as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val request = Req.objectFrom(JsJson.stringify(options))
        return try {
            val res = Connect.to(url, request).execute()
            try {
                Connect.success(request, res)
            } finally {
                res.close()
            }
        } catch (e: Exception) {
            Connect.error()
        }
    }

    private fun fmt(args: Array<Any?>): String {
        return args.joinToString(" ") { a ->
            when (a) {
                null -> "undefined"
                is Map<*, *>, is List<*> -> JsJson.stringify(a)
                else -> a.toString()
            }
        }
    }
}

package com.me.ubox.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Compose 侧 HTTP 小助手 (对照 Flutter 版 dart:http 的用法).
 *
 * - 默认 UA `okhttp/4.12.0` (豆瓣等对默认 UA 返回 418, 需桌面 UA, 见 [DoubanHot]);
 * - 手动兜底 gzip/zlib 魔数解压: OkHttp 已自动解 gzip, 但中间代理可能原样透传,
 *   与 Dart 版 `_decodeBody` 行为保持一致;
 * - 网络失败统一返回 null (调用方决定是否走引擎 OkHttp 兜底).
 */
object Http {

    const val DEFAULT_UA = "okhttp/4.12.0"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** GET 文本. 失败/非 2xx/空 body 返回 null. */
    suspend fun getText(
        url: String,
        ua: String = DEFAULT_UA,
        headers: Map<String, String> = emptyMap(),
    ): String? = withContext(Dispatchers.IO) {
        get(url, ua, headers)?.let { decodeBody(it) }
    }

    /** GET 原始字节. 失败/非 2xx/空 body 返回 null. */
    suspend fun getBytes(
        url: String,
        ua: String = DEFAULT_UA,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray? = withContext(Dispatchers.IO) {
        get(url, ua, headers)
    }

    /** POST 表单. 失败/非 2xx/空 body 返回 null. */
    suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        ua: String = DEFAULT_UA,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val builder = okhttp3.FormBody.Builder()
            for ((k, v) in fields) builder.add(k, v)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .post(builder.build())
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.code !in 200..299) return@withContext null
                val bytes = resp.body?.bytes() ?: return@withContext null
                if (bytes.isEmpty()) return@withContext null
                decodeBody(bytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun get(url: String, ua: String, headers: Map<String, String>): ByteArray? {
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
            for ((k, v) in headers) {
                if (!k.equals("User-Agent", ignoreCase = true)) builder.header(k, v)
            }
            val request = builder.get().build()
            client.newCall(request).execute().use { resp ->
                if (resp.code !in 200..299) return@use null
                val bytes = resp.body?.bytes() ?: return@use null
                if (bytes.isEmpty()) null else bytes
            }
        } catch (_: Exception) {
            null
        }
    }

    /** gzip/zlib 魔数检测兜底解压, 再按 UTF-8 (允许畸形) 解码. */
    fun decodeBody(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            return try {
                String(gunzip(bytes), Charsets.UTF_8)
            } catch (_: Exception) {
                String(bytes, Charsets.UTF_8)
            }
        }
        if (bytes.size >= 2 && bytes[0] == 0x78.toByte()) {
            return try {
                String(inflate(bytes), Charsets.UTF_8)
            } catch (_: Exception) {
                String(bytes, Charsets.UTF_8)
            }
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    private fun inflate(bytes: ByteArray): ByteArray =
        InflaterInputStream(bytes.inputStream()).use { it.readBytes() }
}

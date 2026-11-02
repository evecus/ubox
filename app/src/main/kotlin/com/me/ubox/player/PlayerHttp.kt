package com.me.ubox.player

import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.github.catvod.net.OkHttp
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.me.ubox.data.Http
import java.net.URLDecoder

/**
 * 播放 HTTP 头注入 (对照 Flutter 版 playInfo.headers / 直播 channelHeader):
 * media3 的 MediaItem 没有 per-item HTTP 头 (原 setHttpHeaders 不存在),
 * 由调用方在 setMediaItem 前写入 [PlayerHeaders.current], 包装 DataSource
 * 在 open() 时把头并入 DataSpec.requestHeaders.
 */
object PlayerHeaders {

    /** 当前播放条目的 HTTP 头 (UA / Referer / Origin 等), 换源时整体覆盖. */
    @Volatile
    var current: Map<String, String> = emptyMap()
}

/** 播放器 DataSource 工厂: DefaultHttpDataSource + 头注入包装, 支持跨协议重定向. */
@UnstableApi
object PlayerDataSourceFactory : DataSource.Factory {
    override fun createDataSource(): DataSource = HeaderedDataSource()
}

@UnstableApi
private class HeaderedDataSource : DataSource {

    // OkHttp: trust-all TLS (自签名/证书链不全的片源), 共享 DNS, 支持 IDN 中文域名与跨协议重定向.
    private val upstream: HttpDataSource = OkHttpDataSource.Factory(OkHttp.player())
        .setDefaultRequestProperties(mapOf("User-Agent" to Http.DEFAULT_UA))
        .createDataSource()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val headers = PlayerHeaders.current
        val spec = if (headers.isEmpty()) {
            dataSpec
        } else {
            dataSpec.buildUpon()
                .setHttpRequestHeaders(dataSpec.httpRequestHeaders + headers)
                .build()
        }
        return upstream.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }
}

/**
 * 播放地址类型推断 (补齐 DefaultMediaSourceFactory 只按 URI 扩展名推断的盲区).
 *
 * media3 的 Util.inferContentType 只看 path 扩展名, 而 TVBox 的播放地址分两种:
 *   - 本地代理: `http://127.0.0.1:9978/proxy?do=m3u8&url=<真实地址>`, path 是
 *     `/proxy` 没有扩展名 → 被推断成 progressive, m3u8 文本交给 extractor 必然报
 *     "None of the available extractors could read the stream"
 *     (contentIsMalformed=false, dataType=1);
 *   - 直链: 路径本身带 `.m3u8`, 原生推断即可覆盖.
 * 原版 Flutter 走 media-kit/mpv, mpv 会 sniff 内容自动识别, 没有这个问题.
 */
fun inferMimeType(url: String): String? {
    val lower = url.lowercase()
    // 本地代理 HLS: jar 源 playerContent 拼的 do=m3u8 / do=live 代理流
    if (lower.contains("do=m3u8") || lower.contains("do=live")) {
        return MimeTypes.APPLICATION_M3U8
    }
    // 真实地址编码在 url= 参数里, 解码后再判断扩展名
    val decoded = try {
        URLDecoder.decode(url, "UTF-8").lowercase()
    } catch (_: Exception) {
        lower
    }
    return if (decoded.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else null
}

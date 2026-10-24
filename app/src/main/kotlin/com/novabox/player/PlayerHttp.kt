package com.novabox.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.novabox.data.Http

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

    private val upstream: HttpDataSource = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(mapOf("User-Agent" to Http.DEFAULT_UA))
        .setAllowCrossProtocolRedirects(true)
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

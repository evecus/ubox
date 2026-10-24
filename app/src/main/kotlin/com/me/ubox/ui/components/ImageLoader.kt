package com.me.ubox.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.me.ubox.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 极简网络图片加载器: OkHttp 拉取 + LruCache 内存缓存 + 可选请求头
 * (豆瓣图床防盗链等场景需要 UA/Referer).
 *
 * 只做内存缓存: 海报图小 (数百 px), 会话内复用足够; 不引三方图片库,
 * 请求头可控 (FongMi 风格 `url@User-Agent=..@Referer=..` 后缀拆解后传入).
 */
object ImageLoader {

    private val maxMemory = Runtime.getRuntime().maxMemory()
    private val cacheSize = (maxMemory / 8).coerceAtMost(48L * 1024 * 1024).toInt()

    private val cache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 加载图片. 失败返回 null. */
    suspend fun load(url: String, headers: Map<String, String> = emptyMap()): Bitmap? {
        if (url.isEmpty()) return null
        val cacheKey = buildKey(url, headers)
        synchronized(cache) { cache.get(cacheKey) }?.let { return it }
        val ua = headers["User-Agent"] ?: Http.DEFAULT_UA
        val bytes = Http.getBytes(url, ua, headers) ?: return null
        if (bytes.isEmpty()) return null
        val bitmap = withContext(Dispatchers.Default) {
            try {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            } catch (_: Exception) {
                null
            }
        } ?: return null
        synchronized(cache) { cache.put(cacheKey, bitmap) }
        return bitmap
    }

    private fun buildKey(url: String, headers: Map<String, String>): String {
        if (headers.isEmpty()) return url
        val sb = StringBuilder(url)
        for ((k, v) in headers) sb.append('|').append(k).append('=').append(v)
        return sb.toString()
    }

    fun clear() {
        synchronized(cache) { cache.evictAll() }
    }
}

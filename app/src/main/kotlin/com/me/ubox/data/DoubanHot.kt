package com.me.ubox.data

import com.me.ubox.engine.EngineClient
import com.me.ubox.data.Ctx.prefs
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 原版 UserFragment.setDouBanData: 首页"豆瓣热播"直连豆瓣抓取当年热播电影
 * (对照 Flutter 版 douban_hot.dart).
 * - URL: https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&playable=1&year_range=YYYY,YYYY
 * - 按天缓存 (原版 Hawk home_hot / home_hot_day), 同一天不重复请求
 * - 抓取失败返回空列表, 由调用方回退到 spider.homeVideoContent()
 */
object DoubanHot {

    private const val DAY_KEY = "home_hot_day"
    private const val JSON_KEY = "home_hot"
    private const val MAX_ITEMS = 25

    /** 桌面 UA (原版 UA.randomOne() 的等价物; 豆瓣对 okhttp UA 返回 418). */
    private const val UA = K_DOUBAN_UA

    private fun today(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun url(): String {
        val y = SimpleDateFormat("yyyy", Locale.US).format(Date())
        return "https://movie.douban.com/j/new_search_subjects" +
            "?sort=U&range=0,10&tags=&playable=1&start=0&year_range=$y,$y"
    }

    /** 取热播列表: 先按天缓存, 再网络 (Compose OkHttp → 引擎 OkHttp 兜底). 失败返回空列表. */
    suspend fun fetch(): List<VodItem> {
        val today = today()
        if (prefs.getString(DAY_KEY, "") == today) {
            val cached = prefs.getString(JSON_KEY, "") ?: ""
            if (cached.isNotEmpty()) {
                val items = parse(cached)
                if (items.isNotEmpty()) return items
            }
        }
        var body = Http.getText(url(), UA) ?: ""
        if (body.isEmpty()) {
            // 引擎 OkHttp 兜底 (IDN/证书等场景).
            body = EngineClient.getText(url())
        }
        val items = parse(body)
        if (items.isNotEmpty()) {
            prefs.edit()
                .putString(DAY_KEY, today)
                .putString(JSON_KEY, body)
                .apply()
        }
        return items
    }

    private fun parse(body: String): List<VodItem> {
        if (body.isEmpty()) return emptyList()
        return try {
            val map = JSONObject(body)
            val data = map.optJSONArray("data") ?: return emptyList()
            val take = minOf(data.length(), MAX_ITEMS)
            val limited = JSONArray()
            for (i in 0 until take) limited.put(data.get(i))
            doubanHotList(limited)
        } catch (_: Exception) {
            emptyList()
        }
    }
}

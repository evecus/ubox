package com.me.ubox.data

import android.net.Uri
import com.me.ubox.engine.EngineClient
import org.json.JSONObject

/**
 * 猜你想搜 (拼音联想, 对照原版 SearchActivity.loadRec):
 * 输入首字母后请求智能搜索接口, 返回中文候选词; 原版在键盘每次按键后触发.
 *
 * - URL: https://tv.aiseet.atianqi.com/i-tvbin/qtv_video/search/get_search_smart_box
 *   ?format=json&page_num=0&page_size=20&key=<输入>
 * - 解析: data.search_data.vecGroupData[0].group_data[]
 *          .dtReportInfo.reportData.keyword_txt
 * - 失败返回空列表, 由调用方保持"热门"词列表 (与原版异常时不清空一致).
 */
object SearchSuggest {

    private const val URL =
        "https://tv.aiseet.atianqi.com/i-tvbin/qtv_video/search/get_search_smart_box"

    suspend fun fetch(key: String): List<String> {
        val k = key.trim()
        if (k.isEmpty()) return emptyList()
        val url = "$URL?format=json&page_num=0&page_size=20&key=${Uri.encode(k)}"
        var body = Http.getText(url) ?: ""
        if (body.isEmpty()) {
            // 引擎 OkHttp 兜底 (IDN/证书等场景, 与 DoubanHot 一致).
            body = EngineClient.getText(url)
        }
        return parse(body)
    }

    private fun parse(body: String): List<String> {
        if (body.isEmpty()) return emptyList()
        return try {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return emptyList()
            val searchData = data.optJSONObject("search_data") ?: return emptyList()
            val groups = searchData.optJSONArray("vecGroupData") ?: return emptyList()
            if (groups.length() == 0) return emptyList()
            val groupData = groups.getJSONObject(0).optJSONArray("group_data") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until groupData.length()) {
                val item = groupData.getJSONObject(i)
                val report = item.optJSONObject("dtReportInfo") ?: continue
                val word = report.optJSONObject("reportData")
                    ?.optString("keyword_txt")?.trim()
                if (!word.isNullOrEmpty()) out.add(word)
            }
            out.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }
}

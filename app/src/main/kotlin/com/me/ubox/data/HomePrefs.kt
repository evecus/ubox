package com.me.ubox.data

import com.me.ubox.data.Ctx.prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 原版 HawkConfig 的首页相关键 (对照 Flutter 版 home_prefs.dart):
 *   HOME_API ("home_api") - 当前首页源 key
 *   HOME_REC ("home_rec") - 首页列表数据来源:
 *     0 = 豆瓣热播 / 1 = 站点推荐 / 2 = 观看历史
 */
object HomePrefs {

    private const val KEY_API = "home_api"
    private const val KEY_REC = "home_rec"

    private val _rec = MutableStateFlow(0)

    /** 首页列表数据模式变化 (设置页切换 → 首页重载"主页" Tab). */
    val recNotifier: StateFlow<Int> = _rec.asStateFlow()

    val homeRec: Int get() = _rec.value
    val homeApi: String get() = prefs.getString(KEY_API, "") ?: ""

    /** 原版 getHomeRecName: 0/1/2 -> 豆瓣热播/站点推荐/观看历史. */
    fun recName(mode: Int): String = when (mode) {
        1 -> "站点推荐"
        2 -> "观看历史"
        else -> "豆瓣热播"
    }

    fun load() {
        _rec.value = prefs.getInt(KEY_REC, 0)
    }

    /** 设置首页源 (原版 ApiConfig.setSourceBean 写 Hawk HOME_API). */
    fun setHomeApi(key: String) {
        val editor = prefs.edit()
        if (key.isEmpty()) editor.remove(KEY_API) else editor.putString(KEY_API, key)
        editor.apply()
    }

    /** 切换首页列表数据来源 (原版 ModelSettingFragment 的 llHomeRec). */
    fun setHomeRec(mode: Int) {
        val m = if (mode < 0 || mode > 2) 0 else mode
        _rec.value = m
        prefs.edit().putInt(KEY_REC, m).apply()
    }
}

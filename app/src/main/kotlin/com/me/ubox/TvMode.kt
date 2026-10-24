package com.me.ubox

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import com.me.ubox.data.Ctx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TV (Android TV / 电视盒子) 模式检测与手动覆盖 (对照 Flutter 版 tv_mode.dart).
 *
 * 检测顺序 (对照原版 NovaTV AndroidManifest 的 leanback 声明):
 *   1. 手动覆盖 (SharedPreferences 'tv_mode'): 'on' / 'off' / 'auto'(默认)
 *   2. PackageManager.hasSystemFeature("android.software.leanback")
 *   3. UiModeManager.currentModeType == UI_MODE_TYPE_TELEVISION
 *
 * 手机与 TV 共用同一个 APK: 运行时按 [isTV] 切换布局 (底部导航 <-> 左侧栏,
 * 小网格 <-> 大网格, 触摸交互 <-> 遥控器焦点交互).
 */
object TvMode {

    private const val KEY_OVERRIDE = "tv_mode"

    private val _isTV = MutableStateFlow(false)

    /** TV 状态变化时整树重建 (根布局读取后整棵 composition 重组). */
    val isTV: StateFlow<Boolean> = _isTV.asStateFlow()

    /** 'auto' | 'on' | 'off' */
    var override: String = "auto"
        private set

    private var loaded = false

    fun init() {
        if (loaded) return
        loaded = true
        override = Ctx.prefs.getString(KEY_OVERRIDE, "auto") ?: "auto"
        _isTV.value = resolve()
    }

    private fun resolve(): Boolean {
        return when (override) {
            "on" -> true
            "off" -> false
            else -> detect()
        }
    }

    /** 判定当前是否运行在 TV 设备:
     *  1) leanback feature (Android TV);
     *  2) UI_MODE_TYPE_TELEVISION (电视盒子常伪装 touchscreen, 但 uiMode 仍是 TV). */
    fun detect(): Boolean {
        val ctx = Ctx.app
        val pm = ctx.packageManager
        if (pm.hasSystemFeature("android.software.leanback")) return true
        val uiMode = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** 设置页手动切换: 'auto' / 'on' / 'off'. */
    fun setOverride(value: String) {
        override = value
        Ctx.prefs.edit().putString(KEY_OVERRIDE, value).apply()
        _isTV.value = resolve()
    }

    fun overrideLabel(value: String): String = when (value) {
        "on" -> "强制开启"
        "off" -> "强制关闭"
        else -> "自动检测"
    }
}

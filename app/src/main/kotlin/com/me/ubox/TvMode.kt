package com.me.ubox

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import com.me.ubox.data.Ctx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 界面模式检测与手动覆盖 (对照 Flutter 版 tv_mode.dart), 共四档 (SharedPreferences 'tv_mode'):
 *   'auto' = 自动检测 (leanback / UI_MODE_TYPE_TELEVISION => TV; 最小宽度 >= 600dp => 平板; 否则手机);
 *   'on'   = TV: 左侧栏 + 大网格布局 + 遥控器焦点交互, 锁定横屏;
 *   'pad'  = 平板: 与 TV 相同的布局 (左侧栏 / 大网格), 但没有遥控器焦点, 纯触摸操作, 锁定横屏;
 *   'off'  = 手机: 底部导航 + 手机布局, 方向不限制.
 *
 * 两个状态:
 *   [isTV]     = 使用 TV 布局 (TV 与平板为 true) —— 只决定"长什么样";
 *   [isRemote] = 使用遥控器交互 (仅 TV 为 true) —— 焦点描边 / 自动聚焦 / 按键操作的播放页与直播页.
 *
 * 手机、平板与 TV 共用同一个 APK: 运行时按这两个状态切换.
 */
object TvMode {

    private const val KEY_OVERRIDE = "tv_mode"

    private val _isTV = MutableStateFlow(false)
    private val _isRemote = MutableStateFlow(false)

    /** TV 布局 (TV 或平板). 变化时整树重建. */
    val isTV: StateFlow<Boolean> = _isTV.asStateFlow()

    /** 遥控器交互 (仅真 TV): 焦点效果 / 自动聚焦 / 按键操作. 平板为 false. */
    val isRemote: StateFlow<Boolean> = _isRemote.asStateFlow()

    /** 'auto' | 'on' | 'pad' | 'off' */
    var override: String = "auto"
        private set

    private var loaded = false

    fun init() {
        if (loaded) return
        loaded = true
        override = Ctx.prefs.getString(KEY_OVERRIDE, "auto") ?: "auto"
        apply()
    }

    /** 实际生效的模式: 'on' (TV) / 'pad' / 'off' (手机); 'auto' 时按设备检测. */
    fun effective(): String = when (override) {
        "on", "pad", "off" -> override
        else -> when {
            detect() -> "on"
            detectPad() -> "pad"
            else -> "off"
        }
    }

    private fun apply() {
        val mode = effective()
        _isRemote.value = mode == "on"
        _isTV.value = mode != "off"
    }

    /** 平板判定: 最小宽度 >= 600dp (Android 的 sw600dp 平板分界, 旋转不改变). */
    fun detectPad(): Boolean =
        Ctx.app.resources.configuration.smallestScreenWidthDp >= 600

    /** TV 与平板都锁定横屏; 手机不限制方向. Activity 与播放页退出全屏时都按它复位. */
    fun baseOrientation(): Int =
        if (_isTV.value) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /** 设置页显示用: 自动检测时带上实际生效的模式. */
    fun currentLabel(): String {
        val eff = overrideLabel(effective())
        return if (override == "auto") "自动检测 ($eff)" else eff
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

    /** 设置页手动切换: 'auto' / 'on' / 'pad' / 'off'. */
    fun setOverride(value: String) {
        override = value
        Ctx.prefs.edit().putString(KEY_OVERRIDE, value).apply()
        apply()
    }

    fun overrideLabel(value: String): String = when (value) {
        "on" -> "TV 模式"
        "pad" -> "平板模式"
        "off" -> "手机模式"
        else -> "自动检测"
    }
}

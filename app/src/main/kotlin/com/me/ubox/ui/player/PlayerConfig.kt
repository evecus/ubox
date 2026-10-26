package com.me.ubox.ui.player

import com.me.ubox.data.Ctx
import org.json.JSONObject

/**
 * 点播播放配置 (对照 NovaBox/NovaTV VodController 的 mPlayerConfig, 按影片持久化):
 *   sp = 倍速, sc = 画面比例, st = 片头跳过秒数, et = 片尾跳过秒数.
 */
data class VodPlayConfig(
    val speed: Float = 1.0f,
    val scale: Int = 0,
    val skipStartSec: Int = 0,
    val skipEndSec: Int = 0,
)

object VodPlayConfigStore {

    private fun key(siteKey: String, vodId: String) = "vodcfg_${siteKey}_$vodId"

    fun load(siteKey: String, vodId: String): VodPlayConfig {
        val raw = Ctx.prefs.getString(key(siteKey, vodId), null) ?: return VodPlayConfig()
        return try {
            val o = JSONObject(raw)
            VodPlayConfig(
                speed = o.optDouble("sp", 1.0).toFloat(),
                scale = o.optInt("sc", 0).coerceIn(0, PlayerOptions.SCALE_NAMES.size - 1),
                skipStartSec = o.optInt("st", 0).coerceAtLeast(0),
                skipEndSec = o.optInt("et", 0).coerceAtLeast(0),
            )
        } catch (_: Exception) {
            VodPlayConfig()
        }
    }

    fun save(siteKey: String, vodId: String, cfg: VodPlayConfig) {
        val o = JSONObject()
            .put("sp", cfg.speed.toDouble())
            .put("sc", cfg.scale)
            .put("st", cfg.skipStartSec)
            .put("et", cfg.skipEndSec)
        Ctx.prefs.edit().putString(key(siteKey, vodId), o.toString()).apply()
    }
}

/** 倍速 / 画面比例选项 (对照原版 SpeedSelectDialog 与 PlayerHelper 的 SCREEN_SCALE_*). */
object PlayerOptions {
    /** 菜单里的倍速档位 (长按加速固定 3.0x, 不在此列). */
    val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)

    /** 0 默认(适应) / 1 16:9 / 2 4:3 / 3 填充(拉伸) / 4 原始 / 5 裁剪(铺满). */
    val SCALE_NAMES = listOf("默认", "16:9", "4:3", "填充", "原始", "裁剪")

    const val BOOST_SPEED = 3.0f

    fun speedLabel(v: Float): String =
        if (v == v.toInt().toFloat()) "${v.toInt()}.0x" else "${v}x"
}

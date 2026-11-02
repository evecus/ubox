package com.me.ubox.data

import com.me.ubox.data.Ctx.prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * 配置 / 直播源 本地缓存 (两者共用开关与更新入口).
 *
 * 默认关闭 (在线拉取). 开启后:
 *  - 每次在线拉取成功都会把结果写入缓存;
 *  - 启动时优先读缓存, 不再联网拉取 (配置的 spider 主 jar 若本地已有也直接复用);
 *  - 设置页"更新缓存"强制联网重新拉取一次并覆盖缓存.
 * 关闭开关会清空缓存, 避免之后再开启时读到过期内容.
 *
 * 每种数据 ([KIND_TVBOX] / [KIND_LIVE]) 只保留一份, 文件内记录它对应的地址 (key),
 * 地址不一致视为未命中.
 */
object ConfigCache {

    const val KIND_TVBOX = "tvbox"
    const val KIND_LIVE = "live"

    private const val PREF_ENABLED = "cache_config_enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 缓存内容变化计数 (设置页据此刷新"缓存时间"). */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    class Entry(val text: String, val meta: JSONObject, val time: Long)

    fun load() {
        _enabled.value = prefs.getBoolean(PREF_ENABLED, false)
    }

    /** 开关; 关闭时清空已有缓存. */
    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean(PREF_ENABLED, on).apply()
        if (!on) clearAll()
    }

    private fun file(kind: String): File {
        val dir = File(Ctx.filesDir, "config_cache")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$kind.json")
    }

    /** 读缓存; 未开启 / 不存在 / 地址不匹配 / 损坏 → null. */
    fun read(kind: String, key: String): Entry? {
        if (!_enabled.value) return null
        return peek(kind, key)
    }

    /** 不看开关地读取 (用于更新前后比对内容是否变化). */
    fun peek(kind: String, key: String): Entry? {
        return try {
            val f = file(kind)
            if (!f.exists()) return null
            val obj = JSONObject(f.readText())
            if (obj.optString("key") != key) return null
            val text = obj.optString("text")
            if (text.isEmpty()) return null
            Entry(text, obj.optJSONObject("meta") ?: JSONObject(), obj.optLong("time"))
        } catch (_: Exception) {
            null
        }
    }

    /** 缓存时间 (毫秒); 无缓存返回 0. 不校验地址, 仅用于展示. */
    fun timeOf(kind: String): Long {
        return try {
            val f = file(kind)
            if (!f.exists()) 0L else JSONObject(f.readText()).optLong("time")
        } catch (_: Exception) {
            0L
        }
    }

    /** 写缓存 (只在开关开启时生效). 写入失败静默忽略, 不影响正常使用. */
    fun write(kind: String, key: String, text: String, meta: JSONObject = JSONObject()) {
        if (!_enabled.value || text.isEmpty()) return
        try {
            val obj = JSONObject().apply {
                put("key", key)
                put("time", System.currentTimeMillis())
                put("meta", meta)
                put("text", text)
            }
            val f = file(kind)
            val tmp = File(f.parentFile, "$kind.tmp")
            tmp.writeText(obj.toString())
            if (f.exists()) f.delete()
            tmp.renameTo(f)
            _revision.value++
        } catch (_: Exception) {
        }
    }

    fun clearAll() {
        try {
            File(Ctx.filesDir, "config_cache").listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
        _revision.value++
    }
}

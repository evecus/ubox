package com.me.ubox.data

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest

/** TVBox 配置解析相关的 JSON / Base64 / MD5 工具 (对照 Flutter 版 config.dart). */
object JsonUtil {

    /**
     * 剥离 JSON 中的 // 注释 (整行注释与行尾注释).
     * 原版 ApiConfig.FindResult/parseJson 走 Gson lenient, // 注释会被跳过;
     * org.json 是严格解析, 遇到注释行直接抛异常.
     * 字符串字面量内的 // (http:// 等) 必须保留, 所以按字符串状态扫描而非纯正则.
     */
    fun stripJsonComments(text: String): String {
        if (!text.contains("//")) return text
        val sb = StringBuilder(text.length)
        var inString = false
        var escaped = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                sb.append(c)
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                i++
                continue
            }
            if (c == '"') {
                inString = true
                sb.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                // 行注释: 跳到行尾 (保留换行符, 行号结构不塌)
                while (i < text.length && text[i] != '\n') i++
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** TVBox style plain config: strip // comments but keep protocol URLs. */
    fun clean(body: String): String {
        var text = body
        if (text.startsWith('\uFEFF')) text = text.substring(1)
        text = text.trim()
        if (text.startsWith("{") || text.startsWith("[")) return text
        val out = StringBuilder()
        for (line in text.split('\n')) {
            if (line.trimStart().startsWith("//")) continue
            out.append(stripTrailingComment(line)).append('\n')
        }
        return out.toString()
    }

    /**
     * 去掉行尾注释: 对照 Dart 正则 `(?<![:/\w])//.*$` —
     * `//` 前一字符是 `:` `/` 或单词字符时不算注释 (保护 http:// 等).
     */
    private fun stripTrailingComment(line: String): String {
        var i = 0
        while (i < line.length) {
            if (line[i] == '/' && i + 1 < line.length && line[i + 1] == '/') {
                val prev = if (i > 0) line[i - 1] else ' '
                val prevIsProtocol = prev == ':' || prev == '/' ||
                    prev.isLetterOrDigit() || prev == '_'
                if (!prevIsProtocol) return line.substring(0, i)
                i += 2
                continue
            }
            i++
        }
        return line
    }

    /** 宽松 base64: 兼容 -_ 变体, 补齐 padding; 解出来不是 JSON 返回 null. */
    fun tryBase64(s: String): String? {
        var t = s.replace("-", "+").replace("_", "/")
        while (t.length % 4 != 0) t += "="
        return try {
            val decoded = Base64.decode(t, Base64.DEFAULT)
            val text = String(decoded, Charsets.UTF_8)
            val trimmed = text.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) trimmed else null
        } catch (_: Exception) {
            null
        }
    }

    /** 裸 base64 (解出来即字节, 不校验内容). 用于 img+ jar 提取. */
    fun decodeBase64OrNull(s: String): ByteArray? {
        var t = s.replace("-", "+").replace("_", "/")
        while (t.length % 4 != 0) t += "="
        return try {
            Base64.decode(t, Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
    }

    fun md5Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    /** 稳定文件名用的短哈希 (仅防碰撞, 非密码学强度, 对照 Flutter _md5). */
    fun shortHash(s: String): String {
        var h = 0x811c9dc5.toInt()
        for (c in s) {
            h = h xor c.code
            h *= 0x01000193
        }
        return (h.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
    }

    /** org.json 宽松解析: JSONObject; 带注释时先剥离再重试一次. */
    fun decodeObjectOrNull(text: String): JSONObject? {
        val direct = decodeObjectOnce(text)
        if (direct != null) return direct
        if (!text.contains("//")) return null
        val stripped = stripJsonComments(text)
        if (stripped == text) return null
        return decodeObjectOnce(stripped)
    }

    private fun decodeObjectOnce(text: String): JSONObject? {
        val t = if (text.startsWith("{") || text.startsWith("[")) text else clean(extractConfig(text))
        return try {
            JSONObject(t)
        } catch (_: Exception) {
            null
        }
    }

    /** TVBox 风格的多层解析: 整体 base64 / @ 拼接的多个 base64 / 带注释的轻量 JSON. */
    fun extractConfig(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
        val packed = trimmed.replace(Regex("\\s"), "")
        for (piece in packed.split('@')) {
            if (piece.length < 24) continue
            val decoded = tryBase64(piece) ?: continue
            if (decodeObjectOrNull(clean(decoded)) != null) return decoded
        }
        tryBase64(packed)?.let { decoded ->
            if (decodeObjectOrNull(clean(decoded)) != null) return decoded
        }
        return trimmed
    }

    /**
     * org.json 语义对齐 Dart `m['key']?.toString() ?? ''`:
     * 键缺失或值为 JSONObject.NULL (JSON null) 时返回 null, 否则返回字符串形式.
     *
     * 背景: org.json 的 optString() 与 opt()?.toString() 遇到 JSON null 会得到
     * "null" 字面量 (JSONObject.NULL.toString()), 而 Dart jsonDecode 的 JSON null
     * 就是 null, 经 `?? ''` 兜底成空串. 解析爬虫/直播/弹幕 JSON 时必须走本函数,
     * 否则 vod_name=null 的条目会以 "null" 之名进入列表, danmaku=null 会被当地址加载.
     */
    fun optStringOrNull(obj: JSONObject?, key: String): String? {
        if (obj == null) return null
        val v = obj.opt(key)
        if (v == null || v === JSONObject.NULL) return null
        return v.toString()
    }
}

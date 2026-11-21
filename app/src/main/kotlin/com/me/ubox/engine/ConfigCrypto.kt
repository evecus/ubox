package com.me.ubox.engine

import android.util.Base64
import org.json.JSONObject
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 原版 ApiConfig.FindResult 的配置解密 (8位**+base64 / 2423 AES-CBC / ;pk; AES-ECB).
 *
 * 行为对照原版 NovaTV ApiConfig.FindResult + util.AES:
 *   - 已是 JSON → 原样返回
 *   - `[A-Za-z0-9]{8}**` 标记 + base64 → 先解一层
 *   - `2423` 开头 → AES-CBC (key/iv 从密文内部按 `$#`/`#$` 标记与尾部 13 字符提取)
 *   - 带 configKey (;pk; 携带) 且不是 JSON → AES-ECB
 *   - 其余原样返回; 任何异常都吞掉并返回原文本 (与原版一致, 由调用方判空)
 *
 * 注意: Android JCE 不支持 PKCS7Padding 字面量, 16 字节块下 PKCS5 == PKCS7.
 */
object ConfigCrypto {

    private val MARK8 = Regex("[A-Za-z0-9]{8}\\*\\*")

    fun findResult(json: String, configKey: String?): String {
        var content = json
        return try {
            if (isJson(content)) return content
            val m = MARK8.find(content)
            if (m != null) {
                content = content.substring(content.indexOf(m.value) + 10)
                content = String(Base64.decode(content, Base64.DEFAULT))
            }
            content = content.trim()
            when {
                content.startsWith("2423") -> {
                    val packed = content.replace(Regex("\\s+"), "")
                    val data = packed.substring(packed.indexOf("2324") + 4, packed.length - 26)
                    val lower = String(toBytes(packed)).lowercase()
                    val key = rightPadding(
                        lower.substring(lower.indexOf("$#") + 2, lower.indexOf("#$")), "0", 16)
                    val iv = rightPadding(lower.substring(lower.length - 13), "0", 16)
                    cbc(data, key, iv) ?: content
                }
                configKey != null && !isJson(content) -> {
                    ecb(content, configKey) ?: content
                }
                else -> content
            }
        } catch (_: Exception) {
            json
        }
    }

    fun rightPadding(key: String, replace: String, length: Int): String {
        val trimmed = key.trim()
        return when {
            trimmed.length > length -> trimmed.substring(0, length)
            trimmed.length == length -> trimmed
            else -> trimmed + replace.repeat(length - trimmed.length)
        }
    }

    fun ecb(data: String, key: String): String? {
        return try {
            val keySpec = SecretKeySpec(rightPadding(key, "0", 16).toByteArray(), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            String(cipher.doFinal(toBytes(data)))
        } catch (_: Exception) {
            null
        }
    }

    fun cbc(data: String, key: String, iv: String): String? {
        return try {
            val keySpec = SecretKeySpec(key.toByteArray(), "AES")
            val paramSpec: AlgorithmParameterSpec = IvParameterSpec(iv.toByteArray())
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, paramSpec)
            String(cipher.doFinal(toBytes(data)))
        } catch (_: Exception) {
            null
        }
    }

    fun isJson(content: String): Boolean {
        return try {
            JSONObject(content)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 十六进制串 → 字节数组 (对照原版 AES.toBytes). */
    fun toBytes(src: String): ByteArray {
        val l = src.length / 2
        val ret = ByteArray(l)
        for (i in 0 until l) {
            ret[i] = Integer.valueOf(src.substring(i * 2, i * 2 + 2), 16).toByte()
        }
        return ret
    }
}

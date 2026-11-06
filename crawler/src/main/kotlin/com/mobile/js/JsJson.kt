package com.mobile.js

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON <-> Kotlin 容器互转工具.
 *
 * quickjs-kt 的类型映射: JS object → Map (JsObject), JS array → List,
 * JS number → Long/Double. 这里提供 Map/List → JSON 字符串 与
 * JSON 字符串 → Map/List 的递归转换, 用于绑定层传参 (Req 解析) 与结果加工.
 */
object JsJson {

    /** JSON 字符串 → Map/List/基本类型, 失败返回 null (不抛异常). */
    fun parse(text: String?): Any? {
        if (text.isNullOrEmpty()) return null
        return try {
            val trimmed = text.trim()
            when {
                trimmed.startsWith("{") -> fromAny(JSONObject(trimmed))
                trimmed.startsWith("[") -> fromAny(JSONArray(trimmed))
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** org.json 容器 → Map/List 递归转换. */
    fun fromAny(any: Any?): Any? = when (any) {
        is JSONObject -> {
            val map = LinkedHashMap<String, Any?>()
            val keys = any.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = fromAny(any.opt(k))
            }
            map
        }
        is JSONArray -> {
            val list = ArrayList<Any?>()
            for (i in 0 until any.length()) list.add(fromAny(any.opt(i)))
            list
        }
        else -> any
    }

    /** Map/List/基本类型 → JSON 字符串 (绑定层接 JS object 入参后转 Req/Res 用). */
    fun stringify(any: Any?): String = when (any) {
        null -> "null"
        is Map<*, *> -> toJsonObject(any).toString()
        is List<*> -> toJsonArray(any).toString()
        is Boolean, is Number -> any.toString()
        is String -> JSONObject.quote(any)
        else -> JSONObject.quote(any.toString())
    }

    private fun toJsonObject(map: Map<*, *>): JSONObject {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k.toString(), wrap(v))
        return obj
    }

    private fun toJsonArray(list: List<*>): JSONArray {
        val array = JSONArray()
        for (v in list) array.put(wrap(v))
        return array
    }

    private fun wrap(v: Any?): Any? = when (v) {
        is Map<*, *> -> toJsonObject(v)
        is List<*> -> toJsonArray(v)
        else -> v
    }
}

package com.mobile.js

@Suppress("ObjectPropertyName", "unused")
object local {

    fun delete(str: String?, str2: String?) {
        try {
            App.prefs("jsRuntime").edit().remove("jsRuntime_" + str + "_" + str2).apply()
        } catch (ignored: Exception) {
        }
    }

    fun get(str: String?, str2: String?): String {
        try {
            return App.prefs("jsRuntime").getString("jsRuntime_" + str + "_" + str2, "") ?: ""
        } catch (e: Exception) {
            return str2 ?: ""
        }
    }

    fun set(str: String?, str2: String?, str3: String?) {
        try {
            App.prefs("jsRuntime").edit().putString("jsRuntime_" + str + "_" + str2, str3).apply()
        } catch (ignored: Exception) {
        }
    }
}

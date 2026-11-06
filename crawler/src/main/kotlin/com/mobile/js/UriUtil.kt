package com.mobile.js

import android.text.TextUtils

/**
 * RFC-3986 相对 URI 解析工具.
 *
 * 原实现在 quickjs 模块的 com.whl.quickjs.wrapper.UriUtil (ExoPlayer 同款),
 * 随 quickjs 模块移除一并移植为 Kotlin, 供 JsSpider 做 ES module 相对导入
 * 规范化 (对齐 QuickJS native 的 js_module_normalize_name 行为).
 */
object UriUtil {

    private const val INDEX_COUNT = 4
    private const val SCHEME_COLON = 0
    private const val PATH = 1
    private const val QUERY = 2
    private const val FRAGMENT = 3

    fun resolve(baseUri: String?, referenceUri: String): String {
        val uri = StringBuilder()
        val base = baseUri ?: ""
        val reference = referenceUri ?: ""

        val refIndices = getUriIndices(reference)
        if (refIndices[SCHEME_COLON] != -1) {
            // 引用是绝对 URI, 目标即引用本身.
            uri.append(reference)
            return removeDotSegments(uri, refIndices[PATH], refIndices[QUERY])
        }

        val baseIndices = getUriIndices(base)
        if (refIndices[FRAGMENT] == 0) {
            // 引用为空或只有 fragment: 取 base 去掉 fragment 后拼接引用.
            return uri.append(base, 0, baseIndices[FRAGMENT]).append(reference).toString()
        }

        if (refIndices[QUERY] == 0) {
            // 引用从 query 开始: base 去掉 query 后拼接引用.
            return uri.append(base, 0, baseIndices[QUERY]).append(reference).toString()
        }

        if (refIndices[PATH] != 0) {
            // 引用带 authority: base 的 scheme 加引用.
            val baseLimit = baseIndices[SCHEME_COLON] + 1
            uri.append(base, 0, baseLimit).append(reference)
            return removeDotSegments(uri, baseLimit + refIndices[PATH], baseLimit + refIndices[QUERY])
        }

        if (reference[refIndices[PATH]] == '/') {
            // 引用路径是根路径: base 的 scheme+authority 加引用.
            uri.append(base, 0, baseIndices[PATH]).append(reference)
            return removeDotSegments(uri, baseIndices[PATH], baseIndices[PATH] + refIndices[QUERY])
        }

        return if (baseIndices[SCHEME_COLON] + 2 < baseIndices[PATH]
            && baseIndices[PATH] == baseIndices[QUERY]
        ) {
            // base 的 hier-part 只有 authority 且 path 为空: authority 后补一个 '/'.
            uri.append(base, 0, baseIndices[PATH]).append('/').append(reference)
            removeDotSegments(uri, baseIndices[PATH], baseIndices[PATH] + refIndices[QUERY] + 1)
        } else {
            // 常规相对路径: 截到 base 最后一个 '/' 之后拼接引用.
            val lastSlashIndex = base.lastIndexOf('/', baseIndices[QUERY] - 1)
            val baseLimit = if (lastSlashIndex == -1) baseIndices[PATH] else lastSlashIndex + 1
            uri.append(base, 0, baseLimit).append(reference)
            removeDotSegments(uri, baseIndices[PATH], baseLimit + refIndices[QUERY])
        }
    }

    fun isAbsolute(uri: String?): Boolean {
        return uri != null && getUriIndices(uri)[SCHEME_COLON] != -1
    }

    private fun removeDotSegments(uri: StringBuilder, offset: Int, limit: Int): String {
        var offset = offset
        var limit = limit
        if (offset >= limit) return uri.toString()
        if (uri[offset] == '/') offset++
        var segmentStart = offset
        var i = offset
        while (i <= limit) {
            val nextSegmentStart: Int
            if (i == limit) {
                nextSegmentStart = i
            } else if (uri[i] == '/') {
                nextSegmentStart = i + 1
            } else {
                i++
                continue
            }
            if (i == segmentStart + 1 && uri[segmentStart] == '.') {
                // "abc/def/./ghi" → "abc/def/ghi"
                uri.delete(segmentStart, nextSegmentStart)
                limit -= nextSegmentStart - segmentStart
                i = segmentStart
            } else if (i == segmentStart + 2 && uri[segmentStart] == '.' && uri[segmentStart + 1] == '.') {
                // "abc/def/../ghi" → "abc/ghi"
                val prevSegmentStart = uri.lastIndexOf("/", segmentStart - 2) + 1
                val removeFrom = if (prevSegmentStart > offset) prevSegmentStart else offset
                uri.delete(removeFrom, nextSegmentStart)
                limit -= nextSegmentStart - removeFrom
                segmentStart = prevSegmentStart
                i = prevSegmentStart
            } else {
                i++
                segmentStart = i
            }
        }
        return uri.toString()
    }

    private fun getUriIndices(uriString: String?): IntArray {
        val indices = IntArray(INDEX_COUNT)
        if (TextUtils.isEmpty(uriString)) {
            indices[SCHEME_COLON] = -1
            return indices
        }
        val s = uriString!!
        val length = s.length
        var fragmentIndex = s.indexOf('#')
        if (fragmentIndex == -1) fragmentIndex = length
        var queryIndex = s.indexOf('?')
        if (queryIndex == -1 || queryIndex > fragmentIndex) queryIndex = fragmentIndex
        var schemeIndexLimit = s.indexOf('/')
        if (schemeIndexLimit == -1 || schemeIndexLimit > queryIndex) schemeIndexLimit = queryIndex
        var schemeIndex = s.indexOf(':')
        if (schemeIndex > schemeIndexLimit) schemeIndex = -1

        val hasAuthority = schemeIndex + 2 < queryIndex
                && s[schemeIndex + 1] == '/'
                && s[schemeIndex + 2] == '/'
        val pathIndex: Int
        if (hasAuthority) {
            pathIndex = s.indexOf('/', schemeIndex + 3)
            if (pathIndex == -1 || pathIndex > queryIndex) pathIndex = queryIndex
        } else {
            pathIndex = schemeIndex + 1
        }
        indices[SCHEME_COLON] = schemeIndex
        indices[PATH] = pathIndex
        indices[QUERY] = queryIndex
        indices[FRAGMENT] = fragmentIndex
        return indices
    }
}

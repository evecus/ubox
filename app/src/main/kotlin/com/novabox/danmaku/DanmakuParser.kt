package com.novabox.danmaku

import android.graphics.Color
import android.util.Xml
import com.novabox.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.Duration
import master.flame.danmaku.danmaku.model.IDanmakus
import master.flame.danmaku.danmaku.model.IDisplayer
import master.flame.danmaku.danmaku.model.SpecialDanmaku
import master.flame.danmaku.danmaku.model.android.DanmakuFactory
import master.flame.danmaku.danmaku.model.android.Danmakus
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.danmaku.util.DanmakuUtils
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** 弹幕源解析失败 (网络错误 / 空内容 / 无条目). */
class DanmakuParseException(message: String) : Exception(message)

/** bilibili XML 弹幕条目 (p 属性 + 文本). */
data class DanmuItem(val param: String, val text: String)

/**
 * 弹幕 XML 解析 (对照原版 NovaBox `bean/Danmu.java`):
 * `<d p="time,type,size,color">text</d>` → [DanmuItem].
 * XmlPullParser 为主, 非法实体/解析失败时回退正则.
 */
object Danmu {

    private val D_TAG_PATTERN = Regex(
        "<d\\s+[^>]*\\bp\\s*=\\s*(['\"])(.*?)\\1[^>]*>(.*?)</d>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun fromXml(xml: String): List<DanmuItem> {
        if (xml.isEmpty()) return emptyList()
        val out = mutableListOf<DanmuItem>()
        val fixedXml = escapeIllegalEntities(xml)
        if (!parseByXmlPull(out, fixedXml)) {
            out.clear()
            parseByTag(out, xml)
        }
        return out
    }

    private fun parseByXmlPull(out: MutableList<DanmuItem>, xml: String): Boolean {
        return try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && "d" == parser.name) {
                    val param = parser.getAttributeValue(null, "p")
                    val text = parser.nextText()
                    if (!param.isNullOrEmpty() && !text.isNullOrEmpty()) {
                        out.add(DanmuItem(param, text))
                    }
                }
                eventType = parser.next()
            }
            out.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private fun parseByTag(out: MutableList<DanmuItem>, xml: String) {
        for (m in D_TAG_PATTERN.findAll(xml)) {
            val param = decodeXmlString(m.groupValues[2])
            val text = m.groupValues[3]
            if (param.isNotEmpty() && text.isNotEmpty()) out.add(DanmuItem(param, text))
        }
    }

    /** 裸 `&` 且不是合法实体 → 补成 `&amp;`, 否则 XmlPullParser 直接抛异常. */
    private fun escapeIllegalEntities(xml: String): String {
        val sb = StringBuilder(xml.length)
        for (i in xml.indices) {
            val ch = xml[i]
            if (ch == '&' && !isLegalEntity(xml, i + 1)) {
                sb.append("&amp;")
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun isLegalEntity(text: String, start: Int): Boolean {
        val end = text.indexOf(';', start)
        if (end < 0 || end - start > 10) return false
        val entity = text.substring(start, end)
        if (entity == "amp" || entity == "lt" || entity == "gt" ||
            entity == "quot" || entity == "apos"
        ) {
            return true
        }
        if (entity.startsWith("#x") || entity.startsWith("#X")) return isHexNumber(entity, 2)
        return entity.startsWith("#") && isDecimalNumber(entity, 1)
    }

    private fun isDecimalNumber(text: String, start: Int): Boolean {
        if (text.length <= start) return false
        for (i in start until text.length) {
            if (!text[i].isDigit()) return false
        }
        return true
    }

    private fun isHexNumber(text: String, start: Int): Boolean {
        for (i in start until text.length) {
            val ch = text[i]
            if (!ch.isDigit() && (ch < 'a' || ch > 'f') && (ch < 'A' || ch > 'F')) return false
        }
        return true
    }

    fun decodeXmlString(text: String): String {
        if (text.isEmpty()) return ""
        return text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&gt;", ">")
            .replace("&lt;", "<")
    }
}

/**
 * 弹幕大师解析器 (对照原版 NovaBox `player/danmu/Parser.java`):
 * 把 [DanmuItem] 列表转成库的 [BaseDanmaku] (含高级弹幕特殊处理).
 *
 * 网络拉取/解压在构造前由 [DanmakuParser] 完成 (IO 线程), 与 NovaBox 的
 * "executor 里 resolveContent + Danmu.fromXml, 主线程 prepare" 模式一致.
 */
class NovaDanmakuParser(private val items: List<DanmuItem>) : BaseDanmakuParser() {

    private var scaleX = 1f
    private var scaleY = 1f
    private var index = 0

    val itemCount: Int get() = items.size

    override fun parse(): Danmakus {
        val result = Danmakus(IDanmakus.ST_BY_TIME)
        for (data in items) {
            val danmaku = createDanmaku(data) ?: continue
            synchronized(result.obtainSynchronizer()) {
                result.addItem(danmaku)
            }
        }
        return result
    }

    override fun setDisplayer(display: IDisplayer): BaseDanmakuParser {
        super.setDisplayer(display)
        scaleX = mDispWidth.toFloat() / DanmakuFactory.BILI_PLAYER_WIDTH
        scaleY = mDispHeight.toFloat() / DanmakuFactory.BILI_PLAYER_HEIGHT
        return this
    }

    private fun createDanmaku(data: DanmuItem): BaseDanmaku? {
        return try {
            val values = data.param.split(",")
            if (values.size < 4) return null
            val type = values[1].trim().toInt()
            val item = mContext.mDanmakuFactory.createDanmaku(type, mContext) ?: return null
            val time = (values[0].trim().toFloat() * 1000).toLong()
            val size = values[2].trim().toFloat() *
                maxOf(1.0f, mDispDensity - 0.6f)
            val color = if (DanmuSettings.randomColor) {
                DanmuSettings.pickRandomColor()
            } else {
                (0xFF000000L or values[3].trim().toLong()).toInt()
            }
            item.time = time
            item.setTimer(mTimer)
            item.textSize = size
            item.textColor = color
            item.textShadowColor = if (color <= Color.BLACK) Color.WHITE else Color.BLACK
            item.flags = mContext.mGlobalFlagValues
            item.index = index++
            DanmakuUtils.fillText(item, Danmu.decodeXmlString(data.text))
            if (item.type == BaseDanmaku.TYPE_SPECIAL &&
                data.text.startsWith("[") && data.text.endsWith("]") &&
                !setSpecial(item)
            ) {
                return null
            }
            item
        } catch (_: Throwable) {
            null
        }
    }

    /** 高级弹幕: text 为 JSON 数组 (对照原版 Parser.setSpecial). */
    private fun setSpecial(item: BaseDanmaku): Boolean {
        return try {
            val jsonArray = JSONArray(item.text)
            val textArr = Array(jsonArray.length()) { jsonArray.getString(it) }
            if (textArr.size < 5 || textArr[4].isEmpty()) return false
            DanmakuUtils.fillText(item, textArr[4])
            var beginX = textArr[0].toFloat()
            var beginY = textArr[1].toFloat()
            var endX = beginX
            var endY = beginY
            val alphaArr = textArr[2].split("-")
            val beginAlpha = (master.flame.danmaku.danmaku.model.AlphaValue.MAX * alphaArr[0].toFloat()).toInt()
            val endAlpha = if (alphaArr.size > 1) {
                (master.flame.danmaku.danmaku.model.AlphaValue.MAX * alphaArr[1].toFloat()).toInt()
            } else {
                beginAlpha
            }
            val alphaDuration = (textArr[3].toFloat() * 1000).toLong()
            var translationDuration = alphaDuration
            var translationStartDelay = 0L
            var rotateY = 0f
            var rotateZ = 0f
            if (textArr.size >= 7) {
                rotateZ = textArr[5].toFloat()
                rotateY = textArr[6].toFloat()
            }
            if (textArr.size >= 11) {
                endX = textArr[7].toFloat()
                endY = textArr[8].toFloat()
                if (textArr[9].isNotEmpty()) translationDuration = textArr[9].toLong()
                if (textArr[10].isNotEmpty()) translationStartDelay = (textArr[10].toFloat()).toLong()
            }
            if (isPercentageNumber(textArr[0])) beginX *= DanmakuFactory.BILI_PLAYER_WIDTH
            if (isPercentageNumber(textArr[1])) beginY *= DanmakuFactory.BILI_PLAYER_HEIGHT
            if (textArr.size >= 8 && isPercentageNumber(textArr[7])) endX *= DanmakuFactory.BILI_PLAYER_WIDTH
            if (textArr.size >= 9 && isPercentageNumber(textArr[8])) endY *= DanmakuFactory.BILI_PLAYER_HEIGHT
            item.duration = Duration(alphaDuration)
            item.rotationZ = rotateZ
            item.rotationY = rotateY
            mContext.mDanmakuFactory.fillTranslationData(
                item, beginX, beginY, endX, endY,
                translationDuration, translationStartDelay, scaleX, scaleY,
            )
            mContext.mDanmakuFactory.fillAlphaData(item, beginAlpha, endAlpha, alphaDuration)
            if (textArr.size >= 12 && "true".equals(textArr[11], ignoreCase = true)) {
                item.textShadowColor = Color.TRANSPARENT
            }
            if (textArr.size >= 14) {
                (item as SpecialDanmaku).isQuadraticEaseOut = "0" == textArr[13]
            }
            if (textArr.size >= 15 && textArr[14].isNotEmpty()) {
                fillLinePath(item, textArr[14])
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun fillLinePath(item: BaseDanmaku, motionPathString: String) {
        val motionPath = motionPathString.substring(1)
        if (motionPath.isEmpty()) return
        val pointStrArray = motionPath.split("L")
        val points = Array(pointStrArray.size) { FloatArray(2) }
        for (i in pointStrArray.indices) {
            val pointArray = pointStrArray[i].split(",")
            if (pointArray.size < 2) return
            points[i][0] = pointArray[0].toFloat()
            points[i][1] = pointArray[1].toFloat()
        }
        DanmakuFactory.fillLinePathData(item, points, scaleX, scaleY)
    }

    private fun isPercentageNumber(number: String): Boolean = number.contains(".")
}

/**
 * 弹幕源加载 + 解析入口 (对照原版 Parser.resolveContent):
 *   - http(s) 地址: OkHttp 拉取 (gzip/deflate 解压, 裸 deflate 兜底)
 *   - 其余: 视为内联 XML
 * 返回可直接交给 DanmakuView.prepare 的解析器; 取不到内容/无条目返回 null.
 */
object DanmakuParser {

    suspend fun create(
        source: String,
        isCancelled: () -> Boolean = { false },
    ): NovaDanmakuParser? = withContext(Dispatchers.IO) {
        val text = source.trim()
        if (text.isEmpty()) return@withContext null
        val content = if (!text.startsWith("http")) text else resolveContent(text, isCancelled)
        if (content.isEmpty() || isCancelled()) return@withContext null
        val items = Danmu.fromXml(content)
        if (items.isEmpty()) return@withContext null
        NovaDanmakuParser(items)
    }

    private suspend fun resolveContent(source: String, isCancelled: () -> Boolean): String {
        if (isCancelled()) return ""
        val bytes = Http.getBytes(source) ?: return ""
        if (bytes.isEmpty() || isCancelled()) return ""
        return decodeBody(bytes)
    }

    /** 与 [Http.decodeBody] 一致的 gzip/zlib 检测, 追加裸 deflate (raw zlib) 兜底. */
    private fun decodeBody(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            return try {
                String(gunzip(bytes), Charsets.UTF_8)
            } catch (_: Exception) {
                String(bytes, Charsets.UTF_8)
            }
        }
        if (bytes.size >= 2 && bytes[0] == 0x78.toByte()) {
            return try {
                String(inflate(bytes), Charsets.UTF_8)
            } catch (_: Exception) {
                String(bytes, Charsets.UTF_8)
            }
        }
        // XML 明文或裸 deflate: 先按 XML 判定, 不是再尝试 raw inflate.
        val first = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() }
        if (first == null) return ""
        if (first.toInt().toChar() == '<') return String(bytes, Charsets.UTF_8)
        return try {
            String(inflateRaw(bytes), Charsets.UTF_8)
        } catch (_: Exception) {
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun inflate(bytes: ByteArray): ByteArray =
        InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun inflateRaw(bytes: ByteArray): ByteArray {
        val inflater = Inflater(true)
        return try {
            InflaterInputStream(ByteArrayInputStream(bytes), inflater).use { it.readBytes() }
        } finally {
            inflater.end()
        }
    }
}

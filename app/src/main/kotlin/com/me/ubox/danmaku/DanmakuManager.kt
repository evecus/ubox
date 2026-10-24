package com.me.ubox.danmaku

import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.IDisplayer
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.ui.widget.DanmakuView
import kotlin.math.abs

/** 弹幕加载状态 (供播放页弹 Toast). */
enum class DanmakuLoadStatus { IDLE, LOADING, OK, NONE, FAILED }

/**
 * 弹幕调度管理器 (对照原版 NovaBox `player/danmu/DanmuLoadController.java`):
 *
 * 与自绘版的差异: 弹幕大师自带时间轴 (内部 SystemTimer), 本类不再逐条按时投递,
 * 只需在 seek 时把库的时间轴对齐到播放位置:
 *   - [loadSource]/[loadBySearch] → IO 线程拉取解析 → 主线程 prepare;
 *   - [syncPosition] → 位置跳变 (>1s) 时 seekTo + start; 首次就绪时 start;
 *   - [setPlaying]/[setBuffering] → pause/resume 冻结库动画 (对照 startIfReady /
 *     pauseForBuffering).
 *
 * 加载序号 (loadSeq) 约定 (对齐 NovaBox DanmuLoadController):
 *   - 只在"发起一次新加载"的那一刻自增;
 *   - clear 只清数据与画面, **绝不能**动 loadSeq.
 */
object DanmakuManager {

    private var view: DanmakuView? = null
    private var context: DanmakuContext? = null

    /** prepare 完成 (库可 start). */
    private var prepared = false

    /** 已 start (库时间轴已启动); seek 后需重新 start. */
    private var started = false

    private var lastPosMs = -1L
    private var loadSeq = 0
    private var itemCount = 0

    /** 最近一次加载的弹幕源 (设置变更 reload 时复用, 对照 DanmuLoadController.danmuText). */
    private var currentSource = ""

    private val _status = MutableStateFlow(DanmakuLoadStatus.IDLE)

    /** 加载状态变化 (播放页监听后弹 Toast). */
    val status: StateFlow<DanmakuLoadStatus> = _status.asStateFlow()

    val hasDanmaku: Boolean get() = itemCount > 0
    val attached: Boolean get() = view != null

    fun attach(v: DanmakuView) {
        view = v
        context = DanmakuContext.create()
        prepared = false
        started = false
        lastPosMs = -1
        itemCount = 0
        applySettings()
    }

    fun detach() {
        view = null
        context = null
        prepared = false
        started = false
        lastPosMs = -1
        itemCount = 0
    }

    /**
     * 设置变更后热更新库参数 (对照 DanmuLoadController.applySettings):
     * 速度/透明度/字号缩放/行间距/描边/防重叠/行数/顶部边距.
     * 关闭弹幕时释放画面 (对照 releaseView).
     */
    fun applySettings() {
        val ctx = context ?: return
        val v = view ?: return
        if (!DanmuSettings.open) {
            releaseView()
            return
        }
        val s = DanmuSettings
        ctx.setScrollSpeedFactor(s.speed.toFloat())
            .setDanmakuTransparency(s.alpha.toFloat())
            .setScaleTextSize(s.sizeScale.toFloat())
        // 行间距: 库的行距由 DanmakuContext.margin 控制 (DanmakusRetainer 里
        // topPos = lastItem.getBottom() + margin), 直接用用户设置的 px 值.
        ctx.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3f)
            .setDanmakuMargin(s.lineSpacingPx)
        // 库默认允许同一行内多条弹幕重叠 (preventOverlapping 默认 null),
        // 必须显式对每种类型开启防重叠, 库才会在同一行里给弹幕排队.
        val overlapping = HashMap<Int, Boolean>(4).apply {
            put(BaseDanmaku.TYPE_SCROLL_RL, true)
            put(BaseDanmaku.TYPE_SCROLL_LR, true)
            put(BaseDanmaku.TYPE_FIX_TOP, true)
            put(BaseDanmaku.TYPE_FIX_BOTTOM, true)
        }
        ctx.preventOverlapping(overlapping)
        // 行数直接使用用户设置值: 所见即所得 (不再按 view 高度反算).
        val maxLines = HashMap<Int, Int>(4).apply {
            put(BaseDanmaku.TYPE_FIX_TOP, s.maxLine)
            put(BaseDanmaku.TYPE_SCROLL_RL, s.maxLine)
            put(BaseDanmaku.TYPE_SCROLL_LR, s.maxLine)
            put(BaseDanmaku.TYPE_FIX_BOTTOM, s.maxLine)
        }
        ctx.setMaximumLines(maxLines)
    }

    /** 自动搜索并加载弹幕 (对齐 NovaBox PlayFragment.searchDanmu + checkDanmu). */
    suspend fun loadBySearch(name: String, episode: String) {
        clear()
        if (!DanmuSettings.open) return
        if (name.trim().isEmpty()) return
        // 先清空, 再取号 —— 顺序颠倒会让本次加载自己把自己作废.
        val seq = ++loadSeq
        _status.value = DanmakuLoadStatus.LOADING
        val source = DanmakuApi.search(name, episode)
        if (seq != loadSeq) return
        if (source.isNullOrEmpty()) {
            _status.value = DanmakuLoadStatus.NONE
            return
        }
        load(source, seq)
    }

    /** 直接加载弹幕源 (bilibili XML 或可下载地址), 用于云搜选中的结果与爬虫自带的 danmaku 字段. */
    suspend fun loadSource(source: String) {
        clear()
        if (!DanmuSettings.open) return
        val mySeq = ++loadSeq
        _status.value = DanmakuLoadStatus.LOADING
        load(source, mySeq)
    }

    private suspend fun load(source: String, seq: Int) {
        val v = view ?: return
        val ctx = context ?: return
        currentSource = source.trim()
        val parser = try {
            DanmakuParser.create(source) { seq != loadSeq }
        } catch (_: Exception) {
            null
        }
        if (seq != loadSeq) return
        if (parser == null) {
            _status.value = DanmakuLoadStatus.FAILED
            return
        }
        itemCount = parser.itemCount
        if (itemCount <= 0) {
            _status.value = DanmakuLoadStatus.NONE
            return
        }
        // 主线程 prepare (与 NovaBox 的 danmuView.post 一致).
        withContext(Dispatchers.Main) {
            if (seq != loadSeq) return@withContext
            try {
                v.release()
                v.prepare(parser, ctx)
                // clear() 的 GONE 是 post 的, 可能晚于本处的 prepare 执行, 必须显式设回
                // (对照 DanmuLoadController.prepare/startIfReady 里的 setVisibility).
                v.visibility = View.VISIBLE
                prepared = true
                started = false
                lastPosMs = -1
                _status.value = DanmakuLoadStatus.OK
            } catch (_: Throwable) {
                prepared = false
                _status.value = DanmakuLoadStatus.FAILED
            }
        }
    }

    /**
     * 清空当前弹幕 (换集/重新加载前调用).
     * 注意: 这里不碰 loadSeq —— 作废进行中的加载由"发起新加载时自增"完成.
     */
    fun clear() {
        itemCount = 0
        currentSource = ""
        prepared = false
        started = false
        lastPosMs = -1
        releaseView()
        if (_status.value != DanmakuLoadStatus.IDLE) {
            _status.value = DanmakuLoadStatus.IDLE
        }
    }

    /**
     * 播放位置同步: 位置跳变 (seek) 时把库时间轴对齐;
     * 首次就绪 (playing) 时 start. 库内部按真实时钟推进, 无需逐帧喂位置.
     */
    fun syncPosition(positionMs: Long, playing: Boolean) {
        val v = view ?: return
        if (!prepared || !DanmuSettings.open) return
        val seeked = lastPosMs < 0 || abs(positionMs - lastPosMs) > 1000
        when {
            seeked -> {
                v.seekTo(positionMs)
                if (playing) {
                    v.visibility = View.VISIBLE
                    v.start(positionMs)
                    started = true
                }
                lastPosMs = positionMs
            }
            !started && playing -> {
                v.visibility = View.VISIBLE
                v.start(positionMs)
                started = true
                lastPosMs = positionMs
            }
        }
    }

    fun setPlaying(playing: Boolean) {
        val v = view ?: return
        if (!prepared || !DanmuSettings.open) return
        if (playing) v.resume() else v.pause()
    }

    /** 缓冲中: 冻结弹幕动画, 避免缓冲结束瞬间弹幕跳变 (对照 NovaBox pauseForBuffering). */
    fun setBuffering(buffering: Boolean) {
        val v = view ?: return
        if (!prepared || !DanmuSettings.open) return
        if (buffering) v.pause() else v.resume()
    }

    private fun releaseView() {
        val v = view ?: return
        v.post {
            try {
                v.release()
            } catch (_: Exception) {
            }
            v.visibility = View.GONE
        }
        prepared = false
        started = false
        lastPosMs = -1
    }

    /**
     * 供播放页/弹窗在设置变更后调用 (对照 DanmuLoadController.applySettings):
     * 参数热更新 + 关闭时收画面; [reload] = true 时用当前弹幕源重新 prepare
     * (对照原版 DanmuFullSettingDialog 关闭时 post reload=true:
     * 行数/边距/字号等布局参数变化需重备才生效).
     */
    suspend fun onSettingsChanged(reload: Boolean = false) {
        applySettings()
        if (reload && currentSource.isNotEmpty() && DanmuSettings.open && attached) {
            loadSource(currentSource)
        }
    }
}

package com.me.ubox.danmaku

import android.os.Looper
import android.view.View
import kotlinx.coroutines.CancellationException
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
 * 单次加载结果, 供调用方决定是否回退云搜 (对照原版 DanmuLoadController.LoadCallback.onFailed):
 *   OK          已加载并装入弹幕数据;
 *   EMPTY       取到内容但没有任何弹幕条目;
 *   FAILED      取不到内容 (网络错误/空响应/装载异常);
 *   SUPERSEDED  加载途中被新的加载/换集作废, 调用方不应再做任何事;
 *   SKIPPED     弹幕关闭或没有可搜索的条件, 没有发起加载.
 */
enum class DanmakuLoadResult { OK, EMPTY, FAILED, SUPERSEDED, SKIPPED }

/**
 * 弹幕调度管理器 (对照原版 NovaBox `player/danmu/DanmuLoadController.java`).
 *
 * 与原版 DanmuLoadController 的关键对应关系:
 *   - 原版 DanmakuView 与 Context 在播放页生命周期内始终是同一个; Compose 里 AndroidView 会因
 *     布局分支切换 (手机全屏)、弹幕开关、重组而被销毁重建. 所以这里把"弹幕数据"([items])与
 *     "视图"([view]) 解耦: 数据只在 reset/新加载时清空, 视图每次 attach 时都用已加载的数据重新
 *     prepare —— 全屏切换/重建视图不丢弹幕; 数据先于视图到达 (视图尚未创建) 也不丢.
 *   - detach 带视图身份校验: 新视图 attach 之后, 旧视图的 onRelease 不能把新视图摘掉
 *     (Compose 切换分支时顺序是 新 factory → 旧 onRelease, 旧实现无条件置空导致全屏无弹幕).
 *   - 弹幕大师自带时间轴, 本类只在 seek 跳变/首次就绪时把库时间轴对齐到播放位置.
 *
 * 加载序号 (loadSeq) 约定 (对齐 DanmuLoadController):
 *   - 只在"发起一次新加载"或 [reset] 时自增; [clear] 只清数据与画面, 绝不动 loadSeq.
 */
object DanmakuManager {

    private var view: DanmakuView? = null

    /** 库参数上下文: 全局唯一, 与具体视图无关 (视图重建后参数不丢). */
    private val context: DanmakuContext by lazy { DanmakuContext.create() }

    /** 已加载的弹幕数据 (与视图解耦, 视图重建时据此重新 prepare). */
    @Volatile
    private var items: List<DanmuItem> = emptyList()

    /** 当前视图已 prepare (库可 start). */
    private var prepared = false

    /** 当前视图库时间轴已启动; seek 后需重新 start. */
    private var started = false

    private var lastPosMs = -1L

    @Volatile
    private var loadSeq = 0

    /** 最近一次加载的弹幕源 (仅用于日志/诊断). */
    private var currentSource = ""

    private val _status = MutableStateFlow(DanmakuLoadStatus.IDLE)

    /** 加载状态变化 (播放页监听后弹 Toast). */
    val status: StateFlow<DanmakuLoadStatus> = _status.asStateFlow()

    val hasDanmaku: Boolean get() = items.isNotEmpty()
    val attached: Boolean get() = view != null

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    // ---------------------------------------------------------------- 视图生命周期

    /**
     * 视图创建 (AndroidView factory). 视图可能是首次创建, 也可能是全屏切换/弹幕重新开启后的重建:
     * 数据已加载则立即用它 prepare, 否则等待加载完成时 [load] 装入.
     */
    fun attach(v: DanmakuView) {
        view = v
        prepared = false
        started = false
        lastPosMs = -1
        applySettings()
        if (items.isNotEmpty() && DanmuSettings.open) {
            // factory 在组合阶段调用, 视图尚未挂到窗口: post 到视图队列再 prepare.
            v.post { if (view === v) prepareView(v) }
        }
    }

    /**
     * 视图销毁 (AndroidView onRelease). 只有当前视图自己才能摘掉自己:
     * 切换布局分支时新视图的 factory 先于旧视图的 onRelease 执行, 无条件置空会把新视图摘掉.
     * 弹幕数据保留, 供下一个视图 attach 时重新装载.
     */
    fun detach(v: DanmakuView) {
        if (view !== v) return
        view = null
        prepared = false
        started = false
        lastPosMs = -1
    }

    /** 播放页退出: 无条件解除视图引用. */
    fun detachAll() {
        view = null
        prepared = false
        started = false
        lastPosMs = -1
    }

    /**
     * 把当前弹幕数据装入视图 (主线程). 每次都新建解析器: 库的解析器内部缓存解析结果,
     * 释放后不可复用, 而 [NovaDanmakuParser] 只持有轻量的条目列表, 重建成本很低.
     */
    private fun prepareView(v: DanmakuView): Boolean {
        val list = items
        if (list.isEmpty() || !DanmuSettings.open) return false
        return try {
            v.release()
            v.prepare(NovaDanmakuParser(list), context)
            v.visibility = View.VISIBLE
            prepared = true
            started = false
            lastPosMs = -1
            true
        } catch (_: Throwable) {
            prepared = false
            false
        }
    }

    // ---------------------------------------------------------------- 设置

    /**
     * 设置变更后热更新库参数 (对照 DanmuLoadController.applySettings):
     * 速度/透明度/字号缩放/行间距/描边/防重叠/行数. 关闭弹幕时释放画面 (对照 releaseView).
     * 参数写在全局唯一的 [context] 上, 不依赖视图是否存在.
     */
    fun applySettings() {
        if (!DanmuSettings.open) {
            releaseView()
            return
        }
        val s = DanmuSettings
        val ctx = context
        ctx.setScrollSpeedFactor(s.speed.toFloat())
            .setDanmakuTransparency(s.alpha.toFloat())
            .setScaleTextSize(s.sizeScale.toFloat())
        // 行间距: 库的行距由 DanmakuContext.margin 控制, 直接用用户设置的 px 值.
        ctx.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3f)
            .setDanmakuMargin(s.lineSpacingPx)
        // 库默认允许同一行内多条弹幕重叠, 必须显式对每种类型开启防重叠.
        val overlapping = HashMap<Int, Boolean>(4).apply {
            put(BaseDanmaku.TYPE_SCROLL_RL, true)
            put(BaseDanmaku.TYPE_SCROLL_LR, true)
            put(BaseDanmaku.TYPE_FIX_TOP, true)
            put(BaseDanmaku.TYPE_FIX_BOTTOM, true)
        }
        ctx.preventOverlapping(overlapping)
        // 行数直接使用用户设置值: 所见即所得.
        val maxLines = HashMap<Int, Int>(4).apply {
            put(BaseDanmaku.TYPE_FIX_TOP, s.maxLine)
            put(BaseDanmaku.TYPE_SCROLL_RL, s.maxLine)
            put(BaseDanmaku.TYPE_SCROLL_LR, s.maxLine)
            put(BaseDanmaku.TYPE_FIX_BOTTOM, s.maxLine)
        }
        ctx.setMaximumLines(maxLines)
    }

    /**
     * 供播放页/弹窗在设置变更后调用 (对照 DanmuLoadController.applySettings(reload)):
     * 参数热更新 + 关闭时收画面; [reload] = true 时用**已加载的数据**重新 prepare
     * (行数/边距/字号等布局参数变化需重备才生效), 不再重新联网拉取.
     */
    suspend fun onSettingsChanged(reload: Boolean = false) {
        withContext(Dispatchers.Main) {
            applySettings()
            if (reload && DanmuSettings.open && items.isNotEmpty()) {
                view?.let { prepareView(it) }
            }
        }
    }

    // ---------------------------------------------------------------- 加载

    /**
     * 自动搜索并加载弹幕 (对齐原版 PlayActivity.searchDanmu + checkDanmu).
     * 流程: 取号 → 云接口搜索 → 解析 → 装入数据 (视图已存在则立即 prepare).
     */
    suspend fun loadBySearch(name: String, episode: String): DanmakuLoadResult {
        clear()
        if (!DanmuSettings.open) return DanmakuLoadResult.SKIPPED
        if (name.trim().isEmpty() || !DanmakuApi.canSearch()) return DanmakuLoadResult.SKIPPED
        // 先清空, 再取号 —— 顺序颠倒会让本次加载自己把自己作废.
        val seq = ++loadSeq
        _status.value = DanmakuLoadStatus.LOADING
        val source = DanmakuApi.search(name, episode)
        if (seq != loadSeq) return DanmakuLoadResult.SUPERSEDED
        if (source.isNullOrEmpty()) {
            _status.value = DanmakuLoadStatus.NONE
            return DanmakuLoadResult.EMPTY
        }
        return load(source, seq, silent = false)
    }

    /**
     * 直接加载弹幕源 (bilibili XML 或可下载地址), 用于云搜选中的结果与爬虫自带的 danmaku 字段.
     * [silent] = true 时失败不改状态 (不弹"加载失败"), 由调用方据返回值决定是否回退到云搜
     * (对照原版 checkDanmu(danmaku, onFailed → searchDanmu)).
     */
    suspend fun loadSource(source: String, silent: Boolean = false): DanmakuLoadResult {
        clear()
        if (!DanmuSettings.open) return DanmakuLoadResult.SKIPPED
        val seq = ++loadSeq
        _status.value = DanmakuLoadStatus.LOADING
        return load(source, seq, silent)
    }

    private suspend fun load(source: String, seq: Int, silent: Boolean): DanmakuLoadResult {
        currentSource = source.trim()
        val parser = try {
            DanmakuParser.create(source) { seq != loadSeq }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (seq != loadSeq) return DanmakuLoadResult.SUPERSEDED
        if (parser == null) {
            if (!silent) _status.value = DanmakuLoadStatus.FAILED
            return DanmakuLoadResult.FAILED
        }
        if (parser.itemCount <= 0) {
            if (!silent) _status.value = DanmakuLoadStatus.NONE
            return DanmakuLoadResult.EMPTY
        }
        var prepareOk = true
        withContext(Dispatchers.Main) {
            if (seq != loadSeq) return@withContext
            // 先存数据 (视图不存在 / 将被重建时, attach 会据此装载), 再装入当前视图.
            items = parser.items
            val v = view
            if (v != null) prepareOk = prepareView(v)
        }
        if (seq != loadSeq) return DanmakuLoadResult.SUPERSEDED
        if (!prepareOk) {
            if (!silent) _status.value = DanmakuLoadStatus.FAILED
            return DanmakuLoadResult.FAILED
        }
        _status.value = DanmakuLoadStatus.OK
        return DanmakuLoadResult.OK
    }

    /**
     * 清空当前弹幕 (换集/重新加载前调用).
     * 注意: 这里不碰 loadSeq —— 作废进行中的加载由"发起新加载时自增"或 [reset] 完成.
     */
    fun clear() {
        items = emptyList()
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
     * 换集/退出播放页: 作废进行中的搜索与加载并清空 (对照 DanmuLoadController.reset:
     * cancel 搜索 + loadSeq 自增 + releaseView). 与 [clear] 的区别在于会让在途的加载失效,
     * 否则上一集的搜索结果晚到会把旧弹幕装进新一集.
     */
    fun reset() {
        DanmakuApi.cancel()
        loadSeq++
        clear()
    }

    // ---------------------------------------------------------------- 播放同步

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

    /** 释放视图画面 (保留弹幕数据). 主线程直接执行, 避免 post 晚于随后的 prepare 把新弹幕又释放掉. */
    private fun releaseView() {
        val v = view ?: return
        prepared = false
        started = false
        lastPosMs = -1
        val doRelease = {
            try {
                v.release()
            } catch (_: Throwable) {
            }
            v.visibility = View.GONE
        }
        if (isMainThread()) doRelease() else v.post { doRelease() }
    }
}

package com.me.ubox.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.ubox.data.FavoriteItem
import com.me.ubox.data.Favorites
import com.me.ubox.data.History
import com.me.ubox.data.JsonUtil
import com.me.ubox.data.PlayResolver
import com.me.ubox.data.PlayUrl
import com.me.ubox.data.Site
import com.me.ubox.data.SiteFilter
import com.me.ubox.data.SpiderData
import com.me.ubox.data.WatchProgress
import com.me.ubox.data.asList
import com.me.ubox.data.parsePlayGroups
import com.me.ubox.danmaku.DanmakuLoadResult
import com.me.ubox.danmaku.DanmakuManager
import com.me.ubox.danmaku.DanmuSettings
import com.me.ubox.engine.EngineClient
import com.me.ubox.player.DecoderPrefs
import com.me.ubox.player.PlayRequest
import com.me.ubox.player.PlayerEngine
import com.me.ubox.player.PlayerEngines
import com.me.ubox.player.PlayerListener
import com.me.ubox.player.inferMimeType
import com.me.ubox.ui.AppSnackbar
import com.me.ubox.ui.player.PlayerOptions
import com.me.ubox.ui.player.VodPlayConfig
import com.me.ubox.ui.player.VodPlayConfigStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * TV 端点播页 ViewModel (单页: 影视信息 + 源 + 剧集 + 播放).
 *
 * 与老的"详情页 + 播放页"两级结构 (已删除) 的区别:
 *   - 自己拉详情 (影视信息 + 全部播放源分组), 页面内即可换源 / 换剧集;
 *   - 小屏态 (页面默认) 显示播放器 + 影视信息 + 源 + 剧集, 全屏只是布局切换 (播放器实例不变);
 *   - 进入时按观看进度续播 (首次进入自动播上次那集, 无进度则播第一集).
 */
class VodViewModel(
    private val siteKey: String,
    private val vodId: String,
    private val vodName: String,
) : ViewModel() {

    private val site: Site? =
        com.me.ubox.data.ConfigStore.loaded.value?.sites?.firstOrNull { it.key == siteKey }

    // ---------------------------------------------------------------- 详情

    private val _siteName = MutableStateFlow("")

    /** 站点显示名 (详情页加载完成后才有值, 随 vod / groups 的更新一起被 UI 读到). */
    val siteName: String get() = _siteName.value

    private val _vod = MutableStateFlow<JSONObject?>(null)
    val vod: StateFlow<JSONObject?> = _vod.asStateFlow()

    private val _groups = MutableStateFlow<List<PlayUrl>>(emptyList())
    val groups: StateFlow<List<PlayUrl>> = _groups.asStateFlow()

    private val _detailLoading = MutableStateFlow(true)
    val detailLoading: StateFlow<Boolean> = _detailLoading.asStateFlow()

    private val _detailError = MutableStateFlow("")
    val detailError: StateFlow<String> = _detailError.asStateFlow()

    private val _fav = MutableStateFlow(false)
    val fav: StateFlow<Boolean> = _fav.asStateFlow()

    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    private val _switchMessage = MutableStateFlow<String?>(null)
    val switchMessage: StateFlow<String?> = _switchMessage.asStateFlow()

    fun consumeSwitchMessage() { _switchMessage.value = null }

    // ---------------------------------------------------------------- 选中

    private val _flagIndex = MutableStateFlow(0)
    val flagIndex: StateFlow<Int> = _flagIndex.asStateFlow()

    private val _epIndex = MutableStateFlow(0)
    val epIndex: StateFlow<Int> = _epIndex.asStateFlow()

    // ---------------------------------------------------------------- 播放器

    /**
     * 播放内核: 按设置页"解码方式"选择 ExoPlayer 或 IjkPlayer (PlayerEngines.create),
     * 上层只依赖 [PlayerEngine] 接口.
     * var: 设置页换内核 (ExoPlayer ↔ IjkPlayer) 时整体重建 (见 [watchDecoderMode]).
     */
    private var engine: PlayerEngine = PlayerEngines.create()

    /** 当前媒体项 (换内核后按它续播) 与已挂载的表面. */
    private var currentRequest: PlayRequest? = null
    private var surface: android.view.Surface? = null

    /** 最近一次创建内核时的解码方式 (用于识别"内核类别是否变了"). */
    private var currentMode = DecoderPrefs.current

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _bufferedMs = MutableStateFlow(0L)
    val bufferedMs: StateFlow<Long> = _bufferedMs.asStateFlow()

    // ---- 按影片持久化的播放配置 (倍速 / 画面 / 片头片尾) ----
    private val cfg0 = VodPlayConfigStore.load(siteKey, vodId)

    private val _speed = MutableStateFlow(cfg0.speed)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _scaleType = MutableStateFlow(cfg0.scale)
    val scaleType: StateFlow<Int> = _scaleType.asStateFlow()

    private val _skipStartSec = MutableStateFlow(cfg0.skipStartSec)
    val skipStartSec: StateFlow<Int> = _skipStartSec.asStateFlow()

    private val _skipEndSec = MutableStateFlow(cfg0.skipEndSec)
    val skipEndSec: StateFlow<Int> = _skipEndSec.asStateFlow()

    /** 长按 3x 加速中. */
    private val _boosting = MutableStateFlow(false)
    val boosting: StateFlow<Boolean> = _boosting.asStateFlow()

    private val _videoWidth = MutableStateFlow(0)
    val videoWidth: StateFlow<Int> = _videoWidth.asStateFlow()

    private val _videoHeight = MutableStateFlow(0)
    val videoHeight: StateFlow<Int> = _videoHeight.asStateFlow()

    private val _videoAspectRatio = MutableStateFlow(0f)
    val videoAspectRatio: StateFlow<Float> = _videoAspectRatio.asStateFlow()

    private var startSkipApplied = false
    private var endSkipFired = false
    private var pendingSeekMs = -1L
    private var restorePos = 0
    private var restoreDone = false
    private var lastSavedMs = 0
    private var lastSaveAt = 0L
    private var danmakuJob: Job? = null
    private var episodeDanmakuSource = ""
    private var pollJob: Job? = null

    val title: String get() = JsonUtil.optStringOrNull(_vod.value, "vod_name") ?: vodName

    val cover: String get() = JsonUtil.optStringOrNull(_vod.value, "vod_pic") ?: ""

    /** 当前集名 (剧集名为空时回落到"第N集"). */
    fun epName(index: Int = _epIndex.value): String {
        val g = _groups.value.getOrNull(_flagIndex.value) ?: return "第${index + 1}集"
        val n = g.eps.getOrNull(index)
        return if (n.isNullOrEmpty()) "第${index + 1}集" else n
    }

    /** 内核事件 → 界面状态 (换内核时挂到新实例上, 所以单独提出来). */
    private val engineListener = object : PlayerListener {
        override fun onPlayingChanged(playing: Boolean) {
            _playing.value = playing
            DanmakuManager.setPlaying(playing)
        }

        override fun onBuffering(buffering: Boolean) {
            _buffering.value = buffering
            DanmakuManager.setBuffering(buffering)
        }

        override fun onReady() {
            // 起始位置 (续播/片头) 由内核在就绪后自行 seek, 这里只同步标志与时长.
            _durationMs.value = engine.duration
            if (pendingSeekMs >= 0) {
                pendingSeekMs = -1
                restoreDone = true
            }
            startSkipApplied = true
        }

        override fun onEnded() {
            playNext()
        }

        override fun onError(code: Int, message: String) {
            _loading.value = false
            _error.value = message
        }

        override fun onVideoSize(width: Int, height: Int, pixelRatio: Float) {
            if (width > 0 && height > 0) {
                _videoWidth.value = width
                _videoHeight.value = height
                _videoAspectRatio.value = width.toFloat() * pixelRatio / height
            }
        }

        override fun onNotice(message: String) {
            AppSnackbar.show(message)
        }
    }

    init {
        engine.setSpeed(cfg0.speed)
        engine.listener = engineListener
        watchDecoderMode()
        startPolling()
        loadDetail()
    }

    /**
     * 设置页改了解码方式: 换内核类别 (ExoPlayer ↔ IjkPlayer) 时无法在实例上改,
     * 释放旧内核 → 建新内核 → 从当前位置续播同一地址, 让设置立即生效.
     * 同一内核内切换 (如 IJK 硬解 → IJK 软解) 下次 open 即生效, 不打断当前播放.
     */
    private fun watchDecoderMode() {
        viewModelScope.launch {
            DecoderPrefs.mode.collect { mode ->
                if (mode == currentMode) return@collect
                val crossKernel = DecoderPrefs.isIjk(mode) != DecoderPrefs.isIjk(currentMode)
                currentMode = mode
                if (!crossKernel) return@collect
                rebuildEngine()
            }
        }
    }

    private fun rebuildEngine() {
        val req = currentRequest ?: return
        val pos = engine.currentPosition
        val wasPlaying = engine.isPlaying
        engine.listener = null
        engine.release()
        engine = PlayerEngines.create()
        engine.listener = engineListener
        engine.setSurface(surface)
        engine.setSpeed(if (_boosting.value) PlayerOptions.BOOST_SPEED else _speed.value)
        // 换内核不重走片头/续播逻辑, 直接从当前位置继续.
        pendingSeekMs = -1
        startSkipApplied = true
        _error.value = ""
        engine.open(req.copy(startMs = pos))
        if (wasPlaying) engine.play()
    }

    // ================= 详情加载 =================

    fun loadDetail() {
        viewModelScope.launch {
            _detailLoading.value = true
            _detailError.value = ""
            try {
                val json = SpiderData.detail(siteKey, vodId)
                val list = asList(json.opt("list"))
                if (list.isEmpty()) {
                    _detailLoading.value = false
                    _detailError.value = "详情加载失败"
                    return@launch
                }
                val vod = list.first()
                val groups = parsePlayGroups(
                    JsonUtil.optStringOrNull(vod, "vod_play_url") ?: "",
                    JsonUtil.optStringOrNull(vod, "vod_play_from") ?: "",
                )
                _vod.value = vod
                _groups.value = groups
                _siteName.value = site?.displayName ?: siteKey
                _detailLoading.value = false
                syncFav()
                autoStart(groups)
            } catch (e: Exception) {
                _detailLoading.value = false
                _detailError.value = "详情加载失败: ${e.message ?: ""}"
            }
        }
    }

    /** 进入页面: 定位到上次观看的那一集并续播, 没有记录则播第一集. */
    private suspend fun autoStart(groups: List<PlayUrl>) {
        if (groups.isEmpty()) {
            _error.value = "没有可播放的源"
            return
        }
        _flagIndex.value = 0
        var ep = 0
        var pos = 0
        val saved = WatchProgress.load(siteKey, vodId)
        if (saved != null) {
            val savedEp = saved.optString("ep") ?: ""
            val i = groups[0].eps.indexOfFirst { it == savedEp }
            if (i >= 0) {
                ep = i
                pos = saved.optInt("pos", 0)
            }
        }
        _epIndex.value = ep
        openCurrent(resumePos = pos)
    }

    private suspend fun openCurrent(resumePos: Int, startPlay: Boolean = true) {
        val site = site
        val g = _groups.value.getOrNull(_flagIndex.value)
        val i = _epIndex.value
        if (site == null) {
            _loading.value = false
            _error.value = "站点已失效 (配置可能已切换)"
            return
        }
        if (g == null || i !in g.urls.indices) {
            _loading.value = false
            _error.value = "没有可播放的地址"
            return
        }
        _loading.value = true
        _error.value = ""
        startSkipApplied = false
        endSkipFired = false
        restoreDone = false
        restorePos = if (resumePos > 3000) resumePos - 3000 else 0
        if (restorePos > 0) lastSavedMs = resumePos
        danmakuJob?.cancel()
        DanmakuManager.reset()

        val info = PlayResolver.resolve(site, g.flag, g.urls[i])
        if (info == null) {
            _loading.value = false
            _error.value = "没有可播放的地址\n(可能需要浏览器嗅探)"
            return
        }
        startDanmaku(info.danmaku)
        try {
            // 起始位置: 优先续播, 其次片头跳过. 内核就绪后自行 seek.
            val startMs = when {
                restorePos > 0 -> restorePos.toLong()
                _skipStartSec.value > 0 -> _skipStartSec.value * 1000L
                else -> 0L
            }
            pendingSeekMs = if (restorePos > 0) restorePos.toLong() else -1L
            val request = PlayRequest(
                url = info.url,
                headers = info.headers,
                mimeType = inferMimeType(info.url),
                startMs = startMs,
                live = false,
            )
            currentRequest = request
            engine.open(request)
            if (startPlay) engine.play()
            _loading.value = false
        } catch (e: Exception) {
            _loading.value = false
            _error.value = "播放初始化失败: ${e.message}"
        }
    }

    // ================= 选源 / 选集 =================

    /** 切换播放源 (线路): 保持集序号 (越界则回到第一集). */
    fun selectFlag(index: Int) {
        val groups = _groups.value
        if (index !in groups.indices || index == _flagIndex.value) return
        _flagIndex.value = index
        if (_epIndex.value >= groups[index].urls.size) _epIndex.value = 0
        viewModelScope.launch { openCurrent(resumePos = 0) }
    }

    fun selectEpisode(index: Int) {
        val g = _groups.value.getOrNull(_flagIndex.value) ?: return
        val start = index in g.urls.indices
        if (!start) return
        viewModelScope.launch {
            _epIndex.value = index
            openCurrent(resumePos = 0)
        }
    }

    fun playPrev() {
        if (_epIndex.value <= 0) return
        viewModelScope.launch {
            _epIndex.value = _epIndex.value - 1
            openCurrent(resumePos = 0)
        }
    }

    fun playNext() {
        val g = _groups.value.getOrNull(_flagIndex.value) ?: return
        if (_epIndex.value + 1 >= g.urls.size) {
            AppSnackbar.show("已经是最后一集了")
            return
        }
        viewModelScope.launch {
            _epIndex.value = _epIndex.value + 1
            openCurrent(resumePos = 0)
        }
    }

    /** 重播当前集 (不续播). */
    fun retry() {
        viewModelScope.launch { openCurrent(resumePos = 0) }
    }

    // ================= 播放控制 =================

    fun play() { engine.play() }

    fun pause() { engine.pause() }

    fun playOrPause() {
        if (engine.isPlaying) engine.pause() else engine.play()
    }

    val positionNow: Long get() = engine.currentPosition
    val durationNow: Long get() = engine.duration

    /** 是否正在播放 (遥控器 seek 提交后补一次播放用). */
    val isPlayingNow: Boolean get() = engine.isPlaying

    fun seekBy(seconds: Int) {
        val dur = engine.duration
        var target = engine.currentPosition + seconds * 1000L
        if (target < 0) target = 0
        if (dur > 0 && target > dur) target = dur
        engine.seekTo(target)
        _positionMs.value = target
    }

    fun seekTo(ms: Long) {
        engine.seekTo(ms)
        _positionMs.value = ms
    }

    private fun persistConfig() {
        VodPlayConfigStore.save(
            siteKey,
            vodId,
            VodPlayConfig(_speed.value, _scaleType.value, _skipStartSec.value, _skipEndSec.value),
        )
    }

    fun setSpeed(v: Float) {
        _speed.value = v
        if (!_boosting.value) engine.setSpeed(v)
        persistConfig()
    }

    fun setScale(type: Int) {
        _scaleType.value = type.coerceIn(0, PlayerOptions.SCALE_NAMES.size - 1)
        persistConfig()
    }

    fun markSkipStart() {
        _skipStartSec.value = (engine.currentPosition / 1000L).toInt().coerceAtLeast(0)
        persistConfig()
    }

    fun markSkipEnd() {
        val dur = engine.duration
        if (dur <= 0) return
        _skipEndSec.value = ((dur - engine.currentPosition) / 1000L).toInt().coerceAtLeast(0)
        persistConfig()
    }

    fun clearSkip() {
        _skipStartSec.value = 0
        _skipEndSec.value = 0
        persistConfig()
    }

    fun boostStart() {
        if (_boosting.value || !engine.isPlaying) return
        _boosting.value = true
        engine.setSpeed(PlayerOptions.BOOST_SPEED)
    }

    fun boostEnd() {
        if (!_boosting.value) return
        _boosting.value = false
        engine.setSpeed(_speed.value)
    }

    // ================= 弹幕 =================

    private fun startDanmaku(source: String) {
        episodeDanmakuSource = source.trim()
        launchDanmakuLoad()
    }

    private fun launchDanmakuLoad() {
        danmakuJob?.cancel()
        val src = episodeDanmakuSource
        danmakuJob = viewModelScope.launch {
            if (!DanmuSettings.open) return@launch
            if (src.isNotEmpty()) {
                val r = DanmakuManager.loadSource(src, silent = true)
                if (r == DanmakuLoadResult.FAILED || r == DanmakuLoadResult.EMPTY) {
                    DanmakuManager.loadBySearch(title, epName())
                }
            } else {
                DanmakuManager.loadBySearch(title, epName())
            }
        }
    }

    fun toggleDanmu() {
        viewModelScope.launch {
            val next = !DanmuSettings.open
            DanmuSettings.setOpen(next)
            DanmakuManager.applySettings()
            if (next && !DanmakuManager.hasDanmaku) launchDanmakuLoad()
            AppSnackbar.show(if (next) "弹幕已开启" else "弹幕已关闭")
        }
    }

    fun loadDanmakuSource(source: String) {
        danmakuJob?.cancel()
        danmakuJob = viewModelScope.launch { DanmakuManager.loadSource(source) }
    }

    // ================= 收藏 / 换源 =================

    private fun syncFav() {
        viewModelScope.launch { _fav.value = Favorites.isFav(siteKey, vodId) }
    }

    fun toggleFav() {
        val s = site ?: return
        viewModelScope.launch {
            Favorites.toggle(
                FavoriteItem(
                    siteKey = s.key,
                    siteName = s.displayName,
                    vodId = vodId,
                    name = title,
                    cover = cover,
                )
            )
            syncFav()
        }
    }

    /** 换源候选: 除当前站点外的可用源. */
    fun switchCandidates(): List<Site> {
        val loaded = com.me.ubox.data.ConfigStore.loaded.value ?: return emptyList()
        return SiteFilter.apply(loaded.sites.filter { it.isRealSource }).filter { it.key != siteKey }
    }

    /** 换源: 在目标源搜索同名影片, 精确命中才回调. */
    fun switchSource(target: Site, onMatched: (String, String, String) -> Unit) {
        val name = title
        viewModelScope.launch {
            _switching.value = true
            try {
                val result = SpiderData.search(target.key, name, "1")
                val list = com.me.ubox.data.vodList(result.opt("list"))
                val match = list.firstOrNull { it.name == name }
                if (match != null) {
                    onMatched(target.key, match.id, match.name)
                } else {
                    _switchMessage.value = "${target.displayName}未找到 \"$name\""
                }
            } finally {
                _switching.value = false
            }
        }
    }

    // ================= 轮询 (进度 / 片尾 / 弹幕) =================

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(200)
                val pos = engine.currentPosition
                _positionMs.value = pos
                val dur = engine.duration
                _durationMs.value = dur
                _bufferedMs.value = engine.bufferedPosition
                checkSkipEnd(pos, dur)
                DanmakuManager.syncPosition(pos, engine.isPlaying)
                maybeSaveProgress(pos)
            }
        }
    }

    private fun checkSkipEnd(pos: Long, dur: Long) {
        val et = _skipEndSec.value
        if (et <= 0 || endSkipFired || dur <= et * 1000L || pos <= 0) return
        val g = _groups.value.getOrNull(_flagIndex.value) ?: return
        if (_epIndex.value + 1 >= g.urls.size) return
        if (pos + et * 1000L >= dur) {
            endSkipFired = true
            AppSnackbar.show("已跳过片尾")
            playNext()
        }
    }

    private fun maybeSaveProgress(ms: Long) {
        if (!restoreDone && restorePos > 0 && ms < restorePos - 2000) return
        if (ms - lastSavedMs < 5000) return
        val now = System.currentTimeMillis()
        if (lastSavedMs > 0 && now - lastSaveAt < 10_000) return
        lastSaveAt = now
        lastSavedMs = ms.toInt()
        saveProgress(ms.toInt(), engine.duration.toInt())
    }

    private fun saveProgress(posMs: Int, durMs: Int) {
        val s = site ?: return
        val ep = epName()
        WatchProgress.save(s.key, vodId, title, ep, posMs, durMs)
        History.record(
            s.key,
            s.displayName,
            vodId,
            title,
            cover,
            ep,
            _groups.value.getOrNull(_flagIndex.value)?.urls?.getOrNull(_epIndex.value) ?: "",
            posMs,
            durMs,
        )
    }

    // ================= 生命周期 =================

    fun attachSurface(surface: android.view.Surface) {
        this.surface = surface
        engine.setSurface(surface)
    }

    fun detachSurface() {
        this.surface = null
        engine.setSurface(null)
    }

    /** 当前内核 + 解码方式 (错误提示/调试用). */
    fun engineInfo(): String = engine.describe()

    override fun onCleared() {
        pollJob?.cancel()
        EngineClient.setPlaySource("")
        val pos = engine.currentPosition.toInt()
        if (pos > 0) saveProgress(pos, engine.duration.toInt())
        danmakuJob?.cancel()
        DanmakuManager.reset()
        DanmakuManager.detachAll()
        engine.release()
        super.onCleared()
    }
}

package com.me.ubox.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.me.ubox.data.Ctx
import com.me.ubox.data.History
import com.me.ubox.data.PlayResolver
import com.me.ubox.data.Site
import com.me.ubox.data.WatchProgress
import com.me.ubox.danmaku.DanmakuLoadResult
import com.me.ubox.danmaku.DanmakuManager
import com.me.ubox.danmaku.DanmuSettings
import com.me.ubox.engine.EngineClient
import com.me.ubox.player.PlayerDataSourceFactory
import com.me.ubox.player.PlayerHeaders
import com.me.ubox.player.inferMimeType
import com.me.ubox.ui.AppSnackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 播放页 ViewModel (对照 Flutter 版 player_page.dart):
 * ExoPlayer (media3) 播放内核 + 续播 + 下集 + 弹幕调度 + 观看进度保存.
 */
class PlayerViewModel(
    private val siteKey: String,
    private val vodId: String,
    private val vodName: String,
    private val cover: String,
    private val flag: String,
    private val epNames: List<String>,
    private val epUrls: List<String>,
    private val startIndex: Int,
) : ViewModel() {

    /** 当前站点 (配置可能在播放期间被清除, 允许为空). */
    private val site: Site? = com.me.ubox.data.ConfigStore.loaded.value?.sites?.firstOrNull { it.key == siteKey }

    /** 解码方式 (设置页 → 解码方式): 硬解 / 软解 / 自动 (先硬后软). */
    private val decoder = com.me.ubox.player.DecoderController()

    @androidx.annotation.OptIn(UnstableApi::class)
    val player: ExoPlayer = decoder.createPlayer()

    private val _idx = MutableStateFlow(startIndex.coerceIn(0, (epUrls.size - 1).coerceAtLeast(0)))
    val idx: StateFlow<Int> = _idx.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    /** TV 全屏: 控制层自动隐藏 / 遥控器快进快退提示. */
    private val _tvOverlay = MutableStateFlow(true)
    val tvOverlay: StateFlow<Boolean> = _tvOverlay.asStateFlow()

    private val _seekHint = MutableStateFlow("")
    val seekHint: StateFlow<String> = _seekHint.asStateFlow()

    /** 视频宽高比 (0 = 未知, 铺满). */
    private val _videoAspectRatio = MutableStateFlow(0f)
    val videoAspectRatio: StateFlow<Float> = _videoAspectRatio.asStateFlow()

    private var restorePos = 0
    private var restoreDone = false

    /** 待执行的续播 seek (媒体就绪后生效, 对照 Flutter 版 duration 已知再 seek). */
    private var pendingSeekMs = -1L
    private var lastSavedMs = 0
    private var lastSaveAt = 0L

    /** 当前集是否已触发过弹幕搜索 (设置里开启弹幕后据此补搜). */
    private var danmakuSearched = false

    /** 当前集爬虫 playerContent 自带的弹幕源 (空 = 没有); 弹幕关→开时复用, 不丢掉自带弹幕. */
    private var episodeDanmakuSource = ""

    /** 当前集的弹幕加载任务: 换集/重新触发时取消上一个, 避免旧集结果晚到. */
    private var danmakuJob: kotlinx.coroutines.Job? = null

    val epName: String
        get() = if (_idx.value in epNames.indices) epNames[_idx.value] else "第${_idx.value + 1}集"

    private var pollJob: kotlinx.coroutines.Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playing.value = isPlaying
                DanmakuManager.setPlaying(isPlaying)
            }

            override fun onPlaybackStateChanged(state: Int) {
                _buffering.value = state == Player.STATE_BUFFERING
                DanmakuManager.setBuffering(state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) {
                    _durationMs.value = player.duration.coerceAtLeast(0L)
                    if (pendingSeekMs >= 0) {
                        player.seekTo(pendingSeekMs)
                        pendingSeekMs = -1
                        restoreDone = true
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 自动模式: 解码器相关错误先切软解重试, 不算失败.
                if (decoder.onPlayerError(player, error)) return
                _loading.value = false
                val cause = generateSequence<Throwable>(error) { it.cause }.last()
                _error.value = "播放初始化失败: ${error.errorCodeName}\n" +
                    "${cause.javaClass.simpleName}: ${cause.message ?: ""}\n" +
                    "解码方式: ${decoder.describe()}\n" +
                    player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                when (decoder.onTracks(player, tracks)) {
                    com.me.ubox.player.DecoderController.TracksCheck.UNSUPPORTED -> {
                        // 硬解/软解模式没有可用解码器: 不要悄悄只放声音, 明确报错.
                        player.stop()
                        _loading.value = false
                        _error.value = decoder.unsupportedMessage()
                    }
                    else -> Unit
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    _videoAspectRatio.value =
                        videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
        })
        startPolling()
        viewModelScope.launch {
            openIndex(_idx.value, resume = true)
        }
    }

    /** 位置/播放状态轮询: 驱动弹幕按时加入 + 观看进度节流保存. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(200)
                val pos = player.currentPosition
                _positionMs.value = pos
                _durationMs.value = player.duration.coerceAtLeast(0L)
                // 弹幕大师自带时间轴: 仅在 seek 跳变/首次就绪时对齐 (见 DanmakuManager).
                DanmakuManager.syncPosition(pos, player.isPlaying)
                maybeSaveProgress(pos)
            }
        }
    }

    /** 对照 Flutter 版 _bindStreams 的进度保存节流逻辑. */
    private fun maybeSaveProgress(ms: Long) {
        if (!restoreDone && restorePos > 0 && ms < restorePos - 2000) return
        if (ms - lastSavedMs < 5000) return
        val now = System.currentTimeMillis()
        if (lastSavedMs > 0 && now - lastSaveAt < 10_000) return
        lastSaveAt = now
        lastSavedMs = ms.toInt()
        val dur = player.duration.coerceAtLeast(0L).toInt()
        saveProgress(ms.toInt(), dur)
    }

    private fun saveProgress(posMs: Int, durMs: Int) {
        val site = site ?: return
        WatchProgress.save(site.key, vodId, vodName, epName, posMs, durMs)
        History.record(
            site.key,
            site.name,
            vodId,
            vodName,
            cover,
            epName,
            epUrls.getOrElse(_idx.value) { "" },
            posMs,
            durMs,
        )
    }

    /** 打开某一集. [resume] = 首次进入时恢复播放进度. */
    suspend fun openIndex(index: Int, resume: Boolean = false) {
        if (index !in epUrls.indices) return
        _idx.value = index
        _loading.value = true
        _error.value = ""
        restorePos = 0
        restoreDone = false
        danmakuSearched = false
        // 换集: 重置弹幕 (对照原版 PlayActivity.resetDanmuState → DanmuLoadController.reset):
        // 取消上一集在途的搜索/加载并清空画面. 只 clear 不 reset 的话, 上一集的搜索结果
        // 晚到时会把旧弹幕装进新一集.
        danmakuJob?.cancel()
        DanmakuManager.reset()
        val site = site
        if (site == null) {
            _loading.value = false
            _error.value = "站点已失效 (配置可能已切换)"
            return
        }
        val info = PlayResolver.resolve(site, flag, epUrls[index])
        if (info == null) {
            _loading.value = false
            _error.value = "没有可播放的地址\n(可能需要浏览器嗅探, 目前无法 support)"
            return
        }
        startDanmaku(info.danmaku)
        if (resume) {
            val saved = WatchProgress.load(site.key, vodId)
            if (saved != null) {
                val savedEp = saved.optString("ep") ?: ""
                val pos = saved.optInt("pos", 0)
                if (savedEp == epName && pos > 5000) {
                    restorePos = pos - 3000
                    lastSavedMs = pos
                } else {
                    restorePos = 0
                }
            }
        }
        try {
            PlayerHeaders.current = info.headers
            // 本地代理地址 (do=m3u8/do=live) 与 .m3u8 直链必须显式声明 HLS:
            // DefaultMediaSourceFactory 按 URI path 扩展名推断, 代理地址的 path 是
            // /proxy, 不指定会被当成 progressive 流导致 extractor 解析失败.
            val mediaItem = MediaItem.Builder()
                .setUri(info.url)
                .apply { inferMimeType(info.url)?.let { setMimeType(it) } }
                .build()
            decoder.newItem()
            player.setMediaItem(mediaItem)
            player.prepare()
            if (restorePos > 0) {
                // 就绪前 seek 可能被忽略: 先记下, STATE_READY 时再定位 (对照 Flutter 版
                // "Restore saved position once duration becomes known").
                pendingSeekMs = restorePos.toLong()
            }
            player.play()
            _loading.value = false
        } catch (e: Exception) {
            _loading.value = false
            _error.value = "播放初始化失败: ${e.message}"
        }
    }

    /**
     * 弹幕入口 (对照原版 PlayActivity 播放信息回调里的弹幕分支):
     *
     *   if (danmaku.isEmpty()) { checkDanmu(""); searchDanmu(""); }
     *   else checkDanmu(danmaku, onFailed = { searchDanmu("") });
     *
     *   - [source] 非空 = 爬虫自带弹幕: 先直接加载; **加载失败 (拉取失败/解析为空) 回退云搜**;
     *   - 为空 = 用 片名 + 集名 走内置/自定义接口自动搜索.
     * 以前自带弹幕失败后没有回退, 表现为"只有手动云搜才有弹幕".
     */
    private fun startDanmaku(source: String) {
        episodeDanmakuSource = source.trim()
        danmakuSearched = true
        launchDanmakuLoad()
    }

    private fun launchDanmakuLoad() {
        danmakuJob?.cancel()
        val src = episodeDanmakuSource
        danmakuJob = viewModelScope.launch {
            // 弹幕关闭时不加载; 之后开启走 toggleDanmu → 再次进入这里.
            if (!DanmuSettings.open) return@launch
            if (src.isNotEmpty()) {
                val r = DanmakuManager.loadSource(src, silent = true)
                // SUPERSEDED = 已被换集/手动云搜作废, 不能再回退; 其余失败都回退云搜.
                if (r == DanmakuLoadResult.FAILED || r == DanmakuLoadResult.EMPTY) {
                    DanmakuManager.loadBySearch(vodName, epName)
                }
            } else {
                DanmakuManager.loadBySearch(vodName, epName)
            }
        }
    }

    fun playNext() {
        if (_idx.value + 1 >= epUrls.size) return
        viewModelScope.launch { openIndex(_idx.value + 1) }
    }

    /** 重试当前集: 重新解析加载 (对照 Flutter 版重试按钮 _openIndex(_idx, resume: false)). */
    fun retry() {
        viewModelScope.launch { openIndex(_idx.value, resume = false) }
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun playOrPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(seconds: Int) {
        val dur = player.duration.coerceAtLeast(0L)
        var target = player.currentPosition + seconds * 1000L
        if (target < 0) target = 0
        if (dur > 0 && target > dur) target = dur
        player.seekTo(target)
        _seekHint.value = "${if (seconds > 0) "⏩" else "⏪"} ${fmt(target)} / ${fmt(dur)}"
        viewModelScope.launch {
            delay(1200)
            _seekHint.value = ""
        }
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms)
    }

    /** TV 控制层显隐: 每次唤出都重置定时器 (对照 Flutter _showTvOverlay 的 timer.cancel). */
    private var tvOverlayJob: kotlinx.coroutines.Job? = null

    fun showTvOverlay() {
        _tvOverlay.value = true
        tvOverlayJob?.cancel()
        tvOverlayJob = viewModelScope.launch {
            delay(8000)
            _tvOverlay.value = false
        }
    }

    fun hideTvOverlay() {
        tvOverlayJob?.cancel()
        _tvOverlay.value = false
    }

    /** 弹幕开关 (对照 NovaTV toggleDanmu: 开启时需要重新触发加载). */
    fun toggleDanmu() {
        viewModelScope.launch {
            val next = !DanmuSettings.open
            DanmuSettings.setOpen(next)
            DanmakuManager.applySettings()
            if (next && !DanmakuManager.hasDanmaku) {
                danmakuSearched = true
                launchDanmakuLoad()
            }
            // 对照 Flutter 版 _showMsg(next ? '弹幕已开启' : '弹幕已关闭')
            AppSnackbar.show(if (next) "弹幕已开启" else "弹幕已关闭")
        }
    }

    /** 云搜选中结果 → 直接加载. */
    fun loadDanmakuSource(source: String) {
        // 手动选择优先: 取消仍在进行的自动加载 (loadSource 取号后旧加载也会被作废).
        danmakuJob?.cancel()
        danmakuJob = viewModelScope.launch {
            DanmakuManager.loadSource(source)
        }
    }

    fun attachSurface(surface: android.view.Surface) {
        player.setVideoSurface(surface)
    }

    fun detachSurface() {
        player.setVideoSurface(null)
    }

    private fun fmt(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val mm = m.toString().padStart(2, '0')
        val ss = s.toString().padStart(2, '0')
        return if (h > 0) "$h:$mm:$ss" else "$m:$ss"
    }

    override fun onCleared() {
        pollJob?.cancel()
        // 对照原版播放页退出: setCurrentPlaySourceKey("")
        EngineClient.setPlaySource("")
        val pos = player.currentPosition.toInt()
        if (pos > 0) {
            saveProgress(pos, player.duration.coerceAtLeast(0L).toInt())
        }
        danmakuJob?.cancel()
        DanmakuManager.reset()
        DanmakuManager.detachAll()
        player.release()
        super.onCleared()
    }
}

package com.novabox.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.novabox.data.Ctx
import com.novabox.data.History
import com.novabox.data.PlayResolver
import com.novabox.data.Site
import com.novabox.data.WatchProgress
import com.novabox.danmaku.DanmakuManager
import com.novabox.danmaku.DanmuSettings
import com.novabox.engine.EngineClient
import com.novabox.player.PlayerDataSourceFactory
import com.novabox.player.PlayerHeaders
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
    private val site: Site? = com.novabox.data.ConfigStore.loaded.value?.sites?.firstOrNull { it.key == siteKey }

    @androidx.annotation.OptIn(UnstableApi::class)
    val player: ExoPlayer = ExoPlayer.Builder(Ctx.app)
        .setMediaSourceFactory(DefaultMediaSourceFactory(PlayerDataSourceFactory))
        .build()

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
                _loading.value = false
                _error.value = "播放初始化失败: ${error.errorCodeName}"
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    _videoAspectRatio.value = videoSize.width.toFloat() / videoSize.height
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
        // 换集: 重置弹幕 (对照 NovaTV 的 resetDanmuState / check("")).
        // 注意这里不再无脑发起云搜 —— 对齐 NovaBox PlayFragment:
        //   爬虫 playerContent 自带了 danmaku 就直接用它; 只有没带时才云搜.
        DanmakuManager.clear()
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
            val mediaItem = MediaItem.Builder()
                .setUri(info.url)
                .build()
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
     * 弹幕入口 (对照 NovaBox PlayFragment 的 searchDanmu / checkDanmu):
     *   - [source] 非空 = 爬虫自带弹幕地址, 直接加载;
     *   - 为空 = 用 片名 + 集名 走内置/自定义接口自动搜索.
     */
    private fun startDanmaku(source: String) {
        danmakuSearched = true
        viewModelScope.launch {
            if (source.trim().isNotEmpty()) {
                DanmakuManager.loadSource(source.trim())
            } else {
                DanmakuManager.loadBySearch(vodName, epName)
            }
        }
    }

    fun playNext() {
        if (_idx.value + 1 >= epUrls.size) return
        viewModelScope.launch { openIndex(_idx.value + 1) }
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

    fun showTvOverlay() {
        _tvOverlay.value = true
        viewModelScope.launch {
            delay(8000)
            _tvOverlay.value = false
        }
    }

    fun hideTvOverlay() {
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
                DanmakuManager.loadBySearch(vodName, epName)
            }
        }
    }

    /** 云搜选中结果 → 直接加载. */
    fun loadDanmakuSource(source: String) {
        viewModelScope.launch {
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
        DanmakuManager.clear()
        DanmakuManager.detach()
        player.release()
        super.onCleared()
    }
}

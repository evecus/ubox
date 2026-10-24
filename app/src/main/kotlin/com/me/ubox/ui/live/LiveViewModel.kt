package com.me.ubox.ui.live

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.me.ubox.data.Ctx
import com.me.ubox.data.live.LiveChannelGroup
import com.me.ubox.data.live.LiveChannelItem
import com.me.ubox.data.live.LiveStore
import com.me.ubox.player.PlayerDataSourceFactory
import com.me.ubox.player.PlayerHeaders
import com.me.ubox.player.inferMimeType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 直播页 (对照原版 LiveFragment, Flutter 版 live_page.dart 的 Compose 移植):
 * 设置页配置一个直播源地址 → 进入本页拉取直播列表 (m3u/txt/json) →
 * 分组/频道/线路三列点击播放.
 *
 * 明确不做: 回看 / 多源切换 / EPG / 画面比例 / 播放解码 / 超时换源配置
 * (超时用默认值: 缓冲 10s 换源, 出错 3.5s 换源, 对照原版默认).
 */
class LiveViewModel : ViewModel() {

    // ---- 直播列表数据 ----
    private val _groups = MutableStateFlow<List<LiveChannelGroup>>(emptyList())
    val groups: StateFlow<List<LiveChannelGroup>> = _groups.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _noSource = MutableStateFlow(false)
    val noSource: StateFlow<Boolean> = _noSource.asStateFlow()

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()

    // ---- 选中/播放状态 (对照 LiveFragment 字段) ----
    private val _selectedGroupIndex = MutableStateFlow(-1)
    val selectedGroupIndex: StateFlow<Int> = _selectedGroupIndex.asStateFlow()

    private val _selectedChannelIndex = MutableStateFlow(-1)
    val selectedChannelIndex: StateFlow<Int> = _selectedChannelIndex.asStateFlow()

    private val _playGroupIndex = MutableStateFlow(-1)
    val playGroupIndex: StateFlow<Int> = _playGroupIndex.asStateFlow()

    private val _playChannelIndex = MutableStateFlow(-1)
    val playChannelIndex: StateFlow<Int> = _playChannelIndex.asStateFlow()

    private val _currentChannel = MutableStateFlow<LiveChannelItem?>(null)
    val currentChannel: StateFlow<LiveChannelItem?> = _currentChannel.asStateFlow()

    private val passwordConfirmed = mutableSetOf<Int>()
    private var changeSourceTimes = 0
    private var needManualPlay = false

    // ---- 播放器 (ExoPlayer) ----
    /** 解码方式 (设置页 → 解码方式): 硬解 / 软解 / 自动 (先硬后软). */
    private val decoder = com.me.ubox.player.DecoderController()

    @androidx.annotation.OptIn(UnstableApi::class)
    val player: ExoPlayer = decoder.createPlayer()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    /** Video 表面是否已挂载 (首播后保持挂载, 对照 Flutter 版 _videoMounted). */
    private val _videoMounted = MutableStateFlow(false)
    val videoMounted: StateFlow<Boolean> = _videoMounted.asStateFlow()

    // ---- 手机控制浮层 / 竖屏全屏 ----
    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    private val _portraitFullscreen = MutableStateFlow(false)
    val portraitFullscreen: StateFlow<Boolean> = _portraitFullscreen.asStateFlow()

    // ---- TV 全屏 ----
    private val _tvFullscreen = MutableStateFlow(false)
    val tvFullscreen: StateFlow<Boolean> = _tvFullscreen.asStateFlow()

    private val _tvPanel = MutableStateFlow(false)
    val tvPanel: StateFlow<Boolean> = _tvPanel.asStateFlow()

    // ---- 右上角频道名角标 ----
    private val _badgeVisible = MutableStateFlow(false)
    val badgeVisible: StateFlow<Boolean> = _badgeVisible.asStateFlow()

    private val _badgeName = MutableStateFlow("")
    val badgeName: StateFlow<String> = _badgeName.asStateFlow()

    // ---- 自动换源定时器 ----
    private var bufferTimeoutJob: Job? = null
    private var errorRetryJob: Job? = null

    /** 视频宽高比 (0 = 未知). */
    private val _videoAspectRatio = MutableStateFlow(0f)
    val videoAspectRatio: StateFlow<Float> = _videoAspectRatio.asStateFlow()

    private var initialized = false

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playing.value = isPlaying
                if (isPlaying) {
                    // 对照 STATE_PLAYING: 取消换源任务并重置失败计数
                    cancelTimers()
                    changeSourceTimes = 0
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                _buffering.value = state == Player.STATE_BUFFERING
                if (state == Player.STATE_BUFFERING) {
                    // 对照 STATE_PREPARING/BUFFERING: 启动超时换源
                    scheduleBufferTimeout()
                } else if (state == Player.STATE_READY) {
                    // 对照 STATE_BUFFERED: 取消超时任务 (计数只在播放成功时重置,
                    // 出错时 buffering 也会变 false, 不能在此重置)
                    bufferTimeoutJob?.cancel()
                    bufferTimeoutJob = null
                } else if (state == Player.STATE_ENDED) {
                    // 对照 STATE_PLAYBACK_COMPLETED: 直播流结束与错误同样处理
                    scheduleErrorRetry()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 自动模式: 解码器相关错误先切软解重试, 不算失败.
                if (decoder.onPlayerError(player, error)) return
                // 对照 STATE_ERROR: 3.5s 后换源
                scheduleErrorRetry()
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // 硬解/软解模式没有可用的视频解码器 -> 按失败处理 (同样 3.5s 后换源), 不悄悄只放声音.
                if (decoder.onTracks(player, tracks) ==
                    com.me.ubox.player.DecoderController.TracksCheck.UNSUPPORTED
                ) {
                    scheduleErrorRetry()
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    _videoAspectRatio.value = videoSize.width.toFloat() / videoSize.height
                }
            }
        })
    }

    // ================= 数据加载 =================

    fun bootstrap() {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            LiveStore.init()
            if (LiveStore.liveUrl.value.isEmpty()) {
                // 对照 showNoLiveSourceTip: 未配置直播源
                _noSource.value = true
                return@launch
            }
            loadLives()
        }
    }

    fun loadLives() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = ""
            val (ok, msg, groups) = LiveStore.fetchAndParse(LiveStore.liveUrl.value)
            if (!ok) {
                _loading.value = false
                _error.value = msg
                _groups.value = emptyList()
                return@launch
            }
            _groups.value = groups
            initLiveState()
            _loading.value = false
            _error.value = ""
        }
    }

    /** 直播源地址变化 (设置页保存后直播页重载). */
    fun onLiveUrlChanged() {
        val url = LiveStore.liveUrlChanged.value
        if (url.isEmpty()) {
            _groups.value = emptyList()
            _noSource.value = true
            _error.value = ""
            resetPlayState()
            return
        }
        _noSource.value = false
        loadLives()
    }

    /** 对照 initLiveState: 定位上次频道 (仅高亮不自动播放), 没有则落到第一个无密码分组. */
    private fun initLiveState() {
        val lastChannelName = LiveStore.lastChannelName.value
        var lastGroupIndex = -1
        var lastChannelIndex = -1
        if (lastChannelName.isNotEmpty()) {
            for (g in _groups.value) {
                if (g.channels.isEmpty()) continue
                for (c in g.channels) {
                    if (c.channelName == lastChannelName) {
                        lastGroupIndex = g.groupIndex
                        lastChannelIndex = c.channelIndex
                        break
                    }
                }
                if (lastGroupIndex != -1) break
            }
        }
        if (lastGroupIndex == -1) {
            lastGroupIndex = firstNoPasswordGroup()
            if (lastGroupIndex == -1) lastGroupIndex = 0
            lastChannelIndex = 0
        }
        _selectedGroupIndex.value = lastGroupIndex
        // 密码分组不确认则不亮频道 (对照 loadChannelGroupDataOnly)
        _selectedChannelIndex.value =
            if (needPassword(lastGroupIndex)) -1 else lastChannelIndex
        _playGroupIndex.value = -1
        _playChannelIndex.value = -1
        _currentChannel.value = null
    }

    private fun resetPlayState() {
        cancelTimers()
        _selectedGroupIndex.value = -1
        _selectedChannelIndex.value = -1
        _playGroupIndex.value = -1
        _playChannelIndex.value = -1
        _currentChannel.value = null
        synchronized(passwordConfirmed) { passwordConfirmed.clear() }
        changeSourceTimes = 0
        player.stop()
        _playing.value = false
        _buffering.value = false
    }

    // ================= 播放核心 (对照 playChannel) =================

    /**
     * [changeSource] 换源 (不重置当前频道); [force] 强制重载 (刷新用).
     * @return 是否发起了播放/保持播放.
     */
    fun playChannel(groupIndex: Int, channelIndex: Int, changeSource: Boolean = false, force: Boolean = false): Boolean {
        val item = channelAt(groupIndex, channelIndex) ?: return false
        if (item.sourceNum == 0) return false
        val sameChannel = groupIndex == _playGroupIndex.value && channelIndex == _playChannelIndex.value
        if (!force &&
            !needManualPlay &&
            ((sameChannel && !changeSource) ||
                (changeSource &&
                    _currentChannel.value != null &&
                    _currentChannel.value!!.sourceNum == 1))
        ) {
            // 点击当前已选频道/唯一线路: 不重载, 暂停中则补一次播放
            if (!_playing.value) player.play()
            return true
        }
        if (changeSource && _currentChannel.value == null) return false
        needManualPlay = false
        if (!changeSource) {
            _playGroupIndex.value = groupIndex
            _playChannelIndex.value = channelIndex
            _currentChannel.value = item
            LiveStore.setLastChannelName(item.channelName)
        }
        // UI 高亮同步 (对照 playChannel 末尾的 adapter 同步)
        _selectedGroupIndex.value = _playGroupIndex.value
        _selectedChannelIndex.value = _playChannelIndex.value
        changeSourceTimes = 0
        _videoMounted.value = true
        showBadge(item.channelName)
        cancelTimers()
        openMedia(item)
        return true
    }

    private fun openMedia(item: LiveChannelItem) {
        PlayerHeaders.current = item.getHeaders()
        // 直播 .m3u8 直链若 path 不带扩展名 (http://ip:port/live/xxx 形式),
        // 会被推断成 progressive 流导致 extractor 解析失败, 显式声明 HLS.
        val mediaItem = MediaItem.Builder()
            .setUri(item.url)
            .apply { inferMimeType(item.url)?.let { setMimeType(it) } }
            .build()
        decoder.newItem()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    fun playPreSource() {
        val item = _currentChannel.value ?: return
        item.preSource()
        playChannel(_playGroupIndex.value, _playChannelIndex.value, changeSource = true)
    }

    fun playNextSource() {
        val item = _currentChannel.value ?: return
        item.nextSource()
        playChannel(_playGroupIndex.value, _playChannelIndex.value, changeSource = true)
    }

    /** 线路点击 (对照 playChannelBySource). */
    fun playChannelBySource(sourceIndex: Int) {
        val item = _currentChannel.value ?: return
        if (sourceIndex < 0 || sourceIndex >= item.sourceNum) return
        item.sourceIndex = sourceIndex
        playChannel(_playGroupIndex.value, _playChannelIndex.value, changeSource = true)
    }

    /**
     * 刷新当前频道 (对照 refreshChannel).
     * 原版走 playChannel(..., true), 但唯一线路会被短路分支挡住而不重载,
     * 这里直接 force 重载, 保证刷新按钮真的重新拉流.
     */
    fun refreshChannel() {
        if (_currentChannel.value != null) {
            playChannel(_playGroupIndex.value, _playChannelIndex.value, changeSource = true, force = true)
        }
    }

    fun clickLiveChannel(position: Int) {
        // 对照 clickLiveChannel: 取当前选中分组的第 position 个频道
        playChannel(_selectedGroupIndex.value, position)
        if (_tvFullscreen.value) hideTvPanel() // TV 全屏: 选台后收起面板, 回到纯画面
    }

    // ================= 自动换源 (对照 mConnectTimeoutChangeSourceRun) =================

    private fun onTimeoutChangeSource() {
        val item = _currentChannel.value ?: return
        // ExoPlayer 单核, 对照时跳过原版 switchLivePlayerAndReplay 的多内核切换
        changeSourceTimes++
        if (item.sourceNum == changeSourceTimes) {
            // 所有线路失败 → 组内下一个频道 (原版默认不跨组)
            changeSourceTimes = 0
            val (gi, ci) = getNextChannel(1)
            if (gi >= 0 && ci >= 0) playChannel(gi, ci)
        } else {
            playNextSource()
        }
    }

    /** 对照 getNextChannel (LIVE_CROSS_GROUP 默认 false: 组内循环). */
    private fun getNextChannel(direction: Int): Pair<Int, Int> {
        val channels = channelsOf(_playGroupIndex.value)
        if (channels.isEmpty()) return Pair(-1, -1)
        var channelIndex = _playChannelIndex.value
        if (direction > 0) {
            channelIndex++
            if (channelIndex >= channels.size) channelIndex = 0
        } else {
            channelIndex--
            if (channelIndex < 0) channelIndex = channels.size - 1
        }
        return Pair(_playGroupIndex.value, channelIndex)
    }

    private fun scheduleBufferTimeout() {
        errorRetryJob?.cancel()
        bufferTimeoutJob?.cancel()
        bufferTimeoutJob = viewModelScope.launch {
            delay(LiveStore.DEFAULT_TIMEOUT_MS.toLong())
            onTimeoutChangeSource()
        }
    }

    private fun scheduleErrorRetry() {
        bufferTimeoutJob?.cancel()
        errorRetryJob?.cancel()
        errorRetryJob = viewModelScope.launch {
            delay(LiveStore.ERROR_RETRY_DELAY_MS.toLong())
            onTimeoutChangeSource()
        }
    }

    private fun cancelTimers() {
        bufferTimeoutJob?.cancel()
        errorRetryJob?.cancel()
        bufferTimeoutJob = null
        errorRetryJob = null
    }

    // ================= 分组/密码 (对照 selectChannelGroup / showPasswordDialog) =================

    fun selectChannelGroup(groupIndex: Int) {
        if (groupIndex < 0 || groupIndex >= _groups.value.size) return
        // 已选中且无需密码 → 无操作
        if (groupIndex == _selectedGroupIndex.value && !needPassword(groupIndex)) return
        val previous = _selectedGroupIndex.value
        _selectedGroupIndex.value = groupIndex
        if (needPassword(groupIndex)) return
        // 对照 loadChannelGroupData: 换组重置频道高亮, 同组保留
        if (groupIndex != previous) _selectedChannelIndex.value = -1
    }

    /** 校验分组密码; 正确返回 true. */
    fun confirmPassword(groupIndex: Int, password: String): Boolean {
        if (groupIndex < 0 || groupIndex >= _groups.value.size) return false
        if (password == _groups.value[groupIndex].groupPassword) {
            passwordConfirmed.add(groupIndex)
            // 对照 loadChannelGroupDataAndPlay(groupIndex, -1): 只加载不播放
            _selectedGroupIndex.value = groupIndex
            _selectedChannelIndex.value = -1
            return true
        }
        return false
    }

    fun needPassword(groupIndex: Int): Boolean {
        if (groupIndex < 0 || groupIndex >= _groups.value.size) return false
        return _groups.value[groupIndex].groupPassword.isNotEmpty() &&
            !passwordConfirmed.contains(groupIndex)
    }

    /** 对照 getLiveChannels: 密码分组未确认时返回空列表. */
    fun channelsOf(groupIndex: Int): List<LiveChannelItem> {
        if (groupIndex < 0 || groupIndex >= _groups.value.size) return emptyList()
        if (needPassword(groupIndex)) return emptyList()
        return _groups.value[groupIndex].channels
    }

    private fun channelAt(groupIndex: Int, channelIndex: Int): LiveChannelItem? {
        val channels = channelsOf(groupIndex)
        if (channelIndex < 0 || channelIndex >= channels.size) return null
        return channels[channelIndex]
    }

    private fun firstNoPasswordGroup(): Int {
        for (g in _groups.value) {
            if (g.groupPassword.isEmpty()) return g.groupIndex
        }
        return -1
    }

    // ================= 浮层 / 角标 / 全屏 =================

    private val kOverlaySeconds = 4L
    private val kBadgeSeconds = 6L

    fun toggleOverlay() {
        if (_overlayVisible.value) {
            hideOverlay()
        } else {
            _overlayVisible.value = true
            scheduleHideOverlay()
        }
    }

    private fun scheduleHideOverlay() {
        viewModelScope.launch {
            delay(kOverlaySeconds * 1000)
            if (_overlayVisible.value) _overlayVisible.value = false
        }
    }

    fun hideOverlay() {
        _overlayVisible.value = false
    }

    /** 对照 updateRightTopChannelInfo: 切台后短暂显示频道名 + 加载圈. */
    private fun showBadge(name: String) {
        _badgeName.value = name
        _badgeVisible.value = true
        viewModelScope.launch {
            delay(kBadgeSeconds * 1000)
            if (_badgeVisible.value) _badgeVisible.value = false
        }
    }

    fun togglePlayPause() {
        if (needManualPlay) {
            // 切走过 Tab 播放器已 stop: 必须重新 setUrl+play (对照 ivPlayPauseCenter)
            if (_currentChannel.value != null) {
                playChannel(_playGroupIndex.value, _playChannelIndex.value)
            }
        } else if (_playing.value) {
            player.pause()
        } else if (_currentChannel.value != null) {
            player.play()
        }
    }

    fun enterFullscreen() {
        if (_portraitFullscreen.value) return
        _portraitFullscreen.value = true
        com.me.ubox.ui.AppShell.setLiveFullscreen(true)
        viewModelScope.launch {
            delay(kOverlaySeconds * 1000)
            if (_overlayVisible.value) _overlayVisible.value = false
        }
    }

    fun exitFullscreen() {
        if (!_portraitFullscreen.value) return
        _portraitFullscreen.value = false
        _overlayVisible.value = false
        com.me.ubox.ui.AppShell.setLiveFullscreen(false)
    }

    fun toggleFullscreen() {
        if (_portraitFullscreen.value) exitFullscreen() else enterFullscreen()
    }

    // ================= 切 Tab (对照 onFragmentPause) =================

    fun onTabChanged() {
        // 用 stop 而非 pause: "准备中/缓冲中"时 pause 不生效, 加载完成会自动续播
        cancelTimers()
        needManualPlay = true
        player.stop()
        if (_portraitFullscreen.value || _tvFullscreen.value) {
            _portraitFullscreen.value = false
            _tvFullscreen.value = false
            _tvPanel.value = false
            com.me.ubox.ui.AppShell.setLiveFullscreen(false)
        }
        _playing.value = false
        _buffering.value = false
        _overlayVisible.value = false
    }

    // ================= TV 全屏 =================

    fun enterTvFullscreen() {
        if (_tvFullscreen.value) return
        _tvFullscreen.value = true
        _tvPanel.value = false
        // 通知外壳 (MainShell) 收起 TV 左侧导航与标题栏.
        com.me.ubox.ui.AppShell.setLiveFullscreen(true)
    }

    fun exitTvFullscreen() {
        if (!_tvFullscreen.value) return
        _tvFullscreen.value = false
        _tvPanel.value = false
        com.me.ubox.ui.AppShell.setLiveFullscreen(false)
    }

    fun showTvPanel() {
        if (_tvPanel.value) return
        _tvPanel.value = true
    }

    fun hideTvPanel() {
        _tvPanel.value = false
    }

    fun playAdjacentChannel(direction: Int) {
        if (_currentChannel.value == null) return
        val (gi, ci) = getNextChannel(direction)
        if (gi < 0 || ci < 0) return
        playChannel(gi, ci)
    }

    fun playPreSourceManual() {
        if (_currentChannel.value == null) return
        playPreSource()
    }

    fun playNextSourceManual() {
        if (_currentChannel.value == null) return
        playNextSource()
    }

    fun isCurrentLiveChannelValid(): Boolean = _currentChannel.value != null

    /** 视频表面就绪: 把播放器绑到 SurfaceView. */
    fun attachSurface(surface: android.view.Surface) {
        player.setVideoSurface(surface)
        // 已停止 (切 Tab 后) 且当前有频道: 重新准备
        if (needManualPlay && _currentChannel.value != null) {
            playChannel(_playGroupIndex.value, _playChannelIndex.value)
        }
    }

    fun detachSurface() {
        player.setVideoSurface(null)
    }

    override fun onCleared() {
        cancelTimers()
        player.release()
        super.onCleared()
    }
}

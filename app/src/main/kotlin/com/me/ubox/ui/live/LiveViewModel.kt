package com.me.ubox.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.ubox.data.live.LiveChannelGroup
import com.me.ubox.data.live.LiveChannelItem
import com.me.ubox.data.live.LiveStore
import com.me.ubox.player.PlayRequest
import com.me.ubox.player.PlayerEngine
import com.me.ubox.player.PlayerEngines
import com.me.ubox.player.PlayerListener
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

    // ---- 播放器 ----
    /**
     * 播放内核: 按设置页"解码方式"选择 ExoPlayer 或 IjkPlayer.
     * 直播多为 flv/rtmp/m3u8, IJK 对 flv/rtmp 支持更好 (ExoPlayer 不支持 flv).
     * var: 设置页换内核 (ExoPlayer ↔ IjkPlayer) 时整体重建 (见 [watchDecoderMode]).
     */
    private var engine: PlayerEngine = PlayerEngines.create()

    /** 当前媒体项 (换内核后按它重连) 与已挂载的表面. */
    private var currentRequest: PlayRequest? = null
    private var surface: android.view.Surface? = null

    /** 最近一次创建内核时的解码方式. */
    private var currentMode = com.me.ubox.player.DecoderPrefs.current

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    /** Video 表面是否已挂载 (首播后保持挂载, 对照 Flutter 版 _videoMounted). */
    private val _videoMounted = MutableStateFlow(false)
    val videoMounted: StateFlow<Boolean> = _videoMounted.asStateFlow()

    // ---- 手机/平板控制浮层 (对照 NovaBox live_control_overlay) ----
    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    /** 手机/平板全屏: 视频铺满 + 隐藏信息栏与频道列表 (对照 NovaBox enterFullscreenMode). */
    private val _fullscreen = MutableStateFlow(false)
    val fullscreen: StateFlow<Boolean> = _fullscreen.asStateFlow()

    /** TV (遥控器) 频道面板是否显示 (对照 NovaTV ChannelListView). */
    private val _tvPanel = MutableStateFlow(false)
    val tvPanel: StateFlow<Boolean> = _tvPanel.asStateFlow()

    // ---- 画面比例: 不做设置菜单, TV 由 MENU 键循环 (0 默认/1 16:9/2 4:3/3 填充/4 原始/5 裁剪) ----
    private val _scaleType = MutableStateFlow(0)
    val scaleType: StateFlow<Int> = _scaleType.asStateFlow()

    fun cycleScale(): Int {
        _scaleType.value = (_scaleType.value + 1) % com.me.ubox.ui.player.PlayerOptions.SCALE_NAMES.size
        return _scaleType.value
    }

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

    /** 内核事件 → 界面状态. */
    private val engineListener = object : PlayerListener {
        override fun onPlayingChanged(playing: Boolean) {
            _playing.value = playing
            if (playing) {
                // 对照 STATE_PLAYING: 取消换源任务并重置失败计数
                cancelTimers()
                changeSourceTimes = 0
            }
        }

        override fun onBuffering(buffering: Boolean) {
            _buffering.value = buffering
            if (buffering) {
                // 对照 STATE_PREPARING/BUFFERING: 启动超时换源
                scheduleBufferTimeout()
            } else {
                // 对照 STATE_BUFFERED: 取消超时任务 (计数只在播放成功时重置,
                // 出错时 buffering 也会变 false, 不能在此重置)
                bufferTimeoutJob?.cancel()
                bufferTimeoutJob = null
            }
        }

        override fun onEnded() {
            // 对照 STATE_PLAYBACK_COMPLETED: 直播流结束与错误同样处理
            scheduleErrorRetry()
        }

        override fun onError(code: Int, message: String) {
            // 内核内部已处理"自动模式切软解重试"; 走到这里的都是真失败: 3.5s 后换源.
            scheduleErrorRetry()
        }

        override fun onVideoSize(width: Int, height: Int, pixelRatio: Float) {
            if (width > 0 && height > 0) {
                _videoAspectRatio.value = width.toFloat() * pixelRatio / height
            }
        }

        override fun onNotice(message: String) {
            com.me.ubox.ui.AppSnackbar.show(message)
        }
    }

    init {
        engine.listener = engineListener
        watchDecoderMode()
    }

    /**
     * 设置页改了解码方式: 换内核类别 (ExoPlayer ↔ IjkPlayer) 时重建内核并重连当前频道,
     * 让设置立即生效 (直播没有进度, 直接重连).
     */
    private fun watchDecoderMode() {
        viewModelScope.launch {
            com.me.ubox.player.DecoderPrefs.mode.collect { mode ->
                if (mode == currentMode) return@collect
                val crossKernel = com.me.ubox.player.DecoderPrefs.isIjk(mode) !=
                    com.me.ubox.player.DecoderPrefs.isIjk(currentMode)
                currentMode = mode
                if (!crossKernel) return@collect
                rebuildEngine()
            }
        }
    }

    private fun rebuildEngine() {
        val req = currentRequest ?: return
        cancelTimers()
        changeSourceTimes = 0
        engine.listener = null
        engine.release()
        engine = PlayerEngines.create()
        engine.listener = engineListener
        engine.setSurface(surface)
        _buffering.value = true
        engine.open(req)
        engine.play()
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
            val (ok, msg, groups) = LiveStore.fetchAndParse(LiveStore.liveUrl.value, useCache = true)
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
        engine.stop()
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
            if (!_playing.value) engine.play()
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
        // 直播 .m3u8 直链若 path 不带扩展名 (http://ip:port/live/xxx 形式),
        // ExoPlayer 会推断成 progressive 流导致解析失败, 显式声明 HLS (inferMimeType).
        val request = PlayRequest(
            url = item.url,
            headers = item.getHeaders(),
            mimeType = inferMimeType(item.url),
            live = true,
        )
        currentRequest = request
        engine.open(request)
        engine.play()
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
        hideTvPanel() // 选台后收起频道面板, 回到纯画面
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
            engine.pause()
        } else if (_currentChannel.value != null) {
            engine.play()
        }
    }

    fun enterFullscreen() {
        if (_fullscreen.value) return
        _fullscreen.value = true
        _overlayVisible.value = false
        com.me.ubox.ui.AppShell.setLiveFullscreen(true)
    }

    fun exitFullscreen() {
        if (!_fullscreen.value) return
        _fullscreen.value = false
        _overlayVisible.value = false
        com.me.ubox.ui.AppShell.setLiveFullscreen(false)
    }

    fun toggleFullscreen() {
        if (_fullscreen.value) exitFullscreen() else enterFullscreen()
    }

    // ================= 切 Tab (对照 onFragmentPause) =================

    fun onTabChanged() {
        // 用 stop 而非 pause: "准备中/缓冲中"时 pause 不生效, 加载完成会自动续播
        cancelTimers()
        needManualPlay = true
        engine.stop()
        if (_fullscreen.value) {
            _fullscreen.value = false
            _tvPanel.value = false
            com.me.ubox.ui.AppShell.setLiveFullscreen(false)
        }
        _playing.value = false
        _buffering.value = false
        _overlayVisible.value = false
    }

    // ================= TV (遥控器) 频道面板 =================

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
        this.surface = surface
        engine.setSurface(surface)
        // 已停止 (切 Tab 后) 且当前有频道: 重新准备
        if (needManualPlay && _currentChannel.value != null) {
            playChannel(_playGroupIndex.value, _playChannelIndex.value)
        }
    }

    fun detachSurface() {
        this.surface = null
        engine.setSurface(null)
    }

    /** 当前内核 + 解码方式 (调试/提示用). */
    fun engineInfo(): String = engine.describe()

    override fun onCleared() {
        cancelTimers()
        engine.release()
        super.onCleared()
    }
}

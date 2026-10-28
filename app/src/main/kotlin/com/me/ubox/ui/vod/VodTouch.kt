package com.me.ubox.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.me.ubox.danmaku.DanmuSettings
import com.me.ubox.data.PlayUrl
import com.me.ubox.ui.components.VideoSurfaceView
import com.me.ubox.ui.player.BoostIndicator
import com.me.ubox.ui.player.DanmakuLayer
import com.me.ubox.ui.player.EpisodePanel
import com.me.ubox.ui.player.PlayerGestureLayer
import com.me.ubox.ui.player.PlayerOsdView
import com.me.ubox.ui.player.PlayerUiState
import com.me.ubox.ui.player.StatusView
import com.me.ubox.ui.player.ToolChip
import kotlinx.coroutines.delay

/**
 * 手机 / 平板的点播单页 (对照 NovaBox 的 DetailActivity + PlayFragment):
 *
 *   手机竖屏 = 上 16:9 播放器 (预览窗) + 下 影视信息 / 源 / 剧集 (竖向滚动);
 *   平板     = 左 73% (16:9 播放器) | 右 27% (影视信息 / 源 / 剧集)
 *             (对照 NovaBox layout-sw600dp/activity_detail.xml 的 73 / 27 分栏);
 *   全屏     = 播放器铺满 + 手势 (进度 / 亮度 / 音量 / 双击暂停 / 长按 3x) + 锁屏
 *             + 底部功能条 + 右侧选集侧栏 (对照 player_vod_control_view.xml).
 *
 * 全屏与小屏共用同一个视频容器节点, 切换时 SurfaceView 与弹幕视图不重建.
 */
@Composable
internal fun VodTouchScreen(
    vm: VodViewModel,
    ui: PlayerUiState,
    tablet: Boolean,
    onBack: () -> Unit,
    onSpeed: () -> Unit,
    onScale: () -> Unit,
    onSkip: () -> Unit,
    onDanmuSetting: () -> Unit,
    onDanmuSearch: () -> Unit,
    onSwitchMenu: () -> Unit,
) {
    val detailLoading by vm.detailLoading.collectAsStateWithLifecycle()
    val detailError by vm.detailError.collectAsStateWithLifecycle()
    val vod by vm.vod.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val flagIndex by vm.flagIndex.collectAsStateWithLifecycle()
    val epIndex by vm.epIndex.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val buffering by vm.buffering.collectAsStateWithLifecycle()
    val positionMs by vm.positionMs.collectAsStateWithLifecycle()
    val durationMs by vm.durationMs.collectAsStateWithLifecycle()
    val bufferedMs by vm.bufferedMs.collectAsStateWithLifecycle()
    val boosting by vm.boosting.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val scaleType by vm.scaleType.collectAsStateWithLifecycle()
    val skipStart by vm.skipStartSec.collectAsStateWithLifecycle()
    val skipEnd by vm.skipEndSec.collectAsStateWithLifecycle()
    val aspect by vm.videoAspectRatio.collectAsStateWithLifecycle()
    val vw by vm.videoWidth.collectAsStateWithLifecycle()
    val vh by vm.videoHeight.collectAsStateWithLifecycle()
    val fav by vm.fav.collectAsStateWithLifecycle()
    val switching by vm.switching.collectAsStateWithLifecycle()
    val danmuRevision by DanmuSettings.revision.collectAsStateWithLifecycle()

    val fullscreen = ui.fullscreen
    val split = tablet && !fullscreen
    val g = groups.getOrNull(flagIndex)
    val eps = g?.eps.orEmpty()
    val hasNext = g != null && epIndex + 1 < g.urls.size
    val title = "${vm.title} · ${vm.epName()}"
    var previewMs by remember { mutableStateOf<Long?>(null) }
    var showEpisodes by remember { mutableStateOf(false) }

    // 控制层自动隐藏 (对照 NovaBox): 小窗 3 秒, 全屏 8 秒; 选集侧栏打开时不计时.
    LaunchedEffect(ui.overlay, ui.overlayTick, playing, fullscreen, showEpisodes) {
        if (ui.overlay && playing && !showEpisodes) {
            delay(if (fullscreen) 8000 else 3000)
            ui.hideOverlay()
        }
    }
    LaunchedEffect(error) { if (error.isNotEmpty()) ui.showOverlay() }
    LaunchedEffect(ui.lockHint, ui.lockHintTick) {
        if (ui.lockHint) {
            delay(3000)
            ui.lockHint = false
        }
    }
    LaunchedEffect(fullscreen) {
        if (!fullscreen) {
            ui.locked = false
            showEpisodes = false
        }
    }

    // 后注册的优先: 选集侧栏 > 全屏 (锁屏时返回键只提示锁).
    BackHandler(enabled = showEpisodes) { showEpisodes = false }
    BackHandler(enabled = fullscreen) {
        if (ui.locked) ui.flashLock() else ui.fullscreen = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (fullscreen) Color.Black else MaterialTheme.colorScheme.background)
            .then(
                if (fullscreen) Modifier
                else Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            ),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ---------------- 左/上: 视频 + (手机) 信息 ----------------
            Column(
                modifier = Modifier
                    .weight(if (split) 0.73f else 1f)
                    .fillMaxHeight()
                    .then(if (split) Modifier.background(Color.Black) else Modifier),
                // 平板左栏: 播放器居中 (对照 NovaBox sw600dp 左栏 gravity=center).
                verticalArrangement = if (split) Arrangement.Center else Arrangement.Top,
            ) {
                Box(
                    modifier = (
                        if (fullscreen) Modifier.fillMaxSize()
                        else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        ).background(Color.Black),
                ) {
                    VideoSurfaceView(
                        modifier = Modifier.fillMaxSize(),
                        aspectRatio = if (aspect > 0f) aspect else null,
                        scaleType = scaleType,
                        videoWidthPx = vw,
                        videoHeightPx = vh,
                        onSurfaceReady = { view -> vm.attachSurface(view.holder.surface) },
                        onSurfaceDispose = { vm.detachSurface() },
                    )
                    DanmakuLayer(modifier = Modifier.fillMaxSize())
                    PlayerGestureLayer(
                        modifier = Modifier.matchParentSize(),
                        enabled = !ui.locked,
                        getPositionMs = { vm.positionNow },
                        getDurationMs = { vm.durationNow },
                        onSeekCommit = { vm.seekTo(it); ui.poke() },
                        onTap = {
                            if (ui.locked) {
                                ui.flashLock()
                                // 小屏锁定后浮层就是解锁入口 (全屏靠左侧锁按钮).
                                if (!fullscreen) ui.showOverlay()
                            } else {
                                ui.toggleOverlay()
                            }
                        },
                        onDoubleTap = { vm.playOrPause(); ui.poke() },
                        onBoost = { if (it) vm.boostStart() else vm.boostEnd() },
                        onOsd = { ui.osd = it },
                    )

                    // ---- 控制层 ----
                    // 全屏锁定时不显示 (解锁靠点击后闪出的锁按钮); 小屏锁定仍显示, 否则无处解锁.
                    val controlsVisible = if (fullscreen) {
                        !ui.locked && (ui.overlay || error.isNotEmpty())
                    } else {
                        ui.overlay || error.isNotEmpty()
                    }
                    if (controlsVisible) {
                        if (fullscreen) {
                            FullscreenControls(
                                title = title,
                                playing = playing,
                                positionMs = previewMs ?: positionMs,
                                playPosMs = positionMs,
                                durationMs = durationMs,
                                bufferedMs = bufferedMs,
                                hasNext = hasNext,
                                speed = speed,
                                scaleType = scaleType,
                                danmuOpen = DanmuSettings.open.also { danmuRevision },
                                onBack = { ui.fullscreen = false },
                                onPlayPause = { vm.playOrPause(); ui.poke() },
                                onNext = { vm.playNext(); ui.poke() },
                                onSeek = { vm.seekTo(it); ui.poke() },
                                onPreview = { previewMs = it },
                                onPoke = ui::poke,
                                onToggleDanmu = { vm.toggleDanmu(); ui.poke() },
                                onSpeed = onSpeed,
                                onScale = onScale,
                                onEpisodes = { showEpisodes = true },
                                onExitFullscreen = { ui.fullscreen = false },
                            )
                        } else {
                            MiniControls(
                                tablet = tablet,
                                playing = playing,
                                positionMs = previewMs ?: positionMs,
                                durationMs = durationMs,
                                bufferedMs = bufferedMs,
                                locked = ui.locked,
                                onBack = onBack,
                                onPlayPause = {
                                    if (!ui.locked) {
                                        vm.playOrPause()
                                        ui.poke()
                                    }
                                },
                                onSeek = { if (!ui.locked) { vm.seekTo(it); ui.poke() } },
                                onPreview = { previewMs = it },
                                onPoke = ui::poke,
                                onFullscreen = { ui.fullscreen = true },
                                onLock = {
                                    if (ui.locked) {
                                        ui.locked = false
                                        ui.lockHint = false
                                        ui.showOverlay()
                                    } else {
                                        ui.locked = true
                                        ui.hideOverlay()
                                        ui.flashLock()
                                    }
                                },
                            )
                        }
                    } else if (!fullscreen && durationMs > 0) {
                        // 控制层隐藏时底部一条细进度线.
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth((positionMs.toFloat() / durationMs).coerceIn(0f, 1f))
                                .height(2.dp)
                                .background(com.me.ubox.ui.player.AccentBlue),
                        )
                    }

                    // ---- 锁屏按钮 (全屏左侧居中) ----
                    if (fullscreen && (ui.overlay || ui.lockHint)) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .windowInsetsPadding(WindowInsets.displayCutout)
                                .padding(start = 20.dp)
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .clickable {
                                    ui.locked = !ui.locked
                                    if (ui.locked) {
                                        ui.hideOverlay()
                                        ui.flashLock()
                                    } else {
                                        ui.lockHint = false
                                        ui.showOverlay()
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (ui.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                contentDescription = if (ui.locked) "解锁" else "锁定",
                                tint = Color.White,
                            )
                        }
                    }

                    // ---- 指示器 ----
                    if (boosting) {
                        BoostIndicator(Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
                    }
                    ui.osd?.let { PlayerOsdView(it, Modifier.align(Alignment.Center)) }
                    if (buffering && !loading && error.isEmpty() && ui.osd == null) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 3.dp,
                            modifier = Modifier.align(Alignment.Center).size(36.dp),
                        )
                    }
                    if (!playing && !loading && !buffering && error.isEmpty() && !ui.overlay && ui.osd == null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                    if (loading || error.isNotEmpty()) {
                        StatusView(
                            error = error,
                            onBack = if (fullscreen) ({ ui.fullscreen = false }) else onBack,
                            onRetry = vm::retry,
                            hasNext = hasNext,
                            onNext = vm::playNext,
                        )
                    }

                    // ---- 全屏选集侧栏 ----
                    if (fullscreen && showEpisodes) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { showEpisodes = false },
                        ) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .width(340.dp)
                                    .background(Color.Black.copy(alpha = 0.88f))
                                    .windowInsetsPadding(WindowInsets.displayCutout)
                                    // 吞掉面板空白处的点击, 不让它冒泡关闭面板.
                                    .pointerInput(Unit) { detectTapGestures { } },
                            ) {
                                if (groups.size > 1) {
                                    SourceRow(
                                        groups = groups,
                                        flagIndex = flagIndex,
                                        onSelect = vm::selectFlag,
                                        dark = true,
                                    )
                                }
                                if (eps.isNotEmpty()) {
                                    EpisodePanel(
                                        epNames = eps,
                                        count = eps.size,
                                        currentIdx = epIndex,
                                        onSelect = { i -> vm.selectEpisode(i); showEpisodes = false },
                                        dark = true,
                                        columns = 3,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // ---------------- 手机: 视频下方的信息 / 源 / 剧集 ----------------
                if (!fullscreen && !tablet) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        if (detailLoading) {
                            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                            }
                        } else if (detailError.isNotEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(detailError)
                                Spacer(Modifier.height(8.dp))
                                androidx.compose.material3.TextButton(onClick = vm::loadDetail) { Text("重试") }
                            }
                        } else {
                            VodInfoCard(
                                vm = vm,
                                vod = vod,
                                fav = fav,
                                switching = switching,
                                compact = false,
                                onToggleFav = vm::toggleFav,
                                onSwitchMenu = onSwitchMenu,
                            )
                            Spacer(Modifier.height(8.dp))
                            ToolRow(
                                dark = false,
                                danmuOpen = DanmuSettings.open.also { danmuRevision },
                                skipSet = skipStart > 0 || skipEnd > 0,
                                onDanmuSetting = onDanmuSetting,
                                onDanmuSearch = onDanmuSearch,
                                onSkip = onSkip,
                            )
                            if (groups.size > 1) {
                                Spacer(Modifier.height(10.dp))
                                SourceRow(groups = groups, flagIndex = flagIndex, onSelect = vm::selectFlag)
                            }
                            if (eps.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                Text("剧集 (${eps.size})", fontSize = 15.sp)
                                Spacer(Modifier.height(6.dp))
                                EpisodeFlow(eps = eps, current = epIndex, onSelect = vm::selectEpisode)
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }

            // ---------------- 平板: 右栏 27% (信息 / 源 / 剧集) ----------------
            if (split) {
                Column(
                    modifier = Modifier
                        .weight(0.27f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    if (detailLoading) {
                        Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        }
                    } else if (detailError.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(detailError)
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.material3.TextButton(onClick = vm::loadDetail) { Text("重试") }
                        }
                    } else {
                        VodInfoCard(
                            vm = vm,
                            vod = vod,
                            fav = fav,
                            switching = switching,
                            compact = true,
                            onToggleFav = vm::toggleFav,
                            onSwitchMenu = onSwitchMenu,
                        )
                        Spacer(Modifier.height(8.dp))
                        ToolRow(
                            dark = false,
                            danmuOpen = DanmuSettings.open.also { danmuRevision },
                            skipSet = skipStart > 0 || skipEnd > 0,
                            onDanmuSetting = onDanmuSetting,
                            onDanmuSearch = onDanmuSearch,
                            onSkip = onSkip,
                        )
                        if (groups.size > 1) {
                            Spacer(Modifier.height(10.dp))
                            SourceRow(groups = groups, flagIndex = flagIndex, onSelect = vm::selectFlag)
                        }
                        if (eps.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text("剧集 (${eps.size})", fontSize = 15.sp)
                            Spacer(Modifier.height(6.dp))
                            EpisodeFlow(eps = eps, current = epIndex, onSelect = vm::selectEpisode)
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

/** 源 (线路) 横滑标签行. */
@Composable
private fun SourceRow(
    groups: List<PlayUrl>,
    flagIndex: Int,
    onSelect: (Int) -> Unit,
    dark: Boolean = false,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        itemsIndexed(groups) { i, item ->
            if (dark) {
                ToolChip(
                    label = item.flag,
                    selected = i == flagIndex,
                    dark = true,
                    onClick = { onSelect(i) },
                )
            } else {
                FlagChip(name = item.flag, selected = i == flagIndex, onClick = { onSelect(i) })
            }
        }
    }
}

/** 弹幕设置 / 云搜 / 片头片尾 (小屏信息区下方的工具条). */
@Composable
private fun ToolRow(
    dark: Boolean,
    danmuOpen: Boolean,
    skipSet: Boolean,
    onDanmuSetting: () -> Unit,
    onDanmuSearch: () -> Unit,
    onSkip: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolChip(
            label = if (danmuOpen) "弹幕 开" else "弹幕 关",
            selected = danmuOpen,
            dark = dark,
            onClick = onDanmuSetting,
        )
        ToolChip("云搜", dark = dark, onClick = onDanmuSearch)
        ToolChip(if (skipSet) "片头尾 ✓" else "片头尾", selected = skipSet, dark = dark, onClick = onSkip)
    }
}

package com.me.ubox.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.me.ubox.danmaku.DanmuSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 手机 / 平板的点播播放界面 (对照 NovaBox 的 DetailActivity + VodController):
 *
 *   手机竖屏 = 16:9 视频 + 标题/工具条 + 选集网格;
 *   平板     = 左 73% (16:9 视频 + 标题/工具条) | 右 27% 选集 (对照 NovaBox layout-sw600dp);
 *   全屏     = 视频铺满 + 手势 (亮度/音量/进度/双击暂停/长按 3x) + 锁屏 + 底部功能条 + 侧滑选集.
 *
 * 全屏/非全屏共用同一个视频容器节点, 切换时 SurfaceView 与弹幕视图不重建.
 */
@Composable
internal fun TouchPlayer(
    vm: PlayerViewModel,
    ui: PlayerUiState,
    tablet: Boolean,
    vodName: String,
    epNames: List<String>,
    epUrls: List<String>,
    onBack: () -> Unit,
) {
    val idx by vm.idx.collectAsStateWithLifecycle()
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
    // 弹幕设置变化时刷新按钮文字.
    val danmuRevision by DanmuSettings.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val fullscreen = ui.fullscreen
    val split = tablet && !fullscreen
    val epName = epLabel(epNames, idx)
    val title = "$vodName · $epName"
    val hasNext = idx + 1 < epUrls.size
    val hasPrev = idx > 0
    var previewMs by remember { mutableStateOf<Long?>(null) }

    // 控制层: 播放中 4 秒无操作自动隐藏; 面板打开 / 暂停 / 出错时常驻.
    LaunchedEffect(ui.overlay, ui.overlayTick, playing, ui.anyPanelOpen) {
        if (ui.overlay && playing && !ui.anyPanelOpen) {
            delay(4000)
            ui.hideOverlay()
        }
    }
    LaunchedEffect(error) { if (error.isNotEmpty()) ui.showOverlay() }
    // 锁屏提示 (锁图标) 3 秒后淡出.
    LaunchedEffect(ui.lockHint, ui.lockHintTick) {
        if (ui.lockHint) {
            delay(3000)
            ui.lockHint = false
        }
    }
    // 退出全屏时解锁并收起侧栏.
    LaunchedEffect(fullscreen) {
        if (!fullscreen) {
            ui.locked = false
            ui.showEpisodes = false
        }
    }

    // 后注册的优先: 选集侧栏 > 全屏 (锁屏时返回键只提示锁).
    BackHandler(enabled = fullscreen) {
        if (ui.locked) ui.flashLock() else ui.fullscreen = false
    }
    BackHandler(enabled = ui.showEpisodes) { ui.showEpisodes = false }

    val selectEpisode: (Int) -> Unit = { i ->
        if (i != idx) scope.launch { vm.openIndex(i) }
        ui.showEpisodes = false
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
        androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(if (split) 0.73f else 1f)
                    .fillMaxHeight(),
            ) {
                // ---------------- 视频容器 (全屏 / 非全屏同一节点) ----------------
                Box(
                    modifier = (
                        if (fullscreen) Modifier.fillMaxSize()
                        else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        ).background(Color.Black),
                ) {
                    // Video 必须始终挂载: loading 时也在, 否则纹理在无人消费时起播会有声无画.
                    VideoSurface(
                        vm = vm,
                        videoAspectRatio = if (aspect > 0f) aspect else null,
                        scaleType = scaleType,
                        videoWidth = vw,
                        videoHeight = vh,
                        modifier = Modifier.fillMaxSize(),
                    )
                    DanmakuLayer(modifier = Modifier.fillMaxSize())
                    PlayerGestureLayer(
                        modifier = Modifier.matchParentSize(),
                        enabled = !ui.locked,
                        getPositionMs = { vm.positionNow },
                        getDurationMs = { vm.durationNow },
                        onSeekCommit = { vm.seekTo(it); ui.poke() },
                        onTap = { if (ui.locked) ui.flashLock() else ui.toggleOverlay() },
                        onDoubleTap = { vm.playOrPause(); ui.poke() },
                        onBoost = { if (it) vm.boostStart() else vm.boostEnd() },
                        onOsd = { ui.osd = it },
                    )

                    // ---- 控制层 ----
                    val controlsVisible = !ui.locked && (ui.overlay || error.isNotEmpty())
                    if (controlsVisible) {
                        if (fullscreen) {
                            FullscreenControls(
                                title = title,
                                playing = playing,
                                positionMs = previewMs ?: positionMs,
                                playPosMs = positionMs,
                                durationMs = durationMs,
                                bufferedMs = bufferedMs,
                                hasPrev = hasPrev,
                                hasNext = hasNext,
                                speed = speed,
                                scaleType = scaleType,
                                skipSet = skipStart > 0 || skipEnd > 0,
                                danmuOpen = DanmuSettings.open.also { danmuRevision },
                                onBack = { ui.fullscreen = false },
                                onPlayPause = { vm.playOrPause(); ui.poke() },
                                onPrev = { vm.playPrev(); ui.poke() },
                                onNext = { vm.playNext(); ui.poke() },
                                onSeek = { vm.seekTo(it); ui.poke() },
                                onPreview = { previewMs = it },
                                onPoke = ui::poke,
                                onToggleDanmu = { vm.toggleDanmu(); ui.poke() },
                                onDanmuSetting = { ui.showDanmuSetting = true },
                                onDanmuSearch = { ui.showDanmuSearch = true },
                                onSpeed = { ui.showSpeed = true },
                                onScale = { ui.showScale = true },
                                onSkip = { ui.showSkip = true },
                                onEpisodes = { ui.showEpisodes = true },
                                onExitFullscreen = { ui.fullscreen = false },
                            )
                        } else {
                            EmbeddedControls(
                                title = title,
                                playing = playing,
                                positionMs = previewMs ?: positionMs,
                                durationMs = durationMs,
                                bufferedMs = bufferedMs,
                                onBack = onBack,
                                onPlayPause = { vm.playOrPause(); ui.poke() },
                                onSeek = { vm.seekTo(it); ui.poke() },
                                onPreview = { previewMs = it },
                                onPoke = ui::poke,
                                onFullscreen = { ui.fullscreen = true },
                            )
                        }
                    } else if (!fullscreen && !ui.locked && durationMs > 0) {
                        // 控制层隐藏时底部一条细进度线.
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth((positionMs.toFloat() / durationMs).coerceIn(0f, 1f))
                                .height(2.dp)
                                .background(AccentBlue),
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
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
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
                    if (fullscreen && ui.showEpisodes) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { ui.showEpisodes = false },
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
                                EpisodePanel(
                                    epNames = epNames,
                                    count = epUrls.size,
                                    currentIdx = idx,
                                    onSelect = selectEpisode,
                                    dark = true,
                                    columns = 3,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                }

                // ---------------- 视频下方 (非全屏) ----------------
                if (!fullscreen) {
                    InfoBlock(
                        vodName = vodName,
                        epName = epName,
                        hasPrev = hasPrev,
                        hasNext = hasNext,
                        speed = speed,
                        scaleType = scaleType,
                        skipSet = skipStart > 0 || skipEnd > 0,
                        danmuOpen = DanmuSettings.open.also { danmuRevision },
                        onPrev = vm::playPrev,
                        onNext = vm::playNext,
                        onToggleDanmu = vm::toggleDanmu,
                        onDanmuSetting = { ui.showDanmuSetting = true },
                        onDanmuSearch = { ui.showDanmuSearch = true },
                        onSpeed = { ui.showSpeed = true },
                        onScale = { ui.showScale = true },
                        onSkip = { ui.showSkip = true },
                    )
                    if (!tablet) {
                        EpisodePanel(
                            epNames = epNames,
                            count = epUrls.size,
                            currentIdx = idx,
                            onSelect = selectEpisode,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            // 平板右栏选集 (对照 NovaBox sw600dp 的 27% 栏)
            if (split) {
                EpisodePanel(
                    epNames = epNames,
                    count = epUrls.size,
                    currentIdx = idx,
                    onSelect = selectEpisode,
                    modifier = Modifier
                        .weight(0.27f)
                        .fillMaxHeight()
                        .padding(top = 4.dp),
                    columns = 2,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 非全屏控制层 (叠在视频上)

@Composable
private fun BoxScope.EmbeddedControls(
    title: String,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPreview: (Long?) -> Unit,
    onPoke: () -> Unit,
    onFullscreen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "返回", tint = Color.White)
        }
        Text(
            text = title,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
    }
    Row(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "暂停" else "播放",
                tint = Color.White,
            )
        }
        Text(fmtTime(positionMs), color = Color.White, fontSize = 12.sp)
        PlayerSeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            onSeek = onSeek,
            onInteract = onPoke,
            onPreview = onPreview,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(fmtTime(durationMs), color = Color.White, fontSize = 12.sp)
        IconButton(onClick = onFullscreen) {
            Icon(Icons.Filled.Fullscreen, contentDescription = "全屏", tint = Color.White)
        }
    }
}

// ---------------------------------------------------------------- 全屏控制层

@Composable
private fun BoxScope.FullscreenControls(
    title: String,
    playing: Boolean,
    positionMs: Long,
    playPosMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    hasPrev: Boolean,
    hasNext: Boolean,
    speed: Float,
    scaleType: Int,
    skipSet: Boolean,
    danmuOpen: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onPreview: (Long?) -> Unit,
    onPoke: () -> Unit,
    onToggleDanmu: () -> Unit,
    onDanmuSetting: () -> Unit,
    onDanmuSearch: () -> Unit,
    onSpeed: () -> Unit,
    onScale: () -> Unit,
    onSkip: () -> Unit,
    onEpisodes: () -> Unit,
    onExitFullscreen: () -> Unit,
) {
    // 顶栏: 返回 (退出全屏) + 标题
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "返回", tint = Color.White)
        }
        Text(
            text = title,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 16.sp,
            fontWeight = FontWeight.W500,
            modifier = Modifier.weight(1f),
        )
    }
    // 底栏: 进度条 + 功能条
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmtTime(positionMs), color = Color.White, fontSize = 12.sp)
            PlayerSeekBar(
                positionMs = playPosMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                onSeek = onSeek,
                onInteract = onPoke,
                onPreview = onPreview,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Text(fmtTime(durationMs), color = Color.White, fontSize = 12.sp)
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
            if (hasPrev) {
                IconButton(onClick = onPrev) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一集", tint = Color.White)
                }
            }
            if (hasNext) {
                IconButton(onClick = onNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一集", tint = Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            ToolChip(if (danmuOpen) "弹幕 开" else "弹幕 关", onToggleDanmu, selected = danmuOpen)
            Spacer(Modifier.width(8.dp))
            ToolChip("弹幕设置", onDanmuSetting)
            Spacer(Modifier.width(8.dp))
            ToolChip("云搜", onDanmuSearch)
            Spacer(Modifier.width(8.dp))
            ToolChip("倍速 ${PlayerOptions.speedLabel(speed)}", onSpeed, selected = speed != 1.0f)
            Spacer(Modifier.width(8.dp))
            ToolChip("画面 ${PlayerOptions.SCALE_NAMES[scaleType]}", onScale, selected = scaleType != 0)
            Spacer(Modifier.width(8.dp))
            ToolChip(if (skipSet) "片头尾 ✓" else "片头尾", onSkip, selected = skipSet)
            Spacer(Modifier.width(8.dp))
            ToolChip("选集", onEpisodes)
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onExitFullscreen) {
                Icon(Icons.Filled.FullscreenExit, contentDescription = "退出全屏", tint = Color.White)
            }
        }
    }
}

// ---------------------------------------------------------------- 视频下方信息 + 工具条

@Composable
private fun InfoBlock(
    vodName: String,
    epName: String,
    hasPrev: Boolean,
    hasNext: Boolean,
    speed: Float,
    scaleType: Int,
    skipSet: Boolean,
    danmuOpen: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleDanmu: () -> Unit,
    onDanmuSetting: () -> Unit,
    onDanmuSearch: () -> Unit,
    onSpeed: () -> Unit,
    onScale: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            text = vodName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "正在播放: $epName",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            modifier = Modifier
                .padding(top = 10.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasPrev) {
                ToolChip("上一集", onPrev, dark = false)
                Spacer(Modifier.width(8.dp))
            }
            if (hasNext) {
                ToolChip("下一集", onNext, dark = false)
                Spacer(Modifier.width(8.dp))
            }
            ToolChip(if (danmuOpen) "弹幕 开" else "弹幕 关", onToggleDanmu, selected = danmuOpen, dark = false)
            Spacer(Modifier.width(8.dp))
            ToolChip("弹幕设置", onDanmuSetting, dark = false)
            Spacer(Modifier.width(8.dp))
            ToolChip("云搜", onDanmuSearch, dark = false)
            Spacer(Modifier.width(8.dp))
            ToolChip("倍速 ${PlayerOptions.speedLabel(speed)}", onSpeed, selected = speed != 1.0f, dark = false)
            Spacer(Modifier.width(8.dp))
            ToolChip("画面 ${PlayerOptions.SCALE_NAMES[scaleType]}", onScale, selected = scaleType != 0, dark = false)
            Spacer(Modifier.width(8.dp))
            ToolChip(if (skipSet) "片头尾 ✓" else "片头尾", onSkip, selected = skipSet, dark = false)
        }
    }
}

package com.me.ubox.ui.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.me.ubox.TvMode
import com.me.ubox.danmaku.DanmakuLoadStatus
import com.me.ubox.danmaku.DanmakuManager
import com.me.ubox.danmaku.DanmuSettings
import com.me.ubox.ui.AppSnackbar
import com.me.ubox.ui.components.ImmersiveSystemBarsEffect
import com.me.ubox.ui.components.TvButton
import com.me.ubox.ui.components.TvFocusable
import com.me.ubox.ui.components.VideoSurfaceView
import com.me.ubox.ui.dialogs.DanmuFullSettingDialog
import com.me.ubox.ui.dialogs.DanmuSearchDialog
import com.me.ubox.ui.dialogs.SinglePickSheet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import master.flame.danmaku.ui.widget.DanmakuView

/**
 * 点播播放页 (对照 NovaBox / NovaTV 的 PlayFragment + VodController 重写):
 *
 *   手机 / 平板 (触摸) = [TouchPlayer]: 竖屏 16:9 + 选集; 平板 73/27 分栏; 全屏手势 + 锁屏 + 功能条;
 *   TV (遥控器)        = [TvPlayer]: 全屏播放, 左右累计快进, 上长按 3x, 下/菜单唤出精简控制条.
 *
 * 三端共用: 倍速 / 画面比例 / 片头片尾 (按影片持久化) / 弹幕 / 选集 / 上下一集 / 3x 长按加速.
 */
@Composable
fun PlayerScreen(
    siteKey: String,
    vodId: String,
    vodName: String,
    cover: String,
    flag: String,
    epNames: List<String>,
    epUrls: List<String>,
    startIndex: Int,
    onBack: () -> Unit,
    vm: PlayerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PlayerViewModel(siteKey, vodId, vodName, cover, flag, epNames, epUrls, startIndex)
            }
        }
    ),
) {
    // tv = TV 布局 (TV 与平板); remote = 遥控器交互 (仅 TV). 平板 = 触摸 + 分栏布局.
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val remote by TvMode.isRemote.collectAsStateWithLifecycle()
    val idx by vm.idx.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val scaleType by vm.scaleType.collectAsStateWithLifecycle()
    val skipStart by vm.skipStartSec.collectAsStateWithLifecycle()
    val skipEnd by vm.skipEndSec.collectAsStateWithLifecycle()
    val danmakuStatus by DanmakuManager.status.collectAsStateWithLifecycle()

    val ui = remember { PlayerUiState() }
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val epName = epLabel(epNames, idx)

    // ---------------- 屏幕方向 ----------------
    // 手机: 转横屏自动全屏, 回竖屏退出全屏; 点全屏按钮 = 锁横屏 -> 进入同一条路径.
    // TV / 平板: 始终按基准方向 (横屏锁定), 全屏只是布局变化.
    LaunchedEffect(landscape, tv) {
        if (!tv) ui.fullscreen = landscape
    }
    var wasFullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(ui.fullscreen, tv) {
        val act = context.findPlayerActivity() ?: return@LaunchedEffect
        if (tv) {
            act.requestedOrientation = TvMode.baseOrientation()
        } else if (ui.fullscreen) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else if (wasFullscreen) {
            // 退出全屏 = 回竖屏; 竖屏后下面的 effect 再放开自动旋转.
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        wasFullscreen = ui.fullscreen
    }
    LaunchedEffect(landscape, ui.fullscreen, tv) {
        if (!tv && !landscape && !ui.fullscreen) {
            context.findPlayerActivity()?.requestedOrientation = TvMode.baseOrientation()
        }
    }
    // 退出播放页时复位到当前模式的基准方向.
    DisposableEffect(Unit) {
        onDispose {
            context.findPlayerActivity()?.requestedOrientation = TvMode.baseOrientation()
        }
    }

    // TV 与全屏隐藏系统栏, 退出播放页恢复.
    ImmersiveSystemBarsEffect(immersive = remote || ui.fullscreen)

    // 弹幕加载状态 -> 提示
    LaunchedEffect(danmakuStatus) {
        val msg = when (danmakuStatus) {
            DanmakuLoadStatus.OK -> "加载弹幕成功"
            DanmakuLoadStatus.NONE -> "未查询到匹配弹幕"
            DanmakuLoadStatus.FAILED -> "加载弹幕失败"
            else -> null
        }
        if (msg != null) AppSnackbar.show(msg)
    }

    if (remote) {
        TvPlayer(vm = vm, ui = ui, vodName = vodName, epNames = epNames, epUrls = epUrls, onBack = onBack)
    } else {
        TouchPlayer(
            vm = vm,
            ui = ui,
            tablet = tv,
            vodName = vodName,
            epNames = epNames,
            epUrls = epUrls,
            onBack = onBack,
        )
    }

    // ---------------- 弹层 (三端共用) ----------------
    if (ui.showDanmuSetting) {
        DanmuFullSettingDialog(onDismiss = { ui.showDanmuSetting = false })
    }
    if (ui.showDanmuSearch) {
        DanmuSearchDialog(
            initialWord = vodName,
            episode = epName,
            onLoaded = { source -> vm.loadDanmakuSource(source) },
            onDismiss = { ui.showDanmuSearch = false },
        )
    }
    if (ui.showSpeed) {
        SinglePickSheet(
            title = "倍速",
            items = PlayerOptions.SPEEDS,
            labelOf = { PlayerOptions.speedLabel(it) },
            current = speed,
            onPick = { vm.setSpeed(it); ui.showSpeed = false },
            onDismiss = { ui.showSpeed = false },
        )
    }
    if (ui.showScale) {
        SinglePickSheet(
            title = "画面比例",
            items = PlayerOptions.SCALE_NAMES.indices.toList(),
            labelOf = { PlayerOptions.SCALE_NAMES[it] },
            current = scaleType,
            onPick = { vm.setScale(it); ui.showScale = false },
            onDismiss = { ui.showScale = false },
        )
    }
    if (ui.showSkip) {
        val pos = vm.positionNow
        val rest = (vm.durationNow - pos).coerceAtLeast(0L)
        SinglePickSheet(
            title = "片头 / 片尾",
            items = listOf(0, 1, 2),
            labelOf = {
                when (it) {
                    0 -> "设为片头 (当前 ${fmtTime(pos)})"
                    1 -> "设为片尾 (剩余 ${fmtTime(rest)})"
                    else -> "清除片头片尾"
                }
            },
            subtitleOf = {
                when (it) {
                    0 -> if (skipStart > 0) "已设置: 跳过前 ${fmtTime(skipStart * 1000L)}" else "未设置"
                    1 -> if (skipEnd > 0) "已设置: 结尾前 ${skipEnd} 秒自动下一集" else "未设置"
                    else -> ""
                }
            },
            current = null,
            isSelected = { false },
            onPick = {
                when (it) {
                    0 -> { vm.markSkipStart(); AppSnackbar.show("片头已设为 ${fmtTime(pos)}") }
                    1 -> { vm.markSkipEnd(); AppSnackbar.show("片尾已设为结尾前 ${fmtTime(rest)}") }
                    else -> { vm.clearSkip(); AppSnackbar.show("已清除片头片尾") }
                }
                ui.showSkip = false
            },
            onDismiss = { ui.showSkip = false },
        )
    }
}

// ================================================================ TV (遥控器)

/**
 * TV 全屏播放 (对照 NovaTV VodController.onKeyEvent, 控制条只做部分功能):
 *   控制层隐藏时:
 *     左 / 右  = 累计快进快退 (首次 10s, 连按 800ms 内每次 20s), 松键提交;
 *     确定 / 播放暂停键 = 播放/暂停;
 *     下 / 菜单 = 唤出控制层;
 *     上 = 短按唤出控制层, 长按 (0.6s) = 3x 加速, 松开恢复.
 *   控制层显示时: 方向键在 按钮 / 进度条 / 选集 间移动焦点, 8 秒无操作自动隐藏, 返回键先收起.
 */
@Composable
private fun TvPlayer(
    vm: PlayerViewModel,
    ui: PlayerUiState,
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
    val danmuRevision by DanmuSettings.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val epName = epLabel(epNames, idx)
    val hasNext = idx + 1 < epUrls.size
    val hasPrev = idx > 0
    // 出错时强制显示控制层 (否则遥控器无处落焦点).
    val overlay = ui.overlay || error.isNotEmpty()

    // 进入先显示一会儿控制层 (含选集), 8 秒无操作自动隐藏.
    LaunchedEffect(Unit) { ui.showOverlay() }
    LaunchedEffect(ui.overlay, ui.overlayTick, ui.anyPanelOpen) {
        if (ui.overlay && !ui.anyPanelOpen) {
            delay(8000)
            ui.hideOverlay()
        }
    }
    // 控制层显示 (含出错态) 时拦截返回键, 仅收起控制层;
    // 退出播放走顶栏/状态视图的"返回"按钮.
    BackHandler(enabled = overlay) { ui.hideOverlay() }

    // ---- 遥控器累计快进 ----
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    var lastSeekKeyAt by remember { mutableLongStateOf(0L) }
    fun startSeek(dir: Int) {
        val dur = vm.durationNow
        if (dur <= 0) return
        val now = System.currentTimeMillis()
        val base = seekTarget ?: vm.positionNow
        val step = if (seekTarget != null && now - lastSeekKeyAt <= 800) 20_000L else 10_000L
        val t = (base + dir * step).coerceIn(0L, dur)
        seekTarget = t
        lastSeekKeyAt = now
        ui.osd = PlayerOsd(
            icon = if (dir > 0) PlayerIcons.FastForward else PlayerIcons.FastRewind,
            text = "${fmtTime(t)} / ${fmtTime(dur)}",
        )
    }
    fun commitSeek() {
        val t = seekTarget ?: return
        vm.seekTo(t)
        if (!vm.player.isPlaying) vm.play()
        seekTarget = null
        scope.launch {
            delay(700)
            if (seekTarget == null) ui.osd = null
        }
    }

    // ---- 上键长按 3x ----
    var upJob by remember { mutableStateOf<Job?>(null) }
    var upBoosted by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .onPreviewKeyEvent { event ->
                val k = event.nativeKeyEvent.keyCode
                val down = event.type == KeyEventType.KeyDown
                val up = event.type == KeyEventType.KeyUp
                val first = down && event.nativeKeyEvent.repeatCount == 0

                // 遥控器媒体键: 任何状态都生效.
                if (down) {
                    when (k) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { vm.playOrPause(); return@onPreviewKeyEvent true }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> { vm.play(); return@onPreviewKeyEvent true }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> { vm.pause(); return@onPreviewKeyEvent true }
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { vm.seekBy(30); return@onPreviewKeyEvent true }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> { vm.seekBy(-30); return@onPreviewKeyEvent true }
                        KeyEvent.KEYCODE_MEDIA_NEXT -> { vm.playNext(); return@onPreviewKeyEvent true }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { vm.playPrev(); return@onPreviewKeyEvent true }
                    }
                }
                if (overlay) {
                    // 控制层显示中: 任意按键续命, 方向键交给焦点遍历.
                    if (down) ui.poke()
                    return@onPreviewKeyEvent false
                }
                // ---- 控制层隐藏中 ----
                when (k) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (down) startSeek(if (k == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                        else if (up) commitSeek()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_BUTTON_A -> {
                        if (first) vm.playOrPause()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> {
                        if (down) ui.showOverlay()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (first) {
                            upBoosted = false
                            upJob?.cancel()
                            upJob = scope.launch {
                                delay(600)
                                vm.boostStart()
                                upBoosted = vm.boosting.value
                            }
                        } else if (up) {
                            upJob?.cancel()
                            if (upBoosted) {
                                vm.boostEnd()
                                upBoosted = false
                            } else {
                                ui.showOverlay()
                            }
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
        VideoSurface(
            vm = vm,
            videoAspectRatio = if (aspect > 0f) aspect else null,
            scaleType = scaleType,
            videoWidth = vw,
            videoHeight = vh,
            modifier = Modifier.fillMaxSize(),
        )
        // 弹幕层: 位于视频之上, 控制层之下.
        DanmakuLayer(modifier = Modifier.fillMaxSize())

        if (overlay) {
            Box(modifier = Modifier.align(Alignment.TopCenter)) {
                TvTopBar(
                    title = "$vodName · $epName",
                    onBack = onBack,
                    hasPrev = hasPrev,
                    hasNext = hasNext,
                    onPrev = vm::playPrev,
                    onNext = vm::playNext,
                )
            }
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                Column {
                    TvControlBar(
                        playing = playing,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        bufferedMs = bufferedMs,
                        hasPrev = hasPrev,
                        hasNext = hasNext,
                        speed = speed,
                        scaleType = scaleType,
                        skipStart = skipStart,
                        skipEnd = skipEnd,
                        danmuOpen = DanmuSettings.open.also { danmuRevision },
                        onPoke = ui::poke,
                        onPlayPause = { vm.playOrPause(); ui.poke() },
                        onPrev = vm::playPrev,
                        onNext = vm::playNext,
                        onSeek = { vm.seekTo(it); ui.poke() },
                        onSpeed = { ui.showSpeed = true },
                        onScale = { ui.showScale = true },
                        onMarkStart = {
                            vm.markSkipStart()
                            AppSnackbar.show("片头已设为 ${fmtTime(vm.positionNow)}")
                        },
                        onMarkEnd = {
                            vm.markSkipEnd()
                            AppSnackbar.show("片尾已设为结尾前 ${fmtTime((vm.durationNow - vm.positionNow).coerceAtLeast(0L))}")
                        },
                        onClearSkip = {
                            vm.clearSkip()
                            AppSnackbar.show("已清除片头片尾")
                        },
                        onToggleDanmu = vm::toggleDanmu,
                        onDanmuSetting = { ui.showDanmuSetting = true },
                        onDanmuSearch = { ui.showDanmuSearch = true },
                    )
                    TvEpisodeBar(
                        epNames = epNames,
                        epUrls = epUrls,
                        idx = idx,
                        onSelect = { i -> if (i != idx) scope.launch { vm.openIndex(i) } },
                    )
                }
            }
        }

        // ---- 指示器 ----
        if (boosting) {
            BoostIndicator(Modifier.align(Alignment.TopCenter).padding(top = 18.dp))
        }
        ui.osd?.let { PlayerOsdView(it, Modifier.align(Alignment.Center)) }
        if (buffering && !loading && error.isEmpty() && ui.osd == null) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(44.dp),
            )
        }
        // 暂停且控制层隐藏: 中央暂停图标 (对照 tv_pause_container).
        if (!playing && !loading && !buffering && error.isEmpty() && !overlay && ui.osd == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
        }
        if (loading || error.isNotEmpty()) {
            StatusView(
                error = error,
                onBack = onBack,
                onRetry = vm::retry,
                hasNext = hasNext,
                onNext = vm::playNext,
            )
        }
    }
}

@Composable
private fun TvTopBar(
    title: String,
    onBack: () -> Unit,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.54f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvButton(
                label = "返回",
                icon = Icons.Filled.ArrowBackIosNew,
                height = 46.dp,
                fontSize = 16,
                onTap = onBack,
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = title,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (hasPrev) {
                TvButton(label = "上一集", icon = Icons.Filled.SkipPrevious, height = 46.dp, fontSize = 16, onTap = onPrev)
                Spacer(Modifier.width(8.dp))
            }
            if (hasNext) {
                TvButton(label = "下一集", icon = Icons.Filled.SkipNext, height = 46.dp, fontSize = 16, onTap = onNext)
            }
        }
    }
}

/**
 * 精简控制条 (NovaTV 的 play_btn_group 只取常用项):
 * 播放/暂停 · 上一集 · 下一集 · 倍速 · 画面 · 片头 · 片尾 · 弹幕 · 弹幕设置 · 云搜 + 进度条.
 * 片头 / 片尾: 确定 = 把当前位置记为片头 / 片尾; 长按 = 清除.
 */
@Composable
private fun TvControlBar(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    hasPrev: Boolean,
    hasNext: Boolean,
    speed: Float,
    scaleType: Int,
    skipStart: Int,
    skipEnd: Int,
    danmuOpen: Boolean,
    onPoke: () -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: () -> Unit,
    onScale: () -> Unit,
    onMarkStart: () -> Unit,
    onMarkEnd: () -> Unit,
    onClearSkip: () -> Unit,
    onToggleDanmu: () -> Unit,
    onDanmuSetting: () -> Unit,
    onDanmuSearch: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.54f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    TvButton(
                        label = if (playing) "暂停" else "播放",
                        icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        autofocus = true,
                        height = 48.dp,
                        fontSize = 16,
                        onTap = onPlayPause,
                    )
                }
                if (hasPrev) item {
                    TvButton(label = "上一集", icon = Icons.Filled.SkipPrevious, height = 48.dp, fontSize = 16, onTap = onPrev)
                }
                if (hasNext) item {
                    TvButton(label = "下一集", icon = Icons.Filled.SkipNext, height = 48.dp, fontSize = 16, onTap = onNext)
                }
                item {
                    TvButton(label = "倍速 ${PlayerOptions.speedLabel(speed)}", height = 48.dp, fontSize = 16, onTap = onSpeed)
                }
                item {
                    TvButton(label = "画面 ${PlayerOptions.SCALE_NAMES[scaleType]}", height = 48.dp, fontSize = 16, onTap = onScale)
                }
                item {
                    TvActionButton(
                        label = "片头 ${fmtTime(skipStart * 1000L)}",
                        onTap = onMarkStart,
                        onLongPress = onClearSkip,
                    )
                }
                item {
                    TvActionButton(
                        label = "片尾 ${fmtTime(skipEnd * 1000L)}",
                        onTap = onMarkEnd,
                        onLongPress = onClearSkip,
                    )
                }
                item {
                    TvButton(label = if (danmuOpen) "弹幕 开" else "弹幕 关", height = 48.dp, fontSize = 16, onTap = onToggleDanmu)
                }
                item {
                    TvButton(label = "弹幕设置", height = 48.dp, fontSize = 16, onTap = onDanmuSetting)
                }
                item {
                    TvButton(label = "云搜", height = 48.dp, fontSize = 16, onTap = onDanmuSearch)
                }
            }
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fmtTime(positionMs), color = Color.White, fontSize = 14.sp, modifier = Modifier.width(64.dp))
                PlayerSeekBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedMs = bufferedMs,
                    onSeek = onSeek,
                    onInteract = onPoke,
                    modifier = Modifier.weight(1f),
                    height = 36.dp,
                )
                Text(
                    fmtTime(durationMs),
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(64.dp),
                )
            }
        }
    }
}

/** 支持长按的遥控器按钮 (外观同 TvButton). */
@Composable
private fun TvActionButton(
    label: String,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    var focused by remember { mutableStateOf(false) }
    TvFocusable(
        onTap = onTap,
        modifier = Modifier.height(48.dp),
        radius = 8.dp,
        showBorder = false,
        onLongPress = onLongPress,
        onFocusChanged = { focused = it },
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .background(
                    color = if (focused) Color.White else Color(0x6C3D3D3D),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                maxLines = 1,
                color = if (focused) Color.Black else Color.White,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun TvEpisodeBar(
    epNames: List<String>,
    epUrls: List<String>,
    idx: Int,
    onSelect: (Int) -> Unit,
) {
    if (epUrls.isEmpty()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(Color.Black.copy(alpha = 0.54f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(epUrls.size) { i ->
                TvButton(
                    label = epLabel(epNames, i),
                    selected = i == idx,
                    height = 64.dp,
                    fontSize = 16,
                    onTap = { onSelect(i) },
                )
            }
        }
    }
}

// ================================================================ 视频 + 弹幕 + 状态 (三端共用)

@Composable
internal fun VideoSurface(
    vm: PlayerViewModel,
    videoAspectRatio: Float?,
    scaleType: Int,
    videoWidth: Int,
    videoHeight: Int,
    modifier: Modifier = Modifier,
) {
    VideoSurfaceView(
        modifier = modifier,
        aspectRatio = videoAspectRatio,
        scaleType = scaleType,
        videoWidthPx = videoWidth,
        videoHeightPx = videoHeight,
        onSurfaceReady = { view -> vm.attachSurface(view.holder.surface) },
        onSurfaceDispose = { vm.detachSurface() },
    )
}

/**
 * 弹幕覆盖层: 弹幕大师 (DanmakuFlameMaster) 的 DanmakuView.
 * 顶部边距用 offset 整体下移 (对照原版 UBox FrameLayout.LayoutParams.topMargin:
 * view 高度保持铺满, 轨道范围由行数设置决定, 两者互相独立).
 */
@Composable
internal fun DanmakuLayer(modifier: Modifier = Modifier) {
    val revision by DanmuSettings.revision.collectAsStateWithLifecycle()
    // 设置变更 (参数/开关) -> 热更新库参数 / 关闭时收画面 (对照 applySettings).
    // 布局类参数 (行数/边距/字号) 的完整重备由弹幕设置弹窗关闭时触发 (reload=true).
    LaunchedEffect(revision) {
        DanmakuManager.onSettingsChanged(reload = false)
    }
    if (!DanmuSettings.open) return
    val density = LocalDensity.current
    val topMargin = with(density) { DanmuSettings.topMarginPx.toDp() }
    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                DanmakuView(ctx).also { DanmakuManager.attach(it) }
            },
            onRelease = { view ->
                // 只摘掉"自己": 切换布局分支时新视图的 factory 先于旧视图的 onRelease,
                // 无条件 detach 会把刚 attach 的新视图摘掉 -> 全屏/重建后弹幕消失.
                DanmakuManager.detach(view)
                try {
                    view.release()
                } catch (_: Exception) {
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .offset(y = topMargin),
        )
    }
}

@Composable
internal fun StatusView(
    error: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    hasNext: Boolean,
    onNext: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (error.isEmpty()) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(38.dp),
                )
            } else {
                Text(
                    text = error,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = onBack) { Text("返回") }
                if (error.isNotEmpty()) {
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                if (hasNext) {
                    TextButton(onClick = onNext) { Text("下一集") }
                }
            }
        }
    }
}

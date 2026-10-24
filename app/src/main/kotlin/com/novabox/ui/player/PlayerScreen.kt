package com.novabox.ui.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.novabox.TvMode
import com.novabox.danmaku.DanmakuLoadStatus
import com.novabox.danmaku.DanmakuManager
import com.novabox.danmaku.DanmuSettings
import master.flame.danmaku.ui.widget.DanmakuView
import kotlinx.coroutines.launch
import com.novabox.ui.AppSnackbar
import com.novabox.ui.components.PlayerProgressRow
import com.novabox.ui.components.TvButton
import com.novabox.ui.components.VideoSurfaceView
import com.novabox.ui.dialogs.DanmuFullSettingDialog
import com.novabox.ui.dialogs.DanmuSearchDialog

/**
 * 播放页 (对照 Flutter 版 player_page.dart):
 * 手机 = AppBar + 16:9 视频 + 竖排选集; TV = 全屏播放 + 控制层 8s 自动隐藏.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val idx by vm.idx.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val positionMs by vm.positionMs.collectAsStateWithLifecycle()
    val durationMs by vm.durationMs.collectAsStateWithLifecycle()
    val tvOverlay by vm.tvOverlay.collectAsStateWithLifecycle()
    val seekHint by vm.seekHint.collectAsStateWithLifecycle()
    val videoAspectRatio by vm.videoAspectRatio.collectAsStateWithLifecycle()
    val danmakuStatus by DanmakuManager.status.collectAsStateWithLifecycle()

    var showDanmuSetting by remember { mutableStateOf(false) }
    var showDanmuSearch by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 弹幕加载状态 → Toast (对照 Flutter 版 _onDanmuStatus)
    LaunchedEffect(danmakuStatus) {
        val msg = when (danmakuStatus) {
            DanmakuLoadStatus.OK -> "加载弹幕成功"
            DanmakuLoadStatus.NONE -> "未查询到匹配弹幕"
            DanmakuLoadStatus.FAILED -> "加载弹幕失败"
            else -> null
        }
        if (msg != null) AppSnackbar.show(msg)
    }

    // TV 进入时先显示一会儿控制层 (含选集), 无操作后自动隐藏.
    LaunchedEffect(tv) {
        if (tv) vm.showTvOverlay()
    }

    val epName = if (idx in epNames.indices) epNames[idx] else "第${idx + 1}集"

    if (!tv) {
        // ---------------- 手机: 原布局 (AppBar + 16:9 视频 + 竖排选集) ----------------
        androidx.compose.material3.Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("$vodName · $epName", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::toggleDanmu) {
                            Icon(Icons.Filled.Subtitles, contentDescription = "弹幕开关")
                        }
                        IconButton(onClick = { showDanmuSetting = true }) {
                            Icon(Icons.Filled.Tune, contentDescription = "弹幕设置")
                        }
                        IconButton(onClick = { showDanmuSearch = true }) {
                            Icon(Icons.Filled.TravelExplore, contentDescription = "弹幕云搜")
                        }
                        if (epUrls.isNotEmpty()) {
                            IconButton(
                                onClick = { if (idx + 1 < epUrls.size) vm.playNext() },
                                enabled = idx + 1 < epUrls.size,
                            ) {
                                Icon(Icons.Filled.SkipNext, contentDescription = "下一集")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            // Video 必须始终挂载: 以前 loading 时整页换成状态视图, open 完成后才创建 Video,
            // 纹理在"无人消费"的状态下起播, 部分机型因此有声无画.
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                ) {
                    VideoSurface(
                        vm = vm,
                        videoAspectRatio = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                    DanmakuLayer(modifier = Modifier.fillMaxSize())
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (loading || error.isNotEmpty()) {
                        StatusView(
                            error = error,
                            onBack = onBack,
                            onRetry = { vm.playOrPause() },
                            hasNext = idx + 1 < epUrls.size,
                            onNext = vm::playNext,
                        )
                    } else {
                        EpisodeList(
                            epNames = epNames,
                            epUrls = epUrls,
                            idx = idx,
                            onSelect = { i -> if (i != idx) scope.launch { vm.openIndex(i) } },
                        )
                    }
                }
            }
        }
    } else {
        // ---------------- TV: 全屏播放 ----------------
        // 视频铺满整屏; 顶栏/控制条/选集组成"控制层", 无操作 8 秒自动隐藏:
        //   隐藏时: OK=播放/暂停, 左/右=快退/快进 10s (显示进度提示), 上/下=唤出控制层, 返回=退出;
        //   显示时: 方向键在控制条/选集间移动焦点, 返回=先收起控制层.
        // 出错时强制显示控制层 (否则遥控器无处落焦点).
        val overlay = tvOverlay || error.isNotEmpty()
        BackHandler(enabled = overlay) {
            if (error.isNotEmpty()) onBack() else vm.hideTvOverlay()
        }

        // MENU 键: 短按 = 弹幕开关, 长按 2s = 弹幕设置 (对照原版 toggleDanmu).
        var menuDownAt by remember { mutableLongStateOf(0L) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU) {
                        when (event.type) {
                            KeyEventType.KeyDown -> {
                                if (menuDownAt == 0L) menuDownAt = System.currentTimeMillis()
                                true
                            }
                            KeyEventType.KeyUp -> {
                                val held = System.currentTimeMillis() - menuDownAt
                                menuDownAt = 0L
                                if (held >= 2000) showDanmuSetting = true else vm.toggleDanmu()
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp) return@onPreviewKeyEvent false
                    val k = event.nativeKeyEvent.keyCode
                    // 遥控器媒体键: 任何状态都生效
                    when (k) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            vm.playOrPause(); true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            vm.playOrPause(); true
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            vm.playOrPause(); true
                        }
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            vm.seekBy(30); true
                        }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            vm.seekBy(-30); true
                        }
                        else -> false
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp) return@onPreviewKeyEvent false
                    val k = event.nativeKeyEvent.keyCode
                    if (overlay) {
                        // 控制层显示中: 任意按键续命, 方向键交给焦点遍历.
                        vm.showTvOverlay()
                        return@onPreviewKeyEvent false
                    }
                    // 控制层隐藏中
                    when (k) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            vm.seekBy(-10); true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            vm.seekBy(10); true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                            vm.playOrPause(); vm.showTvOverlay(); true
                        }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                            vm.showTvOverlay(); true
                        }
                        else -> false
                    }
                },
        ) {
            // 必须 NoVideoControls: 默认的控制条是为鼠标/触摸设计的, 会在 TV 上叠出第二套控件.
            VideoSurface(
                vm = vm,
                videoAspectRatio = if (videoAspectRatio > 0f) videoAspectRatio else null,
                modifier = Modifier.fillMaxSize(),
            )
            // 弹幕层: 位于视频之上, 控制层之下.
            DanmakuLayer(modifier = Modifier.fillMaxSize())
            if (overlay) {
                Box(modifier = Modifier.align(Alignment.TopCenter)) {
                    TvTopBar(
                        title = "$vodName · $epName",
                        onBack = onBack,
                        hasNext = idx + 1 < epUrls.size,
                        onNext = vm::playNext,
                    )
                }
                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    Column {
                        TvControlBar(
                            playing = playing,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            onPlayPause = vm::playOrPause,
                            onSeekBy = vm::seekBy,
                            onSeekTo = vm::seekTo,
                            onToggleDanmu = vm::toggleDanmu,
                            onDanmuSetting = { showDanmuSetting = true },
                            onDanmuSearch = { showDanmuSearch = true },
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
            // 控制层隐藏时的快进快退提示
            if (seekHint.isNotEmpty()) {
                Box(modifier = Modifier.align(Alignment.Center)) {
                    Box(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.54f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 22.dp, vertical = 12.dp),
                    ) {
                        Text(seekHint, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            if (loading || error.isNotEmpty()) {
                StatusView(
                    error = error,
                    onBack = onBack,
                    onRetry = { vm.playOrPause() },
                    hasNext = idx + 1 < epUrls.size,
                    onNext = vm::playNext,
                )
            }
        }
    }

    if (showDanmuSetting) {
        DanmuFullSettingDialog(onDismiss = { showDanmuSetting = false })
    }
    if (showDanmuSearch) {
        DanmuSearchDialog(
            initialWord = vodName,
            episode = epName,
            onLoaded = { source -> vm.loadDanmakuSource(source) },
            onDismiss = { showDanmuSearch = false },
        )
    }
}

// ---------------------------------------------------------------- 视频 + 弹幕

@Composable
private fun VideoSurface(
    vm: PlayerViewModel,
    videoAspectRatio: Float?,
    modifier: Modifier = Modifier,
) {
    VideoSurfaceView(
        modifier = modifier,
        aspectRatio = videoAspectRatio,
        onSurfaceReady = { view -> vm.attachSurface(view.holder.surface) },
        onSurfaceDispose = { vm.detachSurface() },
    )
}

/**
 * 弹幕覆盖层: 弹幕大师 (DanmakuFlameMaster) 的 DanmakuView.
 * 顶部边距用 offset 整体下移 (对照原版 NovaBox FrameLayout.LayoutParams.topMargin:
 * view 高度保持铺满, 轨道范围由行数设置决定, 两者互相独立).
 */
@Composable
private fun DanmakuLayer(modifier: Modifier = Modifier) {
    val revision by DanmuSettings.revision.collectAsStateWithLifecycle()
    // 设置变更 (参数/开关) → 热更新库参数 / 关闭时收画面 (对照 applySettings).
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
                DanmakuManager.detach()
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

// ---------------------------------------------------------------- 选集

@Composable
private fun EpisodeList(
    epNames: List<String>,
    epUrls: List<String>,
    idx: Int,
    onSelect: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(all = 12.dp),
    ) {
        items(epUrls.size) { i ->
            val selected = i == idx
            ListItem(
                headlineContent = {
                    Text(
                        text = if (i in epNames.indices && epNames[i].isNotEmpty()) epNames[i] else "第${i + 1}集",
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    )
                },
                leadingContent = {
                    Text(
                        text = "$i",
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !selected) { onSelect(i) },
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
                    label = if (i in epNames.indices && epNames[i].isNotEmpty()) epNames[i] else "第${i + 1}集",
                    selected = i == idx,
                    height = 64.dp,
                    fontSize = 16,
                    onTap = { onSelect(i) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- TV 控制层

@Composable
private fun TvTopBar(
    title: String,
    onBack: () -> Unit,
    hasNext: Boolean,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (hasNext) {
                TvButton(
                    label = "下一集",
                    icon = Icons.Filled.SkipNext,
                    height = 46.dp,
                    fontSize = 16,
                    onTap = onNext,
                )
            }
        }
    }
}

/** 底部控制条: 播放/暂停 + 快退/快进 + 进度条 + 弹幕行 (全部可被遥控器聚焦). */
@Composable
private fun TvControlBar(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeekBy: (Int) -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleDanmu: () -> Unit,
    onDanmuSetting: () -> Unit,
    onDanmuSearch: () -> Unit,
) {
    // 设置变更 (开关/参数) 时刷新按钮文字.
    val danmuRevision by DanmuSettings.revision.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.54f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvButton(
                    label = if (playing) "暂停" else "播放",
                    icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    autofocus = true,
                    height = 48.dp,
                    fontSize = 16,
                    onTap = onPlayPause,
                )
                Spacer(Modifier.width(8.dp))
                TvButton(
                    label = "快退 10s",
                    icon = Icons.Filled.Replay10,
                    height = 48.dp,
                    fontSize = 16,
                    onTap = { onSeekBy(-10) },
                )
                Spacer(Modifier.width(8.dp))
                TvButton(
                    label = "快进 10s",
                    icon = Icons.Filled.Forward10,
                    height = 48.dp,
                    fontSize = 16,
                    onTap = { onSeekBy(10) },
                )
                Spacer(Modifier.weight(1f))
                // 弹幕行: 开关 / 设置 / 云搜 (对照原版 VodController 弹幕按钮).
                TvButton(
                    label = if (DanmuSettings.open) "弹幕开" else "弹幕关",
                    icon = Icons.Filled.Subtitles,
                    height = 48.dp,
                    fontSize = 16,
                    onTap = onToggleDanmu,
                )
                Spacer(Modifier.width(8.dp))
                TvButton(
                    label = "弹幕设置",
                    icon = Icons.Filled.Tune,
                    height = 48.dp,
                    fontSize = 16,
                    onTap = onDanmuSetting,
                )
                Spacer(Modifier.width(8.dp))
                TvButton(
                    label = "弹幕云搜",
                    icon = Icons.Filled.TravelExplore,
                    height = 48.dp,
                    fontSize = 16,
                    onTap = onDanmuSearch,
                )
            }
            PlayerProgressRow(
                positionMs = positionMs,
                durationMs = durationMs,
                onSeek = onSeekTo,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- 状态视图

@Composable
private fun StatusView(
    error: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    hasNext: Boolean,
    onNext: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (error.isEmpty()) {
                CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.width(38.dp).height(38.dp))
            } else {
                Text(
                    text = error,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton(onClick = onBack) { Text("返回") }
                if (error.isNotEmpty()) {
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                if (error.isEmpty() && hasNext) {
                    TextButton(onClick = onNext) { Text("下一集") }
                }
            }
        }
    }
}

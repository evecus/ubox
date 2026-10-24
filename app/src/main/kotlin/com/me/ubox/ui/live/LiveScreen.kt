package com.me.ubox.ui.live

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.drop
import com.me.ubox.TvMode
import com.me.ubox.ui.AppShell
import com.me.ubox.ui.components.Badge
import com.me.ubox.ui.components.ImmersiveSystemBarsEffect
import com.me.ubox.ui.components.LiveListItem
import com.me.ubox.ui.components.TvButton
import com.me.ubox.ui.components.VideoSurfaceView
import com.me.ubox.ui.components.pageFocusGuard
import com.me.ubox.ui.theme.LiveColors

/**
 * 直播页 (对照 Flutter 版 live_page.dart / 原版 LiveFragment):
 * 手机 = 播放器(2) + 信息栏(52dp) + 三列列表(3); TV = 播放器(72%) + 右侧面板(28%).
 */
@Composable
fun LiveScreen(
    onGoSettings: () -> Unit,
    vm: LiveViewModel = viewModel(),
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val tabIndex by AppShell.tabIndex.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val noSource by vm.noSource.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val selectedGroupIndex by vm.selectedGroupIndex.collectAsStateWithLifecycle()
    val selectedChannelIndex by vm.selectedChannelIndex.collectAsStateWithLifecycle()
    val currentChannel by vm.currentChannel.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val buffering by vm.buffering.collectAsStateWithLifecycle()
    val videoMounted by vm.videoMounted.collectAsStateWithLifecycle()
    val overlayVisible by vm.overlayVisible.collectAsStateWithLifecycle()
    val portraitFullscreen by vm.portraitFullscreen.collectAsStateWithLifecycle()
    val tvFullscreen by vm.tvFullscreen.collectAsStateWithLifecycle()
    val tvPanel by vm.tvPanel.collectAsStateWithLifecycle()
    val badgeVisible by vm.badgeVisible.collectAsStateWithLifecycle()
    val badgeName by vm.badgeName.collectAsStateWithLifecycle()
    val videoAspectRatio by vm.videoAspectRatio.collectAsStateWithLifecycle()

    var showPasswordDialog by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) { vm.bootstrap() }

    // 直播源地址变化 → 重载 (跳过初始发射, 首帧加载由 vm.bootstrap 负责)
    LaunchedEffect(Unit) {
        com.me.ubox.data.live.LiveStore.liveUrlChanged.drop(1).collect { vm.onLiveUrlChanged() }
    }

    // 切 Tab: 离开直播 tab 时停止播放 (对照 LiveFragment.onFragmentPause)
    LaunchedEffect(tabIndex) {
        if (tabIndex != AppShell.LIVE_TAB) vm.onTabChanged()
    }

    // 沉浸式系统栏 (对照 Flutter live_page 的 6 处 SystemChrome 调用):
    // 全屏 (竖屏全屏/TV 全屏) = immersiveSticky; 退出全屏/切 Tab 复位 (liveFullscreen=false)/
    // 页面销毁 = edgeToEdge.
    val fullscreen by AppShell.liveFullscreen.collectAsStateWithLifecycle()
    ImmersiveSystemBarsEffect(immersive = fullscreen)
    // 离开组合时复位全屏标记 (对照 Flutter dispose: RootPage.liveFullscreen.value = false).
    DisposableEffect(Unit) {
        onDispose { AppShell.setLiveFullscreen(false) }
    }

    if (tv) {
        if (tvFullscreen) {
            BackHandler {
                if (tvPanel) vm.hideTvPanel() else vm.exitTvFullscreen()
            }
        }
        TvLiveLayout(
            vm = vm,
            groups = groups,
            selectedGroupIndex = selectedGroupIndex,
            selectedChannelIndex = selectedChannelIndex,
            currentChannel = currentChannel,
            playing = playing,
            buffering = buffering,
            videoMounted = videoMounted,
            tvPanel = tvPanel,
            badgeVisible = badgeVisible,
            badgeName = badgeName,
            videoAspectRatio = videoAspectRatio,
            onPasswordNeeded = { showPasswordDialog = it },
        )
    } else {
        if (portraitFullscreen) {
            BackHandler { vm.exitFullscreen() }
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                PlayerArea(vm = vm, tv = false, fullscreen = true, videoAspectRatio = videoAspectRatio)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxWidth().weight(2f)) {
                    PlayerArea(vm = vm, tv = false, fullscreen = false, videoAspectRatio = videoAspectRatio)
                }
                InfoBar(vm = vm, tv = false)
                Box(modifier = Modifier.weight(3f)) {
                    LiveBodyArea(
                        vm = vm,
                        tv = false,
                        groups = groups,
                        loading = loading,
                        noSource = noSource,
                        error = error,
                        selectedGroupIndex = selectedGroupIndex,
                        selectedChannelIndex = selectedChannelIndex,
                        currentChannel = currentChannel,
                        onGoSettings = onGoSettings,
                        onPasswordNeeded = { showPasswordDialog = it },
                    )
                }
            }
        }
    }

    val passwordGroup = showPasswordDialog
    if (passwordGroup != null) {
        var pwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasswordDialog = null },
            title = { Text("分组密码") },
            text = {
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                    ),
                    label = { Text("请输入密码") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = vm.confirmPassword(passwordGroup, pwd)
                    showPasswordDialog = null
                    if (!ok) com.me.ubox.ui.AppSnackbar.show("密码错误")
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = null }) { Text("取消") }
            },
        )
    }
}

// ---------------------------------------------------------------- 播放器区域

@Composable
private fun PlayerArea(
    vm: LiveViewModel,
    tv: Boolean,
    fullscreen: Boolean,
    videoAspectRatio: Float,
) {
    val playing by vm.playing.collectAsStateWithLifecycle()
    val buffering by vm.buffering.collectAsStateWithLifecycle()
    val videoMounted by vm.videoMounted.collectAsStateWithLifecycle()
    val overlayVisible by vm.overlayVisible.collectAsStateWithLifecycle()
    val badgeVisible by vm.badgeVisible.collectAsStateWithLifecycle()
    val badgeName by vm.badgeName.collectAsStateWithLifecycle()
    val portraitFullscreen by vm.portraitFullscreen.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        if (videoMounted) {
            VideoSurfaceView(
                modifier = Modifier.fillMaxSize(),
                aspectRatio = if (videoAspectRatio > 0f) videoAspectRatio else null,
                onSurfaceReady = { view -> vm.attachSurface(view.holder.surface) },
                onSurfaceDispose = { vm.detachSurface() },
            )
        } else {
            // 占位: 未配置直播源 / 错误 / 空白
            val noSource by vm.noSource.collectAsStateWithLifecycle()
            val error by vm.error.collectAsStateWithLifecycle()
            val groups by vm.groups.collectAsStateWithLifecycle()
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    noSource -> NoSourceHint(onGoSettings = {
                        AppShell.selectTab(AppShell.SETTINGS_TAB)
                    })
                    error.isNotEmpty() && groups.isEmpty() -> LiveErrorHint(error = error, onRetry = vm::loadLives)
                    else -> Spacer(Modifier)
                }
            }
        }
        if (buffering && !playing) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White)
            }
        }
        if (badgeVisible) {
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                Badge(
                    text = badgeName,
                    bg = LiveColors.BadgeBg,
                    tv = tv,
                )
            }
        }
        // TV 全屏时不常驻控制条 (用遥控器按键直接操作); 非全屏才显示.
        when {
            tv && !fullscreen -> Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                TvControlBar(vm = vm, playing = playing)
            }
            !tv -> {
                // 手机: 点击视频区域唤出控制浮层 (对照原版 LiveController 的点击手势).
                if (!overlayVisible) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { vm.toggleOverlay() },
                    )
                }
                PhoneOverlay(
                    visible = overlayVisible,
                    playing = playing,
                    portraitFullscreen = portraitFullscreen,
                    vm = vm,
                )
            }
        }
    }
}

@Composable
private fun NoSourceHint(onGoSettings: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.height(48.dp).width(48.dp),
            tint = Color.White.copy(alpha = 0.38f),
        )
        Spacer(Modifier.height(12.dp))
        Text("未配置直播源", color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(4.dp))
        Text(
            "请先到【设置 → 直播】中配置直播源地址",
            color = Color.White.copy(alpha = 0.38f),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGoSettings, modifier = Modifier.pageFocusGuard()) { Text("去设置") }
    }
}

@Composable
private fun LiveErrorHint(error: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(24.dp),
    ) {
        Text(
            text = error.ifEmpty { "直播列表为空" },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry, modifier = Modifier.pageFocusGuard()) { Text("重试") }
    }
}

@Composable
private fun InfoBar(vm: LiveViewModel, tv: Boolean) {
    val currentChannel by vm.currentChannel.collectAsStateWithLifecycle()
    val noSource by vm.noSource.collectAsStateWithLifecycle()
    val text = when {
        noSource -> "未配置直播源"
        else -> currentChannel?.channelName ?: "请选择频道"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(start = if (tv) 14.dp else 16.dp, end = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            maxLines = 1,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

// ---------------------------------------------------------------- 三列列表

@Composable
private fun LiveBodyArea(
    vm: LiveViewModel,
    tv: Boolean,
    groups: List<com.me.ubox.data.live.LiveChannelGroup>,
    loading: Boolean,
    noSource: Boolean,
    error: String,
    selectedGroupIndex: Int,
    selectedChannelIndex: Int,
    currentChannel: com.me.ubox.data.live.LiveChannelItem?,
    onGoSettings: () -> Unit,
    onPasswordNeeded: (Int) -> Unit,
) {
    if (noSource) return
    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
        return
    }
    if (groups.isEmpty()) {
        LiveErrorHint(error = error, onRetry = vm::loadLives)
        return
    }
    Row(modifier = Modifier.fillMaxSize()) {
        // 原版权重: 手机 1 : 1.5 : 1, TV(sw600dp) 0.5 : 0.8 : 0.5
        Box(modifier = Modifier.weight(if (tv) 0.5f else 1f)) {
            GroupList(vm = vm, groups = groups, selectedGroupIndex = selectedGroupIndex, tv = tv, onPasswordNeeded = onPasswordNeeded)
        }
        Box(modifier = Modifier.weight(if (tv) 0.8f else 1.5f)) {
            ChannelList(vm = vm, tv = tv, selectedChannelIndex = selectedChannelIndex)
        }
        Box(modifier = Modifier.weight(if (tv) 0.5f else 1f)) {
            SourceList(vm = vm, tv = tv, currentChannel = currentChannel)
        }
    }
}

@Composable
private fun GroupList(
    vm: LiveViewModel,
    groups: List<com.me.ubox.data.live.LiveChannelGroup>,
    selectedGroupIndex: Int,
    tv: Boolean,
    onPasswordNeeded: (Int) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
        items(groups, key = { it.groupIndex }) { g ->
            LiveListItem(
                text = g.groupName,
                selected = g.groupIndex == selectedGroupIndex,
                large = tv,
                onClick = {
                    if (vm.needPassword(g.groupIndex)) {
                        onPasswordNeeded(g.groupIndex)
                    } else {
                        vm.selectChannelGroup(g.groupIndex)
                    }
                },
            )
        }
    }
}

@Composable
private fun ChannelList(
    vm: LiveViewModel,
    tv: Boolean,
    selectedChannelIndex: Int,
) {
    val selectedGroupIndex by vm.selectedGroupIndex.collectAsStateWithLifecycle()
    val channels = vm.channelsOf(selectedGroupIndex)
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
        items(channels, key = { it.channelNum }) { c ->
            LiveListItem(
                num = "${c.channelNum}",
                text = c.channelName,
                selected = c.channelIndex == selectedChannelIndex,
                large = tv,
                onClick = { vm.clickLiveChannel(c.channelIndex) },
            )
        }
    }
}

@Composable
private fun SourceList(
    vm: LiveViewModel,
    tv: Boolean,
    currentChannel: com.me.ubox.data.live.LiveChannelItem?,
) {
    // 源列表只在有播放频道时填充 (对照 updateMobileSourceList)
    if (currentChannel == null) return
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
        items(currentChannel.sourceNum) { i ->
            LiveListItem(
                text = currentChannel.channelSourceNames.getOrElse(i) { "源${i + 1}" },
                selected = i == currentChannel.sourceIndex,
                large = tv,
                onClick = { vm.playChannelBySource(i) },
            )
        }
    }
}

// ---------------------------------------------------------------- 控制浮层

@Composable
private fun PhoneOverlay(
    visible: Boolean,
    playing: Boolean,
    portraitFullscreen: Boolean,
    vm: LiveViewModel,
) {
    if (!visible) return
    Box(modifier = Modifier.fillMaxSize()) {
        if (portraitFullscreen) {
            OverlayIconButton(
                icon = Icons.Filled.ArrowBack,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                onClick = { vm.exitFullscreen() },
            )
        }
        OverlayIconButton(
            icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            size = 56.dp,
            modifier = Modifier.align(Alignment.Center),
            onClick = { vm.togglePlayPause() },
        )
        OverlayIconButton(
            icon = Icons.Filled.Refresh,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            onClick = { vm.refreshChannel() },
        )
        OverlayIconButton(
            icon = if (portraitFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            onClick = { vm.toggleFullscreen() },
        )
    }
}

@Composable
private fun OverlayIconButton(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .width(size)
            .height(size)
            .background(Color(0x66000000), CircleShape)
            .pageFocusGuard()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.width(size / 2).height(size / 2),
            tint = Color.White,
        )
    }
}

/** TV 底部常驻控制条 (遥控器可聚焦). */
@Composable
private fun TvControlBar(vm: LiveViewModel, playing: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x99000000))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvButton(
                label = if (playing) "暂停" else "播放",
                icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                height = 40.dp,
                fontSize = 15,
                onTap = { vm.togglePlayPause() },
            )
            Spacer(Modifier.width(12.dp))
            TvButton(
                label = "刷新",
                icon = Icons.Filled.Refresh,
                height = 40.dp,
                fontSize = 15,
                onTap = { vm.refreshChannel() },
            )
            Spacer(Modifier.width(12.dp))
            TvButton(
                label = "上一源",
                icon = Icons.Filled.SkipPrevious,
                height = 40.dp,
                fontSize = 15,
                onTap = { vm.playPreSourceManual() },
            )
            Spacer(Modifier.width(12.dp))
            TvButton(
                label = "下一源",
                icon = Icons.Filled.SkipNext,
                height = 40.dp,
                fontSize = 15,
                onTap = { vm.playNextSourceManual() },
            )
            Spacer(Modifier.width(12.dp))
            TvButton(
                label = "全屏",
                icon = Icons.Filled.Fullscreen,
                height = 40.dp,
                fontSize = 15,
                onTap = { vm.enterTvFullscreen() },
            )
        }
    }
}

// ---------------------------------------------------------------- TV 布局

@Composable
private fun TvLiveLayout(
    vm: LiveViewModel,
    groups: List<com.me.ubox.data.live.LiveChannelGroup>,
    selectedGroupIndex: Int,
    selectedChannelIndex: Int,
    currentChannel: com.me.ubox.data.live.LiveChannelItem?,
    playing: Boolean,
    buffering: Boolean,
    videoMounted: Boolean,
    tvPanel: Boolean,
    badgeVisible: Boolean,
    badgeName: String,
    videoAspectRatio: Float,
    onPasswordNeeded: (Int) -> Unit,
) {
    val loading by vm.loading.collectAsStateWithLifecycle()
    val noSource by vm.noSource.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val tvFullscreen by vm.tvFullscreen.collectAsStateWithLifecycle()

    if (tvFullscreen) {
        // TV 全屏: 视频铺满 + OK 唤出右侧频道面板
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val k = event.nativeKeyEvent.keyCode
                    when (k) {
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                            vm.playAdjacentChannel(-1); true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                            vm.playAdjacentChannel(1); true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            vm.playPreSourceManual(); true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            vm.playNextSourceManual(); true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            vm.showTvPanel(); true
                        }
                        KeyEvent.KEYCODE_MENU -> {
                            vm.refreshChannel(); true
                        }
                        else -> false
                    }
                },
        ) {
            PlayerArea(vm = vm, tv = true, fullscreen = true, videoAspectRatio = videoAspectRatio)
            if (tvPanel) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width((340).dp)
                        .background(Color(0xE6101418)),
                ) {
                    Column {
                        InfoBar(vm = vm, tv = true)
                        LiveBodyArea(
                            vm = vm,
                            tv = true,
                            groups = groups,
                            loading = loading,
                            noSource = noSource,
                            error = error,
                            selectedGroupIndex = selectedGroupIndex,
                            selectedChannelIndex = selectedChannelIndex,
                            currentChannel = currentChannel,
                            onGoSettings = {},
                            onPasswordNeeded = onPasswordNeeded,
                        )
                    }
                }
            }
        }
        return
    }

    // TV 非全屏: 播放器(72%) + 右侧面板(28%); 播放器区域 OK 键进入全屏.
    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(0.72f)
                .fillMaxHeight()
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                    ) {
                        vm.enterTvFullscreen()
                        true
                    } else {
                        false
                    }
                },
        ) {
            PlayerArea(vm = vm, tv = true, fullscreen = false, videoAspectRatio = videoAspectRatio)
        }
        Box(modifier = Modifier.weight(0.28f).fillMaxHeight()) {
            Column {
                InfoBar(vm = vm, tv = true)
                LiveBodyArea(
                    vm = vm,
                    tv = true,
                    groups = groups,
                    loading = loading,
                    noSource = noSource,
                    error = error,
                    selectedGroupIndex = selectedGroupIndex,
                    selectedChannelIndex = selectedChannelIndex,
                    currentChannel = currentChannel,
                    onGoSettings = {},
                    onPasswordNeeded = onPasswordNeeded,
                )
            }
        }
    }
}

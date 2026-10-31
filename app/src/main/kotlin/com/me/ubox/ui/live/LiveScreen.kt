package com.me.ubox.ui.live

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.drop
import com.me.ubox.TvMode
import com.me.ubox.data.live.LiveChannelGroup
import com.me.ubox.data.live.LiveChannelItem
import com.me.ubox.ui.AppShell
import com.me.ubox.ui.AppSnackbar
import com.me.ubox.ui.components.Badge
import com.me.ubox.ui.components.ImmersiveSystemBarsEffect
import com.me.ubox.ui.components.LiveListItem
import com.me.ubox.ui.components.VideoSurfaceView
import com.me.ubox.ui.components.pageFocusGuard
import com.me.ubox.ui.player.PlayerOptions
import com.me.ubox.ui.theme.LiveColors

/**
 * 直播页: 三套布局, 分别对照参考实现
 *   - TV (遥控器) = NovaTV LivePlayActivity: 视频铺满, 确定键呼出左侧频道面板,
 *                   面板 = 分组 / 频道 / 线路 三列竖向平行,
 *                   上下键换台, 左右键换线路, MENU 键循环画面比例 (不做设置菜单);
 *   - 平板 (触摸) = NovaBox layout-sw600dp: 左 72% 播放器 | 右 28% 面板 (52dp 信息栏 + 三列列表);
 *   - 手机 (触摸) = NovaBox layout: 播放器 weight 2 + 52dp 信息栏 + 三列列表 weight 3.
 *
 * 三端共用: 分组 / 频道 / 线路 三列, 频道名角标, 分组密码, 自动换源.
 */
@Composable
fun LiveScreen(
    onGoSettings: () -> Unit,
    vm: LiveViewModel = viewModel(),
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val remote by TvMode.isRemote.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val noSource by vm.noSource.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val selectedGroupIndex by vm.selectedGroupIndex.collectAsStateWithLifecycle()
    val selectedChannelIndex by vm.selectedChannelIndex.collectAsStateWithLifecycle()
    val currentChannel by vm.currentChannel.collectAsStateWithLifecycle()
    val videoMounted by vm.videoMounted.collectAsStateWithLifecycle()
    val tvPanel by vm.tvPanel.collectAsStateWithLifecycle()
    val fullscreen by vm.fullscreen.collectAsStateWithLifecycle()
    val badgeVisible by vm.badgeVisible.collectAsStateWithLifecycle()
    val badgeName by vm.badgeName.collectAsStateWithLifecycle()
    val videoAspectRatio by vm.videoAspectRatio.collectAsStateWithLifecycle()
    val scaleType by vm.scaleType.collectAsStateWithLifecycle()

    var showPasswordDialog by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) { vm.bootstrap() }

    // 直播源地址变化 / 设置页"更新缓存" → 重载
    LaunchedEffect(Unit) {
        com.me.ubox.data.live.LiveStore.liveUrlChanged.drop(1).collect { vm.onLiveUrlChanged() }
    }
    LaunchedEffect(Unit) {
        com.me.ubox.data.live.LiveStore.reloadTick.drop(1).collect { vm.onLiveUrlChanged() }
    }

    // 沉浸式系统栏: TV 恒全屏; 手机/平板仅全屏时沉浸式.
    ImmersiveSystemBarsEffect(immersive = remote || fullscreen)
    // TV 端直播页整页铺满 (对照 NovaTV LivePlayActivity 的 Activity 全屏).
    DisposableEffect(remote) {
        if (remote) AppShell.setLiveFullscreen(true)
        onDispose { AppShell.setLiveFullscreen(false) }
    }

    // 屏幕方向: 平板恒横屏; 手机进全屏转横屏, 退出复位 (对照 NovaBox OrientationHelper).
    val context = LocalContext.current
    LaunchedEffect(fullscreen, tv) {
        val act = context.findLiveActivity() ?: return@LaunchedEffect
        act.requestedOrientation = when {
            tv -> TvMode.baseOrientation()
            fullscreen -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> TvMode.baseOrientation()
        }
    }
    DisposableEffect(Unit) {
        onDispose { context.findLiveActivity()?.requestedOrientation = TvMode.baseOrientation() }
    }

    // 面板 / 全屏打开时返回键先收起.
    if (remote) {
        BackHandler(enabled = tvPanel) { vm.hideTvPanel() }
    } else {
        BackHandler(enabled = fullscreen) { vm.exitFullscreen() }
    }

    when {
        remote -> TvLiveLayout(
            vm = vm,
            groups = groups,
            selectedGroupIndex = selectedGroupIndex,
            selectedChannelIndex = selectedChannelIndex,
            currentChannel = currentChannel,
            videoMounted = videoMounted,
            tvPanel = tvPanel,
            badgeVisible = badgeVisible,
            badgeName = badgeName,
            videoAspectRatio = videoAspectRatio,
            scaleType = scaleType,
            onPasswordNeeded = { showPasswordDialog = it },
        )

        tv -> TabletLiveLayout(
            vm = vm,
            groups = groups,
            loading = loading,
            noSource = noSource,
            error = error,
            selectedGroupIndex = selectedGroupIndex,
            selectedChannelIndex = selectedChannelIndex,
            currentChannel = currentChannel,
            videoMounted = videoMounted,
            fullscreen = fullscreen,
            badgeVisible = badgeVisible,
            badgeName = badgeName,
            videoAspectRatio = videoAspectRatio,
            scaleType = scaleType,
            onPasswordNeeded = { showPasswordDialog = it },
        )

        else -> PhoneLiveLayout(
            vm = vm,
            groups = groups,
            loading = loading,
            noSource = noSource,
            error = error,
            selectedGroupIndex = selectedGroupIndex,
            selectedChannelIndex = selectedChannelIndex,
            currentChannel = currentChannel,
            videoMounted = videoMounted,
            fullscreen = fullscreen,
            badgeVisible = badgeVisible,
            badgeName = badgeName,
            videoAspectRatio = videoAspectRatio,
            scaleType = scaleType,
            onPasswordNeeded = { showPasswordDialog = it },
        )
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
                    if (!ok) AppSnackbar.show("密码错误")
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = null }) { Text("取消") }
            },
        )
    }
}

private tailrec fun Context.findLiveActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findLiveActivity()
    else -> null
}

// ---------------------------------------------------------------- 视频区 (三端共用)

/**
 * 视频表面 + 缓冲转圈 + 右上角频道名角标 (切台后短暂显示).
 * [scaleType] 决定画面比例; 未设置比例时按视频真实比例适应.
 */
@Composable
private fun LiveVideo(
    vm: LiveViewModel,
    videoMounted: Boolean,
    videoAspectRatio: Float,
    scaleType: Int,
    badgeVisible: Boolean,
    badgeName: String,
    tv: Boolean,
    modifier: Modifier = Modifier,
) {
    val buffering by vm.buffering.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val noSource by vm.noSource.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()

    Box(modifier = modifier.background(Color.Black)) {
        if (videoMounted) {
            VideoSurfaceView(
                modifier = Modifier.fillMaxSize(),
                aspectRatio = if (videoAspectRatio > 0f) videoAspectRatio else null,
                scaleType = scaleType,
                onSurfaceReady = { view -> vm.attachSurface(view.holder.surface) },
                onSurfaceDispose = { vm.detachSurface() },
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    noSource -> NoSourceHint(onGoSettings = { AppShell.selectTab(AppShell.SETTINGS_TAB) })
                    error.isNotEmpty() && groups.isEmpty() ->
                        LiveErrorHint(error = error, onRetry = vm::loadLives)
                    else -> Spacer(Modifier)
                }
            }
        }
        if (buffering && !playing) {
            CircularProgressIndicator(
                strokeWidth = 3.dp,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).size(if (tv) 50.dp else 36.dp),
            )
        }
        if (badgeVisible) {
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(if (tv) 10.dp else 6.dp)) {
                Badge(text = badgeName, bg = LiveColors.BadgeBg, tv = tv)
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
            modifier = Modifier.size(48.dp),
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
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry, modifier = Modifier.pageFocusGuard()) { Text("重试") }
    }
}

/** 播放器下方 52dp 信息栏: 频道名 (bold) —— 对照 NovaBox live_info_bar. */
@Composable
private fun LiveInfoBar(vm: LiveViewModel, tablet: Boolean) {
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
            .padding(start = if (tablet) 14.dp else 16.dp, end = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Bold,
            fontSize = if (tablet) 16.sp else 18.sp,
        )
    }
}

// ---------------------------------------------------------------- 三列列表

/**
 * 分组 / 频道 / 线路 三列 (对照 NovaBox live_channel_area).
 * 权重: 手机 1 : 1.5 : 1, 平板 0.5 : 0.8 : 0.5.
 */
@Composable
private fun LiveChannelArea(
    vm: LiveViewModel,
    groups: List<LiveChannelGroup>,
    loading: Boolean,
    noSource: Boolean,
    error: String,
    selectedGroupIndex: Int,
    selectedChannelIndex: Int,
    currentChannel: LiveChannelItem?,
    large: Boolean,
    weights: List<Float>,
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
        Box(modifier = Modifier.weight(weights[0]).fillMaxHeight()) {
            GroupList(
                vm = vm,
                groups = groups,
                selectedGroupIndex = selectedGroupIndex,
                large = large,
                onPasswordNeeded = onPasswordNeeded,
            )
        }
        Box(modifier = Modifier.weight(weights[1]).fillMaxHeight()) {
            ChannelList(vm = vm, large = large, selectedChannelIndex = selectedChannelIndex)
        }
        Box(modifier = Modifier.weight(weights[2]).fillMaxHeight()) {
            SourceList(vm = vm, large = large, currentChannel = currentChannel)
        }
    }
}

@Composable
private fun GroupList(
    vm: LiveViewModel,
    groups: List<LiveChannelGroup>,
    selectedGroupIndex: Int,
    large: Boolean,
    onPasswordNeeded: (Int) -> Unit,
) {
    val groupState = rememberLazyListState(
        initialFirstVisibleItemIndex = maxOf(0, groups.indexOfFirst { it.groupIndex == selectedGroupIndex } - 3),
    )
    LazyColumn(state = groupState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp)) {
        items(groups, key = { it.groupIndex }) { g ->
            LiveListItem(
                text = g.groupName,
                selected = g.groupIndex == selectedGroupIndex,
                large = large,
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
    large: Boolean,
    selectedChannelIndex: Int,
    autofocusSelected: Boolean = false,
) {
    val selectedGroupIndex by vm.selectedGroupIndex.collectAsStateWithLifecycle()
    val channels = vm.channelsOf(selectedGroupIndex)
    // 已选频道在长列表靠后时, 懒列表里它根本没有被组合, autofocus 不会发生 -> 面板里没有任何焦点,
    // 方向键无法移动. 先把列表滚到已选频道附近, 让该项被组合后再 autofocus.
    val channelState = rememberLazyListState(
        initialFirstVisibleItemIndex = maxOf(0, channels.indexOfFirst { it.channelIndex == selectedChannelIndex } - 3),
    )
    LazyColumn(state = channelState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp)) {
        items(channels, key = { it.channelNum }) { c ->
            LiveListItem(
                num = "${c.channelNum}",
                text = c.channelName,
                selected = c.channelIndex == selectedChannelIndex,
                large = large,
                autofocus = autofocusSelected && c.channelIndex == selectedChannelIndex,
                onClick = { vm.clickLiveChannel(c.channelIndex) },
            )
        }
    }
}

@Composable
private fun SourceList(
    vm: LiveViewModel,
    large: Boolean,
    currentChannel: LiveChannelItem?,
    scrollToSelected: Boolean = false,
) {
    if (currentChannel == null) return
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = maxOf(0, currentChannel.sourceIndex - 3),
    )
    // 面板呼出前用左右键换过源时, 选中源可能落在可视区之外 -> 滚动到选中项.
    LaunchedEffect(scrollToSelected, currentChannel.channelIndex, currentChannel.sourceIndex) {
        if (scrollToSelected) state.animateScrollToItem(maxOf(0, currentChannel.sourceIndex - 3))
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp)) {
        items(currentChannel.sourceNum) { i ->
            LiveListItem(
                text = currentChannel.channelSourceNames.getOrElse(i) { "源${i + 1}" },
                selected = i == currentChannel.sourceIndex,
                large = large,
                onClick = { vm.playChannelBySource(i) },
            )
        }
    }
}

// ---------------------------------------------------------------- 触摸端 (手机 / 平板)

/** 手机: 播放器 weight 2 + 52dp 信息栏 + 三列列表 weight 3; 全屏时列表与信息栏隐藏. */
@Composable
private fun PhoneLiveLayout(
    vm: LiveViewModel,
    groups: List<LiveChannelGroup>,
    loading: Boolean,
    noSource: Boolean,
    error: String,
    selectedGroupIndex: Int,
    selectedChannelIndex: Int,
    currentChannel: LiveChannelItem?,
    videoMounted: Boolean,
    fullscreen: Boolean,
    badgeVisible: Boolean,
    badgeName: String,
    videoAspectRatio: Float,
    scaleType: Int,
    onPasswordNeeded: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(if (fullscreen) 1f else 2f)) {
            TouchPlayerArea(
                vm = vm,
                videoMounted = videoMounted,
                fullscreen = fullscreen,
                badgeVisible = badgeVisible,
                badgeName = badgeName,
                videoAspectRatio = videoAspectRatio,
                scaleType = scaleType,
                tablet = false,
            )
        }
        if (!fullscreen) {
            LiveInfoBar(vm = vm, tablet = false)
            Box(modifier = Modifier.weight(3f)) {
                LiveChannelArea(
                    vm = vm,
                    groups = groups,
                    loading = loading,
                    noSource = noSource,
                    error = error,
                    selectedGroupIndex = selectedGroupIndex,
                    selectedChannelIndex = selectedChannelIndex,
                    currentChannel = currentChannel,
                    large = false,
                    weights = listOf(1f, 1.5f, 1f),
                    onPasswordNeeded = onPasswordNeeded,
                )
            }
        }
    }
}

/** 平板: 左 72% 播放器 | 右 28% (52dp 信息栏 + 三列列表 0.5 : 0.8 : 0.5); 全屏时右侧隐藏. */
@Composable
private fun TabletLiveLayout(
    vm: LiveViewModel,
    groups: List<LiveChannelGroup>,
    loading: Boolean,
    noSource: Boolean,
    error: String,
    selectedGroupIndex: Int,
    selectedChannelIndex: Int,
    currentChannel: LiveChannelItem?,
    videoMounted: Boolean,
    fullscreen: Boolean,
    badgeVisible: Boolean,
    badgeName: String,
    videoAspectRatio: Float,
    scaleType: Int,
    onPasswordNeeded: (Int) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(if (fullscreen) 1f else 0.72f).fillMaxHeight()) {
            TouchPlayerArea(
                vm = vm,
                videoMounted = videoMounted,
                fullscreen = fullscreen,
                badgeVisible = badgeVisible,
                badgeName = badgeName,
                videoAspectRatio = videoAspectRatio,
                scaleType = scaleType,
                tablet = true,
            )
        }
        if (!fullscreen) {
            Column(modifier = Modifier.weight(0.28f).fillMaxHeight()) {
                LiveInfoBar(vm = vm, tablet = true)
                Box(modifier = Modifier.weight(1f)) {
                    LiveChannelArea(
                        vm = vm,
                        groups = groups,
                        loading = loading,
                        noSource = noSource,
                        error = error,
                        selectedGroupIndex = selectedGroupIndex,
                        selectedChannelIndex = selectedChannelIndex,
                        currentChannel = currentChannel,
                        large = true,
                        weights = listOf(0.5f, 0.8f, 0.5f),
                        onPasswordNeeded = onPasswordNeeded,
                    )
                }
            }
        }
    }
}

/**
 * 触摸端播放器: 点击画面唤出控制浮层 (对照 NovaBox live_control_overlay),
 * 浮层 = 返回 (仅全屏) + 中央播放暂停 + 刷新 + 全屏, 4 秒无操作自动隐藏.
 */
@Composable
private fun BoxScope.TouchPlayerArea(
    vm: LiveViewModel,
    videoMounted: Boolean,
    fullscreen: Boolean,
    badgeVisible: Boolean,
    badgeName: String,
    videoAspectRatio: Float,
    scaleType: Int,
    tablet: Boolean,
) {
    val overlayVisible by vm.overlayVisible.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()

    LiveVideo(
        vm = vm,
        videoMounted = videoMounted,
        videoAspectRatio = videoAspectRatio,
        scaleType = scaleType,
        badgeVisible = badgeVisible,
        badgeName = badgeName,
        tv = false,
        modifier = Modifier.fillMaxSize(),
    )
    if (!overlayVisible) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickableNoRipple { vm.toggleOverlay() },
        )
    } else {
        LiveControlOverlay(
            playing = playing,
            fullscreen = fullscreen,
            tablet = tablet,
            onPlayPause = { vm.togglePlayPause(); vm.toggleOverlay() },
            onRefresh = { vm.refreshChannel(); vm.toggleOverlay() },
            onFullscreen = { vm.toggleFullscreen() },
            onBack = { vm.exitFullscreen() },
            onDismiss = { vm.hideOverlay() },
        )
    }
}

/** 直播控制浮层: 左上返回 (仅全屏) + 中央播放/暂停 + 左下刷新 + 右下全屏. */
@Composable
private fun BoxScope.LiveControlOverlay(
    playing: Boolean,
    fullscreen: Boolean,
    tablet: Boolean,
    onPlayPause: () -> Unit,
    onRefresh: () -> Unit,
    onFullscreen: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val icon = if (tablet) 44.dp else 40.dp
    val center = if (tablet) 64.dp else 56.dp
    val action = if (tablet) 40.dp else 36.dp
    val pad = if (tablet) 10.dp else 8.dp
    Box(
        modifier = Modifier
            .matchParentSize()
            .clickableNoRipple(onClick = onDismiss),
    ) {
        if (fullscreen) {
            OverlayIconButton(
                imageVector = Icons.Filled.FullscreenExit,
                size = icon,
                padding = pad,
                modifier = Modifier.align(Alignment.TopStart),
                onClick = onBack,
            )
        }
        OverlayIconButton(
            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            size = center,
            padding = 0.dp,
            modifier = Modifier.align(Alignment.Center),
            onClick = onPlayPause,
        )
        OverlayIconButton(
            imageVector = Icons.Filled.Refresh,
            size = action,
            padding = pad,
            modifier = Modifier.align(Alignment.BottomStart),
            onClick = onRefresh,
        )
        OverlayIconButton(
            imageVector = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
            size = action,
            padding = pad,
            modifier = Modifier.align(Alignment.BottomEnd),
            onClick = onFullscreen,
        )
    }
}

@Composable
private fun OverlayIconButton(
    imageVector: ImageVector,
    size: Dp,
    padding: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .padding(padding)
            .background(Color(0x66000000), CircleShape)
            .clickableNoRipple(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.fillMaxSize(0.62f),
        )
    }
}

/** 无涟漪点击: 浮层本身是半透明圆, 不需要 ripple 反馈. */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        onClick = onClick,
    )

// ---------------------------------------------------------------- TV (遥控器)

/**
 * TV 直播页 (对照 NovaTV LivePlayActivity): 视频恒铺满, 确定键呼出右侧频道面板.
 *
 * 按键 (面板隐藏时):
 *   上 / 频道+ = 上一个频道;  下 / 频道- = 下一个频道;
 *   左 = 上一个线路;          右 = 下一个线路;
 *   确定 = 呼出频道面板;      MENU = 循环画面比例 (不做设置菜单).
 * 面板显示时方向键交给焦点遍历, 返回键收起面板.
 */
@Composable
private fun TvLiveLayout(
    vm: LiveViewModel,
    groups: List<LiveChannelGroup>,
    selectedGroupIndex: Int,
    selectedChannelIndex: Int,
    currentChannel: LiveChannelItem?,
    videoMounted: Boolean,
    tvPanel: Boolean,
    badgeVisible: Boolean,
    badgeName: String,
    videoAspectRatio: Float,
    scaleType: Int,
    onPasswordNeeded: (Int) -> Unit,
) {
    val loading by vm.loading.collectAsStateWithLifecycle()
    val noSource by vm.noSource.collectAsStateWithLifecycle()
    val rootFocus = remember { FocusRequester() }
    // 面板收起后把焦点还给画面 (面板里的焦点节点已随面板移除).
    LaunchedEffect(tvPanel) {
        if (!tvPanel) {
            repeat(6) {
                withFrameNanos { }
                try {
                    rootFocus.requestFocus()
                    return@LaunchedEffect
                } catch (_: IllegalStateException) {
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (tvPanel) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
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
                        val s = vm.cycleScale()
                        AppSnackbar.show("画面比例: ${PlayerOptions.SCALE_NAMES[s]}")
                        true
                    }
                    else -> false
                }
            },
    ) {
        LiveVideo(
            vm = vm,
            videoMounted = videoMounted,
            videoAspectRatio = videoAspectRatio,
            scaleType = scaleType,
            badgeVisible = badgeVisible,
            badgeName = badgeName,
            tv = true,
            modifier = Modifier.fillMaxSize(),
        )
        if (tvPanel) {
            TvChannelPanel(
                vm = vm,
                groups = groups,
                loading = loading,
                noSource = noSource,
                selectedGroupIndex = selectedGroupIndex,
                selectedChannelIndex = selectedChannelIndex,
                currentChannel = currentChannel,
                onPasswordNeeded = onPasswordNeeded,
            )
        }
    }
}

/** TV 频道面板宽度: 分组列 + 频道列 (自适应) + 线路列. */
private val TV_PANEL_WIDTH = 580.dp

/** 分组列 / 线路列固定宽度, 中间频道列吃剩余. */
private val TV_SIDE_COLUMN_WIDTH = 170.dp

/**
 * TV 频道面板 (贴左侧): 分组 / 频道 / 线路 三列竖向平行, 每列各带标题头.
 * 遥控器左右键在三列之间移动焦点, 与手机 / 平板的三列结构保持一致.
 */
@Composable
private fun BoxScope.TvChannelPanel(
    vm: LiveViewModel,
    groups: List<LiveChannelGroup>,
    loading: Boolean,
    noSource: Boolean,
    selectedGroupIndex: Int,
    selectedChannelIndex: Int,
    currentChannel: LiveChannelItem?,
    onPasswordNeeded: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            .width(TV_PANEL_WIDTH)
            .background(Color(0xE6101418))
            .clickableNoRipple { },
    ) {
        // 1) 分组列
        Column(modifier = Modifier.width(TV_SIDE_COLUMN_WIDTH).fillMaxHeight()) {
            PanelHeader("分组")
            if (noSource || groups.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (loading) "加载中…" else "暂无分组",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 16.sp,
                    )
                }
            } else {
                Box(modifier = Modifier.weight(1f)) {
                    GroupList(
                        vm = vm,
                        groups = groups,
                        selectedGroupIndex = selectedGroupIndex,
                        large = true,
                        onPasswordNeeded = onPasswordNeeded,
                    )
                }
            }
        }
        // 2) 频道列
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            PanelHeader(currentChannel?.channelName ?: "请选择频道")
            Box(modifier = Modifier.weight(1f)) {
                ChannelList(
                    vm = vm,
                    large = true,
                    selectedChannelIndex = selectedChannelIndex,
                    autofocusSelected = true,
                )
            }
        }
        // 3) 线路列 (竖向, 与分组 / 频道两列平行)
        Column(modifier = Modifier.width(TV_SIDE_COLUMN_WIDTH).fillMaxHeight()) {
            PanelHeader(
                if (currentChannel != null && currentChannel.sourceNum > 0) {
                    "线路 (${currentChannel.sourceIndex + 1}/${currentChannel.sourceNum})"
                } else {
                    "线路"
                },
            )
            Box(modifier = Modifier.weight(1f)) {
                SourceList(
                    vm = vm,
                    large = true,
                    currentChannel = currentChannel,
                    scrollToSelected = true,
                )
            }
        }
    }
}

@Composable
private fun PanelHeader(text: String) {
    Text(
        text = text,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x33000000))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

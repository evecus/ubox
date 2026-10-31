package com.me.ubox.ui.vod

import android.app.Activity
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.me.ubox.TvMode
import com.me.ubox.data.PlayUrl
import com.me.ubox.data.Site
import com.me.ubox.ui.AppSnackbar
import com.me.ubox.ui.components.ImmersiveSystemBarsEffect
import com.me.ubox.ui.components.NetworkImage
import com.me.ubox.ui.components.TvButton
import com.me.ubox.ui.components.TvFocusable
import com.me.ubox.ui.components.VideoSurfaceView
import com.me.ubox.ui.components.pageFocusGuard
import com.me.ubox.ui.dialogs.DanmuFullSettingDialog
import com.me.ubox.ui.dialogs.DanmuSearchDialog
import com.me.ubox.ui.dialogs.SinglePickSheet
import com.me.ubox.ui.player.BoostIndicator
import com.me.ubox.ui.player.DanmakuLayer
import com.me.ubox.ui.player.PlayerIcons
import com.me.ubox.ui.player.PlayerOptions
import com.me.ubox.ui.player.PlayerOsd
import com.me.ubox.ui.player.PlayerOsdView
import com.me.ubox.ui.player.PlayerSeekBar
import com.me.ubox.ui.player.PlayerUiState
import com.me.ubox.ui.player.fmtTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 点播页 —— 三端同一个页面 (影视信息 + 源 + 剧集 + 播放, 可切全屏):
 *
 *   TV (遥控器) = 左播放器 58% | 右信息面板 42% (影视信息 / 源 / 剧集), 点播放器或"全屏"进全屏;
 *   平板        = 左 73% (16:9 播放器) | 右 27% (信息 / 源 / 剧集)
 *                (对照 NovaBox layout-sw600dp/activity_detail.xml 的 73 / 27 分栏);
 *   手机        = 上 16:9 播放器 (预览窗) | 下 信息 / 源 / 剧集 (竖向滚动)
 *                (对照 NovaBox layout/activity_detail.xml);
 *   全屏        = 播放器铺满 —— TV 用遥控器控制条, 手机/平板用触摸手势 + 底部功能条.
 *
 * 切换全屏只改变布局, 播放器实例不变 (对照 NovaBox: 预览窗与全屏共用同一个 Fragment).
 */
@Composable
fun VodScreen(
    siteKey: String,
    vodId: String,
    vodName: String,
    onBack: () -> Unit,
    onSwitchSite: (String, String, String) -> Unit,
    vm: VodViewModel = viewModel(
        factory = viewModelFactory {
            initializer { VodViewModel(siteKey, vodId, vodName) }
        }
    ),
) {
    val ui = remember { PlayerUiState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val remote by TvMode.isRemote.collectAsStateWithLifecycle()
    // 平板 = TV 布局 + 触摸交互; 手机 = 手机布局 + 触摸交互.
    val tablet = tv && !remote
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

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
    val switchMessage by vm.switchMessage.collectAsStateWithLifecycle()

    var showSpeed by remember { mutableStateOf(false) }
    var showScale by remember { mutableStateOf(false) }
    var showSkip by remember { mutableStateOf(false) }
    var showDanmuSetting by remember { mutableStateOf(false) }
    var showDanmuSearch by remember { mutableStateOf(false) }
    var showEpisodes by remember { mutableStateOf(false) }
    var showSwitchMenu by remember { mutableStateOf(false) }
    val anyDialog = showSpeed || showScale || showSkip || showDanmuSetting ||
        showDanmuSearch || showEpisodes || showSwitchMenu

    LaunchedEffect(switchMessage) {
        val msg = switchMessage ?: return@LaunchedEffect
        AppSnackbar.show(msg)
        vm.consumeSwitchMessage()
    }

    // ---------------- 屏幕方向 ----------------
    // 手机: 转横屏自动全屏, 回竖屏退出全屏; 点全屏按钮 = 锁横屏 -> 进入同一条路径.
    // TV / 平板: 始终按基准方向 (横屏锁定), 全屏只是布局变化.
    LaunchedEffect(landscape, tv) {
        if (!tv) ui.fullscreen = landscape
    }
    var wasFullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(ui.fullscreen, tv) {
        val act = context.findVodActivity() ?: return@LaunchedEffect
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
            context.findVodActivity()?.requestedOrientation = TvMode.baseOrientation()
        }
    }
    // 退出播放页时复位到当前模式的基准方向.
    DisposableEffect(Unit) {
        onDispose { context.findVodActivity()?.requestedOrientation = TvMode.baseOrientation() }
    }

    // 全屏隐藏系统栏 + 屏幕常亮.
    ImmersiveSystemBarsEffect(immersive = remote || ui.fullscreen)
    DisposableEffect(Unit) {
        val window = view.context.findVodActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 返回键 (TV 全屏): 控制层可见 -> 先收控制层; 再按 -> 回小屏.
    // 手机 / 平板的返回键由触摸端自己处理 (锁屏提示 / 退出全屏).
    BackHandler(enabled = remote && ui.fullscreen && ui.overlay) { ui.hideOverlay() }
    BackHandler(enabled = remote && ui.fullscreen) { ui.fullscreen = false }

    if (remote) {
        if (ui.fullscreen) {
            FullscreenPlayer(
                vm = vm,
                ui = ui,
                scope = scope,
                groups = groups,
                flagIndex = flagIndex,
                epIndex = epIndex,
                playing = playing,
                buffering = buffering,
                loading = loading,
                error = error,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                boosting = boosting,
                speed = speed,
                scaleType = scaleType,
                aspect = aspect,
                vw = vw,
                vh = vh,
                anyDialog = anyDialog,
                onSpeed = { showSpeed = true },
                onScale = { showScale = true },
                onSkip = { showSkip = true },
                onDanmuSetting = { showDanmuSetting = true },
                onDanmuSearch = { showDanmuSearch = true },
                onEpisodes = { showEpisodes = true },
            )
        } else {
            SmallLayout(
                vm = vm,
                groups = groups,
                flagIndex = flagIndex,
                epIndex = epIndex,
                detailLoading = detailLoading,
                detailError = detailError,
                vod = vod,
                loading = loading,
                error = error,
                playing = playing,
                buffering = buffering,
                positionMs = positionMs,
                durationMs = durationMs,
                aspect = aspect,
                vw = vw,
                vh = vh,
                fav = fav,
                switching = switching,
                scaleType = scaleType,
                onReload = vm::loadDetail,
                onFullscreen = { ui.fullscreen = true },
                onToggleFav = vm::toggleFav,
                onSwitchMenu = { showSwitchMenu = true },
            )
        }
    } else {
        VodTouchScreen(
            vm = vm,
            ui = ui,
            tablet = tablet,
            onBack = onBack,
            onSpeed = { showSpeed = true },
            onScale = { showScale = true },
            onSkip = { showSkip = true },
            onDanmuSetting = { showDanmuSetting = true },
            onDanmuSearch = { showDanmuSearch = true },
            onSwitchMenu = { showSwitchMenu = true },
        )
    }

    // ---------------- 弹层 (三端共用) ----------------
    if (showSpeed) {
        SinglePickSheet(
            title = "倍速",
            items = PlayerOptions.SPEEDS,
            labelOf = { PlayerOptions.speedLabel(it) },
            current = speed,
            onPick = { vm.setSpeed(it); showSpeed = false },
            onDismiss = { showSpeed = false },
        )
    }
    if (showScale) {
        SinglePickSheet(
            title = "画面比例",
            items = PlayerOptions.SCALE_NAMES.indices.toList(),
            labelOf = { PlayerOptions.SCALE_NAMES[it] },
            current = scaleType,
            onPick = { vm.setScale(it); showScale = false },
            onDismiss = { showScale = false },
        )
    }
    if (showSkip) {
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
                    0 -> {
                        vm.markSkipStart()
                        AppSnackbar.show("片头已设为 ${fmtTime(pos)}")
                    }
                    1 -> {
                        vm.markSkipEnd()
                        AppSnackbar.show("片尾已设为结尾前 ${fmtTime(rest)}")
                    }
                    else -> {
                        vm.clearSkip()
                        AppSnackbar.show("已清除片头片尾")
                    }
                }
                showSkip = false
            },
            onDismiss = { showSkip = false },
        )
    }
    if (showDanmuSetting) {
        DanmuFullSettingDialog(onDismiss = { showDanmuSetting = false })
    }
    if (showDanmuSearch) {
        DanmuSearchDialog(
            initialWord = vm.title,
            episode = vm.epName(),
            onLoaded = { vm.loadDanmakuSource(it) },
            onDismiss = { showDanmuSearch = false },
        )
    }
    if (showEpisodes) {
        EpisodePickerDialog(
            groups = groups,
            flagIndex = flagIndex,
            epIndex = epIndex,
            onSelectFlag = vm::selectFlag,
            onSelectEp = { i -> vm.selectEpisode(i); showEpisodes = false },
            onDismiss = { showEpisodes = false },
        )
    }
    if (showSwitchMenu) {
        val candidates = vm.switchCandidates()
        SinglePickSheet(
            title = "搜索 \"${vm.title}\" 并换源",
            items = candidates,
            labelOf = { it.displayName },
            subtitleOf = { it.key },
            current = null,
            onPick = { target ->
                showSwitchMenu = false
                vm.switchSource(target) { k, id, name -> onSwitchSite(k, id, name) }
            },
            onDismiss = { showSwitchMenu = false },
        )
    }
}

private tailrec fun Context.findVodActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findVodActivity()
    else -> null
}

// ---------------------------------------------------------------- 小屏态 (播放器 + 信息面板)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmallLayout(
    vm: VodViewModel,
    groups: List<PlayUrl>,
    flagIndex: Int,
    epIndex: Int,
    detailLoading: Boolean,
    detailError: String,
    vod: org.json.JSONObject?,
    loading: Boolean,
    error: String,
    playing: Boolean,
    buffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    aspect: Float,
    vw: Int,
    vh: Int,
    fav: Boolean,
    switching: Boolean,
    scaleType: Int,
    onReload: () -> Unit,
    onFullscreen: () -> Unit,
    onToggleFav: () -> Unit,
    onSwitchMenu: () -> Unit,
) {
    val context = LocalContext.current
    val g = groups.getOrNull(flagIndex)
    val total = g?.eps?.size ?: 0
    // 正序/倒序 + 分页 (TV 详情页本地状态, 不影响手机/平板与 ViewModel).
    var reverse by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    LaunchedEffect(flagIndex) { if (flagIndex >= 0) { page = 0; reverse = false } }
    val PAGE_SIZE = 20
    val paged = total > PAGE_SIZE
    val pageCount = if (total <= 0) 0 else (total + PAGE_SIZE - 1) / PAGE_SIZE
    val safePage = page.coerceIn(0, maxOf(0, pageCount - 1))
    val ordered = remember(total, reverse) {
        if (reverse) (0 until total).reversed().toList() else (0 until total).toList()
    }
    val pageItems = if (paged) ordered.drop(safePage * PAGE_SIZE).take(PAGE_SIZE) else ordered
    var showDesc by remember { mutableStateOf(false) }
    val des = com.me.ubox.data.JsonUtil.optStringOrNull(vod, "vod_content").orEmpty()

    // 剧集网格列数 (对照 NovaTV refreshList: 按最长集名自适应 2~6 列).
    val maxChars = remember(g) { g?.eps?.maxOfOrNull { it.length } ?: 0 }
    val cols = when {
        maxChars <= 5 -> 6
        maxChars <= 8 -> 5
        maxChars <= 12 -> 4
        maxChars <= 16 -> 3
        else -> 2
    }
    val gridState = rememberLazyGridState()
    // 当前播放集变化 / 换线路 / 切正倒序: 自动翻到所在分页 (对照 NovaTV 选中集定位).
    LaunchedEffect(epIndex, flagIndex, reverse, total) {
        if (paged) {
            val pos = ordered.indexOf(epIndex)
            if (pos >= 0) page = pos / PAGE_SIZE
        }
    }
    // 翻页 / 选中变化后: 把当前集滚到可见区 (留出上一行).
    LaunchedEffect(safePage, epIndex, reverse, total, cols) {
        val idx = pageItems.indexOf(epIndex)
        runCatching { gridState.scrollToItem(if (idx >= 0) maxOf(0, idx - cols) else 0) }
    }

    // NovaTV 的布局按 1280 设计宽度等比缩放: 顶部区高 300 单位 (预览播放器 470x300).
    // 注意: 顶部 Row 必须给定固定高度. 之前 Row 里的信息列用了 fillMaxHeight(), 而 Row 自身没有高度,
    // 会撑满整个屏幕剩余高度, 下面的"剧集网格"(weight=1) 分到的高度为 0 —— 即"没有剧集列表".
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    val topH = minOf(maxWidth / 1280f * 300f, maxHeight * 0.46f)
    val playerW = topH * (470f / 300f)
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.height(10.dp))
        // ===== 顶部: 圆角预览播放器 + 影视信息 (对照 NovaTV topLayout) =====
        Row(modifier = Modifier.fillMaxWidth().height(topH).padding(horizontal = 50.dp)) {
            // 左侧: 圆角预览播放器 (比例 470:300, 对照 NovaTV previewPlayer / preview_player_round).
            TvFocusable(
                onTap = onFullscreen,
                modifier = Modifier.width(playerW).fillMaxHeight(),
                radius = 12.dp,
                focusScale = 1.0f,
            ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
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
                if (loading || buffering) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(38.dp))
                }
                if (error.isNotEmpty()) {
                    Text(error, color = Color.White, fontSize = 12.sp, maxLines = 4, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(14.dp))
                }
                Text("${vm.title} · ${vm.epName()}", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
            }
            Spacer(Modifier.width(20.dp))
            // 右侧: 影视信息 (对照 NovaTV tvName / tvSite / tvYear / tvArea / tvType / tvActor / tvDirector / tvPlayUrl / tvDes).
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Text(vm.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                val metaSub = listOfNotNull(
                    vm.siteName.takeIf { it.isNotEmpty() }?.let { "来源：$it" },
                    com.me.ubox.data.JsonUtil.optStringOrNull(vod, "vod_year").takeIf { !it.isNullOrEmpty() }?.let { "年份：$it" },
                    com.me.ubox.data.JsonUtil.optStringOrNull(vod, "vod_area").takeIf { !it.isNullOrEmpty() }?.let { "地区：$it" },
                    com.me.ubox.data.JsonUtil.optStringOrNull(vod, "vod_lang").takeIf { !it.isNullOrEmpty() }?.let { "语言：$it" },
                    com.me.ubox.data.JsonUtil.optStringOrNull(vod, "vod_type").takeIf { !it.isNullOrEmpty() }?.let { "类型：$it" },
                )
                if (metaSub.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        metaSub.forEach { Text(it, color = Color(0xBBFFFFFF), fontSize = 18.sp, maxLines = 1) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                val actor = com.me.ubox.data.JsonUtil.optStringOrNull(vod, "vod_actor").orEmpty()
                if (actor.isNotEmpty()) {
                    Text("演员：$actor", color = Color(0xBBFFFFFF), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                }
                val director = com.me.ubox.data.JsonUtil.optStringOrNull(vod, "vod_director").orEmpty()
                if (director.isNotEmpty()) {
                    Text("导演：$director", color = Color(0xBBFFFFFF), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                }
                val playUrl = g?.urls?.getOrNull(epIndex).orEmpty()
                if (playUrl.isNotEmpty()) {
                    Text("播放地址：$playUrl", color = Color(0xBBFFFFFF), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(null, playUrl))
                        AppSnackbar.show("已复制播放地址")
                    }.padding(2.dp))
                    Spacer(Modifier.height(4.dp))
                }
                if (des.isNotEmpty()) {
                    Text("内容简介：$des", color = Color(0xBBFFFFFF), fontSize = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                // 四个彩色胶囊按钮 (对照 NovaTV button_detail_play / _quick_search / _sort / _collect).
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NovaColorButton("全屏", Color(0xFFFF5F00), Icons.Filled.Fullscreen, onFullscreen)
                    NovaColorButton("快速搜索", Color(0xFF1890FF), Icons.Filled.SwapHoriz, onSwitchMenu)
                    NovaColorButton("简介", Color(0xFF90CA09), null) { showDesc = true }
                    NovaColorButton(if (fav) "取消收藏" else "加入收藏", Color(0xFFFF0057), Icons.Filled.Favorite, onToggleFav)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // ===== 源 / 线路标签 (对照 NovaTV mGridViewFlag) =====
        if (groups.size > 1) {
            LazyRow(modifier = Modifier.padding(horizontal = 50.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(groups) { i, it ->
                    TvFocusable(onTap = { vm.selectFlag(i) }, radius = 8.dp, focusScale = 1.0f) {
                        FlagChip(name = it.flag, selected = i == flagIndex, onClick = { vm.selectFlag(i) }, interactive = false)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // ===== 正序/倒序 + 分页 (对照 NovaTV mSeriesGroupTv / mSeriesGroupView) =====
        if (paged) {
            Row(modifier = Modifier.padding(horizontal = 50.dp), verticalAlignment = Alignment.CenterVertically) {
                NovaChip(if (reverse) "倒序" else "正序", false) { reverse = !reverse }
                Spacer(Modifier.width(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed((0 until pageCount).toList()) { p, _ ->
                        val s = p * PAGE_SIZE + 1
                        val e = minOf((p + 1) * PAGE_SIZE, total)
                        NovaChip("$s-$e", p == safePage) { page = p }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // ===== 剧集网格 (对照 NovaTV mGridView, 6 列) =====
        when {
            detailLoading -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(strokeWidth = 2.dp) }
            }
            detailError.isNotEmpty() -> {
                Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(detailError, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    // 返回走遥控器返回键, 不在这里放"返回"按钮.
                    TextButton(onClick = onReload) { Text("重试") }
                }
            }
            else -> {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(cols),
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 50.dp, vertical = 6.dp),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(pageItems) { _, realIdx ->
                        val sel = realIdx == epIndex
                        val name = g?.eps?.getOrNull(realIdx).orEmpty()
                        NovaSeriesCell(name = name.ifEmpty { "第${realIdx + 1}集" }, selected = sel) { vm.selectEpisode(realIdx) }
                    }
                }
            }
        }
    }
    }
    // 简介弹窗 (对照 NovaTV DescDialog).
    if (showDesc) {
        AlertDialog(
            onDismissRequest = { showDesc = false },
            title = { Text("简介", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text(des.ifEmpty { "暂无简介" }, color = Color.White, fontSize = 15.sp) },
            confirmButton = { TextButton(onClick = { showDesc = false }) { Text("关闭") } },
        )
    }
}

// ---------------------------------------------------------------- TV 详情页专用: 彩色胶囊按钮 / 分页标签 / 剧集格

/** 四色胶囊按钮 (对照 NovaTV button_detail_*): 橙=全屏 / 蓝=快速搜索 / 绿=简介 / 粉=收藏. */
@Composable
private fun NovaColorButton(label: String, color: Color, icon: ImageVector?, onTap: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    TvFocusable(
        onTap = onTap,
        modifier = Modifier.height(40.dp),
        radius = 20.dp,
        borderColor = Color.White,
        showBorder = true,
        onFocusChanged = { focused = it },
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .background(color.copy(alpha = if (focused) 1f else 0.85f), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** 正序/倒序 + 分页标签 (对照 NovaTV shape_source_series_focus). */
@Composable
private fun NovaChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    TvFocusable(
        onTap = onClick,
        modifier = Modifier.height(40.dp),
        radius = 18.dp,
        borderColor = Color.White,
        showBorder = true,
        onFocusChanged = { focused = it },
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .background(if (selected) com.me.ubox.ui.player.AccentBlue else Color(0x6C3D3D3D), RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.White, fontSize = 15.sp, maxLines = 1)
        }
    }
}

/** 剧集格 (对照 NovaTV item_series / shape_source_series_focus). */
@Composable
private fun NovaSeriesCell(name: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    TvFocusable(
        onTap = onClick,
        modifier = Modifier.height(44.dp),
        radius = 8.dp,
        borderColor = Color.White,
        showBorder = true,
        onFocusChanged = { focused = it },
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .background(if (selected) com.me.ubox.ui.player.AccentBlue else Color(0x6C3D3D3D), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


// ---------------------------------------------------------------- 全屏态

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FullscreenPlayer(
    vm: VodViewModel,
    ui: PlayerUiState,
    scope: kotlinx.coroutines.CoroutineScope,
    groups: List<PlayUrl>,
    flagIndex: Int,
    epIndex: Int,
    playing: Boolean,
    buffering: Boolean,
    loading: Boolean,
    error: String,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    boosting: Boolean,
    speed: Float,
    scaleType: Int,
    aspect: Float,
    vw: Int,
    vh: Int,
    anyDialog: Boolean,
    onSpeed: () -> Unit,
    onScale: () -> Unit,
    onSkip: () -> Unit,
    onDanmuSetting: () -> Unit,
    onDanmuSearch: () -> Unit,
    onEpisodes: () -> Unit,
) {
    val g = groups.getOrNull(flagIndex)
    val hasPrev = epIndex > 0
    val hasNext = g != null && epIndex + 1 < g.urls.size
    val overlay = ui.overlay || error.isNotEmpty()
    val rootFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { ui.showOverlay() }
    // 8 秒无操作自动收起控制层 (弹层打开时不计时).
    LaunchedEffect(ui.overlay, ui.overlayTick, anyDialog) {
        if (ui.overlay && !anyDialog) {
            delay(8000)
            ui.hideOverlay()
        }
    }
    LaunchedEffect(ui.overlay) {
        if (!ui.overlay) {
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

    // 遥控器累计快进: 首次 10s, 连按 800ms 内每次 20s, 松键提交.
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
        if (!vm.isPlayingNow) vm.play()
        seekTarget = null
        scope.launch {
            delay(700)
            if (seekTarget == null) ui.osd = null
        }
    }

    var upJob by remember { mutableStateOf<Job?>(null) }
    var upBoosted by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val k = event.nativeKeyEvent.keyCode
                val down = event.type == KeyEventType.KeyDown
                val up = event.type == KeyEventType.KeyUp
                val first = down && event.nativeKeyEvent.repeatCount == 0
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
                    if (down) ui.poke()
                    return@onPreviewKeyEvent false
                }
                when (k) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (down) startSeek(if (k == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                        else if (up) commitSeek()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
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

        if (overlay) {
            // 顶栏: 片名 · 集名 (退出全屏走遥控器返回键, 页面里不放按钮)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${vm.title} · ${vm.epName()}",
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
            }
            // 底栏: 精简按钮行 + 进度条
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TvButton(
                        label = if (playing) "暂停" else "播放",
                        icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        autofocus = true,
                        height = 48.dp,
                        fontSize = 16,
                        onTap = { vm.playOrPause(); ui.poke() },
                    )
                    if (hasPrev) {
                        TvButton(
                            label = "上一集",
                            icon = Icons.Filled.SkipPrevious,
                            height = 48.dp,
                            fontSize = 16,
                            onTap = { vm.playPrev(); ui.poke() },
                        )
                    }
                    if (hasNext) {
                        TvButton(
                            label = "下一集",
                            icon = Icons.Filled.SkipNext,
                            height = 48.dp,
                            fontSize = 16,
                            onTap = { vm.playNext(); ui.poke() },
                        )
                    }
                    TvButton(
                        label = "倍速 ${PlayerOptions.speedLabel(speed)}",
                        height = 48.dp,
                        fontSize = 16,
                        onTap = onSpeed,
                    )
                    TvButton(
                        label = "画面 ${PlayerOptions.SCALE_NAMES[scaleType]}",
                        height = 48.dp,
                        fontSize = 16,
                        onTap = onScale,
                    )
                    TvButton(
                        label = "片头尾",
                        height = 48.dp,
                        fontSize = 16,
                        onTap = onSkip,
                    )
                    TvButton(
                        label = "选集",
                        height = 48.dp,
                        fontSize = 16,
                        onTap = onEpisodes,
                    )
                    TvButton(
                        label = "弹幕",
                        height = 48.dp,
                        fontSize = 16,
                        onTap = { vm.toggleDanmu(); ui.poke() },
                    )
                    TvButton(
                        label = "弹幕设置",
                        height = 48.dp,
                        fontSize = 16,
                        onTap = onDanmuSetting,
                    )
                    TvButton(
                        label = "云搜",
                        height = 48.dp,
                        fontSize = 16,
                        onTap = onDanmuSearch,
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(fmtTime(positionMs), color = Color.White, fontSize = 14.sp, modifier = Modifier.width(64.dp))
                    PlayerSeekBar(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        bufferedMs = bufferedMs,
                        onSeek = { vm.seekTo(it); ui.poke() },
                        onInteract = ui::poke,
                        modifier = Modifier.weight(1f),
                        height = 36.dp,
                    )
                    Text(
                        fmtTime(durationMs),
                        color = Color.White,
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.width(64.dp),
                    )
                }
            }
        }

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
        if (!playing && !loading && !buffering && error.isEmpty() && !overlay && ui.osd == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        if (loading || error.isNotEmpty()) {
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
                            fontSize = 13.sp,
                            maxLines = 6,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 退出走遥控器返回键, 不在这里放"返回"按钮.
                    Row {
                        if (error.isNotEmpty()) {
                            TextButton(onClick = vm::retry) { Text("重试") }
                        }
                        if (hasNext) {
                            TextButton(onClick = vm::playNext) { Text("下一集") }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 选集弹窗 (全屏态用)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodePickerDialog(
    groups: List<PlayUrl>,
    flagIndex: Int,
    epIndex: Int,
    onSelectFlag: (Int) -> Unit,
    onSelectEp: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val g = groups.getOrNull(flagIndex)
    val maxLen = g?.eps?.maxOfOrNull { it.length } ?: 3
    val columns = when {
        maxLen <= 3 -> 6
        maxLen <= 6 -> 5
        maxLen <= 10 -> 4
        maxLen <= 16 -> 3
        else -> 2
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选集", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        text = {
            Column {
                if (groups.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(groups) { i, item ->
                            TvFocusable(onTap = { onSelectFlag(i) }, radius = 8.dp, focusScale = 1.0f) {
                                FlagChip(name = item.flag, selected = i == flagIndex, onClick = { onSelectFlag(i) }, interactive = false)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (g != null) {
                    // 先滚到当前集附近, 再让它 autofocus (懒网格里未组合的项无法获得焦点).
                    val pickState = rememberLazyGridState(
                        initialFirstVisibleItemIndex = maxOf(0, epIndex - columns),
                    )
                    LazyVerticalGrid(
                        state = pickState,
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(g.eps) { i, name ->
                            val selected = i == epIndex
                            TvFocusable(
                                onTap = { onSelectEp(i) },
                                modifier = Modifier.height(48.dp),
                                autofocus = selected,
                                radius = 8.dp,
                            ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        color = if (selected) {
                                            com.me.ubox.ui.player.AccentBlue
                                        } else {
                                            Color.White.copy(alpha = 0.12f)
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = name.ifEmpty { "第${i + 1}集" },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = if (selected) 15.sp else 14.sp,
                                    color = Color.White,
                                )
                            }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

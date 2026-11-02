package com.me.ubox.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.CircularProgressIndicator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.me.ubox.TvMode
import com.me.ubox.danmaku.DanmakuManager
import com.me.ubox.danmaku.DanmuSettings
import com.me.ubox.ui.components.formatDuration
import master.flame.danmaku.ui.widget.DanmakuView
import kotlinx.coroutines.delay
import kotlin.math.abs

/** 播放页进度条/高亮用的蓝色 (同直播页 LiveColors.Blue). */
internal val AccentBlue = Color(0xFF1890FF)

// ---------------------------------------------------------------- UI 状态

/** 手势/遥控器操作时在画面中央显示的提示 (快进时间 / 亮度 / 音量). */
data class PlayerOsd(
    val icon: ImageVector,
    val text: String,
    /** 0..1, 非空时画一条进度 (亮度/音量). */
    val progress: Float? = null,
)

/**
 * 播放页界面状态 (三端共用): 控制层显隐 + 自动隐藏计时、锁屏、全屏、提示、各类弹层开关.
 * 控制层显隐放在界面层而不是 ViewModel: 它只和触摸/按键交互有关.
 */
@Stable
class PlayerUiState {
    var fullscreen by mutableStateOf(false)
    var locked by mutableStateOf(false)
    var overlay by mutableStateOf(true)
    var overlayTick by mutableIntStateOf(0)
    var lockHint by mutableStateOf(false)
    var lockHintTick by mutableIntStateOf(0)
    var osd by mutableStateOf<PlayerOsd?>(null)

    var showEpisodes by mutableStateOf(false)
    var showSpeed by mutableStateOf(false)
    var showScale by mutableStateOf(false)
    var showSkip by mutableStateOf(false)
    var showDanmuSetting by mutableStateOf(false)
    var showDanmuSearch by mutableStateOf(false)

    /** 任何交互都调用: 重置自动隐藏计时. */
    fun poke() {
        overlayTick++
    }

    fun showOverlay() {
        overlay = true
        overlayTick++
    }

    fun hideOverlay() {
        overlay = false
    }

    fun toggleOverlay() {
        if (overlay) overlay = false else showOverlay()
    }

    fun flashLock() {
        lockHint = true
        lockHintTick++
    }

    /** 是否有弹层/面板打开 (自动隐藏计时暂停). */
    val anyPanelOpen: Boolean
        get() = showEpisodes || showSpeed || showScale || showSkip ||
            showDanmuSetting || showDanmuSearch
}

// ---------------------------------------------------------------- 工具

/** ContextWrapper 链上找 Activity (屏幕方向 / 亮度). */
internal fun Context.findPlayerActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

internal fun fmtTime(ms: Long): String = formatDuration(ms.coerceAtLeast(0L))

// ---------------------------------------------------------------- 进度条

/**
 * 播放进度条 (三端共用):
 *   - 触摸: 点按跳转, 拖动时只更新预览, **松手才 seek** (原 Slider 每个 move 都 seek, 会狂刷);
 *   - 遥控器: 获得焦点后 左/右 键累计快进 (首次 10s, 连按 800ms 内每次 20s, 对照 NovaTV tvSlideStart),
 *     停手 600ms 后提交.
 * [onPreview] 回调预览位置 (null = 预览结束), 供外层显示时间.
 */
@Composable
fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onInteract: () -> Unit = {},
    onPreview: (Long?) -> Unit = {},
    height: Dp = 28.dp,
) {
    val remote by TvMode.isRemote.collectAsStateWithLifecycle()
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    var kbTarget by remember { mutableStateOf<Long?>(null) }
    var lastKeyAt by remember { mutableLongStateOf(0L) }
    var focused by remember { mutableStateOf(false) }
    val dur = durationMs.coerceAtLeast(0L)
    val onSeekState by rememberUpdatedState(onSeek)
    val onInteractState by rememberUpdatedState(onInteract)
    val onPreviewState by rememberUpdatedState(onPreview)

    // 遥控器: 停止按键 600ms 后提交 (每次按键重启计时).
    LaunchedEffect(kbTarget, lastKeyAt) {
        val t = kbTarget ?: return@LaunchedEffect
        delay(600)
        onSeekState(t)
        kbTarget = null
        onPreviewState(null)
    }

    val frac = when {
        dragFrac != null -> dragFrac!!
        kbTarget != null && dur > 0 -> kbTarget!!.toFloat() / dur
        dur > 0 -> positionMs.toFloat() / dur
        else -> 0f
    }.coerceIn(0f, 1f)
    val bufFrac = if (dur > 0) (bufferedMs.toFloat() / dur).coerceIn(0f, 1f) else 0f

    Box(
        modifier = modifier
            .height(height)
            .onFocusChanged { focused = it.isFocused }
            .then(if (remote) Modifier.focusable() else Modifier)
            .onPreviewKeyEvent { e ->
                if (!remote || dur <= 0 || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val dir = when (e.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> return@onPreviewKeyEvent false
                }
                onInteractState()
                val now = System.currentTimeMillis()
                val base = kbTarget ?: positionMs
                val step = if (kbTarget != null && now - lastKeyAt <= 800) 20_000L else 10_000L
                val t = (base + dir * step).coerceIn(0L, dur)
                kbTarget = t
                lastKeyAt = now
                onPreviewState(t)
                true
            }
            .pointerInput(dur) {
                detectTapGestures { off ->
                    if (dur > 0) {
                        onInteractState()
                        onSeekState(((off.x / size.width).coerceIn(0f, 1f) * dur).toLong())
                    }
                }
            }
            .pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragStart = { off ->
                        if (dur > 0) {
                            val f = (off.x / size.width).coerceIn(0f, 1f)
                            dragFrac = f
                            onInteractState()
                            onPreviewState((f * dur).toLong())
                        }
                    },
                    onHorizontalDrag = { change, _ ->
                        if (dur > 0) {
                            change.consume()
                            val f = (change.position.x / size.width).coerceIn(0f, 1f)
                            dragFrac = f
                            onInteractState()
                            onPreviewState((f * dur).toLong())
                        }
                    },
                    onDragEnd = {
                        dragFrac?.let { onSeekState((it * dur).toLong()) }
                        dragFrac = null
                        onPreviewState(null)
                    },
                    onDragCancel = {
                        dragFrac = null
                        onPreviewState(null)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackH = (if (focused) 6.dp else 4.dp).toPx()
            val r = CornerRadius(trackH / 2f)
            val y = (size.height - trackH) / 2f
            drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, y), Size(size.width, trackH), r)
            if (bufFrac > 0f) {
                drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(0f, y), Size(size.width * bufFrac, trackH), r)
            }
            if (frac > 0f) {
                drawRoundRect(AccentBlue, Offset(0f, y), Size(size.width * frac, trackH), r)
            }
            val thumbR = (if (focused || dragFrac != null) 9.dp else 6.dp).toPx()
            drawCircle(Color.White, thumbR, Offset(size.width * frac, size.height / 2f))
        }
    }
}

// ---------------------------------------------------------------- 手势层

/**
 * 触摸手势层 (对照 NovaBox BaseController):
 *   单击 = 显示/隐藏控制层; 双击 = 播放/暂停; 长按 = 3x 加速 (松手恢复);
 *   水平滑动 = 进度 (整屏宽 = 120s, 松手生效); 左半屏竖滑 = 亮度; 右半屏竖滑 = 音量;
 *   屏幕边缘 (系统手势区) 起手的滑动忽略.
 * [enabled] = false (锁屏) 时只响应单击.
 */
@Composable
fun PlayerGestureLayer(
    modifier: Modifier,
    enabled: Boolean,
    getPositionMs: () -> Long,
    getDurationMs: () -> Long,
    onSeekCommit: (Long) -> Unit,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onBoost: (Boolean) -> Unit,
    onOsd: (PlayerOsd?) -> Unit,
) {
    val context = LocalContext.current
    val window = remember(context) { context.findPlayerActivity()?.window }
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val getPos by rememberUpdatedState(getPositionMs)
    val getDur by rememberUpdatedState(getDurationMs)
    val seekCommit by rememberUpdatedState(onSeekCommit)
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val boost by rememberUpdatedState(onBoost)
    val osd by rememberUpdatedState(onOsd)

    // 离开播放页时把窗口亮度还给系统.
    DisposableEffect(window) {
        onDispose {
            window?.let {
                val lp = it.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                it.attributes = lp
            }
        }
    }

    fun currentBrightness(): Float {
        val cur = window?.attributes?.screenBrightness ?: -1f
        if (cur >= 0f) return cur
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Exception) {
            0.5f
        }
    }

    Box(
        modifier = modifier
            .pointerInput(enabled) {
                var boosting = false
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { if (enabled) doubleTap() },
                    onLongPress = {
                        if (enabled) {
                            boosting = true
                            boost(true)
                        }
                    },
                    onPress = {
                        tryAwaitRelease()
                        if (boosting) {
                            boosting = false
                            boost(false)
                        }
                    },
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // 0 = 未判定, 1 = 进度, 2 = 亮度, 3 = 音量, -1 = 忽略
                var mode = 0
                var total = Offset.Zero
                var startPos = 0L
                var target = 0L
                var startBright = 0.5f
                var startVol = 0
                var maxVol = 1
                detectDragGestures(
                    onDragStart = { off ->
                        total = Offset.Zero
                        val ex = 32.dp.toPx()
                        val ey = 24.dp.toPx()
                        mode = if (off.x < ex || off.x > size.width - ex || off.y < ey || off.y > size.height - ey) -1 else 0
                    },
                    onDrag = { change, amount ->
                        if (mode == -1) return@detectDragGestures
                        total += amount
                        if (mode == 0) {
                            mode = when {
                                abs(total.x) >= abs(total.y) -> if (getDur() > 0) 1 else -1
                                change.position.x > size.width / 2f -> 3
                                else -> 2
                            }
                            startPos = getPos()
                            target = startPos
                            startBright = currentBrightness()
                            maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        }
                        if (mode == -1) return@detectDragGestures
                        change.consume()
                        when (mode) {
                            1 -> {
                                val dur = getDur()
                                target = (startPos + total.x / size.width * 120_000f).toLong().coerceIn(0L, dur)
                                osd(
                                    PlayerOsd(
                                        icon = if (target >= startPos) PlayerIcons.FastForward else PlayerIcons.FastRewind,
                                        text = "${fmtTime(target)} / ${fmtTime(dur)}",
                                    )
                                )
                            }
                            2 -> {
                                // 向上滑为增 (整屏高度的一半 = 满量程, 同 NovaBox deltaY * 2 / height).
                                val b = (startBright - total.y * 2f / size.height).coerceIn(0.01f, 1f)
                                window?.let {
                                    val lp = it.attributes
                                    lp.screenBrightness = b
                                    it.attributes = lp
                                }
                                osd(PlayerOsd(PlayerIcons.Brightness, "${(b * 100).toInt()}%", b))
                            }
                            3 -> {
                                val v = (startVol - total.y * 2f / size.height * maxVol).coerceIn(0f, maxVol.toFloat())
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, v.toInt(), 0)
                                osd(PlayerOsd(PlayerIcons.Volume, "${(v / maxVol * 100).toInt()}%", v / maxVol))
                            }
                        }
                    },
                    onDragEnd = {
                        if (mode == 1) seekCommit(target)
                        mode = -1
                        osd(null)
                    },
                    onDragCancel = {
                        mode = -1
                        osd(null)
                    },
                )
            },
    )
}

// ---------------------------------------------------------------- OSD / 指示器

@Composable
fun PlayerOsdView(osd: PlayerOsd, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(osd.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text(osd.text, color = Color.White, fontSize = 20.sp)
        }
        osd.progress?.let { p ->
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(p.coerceIn(0f, 1f))
                        .background(Color.White),
                )
            }
        }
    }
}

/** 长按 3x 加速指示 (顶部居中, 对照 play_speed_3_container). */
@Composable
fun BoostIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PlayerIcons.FastForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("${PlayerOptions.speedLabel(PlayerOptions.BOOST_SPEED).uppercase()} 加速中", color = Color.White, fontSize = 14.sp)
    }
}

// ---------------------------------------------------------------- 小部件

/** 触摸端的小胶囊按钮 (倍速 / 画面 / 弹幕 ...). */
@Composable
fun ToolChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    dark: Boolean = true,
) {
    val bg = when {
        selected -> AccentBlue.copy(alpha = 0.85f)
        dark -> Color.White.copy(alpha = 0.16f)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            maxLines = 1,
            fontSize = 13.sp,
            color = if (dark || selected) Color.White else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 选集面板的集名. */
internal fun epLabel(names: List<String>, i: Int): String =
    if (i in names.indices && names[i].isNotEmpty()) names[i] else "第${i + 1}集"

/**
 * 选集面板 (手机下方 / 平板右栏 / 全屏侧栏共用, 对照 NovaBox mGridView + mSeriesGroupView + mSeriesSortTv):
 * 超过 30 集自动分组 (1-30, 31-60 ...), 可倒序.
 */
@Composable
fun EpisodePanel(
    epNames: List<String>,
    count: Int,
    currentIdx: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    columns: Int = 0,
) {
    if (count <= 0) return
    val groupSize = 30
    val groups = (count + groupSize - 1) / groupSize
    var group by remember(count) { mutableIntStateOf((currentIdx / groupSize).coerceIn(0, groups - 1)) }
    var reverse by remember { mutableStateOf(false) }
    // 换集 (含片尾自动下一集) 跟随到当前集所在分组.
    LaunchedEffect(currentIdx) { group = (currentIdx / groupSize).coerceIn(0, groups - 1) }

    val fg = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val from = group * groupSize
    val to = minOf(count, from + groupSize)
    val base = (from until to).toList()
    val indices = if (reverse) base.reversed() else base

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("选集", color = fg, fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp))
            if (groups > 1) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items((0 until groups).toList()) { g ->
                        ToolChip(
                            label = "${g * groupSize + 1}-${minOf(count, (g + 1) * groupSize)}",
                            selected = g == group,
                            dark = dark,
                            onClick = { group = g },
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { reverse = !reverse }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.SwapVert, contentDescription = null, tint = fg.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                Text(if (reverse) "倒序" else "正序", color = fg.copy(alpha = 0.8f), fontSize = 13.sp)
            }
        }
        LazyVerticalGrid(
            columns = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(76.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(indices) { i ->
                val selected = i == currentIdx
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                selected -> AccentBlue.copy(alpha = 0.25f)
                                dark -> Color.White.copy(alpha = 0.12f)
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                        )
                        .then(if (selected) Modifier.border(1.dp, AccentBlue, RoundedCornerShape(8.dp)) else Modifier)
                        .clickable(
                            enabled = !selected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(i) }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = epLabel(epNames, i),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp,
                        color = if (selected) AccentBlue else fg,
                    )
                }
            }
        }
    }
}

/**
 * 全屏选集弹窗 (对照 NovaBox EpisodeSelectDialog):
 * 居中对话框, 宽度约为屏幕 80%, 网格列数按集名长度自适应, 标题栏 56dp + 右上角关闭.
 */
@Composable
fun EpisodeSelectDialog(
    epNames: List<String>,
    count: Int,
    currentIdx: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (count <= 0) return
    val maxLen = (0 until count).maxOfOrNull { epLabel(epNames, it).length } ?: 3
    val columns = when {
        maxLen <= 3 -> 6
        maxLen <= 6 -> 5
        maxLen <= 10 -> 4
        maxLen <= 16 -> 3
        else -> 2
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选集", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(count) { i ->
                    val selected = i == currentIdx
                    Box(
                        modifier = Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) AccentBlue else Color.White.copy(alpha = 0.12f)
                            )
                            .clickable { onSelect(i) }
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = epLabel(epNames, i),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = if (selected) 15.sp else 14.sp,
                            color = Color.White,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 播放页用到的图标 (集中一处, 手势层/OSD 共用). */
internal object PlayerIcons {
    val FastForward: ImageVector get() = Icons.Filled.FastForward
    val FastRewind: ImageVector get() = Icons.Filled.FastRewind
    val Brightness: ImageVector get() = Icons.Filled.Brightness6
    val Volume: ImageVector get() = Icons.Filled.VolumeUp
}

// ================================================================ 弹幕层 + 加载/错误态

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

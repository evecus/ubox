package com.me.ubox.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.me.ubox.TvMode
import com.me.ubox.data.VodItem
import com.me.ubox.data.picUrlAndHeaders
import com.me.ubox.ui.theme.LiveColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * 当前页签是否可见 (由 MainShell 的 KeepAliveStack 提供, 对照 Flutter IndexedStack
 * 外包 ExcludeFocus 的语义).
 *
 * 背景: Compose 的 `focusProperties { canFocus = false }` 按官方文档可以禁用子树,
 * 但实测 (Google 工程师确认, issue 319412360) 只对节点本身生效, 对子树/Lazy 列表
 * 内容无效 —— 无法像 Flutter ExcludeFocus 那样整树排除. 所以:
 *   - 经由 [TvFocusable]/[TvButton] 的控件: 由 enabled 逻辑统一收口;
 *   - 页面里的原生可聚焦控件 (clickable/TextField/Switch/IconButton):
 *     用 [pageFocusGuard] 精确加在控件节点上.
 */
val LocalPageVisible = compositionLocalOf { true }

/**
 * 页签不可见时把本节点排除出焦点遍历 (TV).
 * 注意必须加在可聚焦控件自身的节点上 (canFocus 不向子树传播).
 */
@Composable
fun Modifier.pageFocusGuard(): Modifier {
    if (LocalPageVisible.current) return this
    return this.focusProperties { canFocus = false }
}

/**
 * 沉浸式系统栏 (对照 Flutter `SystemChrome.setEnabledSystemUIMode`):
 * [immersive] = true 时隐藏状态栏/导航栏 (sticky: 划入临时显示, BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
 * [immersive] 变回 false 或离开组合时恢复显示 (对照 dispose 里的 edgeToEdge).
 * 仅在全屏播放/直播等场景使用.
 */
@Composable
fun ImmersiveSystemBarsEffect(immersive: Boolean) {
    val view = LocalView.current
    DisposableEffect(immersive) {
        val window = (view.context.findActivity())?.window
        if (immersive && window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null) {
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? =
    when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/**
 * 遥控器可聚焦控件 (TV 焦点效果对照 Flutter 版 TvFocusable / dart_simple_live HighlightWidget):
 *   焦点态 = 白色 2dp 描边 + 4% 放大 (160ms) + 白色光晕阴影;
 *   OK/确定键 (enter/select/space/dpadCenter/gameButtonA) 与触摸点击等价.
 * 手机模式下退化为普通 clickable (无焦点装饰).
 */
@Composable
fun TvFocusable(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    autofocus: Boolean = false,
    radius: Dp = 8.dp,
    focusScale: Float = 1.04f,
    borderColor: Color = Color.White,
    showBorder: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    onFocusChanged: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    // 所在页签不可见 (KeepAliveStack 的隐藏页) 时整体退出焦点遍历 (对照 Flutter ExcludeFocus).
    val focusEnabled = enabled && LocalPageVisible.current
    if (!tv) {
        Box(
            modifier = modifier.then(
                if (onLongPress != null) {
                    Modifier.pointerInput(onTap, onLongPress) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onLongPress = { onLongPress() },
                        )
                    }
                } else {
                    Modifier.clickable(enabled = enabled, onClick = onTap)
                }
            ),
        ) { content() }
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autofocus) {
        // 只在可见时请求: 隐藏页的 autofocus 不能抢走可见页焦点 (enabled=false 时
        // requestFocus 本身无效, 这里显式跳过, 且 visibility 变化不重触发).
        if (autofocus && focusEnabled) focusRequester.requestFocus()
    }
    LaunchedEffect(focused) { onFocusChanged(focused) }
    val scale by animateFloatAsState(
        targetValue = if (focused) focusScale else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "tv-focus-scale",
    )
    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && isTvSelectKey(event.key)) {
                    onTap()
                    true
                } else {
                    false
                }
            }
            .focusable(interactionSource = interactionSource, enabled = focusEnabled)
            .pointerInput(enabled, onTap, onLongPress) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress?.invoke() },
                )
            }
            .then(
                if (focused) {
                    Modifier
                        .scale(scale)
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(radius),
                            ambientColor = Color.White,
                            spotColor = Color.White,
                        )
                        .then(
                            if (showBorder) {
                                Modifier.border(2.dp, borderColor, RoundedCornerShape(radius))
                            } else {
                                Modifier
                            }
                        )
                } else {
                    Modifier
                }
            )
    ) { content() }
}

/** 遥控器 OK/确定键集合 (对照 Flutter: enter/select/space/gameButtonA). */
fun isTvSelectKey(key: Key): Boolean {
    return key == Key.Enter ||
        key == Key.NumPadEnter ||
        key == Key.DirectionCenter ||
        key == Key.Spacebar ||
        key == Key.ButtonA
}

/**
 * 遥控器按钮: 图标 + 文字 (对照 Flutter 版 TvButton):
 * 焦点/选中态 = 白底黑字, 平时 = 深灰底白字.
 */
@Composable
fun TvButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onTap: (() -> Unit)? = null,
    autofocus: Boolean = false,
    selected: Boolean = false,
    height: Dp = 52.dp,
    fontSize: Int = 16,
) {
    var focusedState by remember { mutableStateOf(false) }
    TvFocusable(
        onTap = { onTap?.invoke() },
        modifier = modifier.height(height),
        enabled = onTap != null,
        autofocus = autofocus,
        radius = 8.dp,
        showBorder = false,
        onFocusChanged = { focusedState = it },
    ) {
        val active = focusedState || selected
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = if (active) Color.White else Color(0x6C3D3D3D),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size((fontSize + 6).dp),
                        tint = if (active) Color.Black else Color.White.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.W500,
                    color = if (active) Color.Black else Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize.sp),
                )
            }
        }
    }
}

/**
 * 网络图片 (海报/封面): 支持 FongMi 风格 `url@User-Agent=..@Referer=..` 后缀拆解.
 * 加载中/失败有占位.
 */
@Composable
fun NetworkImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit = { DefaultImagePlaceholder() },
) {
    val (cleanUrl, headers) = remember(url) { picUrlAndHeaders(url) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, cleanUrl) {
        value = withContext(Dispatchers.IO) {
            ImageLoader.load(cleanUrl, headers)?.asImageBitmap()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
            filterQuality = FilterQuality.Medium,
        )
    } else {
        Box(modifier = modifier) { placeholder() }
    }
}

@Composable
fun DefaultImagePlaceholder(
    icon: ImageVector = Icons.Filled.Movie,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * 海报卡片 (对照 Flutter 版 home_page._poster):
 * 圆角封面 + 左上角角标 (年份/来源) + 底部备注分片 + 片名栏.
 */
@Composable
fun VodPoster(
    item: VodItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showYearBadge: Boolean = false,
    badgeText: String? = null,
    badgeBg: Color = Color(0xBD0CADE2),
    tvSizes: Boolean = false,
) {
    var note = item.remark?.trim() ?: ""
    if (note.isEmpty() && !item.score.isNullOrEmpty()) {
        note = "评分 ${item.score}"
    }
    val year = item.year?.trim() ?: ""
    TvFocusable(onTap = onClick, modifier = modifier, radius = 8.dp) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (tvSizes) 0.68f else 0.62f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            NetworkImage(
                url = item.cover,
                modifier = Modifier.fillMaxSize(),
                placeholder = { DefaultImagePlaceholder() },
            )
            if (showYearBadge && year.isNotEmpty()) {
                Badge(
                    text = year,
                    bg = Color.Black.copy(alpha = 0.54f),
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                    tv = tvSizes,
                )
            }
            if (!badgeText.isNullOrEmpty()) {
                Badge(
                    text = badgeText,
                    bg = badgeBg,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                    tv = tvSizes,
                )
            }
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                if (note.isNotEmpty()) {
                    Badge(
                        text = note,
                        bg = Color.Black.copy(alpha = 0.54f),
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        tv = tvSizes,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.54f))
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = item.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = (if (tvSizes) 14 else 12).sp,
                        ),
                    )
                }
            }
        }
    }
}

/** 角标小胶囊 (对照 Flutter 版 _badge). */
@Composable
fun Badge(
    text: String,
    bg: Color,
    modifier: Modifier = Modifier,
    tv: Boolean = false,
) {
    Box(
        modifier = modifier
            .background(color = bg, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = (if (tv) 14 else 10).sp,
            ),
        )
    }
}

/** 通用加载/错误/空态. */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
    }
}

@Composable
fun ErrorBox(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

@Composable
fun EmptyBox(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = Color.Gray, textAlign = TextAlign.Center)
    }
}

/** 分组小标题 (设置页分区). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleSmall,
    )
}

/** 直播列表项 (对照原版 item_live_channel + shape_live_focus + LiveChannelItemAdapter 配色). */
@Composable
fun LiveListItem(
    text: String,
    num: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    // 焦点 = #1890FF 实底 (黑字); 选中 = #331890FF 底 + #1890FF 1dp 描边 (#1890FF 字);
    // 常规 = 透明底. 原版常规是黑字 (直播页浅色底), 本工程暗色主题故用白字.
    val bg = when {
        focused -> LiveColors.Blue
        selected -> LiveColors.SelectedBg
        else -> Color.Transparent
    }
    val borderColor = if (focused || !selected) Color.Transparent else LiveColors.Blue
    val textColor = when {
        focused -> Color.Black
        selected -> LiveColors.Blue
        else -> Color.White
    }
    TvFocusable(
        onTap = onClick,
        modifier = modifier,
        radius = 6.dp,
        focusScale = 1.0f, // 原版焦点无缩放
        showBorder = false, // 自绘焦点底色
        onFocusChanged = { focused = it },
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp, vertical = 2.dp)
                .background(bg, RoundedCornerShape(6.dp))
                .then(
                    if (selected) {
                        Modifier.border(1.dp, borderColor, RoundedCornerShape(6.dp))
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 6.dp, vertical = if (large) 10.dp else 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (num != null) {
                    Text(
                        text = num,
                        color = textColor,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = (if (large) 14 else 11).sp,
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = (if (large) 17 else 13).sp,
                    ),
                )
            }
        }
    }
}

/**
 * 视频表面视图: ExoPlayer setVideoSurfaceView 挂载.
 * [aspectRatio] 非空时按视频比例 letterbox (contain); null 时铺满.
 */
@Composable
fun VideoSurfaceView(
    modifier: Modifier = Modifier,
    aspectRatio: Float? = null,
    onSurfaceReady: (android.view.SurfaceView) -> Unit,
    onSurfaceDispose: () -> Unit,
) {
    val context = LocalContext.current
    val surfaceView = remember { android.view.SurfaceView(context) }
    DisposableEffect(surfaceView) {
        val callback = object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                onSurfaceReady(surfaceView)
            }
            override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                onSurfaceDispose()
            }
        }
        surfaceView.holder.addCallback(callback)
        onDispose {
            surfaceView.holder.removeCallback(callback)
            onSurfaceDispose()
        }
    }
    AndroidView(
        factory = { surfaceView },
        modifier = if (aspectRatio != null && aspectRatio > 0f) {
            modifier.fillMaxWidth().aspectRatio(aspectRatio)
        } else {
            modifier
        },
    )
}

/** 进度条 + 时间文本 (播放页控制条). */
@Composable
fun PlayerProgressRow(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxMs = if (durationMs > 0) durationMs else 1L
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatDuration(positionMs),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(60.dp),
        )
        Slider(
            value = positionMs.coerceIn(0L, maxMs).toFloat(),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..maxMs.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDuration(durationMs),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(60.dp),
        )
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    val mm = m.toString().padStart(2, '0')
    val ss = s.toString().padStart(2, '0')
    return if (h > 0) "$h:$mm:$ss" else "$m:$ss"
}

/** 二级页面顶部 AppBar (返回 + 标题 + actions). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = { actions() },
    )
}

/** StateFlow 收集的兼容别名. */
@Composable
fun <T> StateFlow<T>.collectAsState(): androidx.compose.runtime.State<T> =
    collectAsStateWithLifecycle()

package com.me.ubox.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.me.ubox.TvMode
import com.me.ubox.data.History
import com.me.ubox.data.HistoryItem
import com.me.ubox.ui.AppSnackbar
import com.me.ubox.ui.components.NetworkImage
import com.me.ubox.ui.components.SimpleTopBar
import com.me.ubox.ui.components.TvFocusable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 观看历史页 (样式对照收藏页 favorites_page.dart 的海报网格):
 * 海报网格 (封面 + 片名 + 源·集数 + 进度条 + 相对时间); 长按删除; 源未加载时提示.
 */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenDetail: (String, String, String) -> Unit,
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val remote by TvMode.isRemote.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf<List<HistoryItem>?>(null) }

    fun reload() {
        items = History.list()
    }

    LaunchedEffect(Unit) { reload() }

    // 独立页面: 自带顶栏, 不显示底部栏/左侧栏 (同搜索页).
    // TV 遥控器用返回键, 顶栏不放返回按钮.
    androidx.compose.material3.Scaffold(
        topBar = { SimpleTopBar(title = "观看历史", onBack = if (remote) null else onBack) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            HistoryContent(items = items, tv = tv, onOpenDetail = onOpenDetail, reload = ::reload)
        }
    }
}

@Composable
private fun HistoryContent(
    items: List<HistoryItem>?,
    tv: Boolean,
    onOpenDetail: (String, String, String) -> Unit,
    reload: () -> Unit,
) {
    val list = items
    if (list == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(strokeWidth = 2.dp)
        }
        return
    }
    if (list.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无观看记录", color = Color.Gray)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (tv) 170.dp else 130.dp),
        contentPadding = PaddingValues(if (tv) 16.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (tv) 16.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (tv) 16.dp else 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(list, key = { _, item -> item.siteKey + "#" + item.vodId }) { _, item ->
            val progress = if (item.dur > 0) {
                (item.pos.toFloat() / item.dur).coerceIn(0f, 1f)
            } else {
                0f
            }
            TvFocusable(
                onTap = {
                    val site = History.siteOf(item.siteKey)
                    if (site == null) {
                        AppSnackbar.show("${item.siteName}未加载, 请先应用配置")
                    } else {
                        onOpenDetail(item.siteKey, item.vodId, item.name)
                    }
                },
                onLongPress = {
                    History.remove(item.siteKey, item.vodId)
                    reload()
                },
                radius = 8.dp,
            ) {
                // 整格宽高比 (同收藏页: TV 0.68 / 手机 0.62), 封面占满文字以外空间.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (tv) 0.68f else 0.62f),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        if (item.cover.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Movie,
                                    contentDescription = null,
                                    tint = Color.Gray,
                                )
                            }
                        } else {
                            NetworkImage(
                                url = item.cover,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = (if (tv) 16 else 12).sp),
                    )
                    Text(
                        text = "${item.siteName} · ${if (item.ep.isEmpty()) "播放" else item.ep}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = (if (tv) 14 else 10).sp),
                    )
                    // 播放进度条 + 相对时间 (历史页独有, 收藏页没有).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (progress > 0.01f) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(3.dp),
                            )
                        } else {
                            // 无进度 = 不确定态 (对照 Flutter value: null 的动画).
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(3.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = tsText(item.ts),
                            color = Color.Gray,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = (if (tv) 12 else 10).sp),
                        )
                    }
                }
            }
        }
    }
}

/** 相对时间 (对照 Flutter 版 _tsText). */
private fun tsText(ts: Long): String {
    val dt = Date(ts)
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000}小时前"
        diff < 30L * 86_400_000 -> "${diff / 86_400_000}天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(dt)
    }
}

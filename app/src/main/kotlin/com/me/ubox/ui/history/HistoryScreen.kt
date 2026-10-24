package com.me.ubox.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.me.ubox.TvMode
import com.me.ubox.data.History
import com.me.ubox.data.HistoryItem
import com.me.ubox.ui.AppSnackbar
import com.me.ubox.ui.components.NetworkImage
import com.me.ubox.ui.components.SimpleTopBar
import com.me.ubox.ui.components.TvFocusable
import com.me.ubox.ui.components.pageFocusGuard
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 观看历史页 (对照 Flutter 版 history_page.dart):
 * 列表 (封面 + 片名 + 源·集数 + 进度条 + 相对时间); 删除按钮; 源未加载时提示.
 */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenDetail: (String, String, String) -> Unit,
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf<List<HistoryItem>?>(null) }

    fun reload() {
        items = History.list()
    }

    LaunchedEffect(Unit) { reload() }

    // 独立页面: 自带顶栏 + 返回, 不显示底部栏/左侧栏 (同搜索页).
    androidx.compose.material3.Scaffold(
        topBar = { SimpleTopBar(title = "观看历史", onBack = onBack) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            HistoryContent(items = items, onOpenDetail = onOpenDetail, reload = ::reload)
        }
    }
}

@Composable
private fun HistoryContent(
    items: List<HistoryItem>?,
    onOpenDetail: (String, String, String) -> Unit,
    reload: () -> Unit,
) {
    val list = items
    if (list == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
        return
    }
    if (list.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无观看记录", color = Color.Gray)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(items = list, key = { _, item -> item.siteKey + "#" + item.vodId }) { index, item ->
            val progress = if (item.dur > 0) {
                (item.pos.toFloat() / item.dur).coerceIn(0f, 1f)
            } else {
                0f
            }
            TvFocusable(
                radius = 8.dp,
                onTap = {
                    val site = History.siteOf(item.siteKey)
                    if (site == null) {
                        AppSnackbar.show("${item.siteName}未加载, 请先应用配置")
                    } else {
                        onOpenDetail(item.siteKey, item.vodId, item.name)
                    }
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 封面宽 72 自适应高 (对照 Flutter SizedBox(width: 72) 的固有比例).
                    Box(modifier = Modifier.width(72.dp)) {
                        if (item.cover.isEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Movie,
                                    contentDescription = null,
                                    tint = Color.Gray,
                                )
                            }
                        } else {
                            NetworkImage(
                                url = item.cover,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "${item.siteName} · ${if (item.ep.isEmpty()) "播放" else item.ep}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall,
                        )
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
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = tsText(item.ts),
                                color = Color.Gray,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            History.remove(item.siteKey, item.vodId)
                            reload()
                        },
                        modifier = Modifier.pageFocusGuard(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "删除",
                            modifier = Modifier.width(18.dp).height(18.dp),
                        )
                    }
                }
            }
            // 分隔线只在条目之间 (对照 Flutter ListView.separated, 末尾无分隔线).
            if (index < list.lastIndex) HorizontalDivider()
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

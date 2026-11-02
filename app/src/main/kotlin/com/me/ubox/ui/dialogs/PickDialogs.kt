package com.me.ubox.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.me.ubox.TvMode
import com.me.ubox.ui.components.TvFocusable
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 通用单选弹层 (对照 Flutter 版 home_page._pick):
 *   TV = 对话框 (遥控器可上下移动焦点, 首项自动聚焦);
 *   手机 = 底部弹层.
 *
 * @param items 候选项
 * @param labelOf 展示名
 * @param subtitleOf 副标题 (源 key / 线路 url)
 * @param current 当前选中项
 * @param onPick 选中回调
 * @param onDismiss 取消回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SinglePickSheet(
    title: String,
    items: List<T>,
    labelOf: (T) -> String,
    subtitleOf: (T) -> String = { "" },
    current: T?,
    isSelected: (T) -> Boolean = { it == current },
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    LaunchedEffect(items.isEmpty()) {
        if (items.isEmpty()) onDismiss()
    }
    if (items.isEmpty()) return
    if (!tv) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    items(items.size) { i ->
                        val item = items[i]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(item) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (isSelected(item)) {
                                    Icons.Filled.RadioButtonChecked
                                } else {
                                    Icons.Filled.RadioButtonUnchecked
                                },
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = labelOf(item),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val sub = subtitleOf(item)
                                if (sub.isNotEmpty()) {
                                    Text(
                                        text = sub,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }
    // TV: 对话框 + 可聚焦列表 (对照 Flutter 版 showDialog + TvFocusable)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(items.size) { i ->
                    val item = items[i]
                    TvFocusable(
                        autofocus = i == 0,
                        radius = 8.dp,
                        showBorder = false,
                        onTap = { onPick(item) },
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (isSelected(item)) {
                                    Icons.Filled.RadioButtonChecked
                                } else {
                                    Icons.Filled.RadioButtonUnchecked
                                },
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.7f),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = labelOf(item),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                            )
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

/** 简易信息对话框 (引擎日志 / 提示). */
@Composable
fun InfoDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    onCopy: (() -> Unit)? = null,
) {
    val scrollState = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 360.dp),
            ) {
                Column(
                    modifier = Modifier.verticalScroll(scrollState)
                ) {
                    Text(
                        text = text.ifEmpty { "暂无内容" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = if (onCopy != null && text.isNotEmpty()) {
            {
                TextButton(onClick = onCopy) { Text("复制") }
            }
        } else {
            null
        },
    )
}

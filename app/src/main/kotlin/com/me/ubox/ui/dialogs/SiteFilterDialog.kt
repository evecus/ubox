package com.me.ubox.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.me.ubox.TvMode
import com.me.ubox.data.Site
import com.me.ubox.data.SiteFilter
import com.me.ubox.ui.components.TvFocusable

/**
 * 站源黑名单管理: 添加/删除屏蔽关键词, 增删即时生效并保存 (无需确认).
 * 关键词按站源名称模糊匹配 (包含, 忽略大小写), 下方实时预览将被屏蔽的源.
 *
 * @param sites 当前配置的全部可用源 (未导入配置时为空, 仍可编辑关键词)
 * @param keywords 已保存的关键词
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteBlacklistSheet(
    sites: List<Site>,
    keywords: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    val blocked = sites.filter { SiteFilter.isBlocked(it, keywords) }
    val allBlocked = sites.isNotEmpty() && blocked.size >= sites.size

    fun submit() {
        val t = input.trim()
        if (t.isEmpty()) return
        onAdd(t)
        input = ""
    }

    @Composable
    fun Body(modifier: Modifier) {
        Column(modifier = modifier) {
            Text(
                text = "站源名称包含任一关键词 (忽略大小写) 的源, 首页站源列表、搜索与详情换源都不使用. " +
                    "此列表只能在这里增删, 更换配置不会清空.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = { Text("输入关键词, 如: 直播 / 4K") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { submit() }, enabled = input.trim().isNotEmpty()) { Text("添加") }
            }
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                if (keywords.isEmpty()) {
                    item {
                        Text(
                            text = "还没有屏蔽关键词",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                items(keywords.size) { i ->
                    val kw = keywords[i]
                    val hit = sites.count { SiteFilter.matches(it, kw) }
                    val row: @Composable () -> Unit = {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(kw)
                                Text(
                                    text = if (sites.isEmpty()) "导入配置后统计" else "匹配 $hit 个源",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!tv) {
                                IconButton(onClick = { onRemove(kw) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "删除")
                                }
                            } else {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (tv) {
                        // 遥控器: 整行可聚焦, 确认键删除.
                        TvFocusable(
                            autofocus = false,
                            radius = 8.dp,
                            showBorder = false,
                            onTap = { onRemove(kw) },
                            modifier = Modifier.padding(vertical = 2.dp),
                        ) { row() }
                    } else {
                        row()
                    }
                }
                if (sites.isNotEmpty() && keywords.isNotEmpty()) {
                    item {
                        Text(
                            text = "将被屏蔽的源 (${blocked.size} / ${sites.size})",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                        Text(
                            text = when {
                                allBlocked -> "所有源都被匹配, 为避免没有可用源, 本次将忽略黑名单"
                                blocked.isEmpty() -> "没有源匹配当前关键词"
                                else -> blocked.joinToString("、") { it.displayName }
                            },
                            color = if (allBlocked) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    if (!tv) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                Text("站源黑名单", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(4.dp))
                Body(Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("站源黑名单") },
        text = { Body(Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

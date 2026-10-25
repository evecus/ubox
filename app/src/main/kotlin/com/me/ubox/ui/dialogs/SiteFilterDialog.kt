package com.me.ubox.ui.dialogs

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.me.ubox.TvMode
import com.me.ubox.data.Site
import com.me.ubox.ui.components.TvFocusable

/**
 * 站源过滤多选弹层.
 *
 * @param sites 当前配置的全部可用源
 * @param selected 已保存的过滤集合 (空 = 当前未过滤, 此时默认全部勾选)
 * @param onConfirm 确认回调; 返回空集合 = 不过滤 (全部勾选时也视为不过滤)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteFilterSheet(
    sites: List<Site>,
    selected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val allKeys = remember(sites) { sites.map { it.key }.toSet() }
    var picked by remember {
        mutableStateOf(if (selected.isEmpty()) allKeys else selected.intersect(allKeys).ifEmpty { allKeys })
    }
    var query by remember { mutableStateOf("") }
    val shown = remember(sites, query) {
        val q = query.trim()
        if (q.isEmpty()) sites else sites.filter {
            it.displayName.contains(q, ignoreCase = true) || it.key.contains(q, ignoreCase = true)
        }
    }

    fun toggle(key: String) {
        picked = if (key in picked) picked - key else picked + key
    }

    fun confirm() {
        // 全选 = 不过滤; 至少选一个由确认按钮的 enabled 保证.
        onConfirm(if (picked.size >= allKeys.size) emptySet() else picked)
    }

    val summary = if (picked.size >= allKeys.size) {
        "已选 ${picked.size} / ${allKeys.size} (全部, 不过滤)"
    } else {
        "已选 ${picked.size} / ${allKeys.size}"
    }

    @Composable
    fun Actions() {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { picked = allKeys }) { Text("全选") }
            TextButton(onClick = {
                // 反选只作用于当前显示 (搜索结果内) 的源.
                val keys = shown.map { it.key }.toSet()
                picked = (picked - keys) + (keys - picked)
            }) { Text("反选") }
            TextButton(onClick = { picked = emptySet() }) { Text("清空") }
        }
    }

    @Composable
    fun Rows(modifier: Modifier) {
        LazyColumn(modifier = modifier) {
            items(shown.size) { i ->
                val site = shown[i]
                val checked = site.key in picked
                val content: @Composable () -> Unit = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(site.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = site.key,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (tv) {
                    TvFocusable(
                        autofocus = i == 0,
                        radius = 8.dp,
                        showBorder = false,
                        onTap = { toggle(site.key) },
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) { content() }
                } else {
                    Row(modifier = Modifier.clickable { toggle(site.key) }) { content() }
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
                Text("站源过滤", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "只保留勾选的源: 首页站源列表与搜索都只使用它们. 更换配置后会自动清空.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("按名称筛选") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(summary, style = MaterialTheme.typography.bodySmall)
                    Actions()
                }
                Rows(Modifier.heightIn(max = 380.dp))
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { confirm() },
                    enabled = picked.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(if (picked.isEmpty()) "至少选择一个源" else "确定") }
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("站源过滤 · $summary") },
        text = {
            Column {
                Actions()
                Rows(Modifier.heightIn(max = 360.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm() }, enabled = picked.isNotEmpty()) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

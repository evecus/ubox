package com.me.ubox.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.me.ubox.TvMode
import com.me.ubox.danmaku.DanmakuApi
import com.me.ubox.danmaku.DanmakuManager
import com.me.ubox.danmaku.DanmakuLoadStatus
import com.me.ubox.danmaku.DanmuSearchResult
import com.me.ubox.danmaku.DanmuSettings
import com.me.ubox.ui.AppSnackbar
import com.me.ubox.ui.components.TvButton
import com.me.ubox.ui.components.TvFocusable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** 弹幕设置速度档位 (对照 Flutter 版 DanmuDialogs._speeds). */
private val SPEEDS = listOf(2.4, 1.8, 1.5, 1.0)
private val SPEED_NAMES = listOf("超慢", "慢", "适中", "快")

/**
 * 弹幕全量设置弹窗 (移植自 NovaTV DanmuFullSettingDialog):
 * 开关/速度/字号/行数/透明度/行间距/顶边距.
 */
@Composable
fun DanmuFullSettingDialog(onDismiss: () -> Unit) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(DanmuSettings.open) }
    var speed by remember { mutableStateOf(DanmuSettings.speed) }
    var sizeScale by remember { mutableStateOf(DanmuSettings.sizeScale) }
    var maxLine by remember { mutableStateOf(DanmuSettings.maxLine) }
    var alpha by remember { mutableStateOf(DanmuSettings.alpha) }
    var lineSpacing by remember { mutableStateOf(DanmuSettings.lineSpacingPx) }
    var topMargin by remember { mutableStateOf(DanmuSettings.topMarginPx) }

    fun applyOpen(v: Boolean) {
        open = v
        DanmuSettings.setOpen(v)
        DanmakuManager.applySettings()
    }
    fun applySpeed(v: Double) {
        speed = v
        DanmuSettings.setSpeed(v)
        DanmakuManager.applySettings()
    }
    fun applySize(v: Double) {
        sizeScale = v
        DanmuSettings.setSizeScale(v)
        DanmakuManager.applySettings()
    }
    fun applyLine(v: Int) {
        maxLine = v
        DanmuSettings.setMaxLine(v)
        DanmakuManager.applySettings()
    }
    fun applyAlpha(v: Double) {
        alpha = v
        DanmuSettings.setAlpha(v)
        DanmakuManager.applySettings()
    }
    fun applySpacing(v: Int) {
        lineSpacing = v
        DanmuSettings.setLineSpacingPx(v)
        DanmakuManager.applySettings()
    }
    fun applyTopMargin(v: Int) {
        topMargin = v
        DanmuSettings.setTopMarginPx(v)
        DanmakuManager.applySettings()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("弹幕设置", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Label("弹幕开关")
                Spacer(Modifier.height(6.dp))
                Row {
                    Box(modifier = Modifier.weight(1f)) {
                        SettingChip("开启", open) { applyOpen(true) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        SettingChip("关闭", !open) { applyOpen(false) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Label("滚动速度")
                Spacer(Modifier.height(6.dp))
                Row {
                    for (i in SPEED_NAMES.indices) {
                        if (i > 0) Spacer(Modifier.width(6.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            SettingChip(SPEED_NAMES[i], kotlin.math.abs(speed - SPEEDS[i]) < 0.001) {
                                applySpeed(SPEEDS[i])
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Label("弹幕字号")
                StepperRow(
                    valueText = "${"%.1f".format(sizeScale)}x",
                    onMinus = if (sizeScale > 0.4 + 0.001) {
                        { applySize((sizeScale - 0.1).coerceIn(0.4, 2.4)) }
                    } else null,
                    onPlus = if (sizeScale < 2.4 - 0.001) {
                        { applySize((sizeScale + 0.1).coerceIn(0.4, 2.4)) }
                    } else null,
                )
                Spacer(Modifier.height(10.dp))
                Label("弹幕行数")
                StepperRow(
                    valueText = "${maxLine}行",
                    onMinus = if (maxLine > 1) {
                        { applyLine((maxLine - 1).coerceIn(1, 30)) }
                    } else null,
                    onPlus = if (maxLine < 30) {
                        { applyLine((maxLine + 1).coerceIn(1, 30)) }
                    } else null,
                )
                Spacer(Modifier.height(10.dp))
                Label("弹幕透明度")
                StepperRow(
                    valueText = "${(alpha * 100).toInt()}%",
                    onMinus = if (alpha > 0.1 + 0.001) {
                        { applyAlpha((alpha - 0.1).coerceIn(0.1, 1.0)) }
                    } else null,
                    onPlus = if (alpha < 1.0 - 0.001) {
                        { applyAlpha((alpha + 0.1).coerceIn(0.1, 1.0)) }
                    } else null,
                )
                Spacer(Modifier.height(10.dp))
                Label("行间距")
                StepperRow(
                    valueText = "${lineSpacing}px",
                    onMinus = if (lineSpacing > 0) {
                        { applySpacing((lineSpacing - 4).coerceIn(0, 32)) }
                    } else null,
                    onPlus = if (lineSpacing < 32) {
                        { applySpacing((lineSpacing + 4).coerceIn(0, 32)) }
                    } else null,
                )
                Spacer(Modifier.height(10.dp))
                Label("顶部边距")
                StepperRow(
                    valueText = "${topMargin}px",
                    onMinus = if (topMargin > 0) {
                        { applyTopMargin((topMargin - 20).coerceIn(0, 300)) }
                    } else null,
                    onPlus = if (topMargin < 300) {
                        { applyTopMargin((topMargin + 20).coerceIn(0, 300)) }
                    } else null,
                )
            }
        },
        confirmButton = {
            val closeWithReload: () -> Unit = {
                // 行数/边距/字号等布局参数需重备才生效 (对照原版关闭弹窗时 reload=true).
                scope.launch { DanmakuManager.onSettingsChanged(reload = true) }
                onDismiss()
            }
            if (tv) {
                TvButton(label = "完成", height = 44.dp, fontSize = 16, onTap = closeWithReload)
            } else {
                TextButton(onClick = closeWithReload) { Text("完成") }
            }
        },
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SettingChip(text: String, selected: Boolean, onTap: () -> Unit) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    if (tv) {
        TvButton(
            label = text,
            selected = selected,
            height = 46.dp,
            fontSize = 16,
            onTap = onTap,
        )
        return
    }
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .height(40.dp)
            .background(
                color = if (selected) color else Color(0xFF3A3A3A),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clickable(onClick = onTap),
        )
    }
}

@Composable
private fun StepperRow(
    valueText: String,
    onMinus: (() -> Unit)?,
    onPlus: (() -> Unit)?,
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (tv) {
                TvButton(label = "－", height = 44.dp, fontSize = 18, onTap = onMinus)
            } else {
                OutlinedButton(onClick = { onMinus?.invoke() }, enabled = onMinus != null) {
                    Text("－", fontSize = 18.sp)
                }
            }
        }
        Box(modifier = Modifier.width(90.dp), contentAlignment = Alignment.Center) {
            Text(
                text = valueText,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            if (tv) {
                TvButton(label = "＋", height = 44.dp, fontSize = 18, onTap = onPlus)
            } else {
                OutlinedButton(onClick = { onPlus?.invoke() }, enabled = onPlus != null) {
                    Text("＋", fontSize = 18.sp)
                }
            }
        }
    }
}

/** 弹幕地址弹窗 (移植自 NovaTV DanmuApiDialog): 默认 / 自定义. */
@Composable
fun DanmuApiDialog(onDismiss: () -> Unit) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf(DanmuSettings.customApi) }
    val hint = DanmakuApi.getDisplayApiUrl().ifEmpty { "请输入弹幕搜索地址" }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("弹幕地址", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = (if (tv) 17 else 14).sp),
                    placeholder = {
                        Text(
                            text = hint,
                            color = Color.White.copy(alpha = 0.38f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        DanmuSettings.setCustomApi(input)
                        onDismiss()
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "留空并确定 = 使用默认地址; 支持 {name}/{episode} 占位",
                    color = Color.White.copy(alpha = 0.54f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    Box(modifier = Modifier.weight(1f)) {
                        if (tv) {
                            TvButton(label = "恢复默认", height = 46.dp, fontSize = 16, onTap = {
                                DanmuSettings.setUseDefault(true)
                                onDismiss()
                            })
                        } else {
                            OutlinedButton(
                                onClick = {
                                    DanmuSettings.setUseDefault(true)
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("恢复默认") }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (tv) {
                            TvButton(label = "确定", height = 46.dp, fontSize = 16, onTap = {
                                DanmuSettings.setCustomApi(input)
                                onDismiss()
                            })
                        } else {
                            OutlinedButton(
                                onClick = {
                                    DanmuSettings.setCustomApi(input)
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("确定") }
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}

/** 云搜弹窗 (移植自 NovaTV SearchDanmuDialog): 打开即用当前片名自动搜索. */
@Composable
fun DanmuSearchDialog(
    initialWord: String,
    episode: String,
    onLoaded: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf(initialWord) }
    var results by remember { mutableStateOf<List<DanmuSearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun doSearch(word: String) {
        if (word.isEmpty()) {
            AppSnackbar.show("输入内容不能为空")
            return
        }
        loading = true
        results = emptyList()
        scope.launch {
            val result = DanmakuApi.searchList(word, episode)
            loading = false
            searched = true
            results = result.results
            // 失败与"查到了但列表为空"要区分提示 (对齐 UBox SearchDanmuDialog).
            if (result.isError) {
                AppSnackbar.show(result.error)
            } else if (result.results.isEmpty()) {
                AppSnackbar.show("未查询到匹配弹幕")
            }
        }
    }

    // 对照 NovaTV: 打开即用当前片名自动搜索.
    LaunchedEffect(Unit) { doSearch(input) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("弹幕云搜", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.height(if (tv) 420.dp else 360.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = (if (tv) 17 else 14).sp),
                        placeholder = {
                            Text(
                                "输入片名搜索弹幕",
                                color = Color.White.copy(alpha = 0.38f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch(input) }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    if (tv) {
                        TvButton(label = "搜索", height = 48.dp, fontSize = 16, onTap = { doSearch(input) })
                    } else {
                        OutlinedButton(onClick = { doSearch(input) }) { Text("搜索") }
                    }
                }
                Spacer(Modifier.height(10.dp))
                when {
                    loading -> Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator(strokeWidth = 3.dp)
                    }
                    results.isEmpty() && searched -> Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("未查询到匹配弹幕", color = Color.White.copy(alpha = 0.54f))
                    }
                    else -> LazyColumn(modifier = Modifier.weight(1f)) {
                        items(results) { r ->
                            val label = if (r.name.isEmpty()) r.url else r.name
                            if (tv) {
                                TvFocusable(
                                    radius = 8.dp,
                                    showBorder = false,
                                    onTap = {
                                        scope.launch {
                                            loading = true
                                            val source = DanmakuApi.loadSearchResult(r)
                                            loading = false
                                            if (source.isEmpty()) {
                                                AppSnackbar.show("弹幕加载失败")
                                            } else {
                                                onDismiss()
                                                onLoaded(source)
                                            }
                                        }
                                    },
                                    modifier = Modifier.padding(vertical = 4.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0x6C3D3D3D), RoundedCornerShape(8.dp))
                                            .padding(all = 10.dp),
                                    ) {
                                        Text(
                                            text = label,
                                            maxLines = 2,
                                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                loading = true
                                                val source = DanmakuApi.loadSearchResult(r)
                                                loading = false
                                                if (source.isEmpty()) {
                                                    AppSnackbar.show("弹幕加载失败")
                                                } else {
                                                    onDismiss()
                                                    onLoaded(source)
                                                }
                                            }
                                        }
                                        .padding(vertical = 12.dp, horizontal = 4.dp),
                                ) {
                                    Text(
                                        text = label,
                                        maxLines = 2,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (tv) {
                TvButton(label = "取消", height = 44.dp, fontSize = 16, onTap = onDismiss)
            } else {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 导入 TVBox 配置对话框: 支持远程 URL / 直接粘贴 JSON 文本 / 剪贴板. */
@Composable
fun ConfigImportDialog(
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 TVBox 配置") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 6,
                    maxLines = 8,
                    placeholder = {
                        Text("配置地址 (https://...) 或配置 JSON 内容\n支持 {\"urls\":[...]} 多线路集合")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        val t = clipboard.getText()?.text?.trim() ?: ""
                        if (t.isNotEmpty()) text = t
                    },
                ) {
                    Text("从剪贴板粘贴")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onImport(text.trim())
            }) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

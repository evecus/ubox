package com.novabox.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArticleOutlined
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novabox.TvMode
import com.novabox.data.ConfigStore
import com.novabox.data.History
import com.novabox.data.HomePrefs
import com.novabox.data.WatchProgress
import com.novabox.data.live.LiveStore
import com.novabox.danmaku.DanmakuApi
import com.novabox.danmaku.DanmakuManager
import com.novabox.danmaku.DanmuSettings
import com.novabox.engine.EngineClient
import com.novabox.ui.AppSnackbar
import com.novabox.ui.components.SectionHeader
import com.novabox.ui.components.TvFocusable
import com.novabox.ui.dialogs.ConfigImportDialog
import com.novabox.ui.dialogs.DanmuApiDialog
import com.novabox.ui.dialogs.DanmuFullSettingDialog
import com.novabox.ui.dialogs.InfoDialog
import com.novabox.ui.dialogs.SinglePickSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置页 (对照 Flutter 版 settings_page.dart):
 * 配置导入/线路切换, 直播源, 首页数据, TV 模式, 弹幕, 引擎信息/清理.
 */
@Composable
fun SettingsScreen() {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val configUrl by ConfigStore.loaded.collectAsStateWithLifecycle()
    val loaded = ConfigStore.loaded.value
    val lines by remember {
        mutableStateOf(ConfigStore.loadLines())
    }

    var urlInput by remember { mutableStateOf(ConfigStore.loadUrl()) }
    var liveUrlInput by remember { mutableStateOf(LiveStore.liveUrl.value) }
    var status by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf("") }
    var liveStatus by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }
    var showHomeRecPicker by remember { mutableStateOf(false) }
    var showTvModePicker by remember { mutableStateOf(false) }
    var showDanmuSetting by remember { mutableStateOf(false) }
    var showDanmuApi by remember { mutableStateOf(false) }
    var showEngineLog by remember { mutableStateOf(false) }
    var engineLogText by remember { mutableStateOf("") }

    fun say(msg: String, error: Boolean = false) {
        status = msg
        statusIsError = error
    }

    suspend fun applyConfigInput(input: String, keepLines: Boolean): Boolean {
        val text = input.trim()
        if (text.isEmpty()) {
            ConfigStore.saveUrl("")
            ConfigStore.saveLines(emptyList(), "")
            ConfigStore.markLoaded(null)
            busy = false
            say("配置已清除")
            return false
        }
        busy = true
        statusIsError = false
        say("应用配置中...")
        val (ok, msg) = ConfigStore.applyInput(
            input,
            keepLines = if (keepLines) ConfigStore.loadLines() else emptyList(),
        )
        busy = false
        say(msg, error = !ok)
        return ok
    }

    LaunchedEffect(Unit) {
        engine = withContext(Dispatchers.IO) {
            val ip = EngineClient.getIp()
            val proxy = EngineClient.proxyUrl(true)
            if (ip.isEmpty()) "引擎未就绪" else "$ip\n$proxy"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(all = 12.dp),
    ) {
        // ---------------- 配置 ----------------
        SectionHeader("配置")
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            placeholder = { Text("远程配置地址 / 配置 JSON") },
            singleLine = true,
            isError = false,
            trailingIcon = {
                if (busy) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.width(18.dp).height(18.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                scope.launch { applyConfigInput(urlInput, false) }
            }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row {
            SettingsActionButton(
                label = "更换配置",
                filled = true,
                enabled = !busy,
                onClick = { scope.launch { applyConfigInput(urlInput, false) } },
            )
        }
        Spacer(Modifier.height(8.dp))
        SettingsActionButton(
            label = "导入 TVBox 配置 (URL / JSON)",
            enabled = !busy,
            onClick = { showImport = true },
        )
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = status,
                color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // 线路列表
        val currentLines = loaded?.lines ?: lines
        if (currentLines.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            SectionHeader("线路 (${currentLines.size})")
            for (line in currentLines) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = line.url == loaded?.url,
                        onClick = {
                            scope.launch {
                                val ok = applyConfigInput(line.url, keepLines = true)
                                if (ok) urlInput = line.url
                            }
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(line.name, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = line.url,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        // ---------------- 直播 ----------------
        SectionHeader("直播")
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = liveUrlInput,
            onValueChange = { liveUrlInput = it },
            placeholder = { Text("直播源地址 (m3u / txt / json)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val changed = LiveStore.setLiveUrl(liveUrlInput)
                liveStatus = if (changed) {
                    if (liveUrlInput.trim().isEmpty()) "直播源已清除" else "直播源已保存"
                } else {
                    "直播源无变化"
                }
            }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row {
            SettingsActionButton(
                label = "保存直播源",
                filled = true,
                onClick = {
                    val changed = LiveStore.setLiveUrl(liveUrlInput)
                    liveStatus = if (changed) {
                        if (liveUrlInput.trim().isEmpty()) "直播源已清除" else "直播源已保存"
                    } else {
                        "直播源无变化"
                    }
                },
            )
        }
        if (liveStatus.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(liveStatus, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        // ---------------- 首页 ----------------
        SectionHeader("首页")
        Spacer(Modifier.height(6.dp))
        // 原版 ModelSettingFragment.llHomeRec: 首页列表数据三选一.
        ListItem(
            headlineContent = { Text("首页列表数据") },
            supportingContent = { Text(HomePrefs.recName(HomePrefs.homeRec)) },
            leadingContent = { Icon(Icons.Outlined.Home, contentDescription = null) },
            trailingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickableSetting { showHomeRecPicker = true },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        // ---------------- 界面 ----------------
        SectionHeader("界面")
        Spacer(Modifier.height(6.dp))
        ListItem(
            headlineContent = { Text("TV 模式") },
            supportingContent = {
                Text(
                    if (tv) {
                        "已开启 (${TvMode.overrideLabel(TvMode.override)}): 左侧导航 / 大网格 / 遥控器焦点"
                    } else {
                        "已关闭 (${TvMode.overrideLabel(TvMode.override)}): 底部导航 / 手机布局"
                    }
                )
            },
            leadingContent = { Icon(Icons.Filled.Tv, contentDescription = null) },
            trailingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickableSetting { showTvModePicker = true },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        // ---------------- 弹幕 ----------------
        SectionHeader("弹幕")
        Spacer(Modifier.height(6.dp))
        SwitchRow(
            title = "弹幕开关",
            subtitle = "播放时自动搜索并加载弹幕",
            checked = DanmuSettings.open,
            onCheckedChange = { v ->
                DanmuSettings.setOpen(v)
                DanmakuManager.applySettings()
            },
        )
        ListItem(
            headlineContent = { Text("弹幕设置") },
            supportingContent = { Text("字号 / 行数 / 速度 / 透明度 / 行间距 / 顶部边距") },
            leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
            trailingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickableSetting { showDanmuSetting = true },
        )
        ListItem(
            headlineContent = { Text("弹幕地址") },
            supportingContent = {
                Text(DanmakuApi.getDisplayApiUrl().ifEmpty { "默认" })
            },
            leadingContent = { Icon(Icons.Filled.Link, contentDescription = null) },
            trailingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickableSetting { showDanmuApi = true },
        )
        SwitchRow(
            title = "随机颜色",
            subtitle = "弹幕文字随机取色 (忽略原配色)",
            checked = DanmuSettings.randomColor,
            onCheckedChange = { v -> DanmuSettings.setRandomColor(v) },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        // ---------------- 引擎 ----------------
        SectionHeader("引擎")
        Spacer(Modifier.height(6.dp))
        Text(
            text = engine.ifEmpty { "..." },
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsActionButton(
                label = "清理引擎缓存",
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        EngineClient.unload()
                        try {
                            val jar = File(context.filesDir, "main.jar")
                            if (jar.exists()) jar.delete()
                        } catch (_: Exception) {
                        }
                        ConfigStore.saveUrl(urlInput.trim())
                        ConfigStore.markLoaded(null)
                        busy = false
                        say("已清理 jar/js 引擎缓存")
                        engine = ""
                    }
                },
            )
            SettingsActionButton(
                label = "清除观看数据",
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        History.clearAll()
                        WatchProgress.clearAll()
                        com.novabox.data.Ctx.prefs.edit().remove("favorites").apply()
                        busy = false
                        say("收藏/历史/播放进度已清除")
                    }
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        SettingsActionButton(
            label = "查看引擎日志",
            enabled = !busy,
            onClick = {
                scope.launch {
                    engineLogText = withContext(Dispatchers.IO) {
                        try {
                            val file = File(context.filesDir, "engine.log")
                            if (file.exists()) file.readText() else ""
                        } catch (_: Exception) {
                            ""
                        }
                    }
                    showEngineLog = true
                }
            },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        // ---------------- 关于 ----------------
        SectionHeader("关于")
        Spacer(Modifier.height(6.dp))
        Text(
            text = "NovaBox (Jetpack Compose 版)\n" +
                "播放器: ExoPlayer (media3)\n" +
                "爬虫引擎: QuickJS + OkHttp (Kotlin)\n" +
                "本地代理端口: 9978",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showImport) {
        ConfigImportDialog(
            onImport = { input ->
                urlInput = input
                scope.launch { applyConfigInput(input, false) }
            },
            onDismiss = { showImport = false },
        )
    }
    if (showHomeRecPicker) {
        val options = listOf(0, 1, 2)
        SinglePickSheet(
            title = "请选择首页列表数据",
            items = options,
            labelOf = { HomePrefs.recName(it) },
            current = HomePrefs.homeRec,
            isSelected = { it == HomePrefs.homeRec },
            onPick = { picked ->
                showHomeRecPicker = false
                HomePrefs.setHomeRec(picked)
            },
            onDismiss = { showHomeRecPicker = false },
        )
    }
    if (showTvModePicker) {
        val options = listOf("auto", "on", "off")
        SinglePickSheet(
            title = "TV 模式",
            items = options,
            labelOf = { TvMode.overrideLabel(it) },
            current = TvMode.override,
            isSelected = { it == TvMode.override },
            onPick = { picked ->
                showTvModePicker = false
                TvMode.setOverride(picked)
            },
            onDismiss = { showTvModePicker = false },
        )
    }
    if (showDanmuSetting) {
        DanmuFullSettingDialog(onDismiss = { showDanmuSetting = false })
    }
    if (showDanmuApi) {
        DanmuApiDialog(onDismiss = { showDanmuApi = false })
    }
    if (showEngineLog) {
        InfoDialog(
            title = "引擎日志",
            text = engineLogText,
            onCopy = {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("engine.log", engineLogText))
                AppSnackbar.show("日志已复制到剪贴板")
            },
            onDismiss = { showEngineLog = false },
        )
    }
}

/** 开关行 (弹幕开关 / 随机颜色). */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(Icons.Outlined.Palette, contentDescription = null) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickableSetting { onCheckedChange(!checked) },
    )
}

/**
 * 动作按钮 (对照 Flutter 版 _actionButton):
 * TV 模式下外层包 TvFocusable (焦点白描边), 内层 onPressed 置空防止触摸/OK 双触发;
 * 手机模式就是普通按钮.
 */
@Composable
private fun SettingsActionButton(
    label: String,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val textStyle = if (tv) {
        MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp)
    } else {
        null
    }
    val button: @Composable () -> Unit = {
        if (filled) {
            androidx.compose.material3.Button(
                onClick = if (tv) ({}) else onClick,
                enabled = enabled && !tv,
                modifier = Modifier.height(if (tv) 48.dp else 40.dp),
            ) {
                Text(label, style = textStyle ?: MaterialTheme.typography.bodyMedium)
            }
        } else {
            OutlinedButton(
                onClick = if (tv) ({}) else onClick,
                enabled = enabled && !tv,
                modifier = Modifier.height(if (tv) 48.dp else 40.dp),
            ) {
                Text(label, style = textStyle ?: MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (!tv) {
        button()
        return
    }
    TvFocusable(onTap = if (enabled) onClick else ({}), radius = 8.dp) {
        button()
    }
}

/** ListItem 的可点击修饰 (手机模式). */
private fun Modifier.clickableSetting(onClick: () -> Unit): Modifier =
    this.then(clickable(onClick = onClick))

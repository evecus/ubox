package com.me.ubox.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.filled.Memory
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.me.ubox.TvMode
import com.me.ubox.data.ConfigStore
import com.me.ubox.data.History
import com.me.ubox.data.ConfigCache
import com.me.ubox.data.HomePrefs
import com.me.ubox.data.SiteFilter
import com.me.ubox.ui.dialogs.SiteBlacklistSheet
import com.me.ubox.data.WatchProgress
import com.me.ubox.data.live.LiveStore
import com.me.ubox.danmaku.DanmakuApi
import com.me.ubox.danmaku.DanmakuManager
import com.me.ubox.danmaku.DanmuSettings
import com.me.ubox.engine.EngineClient
import com.me.ubox.ui.AppShell
import com.me.ubox.ui.AppSnackbar
import com.me.ubox.ui.components.SectionHeader
import com.me.ubox.ui.components.TvButton
import com.me.ubox.ui.components.TvFocusable
import com.me.ubox.ui.components.pageFocusGuard
import com.me.ubox.ui.dialogs.DanmuApiDialog
import com.me.ubox.ui.dialogs.DanmuFullSettingDialog
import com.me.ubox.ui.dialogs.InfoDialog
import com.me.ubox.ui.dialogs.SinglePickSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess

/**
 * 设置页 (对照 Flutter 版 settings_page.dart):
 * 配置导入/线路切换, 直播源, 首页数据, TV 模式, 弹幕, 引擎信息/清理.
 */
@Composable
fun SettingsScreen() {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val remoteMode by TvMode.isRemote.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val configUrl by ConfigStore.loaded.collectAsStateWithLifecycle()
    val loaded = ConfigStore.loaded.value
    val lines by remember {
        mutableStateOf(ConfigStore.loadLines())
    }
    // 首页列表数据 / 弹幕设置的变化驱动本页刷新 (对照 Flutter 的两个 ValueListenableBuilder;
    // 这些值是 @Volatile 直读, 不收集 revision 的话切完开关 UI 不刷新).
    val homeRec by HomePrefs.recNotifier.collectAsStateWithLifecycle()
    val danmuRevision by DanmuSettings.revision.collectAsStateWithLifecycle()

    var urlInput by remember { mutableStateOf(ConfigStore.loadUrl()) }
    var liveUrlInput by remember { mutableStateOf(LiveStore.liveUrl.value) }
    var status by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf("") }
    var liveStatus by remember { mutableStateOf("") }
    var showHomeRecPicker by remember { mutableStateOf(false) }
    var showSiteFilter by remember { mutableStateOf(false) }
    val cacheOn by ConfigCache.enabled.collectAsStateWithLifecycle()
    val cacheRevision by ConfigCache.revision.collectAsStateWithLifecycle()
    val siteBlacklist by SiteFilter.keywords.collectAsStateWithLifecycle()
    var showTvModePicker by remember { mutableStateOf(false) }
    var showDecoderPicker by remember { mutableStateOf(false) }
    val decoderMode by com.me.ubox.player.DecoderPrefs.mode.collectAsStateWithLifecycle()
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
        val oldUrl = ConfigStore.loadUrl()
        val (ok, msg) = ConfigStore.applyInput(
            input,
            keepLines = if (keepLines) ConfigStore.loadLines() else emptyList(),
        )
        busy = false
        say(msg, error = !ok)
        // 配置 URL 实际变化才重启 (对照原版 !oldApi.equals(url) 判断, 相同配置
        // 重复应用不重启): 只刷 StateFlow 的话, 返回栈里的详情/播放页与各 VM
        // 缓存仍持旧站源数据 (原版 ModelSettingFragment 对此整体重启应用).
        if (ok && oldUrl != ConfigStore.loadUrl()) {
            restartAppAfterConfigChanged(context, scope)
        }
        return ok
    }

    /** 引擎信息 (ip + proxy), 清理缓存后重新拉取 (对照 Flutter _refreshInfo). */
    suspend fun refreshEngine() {
        engine = withContext(Dispatchers.IO) {
            val ip = EngineClient.getIp()
            val proxy = EngineClient.proxyUrl(true)
            if (ip.isEmpty()) "引擎未就绪" else "$ip\n$proxy"
        }
    }

    /**
     * 更新本地缓存: 配置与直播源各强制联网拉取一次并覆盖缓存.
     * 配置内容有变化时重启应用 (与换配置一致, 保证各页/引擎拿到新站源).
     */
    suspend fun updateCache() {
        busy = true
        statusIsError = false
        say("更新缓存中...")
        val parts = mutableListOf<String>()
        var allOk = true
        var configChanged = false
        val cfgUrl = ConfigStore.loadUrl()
        if (cfgUrl.isNotEmpty()) {
            val old = ConfigCache.peek(ConfigCache.KIND_TVBOX, cfgUrl)?.text
            val (ok, msg) = ConfigStore.applyInput(cfgUrl)
            allOk = allOk && ok
            parts.add("配置: ${msg.lineSequence().first()}")
            if (ok && old != null) {
                val now = ConfigCache.peek(ConfigCache.KIND_TVBOX, ConfigStore.loadUrl())?.text
                configChanged = now != null && now != old
            }
        }
        val liveUrl = LiveStore.liveUrl.value
        if (liveUrl.isNotEmpty()) {
            val (ok, msg, _) = LiveStore.fetchAndParse(liveUrl, useCache = false)
            allOk = allOk && ok
            parts.add("直播: ${msg.lineSequence().first()}")
            if (ok) LiveStore.notifyReload()
        }
        busy = false
        if (parts.isEmpty()) {
            say("没有可更新的内容, 请先导入配置或保存直播源", error = true)
            return
        }
        say(parts.joinToString("\n"), error = !allOk)
        if (configChanged) restartAppAfterConfigChanged(context, scope)
    }

    LaunchedEffect(Unit) { refreshEngine() }

    // 真 TV 无左侧栏: 设置页顶部提供"返回首页"入口 (平板走左侧栏, 手机走底部导航).
    // 每次切到设置页都把焦点落到该按钮 (KeepAliveStack 的隐藏页不抢焦点).
    if (remoteMode) {
        val backRequester = remember { FocusRequester() }
        val shellTab by AppShell.tabIndex.collectAsStateWithLifecycle()
        LaunchedEffect(shellTab) {
            if (shellTab == AppShell.SETTINGS_TAB) {
                repeat(8) {
                    androidx.compose.runtime.withFrameNanos { }
                    val ok = try {
                        backRequester.requestFocus()
                        true
                    } catch (_: IllegalStateException) {
                        false
                    }
                    if (ok) return@LaunchedEffect
                }
            }
        }
        TvButton(
            label = "返回首页",
            icon = Icons.Filled.ArrowBackIosNew,
            height = 48.dp,
            fontSize = 16,
            autofocus = true,
            onTap = { AppShell.selectTab(AppShell.HOME_TAB) },
            modifier = Modifier
                .focusRequester(backRequester)
                .padding(bottom = 8.dp),
        )
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
            modifier = Modifier.fillMaxWidth().pageFocusGuard(),
        )
        Spacer(Modifier.height(6.dp))
        Row {
            SettingsActionButton(
                label = "保存配置",
                filled = true,
                enabled = !busy,
                onClick = { scope.launch { applyConfigInput(urlInput, false) } },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "仅支持单个线路的配置地址 (或配置 JSON), 不支持多线路组合链接; 留空保存 = 清除配置",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
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
                val switchLine: () -> Unit = {
                    scope.launch {
                        // 已在当前线路只提示 (对照 Flutter _switchLine: _say('当前已在 ...')).
                        if (line.url == loaded?.url) {
                            say("当前已在 \"${line.name}\" 线路")
                            return@launch
                        }
                        val ok = applyConfigInput(line.url, keepLines = true)
                        if (ok) urlInput = line.url
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickableSetting(switchLine),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = line.url == loaded?.url,
                        onClick = null,
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
                val changed = LiveStore.setLiveUrl(liveUrlInput.trim())
                liveStatus = if (changed) {
                    if (liveUrlInput.trim().isEmpty()) "直播源已清除" else "直播源已保存"
                } else {
                    "直播源无变化"
                }
            }),
            modifier = Modifier.fillMaxWidth().pageFocusGuard(),
        )
        Spacer(Modifier.height(6.dp))
        Row {
            SettingsActionButton(
                label = "保存直播源",
                filled = true,
                onClick = {
                    // 保存前 trim (对照 Flutter _saveLiveUrl: _liveUrlCtrl.text.trim()).
                    val changed = LiveStore.setLiveUrl(liveUrlInput.trim())
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
        // ---------------- 缓存 ----------------
        SectionHeader("缓存")
        Spacer(Modifier.height(6.dp))
        SwitchRow(
            title = "缓存配置到本地",
            subtitle = "开启后配置与直播源缓存在本地, 启动不再联网拉取; 默认关闭",
            checked = cacheOn,
            onCheckedChange = { v ->
                ConfigCache.setEnabled(v)
                // 开启时立即联网拉一次建立缓存; 关闭时缓存已清空.
                if (v) scope.launch { updateCache() }
            },
        )
        if (cacheOn) {
            val fmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }
            val cfgTime = remember(cacheRevision) { ConfigCache.timeOf(ConfigCache.KIND_TVBOX) }
            val liveTime = remember(cacheRevision) { ConfigCache.timeOf(ConfigCache.KIND_LIVE) }
            Text(
                text = "配置缓存: " + (if (cfgTime > 0) fmt.format(java.util.Date(cfgTime)) else "无") +
                    "    直播缓存: " + (if (liveTime > 0) fmt.format(java.util.Date(liveTime)) else "无"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row {
                SettingsActionButton(
                    label = "更新缓存",
                    filled = true,
                    enabled = !busy,
                    onClick = { scope.launch { updateCache() } },
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        // ---------------- 首页 ----------------
        SectionHeader("首页")
        Spacer(Modifier.height(6.dp))
        // 原版 ModelSettingFragment.llHomeRec: 首页列表数据三选一.
        ListItem(
            headlineContent = { Text("首页列表数据") },
            supportingContent = { Text(HomePrefs.recName(homeRec)) },
            leadingContent = { Icon(Icons.Outlined.Home, contentDescription = null) },
            trailingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickableSetting { showHomeRecPicker = true },
        )
        // 站源黑名单: 名称命中关键词的源, 首页/搜索/换源都不使用; 换配置不会清空.
        run {
            val all = loaded?.sites?.filter { it.isRealSource } ?: emptyList()
            val blocked = all.count { SiteFilter.isBlocked(it, siteBlacklist) }
            ListItem(
                headlineContent = { Text("站源黑名单") },
                supportingContent = {
                    Text(
                        when {
                            siteBlacklist.isEmpty() -> "未设置 (使用全部源), 点击添加屏蔽关键词"
                            all.isEmpty() -> "${siteBlacklist.size} 个关键词 (导入配置后生效)"
                            else -> "${siteBlacklist.size} 个关键词, 当前屏蔽 $blocked / ${all.size} 个源"
                        },
                    )
                },
                leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.clickableSetting { showSiteFilter = true },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        // ---------------- 界面 ----------------
        SectionHeader("界面")
        Spacer(Modifier.height(6.dp))
        ListItem(
            headlineContent = { Text("界面模式") },
            supportingContent = {
                Text(
                    when {
                        remoteMode -> "${TvMode.currentLabel()}: 左侧导航 / 大网格 / 遥控器焦点 / 横屏"
                        tv -> "${TvMode.currentLabel()}: 左侧导航 / 大网格 / 触摸操作 / 横屏"
                        else -> "${TvMode.currentLabel()}: 底部导航 / 手机布局"
                    }
                )
            },
            leadingContent = { Icon(Icons.Filled.Tv, contentDescription = null) },
            trailingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickableSetting { showTvModePicker = true },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        // ---------------- 播放 ----------------
        SectionHeader("播放")
        Spacer(Modifier.height(6.dp))
        ListItem(
            headlineContent = { Text("解码方式") },
            supportingContent = {
                Text(
                    "${com.me.ubox.player.DecoderPrefs.label(decoderMode)}: " +
                        com.me.ubox.player.DecoderPrefs.description(decoderMode) +
                        " (点播与直播通用, 下次开始播放时生效)"
                )
            },
            leadingContent = { Icon(Icons.Filled.Memory, contentDescription = null) },
            trailingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickableSetting { showDecoderPicker = true },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        // ---------------- 弹幕 ----------------
        // 弹幕区: revision 变化时重读设置 (对照 Flutter ValueListenableBuilder(revision)).
        SectionHeader("弹幕")
        Spacer(Modifier.height(6.dp))
        val danmuOpen = remember(danmuRevision) { DanmuSettings.open }
        val danmuRandom = remember(danmuRevision) { DanmuSettings.randomColor }
        val danmuApiText = remember(danmuRevision) { DanmakuApi.getDisplayApiUrl().ifEmpty { "默认" } }
        SwitchRow(
            title = "弹幕开关",
            subtitle = "播放时自动搜索并加载弹幕",
            checked = danmuOpen,
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
            supportingContent = { Text(danmuApiText) },
            leadingContent = { Icon(Icons.Filled.Link, contentDescription = null) },
            trailingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickableSetting { showDanmuApi = true },
        )
        SwitchRow(
            title = "随机颜色",
            subtitle = "弹幕文字随机取色 (忽略原配色)",
            checked = danmuRandom,
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
                            // 本地缓存模式会复用已下载的主 jar, 清理时一并删除以便重新下载.
                            context.filesDir.listFiles { f ->
                                f.name.startsWith("main_") && f.name.endsWith(".jar")
                            }?.forEach { it.delete() }
                        } catch (_: Exception) {
                        }
                        ConfigStore.saveUrl(urlInput.trim())
                        ConfigStore.markLoaded(null)
                        busy = false
                        say("已清理 jar/js 引擎缓存")
                        // 清理后重新拉取引擎信息 (对照 Flutter: _engine = ''; await _refreshInfo()).
                        refreshEngine()
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
                        com.me.ubox.data.Ctx.prefs.edit().remove("favorites").apply()
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
            text = "UBox (Jetpack Compose 版)\n" +
                "播放器: ExoPlayer (media3)\n" +
                "爬虫引擎: QuickJS + OkHttp (Kotlin)\n" +
                "本地代理端口: 9978",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showSiteFilter) {
        SiteBlacklistSheet(
            sites = loaded?.sites?.filter { it.isRealSource } ?: emptyList(),
            keywords = siteBlacklist,
            onAdd = { SiteFilter.add(it) },
            onRemove = { SiteFilter.remove(it) },
            onDismiss = { showSiteFilter = false },
        )
    }
    if (showHomeRecPicker) {
        val options = listOf(0, 1, 2)
        SinglePickSheet(
            title = "请选择首页列表数据",
            items = options,
            labelOf = { HomePrefs.recName(it) },
            current = homeRec,
            isSelected = { it == homeRec },
            onPick = { picked ->
                showHomeRecPicker = false
                HomePrefs.setHomeRec(picked)
            },
            onDismiss = { showHomeRecPicker = false },
        )
    }
    if (showTvModePicker) {
        val options = listOf("auto", "on", "pad", "off")
        SinglePickSheet(
            title = "界面模式",
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
    if (showDecoderPicker) {
        val options = listOf(
            com.me.ubox.player.DecoderPrefs.HARD,
            com.me.ubox.player.DecoderPrefs.SOFT,
            com.me.ubox.player.DecoderPrefs.AUTO,
        )
        SinglePickSheet(
            title = "解码方式",
            items = options,
            labelOf = { com.me.ubox.player.DecoderPrefs.label(it) },
            subtitleOf = { com.me.ubox.player.DecoderPrefs.description(it) },
            current = decoderMode,
            isSelected = { it == decoderMode },
            onPick = { picked ->
                showDecoderPicker = false
                com.me.ubox.player.DecoderPrefs.setMode(picked)
            },
            onDismiss = { showDecoderPicker = false },
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
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
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
    val remote by TvMode.isRemote.collectAsStateWithLifecycle()
    val textStyle = if (tv) {
        MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp)
    } else {
        null
    }
    val button: @Composable () -> Unit = {
        if (filled) {
            androidx.compose.material3.Button(
                onClick = if (remote) ({}) else onClick,
                enabled = enabled && !remote,
                modifier = Modifier.height(if (tv) 48.dp else 40.dp),
            ) {
                Text(label, style = textStyle ?: MaterialTheme.typography.bodyMedium)
            }
        } else {
            OutlinedButton(
                onClick = if (remote) ({}) else onClick,
                enabled = enabled && !remote,
                modifier = Modifier.height(if (tv) 48.dp else 40.dp),
            ) {
                Text(label, style = textStyle ?: MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (!remote) {
        button()
        return
    }
    TvFocusable(onTap = if (enabled) onClick else ({}), radius = 8.dp) {
        button()
    }
}

/** ListItem 的可点击修饰 (手机模式); TV 下所在页签不可见时退出焦点遍历. */
@Composable
private fun Modifier.clickableSetting(onClick: () -> Unit): Modifier {
    val tv by TvMode.isRemote.collectAsStateWithLifecycle()
    val guarded = this.pageFocusGuard()
    if (!tv) return guarded.clickable(onClick = onClick)
    // TV: 遥控器焦点显示白色描边 (默认 clickable 的焦点指示在深色背景下几乎看不见).
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    // 修饰链结构必须恒定: 原先焦点时在 clickable 之前插入 border, 修饰链结构变化会让
    // clickable (focusable) 节点被重建 -> 焦点丢失 -> 描边消失, 表现为"没有焦点".
    // 现在 clickable 在前、border 恒在后, 仅切换颜色.
    return guarded
        .clickable(interactionSource = source, indication = null, onClick = onClick)
        .border(
            2.dp,
            if (focused) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Transparent,
            androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        )
}

/** 重启已排程 (防止 2.5s 窗口内连点多个线路导致重复启动). */
private var restartScheduled = false

/**
 * 配置/线路切换后重启应用 (对照原版 ModelSettingFragment.restartAppAfterConfigChanged):
 * Toast 提示 + 2.5s 延迟后启动 Launch Intent (FLAG_ACTIVITY_CLEAR_TOP) 并杀进程.
 *
 * 为什么必须重启而不是只刷 StateFlow: ConfigStore.loaded 变化只驱动了监听它的 UI,
 * 但自绘返回栈里的二级页 (详情/播放/浏览) 与各 VM 缓存 (首页 tabStates、
 * 保活的 SearchResultsViewModel) 仍持旧站源数据, 引擎 spider 实例同理;
 * 整个进程重建才能保证切换彻底 (对照原版 System.exit(0) 的效果).
 */
private fun restartAppAfterConfigChanged(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    if (restartScheduled) return
    restartScheduled = true
    Toast.makeText(context, "配置已切换, 即将自动重启应用", Toast.LENGTH_SHORT).show()
    scope.launch {
        delay(2500)
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
        }
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(0)
    }
}

package com.novabox.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novabox.TvMode
import com.novabox.data.ConfigLine
import com.novabox.data.History
import com.novabox.data.HomePrefs
import com.novabox.data.Site
import com.novabox.ui.components.ErrorBox
import com.novabox.ui.components.LoadingBox
import com.novabox.ui.components.TvButton
import com.novabox.ui.components.TvFocusable
import com.novabox.ui.components.VodPoster
import com.novabox.ui.AppShell
import com.novabox.ui.AppSnackbar
import com.novabox.ui.dialogs.SinglePickSheet
import android.view.KeyEvent as AndroidKeyEvent

/**
 * 首页 (对照 Flutter 版 home_page.dart):
 * 顶部 线路/站源/搜索/收藏/历史 + 横向分类 Tab + 每 Tab 海报网格.
 */
@Composable
fun HomeScreen(
    onOpenDetail: (String, String, String) -> Unit,
    onOpenSearch: (String?, String) -> Unit,
    onOpenBrowse: (String, String) -> Unit,
    onOpenPlayer: (String, String, String, String, String, List<String>, List<String>, Int) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val site by vm.site.collectAsStateWithLifecycle()
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val tabStates by vm.tabStates.collectAsStateWithLifecycle()
    val selectedTab by vm.selectedTab.collectAsStateWithLifecycle()
    val bootLoading by vm.bootLoading.collectAsStateWithLifecycle()
    val bootError by vm.bootError.collectAsStateWithLifecycle()
    val lineBusy by vm.lineBusy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val hasConfig = config != null && config!!.sites.any { it.isRealSource }
    val sites = config?.sites?.filter { it.isRealSource } ?: emptyList()
    val lines = config?.lines ?: emptyList()

    var showSitePicker by remember { mutableStateOf(false) }
    var showLinePicker by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        if (message != null) {
            AppSnackbar.show(message)
            vm.consumeMessage()
        }
    }

    if (!hasConfig) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (bootLoading) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                } else {
                    Text(
                        text = bootError.ifEmpty { "未导入配置" },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "请到\"设置\"页导入 TVBox 配置",
                        color = androidx.compose.ui.graphics.Color.Gray,
                    )
                }
            }
        }
        return
    }

    // MENU 键 (对照原版 HomeActivity.dispatchKeyEvent): 短按 = 换源, 长按 2s = 设置页. 仅 TV 模式生效.
    var menuDownAt by remember { mutableLongStateOf(0L) }
    val menuModifier = if (tv) {
        Modifier.onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (menuDownAt == 0L) menuDownAt = System.currentTimeMillis()
                        true
                    }
                    KeyEventType.KeyUp -> {
                        val held = System.currentTimeMillis() - menuDownAt
                        menuDownAt = 0L
                        if (held >= 2000) {
                            com.novabox.ui.AppShell.selectTab(com.novabox.ui.AppShell.SETTINGS_TAB)
                        } else {
                            showSitePicker = true
                        }
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
    } else {
        Modifier
    }

    Column(modifier = menuModifier.fillMaxSize()) {
        HomeTopBar(
            tv = tv,
            lineLabel = lineLabelOf(lines, config),
            siteName = site?.displayName ?: "站源",
            lineBusy = lineBusy,
            onLineClick = { if (lines.size >= 2) showLinePicker = true },
            onSiteClick = { showSitePicker = true },
            onSearchClick = { onOpenSearch(site?.key, "") },
            onFavoritesClick = { com.novabox.ui.AppShell.selectTab(2) },
            onHistoryClick = { com.novabox.ui.AppShell.selectTab(3) },
        )
        HomeTabBar(
            tabs = tabs,
            selected = selectedTab,
            tv = tv,
            onSelect = vm::selectTab,
        )
        Box(modifier = Modifier.fillMaxSize()) {
            val tab = tabs.getOrNull(selectedTab)
            val st = tab?.let { tabStates[it.id] }
            when {
                tab == null || st == null -> LoadingBox()
                st.error.isNotEmpty() && st.items.isEmpty() -> ErrorBox(
                    message = st.error,
                    onRetry = vm::retryTab,
                )
                st.items.isEmpty() && st.loading -> LoadingBox()
                st.items.isEmpty() -> {
                    val hint = if (tab.id == "my0") {
                        if (HomePrefs.homeRec == 2) "暂无观看历史" else "暂无数据"
                    } else {
                        "暂无数据"
                    }
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(text = hint, color = androidx.compose.ui.graphics.Color.Gray)
                        if (HomePrefs.homeRec != 2 || tab.id != "my0") {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = vm::retryHomeContent) { Text("重试") }
                        }
                    }
                }
                else -> HomeGrid(
                    items = st.items,
                    tv = tv,
                    isHomeTab = tab.id == "my0",
                    site = site,
                    tabStates = tabStates,
                    tabs = tabs,
                    selectedTab = selectedTab,
                    hasMore = st.hasMore && tab.id != "my0",
                    onLoadMore = vm::loadMore,
                    onOpenVod = { item ->
                        when {
                            item.searchOnly -> onOpenSearch(item.siteKey.ifEmpty { site?.key ?: "" }, item.name)
                            item.siteKey.isNotEmpty() && item.siteKey != site?.key -> {
                                // 回原始源 (对照原版 Movie.Video.sourceKey)
                                onOpenDetail(item.siteKey, item.id, item.name)
                            }
                            else -> onOpenDetail(site?.key ?: "", item.id, item.name)
                        }
                    },
                )
            }
        }
    }

    if (showSitePicker) {
        SinglePickSheet(
            title = "切换站源",
            items = sites,
            labelOf = { it.displayName },
            subtitleOf = { it.key },
            current = site,
            isSelected = { it.key == site?.key },
            onPick = {
                showSitePicker = false
                vm.selectSite(it)
            },
            onDismiss = { showSitePicker = false },
        )
    }
    if (showLinePicker) {
        SinglePickSheet(
            title = "切换线路",
            items = lines,
            labelOf = { it.name },
            subtitleOf = { it.url },
            current = null,
            isSelected = { it.url == config?.url },
            onPick = {
                showLinePicker = false
                vm.switchLine(it)
            },
            onDismiss = { showLinePicker = false },
        )
    }
}

private fun lineLabelOf(lines: List<ConfigLine>, config: com.novabox.data.Config?): String {
    if (config == null) return "线路"
    for (l in lines) {
        if (l.url == config.url) return l.name
    }
    return "线路"
}

/** 顶部: 线路 / 站源 / 搜索 / 收藏 / 历史 (TV 下为可聚焦 chip). */
@Composable
private fun HomeTopBar(
    tv: Boolean,
    lineLabel: String,
    siteName: String,
    lineBusy: Boolean,
    onLineClick: () -> Unit,
    onSiteClick: () -> Unit,
    onSearchClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onHistoryClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (tv) 20.dp else 12.dp, top = if (tv) 12.dp else 8.dp, end = if (tv) 16.dp else 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvFocusable(radius = 8.dp, onTap = onLineClick) {
            BarChip(label = lineLabel, icon = Icons.Filled.ArrowDropDown, busy = lineBusy, tv = tv)
        }
        Spacer(Modifier.width(6.dp))
        TvFocusable(radius = 8.dp, onTap = onSiteClick) {
            BarChip(label = siteName, icon = Icons.Filled.ArrowDropDown, tv = tv)
        }
        Spacer(Modifier.width(6.dp))
        TvFocusable(radius = 18.dp, onTap = onSearchClick, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .height(if (tv) 52.dp else 36.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = androidx.compose.ui.graphics.Color.Gray,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "搜索影视...",
                        color = androidx.compose.ui.graphics.Color.Gray,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = (if (tv) 17 else 12).sp),
                    )
                }
            }
        }
        if (tv) {
            Spacer(Modifier.width(8.dp))
            TvButton(label = "收藏", icon = Icons.Filled.Star, height = 52.dp, onTap = onFavoritesClick)
            Spacer(Modifier.width(6.dp))
            TvButton(label = "历史", icon = Icons.Filled.History, height = 52.dp, onTap = onHistoryClick)
        } else {
            Icon(imageVector = Icons.Filled.Star, contentDescription = "收藏", modifier = Modifier.clickable(onClick = onFavoritesClick).padding(8.dp))
            Icon(imageVector = Icons.Filled.History, contentDescription = "历史", modifier = Modifier.clickable(onClick = onHistoryClick).padding(8.dp))
        }
    }
}

@Composable
private fun BarChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tv: Boolean, busy: Boolean = false) {
    Box(
        modifier = Modifier
            .height(if (tv) 44.dp else 36.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
            .padding(horizontal = if (tv) 12.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = (if (tv) 17 else 13).sp,
                        fontWeight = FontWeight.W600,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(if (tv) 22.dp else 18.dp),
                tint = androidx.compose.ui.graphics.Color.Gray,
            )
        }
    }
}

/** 横向分类 Tab (主页 + 站点分类). */
@Composable
private fun HomeTabBar(
    tabs: List<HomeTab>,
    selected: Int,
    tv: Boolean,
    onSelect: (Int) -> Unit,
) {
    if (tabs.size <= 1) return
    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        listState.animateScrollToItem(selected.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
    }
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(if (tv) 60.dp else 44.dp),
        contentPadding = PaddingValues(horizontal = if (tv) 16.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(tabs) { i, tab ->
            val selectedTab = i == selected
            Box(modifier = Modifier.padding(horizontal = 4.dp)) {
                if (tv) {
                    TvFocusable(radius = 8.dp, onTap = { onSelect(i) }) {
                        FilterChip(
                            selected = selectedTab,
                            onClick = { onSelect(i) },
                            label = {
                                Text(
                                    text = tab.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 17.sp),
                                )
                            },
                        )
                    }
                } else {
                    FilterChip(
                        selected = selectedTab,
                        onClick = { onSelect(i) },
                        label = { Text(text = tab.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}

/** 海报网格 + 触底加载更多 (对照原版 BaseQuickAdapter RequestLoadMoreListener). */
@Composable
private fun HomeGrid(
    items: List<com.novabox.data.VodItem>,
    tv: Boolean,
    isHomeTab: Boolean,
    site: Site?,
    tabStates: Map<String, HomeTabState>,
    tabs: List<HomeTab>,
    selectedTab: Int,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onOpenVod: (com.novabox.data.VodItem) -> Unit,
) {
    val gridState = rememberLazyGridState()
    // 触底加载更多
    LaunchedEffect(gridState, items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (hasMore && lastVisible >= items.size - 3) {
                    val st = tabs.getOrNull(selectedTab)?.let { tabStates[it.id] }
                    if (st != null && !st.loading) onLoadMore()
                }
            }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = if (tv) 200.dp else 130.dp),
        contentPadding = PaddingValues(if (tv) 16.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (tv) 16.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (tv) 16.dp else 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(items) { _, item ->
            val badgeText = if (isHomeTab) {
                if (HomePrefs.homeRec == 2) {
                    History.siteOf(item.siteKey)?.displayName ?: "搜"
                } else {
                    HomePrefs.recName(HomePrefs.homeRec)
                }
            } else {
                null
            }
            val badgeBg = if (isHomeTab && HomePrefs.homeRec == 2) {
                androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.54f)
            } else {
                androidx.compose.ui.graphics.Color(0xBD0CADE2)
            }
            VodPoster(
                item = item,
                onClick = { onOpenVod(item) },
                showYearBadge = !isHomeTab,
                badgeText = badgeText,
                badgeBg = badgeBg,
                tvSizes = tv,
            )
        }
        if (hasMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

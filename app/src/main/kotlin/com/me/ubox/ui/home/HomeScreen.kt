package com.me.ubox.ui.home

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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.me.ubox.TvMode
import com.me.ubox.data.History
import com.me.ubox.data.HomePrefs
import com.me.ubox.data.Site
import com.me.ubox.ui.components.ErrorBox
import com.me.ubox.ui.components.LoadingBox
import com.me.ubox.ui.components.TvButton
import com.me.ubox.ui.components.TvFocusable
import com.me.ubox.ui.components.VodPoster
import com.me.ubox.ui.components.pageFocusGuard
import com.me.ubox.ui.AppShell
import com.me.ubox.ui.AppSnackbar
import com.me.ubox.ui.dialogs.SinglePickSheet
import android.view.KeyEvent as AndroidKeyEvent

/**
 * 首页 (对照 Flutter 版 home_page.dart):
 * 顶部 站源/搜索/收藏/历史 + 横向分类 Tab + 每 Tab 海报网格.
 */
@Composable
fun HomeScreen(
    onOpenDetail: (String, String, String) -> Unit,
    onOpenSearch: (String) -> Unit,
    onOpenBrowse: (String, String) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlayer: (String, String, String, String, String, List<String>, List<String>, Int) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val remote by TvMode.isRemote.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val site by vm.site.collectAsStateWithLifecycle()
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val tabStates by vm.tabStates.collectAsStateWithLifecycle()
    val selectedTab by vm.selectedTab.collectAsStateWithLifecycle()
    val bootLoading by vm.bootLoading.collectAsStateWithLifecycle()
    val bootError by vm.bootError.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val hasConfig = config != null && config!!.sites.any { it.isRealSource }
    val sites = config?.sites?.filter { it.isRealSource } ?: emptyList()

    var showSitePicker by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        if (message != null) {
            AppSnackbar.show(message ?: "")
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
                            com.me.ubox.ui.AppShell.selectTab(com.me.ubox.ui.AppShell.SETTINGS_TAB)
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
            remote = remote,
            siteName = site?.displayName ?: "站源",
            onSiteClick = { showSitePicker = true },
            onSearchClick = { onOpenSearch("") },
            onFavoritesClick = onOpenFavorites,
            onHistoryClick = onOpenHistory,
            onLiveClick = onOpenLive,
            onOpenSettings = onOpenSettings,
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
                        // 对照 Flutter: homeRec==2 (观看历史来源) 时无重试按钮, 与 tab 无关.
                        if (HomePrefs.homeRec != 2) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = vm::retryHomeContent,
                                modifier = Modifier.pageFocusGuard(),
                            ) { Text("重试") }
                        }
                    }
                }
                else -> HomeGrid(
                    items = st.items,
                    tv = tv,
                    tabId = tab.id,
                    isHomeTab = tab.id == "my0",
                    site = site,
                    tabStates = tabStates,
                    tabs = tabs,
                    selectedTab = selectedTab,
                    hasMore = st.hasMore && tab.id != "my0",
                    onLoadMore = vm::loadMore,
                    onOpenVod = { item ->
                        // 对照 Flutter: History.siteOf(item.siteKey) ?? _site —
                        // 站点已不在当前配置 (陈旧 key) 时回落当前站, 而不是拿失效 key 打开详情.
                        val resolved = History.siteOf(item.siteKey) ?: site
                        if (resolved != null) {
                            if (item.searchOnly) {
                                // 豆瓣热播条目没有 vod_id: 跳搜索页
                                onOpenSearch(item.name)
                            } else {
                                onOpenDetail(resolved.key, item.id, item.name)
                            }
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
}

/** 顶部: 站源 / 搜索 / 收藏 / 历史 / 直播 (TV 下为可聚焦 chip; 真 TV 追加设置入口). */
@Composable
private fun HomeTopBar(
    tv: Boolean,
    remote: Boolean,
    siteName: String,
    onSiteClick: () -> Unit,
    onSearchClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onLiveClick: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (tv) 20.dp else 12.dp, top = if (tv) 12.dp else 8.dp, end = if (tv) 16.dp else 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvFocusable(radius = 8.dp, onTap = onSiteClick) {
            BarChip(label = siteName, icon = Icons.Filled.ArrowDropDown, tv = tv)
        }
        Spacer(Modifier.width(6.dp))
        if (!tv) {
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
        }
        if (tv) {
            // TV: 搜索入口是独立的"搜索"按钮 (与 收藏/历史 同排), 不再是占满整行的假输入框.
            Spacer(Modifier.weight(1f))
            TvButton(label = "搜索", icon = Icons.Filled.Search, height = 52.dp, onTap = onSearchClick)
            Spacer(Modifier.width(6.dp))
            // 对照 Flutter: 收藏按钮图标是 star_border (描边星), 不是实心星.
            TvButton(label = "收藏", icon = Icons.Outlined.StarBorder, height = 52.dp, onTap = onFavoritesClick)
            Spacer(Modifier.width(6.dp))
            TvButton(label = "历史", icon = Icons.Filled.History, height = 52.dp, onTap = onHistoryClick)
            Spacer(Modifier.width(6.dp))
            TvButton(label = "直播", icon = Icons.Filled.LiveTv, height = 52.dp, onTap = onLiveClick)
            // 真 TV 无左侧栏: 设置入口放首页右上角, 与 搜索/收藏/历史/直播 同排
            // (平板仍走左侧栏, 手机走底部导航, 均不显示此按钮).
            if (remote) {
                Spacer(Modifier.width(6.dp))
                TvButton(label = "设置", icon = Icons.Filled.Settings, height = 52.dp, onTap = onOpenSettings)
            }
        } else {
            Icon(imageVector = Icons.Outlined.StarBorder, contentDescription = "收藏", modifier = Modifier.clickable(onClick = onFavoritesClick).padding(8.dp))
            Icon(imageVector = Icons.Filled.History, contentDescription = "历史", modifier = Modifier.clickable(onClick = onHistoryClick).padding(8.dp))
            Icon(imageVector = Icons.Filled.LiveTv, contentDescription = "直播", modifier = Modifier.clickable(onClick = onLiveClick).padding(8.dp))
        }
    }
}

@Composable
private fun BarChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tv: Boolean) {
    Box(
        modifier = Modifier
            .height(if (tv) 44.dp else 36.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
            .padding(horizontal = if (tv) 12.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = (if (tv) 17 else 13).sp,
                    fontWeight = FontWeight.W600,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                            modifier = Modifier.pageFocusGuard(),
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
    items: List<com.me.ubox.data.VodItem>,
    tv: Boolean,
    tabId: String,
    isHomeTab: Boolean,
    site: Site?,
    tabStates: Map<String, HomeTabState>,
    tabs: List<HomeTab>,
    selectedTab: Int,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onOpenVod: (com.me.ubox.data.VodItem) -> Unit,
) {
    // 每个 Tab 独立滚动位置 (对照 Flutter IndexedStack 的保活语义).
    val gridStates = remember { mutableMapOf<String, LazyGridState>() }
    val gridState = gridStates.getOrPut(tabId) { LazyGridState() }
    // 触底加载更多
    val density = LocalDensity.current
    val bottomThresholdPx = with(density) { 200.dp.toPx() }
    LaunchedEffect(gridState, items.size, hasMore) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@snapshotFlow false
            // 触底阈值: 最后可见项底部距 viewport 底部不足 200px
            // (对照 Flutter pos.pixels >= pos.maxScrollExtent - 200).
            // GridItemInfo 的 offset 是 IntOffset (x/y), size 是 IntSize; 纵向主轴取 y + height.
            last.offset.y + last.size.height >= info.viewportEndOffset - bottomThresholdPx
        }.collect { nearBottom ->
            if (hasMore && nearBottom) {
                val st = tabs.getOrNull(selectedTab)?.let { tabStates[it.id] }
                if (st != null && !st.loading) onLoadMore()
            }
        }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = if (tv) 200.dp else 104.dp),
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

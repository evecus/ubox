package com.me.ubox.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.me.ubox.TvMode
import com.me.ubox.ui.components.LocalPageVisible
import com.me.ubox.ui.theme.UBoxTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TV 与平板强制横屏; 手机模式不限制方向. (对照 Flutter main.dart: sensorLandscape); 手机模式不限制方向.
        applyOrientation(TvMode.isTV.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TvMode.isTV.collect { applyOrientation(it) }
            }
        }
        setContent {
            UBoxTheme {
                UBoxApp()
            }
        }
    }

    private fun applyOrientation(tv: Boolean) {
        requestedOrientation = if (tv) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

/** 应用外壳共享状态 (对照 Flutter 版 RootPage 的静态 notifier). */
object AppShell {

    /** 当前页签索引 (首页 / 设置). 直播页内可写它跳转设置页 (UBoxApp 会同时关闭直播页). */
    private val _tabIndex = MutableStateFlow(0)
    val tabIndex: StateFlow<Int> = _tabIndex.asStateFlow()

    /** 直播页全屏状态: true 时隐藏 AppBar 与底部导航 (真全屏直播). */
    private val _liveFullscreen = MutableStateFlow(false)
    val liveFullscreen: StateFlow<Boolean> = _liveFullscreen.asStateFlow()

    const val SETTINGS_TAB = 1
    const val HOME_TAB = 0

    fun selectTab(i: Int) {
        _tabIndex.value = i
    }

    fun setLiveFullscreen(v: Boolean) {
        _liveFullscreen.value = v
    }
}

/** 全局 Snackbar (对照 Flutter 版 ScaffoldMessenger): 任意页面调用 [show], 根层统一展示. */
object AppSnackbar {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun show(msg: String) {
        _message.value = msg
    }

    internal fun consume() {
        _message.value = null
    }
}

/** 应用路由 (自绘轻量返回栈, 对照 Flutter 版 Navigator.push/pop/pushReplacement). */
sealed class Route {
    data object Main : Route()

    /**
     * 点播页 (三端同一个单页: 影视信息 + 源 + 剧集 + 播放, 可切全屏):
     *   TV = 左播放器 | 右信息面板, 遥控器操作;
     *   平板 = 左 73% 播放器 | 右 27% 信息 (对照 NovaBox sw600dp);
     *   手机 = 上 16:9 播放器 | 下 信息 (对照 NovaBox activity_detail).
     */
    data class Vod(
        val siteKey: String,
        val vodId: String,
        val vodName: String,
    ) : Route()

    data class Browse(
        val siteKey: String,
        val initialTid: String = "",
    ) : Route()

    /**
     * 搜索页: 只负责输入与词面 (热门/猜你想搜/历史);
     * [initialQuery] 非空 + [autoSearch] = true 时进入即搜索并推结果页
     * (首页热播条目跳转); 从结果页返回时路由已回填输入词, [autoSearch] = false.
     */
    data class Search(
        val initialQuery: String = "",
        val searchSiteKey: String? = null,
        val autoSearch: Boolean = true,
    ) : Route()

    /** 搜索结果页: 与搜索页分离, TV/平板为多列网格, 手机为单列列表. */
    data class SearchResults(
        val query: String,
        val searchSiteKey: String? = null,
    ) : Route()

    /** 收藏页: 独立页面, 不带导航栏 (同搜索页). */
    data object Favorites : Route()

    /** 观看历史页: 独立页面, 不带导航栏 (同搜索页). */
    data object History : Route()

    /** 直播页: 独立页面, 不带导航栏 (同搜索页). */
    data object Live : Route()

}

/**
 * 二级页面的 per-route ViewModelStore (对照 Flutter Navigator: push 的页面各自持有
 * State, pop 即销毁). 自绘返回栈没有 destination 级 owner, viewModel() 会退化成
 * Activity 级缓存 —— 播放页退出不 clear, ExoPlayer 不 release (退出后后台继续播),
 * 且再次进入播放页/详情页会复用上一个条目的旧 VM.
 */
private class RouteViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/** 给二级页面提供 per-route ViewModelStore, 页面离开组合树 (pop) 时清空. */
@Composable
private fun RouteOwned(content: @Composable () -> Unit) {
    val owner = remember { RouteViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        content()
    }
}

@Composable
fun UBoxApp() {
    val backStack = remember { mutableStateListOf<Route>(Route.Main) }
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    BackHandler(enabled = backStack.size > 1) {
        backStack.removeAt(backStack.size - 1)
    }
    // 直播页内点"去设置": 页签切到设置 -> 关闭直播页, 回到外壳显示设置页.
    val shellTab by AppShell.tabIndex.collectAsStateWithLifecycle()
    LaunchedEffect(shellTab) {
        if (backStack.last() is Route.Live) backStack.removeAt(backStack.size - 1)
    }
    // 全局 Snackbar 覆盖层 (对照 Flutter 版 MaterialApp 级的 ScaffoldMessenger).
    val snackbarMessage by AppSnackbar.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = msg)
        AppSnackbar.consume()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        val route = backStack.last()
        // 点播三端都是单页 (信息 + 源 + 剧集 + 播放, 可切全屏).
        val openDetail: (String, String, String) -> Unit = { siteKey, vodId, name ->
            backStack.add(Route.Vod(siteKey, vodId, name))
        }
        when (route) {
        Route.Main -> MainShell(
            tv = tv,
            onOpenDetail = openDetail,
            onOpenSearch = { q ->
                backStack.add(Route.Search(initialQuery = q, autoSearch = q.isNotEmpty()))
            },
            onOpenBrowse = { siteKey, tid ->
                backStack.add(Route.Browse(siteKey, tid))
            },
            onOpenFavorites = { backStack.add(Route.Favorites) },
            onOpenHistory = { backStack.add(Route.History) },
            onOpenLive = { backStack.add(Route.Live) },
            onOpenSettings = { AppShell.selectTab(AppShell.SETTINGS_TAB) },
        )

        is Route.Browse -> RouteOwned {
            com.me.ubox.ui.browse.BrowseScreen(
                siteKey = route.siteKey,
                initialTid = route.initialTid,
                onBack = { backStack.removeAt(backStack.size - 1) },
                onOpenDetail = openDetail,
            )
        }

        is Route.Search -> RouteOwned {
            com.me.ubox.ui.search.SearchScreen(
                initialQuery = route.initialQuery,
                searchSiteKey = route.searchSiteKey,
                autoSearch = route.autoSearch,
                onBack = { backStack.removeAt(backStack.size - 1) },
                onSubmit = { q, siteKey ->
                    // 回填搜索页路由条目 (从结果页返回时恢复输入词与指定搜索源,
                    // 对照原版 etSearch 保留已搜文本), 再推结果页.
                    val idx = backStack.indexOfFirst { it is Route.Search }
                    if (idx >= 0) {
                        backStack[idx] = Route.Search(
                            initialQuery = q,
                            searchSiteKey = siteKey,
                            autoSearch = false,
                        )
                    }
                    backStack.add(Route.SearchResults(query = q, searchSiteKey = siteKey))
                },
            )
        }

        is Route.SearchResults -> {
            // 不包 RouteOwned: VM 走 Activity 级 store (key=query), 跳详情/播放页
            // 再返回时搜索协程与部分结果保留 (RouteOwned 的 pop clear 会切断搜索).
            com.me.ubox.ui.search.SearchResultsScreen(
                searchSiteKey = route.searchSiteKey,
                query = route.query,
                onBack = { backStack.removeAt(backStack.size - 1) },
                onOpenDetail = openDetail,
            )
        }

        Route.Favorites -> RouteOwned {
            com.me.ubox.ui.favorites.FavoritesScreen(
                onBack = { backStack.removeAt(backStack.size - 1) },
                onOpenDetail = openDetail,
            )
        }

        Route.History -> RouteOwned {
            com.me.ubox.ui.history.HistoryScreen(
                onBack = { backStack.removeAt(backStack.size - 1) },
                onOpenDetail = openDetail,
            )
        }

        Route.Live -> RouteOwned {
            // 独立页面: 无底部栏/左侧栏; 非全屏时自己让出系统栏, 全屏时铺满 (原先由外壳处理).
            val liveFullscreen by AppShell.liveFullscreen.collectAsStateWithLifecycle()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (liveFullscreen) Color.Black else Color.Transparent)
                    .then(if (liveFullscreen) Modifier else Modifier.systemBarsPadding()),
            ) {
                com.me.ubox.ui.live.LiveScreen(
                    onGoSettings = { AppShell.selectTab(AppShell.SETTINGS_TAB) },
                )
            }
        }

        is Route.Vod -> RouteOwned {
            com.me.ubox.ui.vod.VodScreen(
                siteKey = route.siteKey,
                vodId = route.vodId,
                vodName = route.vodName,
                onBack = { backStack.removeAt(backStack.size - 1) },
                onSwitchSite = { siteKey, vodId, name ->
                    // 换源: pushReplacement (新站点重新拉详情)
                    backStack.removeAt(backStack.size - 1)
                    backStack.add(Route.Vod(siteKey, vodId, name))
                },
            )
        }

        }
        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private data class TabSpec(
    val outlinedIcon: ImageVector,
    val filledIcon: ImageVector,
    val label: String,
)

private val tabSpecs = listOf(
    TabSpec(Icons.Outlined.Home, Icons.Filled.Home, "首页"),
    TabSpec(Icons.Outlined.Settings, Icons.Filled.Settings, "设置"),
)

/**
 * 应用外壳 (对照 Flutter 版 RootPage):
 *   手机 = 底部导航; 平板 = 左侧导航栏; TV (遥控器) = 无左侧栏,
 *   设置入口在首页右上角 (与 搜索/收藏/历史/直播 同排), 设置页顶部有返回首页.
 * 直播全屏时收起导航栏 (对照原版全屏直播).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    tv: Boolean,
    onOpenDetail: (String, String, String) -> Unit,
    onOpenSearch: (String) -> Unit,
    onOpenBrowse: (String, String) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val tab by AppShell.tabIndex.collectAsStateWithLifecycle()
    // 导航栏 autofocus 只在首次成功聚焦前生效: 刚退出直播全屏时导航栏重建,
    // 不能抢走直播页刚还回的焦点 (对照 Flutter 版 _wasFullscreen 的可观测行为;
    // 不用布尔翻转是因为 Compose 的 LaunchedEffect(autofocus) 会在值变化时重新请求).
    var railFocusedOnce by remember { mutableStateOf(false) }

    val pages: List<@Composable () -> Unit> = listOf(
        {
            com.me.ubox.ui.home.HomeScreen(
                onOpenDetail = onOpenDetail,
                onOpenSearch = onOpenSearch,
                onOpenBrowse = onOpenBrowse,
                onOpenFavorites = onOpenFavorites,
                onOpenHistory = onOpenHistory,
                onOpenLive = onOpenLive,
                onOpenSettings = onOpenSettings,
            )
        },
        {
            com.me.ubox.ui.settings.SettingsScreen()
        },
    )

    if (!tv) {
        // ---------------- 手机: 底部导航 ----------------
        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabSpecs.forEachIndexed { i, spec ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { AppShell.selectTab(i) },
                            icon = {
                                Icon(
                                    imageVector = if (tab == i) spec.filledIcon else spec.outlinedIcon,
                                    contentDescription = spec.label,
                                )
                            },
                            label = { Text(spec.label) },
                        )
                    }
                }
            },
        ) { padding ->
            // Scaffold 的 padding 已包含状态栏 + 底部栏高度, 不能再叠加 statusBarsPadding
            // (否则顶部会多出一截空白).
            Box(modifier = Modifier.padding(padding)) {
                KeepAliveStack(index = tab, pages = pages)
            }
        }
        return
    }

    // ---------------- TV 布局: 左侧栏仅平板保留; 真 TV (遥控器) 无左侧栏 ----------------
    // KeepAliveStack 只在这一处调用: 全屏切换只增减 rail/标题行 (条件块不影响其后
    // 组合槽位), 页面组合不销毁 (对照 Flutter 版 _stackKey GlobalKey 的作用).
    // 全屏 (直播): 收起导航栏与标题栏, 页面铺满整屏.
    // 平板 (TV 布局 + 触摸) 的系统状态栏/导航栏是可见的, 这里没有 Scaffold 帮忙避让,
    // 必须自己让出系统栏 + 刘海区域; 真 TV 无系统栏 (且需要铺满), 不加.
    val remote by TvMode.isRemote.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (remote) {
                    Modifier
                } else {
                    Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                }
            ),
    ) {
        // 左侧栏是平板专属; 真 TV 的设置入口在首页右上角 (见 HomeTopBar).
        if (!remote) {
            TvRail(
                tab = tab,
                autofocus = !railFocusedOnce,
                onFocused = { railFocusedOnce = true },
                onSelect = { AppShell.selectTab(it) },
            )
            VerticalDivider(thickness = 1.dp)
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                KeepAliveStack(index = tab, pages = pages)
            }
        }
    }
}

/** 消费所有指针事件: 不可见页挡在可见页下方, 空白区域的触摸不能穿透 (对照 IndexedStack 的 Offstage). */
private fun Modifier.blockPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

/**
 * 页签保活容器 (对照 Flutter IndexedStack + ExcludeFocus):
 * 2 个页面同时保持组合, 仅当前页可见 —— 切换页签不丢状态
 * (首页数据/滚动位置, 直播页播放进度与已拉取的源列表).
 *
 *   - 可见页在最上层 (zIndex), 不可见页 alpha=0 不绘制;
 *   - 不可见页拦截全部指针事件 (手机触摸);
 *   - 不可见页经 LocalPageVisible=false 退出焦点遍历 (TV 遥控器, 对照 ExcludeFocus;
 *     focusProperties 的 canFocus 对子树无效, 收口见 Components.kt 注释).
 */
@Composable
private fun KeepAliveStack(
    index: Int,
    pages: List<@Composable () -> Unit>,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        pages.forEachIndexed { i, page ->
            val visible = i == index
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (visible) 1f else 0f)
                    .graphicsLayer { alpha = if (visible) 1f else 0f }
                    .then(if (visible) Modifier else Modifier.blockPointerInput()),
            ) {
                CompositionLocalProvider(LocalPageVisible provides visible) {
                    page()
                }
            }
        }
    }
}

/** TV 左侧导航: 每项可聚焦; OK 键切换页签, 向右键进入内容区. */
@Composable
fun TvRail(
    tab: Int,
    autofocus: Boolean,
    onFocused: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .width(112.dp)
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(
            modifier = Modifier.padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.MovieFilter,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(34.dp),
            )
            Text(
                text = "UBox",
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            tabSpecs.forEachIndexed { i, spec ->
                val selected = tab == i
                com.me.ubox.ui.components.TvFocusable(
                    onTap = { onSelect(i) },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    // 初始焦点落在当前选中的导航项上 (仅首次成功聚焦前, 见 MainShell 注释).
                    autofocus = autofocus && i == tab,
                    onFocusChanged = { focused -> if (focused) onFocused() },
                    radius = 14.dp,
                ) {
                    Box(
                        modifier = Modifier
                            .height(72.dp)
                            .fillMaxSize()
                            .background(
                                color = if (selected) {
                                    primary.copy(alpha = 0.28f)
                                } else {
                                    Color.Transparent
                                },
                                shape = RoundedCornerShape(14.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (selected) spec.filledIcon else spec.outlinedIcon,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
                            )
                            Text(
                                text = spec.label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 15.sp,
                                ),
                                fontWeight = if (selected) FontWeight.Bold else null,
                                color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}

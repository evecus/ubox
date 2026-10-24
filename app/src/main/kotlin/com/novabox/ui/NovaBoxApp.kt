package com.novabox.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.novabox.TvMode
import com.novabox.ui.theme.NovaBoxTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TV 模式强制横屏 (对照 Flutter main.dart: sensorLandscape); 手机模式不限制方向.
        applyOrientation(TvMode.isTV.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TvMode.isTV.collect { applyOrientation(it) }
            }
        }
        setContent {
            NovaBoxTheme {
                NovaBoxApp()
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

    /** 当前页签索引. 直播页监听它: 离开直播 tab 时停止播放; 直播页内也可反向写它跳转设置页. */
    private val _tabIndex = MutableStateFlow(0)
    val tabIndex: StateFlow<Int> = _tabIndex.asStateFlow()

    /** 直播页全屏状态: true 时隐藏 AppBar 与底部导航 (真全屏直播). */
    private val _liveFullscreen = MutableStateFlow(false)
    val liveFullscreen: StateFlow<Boolean> = _liveFullscreen.asStateFlow()

    const val LIVE_TAB = 1
    const val SETTINGS_TAB = 4

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

    data class Detail(
        val siteKey: String,
        val vodId: String,
        val vodName: String,
    ) : Route()

    data class Browse(
        val siteKey: String,
        val initialTid: String = "",
    ) : Route()

    data class Search(
        val siteKey: String? = null,
        val initialQuery: String = "",
    ) : Route()

    data class Player(
        val siteKey: String,
        val vodId: String,
        val vodName: String,
        val cover: String,
        val flag: String,
        val epNames: List<String>,
        val epUrls: List<String>,
        val startIndex: Int,
    ) : Route()
}

@Composable
fun NovaBoxApp() {
    val backStack = remember { mutableStateListOf<Route>(Route.Main) }
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    BackHandler(enabled = backStack.size > 1) {
        backStack.removeAt(backStack.size - 1)
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
        when (route) {
        Route.Main -> MainShell(
            tv = tv,
            onOpenDetail = { siteKey, vodId, name ->
                backStack.add(Route.Detail(siteKey, vodId, name))
            },
            onOpenSearch = { siteKey, q ->
                backStack.add(Route.Search(siteKey, q))
            },
            onOpenBrowse = { siteKey, tid ->
                backStack.add(Route.Browse(siteKey, tid))
            },
            onOpenPlayer = { siteKey, vodId, name, cover, flag, epNames, epUrls, startIndex ->
                backStack.add(
                    Route.Player(siteKey, vodId, name, cover, flag, epNames, epUrls, startIndex)
                )
            },
        )

        is Route.Detail -> com.novabox.ui.detail.DetailScreen(
            siteKey = route.siteKey,
            vodId = route.vodId,
            vodName = route.vodName,
            onBack = { backStack.removeAt(backStack.size - 1) },
            onOpenPlayer = { siteKey, vodId, name, cover, flag, epNames, epUrls, startIndex ->
                backStack.add(
                    Route.Player(siteKey, vodId, name, cover, flag, epNames, epUrls, startIndex)
                )
            },
            onReplaceWithDetail = { siteKey, vodId, name ->
                // 换源: pushReplacement (对照 Flutter 版 _showSwitchMenu)
                backStack.removeAt(backStack.size - 1)
                backStack.add(Route.Detail(siteKey, vodId, name))
            },
        )

        is Route.Browse -> com.novabox.ui.browse.BrowseScreen(
            siteKey = route.siteKey,
            initialTid = route.initialTid,
            onBack = { backStack.removeAt(backStack.size - 1) },
            onOpenDetail = { siteKey, vodId, name ->
                backStack.add(Route.Detail(siteKey, vodId, name))
            },
        )

        is Route.Search -> com.novabox.ui.search.SearchScreen(
            siteKey = route.siteKey,
            initialQuery = route.initialQuery,
            onBack = { backStack.removeAt(backStack.size - 1) },
            onOpenDetail = { siteKey, vodId, name ->
                backStack.add(Route.Detail(siteKey, vodId, name))
            },
        )

        is Route.Player -> com.novabox.ui.player.PlayerScreen(
            siteKey = route.siteKey,
            vodId = route.vodId,
            vodName = route.vodName,
            cover = route.cover,
            flag = route.flag,
            epNames = route.epNames,
            epUrls = route.epUrls,
            startIndex = route.startIndex,
            onBack = { backStack.removeAt(backStack.size - 1) },
        )
        }
        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private val tabTitles = listOf("首页", "直播", "收藏", "历史", "设置")

private data class TabSpec(
    val outlinedIcon: ImageVector,
    val filledIcon: ImageVector,
    val label: String,
)

private val tabSpecs = listOf(
    TabSpec(Icons.Outlined.Home, Icons.Filled.Home, "首页"),
    TabSpec(Icons.Outlined.LiveTv, Icons.Filled.LiveTv, "直播"),
    TabSpec(Icons.Outlined.Favorite, Icons.Filled.Favorite, "收藏"),
    TabSpec(Icons.Outlined.History, Icons.Filled.History, "历史"),
    TabSpec(Icons.Outlined.Settings, Icons.Filled.Settings, "设置"),
)

/**
 * 应用外壳 (对照 Flutter 版 RootPage):
 *   手机 = 底部导航; TV = 左侧导航栏 + 大字号标题行.
 * 直播全屏时收起导航栏 (对照原版全屏直播).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    tv: Boolean,
    onOpenDetail: (String, String, String) -> Unit,
    onOpenSearch: (String?, String) -> Unit,
    onOpenBrowse: (String, String) -> Unit,
    onOpenPlayer: (String, String, String, String, String, List<String>, List<String>, Int) -> Unit,
) {
    val tab by AppShell.tabIndex.collectAsStateWithLifecycle()
    val fullscreen by AppShell.liveFullscreen.collectAsStateWithLifecycle()

    val pages: List<@Composable () -> Unit> = listOf(
        {
            com.novabox.ui.home.HomeScreen(
                onOpenDetail = onOpenDetail,
                onOpenSearch = onOpenSearch,
                onOpenBrowse = onOpenBrowse,
                onOpenPlayer = onOpenPlayer,
            )
        },
        {
            com.novabox.ui.live.LiveScreen(
                onGoSettings = { AppShell.selectTab(AppShell.SETTINGS_TAB) },
            )
        },
        {
            com.novabox.ui.favorites.FavoritesScreen(
                onOpenDetail = onOpenDetail,
            )
        },
        {
            com.novabox.ui.history.HistoryScreen(
                onOpenDetail = onOpenDetail,
            )
        },
        {
            com.novabox.ui.settings.SettingsScreen()
        },
    )

    if (!tv) {
        // ---------------- 手机: 底部导航 ----------------
        Scaffold(
            topBar = {
        if (!fullscreen) {
            TopAppBar(title = { Text(tabTitles[tab]) })
        }
            },
            bottomBar = {
                if (!fullscreen) {
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
                }
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                pages[tab]()
            }
        }
        return
    }

    // ---------------- TV: 左侧导航栏 ----------------
    // 全屏 (直播): 收起导航栏与标题栏, 页面铺满整屏.
    if (fullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            pages[tab]()
        }
        return
    }
    Row(modifier = Modifier.fillMaxSize()) {
        TvRail(
            tab = tab,
            onSelect = { AppShell.selectTab(it) },
        )
        VerticalDivider(thickness = 1.dp)
        Column(modifier = Modifier.fillMaxSize()) {
            // TV 无 AppBar: 用一行大号标题代替 (10-foot UI).
            Box(
                modifier = Modifier
                    .padding(start = 20.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Text(
                    text = tabTitles[tab],
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            HorizontalDivider()
            Box(modifier = Modifier.fillMaxSize()) {
                pages[tab]()
            }
        }
    }
}

/** TV 左侧导航: 每项可聚焦; OK 键切换页签, 向右键进入内容区. */
@Composable
fun TvRail(tab: Int, onSelect: (Int) -> Unit) {
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
                text = "NovaBox",
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            tabSpecs.forEachIndexed { i, spec ->
                val selected = tab == i
                com.novabox.ui.components.TvFocusable(
                    onTap = { onSelect(i) },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    // 初始焦点落在当前选中的导航项上
                    autofocus = i == tab,
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

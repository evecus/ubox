package com.me.ubox.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.me.ubox.TvMode
import com.me.ubox.data.Site
import com.me.ubox.ui.AppSnackbar
import com.me.ubox.ui.components.TvButton
import com.me.ubox.ui.components.TvFocusable
import com.me.ubox.ui.dialogs.SinglePickSheet

/**
 * 全站搜索页 (对照原版 SearchActivity, 页签与搜索结果分离):
 *   手机 = 顶栏输入框 + 热词/历史;
 *   平板 = 搜索框 (软键盘) + 热门/历史 左右排布, 无屏显键盘;
 *   TV   = 三栏: 左栏 指定搜索源 + 输入框 + 搜索/清空 + 屏显键盘,
 *         中栏 热门/猜你想搜 (输入首字母后热门改为猜你想搜, 对照原版 loadRec),
 *         右栏 历史 (对照原版 historyWordGrid).
 *
 * 提交搜索后由调用方推 [com.me.ubox.ui.Route.SearchResults] 结果页;
 * 返回时路由条目已回填输入词与指定源 (恢复输入, 对照原版 etSearch 保留文本).
 */
@Composable
fun SearchScreen(
    initialQuery: String,
    searchSiteKey: String?,
    autoSearch: Boolean,
    onBack: () -> Unit,
    onSubmit: (String, String?) -> Unit,
    vm: SearchViewModel = viewModel(),
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val remote by TvMode.isRemote.collectAsStateWithLifecycle()
    val sites by vm.sites.collectAsStateWithLifecycle()
    val hotWords by vm.hotWords.collectAsStateWithLifecycle()
    val hotLoading by vm.hotLoading.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf(initialQuery) }
    var searchSite by remember { mutableStateOf(searchSiteKey) }
    var showSitePicker by remember { mutableStateOf(false) }
    val phoneInputFocusRequester = remember { FocusRequester() }
    val tvInputFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // 提交搜索 (对照原版 search()): 空词提示, 记历史, 手机收起软键盘.
    fun submitSearch(q: String) {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) {
            AppSnackbar.show("请输入搜索词")
            return
        }
        // 仅手机需要收起软键盘; TV 没有软键盘, 强制清焦点会让遥控器失去焦点落点.
        if (!tv) focusManager.clearFocus(force = true)
        vm.recordHistory(trimmed)
        onSubmit(trimmed, searchSite)
    }

    // 首页热播条目跳转 (对照原版 intent extra "title"): 初始词立即搜索进结果页.
    // autoSearch=false 时是从结果页返回 (路由已回填输入词), 只恢复输入不重复搜索.
    LaunchedEffect(Unit) {
        if (autoSearch && initialQuery.isNotEmpty()) submitSearch(initialQuery)
    }

    // 输入变化驱动猜你想搜 (仅 TV 屏显键盘; 手机/平板软键盘自带联想).
    val onInputChange: (String) -> Unit = { text ->
        input = text
        if (remote) vm.onInputChanged(text)
    }

    when {
        !tv -> PhoneSearchLayout(
            input = input,
            sitesCount = sites.size,
            hotWords = hotWords,
            hotLoading = hotLoading,
            history = history,
            inputFocusRequester = phoneInputFocusRequester,
            initialQuery = initialQuery,
            onInputChange = onInputChange,
            onSubmitQuery = { submitSearch(input) },
            onPickWord = { w -> input = w; submitSearch(w) },
            onClearHistory = vm::clearHistory,
            onBack = onBack,
        )

        remote -> TvSearchLayout(
            searchSiteName = searchSite
                ?.let { key -> sites.firstOrNull { it.key == key }?.name } ?: "全部源站",
            input = input,
            inputFocusRequester = tvInputFocusRequester,
            onInputChange = onInputChange,
            onSubmitQuery = { submitSearch(input) },
            onClearInput = {
                input = ""
                vm.clearSuggestions()
            },
            onPickSearchSite = { showSitePicker = true },
            hotWords = hotWords,
            hotLoading = hotLoading,
            suggestions = suggestions,
            history = history,
            onPickWord = { w -> input = w; submitSearch(w) },
            onClearHistory = vm::clearHistory,
        )

        else -> PadSearchLayout(
            searchSiteName = searchSite
                ?.let { key -> sites.firstOrNull { it.key == key }?.name } ?: "全部源站",
            input = input,
            onInputChange = onInputChange,
            onSubmitQuery = { submitSearch(input) },
            onPickSearchSite = { showSitePicker = true },
            hotWords = hotWords,
            hotLoading = hotLoading,
            history = history,
            onPickWord = { w -> input = w; submitSearch(w) },
            onClearHistory = vm::clearHistory,
            onBack = onBack,
        )
    }

    if (showSitePicker) {
        val options: List<Site?> = listOf(null) + sites
        SinglePickSheet(
            title = "指定搜索源",
            items = options,
            labelOf = { it?.name ?: "全部源站" },
            subtitleOf = { it?.key ?: "" },
            current = searchSite?.let { key -> sites.firstOrNull { it.key == key } },
            isSelected = { (it?.key ?: "") == (searchSite ?: "") },
            onPick = { picked ->
                showSitePicker = false
                searchSite = picked?.key
            },
            onDismiss = { showSitePicker = false },
        )
    }
}

// ---------------------------------------------------------------- 手机

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PhoneSearchLayout(
    input: String,
    sitesCount: Int,
    hotWords: List<String>,
    hotLoading: Boolean,
    history: List<String>,
    inputFocusRequester: FocusRequester,
    initialQuery: String,
    onInputChange: (String) -> Unit,
    onSubmitQuery: () -> Unit,
    onPickWord: (String) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit,
) {
    // 无初始词时自动聚焦输入框 (对照原版 autofocus).
    LaunchedEffect(Unit) {
        if (initialQuery.isEmpty()) {
            try { inputFocusRequester.requestFocus() } catch (_: IllegalStateException) {}
        }
    }
    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .border(
                                width = 1.2.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = RoundedCornerShape(20.dp),
                            )
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 用 BasicTextField 而非 OutlinedTextField: 后者最小高度 56dp,
                        // 放进 40dp 外框会把文字上下裁掉.
                        BasicTextField(
                            value = input,
                            onValueChange = onInputChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                MaterialTheme.colorScheme.onSurface,
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = { onSubmitQuery() },
                            ),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Search,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(inputFocusRequester),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (input.isEmpty()) {
                                        Text(
                                            text = "搜索影片 / 演员 / 关键词",
                                            maxLines = 1,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onSubmitQuery) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Text(
                    text = "输入关键词, 将并行搜索全部 $sitesCount 个源",
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Spacer(Modifier.height(20.dp))
                Text("热词", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (hotLoading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.width(20.dp).height(20.dp),
                    )
                } else if (hotWords.isEmpty()) {
                    Text("暂无热词", color = Color.Gray)
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        for (w in hotWords) {
                            FilterChip(
                                selected = false,
                                onClick = { onPickWord(w) },
                                label = {
                                    Text(
                                        text = w,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    }
                }
            }
            if (history.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("搜索历史", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "清除",
                            color = Color.Gray,
                            modifier = Modifier
                                .clickable(onClick = onClearHistory)
                                .padding(6.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(modifier = Modifier.fillMaxWidth()) {
                        for (w in history) {
                            FilterChip(
                                selected = false,
                                onClick = { onPickWord(w) },
                                label = {
                                    Text(
                                        text = w,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 平板

/**
 * 平板搜索页 (无屏显键盘, 点击搜索框唤软键盘):
 * 顶部 指定搜索源 + 搜索框 + 搜索按钮; 下方 热门搜索 | 搜索历史 左右排布.
 */
@Composable
private fun PadSearchLayout(
    searchSiteName: String,
    input: String,
    onInputChange: (String) -> Unit,
    onSubmitQuery: () -> Unit,
    onPickSearchSite: () -> Unit,
    hotWords: List<String>,
    hotLoading: Boolean,
    history: List<String>,
    onPickWord: (String) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvButton(
                label = searchSiteName,
                icon = Icons.Filled.Tune,
                height = 52.dp,
                fontSize = 17,
                onTap = onPickSearchSite,
            )
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                singleLine = true,
                placeholder = { Text("搜索影片 / 演员 / 关键词") },
                keyboardActions = KeyboardActions(onSearch = { onSubmitQuery() }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            TvButton(
                label = "搜索",
                icon = Icons.Filled.Search,
                height = 52.dp,
                fontSize = 18,
                selected = true,
                onTap = onSubmitQuery,
            )
            Spacer(Modifier.width(10.dp))
            TvButton(
                label = "返回",
                height = 52.dp,
                fontSize = 16,
                onTap = onBack,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxSize()) {
            PadWordColumn(
                title = "热门搜索",
                words = hotWords,
                loading = hotLoading,
                emptyText = "暂无热词",
                onPickWord = onPickWord,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            PadWordColumn(
                title = "搜索历史",
                words = history,
                loading = false,
                emptyText = "暂无搜索历史",
                onPickWord = onPickWord,
                onClear = onClearHistory,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PadWordColumn(
    title: String,
    words: List<String>,
    loading: Boolean,
    emptyText: String,
    onPickWord: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            )
            if (onClear != null && words.isNotEmpty()) {
                Spacer(Modifier.weight(1f))
                TvButton(
                    label = "清空",
                    height = 40.dp,
                    fontSize = 14,
                    onTap = onClear,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.width(20.dp).height(20.dp),
                )
            }
            words.isEmpty() -> Text(
                text = emptyText,
                color = Color.Gray,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                items(words) { w ->
                    Box(modifier = Modifier.padding(vertical = 4.dp)) {
                        WordPill(word = w, onPick = { onPickWord(w) })
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- TV (三栏, 对照原版 activity_search)

/**
 * TV 搜索页三栏 (对照原版 activity_search.xml 的横排布局):
 * 左栏 vs_420 (指定搜索源 + 输入框 + 搜索/清空 + 屏显键盘) |
 * 中栏 vs_270 (热门/猜你想搜) | 右栏 (历史网格, 对照原版 historyWordGrid).
 */
@Composable
private fun TvSearchLayout(
    searchSiteName: String,
    input: String,
    inputFocusRequester: FocusRequester,
    onInputChange: (String) -> Unit,
    onSubmitQuery: () -> Unit,
    onClearInput: () -> Unit,
    onPickSearchSite: () -> Unit,
    hotWords: List<String>,
    hotLoading: Boolean,
    suggestions: List<String>,
    history: List<String>,
    onPickWord: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    // TV: 无软键盘, 输入框自动获焦, 遥控器下键即可移到键盘按键.
    LaunchedEffect(Unit) {
        try { inputFocusRequester.requestFocus() } catch (_: IllegalStateException) {}
    }
    Row(modifier = Modifier.fillMaxSize()) {
        // 左栏: 指定搜索源 + 输入框 + 搜索/清空 + 屏显键盘
        Box(modifier = Modifier.width(430.dp)) {
            TvLeftPanel(
                searchSiteName = searchSiteName,
                input = input,
                inputFocusRequester = inputFocusRequester,
                onInputChange = onInputChange,
                onSearch = onSubmitQuery,
                onClear = onClearInput,
                onPickSearchSite = onPickSearchSite,
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        // 中栏: 热门 / 猜你想搜 (输入首字母后热门这一栏改为猜你想搜)
        Box(modifier = Modifier.width(240.dp)) {
            TvWordPanel(
                title = if (suggestions.isNotEmpty()) "猜你 想搜" else "热  门",
                words = if (suggestions.isNotEmpty()) suggestions else hotWords,
                loading = suggestions.isEmpty() && hotLoading,
                onPick = onPickWord,
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        // 右栏: 历史 (对照原版 llHistoryWord: 标题 + 清空 + 3 列网格)
        Box(modifier = Modifier.weight(1f)) {
            TvHistoryPanel(
                history = history,
                onPick = onPickWord,
                onClear = onClearHistory,
            )
        }
    }
}

/** 屏显键盘 (对照原版 SearchKeyboard: 远程搜索/删除 各占半行 + A-Z + 0-9). */
private val keyboardKeys: List<String> = buildList {
    add("远程搜索")
    add("删除")
    for (c in 'A'..'Z') add(c.toString())
    for (d in 0..9) add(d.toString())
}

@Composable
private fun TvLeftPanel(
    searchSiteName: String,
    input: String,
    inputFocusRequester: FocusRequester,
    onInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onPickSearchSite: () -> Unit,
) {
    var inputFocused by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        item {
            // 指定搜索源 (对照原版 tvSearchCheckboxBtn)
            TvButton(
                label = searchSiteName,
                icon = Icons.Filled.Tune,
                height = 52.dp,
                fontSize = 17,
                onTap = onPickSearchSite,
            )
            Spacer(Modifier.height(10.dp))
            // 输入框: 焦点态白色描边, 只读 (输入全靠下方键盘)
            Box(
                modifier = Modifier
                    .height(56.dp)
                    .fillMaxWidth()
                    .border(
                        width = if (inputFocused) 2.dp else 1.dp,
                        color = if (inputFocused) Color.White else Color.Gray.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .focusRequester(inputFocusRequester)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    singleLine = true,
                    readOnly = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { inputFocused = it.isFocused },
                    decorationBox = { inner ->
                        if (input.isEmpty()) {
                            Text(
                                text = "请输入要搜索的内容",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                            )
                        }
                        inner()
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row {
                TvButton(
                    label = "搜索",
                    icon = Icons.Filled.Search,
                    modifier = Modifier.weight(1f),
                    fillWidth = true,
                    height = 56.dp,
                    fontSize = 18,
                    selected = true,
                    onTap = onSearch,
                )
                Spacer(Modifier.width(10.dp))
                TvButton(
                    label = "清空",
                    modifier = Modifier.weight(1f),
                    fillWidth = true,
                    height = 56.dp,
                    fontSize = 18,
                    onTap = onClear,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        // 键盘: 首行 远程搜索/删除 各占半行, 其后每行 6 个字符键
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                KeyBtn("远程搜索", wide = true, modifier = Modifier.weight(1f)) {
                    onSearch()
                }
                KeyBtn("删除", wide = true, modifier = Modifier.weight(1f)) {
                    if (input.isNotEmpty()) onInputChange(input.dropLast(1))
                }
            }
        }
        val chars = keyboardKeys.drop(2)
        items(chars.chunked(6)) { rowKeys ->
            Row(modifier = Modifier.fillMaxWidth()) {
                for (k in rowKeys) {
                    KeyBtn(k, wide = false, modifier = Modifier.weight(1f)) {
                        onInputChange(input + k)
                    }
                }
                // 补齐不足 6 个的空位
                repeat(6 - rowKeys.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun KeyBtn(
    key: String,
    wide: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    Box(modifier = modifier.padding(3.dp)) {
        TvFocusable(radius = 6.dp, onTap = onTap, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .height(56.dp)
                    .fillMaxWidth()
                    .background(Color(0x6C3D3D3D), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = if (wide) 16.sp else 20.sp,
                        fontWeight = if (wide) FontWeight.W600 else FontWeight.Normal,
                    ),
                    color = Color.White,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- TV 中栏: 热门 / 猜你想搜

@Composable
private fun TvWordPanel(
    title: String,
    words: List<String>,
    loading: Boolean,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
        )
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.width(20.dp).height(20.dp))
            }
            return@Column
        }
        if (words.isEmpty()) {
            Text(
                text = "暂无词语",
                color = Color.Gray,
                modifier = Modifier.padding(12.dp),
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            items(words) { w ->
                Box(modifier = Modifier.padding(vertical = 3.dp)) {
                    WordPill(word = w, onPick = { onPick(w) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- TV 右栏: 历史

@Composable
private fun TvHistoryPanel(
    history: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "历  史",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            if (history.isNotEmpty()) {
                TvButton(
                    label = "清空",
                    height = 40.dp,
                    fontSize = 14,
                    onTap = onClear,
                )
            }
        }
        if (history.isEmpty()) {
            Text(
                text = "暂无搜索历史",
                color = Color.Gray,
                modifier = Modifier.padding(12.dp),
            )
            return@Column
        }
        // 3 列网格 (对照原版 historyWordGrid columnCount=3).
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(history) { w ->
                WordPill(word = w, onPick = { onPick(w) }, height = 46.dp, fontSize = 15)
            }
        }
    }
}

// ---------------------------------------------------------------- 共用词条

/** 词条胶囊 (热门/猜你想搜/历史 共用; TV 遥控器与平板触摸均可). */
@Composable
private fun WordPill(
    word: String,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 50.dp,
    fontSize: Int = 16,
) {
    TvFocusable(radius = 20.dp, onTap = onPick, modifier = modifier) {
        Box(
            modifier = Modifier
                .height(height)
                .fillMaxWidth()
                .background(Color(0x6C3D3D3D), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = word,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp),
            )
        }
    }
}

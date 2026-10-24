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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import com.me.ubox.TvMode
import com.me.ubox.data.Site
import com.me.ubox.ui.AppSnackbar
import com.me.ubox.ui.components.NetworkImage
import com.me.ubox.ui.components.SimpleTopBar
import com.me.ubox.ui.components.TvButton
import com.me.ubox.ui.components.TvFocusable
import com.me.ubox.ui.components.VodPoster
import com.me.ubox.ui.dialogs.SinglePickSheet

/**
 * 全站搜索页 (对照 Flutter 版 search_page.dart).
 *
 * 手机: 顶栏输入框 + 搜索按钮 + 源筛选 + 结果列表; 空状态给热词快捷入口.
 * TV: 三栏 —— 左栏 = 指定搜索源按钮 + 输入框 + 搜索/清空按钮 + 屏显键盘;
 *      中栏 = 热词; 右栏 = 源筛选 + 结果.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    siteKey: String?,
    initialQuery: String,
    onBack: () -> Unit,
    onOpenDetail: (String, String, String) -> Unit,
    vm: SearchViewModel = viewModel(factory = viewModelFactory {
        initializer { SearchViewModel(siteKey, initialQuery) }
    }),
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val remote by TvMode.isRemote.collectAsStateWithLifecycle()
    val sites by vm.sites.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val searchSite by vm.searchSite.collectAsStateWithLifecycle()
    val hotWords by vm.hotWords.collectAsStateWithLifecycle()
    val hotLoading by vm.hotLoading.collectAsStateWithLifecycle()
    val focusFirstResult by vm.focusFirstResult.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf(initialQuery) }
    var showSitePicker by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val phoneInputFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // 提交搜索 (对照 Flutter _search): 空词提示, 提交后收起软键盘.
    fun submitSearch(q: String) {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) {
            AppSnackbar.show("请输入搜索词")
            return
        }
        // 仅手机需要收起软键盘; TV 没有软键盘, 强制清焦点会让遥控器失去焦点落点.
        if (!tv) focusManager.clearFocus(force = true)
        vm.search(trimmed)
    }

    // 注意: 不把路由带来的 siteKey 设成搜索源 (对照原版 initialSite 不使用),
    // 初始即并行搜索全部源; 指定搜索源由用户点「指定搜索源」按钮选择.
    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotEmpty()) {
            input = initialQuery
            vm.search(initialQuery)
        }
    }
    LaunchedEffect(focusFirstResult) {
        if (focusFirstResult) {
            // autofocus 只在本帧生效, 下一帧清零防止后续重建抢焦点.
            vm.consumeFocusFirstResult()
        }
    }

    if (!tv) {
        // ---------------- 手机: 原布局 ----------------
        // 无初始词时自动聚焦输入框 (对照 Flutter autofocus: widget.initialQuery.isEmpty).
        LaunchedEffect(Unit) {
            if (initialQuery.isEmpty()) phoneInputFocusRequester.requestFocus()
        }
        androidx.compose.material3.Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Box(
                            modifier = Modifier
                                .height(40.dp)
                                .border(
                                    width = 1.2.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = RoundedCornerShape(20.dp),
                                )
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                singleLine = true,
                                placeholder = { Text("搜索影片 / 演员 / 关键词") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(phoneInputFocusRequester),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                    onSearch = { submitSearch(input) },
                                ),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                                ),
                                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                ),
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { submitSearch(input) }) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                SearchBody(
                    vm = vm,
                    tv = false,
                    query = query,
                    running = running,
                    done = done,
                    sitesCount = sites.size,
                    results = results,
                    filter = filter,
                    hotWords = hotWords,
                    onPickWord = { w ->
                        input = w
                        submitSearch(w)
                    },
                    onOpenDetail = onOpenDetail,
                )
            }
        }
        return
    }

    // ---------------- TV: 三栏 (键盘 / 热词 / 结果) ----------------
    // TV: 无软键盘, 输入框自动获焦, 遥控器下键即可移到键盘按键.
    LaunchedEffect(remote) {
        if (remote) {
            try { inputFocusRequester.requestFocus() } catch (_: IllegalStateException) {}
        }
    }
    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Row(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // 左栏: 指定搜索源 + 输入框 + 搜索/清空 + 屏显键盘
            Box(modifier = Modifier.width(430.dp)) {
                TvLeftPanel(
                    searchSiteName = searchSite?.let { key -> sites.firstOrNull { it.key == key }?.name } ?: "全部源站",
                    input = input,
                    inputFocused = inputFocused,
                    inputFocusRequester = inputFocusRequester,
                    onInputFocusChanged = { inputFocused = it },
                    onInputChange = { input = it },
                    onSearch = { submitSearch(input) },
                    onClear = {
                        input = ""
                        vm.clearQuery()
                    },
                    onPickSearchSite = { showSitePicker = true },
                )
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            // 中栏: 热词
            Box(modifier = Modifier.width(240.dp)) {
                HotWordPanel(
                    tv = true,
                    loading = hotLoading,
                    words = hotWords,
                    onPick = { w ->
                        input = w
                        submitSearch(w)
                    },
                )
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            // 右栏: 源筛选 + 结果
            Box(modifier = Modifier.weight(1f)) {
                SearchBody(
                    vm = vm,
                    tv = true,
                    query = query,
                    running = running,
                    done = done,
                    sitesCount = sites.size,
                    results = results,
                    filter = filter,
                    hotWords = hotWords,
                    onPickWord = { w ->
                        input = w
                        submitSearch(w)
                    },
                    onOpenDetail = onOpenDetail,
                )
            }
        }
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
                vm.setSearchSite(picked?.key)
            },
            onDismiss = { showSitePicker = false },
        )
    }
}

// ---------------------------------------------------------------- 结果区 (双端共用)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchBody(
    vm: SearchViewModel,
    tv: Boolean,
    query: String,
    running: Boolean,
    done: Int,
    sitesCount: Int,
    results: Map<String, List<com.me.ubox.data.VodItem>>,
    filter: String?,
    hotWords: List<String>,
    onPickWord: (String) -> Unit,
    onOpenDetail: (String, String, String) -> Unit,
) {
    if (query.isEmpty() && !running) {
        if (tv) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "用左侧键盘输入, 或按\"远程搜索\"",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = "输入关键词, 将并行搜索全部 $sitesCount 个源",
                    textAlign = TextAlign.Center,
                )
            }
            if (hotWords.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(20.dp))
                    Text("热词", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
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
        }
        return
    }
    val flat = vm.flatten()
    Column(modifier = Modifier.fillMaxSize()) {
        if (running) {
            LinearProgressIndicator(
                progress = { if (sitesCount > 0) done.toFloat() / sitesCount else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            )
        } else {
            Spacer(Modifier.height(3.dp))
        }
        if (results.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (running) "正在搜索 (\"$query\") ..." else "没有搜索结果")
            }
            return@Column
        }
        Row(modifier = Modifier.fillMaxSize()) {
            // 左侧: "全部" + 有结果的源
            Box(modifier = Modifier.width(118.dp)) {
                SiteFilterList(
                    sites = vm.sitesWithResults(),
                    filter = filter,
                    onFilter = vm::setFilter,
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                if (flat.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (running) "正在搜索 ..." else "没有搜索结果")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 10.dp, bottom = 12.dp),
                    ) {
                        items(flat) { hit ->
                            SearchResultRow(
                                hit = hit,
                                tv = tv,
                                autofocus = tv && vm.focusFirstResult.value && flat.firstOrNull() === hit,
                                onClick = { onOpenDetail(hit.site.key, hit.item.id, hit.item.name) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SiteFilterList(
    sites: List<Site>,
    filter: String?,
    onFilter: (String?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 6.dp, top = 4.dp, end = 4.dp, bottom = 12.dp),
    ) {
        item {
            SiteFilterTile(
                name = "全部",
                selected = filter == null,
                onClick = { onFilter(null) },
            )
        }
        items(sites) { site ->
            SiteFilterTile(
                name = site.name,
                selected = filter == site.key,
                onClick = { onFilter(site.key) },
            )
        }
    }
}

@Composable
private fun SiteFilterTile(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .padding(vertical = 3.dp)
            .fillMaxWidth()
            .border(
                width = if (selected) 1.5.dp else 0.dp,
                color = if (selected) scheme.onSurface else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
        )
    }
}

/** 右侧: 封面 + 片名 + 源站蓝标 + 备注. */
@Composable
private fun SearchResultRow(
    hit: SearchHit,
    tv: Boolean,
    autofocus: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    TvFocusable(
        autofocus = autofocus,
        radius = 8.dp,
        onTap = onClick,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x6C3D3D3D), RoundedCornerShape(8.dp))
                .padding(all = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(110.dp)
                    .height(150.dp)
                    .clip(RoundedCornerShape(6.dp)),
            ) {
                NetworkImage(
                    url = hit.item.cover,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = hit.item.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.W500,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .background(scheme.primary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = hit.site.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = scheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                val remark = hit.item.remark
                if (!remark.isNullOrEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = remark,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- TV 左栏: 键盘

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
    inputFocused: Boolean,
    inputFocusRequester: FocusRequester,
    onInputFocusChanged: (Boolean) -> Unit,
    onInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onPickSearchSite: () -> Unit,
) {
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
                androidx.compose.foundation.text.BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    singleLine = true,
                    readOnly = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { onInputFocusChanged(it.isFocused) },
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

// ---------------------------------------------------------------- TV 中栏: 热词

@Composable
private fun HotWordPanel(
    tv: Boolean,
    loading: Boolean,
    words: List<String>,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "热词",
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
                text = "暂无热词",
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
                    TvFocusable(radius = 20.dp, onTap = { onPick(w) }) {
                        Box(
                            modifier = Modifier
                                .height(50.dp)
                                .fillMaxWidth()
                                .background(Color(0x6C3D3D3D), RoundedCornerShape(20.dp))
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = w,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** BasicTextField 焦点变化修饰 (TV 输入框白描边) — 见 TvLeftPanel 中的 onFocusChanged 用法. */

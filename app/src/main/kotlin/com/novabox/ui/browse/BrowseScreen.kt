package com.novabox.ui.browse

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.novabox.TvMode
import com.novabox.ui.components.LoadingBox
import com.novabox.ui.components.NetworkImage
import com.novabox.ui.components.TvFocusable
import com.novabox.ui.components.VodPoster

/**
 * 分类浏览页 (对照 Flutter 版 browse_page.dart):
 * AppBar (标题/搜索) + 分类 chip 行 + 海报网格 + "更多 (下一页)".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    siteKey: String,
    initialTid: String,
    onBack: () -> Unit,
    onOpenDetail: (String, String, String) -> Unit,
    vm: BrowseViewModel = viewModel(factory = viewModelFactory {
        initializer { BrowseViewModel(siteKey, initialTid) }
    }),
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val site by vm.site.collectAsStateWithLifecycle()
    val classes by vm.classes.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val tid by vm.tid.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val searchMode by vm.searchMode.collectAsStateWithLifecycle()

    var searchText by remember { mutableStateOf("") }

    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = if (searchMode) {
                    {
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            singleLine = true,
                            placeholder = { Text("搜索...") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = ImeAction.Search,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onSearch = { vm.search(searchText) },
                            ),
                        )
                    }
                } else {
                    { Text(site?.displayName ?: "浏览") }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (searchMode) vm.exitSearch() else onBack()
                    }) {
                        Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.search(searchText) }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (classes.isNotEmpty() && !searchMode) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item {
                        ClassChip(name = "首页", selected = tid.isEmpty(), onClick = { vm.setClass("") })
                    }
                    items(classes) { c ->
                        val id = c.opt("type_id")?.toString() ?: ""
                        val name = c.opt("type_name")?.toString() ?: ""
                        ClassChip(name = name, selected = tid == id, onClick = { vm.setClass(id) })
                    }
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                when {
                    loading && items.isEmpty() -> LoadingBox()
                    items.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (searchMode) "输入关键字回车搜索" else "暂无数据")
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 130.dp),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(items) { _, item ->
                            TvFocusable(
                                onTap = { onOpenDetail(siteKey, item.id, item.name) },
                                radius = 8.dp,
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(if (tv) 190.dp else 170.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                    ) {
                                        NetworkImage(
                                            url = item.cover,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = item.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    )
                                    val remark = item.remark
                                    if (!remark.isNullOrEmpty()) {
                                        Text(
                                            text = remark,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = androidx.compose.ui.graphics.Color.Gray,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.height(16.dp).width(16.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { if (!loading) vm.nextPage() }) {
                    Text("更多 (下一页)")
                }
            }
        }
    }
}

@Composable
private fun ClassChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
    }
}


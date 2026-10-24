package com.me.ubox.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import com.me.ubox.TvMode
import com.me.ubox.data.Site
import com.me.ubox.data.VodItem
import com.me.ubox.ui.components.NetworkImage
import com.me.ubox.ui.components.SimpleTopBar
import com.me.ubox.ui.components.TvFocusable
import com.me.ubox.ui.components.VodPoster

/**
 * 搜索结果页 (与搜索页分离的独立页面):
 *   手机 = 单列列表 (封面 + 片名 + 源站 + 备注);
 *   TV/平板 = 多列海报网格 (对照原版 item_search / V7GridLayoutManager 4 列:
 *            封面 + 左上源站角标 + 底部备注 + 片名).
 *
 * VM 存活期: 走 Activity 级 store 且以 query 为 key, **不**包 RouteOwned ——
 * 从结果页跳详情/播放页再返回时 VM 不销毁, 进行中的搜索协程继续, 已完成
 * 的部分结果保留 (对照页签保活语义; RouteOwned 的 pop 即 clear 会切断搜索).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultsScreen(
    searchSiteKey: String?,
    query: String,
    onBack: () -> Unit,
    onOpenDetail: (String, String, String) -> Unit,
    vm: SearchResultsViewModel = viewModel(
        key = "search_results_$query",
        factory = viewModelFactory {
            initializer { SearchResultsViewModel(searchSiteKey, query) }
        },
    ),
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val sites by vm.sites.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val queryState by vm.query.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val focusFirstResult by vm.focusFirstResult.collectAsStateWithLifecycle()

    LaunchedEffect(focusFirstResult) {
        if (focusFirstResult) {
            // autofocus 只在本帧生效, 下一帧清零防止后续重建抢焦点.
            vm.consumeFocusFirstResult()
        }
    }

    Scaffold(
        topBar = { SimpleTopBar(title = "搜索结果", onBack = onBack) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            SearchResultsBody(
                vm = vm,
                tv = tv,
                query = queryState,
                running = running,
                done = done,
                sitesCount = sites.size,
                results = results,
                filter = filter,
                focusFirstResult = focusFirstResult,
                onOpenDetail = onOpenDetail,
            )
        }
    }
}

@Composable
private fun SearchResultsBody(
    vm: SearchResultsViewModel,
    tv: Boolean,
    query: String,
    running: Boolean,
    done: Int,
    sitesCount: Int,
    results: Map<String, List<VodItem>>,
    filter: String?,
    focusFirstResult: Boolean,
    onOpenDetail: (String, String, String) -> Unit,
) {
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
            Box(modifier = Modifier.width(140.dp)) {
                SiteFilterList(
                    sites = vm.sitesWithResults(),
                    filter = filter,
                    onFilter = vm::setFilter,
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                val flat = vm.flatten()
                if (flat.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (running) "正在搜索 ..." else "没有搜索结果")
                    }
                } else if (tv) {
                    // TV/平板: 多列海报网格 (对照原版 5 列结果网格).
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(flat) { index, hit ->
                            VodPoster(
                                item = hit.item,
                                onClick = { onOpenDetail(hit.site.key, hit.item.id, hit.item.name) },
                                badgeText = hit.site.name,
                                tvSizes = true,
                                autofocus = focusFirstResult && index == 0,
                            )
                        }
                    }
                } else {
                    // 手机: 单列列表.
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 10.dp, bottom = 12.dp),
                    ) {
                        items(flat) { hit ->
                            SearchResultRow(
                                hit = hit,
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
            .padding(horizontal = 8.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
        )
    }
}

/** 手机单列结果行: 封面 + 片名 + 源站蓝标 + 备注. */
@Composable
private fun SearchResultRow(
    hit: SearchHit,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    TvFocusable(
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
                    .width(96.dp)
                    .height(130.dp)
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

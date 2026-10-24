package com.me.ubox.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.me.ubox.TvMode
import com.me.ubox.data.JsonUtil
import com.me.ubox.data.PlayUrl
import com.me.ubox.data.Site
import com.me.ubox.ui.AppSnackbar
import com.me.ubox.ui.components.LoadingBox
import com.me.ubox.ui.components.NetworkImage
import com.me.ubox.ui.components.TvFocusable
import com.me.ubox.ui.dialogs.SinglePickSheet

/**
 * 详情页 (对照 Flutter 版 detail_page.dart):
 * 海报 + 元信息 + 选集 (可聚焦, 首集自动获焦) + 收藏 + 换源.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    siteKey: String,
    vodId: String,
    vodName: String,
    onBack: () -> Unit,
    onOpenPlayer: (String, String, String, String, String, List<String>, List<String>, Int) -> Unit,
    onReplaceWithDetail: (String, String, String) -> Unit,
    vm: DetailViewModel = viewModel(factory = viewModelFactory {
        initializer { DetailViewModel(siteKey, vodId, vodName) }
    }),
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    val site by vm.site.collectAsStateWithLifecycle()
    val json by vm.json.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val fav by vm.fav.collectAsStateWithLifecycle()
    val switching by vm.switching.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var groupIdx by remember { mutableStateOf(0) }
    var epIdx by remember { mutableStateOf(-1) }
    var showSwitchMenu by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        if (message != null) {
            AppSnackbar.show(message ?: "")
            vm.consumeMessage()
        }
    }

    fun openEp() {
        if (groupIdx < 0 || groupIdx >= groups.size) return
        val group = groups[groupIdx]
        val idx = if (epIdx < 0 || epIdx >= group.urls.size) 0 else epIdx
        onOpenPlayer(
            siteKey,
            vodId,
            JsonUtil.optStringOrNull(json, "vod_name") ?: vodName,
            JsonUtil.optStringOrNull(json, "vod_pic") ?: "",
            group.flag,
            group.eps,
            group.urls,
            idx,
        )
    }

    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vodName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = vm::toggleFav) {
                        Icon(
                            imageVector = if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "收藏",
                        )
                    }
                    IconButton(onClick = { if (!switching) showSwitchMenu = true }) {
                        if (switching) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.width(18.dp).height(18.dp),
                            )
                        } else {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = "换源")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                loading -> LoadingBox()
                error -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("详情加载失败")
                        TextButton(onClick = vm::load) { Text("重试") }
                    }
                }
                else -> DetailBody(
                    tv = tv,
                    vod = json,
                    siteName = site?.name ?: "",
                    groups = groups,
                    onEpClick = { gi, i ->
                        groupIdx = gi
                        epIdx = i
                        openEp()
                    },
                )
            }
        }
    }

    if (showSwitchMenu) {
        val candidates = vm.switchCandidates()
        SinglePickSheet(
            title = "搜索 \"${JsonUtil.optStringOrNull(json, "vod_name") ?: vodName}\" 并换源",
            items = candidates,
            labelOf = { it.name },
            subtitleOf = { it.key },
            current = null,
            onPick = { target ->
                showSwitchMenu = false
                vm.switchSource(target) { k, id, name ->
                    onReplaceWithDetail(k, id, name)
                }
            },
            onDismiss = { showSwitchMenu = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(
    tv: Boolean,
    vod: org.json.JSONObject?,
    siteName: String,
    groups: List<PlayUrl>,
    onEpClick: (Int, Int) -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(all = if (tv) 24.dp else 12.dp),
    ) {
        Row {
            val pic = JsonUtil.optStringOrNull(vod, "vod_pic") ?: ""
            if (pic.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .width(if (tv) 180.dp else 110.dp)
                        .height(if (tv) 240.dp else 150.dp)
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    NetworkImage(
                        url = pic,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = JsonUtil.optStringOrNull(vod, "vod_name") ?: "",
                    style = if (tv) {
                        MaterialTheme.typography.headlineSmall
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                )
                Spacer(Modifier.height(6.dp))
                MetaText(tv, JsonUtil.optStringOrNull(vod, "vod_year") ?: "")
                MetaText(tv, JsonUtil.optStringOrNull(vod, "vod_area") ?: "")
                Text(
                    text = siteName,
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = (if (tv) 14 else 12).sp),
                )
            }
        }
        Spacer(Modifier.height(if (tv) 16.dp else 12.dp))
        MetaText(tv, JsonUtil.optStringOrNull(vod, "vod_remarks") ?: "", gray = true)
        val actor = JsonUtil.optStringOrNull(vod, "vod_actor") ?: ""
        if (actor.isNotEmpty()) {
            Text(
                text = actor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = (if (tv) 17 else 14).sp),
            )
        }
        val content = JsonUtil.optStringOrNull(vod, "vod_content") ?: ""
        if (content.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = content,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = (if (tv) 17 else 14).sp),
            )
        }
        Spacer(Modifier.height(16.dp))
        for ((gi, group) in groups.withIndex()) {
            Text(
                text = group.flag,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = (if (tv) 16 else 14).sp,
                ),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(if (tv) 10.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (tv) 10.dp else 8.dp),
                modifier = Modifier.padding(vertical = 6.dp),
            ) {
                for (i in group.urls.indices) {
                    // TV: 选集按钮可聚焦, 首集自动获焦 (进入详情页即可按 OK 播放).
                    TvFocusable(
                        autofocus = tv && gi == 0 && i == 0,
                        radius = 6.dp,
                        onTap = { onEpClick(gi, i) },
                    ) {
                        Box(
                            modifier = Modifier
                                .background(Color(0x6C3D3D3D), RoundedCornerShape(20.dp))
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(20.dp),
                                )
                                .padding(
                                    horizontal = if (tv) 18.dp else 12.dp,
                                    vertical = if (tv) 12.dp else 8.dp,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = group.eps[i].ifEmpty { "第${i + 1}集" },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = (if (tv) 17 else 14).sp,
                                ),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(if (tv) 16.dp else 12.dp))
        }
    }
}

@Composable
private fun MetaText(tv: Boolean, text: String, gray: Boolean = false) {
    if (text.isEmpty()) return
    Text(
        text = text,
        color = if (gray) Color.Gray else Color.Unspecified,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = (if (tv) 17 else 14).sp),
    )
}

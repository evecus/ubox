package com.novabox.ui.favorites

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novabox.TvMode
import com.novabox.data.Favorites
import com.novabox.data.FavoriteItem
import com.novabox.ui.AppSnackbar
import com.novabox.ui.components.NetworkImage
import com.novabox.ui.components.TvFocusable

/**
 * 收藏页 (对照 Flutter 版 favorites_page.dart):
 * 海报网格; 长按移除; 源未加载时提示.
 */
@Composable
fun FavoritesScreen(
    onOpenDetail: (String, String, String) -> Unit,
) {
    val tv by TvMode.isTV.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf<List<FavoriteItem>?>(null) }

    fun reload() {
        items = Favorites.list()
    }

    LaunchedEffect(Unit) { reload() }

    val list = items
    if (list == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(strokeWidth = 2.dp)
        }
        return
    }
    if (list.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无收藏", color = Color.Gray)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (tv) 200.dp else 130.dp),
        contentPadding = PaddingValues(if (tv) 16.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (tv) 16.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (tv) 16.dp else 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(list) { _, item ->
            TvFocusable(
                onTap = {
                    val site = Favorites.siteOf(item.siteKey)
                    if (site == null) {
                        AppSnackbar.show("${item.siteName}未加载, 请先应用配置")
                    } else {
                        onOpenDetail(item.siteKey, item.vodId, item.name)
                    }
                },
                onLongPress = {
                    Favorites.remove(item.siteKey, item.vodId)
                    reload()
                },
                radius = 8.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (tv) 260.dp else 190.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        if (item.cover.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("无封面", color = Color.Gray)
                            }
                        } else {
                            NetworkImage(
                                url = item.cover,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = (if (tv) 16 else 12).sp),
                    )
                    Text(
                        text = item.siteName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = (if (tv) 14 else 10).sp),
                    )
                }
            }
        }
    }
}

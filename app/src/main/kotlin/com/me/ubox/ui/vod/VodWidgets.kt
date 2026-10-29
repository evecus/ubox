package com.me.ubox.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.me.ubox.data.JsonUtil
import com.me.ubox.ui.components.NetworkImage
import com.me.ubox.ui.player.AccentBlue
import com.me.ubox.ui.player.PlayerOptions
import com.me.ubox.ui.player.PlayerSeekBar
import com.me.ubox.ui.player.fmtTime

// ---------------------------------------------------------------- 信息 / 源 / 剧集

@Composable
internal fun MetaLine(label: String, value: String) {
    if (value.isEmpty()) return
    Text("$label: $value", color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** 源 (线路) 标签: 选中 = 蓝字 + 蓝色下划线 (对照 NovaBox item_series_flag). */
@Composable
internal fun FlagChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = name,
            color = if (selected) AccentBlue else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else null,
            fontSize = 15.sp,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .height(3.dp)
                .width(28.dp)
                .background(
                    if (selected) AccentBlue else Color.Transparent,
                    RoundedCornerShape(2.dp),
                ),
        )
    }
}

/** 剧集网格 (非懒加载, 可放进竖向滚动容器). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EpisodeFlow(
    eps: List<String>,
    current: Int,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        eps.forEachIndexed { i, name ->
            val selected = i == current
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(44.dp)
                    .background(
                        color = if (selected) {
                            AccentBlue
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable(onClick = { onSelect(i) })
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.ifEmpty { "第${i + 1}集" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * 影视信息卡 (对照 NovaBox 详情页 topLayout):
 * 封面 + 片名 + 站点/年份/地区/类型 + 备注 + 收藏/换源 + 演员/导演/简介.
 * [compact] = 平板右栏 (窄): 封面放上方, 简介只留 3 行.
 */
@Composable
internal fun VodInfoCard(
    vm: VodViewModel,
    vod: org.json.JSONObject?,
    fav: Boolean,
    switching: Boolean,
    compact: Boolean,
    onToggleFav: () -> Unit,
    onSwitchMenu: () -> Unit,
) {
    val pic = vm.cover
    Column(modifier = Modifier.fillMaxWidth()) {
        if (compact) {
            if (pic.isNotEmpty()) {
                NetworkImage(
                    url = pic,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = vm.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Row {
                if (pic.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .width(100.dp)
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
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
                        text = vm.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    MetaLine("备注", JsonUtil.optStringOrNull(vod, "vod_remarks").orEmpty())
                    MetaLine("年份", JsonUtil.optStringOrNull(vod, "vod_year").orEmpty())
                    MetaLine("地区", JsonUtil.optStringOrNull(vod, "vod_area").orEmpty())
                    MetaLine("类型", JsonUtil.optStringOrNull(vod, "vod_type").orEmpty())
                    MetaLine("来源", vm.siteName)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ActionRow(
            fav = fav,
            switching = switching,
            onToggleFav = onToggleFav,
            onSwitchMenu = onSwitchMenu,
        )
        val actor = JsonUtil.optStringOrNull(vod, "vod_actor").orEmpty()
        if (actor.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("演员: $actor", color = Color.White, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        val director = JsonUtil.optStringOrNull(vod, "vod_director").orEmpty()
        if (director.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("导演: $director", color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val des = JsonUtil.optStringOrNull(vod, "vod_content").orEmpty()
        if (des.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "简介: $des",
                fontSize = 13.sp,
                maxLines = if (compact) 4 else 6,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
            )
        }
    }
}

/** 收藏 / 换源 两个操作按钮. */
@Composable
internal fun ActionRow(
    fav: Boolean,
    switching: Boolean,
    onToggleFav: () -> Unit,
    onSwitchMenu: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OpButton(
            modifier = Modifier.weight(1f),
            label = if (fav) "已收藏" else "收藏",
            icon = if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            selected = fav,
            onClick = onToggleFav,
        )
        OpButton(
            modifier = Modifier.weight(1f),
            label = if (switching) "换源中…" else "换源",
            icon = Icons.Filled.SwapHoriz,
            selected = false,
            onClick = { if (!switching) onSwitchMenu() },
        )
    }
}

@Composable
private fun OpButton(
    modifier: Modifier,
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) AccentBlue.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) AccentBlue else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            maxLines = 1,
            color = if (selected) AccentBlue else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------------------------------------------------------------- 小屏控制层 (叠在视频上)

/**
 * 小屏预览窗的控制浮层 (对照 NovaBox activity_detail.xml 的 miniControlsOverlay):
 *   顶行 = 返回 (左) + 锁屏 (右); 中央 = 播放/暂停; 底行 = 当前时间 + 进度 + 总时长 + 全屏.
 * 由外层按 overlay 显隐, 3 秒无操作自动收.
 */
@Composable
internal fun BoxScope.MiniControls(
    tablet: Boolean,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    locked: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPreview: (Long?) -> Unit,
    onPoke: () -> Unit,
    onFullscreen: () -> Unit,
    onLock: () -> Unit,
) {
    val barH = if (tablet) 44.dp else 40.dp
    val centerSize = if (tablet) 52.dp else 48.dp
    // 顶行
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .height(barH)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "返回", tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onLock) {
            Icon(
                imageVector = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = if (locked) "解锁" else "锁定",
                tint = Color.White,
            )
        }
    }
    // 中央播放/暂停
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .size(centerSize)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onPlayPause),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (playing) "暂停" else "播放",
            tint = Color.White,
            modifier = Modifier.size(centerSize * 0.6f),
        )
    }
    // 底行
    Row(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(barH)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(fmtTime(positionMs), color = Color.White, fontSize = 12.sp)
        PlayerSeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            onSeek = onSeek,
            onInteract = onPoke,
            onPreview = onPreview,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(fmtTime(durationMs), color = Color.White, fontSize = 12.sp)
        IconButton(onClick = onFullscreen) {
            Icon(Icons.Filled.Fullscreen, contentDescription = "全屏", tint = Color.White)
        }
    }
}

// ---------------------------------------------------------------- 全屏控制层 (触摸端)

/**
 * 全屏控制层 (对照 NovaBox player_vod_control_view.xml 的 fs_function_row):
 *   顶行 = 返回 (退出全屏) + 片名·集名;
 *   底行 = 时间 + 进度条 + 总时长 + 退出全屏;
 *   功能行 = 播放/暂停 · 下一集 · 弹幕 · 倍速 · 画面 · 选集 (等分, 与 NovaBox 一致).
 */
@Composable
internal fun BoxScope.FullscreenControls(
    title: String,
    playing: Boolean,
    positionMs: Long,
    playPosMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    hasNext: Boolean,
    speed: Float,
    scaleType: Int,
    danmuOpen: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onPreview: (Long?) -> Unit,
    onPoke: () -> Unit,
    onToggleDanmu: () -> Unit,
    onSpeed: () -> Unit,
    onScale: () -> Unit,
    onEpisodes: () -> Unit,
    onExitFullscreen: () -> Unit,
) {
    // 顶行
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "返回", tint = Color.White)
        }
        Text(
            text = title,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 16.sp,
            fontWeight = FontWeight.W500,
            modifier = Modifier.weight(1f),
        )
    }
    // 底栏
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmtTime(positionMs), color = Color.White, fontSize = 12.sp)
            PlayerSeekBar(
                positionMs = playPosMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                onSeek = onSeek,
                onInteract = onPoke,
                onPreview = onPreview,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Text(fmtTime(durationMs), color = Color.White, fontSize = 12.sp)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FsIconButton(
                icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                desc = if (playing) "暂停" else "播放",
                onClick = onPlayPause,
            )
            if (hasNext) {
                FsIconButton(icon = Icons.Filled.SkipNext, desc = "下一集", onClick = onNext)
            }
            FsTextButton(text = if (danmuOpen) "弹幕 开" else "弹幕 关", selected = danmuOpen, onClick = onToggleDanmu)
            FsTextButton(
                text = "倍速 ${PlayerOptions.speedLabel(speed)}",
                selected = speed != 1.0f,
                onClick = onSpeed,
            )
            FsTextButton(
                text = "画面 ${PlayerOptions.SCALE_NAMES[scaleType]}",
                selected = scaleType != 0,
                onClick = onScale,
            )
            FsTextButton(text = "选集", selected = false, onClick = onEpisodes)
            FsIconButton(icon = Icons.Filled.FullscreenExit, desc = "退出全屏", onClick = onExitFullscreen)
        }
    }
}

@Composable
private fun RowScope.FsIconButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.weight(1f).height(44.dp)) {
        Icon(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun RowScope.FsTextButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

package io.github.bilidownload.ui.screens

import android.os.Bundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bilidownload.*
import io.github.bilidownload.R
import io.github.bilidownload.ui.components.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    listState: LazyListState,
    input: String,
    onInputChange: (String) -> Unit,
    loading: Boolean,
    media: List<MediaItem>,
    selected: Set<String>,
    mode: Int,
    onModeChange: (Int) -> Unit,
    mp3: Boolean,
    onMp3Change: (Boolean) -> Unit,
    bitrate: Int,
    onBitrateChange: (Int) -> Unit,
    variants: List<Variant>,
    sizeLimit: Variant?,
    onQualityCapSelect: (Int) -> Unit,
    sizes: Map<String, Variant?>,
    sizeLoading: Boolean,
    gifIds: Set<String>,
    onToggleGif: (String, Boolean) -> Unit,
    audioCache: Map<String, List<AudioTrack>>,
    trackIds: Bundle,
    onSelectTrack: (String, String?) -> Unit,
    audioMode: (MediaItem) -> Boolean,
    videoMode: (MediaItem) -> Boolean,
    outputFor: (MediaItem) -> AudioOutput?,
    extFor: (MediaItem) -> String,
    namingTemplate: String,
    customNames: Map<String, String>,
    canDownload: Boolean,
    hasMorePages: Boolean,
    onLoadMore: () -> Unit,
    onParse: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onToggleItem: (String, Boolean) -> Unit,
    onRenameItem: (MediaItem, String) -> Unit,
    onDownloadSelected: () -> Unit,
) {
    var qualityOpen by remember { mutableStateOf(false) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                text = "链接解析",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            )
        }

        // Hero Card
        item {
            SaltCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SaltPlatformChip(name = "Bilibili", active = true) {}
                        SaltPlatformChip(name = "YouTube / Music", active = true) {}
                        SaltPlatformChip(name = "X", active = true) {}
                    }

                    SaltTextField(
                        value = input,
                        onValueChange = onInputChange,
                        placeholder = "粘贴 B 站、YouTube 或 X 链接，多条可换行",
                        minLines = 3,
                        maxLines = 6,
                        showClear = true,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SaltButton(
                            enabled = !loading,
                            onClick = onParse,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("解析中…")
                            } else {
                                Text("解析链接")
                            }
                        }

                        SaltTonalButton(
                            onClick = onPaste,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_paste),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("粘贴")
                        }
                    }
                }
            }
        }

        if (media.isEmpty()) {
            item {
                SaltCard(modifier = Modifier.animateItem()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("使用步骤", style = MaterialTheme.typography.titleMedium)
                        GuideStep("01", "多平台导入", "粘贴链接、短链、BV/av 号或通过系统分享直接导入")
                        SaltInsetDivider(Modifier.padding(vertical = 2.dp))
                        GuideStep("02", "音画与格式", "自由勾选条目，支持视频画质、原音/MP3 导出与动图格式切换")
                        SaltInsetDivider(Modifier.padding(vertical = 2.dp))
                        GuideStep("03", "高速下载与分享", "后台下载混流并自动写入音乐标签与封面，完成后一键分享")
                    }
                }
            }
        }

        if (media.isNotEmpty()) {
            item {
                SaltCard(modifier = Modifier.animateItem()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("解析结果", style = MaterialTheme.typography.titleMedium)
                                SaltStatusBadge(text = "找到 ${media.size} 项", tone = BadgeTone.Accent)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                SaltTextButton(onClick = onSelectAll) { Text("全选") }
                                SaltTextButton(onClick = onSelectNone) { Text("取消") }
                            }
                        }

                        SaltInsetDivider(Modifier.padding(horizontal = 0.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (media.any { it.x == null || it.x.audio }) {
                                ChoiceMenu("下载内容", listOf("按链接类型", "视频", "音频"), mode, onModeChange)
                            }
                            if (media.any(audioMode)) {
                                ChoiceMenu("音频格式", listOf("保留原音", "MP3"), if (mp3) 1 else 0) { onMp3Change(it == 1) }
                            }
                            if (mp3 && media.any(audioMode)) {
                                val rates = listOf(320, 192, 128)
                                ChoiceMenu("MP3 码率", rates.map { "$it kb/s" }, rates.indexOf(bitrate).coerceAtLeast(0)) {
                                    onBitrateChange(rates[it])
                                }
                            }
                            if (variants.isNotEmpty() && media.any(videoMode)) {
                                val qualityArrowRotation by animateFloatAsState(
                                    targetValue = if (qualityOpen) 270f else 90f,
                                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 480f),
                                    label = "qualityArrow",
                                )
                                Box {
                                    SaltOutlinedButton(
                                        border = BorderStroke(1.dp, saltColors().subtleBorder),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(containerColor = saltColors().subCard),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        onClick = { qualityOpen = true },
                                    ) {
                                        Text(
                                            text = "画质上限：${sizeLimit?.label.orEmpty()}",
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            painter = painterResource(R.drawable.ic_chevron_right),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(14.dp)
                                                .rotate(qualityArrowRotation),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    DropdownMenu(expanded = qualityOpen, onDismissRequest = { qualityOpen = false }) {
                                        variants.forEach { v ->
                                            DropdownMenuItem(
                                                text = { Text(v.label + "  ·  " + MediaSize.format(v.estimatedBytes)) },
                                                onClick = {
                                                    onQualityCapSelect(v.quality)
                                                    qualityOpen = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        val chosen = media.filter { it.id in selected }
                        val totals = chosen.map {
                            if (audioMode(it)) outputFor(it)?.estimatedBytes else sizes[it.id]?.estimatedBytes ?: it.x?.bytes
                        }
                        val estimateText = if (sizeLoading) {
                            "正在估算选中内容的大小…"
                        } else {
                            "选中 ${selected.size} 项 · " +
                                MediaSize.format(totals.filterNotNull().takeIf { it.isNotEmpty() }?.sum()) +
                                if (totals.any { it == null }) "（部分大小未知）" else ""
                        }
                        Text(
                            text = estimateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        SaltButton(
                            enabled = canDownload,
                            onClick = onDownloadSelected,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(vertical = 12.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_download),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("下载选中内容 (${selected.size})", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            items(media.sortedBy { audioMode(it) }, key = { it.id }) { item ->
                val isSelected = item.id in selected
                val enabled = item.x?.available != false
                val ext = extFor(item)
                val renderedName = FileNaming.render(item, namingTemplate, customNames[item.id], ext)
                val platformLabel = when {
                    item.x != null -> "X"
                    item.music -> "YT Music"
                    item.youtubeId != null -> "YouTube"
                    else -> "Bilibili"
                }
                val kindLabel = when (item.x?.kind) {
                    "markdown" -> "正文"
                    "image" -> "图片"
                    "gif" -> "动图"
                    "video" -> "视频"
                    else -> when {
                        item.music -> "音乐"
                        item.youtubeId != null -> "视频"
                        item.kind == "bangumi" -> "番剧"
                        item.kind == "course" -> "课程"
                        else -> "投稿"
                    }
                }

                SaltCard(
                    modifier = Modifier.animateItem(),
                    highlighted = isSelected,
                    onClick = if (enabled) ({ onToggleItem(item.id, !isSelected) }) else null,
                ) {
                    Column {
                        if (item.cover.isNotBlank()) {
                            Box(modifier = Modifier.padding(10.dp, 10.dp, 10.dp, 0.dp)) {
                                Cover(
                                    url = item.cover,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                                    cornerRadius = 12.dp,
                                )
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    SaltStatusBadge(platformLabel, BadgeTone.Accent)
                                    SaltStatusBadge(kindLabel, BadgeTone.Neutral)
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                SaltStatusBadge(platformLabel, BadgeTone.Accent)
                                SaltStatusBadge(kindLabel, BadgeTone.Neutral)
                            }
                        }

                        Row(
                            modifier = Modifier.padding(12.dp, 10.dp, 12.dp, 12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SaltCheckbox(
                                checked = isSelected,
                                enabled = enabled,
                                onCheckedChange = { onToggleItem(item.id, it) },
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                if (item.partTitle.isNotBlank() && item.partTitle != item.title) {
                                    Text(
                                        text = item.partTitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (item.x?.available == false) {
                                    SaltStatusBadge("没有可下载的 MP4 格式", BadgeTone.Warning)
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = item.x?.kind == "gif" && isSelected,
                                    enter = expandVertically(spring(dampingRatio = 0.82f, stiffness = 440f)) + fadeIn(tween(180)),
                                    exit = shrinkVertically(tween(160)) + fadeOut(tween(130)),
                                ) {
                                    ChoiceMenu("动图格式", listOf("MP4", "GIF"), if (item.id in gifIds) 1 else 0) { option ->
                                        onToggleGif(item.id, option == 1)
                                    }
                                }
                                val doc = item.x?.document
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = doc != null && isSelected,
                                    enter = expandVertically(spring(dampingRatio = 0.82f, stiffness = 440f)) + fadeIn(tween(180)),
                                    exit = shrinkVertically(tween(160)) + fadeOut(tween(130)),
                                ) {
                                    if (doc != null) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = saltColors().subCard,
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Text(
                                                    text = doc.text,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 4,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                if (doc.truncated) {
                                                    Text(
                                                        text = "正文可能不完整",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                val isAudioSelected = isSelected && audioMode(item)
                                val isVideoSelected = isSelected && !audioMode(item) && videoMode(item)
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = isAudioSelected,
                                    enter = expandVertically(spring(dampingRatio = 0.82f, stiffness = 440f)) + fadeIn(tween(180)),
                                    exit = shrinkVertically(tween(160)) + fadeOut(tween(130)),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        val tracks = audioCache[item.id].orEmpty()
                                        if (tracks.isNotEmpty()) {
                                            ChoiceMenu(
                                                "音质",
                                                listOf("自动最高") + tracks.map { it.label },
                                                tracks.indexOfFirst { it.id == trackIds.getString(item.id) } + 1,
                                            ) { index ->
                                                onSelectTrack(item.id, if (index == 0) null else tracks[index - 1].id)
                                            }
                                        }
                                        outputFor(item)?.let { SaltStatusBadge(it.summary, BadgeTone.Info) }
                                    }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = isVideoSelected,
                                    enter = expandVertically(spring(dampingRatio = 0.82f, stiffness = 440f)) + fadeIn(tween(180)),
                                    exit = shrinkVertically(tween(160)) + fadeOut(tween(130)),
                                ) {
                                    val sizeLabel = if (sizeLoading) {
                                        "正在估算…"
                                    } else {
                                        sizes[item.id]?.let { "${it.label} · ${MediaSize.format(it.estimatedBytes)}" }
                                            ?: "没有兼容画质，可选择音频"
                                    }
                                    SaltStatusBadge(sizeLabel, BadgeTone.Info)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = renderedName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    SaltTextButton(
                                        onClick = { onRenameItem(item, renderedName.substringBeforeLast('.')) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_edit),
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("改名", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (hasMorePages) {
                item {
                    SaltTonalButton(
                        enabled = !loading,
                        onClick = onLoadMore,
                        modifier = Modifier.fillMaxWidth().animateItem(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (loading) "加载中…" else "加载更多视频")
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideStep(step: String, title: String, desc: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SaltStatusBadge(step, BadgeTone.Accent)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

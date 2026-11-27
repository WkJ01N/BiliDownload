package io.github.bilidownload.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bilidownload.*
import io.github.bilidownload.R
import io.github.bilidownload.ui.components.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QueueScreen(
    completedOnly: Boolean,
    listState: LazyListState,
    entries: List<DownloadEntry>,
    listDensity: String,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    sessionState: SessionState,
    componentReady: Boolean,
    onEnterSelection: (String?) -> Unit,
    onToggleSelect: (String) -> Unit,
    onExitSelection: () -> Unit,
    onOpenFile: (DownloadEntry) -> Unit,
    onShare: (DownloadEntry) -> Unit,
    onReparseTrackChanged: (DownloadEntry) -> Unit,
    onManageSession: (String?) -> Unit,
    onLogin: (String) -> Unit,
    onRetryTask: (DownloadEntry) -> Unit,
    onPause: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onGoHome: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val visible = remember(entries, completedOnly) {
        entries.filter { if (completedOnly) it.state == "Completed" else it.state != "Completed" }
    }
    val activeCount = remember(entries) {
        entries.count { it.state in listOf("Queued", "Downloading", "Merging") }
    }

    if (completedOnly && selectionMode) {
        BackHandler { onExitSelection() }
    }

    val itemGap = when (listDensity) {
        "Compact" -> 8.dp
        "Comfortable" -> 12.dp
        else -> 10.dp
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(itemGap),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (completedOnly) "已完成" else "下载队列",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (completedOnly) {
                        SaltStatusBadge("共 ${visible.size} 项", if (visible.isNotEmpty()) BadgeTone.Success else BadgeTone.Neutral)
                        if (visible.isNotEmpty()) {
                            SaltTonalButton(
                                onClick = {
                                    if (selectionMode) onExitSelection() else onEnterSelection(null)
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_checklist),
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (selectionMode) "完成" else "多选",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    } else {
                        SaltStatusBadge("进行中 $activeCount", if (activeCount > 0) BadgeTone.Info else BadgeTone.Neutral)
                        SaltStatusBadge("队列 ${visible.size}", BadgeTone.Neutral)
                    }
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                SaltEmptyState(
                    iconRes = if (completedOnly) R.drawable.ic_complete else R.drawable.ic_download,
                    title = if (completedOnly) "暂无已完成内容" else "暂无下载任务",
                    subtitle = if (completedOnly) "下载并处理完成的内容会显示在这里" else "未完成的下载任务会显示在这里，完成后自动归入「已完成」",
                    actionLabel = "去解析链接",
                    onAction = onGoHome,
                )
            }
        }

        items(visible, key = { it.id }) { entry ->
            val (stateLabel, tone) = when (entry.state) {
                "Downloading" -> "下载中" to BadgeTone.Info
                "Merging" -> "合并中" to BadgeTone.Info
                "Queued" -> "等待中" to BadgeTone.Neutral
                "Paused" -> "已暂停" to BadgeTone.Warning
                "Completed" -> "已完成" to BadgeTone.Success
                "Failed" -> "失败" to BadgeTone.Error
                "Canceled" -> "已取消" to BadgeTone.Neutral
                else -> entry.state to BadgeTone.Neutral
            }
            val platformLabel = when {
                entry.item.x != null -> "X"
                entry.item.music -> "YT Music"
                entry.item.youtubeId != null -> "YouTube"
                else -> "Bilibili"
            }
            val animatedProgress by animateFloatAsState(
                targetValue = entry.progress.coerceIn(0f, 1f),
                animationSpec = tween(220),
                label = "progress_${entry.id}",
            )
            val pct = (animatedProgress * 100).toInt().coerceIn(0, 100)
            val fileName = entry.finalFileName ?: entry.outputFileName
            val statusMessage = if (entry.state == "Failed" && entry.message.contains("扩展")) {
                when (entry.failureKind) {
                    "YoutubeComponent", "YoutubeConnect", "XComponent" ->
                        if (componentReady) "组件已就绪，可重试。" else "需要下载 YouTube / X 解析组件。"
                    "GifComponent", "AudioComponent", "AudioConnect" -> "处理组件已内置，可重试。"
                    else -> entry.message
                }
            } else {
                entry.message
            }
            val isSelected = completedOnly && selectionMode && entry.id in selectedIds
            val cardModifier = if (completedOnly) {
                Modifier.saltCombinedPressBounce(
                    onClick = {
                        if (selectionMode) onToggleSelect(entry.id)
                    },
                    onLongClick = {
                        if (!selectionMode) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onEnterSelection(entry.id)
                        }
                    },
                )
            } else {
                Modifier
            }

            SaltCard(
                modifier = cardModifier.animateItem(),
                highlighted = isSelected,
            ) {
                when (listDensity) {
                    "Compact" -> CompactQueueCardContent(
                        entry = entry,
                        completedOnly = completedOnly,
                        selectionMode = selectionMode,
                        isSelected = isSelected,
                        platformLabel = platformLabel,
                        stateLabel = stateLabel,
                        tone = tone,
                        animatedProgress = animatedProgress,
                        pct = pct,
                        fileName = fileName,
                        statusMessage = statusMessage,
                        sessionState = sessionState,
                        onToggleSelect = { onToggleSelect(entry.id) },
                        onOpenFile = { onOpenFile(entry) },
                        onShare = { onShare(entry) },
                        onReparseTrackChanged = { onReparseTrackChanged(entry) },
                        onManageSession = { onManageSession(entry.failureKind) },
                        onLogin = { onLogin(entry.id) },
                        onRetryTask = { onRetryTask(entry) },
                        onPause = { onPause(entry.id) },
                        onRemove = { onRemove(entry.id) },
                    )

                    "Comfortable" -> ComfortableQueueCardContent(
                        entry = entry,
                        completedOnly = completedOnly,
                        selectionMode = selectionMode,
                        isSelected = isSelected,
                        platformLabel = platformLabel,
                        stateLabel = stateLabel,
                        tone = tone,
                        animatedProgress = animatedProgress,
                        pct = pct,
                        fileName = fileName,
                        statusMessage = statusMessage,
                        sessionState = sessionState,
                        onToggleSelect = { onToggleSelect(entry.id) },
                        onOpenFile = { onOpenFile(entry) },
                        onShare = { onShare(entry) },
                        onReparseTrackChanged = { onReparseTrackChanged(entry) },
                        onManageSession = { onManageSession(entry.failureKind) },
                        onLogin = { onLogin(entry.id) },
                        onRetryTask = { onRetryTask(entry) },
                        onPause = { onPause(entry.id) },
                        onCancel = { onCancel(entry.id) },
                        onRemove = { onRemove(entry.id) },
                    )

                    else -> StandardQueueCardContent(
                        entry = entry,
                        completedOnly = completedOnly,
                        selectionMode = selectionMode,
                        isSelected = isSelected,
                        platformLabel = platformLabel,
                        stateLabel = stateLabel,
                        tone = tone,
                        animatedProgress = animatedProgress,
                        pct = pct,
                        fileName = fileName,
                        statusMessage = statusMessage,
                        sessionState = sessionState,
                        onToggleSelect = { onToggleSelect(entry.id) },
                        onOpenFile = { onOpenFile(entry) },
                        onShare = { onShare(entry) },
                        onReparseTrackChanged = { onReparseTrackChanged(entry) },
                        onManageSession = { onManageSession(entry.failureKind) },
                        onLogin = { onLogin(entry.id) },
                        onRetryTask = { onRetryTask(entry) },
                        onPause = { onPause(entry.id) },
                        onCancel = { onCancel(entry.id) },
                        onRemove = { onRemove(entry.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactQueueCardContent(
    entry: DownloadEntry,
    completedOnly: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    platformLabel: String,
    stateLabel: String,
    tone: BadgeTone,
    animatedProgress: Float,
    pct: Int,
    fileName: String?,
    statusMessage: String,
    sessionState: SessionState,
    onToggleSelect: () -> Unit,
    onOpenFile: () -> Unit,
    onShare: () -> Unit,
    onReparseTrackChanged: () -> Unit,
    onManageSession: () -> Unit,
    onLogin: () -> Unit,
    onRetryTask: () -> Unit,
    onPause: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(
                visible = completedOnly && selectionMode,
                enter = expandHorizontally(spring(dampingRatio = 0.82f, stiffness = 460f)) + fadeIn(tween(150)),
                exit = shrinkHorizontally(tween(140)) + fadeOut(tween(110)),
            ) {
                SaltCheckbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
            }
            Cover(
                url = entry.item.cover,
                modifier = Modifier.width(56.dp).height(38.dp),
                cornerRadius = 8.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.item.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = platformLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                        color = saltColors().accentSoftFg,
                    )
                    Text(
                        text = if (entry.state == "Completed") (fileName ?: "已完成") else "$stateLabel $pct% · $statusMessage",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.state == "Failed") saltColors().error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            AnimatedVisibility(
                visible = !(completedOnly && selectionMode),
                enter = expandHorizontally(spring(dampingRatio = 0.84f, stiffness = 460f)) + fadeIn(tween(150)),
                exit = shrinkHorizontally(tween(140)) + fadeOut(tween(110)),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (entry.state == "Completed") {
                        if (entry.item.x != null || entry.audioOutput != null) {
                            SaltIconButton(onClick = onOpenFile, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_play),
                                    contentDescription = if (entry.audioOutput != null) "播放音频" else "打开文件",
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                        SaltIconButton(onClick = onShare, modifier = Modifier.size(34.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_share),
                                contentDescription = "分享",
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        if (entry.state == "Failed" && entry.failureKind == "TrackChanged") {
                            SaltIconButton(onClick = onReparseTrackChanged, modifier = Modifier.size(34.dp)) {
                                Icon(painterResource(R.drawable.ic_refresh), contentDescription = "重新解析", modifier = Modifier.size(17.dp))
                            }
                        } else if (entry.state == "Failed" && entry.failureKind in listOf("XSession", "YoutubeSession")) {
                            SaltIconButton(onClick = onManageSession, modifier = Modifier.size(34.dp)) {
                                Icon(painterResource(R.drawable.ic_account), contentDescription = "登录", modifier = Modifier.size(17.dp))
                            }
                        } else if (entry.state == "Failed" && AuthRecovery.needsLogin(entry.failureKind, sessionState)) {
                            SaltIconButton(onClick = onLogin, modifier = Modifier.size(34.dp)) {
                                Icon(painterResource(R.drawable.ic_account), contentDescription = "登录 B 站", modifier = Modifier.size(17.dp))
                            }
                        }
                        if (entry.state in listOf("Queued", "Downloading", "Merging")) {
                            SaltIconButton(onClick = onPause, modifier = Modifier.size(34.dp)) {
                                Icon(painterResource(R.drawable.ic_pause), contentDescription = "暂停", modifier = Modifier.size(17.dp))
                            }
                        } else if (entry.state in listOf("Paused", "Failed")) {
                            SaltIconButton(onClick = onRetryTask, modifier = Modifier.size(34.dp)) {
                                Icon(painterResource(R.drawable.ic_refresh), contentDescription = "重试", modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                    SaltIconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = "移除记录",
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = entry.state != "Completed",
            enter = expandVertically(spring(dampingRatio = 0.84f, stiffness = 440f)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(120)),
        ) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                trackColor = saltColors().subCard,
            )
        }
    }
}

@Composable
private fun StandardQueueCardContent(
    entry: DownloadEntry,
    completedOnly: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    platformLabel: String,
    stateLabel: String,
    tone: BadgeTone,
    animatedProgress: Float,
    pct: Int,
    fileName: String?,
    statusMessage: String,
    sessionState: SessionState,
    onToggleSelect: () -> Unit,
    onOpenFile: () -> Unit,
    onShare: () -> Unit,
    onReparseTrackChanged: () -> Unit,
    onManageSession: () -> Unit,
    onLogin: () -> Unit,
    onRetryTask: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = completedOnly && selectionMode,
                enter = expandHorizontally(spring(dampingRatio = 0.82f, stiffness = 460f)) + fadeIn(tween(150)),
                exit = shrinkHorizontally(tween(140)) + fadeOut(tween(110)),
            ) {
                SaltCheckbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
            }
            Cover(
                url = entry.item.cover,
                modifier = Modifier.width(72.dp).height(46.dp),
                cornerRadius = 9.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SaltStatusBadge(platformLabel, BadgeTone.Accent)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SaltStatusBadge(stateLabel, tone)
                        if (entry.state != "Completed") {
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    text = entry.item.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = if (completedOnly) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = fileName?.takeIf { it.isNotBlank() }
                    ?: entry.item.partTitle.takeIf { it.isNotBlank() && it != entry.item.title }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = entry.state != "Completed",
            enter = expandVertically(spring(dampingRatio = 0.84f, stiffness = 440f)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(120)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    trackColor = saltColors().subCard,
                )
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.state == "Failed") saltColors().error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = !(completedOnly && selectionMode),
            enter = expandVertically(spring(dampingRatio = 0.84f, stiffness = 440f)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(120)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (entry.state == "Completed") {
                    if (entry.item.x != null || entry.audioOutput != null) {
                        SaltTonalButton(
                            onClick = onOpenFile,
                            shape = RoundedCornerShape(9.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (entry.audioOutput != null) "播放" else "打开", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    SaltButton(
                        onClick = onShare,
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("分享", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.weight(1f))
                } else {
                    if (entry.state == "Failed" && entry.failureKind == "TrackChanged") {
                        SaltTonalButton(
                            onClick = onReparseTrackChanged,
                            shape = RoundedCornerShape(9.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Text(if (entry.item.x != null) "重新解析" else "重选音轨", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (entry.state == "Failed" && entry.failureKind in listOf("XSession", "YoutubeSession")) {
                        SaltTonalButton(
                            onClick = onManageSession,
                            shape = RoundedCornerShape(9.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Text(if (entry.failureKind == "XSession") "登录 X" else "登录 YouTube", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (entry.state == "Failed" && AuthRecovery.needsLogin(entry.failureKind, sessionState)) {
                        SaltTonalButton(
                            onClick = onLogin,
                            shape = RoundedCornerShape(9.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Text("登录 B 站", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (entry.state in listOf("Paused", "Failed")) {
                        SaltTonalButton(
                            onClick = onRetryTask,
                            shape = RoundedCornerShape(9.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("重试", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (entry.state in listOf("Queued", "Downloading", "Merging")) {
                        SaltTonalButton(
                            onClick = onPause,
                            shape = RoundedCornerShape(9.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_pause), contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("暂停", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (entry.state != "Canceled") {
                        SaltTextButton(
                            onClick = onCancel,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Text("取消", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
                SaltIconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = "移除记录",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComfortableQueueCardContent(
    entry: DownloadEntry,
    completedOnly: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    platformLabel: String,
    stateLabel: String,
    tone: BadgeTone,
    animatedProgress: Float,
    pct: Int,
    fileName: String?,
    statusMessage: String,
    sessionState: SessionState,
    onToggleSelect: () -> Unit,
    onOpenFile: () -> Unit,
    onShare: () -> Unit,
    onReparseTrackChanged: () -> Unit,
    onManageSession: () -> Unit,
    onLogin: () -> Unit,
    onRetryTask: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AnimatedVisibility(
                visible = completedOnly && selectionMode,
                enter = expandHorizontally(spring(dampingRatio = 0.82f, stiffness = 460f)) + fadeIn(tween(150)),
                exit = shrinkHorizontally(tween(140)) + fadeOut(tween(110)),
            ) {
                SaltCheckbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
            }
            Cover(
                url = entry.item.cover,
                modifier = Modifier.width(92.dp).height(58.dp),
                cornerRadius = 10.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    SaltStatusBadge(platformLabel, BadgeTone.Accent)
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        SaltStatusBadge(stateLabel, tone)
                        if (entry.state != "Completed") {
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    text = entry.item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.item.partTitle.isNotBlank() && entry.item.partTitle != entry.item.title) {
                    Text(
                        text = entry.item.partTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!fileName.isNullOrBlank()) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = entry.state != "Completed",
            enter = expandVertically(spring(dampingRatio = 0.84f, stiffness = 440f)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(120)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    trackColor = saltColors().subCard,
                )
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.state == "Failed") saltColors().error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = !(completedOnly && selectionMode),
            enter = expandVertically(spring(dampingRatio = 0.84f, stiffness = 440f)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(120)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SaltInsetDivider(Modifier.padding(horizontal = 0.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (entry.state == "Completed") {
                        if (entry.item.x != null || entry.audioOutput != null) {
                            SaltTonalButton(
                                onClick = onOpenFile,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (entry.audioOutput != null) "播放音频" else "打开文件")
                            }
                        }
                        SaltButton(
                            onClick = onShare,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (entry.item.x != null) "分享文件" else if (entry.audioOutput == null) "分享视频" else "分享音频")
                        }
                    } else {
                        if (entry.state == "Failed" && entry.failureKind == "TrackChanged") {
                            SaltTonalButton(
                                onClick = onReparseTrackChanged,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(if (entry.item.x != null) "重新解析 X 帖子" else "重新选择音轨")
                            }
                        }
                        if (entry.state == "Failed" && entry.failureKind in listOf("XSession", "YoutubeSession")) {
                            SaltTonalButton(
                                onClick = onManageSession,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(if (entry.failureKind == "XSession") "登录 X" else "登录 YouTube")
                            }
                        }
                        if (entry.state == "Failed" && AuthRecovery.needsLogin(entry.failureKind, sessionState)) {
                            SaltTonalButton(
                                onClick = onLogin,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text("登录 B 站")
                            }
                        }
                        if (entry.state in listOf("Paused", "Failed")) {
                            SaltTonalButton(
                                onClick = onRetryTask,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("继续 / 重试")
                            }
                        }
                        if (entry.state in listOf("Queued", "Downloading", "Merging")) {
                            SaltTonalButton(
                                onClick = onPause,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Icon(painterResource(R.drawable.ic_pause), contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("暂停")
                            }
                        }
                        if (entry.state != "Canceled") {
                            SaltTextButton(onClick = onCancel) { Text("取消") }
                        }
                    }

                    SaltTextButton(onClick = onRemove) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("移除记录")
                    }
                }
            }
        }
    }
}
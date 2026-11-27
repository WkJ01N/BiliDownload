package io.github.bilidownload.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bilidownload.*
import io.github.bilidownload.R
import io.github.bilidownload.ui.components.*
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(
    listState: LazyListState,
    subPage: String,
    onSubPageChange: (String) -> Unit,
    account: AccountInfo,
    ytAccount: AccountInfo,
    xAccount: AccountInfo,
    componentState: ComponentState,
    legacyComponents: List<LegacyComponent>,
    theme: String,
    accent: String,
    listDensity: String,
    pageAnimStyle: String,
    pageAnimDurationMs: Int,
    pageAnimDamping: Float,
    pageAnimIntensity: Float,
    navFollowSpeed: Float,
    navBounciness: Float,
    navLiquidDeform: Float,
    navDockPressScale: Float,
    expandRelated: Boolean,
    detectClipboard: Boolean,
    xGrouped: Boolean,
    directory: String?,
    namingDraft: String,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onLoginYoutube: () -> Unit,
    onImportYoutubeCookies: () -> Unit,
    onClearYoutubeSession: () -> Unit,
    onLoginX: () -> Unit,
    onImportXCookies: () -> Unit,
    onClearXSession: () -> Unit,
    onOpenComponentDialog: () -> Unit,
    onUninstallLegacy: (LegacyComponent) -> Unit,
    onThemeChange: (String) -> Unit,
    onAccentChange: (String) -> Unit,
    onListDensityChange: (String) -> Unit,
    onPageAnimStyleChange: (String) -> Unit,
    onPageAnimDurationChange: (Int) -> Unit,
    onPageAnimDampingChange: (Float) -> Unit,
    onPageAnimIntensityChange: (Float) -> Unit,
    onResetPageAnim: () -> Unit,
    onNavFollowSpeedChange: (Float) -> Unit,
    onNavBouncinessChange: (Float) -> Unit,
    onNavLiquidDeformChange: (Float) -> Unit,
    onNavDockPressScaleChange: (Float) -> Unit,
    onResetNavAnim: () -> Unit,
    onExpandChange: (Boolean) -> Unit,
    onDetectChange: (Boolean) -> Unit,
    onXGroupedChange: (Boolean) -> Unit,
    onPickDirectory: () -> Unit,
    onResetDirectory: () -> Unit,
    onNamingDraftChange: (String) -> Unit,
    onSaveNaming: () -> Unit,
    onResetNaming: () -> Unit,
    onOpenGithub: () -> Unit,
) {
    var showNamingVariables by remember { mutableStateOf(false) }
    var showPageAnimParams by remember { mutableStateOf(false) }
    var showNavAnimParams by remember { mutableStateOf(false) }
    var ytMenuOpen by remember { mutableStateOf(false) }
    var xMenuOpen by remember { mutableStateOf(false) }
    val namingError = remember(namingDraft) { runCatching { FileNaming.validate(namingDraft) }.exceptionOrNull()?.message }
    val previewName = remember(namingDraft) {
        if (namingError == null) {
            "预览：" + FileNaming.render(
                MediaItem("video:123", "示例视频", "第二部分", "video", 1, "BV1xx411c7mD", 123, 0, 2, partCount = 3),
                namingDraft,
            )
        } else {
            namingError
        }
    }

    BackHandler(enabled = subPage != "Overview") {
        onSubPageChange("Overview")
    }

    AnimatedContent(
        targetState = subPage,
        transitionSpec = {
            val forward = targetState != "Overview"
            if (forward) {
                (slideInHorizontally(tween(240)) { it / 4 } + fadeIn(tween(220)))
                    .togetherWith(slideOutHorizontally(tween(200)) { -it / 8 } + fadeOut(tween(160)))
            } else {
                (slideInHorizontally(tween(240)) { -it / 4 } + fadeIn(tween(220)))
                    .togetherWith(slideOutHorizontally(tween(200)) { it / 4 } + fadeOut(tween(160)))
            }
        },
        label = "settingsSubPage",
    ) { page ->
        when (page) {
            "Accounts" -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { SettingsSubHeader("账号与平台会话", onBack = { onSubPageChange("Overview") }) }
                item {
                    SaltCard {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            SaltLeadingIconBox(
                                iconRes = R.drawable.ic_account,
                                tint = saltColors().accentSoftFg,
                                containerColor = saltColors().accentSoftBg,
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text("Bilibili 账号", style = MaterialTheme.typography.titleMedium)
                                    SaltStatusBadge("B 站", BadgeTone.Accent)
                                }
                                Text(
                                    account.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!account.loggedIn) {
                                Button(onClick = onLogin, shape = RoundedCornerShape(10.dp)) {
                                    Text("登录 B 站")
                                }
                            } else {
                                FilledTonalButton(onClick = onLogout, shape = RoundedCornerShape(10.dp)) {
                                    Text("退出登录")
                                }
                            }
                        }

                        SaltInsetDivider()

                        SaltItemRow(
                            iconRes = R.drawable.ic_play,
                            title = "YouTube 账号",
                            subtitle = if (ytAccount.loggedIn) "已登录" else "未登录 · 点击登录以访问会员或受限视频",
                            trailing = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    FilledTonalButton(
                                        onClick = onLoginYoutube,
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        Text(if (ytAccount.loggedIn) "重新登录" else "登录")
                                    }
                                    Box {
                                        IconButton(
                                            onClick = { ytMenuOpen = true },
                                            modifier = Modifier.size(34.dp),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_more_vert),
                                                contentDescription = "更多选项",
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        DropdownMenu(expanded = ytMenuOpen, onDismissRequest = { ytMenuOpen = false }) {
                                            DropdownMenuItem(
                                                text = { Text("导入 cookies.txt") },
                                                onClick = { ytMenuOpen = false; onImportYoutubeCookies() },
                                            )
                                            if (ytAccount.loggedIn) {
                                                DropdownMenuItem(
                                                    text = { Text("退出登录（移除会话）", color = saltColors().error) },
                                                    onClick = { ytMenuOpen = false; onClearYoutubeSession() },
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                        )

                        SaltInsetDivider()

                        SaltItemRow(
                            iconRes = R.drawable.ic_link,
                            title = "X（Twitter）账号",
                            subtitle = if (xAccount.loggedIn) "已登录" else "未登录 · 点击登录以解析受限或需验证帖子",
                            trailing = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    FilledTonalButton(
                                        onClick = onLoginX,
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        Text(if (xAccount.loggedIn) "重新登录" else "登录")
                                    }
                                    Box {
                                        IconButton(
                                            onClick = { xMenuOpen = true },
                                            modifier = Modifier.size(34.dp),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_more_vert),
                                                contentDescription = "更多选项",
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        DropdownMenu(expanded = xMenuOpen, onDismissRequest = { xMenuOpen = false }) {
                                            DropdownMenuItem(
                                                text = { Text("导入 cookies.txt") },
                                                onClick = { xMenuOpen = false; onImportXCookies() },
                                            )
                                            if (xAccount.loggedIn) {
                                                DropdownMenuItem(
                                                    text = { Text("退出登录（移除会话）", color = saltColors().error) },
                                                    onClick = { xMenuOpen = false; onClearXSession() },
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }

            "Appearance" -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { SettingsSubHeader("外观与交互动效", onBack = { onSubPageChange("Overview") }) }
                item {
                    SaltSectionTitle("主题与显示")
                    SaltCard {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                SaltLeadingIconBox(iconRes = R.drawable.ic_palette)
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("深浅模式", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "切换浅色、深色或跟随系统设置",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(saltColors().subCard)
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                listOf("Default" to "跟随系统", "Light" to "浅色", "Dark" to "深色").forEach { (value, label) ->
                                    val selected = theme == value
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(if (selected) saltColors().card else Color.Transparent)
                                            .clickable { onThemeChange(value) }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            ),
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        SaltInsetDivider()

                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                SaltLeadingIconBox(
                                    iconRes = R.drawable.ic_palette,
                                    tint = saltColors().accentSoftFg,
                                    containerColor = saltColors().accentSoftBg,
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("主题强调色", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = AccentPreset.fromKey(accent).label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                listOf(AccentPreset.Pink, AccentPreset.Blue, AccentPreset.Jade, AccentPreset.Amber).forEach { preset ->
                                    val selected = accent == preset.key
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(preset.swatchColor)
                                            .then(
                                                if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                                else Modifier,
                                            )
                                            .clickable { onAccentChange(preset.key) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (selected) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_check),
                                                contentDescription = preset.label,
                                                modifier = Modifier.size(16.dp),
                                                tint = Color.White,
                                            )
                                        }
                                    }
                                }

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    Spacer(Modifier.weight(1f))
                                    val dynamicSelected = accent == AccentPreset.Dynamic.key
                                    FilterChip(
                                        selected = dynamicSelected,
                                        onClick = { onAccentChange(if (dynamicSelected) AccentPreset.Pink.key else AccentPreset.Dynamic.key) },
                                        label = { Text("动态取色") },
                                        shape = RoundedCornerShape(20.dp),
                                    )
                                }
                            }
                        }

                        SaltInsetDivider()

                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                SaltLeadingIconBox(iconRes = R.drawable.ic_density)
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("列表显示密度", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "调整下载队列与已完成卡片高度与信息密度",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(saltColors().subCard)
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                listOf("Compact" to "紧凑", "Standard" to "标准", "Comfortable" to "宽松").forEach { (value, label) ->
                                    val selected = listDensity == value
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(if (selected) saltColors().card else Color.Transparent)
                                            .clickable { onListDensityChange(value) }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            ),
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    SaltSectionTitle("交互与动效")
                    SaltCard {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                SaltLeadingIconBox(iconRes = R.drawable.ic_refresh)
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("页面切换动画", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "选择翻页动效风格并可展开微调参数",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(saltColors().subCard)
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                listOf("SharedAxis" to "轴向滑移", "Parallax" to "视差推拉", "BreathFade" to "呼吸淡入").forEach { (value, label) ->
                                    val selected = pageAnimStyle == value
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(if (selected) saltColors().card else Color.Transparent)
                                            .clickable { onPageAnimStyleChange(value) }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            ),
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }

                            val pageChevronRotation by animateFloatAsState(
                                targetValue = if (showPageAnimParams) 90f else 0f,
                                label = "pageAnimChevron",
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(saltColors().subCard),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showPageAnimParams = !showPageAnimParams }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("高级参数微调", style = MaterialTheme.typography.labelLarge)
                                        Text(
                                            text = "${pageAnimDurationMs}ms · 阻尼 ${String.format(Locale.US, "%.2f", pageAnimDamping)} · ${String.format(Locale.US, "%.1fx", pageAnimIntensity)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Icon(
                                        painter = painterResource(R.drawable.ic_chevron_right),
                                        contentDescription = if (showPageAnimParams) "收起" else "展开",
                                        modifier = Modifier
                                            .size(18.dp)
                                            .rotate(pageChevronRotation),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                AnimatedVisibility(
                                    visible = showPageAnimParams,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut(),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        SettingsParamSlider(
                                            label = "动画时长",
                                            valueLabel = "$pageAnimDurationMs ms",
                                            value = pageAnimDurationMs.toFloat(),
                                            valueRange = 200f..520f,
                                            onValueChange = { onPageAnimDurationChange(((it / 20f).roundToInt() * 20).coerceIn(200, 520)) },
                                        )
                                        val hint = when {
                                            pageAnimDamping >= 0.96f -> "顺滑"
                                            pageAnimDamping <= 0.80f -> "弹性"
                                            else -> "柔和"
                                        }
                                        SettingsParamSlider(
                                            label = "弹簧阻尼",
                                            valueLabel = "${String.format(Locale.US, "%.2f", pageAnimDamping)} · $hint",
                                            value = pageAnimDamping,
                                            valueRange = 0.72f..1.00f,
                                            onValueChange = { onPageAnimDampingChange((it * 100f).roundToInt() / 100f) },
                                        )
                                        SettingsParamSlider(
                                            label = "动效幅度",
                                            valueLabel = String.format(Locale.US, "%.1fx", pageAnimIntensity),
                                            value = pageAnimIntensity,
                                            valueRange = 0.5f..1.6f,
                                            onValueChange = { onPageAnimIntensityChange((it * 10f).roundToInt() / 10f) },
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                            horizontalArrangement = Arrangement.End,
                                        ) {
                                            TextButton(
                                                onClick = onResetPageAnim,
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            ) {
                                                Text("恢复默认", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        SaltInsetDivider()

                        val navChevronRotation by animateFloatAsState(
                            targetValue = if (showNavAnimParams) 90f else 0f,
                            label = "navAnimChevron",
                        )
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showNavAnimParams = !showNavAnimParams }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                SaltLeadingIconBox(iconRes = R.drawable.ic_settings)
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("悬浮导航栏动效", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "拖动跟手度、Q 弹液态形变与按下放大",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    painter = painterResource(R.drawable.ic_chevron_right),
                                    contentDescription = if (showNavAnimParams) "收起" else "展开",
                                    modifier = Modifier
                                        .size(18.dp)
                                        .rotate(navChevronRotation),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            AnimatedVisibility(
                                visible = showNavAnimParams,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(saltColors().subCard)
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    val followHint = when {
                                        navFollowSpeed <= 17.5f -> "慵懒"
                                        navFollowSpeed >= 27f -> "紧跟"
                                        else -> "平衡"
                                    }
                                    SettingsParamSlider(
                                        label = "跟手速度",
                                        valueLabel = "${String.format(Locale.US, "%.1f", navFollowSpeed)} · $followHint",
                                        value = navFollowSpeed,
                                        valueRange = 14f..34f,
                                        onValueChange = { onNavFollowSpeedChange(((it * 2f).roundToInt() / 2f).coerceIn(14f, 34f)) },
                                    )
                                    SettingsParamSlider(
                                        label = "Q 弹程度",
                                        valueLabel = "${(navBounciness * 100f).roundToInt()}%",
                                        value = navBounciness,
                                        valueRange = 0f..1f,
                                        onValueChange = { onNavBouncinessChange(((it * 50f).roundToInt() / 50f).coerceIn(0f, 1f)) },
                                    )
                                    SettingsParamSlider(
                                        label = "液态形变",
                                        valueLabel = String.format(Locale.US, "%.2fx", navLiquidDeform),
                                        value = navLiquidDeform,
                                        valueRange = 0.3f..2.0f,
                                        onValueChange = { onNavLiquidDeformChange(((it * 20f).roundToInt() / 20f).coerceIn(0.3f, 2.0f)) },
                                    )
                                    SettingsParamSlider(
                                        label = "按下放大",
                                        valueLabel = if (navDockPressScale <= 1.001f) {
                                            "关闭"
                                        } else {
                                            String.format(Locale.US, "+%.1f%%", (navDockPressScale - 1f) * 100f)
                                        },
                                        value = navDockPressScale,
                                        valueRange = 1.00f..1.08f,
                                        onValueChange = { onNavDockPressScaleChange(((it * 200f).roundToInt() / 200f).coerceIn(1.00f, 1.08f)) },
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        TextButton(
                                            onClick = onResetNavAnim,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        ) {
                                            Text("恢复默认", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            "Download" -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { SettingsSubHeader("存储、命名与解析", onBack = { onSubPageChange("Overview") }) }
                item {
                    SaltSectionTitle("存储位置")
                    SaltCard {
                        SaltItemRow(
                            iconRes = R.drawable.ic_folder,
                            title = "保存位置",
                            subtitle = directory ?: "视频 Movies/BiliDownload · 音频 Music/BiliDownload",
                            onClick = onPickDirectory,
                        )
                        Row(
                            modifier = Modifier.padding(start = 66.dp, end = 16.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(onClick = onPickDirectory, shape = RoundedCornerShape(10.dp)) {
                                Text("选择目录")
                            }
                            TextButton(onClick = onResetDirectory) {
                                Text("恢复默认")
                            }
                        }

                        SaltInsetDivider()

                        SaltSwitchRow(
                            iconRes = R.drawable.ic_folder,
                            title = "X 按帖子保存到独立文件夹",
                            subtitle = "关闭则按图片、视频、音频和正文类型分类保存",
                            checked = xGrouped,
                            onCheckedChange = onXGroupedChange,
                        )
                    }
                }

                item {
                    SaltSectionTitle("文件命名模板")
                    SaltCard {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SaltTextField(
                                value = namingDraft,
                                onValueChange = onNamingDraftChange,
                                label = "默认文件名模板",
                                placeholder = FileNaming.DEFAULT,
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (namingError == null) saltColors().subCard else saltColors().errorBg)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = previewName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (namingError == null) MaterialTheme.colorScheme.onSurfaceVariant else saltColors().error,
                                )
                            }

                            TextButton(
                                onClick = { showNamingVariables = !showNamingVariables },
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                Text(if (showNamingVariables) "收起命名变量" else "展开并插入命名变量")
                            }

                            if (showNamingVariables) {
                                val variableLabels = remember {
                                    mapOf(
                                        "name" to "默认名称",
                                        "title" to "标题",
                                        "part" to "分项标题",
                                        "index" to "序号",
                                        "bvid" to "BV号",
                                        "id" to "编号",
                                    )
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FileNaming.variables.forEach { variable ->
                                        SuggestionChip(
                                            onClick = { onNamingDraftChange("$namingDraft{$variable}") },
                                            label = { Text(variableLabels.getValue(variable)) },
                                            shape = RoundedCornerShape(10.dp),
                                        )
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = namingError == null,
                                    onClick = onSaveNaming,
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("保存命名设置")
                                }
                                TextButton(onClick = onResetNaming) {
                                    Text("恢复默认")
                                }
                            }
                        }
                    }
                }

                item {
                    SaltSectionTitle("解析与剪贴板行为")
                    SaltCard {
                        SaltSwitchRow(
                            iconRes = R.drawable.ic_play,
                            title = "展开 YouTube 播放列表",
                            subtitle = "链接包含播放列表时展开列表供批量选择",
                            checked = expandRelated,
                            onCheckedChange = onExpandChange,
                        )
                        SaltInsetDivider()
                        SaltSwitchRow(
                            iconRes = R.drawable.ic_clipboard,
                            title = "切回应用时识别剪贴板",
                            subtitle = "回到应用前台时自动提取剪贴板中的可下载链接",
                            checked = detectClipboard,
                            onCheckedChange = onDetectChange,
                        )
                    }
                }
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                    )
                }

                item {
                    SaltSectionTitle("偏好设置")
                    SaltCard {
                        val loggedPlatforms = remember(account.loggedIn, ytAccount.loggedIn, xAccount.loggedIn) {
                            buildList {
                                if (account.loggedIn) add("B 站")
                                if (ytAccount.loggedIn) add("YouTube")
                                if (xAccount.loggedIn) add("X")
                            }
                        }
                        SettingsOverviewEntry(
                            iconRes = R.drawable.ic_account,
                            title = "账号与平台会话",
                            subtitle = if (loggedPlatforms.isEmpty()) "管理 B 站、YouTube 与 X 登录状态" else "已登录：${loggedPlatforms.joinToString(" · ")}",
                            badgeText = if (loggedPlatforms.isEmpty()) null else "${loggedPlatforms.size}/3",
                            badgeTone = BadgeTone.Success,
                            onClick = { onSubPageChange("Accounts") },
                        )

                        SaltInsetDivider()

                        val themeLabel = when (theme) {
                            "Light" -> "浅色"
                            "Dark" -> "深色"
                            else -> "跟随系统"
                        }
                        val densityLabel = when (listDensity) {
                            "Compact" -> "紧凑"
                            "Comfortable" -> "宽松"
                            else -> "标准"
                        }
                        SettingsOverviewEntry(
                            iconRes = R.drawable.ic_palette,
                            title = "外观与交互动效",
                            subtitle = "$themeLabel · ${AccentPreset.fromKey(accent).label} · $densityLabel",
                            onClick = { onSubPageChange("Appearance") },
                        )

                        SaltInsetDivider()

                        SettingsOverviewEntry(
                            iconRes = R.drawable.ic_folder,
                            title = "存储、命名与解析",
                            subtitle = "${if (directory == null) "默认存储目录" else "自定义目录"} · 剪贴板${if (detectClipboard) "已开启" else "已关闭"}",
                            onClick = { onSubPageChange("Download") },
                        )
                    }
                }

                item {
                    SaltSectionTitle("组件与扩展")
                    SaltCard {
                        MediaComponentSetting(componentState, onOpenComponentDialog)
                        legacyComponents.forEach { legacy ->
                            SaltInsetDivider()
                            SaltItemRow(
                                iconRes = R.drawable.ic_delete,
                                title = legacy.removalLabel,
                                subtitle = "旧版独立扩展已不再需要，点击可卸载清理",
                                onClick = { onUninstallLegacy(legacy) },
                            )
                        }
                    }
                }

                item {
                    SaltSectionTitle("关于")
                    SaltCard {
                        SaltItemRow(
                            iconRes = R.drawable.ic_info,
                            title = "BiliDownload",
                            subtitle = "Android ${BuildConfig.VERSION_NAME}",
                        )
                        SaltInsetDivider()
                        SaltItemRow(
                            iconRes = R.drawable.ic_link,
                            title = "GitHub 仓库",
                            subtitle = "github.com/WkJ01N/BiliDownload",
                            onClick = onOpenGithub,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSubHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = "返回设置",
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun SettingsOverviewEntry(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String,
    badgeText: String? = null,
    badgeTone: BadgeTone = BadgeTone.Accent,
    onClick: () -> Unit,
) {
    SaltItemRow(
        iconRes = iconRes,
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (badgeText != null) {
                    SaltStatusBadge(badgeText, badgeTone)
                }
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun SettingsParamSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }
}

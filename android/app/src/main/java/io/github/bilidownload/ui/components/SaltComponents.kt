package io.github.bilidownload.ui.components

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationEndReason
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bilidownload.R
import io.github.bilidownload.radixFocusColor
import io.github.bilidownload.saltColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.flow.first

enum class BadgeTone { Accent, Success, Warning, Error, Info, Neutral }

@Composable
fun Modifier.saltPressBounce(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = 0.94f,
    pressedAlpha: Float = 0.84f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val active = pressed && enabled
    val scale by animateFloatAsState(
        targetValue = if (active) pressedScale else 1f,
        animationSpec = if (active) {
            spring(dampingRatio = 0.85f, stiffness = 920f)
        } else {
            spring(dampingRatio = 0.56f, stiffness = 460f)
        },
        label = "saltPressScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (active) pressedAlpha else 1f,
        animationSpec = tween(if (active) 70 else 160),
        label = "saltPressAlpha",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}

@Composable
fun Modifier.pressScale(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .saltPressBounce(
            interactionSource = interactionSource,
            enabled = enabled,
            pressedScale = 0.975f,
            pressedAlpha = 0.95f,
        )
        .clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.saltCombinedPressBounce(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .saltPressBounce(
            interactionSource = interactionSource,
            enabled = enabled,
            pressedScale = 0.975f,
            pressedAlpha = 0.95f,
        )
        .combinedClickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
}

@Composable
fun SaltButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.saltPressBounce(
            interactionSource = interactionSource,
            enabled = enabled,
            pressedScale = 0.95f,
            pressedAlpha = 0.88f,
        ),
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
fun SaltTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.saltPressBounce(
            interactionSource = interactionSource,
            enabled = enabled,
            pressedScale = 0.94f,
            pressedAlpha = 0.85f,
        ),
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
fun SaltOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.saltPressBounce(
            interactionSource = interactionSource,
            enabled = enabled,
            pressedScale = 0.94f,
            pressedAlpha = 0.85f,
        ),
        enabled = enabled,
        shape = shape,
        colors = colors,
        border = border ?: ButtonDefaults.outlinedButtonBorder(enabled),
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
fun SaltTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(10.dp),
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        modifier = modifier.saltPressBounce(
            interactionSource = interactionSource,
            enabled = enabled,
            pressedScale = 0.93f,
            pressedAlpha = 0.78f,
        ),
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
fun SaltIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = modifier.saltPressBounce(
            interactionSource = interactionSource,
            enabled = enabled,
            pressedScale = 0.86f,
            pressedAlpha = 0.76f,
        ),
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
fun SaltSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
    )
}

@Composable
fun SaltCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val salt = saltColors()
    val borderColor by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.primary else salt.subtleBorder,
        animationSpec = tween(180),
        label = "cardBorder",
    )
    val baseModifier = modifier
        .fillMaxWidth()
        .let { if (onClick != null) it.pressScale(onClick = onClick) else it }

    Surface(
        modifier = baseModifier,
        shape = RoundedCornerShape(16.dp),
        color = salt.card,
        border = BorderStroke(if (highlighted) 1.5.dp else 1.dp, borderColor),
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun SaltInsetDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = saltColors().subtleBorder,
    )
}

@Composable
fun SaltLeadingIconBox(
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = saltColors().subCard,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = tint,
        )
    }
}

@Composable
fun SaltItemRow(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = 16.dp, vertical = 14.dp)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SaltLeadingIconBox(iconRes = iconRes)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SaltSwitchRow(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SaltLeadingIconBox(iconRes = iconRes)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = saltColors().subCard,
                uncheckedThumbColor = colors.onSurfaceVariant,
                uncheckedBorderColor = saltColors().subtleBorder,
            ),
        )
    }
}

@Composable
fun SaltStatusBadge(text: String, tone: BadgeTone = BadgeTone.Neutral, modifier: Modifier = Modifier) {
    val salt = saltColors()
    val (targetBg, targetFg) = when (tone) {
        BadgeTone.Accent -> salt.accentSoftBg to salt.accentSoftFg
        BadgeTone.Success -> salt.successBg to salt.success
        BadgeTone.Warning -> salt.warningBg to salt.warning
        BadgeTone.Error -> salt.errorBg to salt.error
        BadgeTone.Info -> salt.infoBg to salt.info
        BadgeTone.Neutral -> salt.subCard to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val bg by animateColorAsState(targetValue = targetBg, animationSpec = tween(180), label = "badgeBg")
    val fg by animateColorAsState(targetValue = targetFg, animationSpec = tween(180), label = "badgeFg")
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
fun SaltPlatformChip(
    name: String,
    active: Boolean,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val salt = saltColors()
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        targetValue = if (active) salt.accentSoftBg else salt.subCard,
        animationSpec = tween(160),
        label = "chipBg",
    )
    val border by animateColorAsState(
        targetValue = if (active) colors.primary.copy(alpha = 0.35f) else salt.subtleBorder,
        animationSpec = tween(160),
        label = "chipBorder",
    )
    val fg by animateColorAsState(
        targetValue = if (active) salt.accentSoftFg else colors.onSurfaceVariant,
        animationSpec = tween(160),
        label = "chipFg",
    )
    Row(
        modifier = Modifier
            .saltPressBounce(interactionSource = interactionSource, pressedScale = 0.94f, pressedAlpha = 0.86f)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(20.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(colors.primary),
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = fg,
        )
        if (!badge.isNullOrBlank()) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = colors.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
fun SaltTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    showClear: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val salt = saltColors()
    val borderColor by animateColorAsState(
        targetValue = if (focused) radixFocusColor() else salt.subtleBorder,
        animationSpec = tween(150),
        label = "fieldBorder",
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = colors.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(salt.subCard)
                .border(if (focused) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = if (minLines > 1) Alignment.Top else Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    minLines = minLines,
                    maxLines = maxLines,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = showClear && value.isNotEmpty(),
                enter = scaleIn(spring(dampingRatio = 0.65f, stiffness = 520f)) + fadeIn(tween(120)),
                exit = scaleOut(tween(100)) + fadeOut(tween(100)),
            ) {
                val clearInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .saltPressBounce(interactionSource = clearInteraction, pressedScale = 0.85f, pressedAlpha = 0.75f)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(colors.outlineVariant.copy(alpha = 0.5f))
                        .clickable(interactionSource = clearInteraction, indication = null) { onValueChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_clear),
                        contentDescription = "清空",
                        modifier = Modifier.size(14.dp),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun SaltSegmentedControl(
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val count = options.size
    val selectedIndex = options.indexOfFirst { it.first == selectedValue }.coerceAtLeast(0)
    val haptic = LocalHapticFeedback.current
    val viewConfig = LocalViewConfiguration.current
    val colors = MaterialTheme.colorScheme

    var targetPos by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var pillPos by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var pillVel by remember { mutableFloatStateOf(0f) }
    var isPressed by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(selectedIndex) {
        if (!isPressed && !isDragging) {
            targetPos = selectedIndex.toFloat()
        }
    }

    // Permanent VSync 2nd-order Spring-Damper physics loop: fast launch, smooth ease-out & gentle Q-bounce settle
    LaunchedEffect(Unit) {
        while (true) {
            snapshotFlow {
                isPressed ||
                    isDragging ||
                    abs(targetPos - pillPos) >= 0.0005f ||
                    abs(pillVel) >= 0.002f
            }.first { it }

            var lastNanos = 0L
            while (true) {
                val done = withFrameNanos { frameNanos ->
                    if (lastNanos == 0L) {
                        lastNanos = frameNanos
                        return@withFrameNanos false
                    }
                    val dt = ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.032f)
                    lastNanos = frameNanos

                    val omega = if (isDragging) 28f else 19.5f
                    val zeta = if (isDragging) 0.86f else 0.65f
                    val stiffness = omega * omega
                    val damping = 2f * zeta * omega

                    val subDt = dt * 0.5f
                    var p = pillPos
                    var v = pillVel
                    repeat(2) {
                        val accel = stiffness * (targetPos - p) - damping * v
                        v += accel * subDt
                        p += v * subDt
                    }
                    pillPos = p
                    pillVel = v

                    !isPressed &&
                        !isDragging &&
                        abs(targetPos - p) < 0.0005f &&
                        abs(v) < 0.002f
                }
                if (done) {
                    pillPos = targetPos
                    pillVel = 0f
                    break
                }
            }
        }
    }

    val pressProgress by animateFloatAsState(
        targetValue = if (isPressed || isDragging) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 560f),
        label = "segPress",
    )

    val density = LocalDensity.current
    val pillShape = RoundedCornerShape(9.dp)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(saltColors().subCard)
            .padding(4.dp),
    ) {
        val slotWidth = maxWidth / count
        val slotWidthPx = with(density) { slotWidth.toPx() }.coerceAtLeast(1f)
        val latestOptions by rememberUpdatedState(options)
        val latestSelectedIndex by rememberUpdatedState(selectedIndex)
        val latestSlotWidthPx by rememberUpdatedState(slotWidthPx)
        val latestOnSelect by rememberUpdatedState(onSelect)

        Box(
            modifier = Modifier
                .width(slotWidth)
                .height(36.dp)
                .graphicsLayer {
                    val velStretch = (abs(pillVel) * 0.012f).coerceIn(0f, 0.055f)
                    val pressShrink = 1f - 0.03f * pressProgress.coerceIn(0f, 1f)
                    translationX = pillPos * slotWidthPx
                    scaleX = pressShrink * (1f + velStretch)
                    scaleY = pressShrink / sqrt((1f + velStretch * 0.6f).coerceAtLeast(0.9f))
                }
                .shadow(elevation = 1.dp, shape = pillShape, clip = false)
                .clip(pillShape)
                .background(saltColors().card),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(count) {
                    val maxIdx = (count - 1).toFloat()
                    val touchSlop = viewConfig.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isPressed = true
                        var dragging = false
                        var canceled = false
                        val initialSlotPx = latestSlotWidthPx.coerceAtLeast(1f)
                        var lastHapticSlot = (down.position.x / initialSlotPx).toInt().coerceIn(0, count - 1)
                        targetPos = lastHapticSlot.toFloat()

                        while (true) {
                            val event = awaitPointerEvent()
                            val activeChange = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                            if (activeChange == null) {
                                canceled = true
                                break
                            }
                            if (!dragging && activeChange.isConsumed) {
                                canceled = true
                                break
                            }
                            if (!activeChange.pressed) {
                                break
                            }
                            val dx = activeChange.position.x - down.position.x
                            if (!dragging && abs(dx) > touchSlop) {
                                dragging = true
                                isDragging = true
                            }
                            if (dragging) {
                                activeChange.consume()
                                val slotPx = latestSlotWidthPx.coerceAtLeast(1f)
                                val rawPos = activeChange.position.x / slotPx - 0.5f
                                targetPos = when {
                                    rawPos < 0f -> rawPos * 0.25f
                                    rawPos > maxIdx -> maxIdx + (rawPos - maxIdx) * 0.25f
                                    else -> rawPos
                                }.coerceIn(-0.18f, maxIdx + 0.18f)
                                val nearest = rawPos.roundToInt().coerceIn(0, count - 1)
                                if (nearest != lastHapticSlot) {
                                    lastHapticSlot = nearest
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        }

                        if (canceled) {
                            targetPos = latestSelectedIndex.toFloat()
                        } else {
                            val finalIdx = targetPos.roundToInt().coerceIn(0, count - 1)
                            targetPos = finalIdx.toFloat()
                            if (finalIdx != latestSelectedIndex) {
                                if (!dragging) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                latestOnSelect(latestOptions[finalIdx].first)
                            }
                        }
                        isPressed = false
                        isDragging = false
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, (value, label) ->
                val isSelected = value == selectedValue
                val proximity = (1f - abs(pillPos - index)).coerceIn(0f, 1f)
                val labelColor = lerp(colors.onSurfaceVariant, colors.primary, proximity)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics {
                            role = Role.Tab
                            selected = isSelected
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (isSelected || proximity > 0.55f) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = labelColor,
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer {
                            val s = 1f + 0.03f * proximity
                            scaleX = s
                            scaleY = s
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SaltCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        targetValue = if (checked) colors.primary else Color.Transparent,
        animationSpec = tween(140),
        label = "checkBg",
    )
    val border by animateColorAsState(
        targetValue = if (!enabled) colors.outline.copy(alpha = 0.4f) else if (checked) colors.primary else colors.outline,
        animationSpec = tween(140),
        label = "checkBorder",
    )
    Box(
        modifier = Modifier
            .size(36.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .saltPressBounce(interactionSource = interactionSource, enabled = enabled, pressedScale = 0.86f, pressedAlpha = 0.86f)
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(bg)
                .border(1.5.dp, border, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = checked,
                enter = scaleIn(spring(dampingRatio = 0.65f, stiffness = 560f)) + fadeIn(tween(120)),
                exit = scaleOut(tween(110)) + fadeOut(tween(100)),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.onPrimary,
                )
            }
        }
    }
}

@Composable
fun SaltAlertBanner(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    countdownProgress: Float? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val salt = saltColors()
    val bg = if (isError) salt.errorBg else salt.infoBg
    val fg = if (isError) salt.error else salt.info

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.25f)),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            if (countdownProgress != null) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            scaleX = countdownProgress.coerceIn(0f, 1f)
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                        .background(fg.copy(alpha = 0.14f)),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_info),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = fg,
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    val closeInteraction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .saltPressBounce(interactionSource = closeInteraction, pressedScale = 0.85f, pressedAlpha = 0.75f)
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(interactionSource = closeInteraction, indication = null, onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_clear),
                            contentDescription = "关闭提示",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (actions != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
                }
            }
        }
    }
}

@Composable
fun SaltAnimatedAlertBanner(
    visible: Boolean,
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    autoDismissMs: Long? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    var displayMessage by remember { mutableStateOf("") }
    var displayError by remember { mutableStateOf(false) }
    if (visible && message.isNotBlank()) {
        displayMessage = message
        displayError = isError
    }
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val shouldAutoDismiss = visible && message.isNotBlank() && autoDismissMs != null && autoDismissMs > 0L && actions == null && !isError
    val countdown = remember { Animatable(1f) }
    LaunchedEffect(visible, message, autoDismissMs, isError, actions != null) {
        if (visible && message.isNotBlank() && autoDismissMs != null && autoDismissMs > 0L && actions == null && !isError) {
            countdown.snapTo(1f)
            val result = countdown.animateTo(0f, animationSpec = tween(autoDismissMs.toInt(), easing = LinearEasing))
            if (result.endReason == AnimationEndReason.Finished) {
                latestOnDismiss()
            }
        } else {
            countdown.snapTo(1f)
        }
    }
    AnimatedVisibility(
        visible = visible && message.isNotBlank(),
        modifier = modifier,
        enter = expandVertically(spring(dampingRatio = 0.84f, stiffness = 420f)) +
            slideInVertically(tween(220)) { -it / 3 } + fadeIn(tween(200)),
        exit = shrinkVertically(tween(200)) +
            slideOutVertically(tween(180)) { -it / 3 } + fadeOut(tween(150)),
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            SaltAlertBanner(
                message = displayMessage,
                isError = displayError,
                onDismiss = onDismiss,
                countdownProgress = if (shouldAutoDismiss) countdown.value else null,
                actions = actions,
            )
        }
    }
}

@Composable
fun SaltEmptyState(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val salt = saltColors()
    SaltCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(salt.accentSoftBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = salt.accentSoftFg,
                )
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!actionLabel.isNullOrBlank() && onAction != null) {
                Spacer(Modifier.height(4.dp))
                SaltTonalButton(
                    onClick = onAction,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

private data class NavEntry(val label: String, @DrawableRes val icon: Int)

@Composable
private fun SaltFrostedDockBackdrop(
    shape: Shape,
    elevation: Dp,
    borderBrush: Brush,
    isDark: Boolean,
    backdropLayer: GraphicsLayer?,
    backdropCoords: LayoutCoordinates?,
    observeBackdrop: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val activeBackdrop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) backdropLayer else null
    val realBlurActive = activeBackdrop != null
    val tintBrush = remember(isDark, realBlurActive) {
        if (isDark) {
            Brush.verticalGradient(
                listOf(
                    Color(0xFF2B2B34).copy(alpha = if (realBlurActive) 0.56f else 0.90f),
                    Color(0xFF17171D).copy(alpha = if (realBlurActive) 0.46f else 0.84f),
                ),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = if (realBlurActive) 0.64f else 0.92f),
                    Color(0xFFF3F3F7).copy(alpha = if (realBlurActive) 0.48f else 0.86f),
                ),
            )
        }
    }
    val canvasBackground = MaterialTheme.colorScheme.background
    val density = LocalDensity.current
    val blurRadiusPx = with(density) { 22.dp.toPx() }
    val blurEffect = remember(blurRadiusPx) {
        if (blurRadiusPx > 0f) BlurEffect(blurRadiusPx, blurRadiusPx, TileMode.Clamp) else null
    }
    val sliceLayer = rememberGraphicsLayer()
    val blurLayer = rememberGraphicsLayer()
    var dockCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Box(
        modifier = modifier
            .onGloballyPositioned { dockCoords = it }
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (isDark) 0.45f else 0.14f),
                spotColor = Color.Black.copy(alpha = if (isDark) 0.55f else 0.18f),
            )
            .clip(shape),
    ) {
        if (activeBackdrop != null && blurEffect != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        observeBackdrop()
                        val dockW = size.width.roundToInt()
                        val dockH = size.height.roundToInt()
                        if (dockW <= 0 || dockH <= 0) return@drawBehind
                        val offset = runCatching {
                            val b = backdropCoords
                            val d = dockCoords
                            if (b != null && b.isAttached && d != null && d.isAttached) {
                                b.localPositionOf(d, Offset.Zero)
                            } else {
                                null
                            }
                        }.getOrNull()
                        if (offset == null || activeBackdrop.size.width <= 0 || activeBackdrop.size.height <= 0) {
                            drawRect(canvasBackground)
                            return@drawBehind
                        }
                        val pad = ceil(blurRadiusPx).toInt().coerceAtLeast(1)
                        val paddedSize = IntSize(dockW + pad * 2, dockH + pad * 2)
                        // Stage 1: flatten all nested RenderNodes (cards, Covers, animateItem) into an opaque offscreen texture
                        sliceLayer.compositingStrategy = LayerCompositingStrategy.Offscreen
                        sliceLayer.clip = true
                        sliceLayer.record(size = paddedSize) {
                            drawRect(canvasBackground)
                            translate(left = -(offset.x - pad), top = -(offset.y - pad)) {
                                drawLayer(activeBackdrop)
                            }
                        }
                        // Stage 2: apply hardware BlurEffect strictly on the flattened offscreen slice
                        blurLayer.compositingStrategy = LayerCompositingStrategy.Auto
                        blurLayer.clip = true
                        blurLayer.renderEffect = blurEffect
                        blurLayer.record(size = paddedSize) {
                            drawLayer(sliceLayer)
                        }
                        translate(left = -pad.toFloat(), top = -pad.toFloat()) {
                            drawLayer(blurLayer)
                        }
                    },
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(tintBrush)
                .border(1.dp, borderBrush, shape),
        )
    }
}

@Composable
fun SaltFloatingNavBar(
    currentPage: Int,
    activeDownloadCount: Int,
    onSelect: (Int) -> Unit,
    onReselect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    followSpeed: Float = 22.5f,
    bounciness: Float = 0.20f,
    liquidDeform: Float = 1.15f,
    dockPressScale: Float = 1.04f,
    backdropLayer: GraphicsLayer? = null,
    backdropCoords: LayoutCoordinates? = null,
    observeBackdrop: () -> Unit = {},
) {
    val salt = saltColors()
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.5f
    val haptic = LocalHapticFeedback.current
    val entries = remember {
        listOf(
            NavEntry("解析", R.drawable.ic_link),
            NavEntry("下载", R.drawable.ic_download),
            NavEntry("已完成", R.drawable.ic_complete),
            NavEntry("设置", R.drawable.ic_settings),
        )
    }

    val tabCount = entries.size
    var targetPos by remember { mutableFloatStateOf(currentPage.toFloat()) }
    var lensPos by remember { mutableFloatStateOf(currentPage.toFloat()) }
    var lensVel by remember { mutableFloatStateOf(0f) }
    var jellyDeform by remember { mutableFloatStateOf(0f) }
    var jellyVel by remember { mutableFloatStateOf(0f) }
    var isPressed by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    val latestFollowSpeed by rememberUpdatedState(followSpeed.coerceIn(14f, 34f))
    val latestBounciness by rememberUpdatedState(bounciness.coerceIn(0f, 1f))
    val latestLiquidDeform by rememberUpdatedState(liquidDeform.coerceIn(0.3f, 2.0f))

    val liftProgress by animateFloatAsState(
        targetValue = if (isPressed || isDragging) 1f else 0f,
        animationSpec = spring(
            dampingRatio = (0.72f - 0.24f * bounciness).coerceIn(0.46f, 0.72f),
            stiffness = 460f,
        ),
        label = "lensLift",
    )
    val dockPressProgress by animateFloatAsState(
        targetValue = if (isPressed || isDragging) 1f else 0f,
        animationSpec = spring(
            dampingRatio = (0.76f - 0.30f * bounciness).coerceIn(0.46f, 0.76f),
            stiffness = 420f,
        ),
        label = "dockPress",
    )
    // Guard against under-damped spring undershoot producing illegal negative alpha or shadow elevation
    val safeLift = liftProgress.coerceIn(0f, 1f)
    val safeDockPress = dockPressProgress.coerceIn(0f, 1f)

    LaunchedEffect(currentPage) {
        if (!isPressed && !isDragging) {
            targetPos = currentPage.toFloat()
        }
    }

    // Permanent VSync 2nd-order Spring-Damper physics loop: parks via snapshotFlow at 0% CPU when settled, never deadlocks
    LaunchedEffect(Unit) {
        while (true) {
            snapshotFlow {
                isPressed ||
                    isDragging ||
                    abs(targetPos - lensPos) >= 0.0005f ||
                    abs(lensVel) >= 0.002f ||
                    abs(jellyDeform) >= 0.001f ||
                    abs(jellyVel) >= 0.003f
            }.first { it }

            var lastNanos = 0L
            while (true) {
                val done = withFrameNanos { frameNanos ->
                    if (lastNanos == 0L) {
                        lastNanos = frameNanos
                        return@withFrameNanos false
                    }
                    val dt = ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.032f)
                    lastNanos = frameNanos

                    val activeTouch = isPressed || isDragging
                    val b = latestBounciness
                    val omega = if (activeTouch) latestFollowSpeed else (latestFollowSpeed * 1.08f).coerceAtLeast(22f)
                    val zeta = if (activeTouch) {
                        (0.88f - 0.24f * b).coerceIn(0.58f, 0.88f)
                    } else {
                        (0.88f - 0.48f * b).coerceIn(0.38f, 0.88f)
                    }
                    val stiffness = omega * omega
                    val damping = 2f * zeta * omega

                    val omegaJ = 26f
                    val zetaJ = (0.56f - 0.26f * b).coerceIn(0.28f, 0.56f)
                    val stiffnessJ = omegaJ * omegaJ
                    val dampingJ = 2f * zetaJ * omegaJ
                    val deformScale = latestLiquidDeform

                    val subDt = dt * 0.5f
                    var p = lensPos
                    var v = lensVel
                    var j = jellyDeform
                    var jv = jellyVel
                    repeat(2) {
                        val accel = stiffness * (targetPos - p) - damping * v
                        v += accel * subDt
                        p += v * subDt

                        val velStretch = (abs(v) * 0.032f * deformScale).coerceIn(0f, 0.36f)
                        val brakeSquash = if (v * accel < 0f) {
                            (-abs(accel) * 0.00055f * deformScale).coerceIn(-0.15f, 0f)
                        } else {
                            0f
                        }
                        val jellyTarget = (velStretch + brakeSquash).coerceIn(-0.16f, 0.38f)
                        val jAccel = stiffnessJ * (jellyTarget - j) - dampingJ * jv
                        jv += jAccel * subDt
                        j = (j + jv * subDt).coerceIn(-0.22f, 0.42f)
                    }
                    lensPos = p
                    lensVel = v
                    jellyDeform = j
                    jellyVel = jv

                    !activeTouch &&
                        abs(targetPos - p) < 0.0005f &&
                        abs(v) < 0.002f &&
                        abs(j) < 0.001f &&
                        abs(jv) < 0.003f
                }
                if (done) {
                    lensPos = targetPos
                    lensVel = 0f
                    jellyDeform = 0f
                    jellyVel = 0f
                    break
                }
            }
        }
    }

    val dockShape = RoundedCornerShape(30.dp)
    val dockBorderBrush = if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = (0.28f + 0.12f * safeDockPress).coerceIn(0f, 1f)),
                Color.White.copy(alpha = (0.08f + 0.06f * safeDockPress).coerceIn(0f, 1f)),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = (0.96f + 0.04f * safeDockPress).coerceIn(0f, 1f)),
                salt.subtleBorder.copy(alpha = (0.55f + 0.15f * safeDockPress).coerceIn(0f, 1f)),
            ),
        )
    }

    Box(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // Whole-dock press-swell magnification & subtle edge rubber-band push around exact center of the dock
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val maxPos = (tabCount - 1).toFloat()
                    val overscroll = when {
                        lensPos < 0f -> lensPos
                        lensPos > maxPos -> lensPos - maxPos
                        else -> 0f
                    }
                    val deltaScale = (dockPressScale - 1f).coerceIn(0f, 0.08f)
                    scaleX = 1f + deltaScale * dockPressProgress + abs(overscroll) * 0.06f
                    scaleY = 1f + deltaScale * 1.45f * dockPressProgress
                    translationX = (overscroll * 24.dp.toPx()).coerceIn(-5.dp.toPx(), 5.dp.toPx())
                },
        ) {
            // Frosted real-blur translucent glass dock
            SaltFrostedDockBackdrop(
                shape = dockShape,
                elevation = (14f + 6f * safeDockPress).dp,
                borderBrush = dockBorderBrush,
                isDark = isDark,
                backdropLayer = backdropLayer,
                backdropCoords = backdropCoords,
                observeBackdrop = observeBackdrop,
                modifier = Modifier.matchParentSize(),
            )

            val density = LocalDensity.current
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
            ) {
                val tabWidth = maxWidth / tabCount
                val tabWidthPx = with(density) { tabWidth.toPx() }.coerceAtLeast(1f)

                val highlightedTab by remember {
                    derivedStateOf {
                        lensPos.roundToInt().coerceIn(0, tabCount - 1)
                    }
                }

                val latestCurrentPage by rememberUpdatedState(currentPage)
                val latestTabWidthPx by rememberUpdatedState(tabWidthPx)
                val latestOnSelect by rememberUpdatedState(onSelect)
                val latestOnReselect by rememberUpdatedState(onReselect)

                val lensShape = RoundedCornerShape(24.dp)
                val restingFill = salt.accentSoftBg
                val liftedFill = if (isDark) colors.primary.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.62f)
                val currentFill = lerp(restingFill, liftedFill, safeLift)
                val rimBrush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = ((if (isDark) 0.44f else 0.96f) * (0.28f + 0.72f * safeLift)).coerceIn(0f, 1f)),
                        colors.primary.copy(alpha = (0.34f * safeLift).coerceIn(0f, 1f)),
                    ),
                )

                // Lifted Liquid-Glass Lens with top specular sheen, bottom inner bounce reflection & jelly deformation
                Box(
                    modifier = Modifier
                        .width(tabWidth)
                        .height(52.dp)
                        .graphicsLayer {
                            val j = jellyDeform.coerceIn(-0.20f, 0.38f)
                            val baseScaleX = 1f + 0.08f * liftProgress
                            val baseScaleY = 1f + 0.14f * liftProgress
                            translationX = lensPos * tabWidthPx
                            scaleX = baseScaleX * (1f + j)
                            scaleY = baseScaleY / sqrt((1f + j * 0.90f).coerceAtLeast(0.62f))
                        }
                        .shadow(
                            elevation = (10f * safeLift).dp,
                            shape = lensShape,
                            clip = false,
                            ambientColor = colors.primary.copy(alpha = (0.25f * safeLift).coerceIn(0f, 1f)),
                            spotColor = Color.Black.copy(alpha = if (isDark) 0.45f else 0.18f),
                        )
                        .clip(lensShape)
                        .background(currentFill)
                        .then(
                            if (!isDark && safeLift > 0.01f) {
                                Modifier.background(colors.primary.copy(alpha = (0.14f * safeLift).coerceIn(0f, 1f)))
                            } else {
                                Modifier
                            },
                        )
                        .border(1.dp, rimBrush, lensShape),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.52f)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = ((if (isDark) 0.28f else 0.86f) * (0.28f + 0.72f * safeLift)).coerceIn(0f, 1f)),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.32f)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        (if (isDark) Color.White else colors.primary).copy(
                                            alpha = (0.18f * (0.25f + 0.75f * safeLift)).coerceIn(0f, 1f),
                                        ),
                                    ),
                                ),
                            ),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .pointerInput(tabCount) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                isPressed = true
                                jellyVel -= 2.0f * latestLiquidDeform
                                val downX = down.position.x
                                val startTab = (downX.coerceIn(0f, latestTabWidthPx * tabCount) / latestTabWidthPx)
                                    .toInt()
                                    .coerceIn(0, tabCount - 1)
                                // Pressing on a non-active tab glides the lens smoothly to the finger instead of teleporting
                                targetPos = startTab.toFloat()
                                if (startTab != latestCurrentPage) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                var dragged = false
                                var lastHovered = startTab
                                var finishedNormally = false
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        if (!change.pressed) {
                                            finishedNormally = true
                                            break
                                        }
                                        val x = change.position.x
                                        if (!dragged && abs(x - downX) > viewConfiguration.touchSlop * 0.45f) {
                                            dragged = true
                                            isDragging = true
                                        }
                                        if (dragged) {
                                            change.consume()
                                            val maxPos = (tabCount - 1).toFloat()
                                            val rawPos = (x / latestTabWidthPx) - 0.5f
                                            val bandedPos = when {
                                                rawPos < 0f -> -ln(1f + (-rawPos) * 2.5f) * 0.16f
                                                rawPos > maxPos -> maxPos + ln(1f + (rawPos - maxPos) * 2.5f) * 0.16f
                                                else -> rawPos
                                            }
                                            targetPos = bandedPos
                                            val hovered = (x.coerceIn(0f, latestTabWidthPx * tabCount) / latestTabWidthPx)
                                                .toInt()
                                                .coerceIn(0, tabCount - 1)
                                            if (hovered != lastHovered) {
                                                lastHovered = hovered
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    }
                                    val finalTarget = if (finishedNormally) {
                                        if (dragged) lastHovered else startTab
                                    } else {
                                        if (dragged) lastHovered else latestCurrentPage
                                    }
                                    if (finishedNormally && !dragged) {
                                        jellyVel += 1.6f * latestLiquidDeform
                                    }
                                    targetPos = finalTarget.toFloat()
                                    if (finishedNormally) {
                                        if (dragged) {
                                            if (finalTarget != latestCurrentPage) {
                                                latestOnSelect(finalTarget)
                                            }
                                        } else {
                                            if (finalTarget == latestCurrentPage) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                latestOnReselect(finalTarget)
                                            } else {
                                                latestOnSelect(finalTarget)
                                            }
                                        }
                                    } else if (dragged && finalTarget != latestCurrentPage) {
                                        latestOnSelect(finalTarget)
                                    }
                                } finally {
                                    isPressed = false
                                    isDragging = false
                                }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    entries.forEachIndexed { index, entry ->
                        val isHighlighted = highlightedTab == index
                        val fg by animateColorAsState(
                            targetValue = if (isHighlighted) salt.accentSoftFg else colors.onSurfaceVariant,
                            animationSpec = tween(140),
                            label = "navFg$index",
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                modifier = Modifier.graphicsLayer {
                                    val proximity = (1f - abs(lensPos - index)).coerceIn(0f, 1f)
                                    val mag = 1f + (0.06f + 0.08f * liftProgress) * proximity
                                    scaleX = mag
                                    scaleY = mag
                                },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Box {
                                    Icon(
                                        painter = painterResource(entry.icon),
                                        contentDescription = entry.label,
                                        modifier = Modifier.size(20.dp),
                                        tint = fg,
                                    )
                                    if (index == 1 && activeDownloadCount > 0) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = 8.dp, y = (-4).dp)
                                                .defaultMinSize(minWidth = 15.dp, minHeight = 15.dp)
                                                .clip(CircleShape)
                                                .background(colors.primary)
                                                .padding(horizontal = 3.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = if (activeDownloadCount > 99) "99+" else activeDownloadCount.toString(),
                                                color = colors.onPrimary,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = entry.label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Medium,
                                    ),
                                    color = fg,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SaltBatchActionBar(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onBatchShare: () -> Unit,
    onBatchDelete: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    backdropLayer: GraphicsLayer? = null,
    backdropCoords: LayoutCoordinates? = null,
    observeBackdrop: () -> Unit = {},
) {
    val salt = saltColors()
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.5f
    val dockShape = RoundedCornerShape(30.dp)
    val dockBorderBrush = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (isDark) 0.26f else 0.94f),
            salt.subtleBorder.copy(alpha = if (isDark) 0.3f else 0.6f),
        ),
    )
    Box(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        SaltFrostedDockBackdrop(
            shape = dockShape,
            elevation = 14.dp,
            borderBrush = dockBorderBrush,
            isDark = isDark,
            backdropLayer = backdropLayer,
            backdropCoords = backdropCoords,
            observeBackdrop = observeBackdrop,
            modifier = Modifier.matchParentSize(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SaltIconButton(onClick = onToggleSelectAll, modifier = Modifier.size(38.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_select_all),
                    contentDescription = if (allSelected) "取消全选" else "全选",
                    modifier = Modifier.size(19.dp),
                    tint = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "已选 $selectedCount 项",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SaltTonalButton(
                enabled = selectedCount > 0,
                onClick = onBatchShare,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("分享", style = MaterialTheme.typography.labelMedium)
            }
            SaltTonalButton(
                enabled = selectedCount > 0,
                onClick = onBatchDelete,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = salt.errorBg,
                    contentColor = salt.error,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("删除", style = MaterialTheme.typography.labelMedium)
            }
            SaltTextButton(
                onClick = onExit,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Text("完成", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

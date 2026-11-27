package io.github.bilidownload

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

enum class AccentPreset(val key: String, val label: String, val swatchColor: Color) {
    Pink("Pink", "品牌粉", Color(0xFFD6409F)),
    Blue("Blue", "海盐蓝", Color(0xFF0090FF)),
    Jade("Jade", "翡翠绿", Color(0xFF29A383)),
    Amber("Amber", "琥珀金", Color(0xFFAB6400)),
    Dynamic("Dynamic", "动态取色", Color(0xFFD6409F));

    companion object {
        fun fromKey(key: String?): AccentPreset = entries.firstOrNull { it.key == key } ?: Pink
    }
}

@Immutable
data class SaltExtendedColors(
    val canvas: Color,
    val card: Color,
    val subCard: Color,
    val subtleBorder: Color,
    val accentSoftBg: Color,
    val accentSoftFg: Color,
    val success: Color,
    val successBg: Color,
    val warning: Color,
    val warningBg: Color,
    val error: Color,
    val errorBg: Color,
    val info: Color,
    val infoBg: Color,
)

private fun radixScheme(dark: Boolean, scale: String): ColorScheme {
    return if (!dark) when (scale) {
        "blue" -> lightColorScheme(
        primary = Color(0xFF0090FF),
        onPrimary = Color(0xFFFCFCFC),
        primaryContainer = Color(0xFFC2E5FF),
        onPrimaryContainer = Color(0xFF0D74CE),
        inversePrimary = Color(0xFF0D74CE),
        secondary = Color(0xFF65636D),
        onSecondary = Color(0xFFFCFCFC),
        secondaryContainer = Color(0xFFE3DFE6),
        onSecondaryContainer = Color(0xFF211F26),
        tertiary = Color(0xFF0D74CE),
        onTertiary = Color(0xFFFCFCFC),
        tertiaryContainer = Color(0xFFE6F4FE),
        onTertiaryContainer = Color(0xFF0D74CE),
        background = Color(0xFFF2EFF3),
        onBackground = Color(0xFF211F26),
        surface = Color(0xFFFDFCFD),
        onSurface = Color(0xFF211F26),
        surfaceVariant = Color(0xFFF2EFF3),
        onSurfaceVariant = Color(0xFF65636D),
        surfaceTint = Color(0xFFFDFCFD),
        inverseSurface = Color(0xFF211F26),
        inverseOnSurface = Color(0xFFF2EFF3),
        outline = Color(0xFFD0CDD7),
        outlineVariant = Color(0xFFE3DFE6),
        error = Color(0xFFCA244D),
        onError = Color(0xFFFCFCFC),
        errorContainer = Color(0xFFFEEAED),
        onErrorContainer = Color(0xFFCA244D),
        surfaceBright = Color(0xFFFDFCFD),
        surfaceDim = Color(0xFFFAF9FB),
        surfaceContainer = Color(0xFFFAF9FB),
        surfaceContainerHigh = Color(0xFFF2EFF3),
        surfaceContainerHighest = Color(0xFFEAE7EC),
        surfaceContainerLow = Color(0xFFFAF9FB),
        surfaceContainerLowest = Color(0xFFFDFCFD),
        scrim = Color(0xFF211F26),
        )
        "jade" -> lightColorScheme(
        primary = Color(0xFF29A383),
        onPrimary = Color(0xFFFCFCFC),
        primaryContainer = Color(0xFFC3E9D7),
        onPrimaryContainer = Color(0xFF208368),
        inversePrimary = Color(0xFF208368),
        secondary = Color(0xFF65636D),
        onSecondary = Color(0xFFFCFCFC),
        secondaryContainer = Color(0xFFE3DFE6),
        onSecondaryContainer = Color(0xFF211F26),
        tertiary = Color(0xFF208368),
        onTertiary = Color(0xFFFCFCFC),
        tertiaryContainer = Color(0xFFE6F7ED),
        onTertiaryContainer = Color(0xFF208368),
        background = Color(0xFFF2EFF3),
        onBackground = Color(0xFF211F26),
        surface = Color(0xFFFDFCFD),
        onSurface = Color(0xFF211F26),
        surfaceVariant = Color(0xFFF2EFF3),
        onSurfaceVariant = Color(0xFF65636D),
        surfaceTint = Color(0xFFFDFCFD),
        inverseSurface = Color(0xFF211F26),
        inverseOnSurface = Color(0xFFF2EFF3),
        outline = Color(0xFFD0CDD7),
        outlineVariant = Color(0xFFE3DFE6),
        error = Color(0xFFCA244D),
        onError = Color(0xFFFCFCFC),
        errorContainer = Color(0xFFFEEAED),
        onErrorContainer = Color(0xFFCA244D),
        surfaceBright = Color(0xFFFDFCFD),
        surfaceDim = Color(0xFFFAF9FB),
        surfaceContainer = Color(0xFFFAF9FB),
        surfaceContainerHigh = Color(0xFFF2EFF3),
        surfaceContainerHighest = Color(0xFFEAE7EC),
        surfaceContainerLow = Color(0xFFFAF9FB),
        surfaceContainerLowest = Color(0xFFFDFCFD),
        scrim = Color(0xFF211F26),
        )
        "amber" -> lightColorScheme(
        primary = Color(0xFFAB6400),
        onPrimary = Color(0xFFFCFCFC),
        primaryContainer = Color(0xFFFBE577),
        onPrimaryContainer = Color(0xFFAB6400),
        inversePrimary = Color(0xFFAB6400),
        secondary = Color(0xFF65636D),
        onSecondary = Color(0xFFFCFCFC),
        secondaryContainer = Color(0xFFE3DFE6),
        onSecondaryContainer = Color(0xFF211F26),
        tertiary = Color(0xFFAB6400),
        onTertiary = Color(0xFFFCFCFC),
        tertiaryContainer = Color(0xFFFFF7C2),
        onTertiaryContainer = Color(0xFFAB6400),
        background = Color(0xFFF2EFF3),
        onBackground = Color(0xFF211F26),
        surface = Color(0xFFFDFCFD),
        onSurface = Color(0xFF211F26),
        surfaceVariant = Color(0xFFF2EFF3),
        onSurfaceVariant = Color(0xFF65636D),
        surfaceTint = Color(0xFFFDFCFD),
        inverseSurface = Color(0xFF211F26),
        inverseOnSurface = Color(0xFFF2EFF3),
        outline = Color(0xFFD0CDD7),
        outlineVariant = Color(0xFFE3DFE6),
        error = Color(0xFFCA244D),
        onError = Color(0xFFFCFCFC),
        errorContainer = Color(0xFFFEEAED),
        onErrorContainer = Color(0xFFCA244D),
        surfaceBright = Color(0xFFFDFCFD),
        surfaceDim = Color(0xFFFAF9FB),
        surfaceContainer = Color(0xFFFAF9FB),
        surfaceContainerHigh = Color(0xFFF2EFF3),
        surfaceContainerHighest = Color(0xFFEAE7EC),
        surfaceContainerLow = Color(0xFFFAF9FB),
        surfaceContainerLowest = Color(0xFFFDFCFD),
        scrim = Color(0xFF211F26),
        )
        else -> lightColorScheme(
        primary = Color(0xFFD6409F),
        onPrimary = Color(0xFFFCFCFC),
        primaryContainer = Color(0xFFF6CEE7),
        onPrimaryContainer = Color(0xFFC2298A),
        inversePrimary = Color(0xFFC2298A),
        secondary = Color(0xFF65636D),
        onSecondary = Color(0xFFFCFCFC),
        secondaryContainer = Color(0xFFE3DFE6),
        onSecondaryContainer = Color(0xFF211F26),
        tertiary = Color(0xFFC2298A),
        onTertiary = Color(0xFFFCFCFC),
        tertiaryContainer = Color(0xFFFEE9F5),
        onTertiaryContainer = Color(0xFFC2298A),
        background = Color(0xFFF2EFF3),
        onBackground = Color(0xFF211F26),
        surface = Color(0xFFFDFCFD),
        onSurface = Color(0xFF211F26),
        surfaceVariant = Color(0xFFF2EFF3),
        onSurfaceVariant = Color(0xFF65636D),
        surfaceTint = Color(0xFFFDFCFD),
        inverseSurface = Color(0xFF211F26),
        inverseOnSurface = Color(0xFFF2EFF3),
        outline = Color(0xFFD0CDD7),
        outlineVariant = Color(0xFFE3DFE6),
        error = Color(0xFFCA244D),
        onError = Color(0xFFFCFCFC),
        errorContainer = Color(0xFFFEEAED),
        onErrorContainer = Color(0xFFCA244D),
        surfaceBright = Color(0xFFFDFCFD),
        surfaceDim = Color(0xFFFAF9FB),
        surfaceContainer = Color(0xFFFAF9FB),
        surfaceContainerHigh = Color(0xFFF2EFF3),
        surfaceContainerHighest = Color(0xFFEAE7EC),
        surfaceContainerLow = Color(0xFFFAF9FB),
        surfaceContainerLowest = Color(0xFFFDFCFD),
        scrim = Color(0xFF211F26),
        )
    } else when (scale) {
        "blue" -> darkColorScheme(
        primary = Color(0xFF0090FF),
        onPrimary = Color(0xFFFCFCFC),
        primaryContainer = Color(0xFF003362),
        onPrimaryContainer = Color(0xFF70B8FF),
        inversePrimary = Color(0xFF70B8FF),
        secondary = Color(0xFFB5B2BC),
        onSecondary = Color(0xFFFCFCFC),
        secondaryContainer = Color(0xFF323035),
        onSecondaryContainer = Color(0xFFEEEEF0),
        tertiary = Color(0xFF70B8FF),
        onTertiary = Color(0xFFFCFCFC),
        tertiaryContainer = Color(0xFF0D2847),
        onTertiaryContainer = Color(0xFF70B8FF),
        background = Color(0xFF121113),
        onBackground = Color(0xFFEEEEF0),
        surface = Color(0xFF1A191B),
        onSurface = Color(0xFFEEEEF0),
        surfaceVariant = Color(0xFF232225),
        onSurfaceVariant = Color(0xFFB5B2BC),
        surfaceTint = Color(0xFF1A191B),
        inverseSurface = Color(0xFFEEEEF0),
        inverseOnSurface = Color(0xFF121113),
        outline = Color(0xFF49474E),
        outlineVariant = Color(0xFF323035),
        error = Color(0xFFFF949D),
        onError = Color(0xFFFCFCFC),
        errorContainer = Color(0xFF3A141E),
        onErrorContainer = Color(0xFFFF949D),
        surfaceBright = Color(0xFF1A191B),
        surfaceDim = Color(0xFF232225),
        surfaceContainer = Color(0xFF1A191B),
        surfaceContainerHigh = Color(0xFF232225),
        surfaceContainerHighest = Color(0xFF2B292D),
        surfaceContainerLow = Color(0xFF232225),
        surfaceContainerLowest = Color(0xFF1A191B),
        scrim = Color(0xFFEEEEF0),
        )
        "jade" -> darkColorScheme(
        primary = Color(0xFF29A383),
        onPrimary = Color(0xFFFCFCFC),
        primaryContainer = Color(0xFF0B3B2C),
        onPrimaryContainer = Color(0xFF1FD8A4),
        inversePrimary = Color(0xFF1FD8A4),
        secondary = Color(0xFFB5B2BC),
        onSecondary = Color(0xFFFCFCFC),
        secondaryContainer = Color(0xFF323035),
        onSecondaryContainer = Color(0xFFEEEEF0),
        tertiary = Color(0xFF1FD8A4),
        onTertiary = Color(0xFFFCFCFC),
        tertiaryContainer = Color(0xFF0F2E22),
        onTertiaryContainer = Color(0xFF1FD8A4),
        background = Color(0xFF121113),
        onBackground = Color(0xFFEEEEF0),
        surface = Color(0xFF1A191B),
        onSurface = Color(0xFFEEEEF0),
        surfaceVariant = Color(0xFF232225),
        onSurfaceVariant = Color(0xFFB5B2BC),
        surfaceTint = Color(0xFF1A191B),
        inverseSurface = Color(0xFFEEEEF0),
        inverseOnSurface = Color(0xFF121113),
        outline = Color(0xFF49474E),
        outlineVariant = Color(0xFF323035),
        error = Color(0xFFFF949D),
        onError = Color(0xFFFCFCFC),
        errorContainer = Color(0xFF3A141E),
        onErrorContainer = Color(0xFFFF949D),
        surfaceBright = Color(0xFF1A191B),
        surfaceDim = Color(0xFF232225),
        surfaceContainer = Color(0xFF1A191B),
        surfaceContainerHigh = Color(0xFF232225),
        surfaceContainerHighest = Color(0xFF2B292D),
        surfaceContainerLow = Color(0xFF232225),
        surfaceContainerLowest = Color(0xFF1A191B),
        scrim = Color(0xFFEEEEF0),
        )
        "amber" -> darkColorScheme(
        primary = Color(0xFFFFC53D),
        onPrimary = Color(0xFFEEEEF0),
        primaryContainer = Color(0xFF3F2700),
        onPrimaryContainer = Color(0xFFFFCA16),
        inversePrimary = Color(0xFFFFCA16),
        secondary = Color(0xFFB5B2BC),
        onSecondary = Color(0xFFFCFCFC),
        secondaryContainer = Color(0xFF323035),
        onSecondaryContainer = Color(0xFFEEEEF0),
        tertiary = Color(0xFFFFCA16),
        onTertiary = Color(0xFFFCFCFC),
        tertiaryContainer = Color(0xFF302008),
        onTertiaryContainer = Color(0xFFFFCA16),
        background = Color(0xFF121113),
        onBackground = Color(0xFFEEEEF0),
        surface = Color(0xFF1A191B),
        onSurface = Color(0xFFEEEEF0),
        surfaceVariant = Color(0xFF232225),
        onSurfaceVariant = Color(0xFFB5B2BC),
        surfaceTint = Color(0xFF1A191B),
        inverseSurface = Color(0xFFEEEEF0),
        inverseOnSurface = Color(0xFF121113),
        outline = Color(0xFF49474E),
        outlineVariant = Color(0xFF323035),
        error = Color(0xFFFF949D),
        onError = Color(0xFFFCFCFC),
        errorContainer = Color(0xFF3A141E),
        onErrorContainer = Color(0xFFFF949D),
        surfaceBright = Color(0xFF1A191B),
        surfaceDim = Color(0xFF232225),
        surfaceContainer = Color(0xFF1A191B),
        surfaceContainerHigh = Color(0xFF232225),
        surfaceContainerHighest = Color(0xFF2B292D),
        surfaceContainerLow = Color(0xFF232225),
        surfaceContainerLowest = Color(0xFF1A191B),
        scrim = Color(0xFFEEEEF0),
        )
        else -> darkColorScheme(
        primary = Color(0xFFD6409F),
        onPrimary = Color(0xFFFCFCFC),
        primaryContainer = Color(0xFF4B143D),
        onPrimaryContainer = Color(0xFFFF8DCC),
        inversePrimary = Color(0xFFFF8DCC),
        secondary = Color(0xFFB5B2BC),
        onSecondary = Color(0xFFFCFCFC),
        secondaryContainer = Color(0xFF323035),
        onSecondaryContainer = Color(0xFFEEEEF0),
        tertiary = Color(0xFFFF8DCC),
        onTertiary = Color(0xFFFCFCFC),
        tertiaryContainer = Color(0xFF37172F),
        onTertiaryContainer = Color(0xFFFF8DCC),
        background = Color(0xFF121113),
        onBackground = Color(0xFFEEEEF0),
        surface = Color(0xFF1A191B),
        onSurface = Color(0xFFEEEEF0),
        surfaceVariant = Color(0xFF232225),
        onSurfaceVariant = Color(0xFFB5B2BC),
        surfaceTint = Color(0xFF1A191B),
        inverseSurface = Color(0xFFEEEEF0),
        inverseOnSurface = Color(0xFF121113),
        outline = Color(0xFF49474E),
        outlineVariant = Color(0xFF323035),
        error = Color(0xFFFF949D),
        onError = Color(0xFFFCFCFC),
        errorContainer = Color(0xFF3A141E),
        onErrorContainer = Color(0xFFFF949D),
        surfaceBright = Color(0xFF1A191B),
        surfaceDim = Color(0xFF232225),
        surfaceContainer = Color(0xFF1A191B),
        surfaceContainerHigh = Color(0xFF232225),
        surfaceContainerHighest = Color(0xFF2B292D),
        surfaceContainerLow = Color(0xFF232225),
        surfaceContainerLowest = Color(0xFF1A191B),
        scrim = Color(0xFFEEEEF0),
        )
    }
}

private fun buildSaltColors(dark: Boolean, scheme: ColorScheme, scale: String): SaltExtendedColors {
    val canvas = if (dark) Color(0xFF121113) else Color(0xFFF2EFF3)
    val card = if (dark) Color(0xFF1A191B) else Color(0xFFFDFCFD)
    val subCard = if (dark) Color(0xFF232225) else Color(0xFFFAF9FB)
    val subtleBorder = if (dark) Color(0xFF323035) else Color(0xFFE3DFE6)
    val accentSoftBg = if (scale == "dynamic") scheme.primaryContainer.copy(alpha = 0.55f) else when (scale) {
        "blue" -> if (dark) Color(0xFF0D2847) else Color(0xFFE6F4FE)
        "jade" -> if (dark) Color(0xFF0F2E22) else Color(0xFFE6F7ED)
        "amber" -> if (dark) Color(0xFF302008) else Color(0xFFFFF7C2)
        else -> if (dark) Color(0xFF37172F) else Color(0xFFFEE9F5)
    }
    val accentSoftFg = if (scale == "dynamic") scheme.onPrimaryContainer else when (scale) {
        "blue" -> if (dark) Color(0xFF70B8FF) else Color(0xFF0D74CE)
        "jade" -> if (dark) Color(0xFF1FD8A4) else Color(0xFF208368)
        "amber" -> if (dark) Color(0xFFFFCA16) else Color(0xFFAB6400)
        else -> if (dark) Color(0xFFFF8DCC) else Color(0xFFC2298A)
    }
    return SaltExtendedColors(
        canvas = canvas,
        card = card,
        subCard = subCard,
        subtleBorder = subtleBorder,
        accentSoftBg = accentSoftBg,
        accentSoftFg = accentSoftFg,
        success = if (dark) Color(0xFF1FD8A4) else Color(0xFF208368),
        successBg = if (dark) Color(0xFF0F2E22) else Color(0xFFE6F7ED),
        warning = if (dark) Color(0xFFFFCA16) else Color(0xFFAB6400),
        warningBg = if (dark) Color(0xFF302008) else Color(0xFFFFF7C2),
        error = if (dark) Color(0xFFFF949D) else Color(0xFFCA244D),
        errorBg = if (dark) Color(0xFF3A141E) else Color(0xFFFEEAED),
        info = if (dark) Color(0xFF70B8FF) else Color(0xFF0D74CE),
        infoBg = if (dark) Color(0xFF0D2847) else Color(0xFFE6F4FE),
    )
}

private val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
private val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
private val titleLargeStyle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
private val headlineStyle = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
private val titleMediumStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
private val typography = Typography(
    displayLarge = headlineStyle, displayMedium = headlineStyle, displaySmall = headlineStyle,
    headlineLarge = headlineStyle, headlineMedium = headlineStyle, headlineSmall = headlineStyle,
    titleLarge = titleLargeStyle, titleMedium = titleMediumStyle, titleSmall = body.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = body, bodyMedium = body, bodySmall = label,
    labelLarge = body.copy(fontWeight = FontWeight.Medium), labelMedium = label, labelSmall = label,
)

private val LocalFocus = staticCompositionLocalOf { Color(0xFFDD93C2) }
private val LocalSaltColors = staticCompositionLocalOf {
    buildSaltColors(false, radixScheme(false, "pink"), "pink")
}

@Composable fun radixFocusColor() = LocalFocus.current
@Composable fun saltColors() = LocalSaltColors.current

private class SaltHighlightIndication(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        SaltHighlightNode(interactionSource, color)

    override fun equals(other: Any?): Boolean =
        this === other || (other is SaltHighlightIndication && color == other.color)

    override fun hashCode(): Int = color.hashCode()

    private class SaltHighlightNode(
        private val interactionSource: InteractionSource,
        private val color: Color,
    ) : Modifier.Node(), DrawModifierNode {
        private val alphaAnim = Animatable(0f)
        private var pressCount = 0

        override fun onAttach() {
            coroutineScope.launch {
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> {
                            pressCount++
                            launch {
                                alphaAnim.animateTo(1f, tween(70)) { invalidateDraw() }
                            }
                        }
                        is PressInteraction.Release, is PressInteraction.Cancel -> {
                            pressCount = (pressCount - 1).coerceAtLeast(0)
                            if (pressCount == 0) {
                                launch {
                                    alphaAnim.animateTo(0f, tween(180)) { invalidateDraw() }
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            val progress = alphaAnim.value
            if (progress > 0.001f) {
                drawRect(color = color.copy(alpha = color.alpha * progress))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BiliTheme(dark: Boolean, accent: String = "Pink", content: @Composable () -> Unit) {
    val preset = AccentPreset.fromKey(accent)
    val scale = preset.key.lowercase()
    val context = LocalContext.current
    val useDynamic = preset == AccentPreset.Dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = if (useDynamic) {
        val base = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        base.copy(
            background = if (dark) Color(0xFF121113) else Color(0xFFF2EFF3),
            surface = if (dark) Color(0xFF1A191B) else Color(0xFFFDFCFD),
        )
    } else radixScheme(dark, scale)
    val focusColor = if (useDynamic) scheme.primary else when (scale) {
        "blue" -> if (dark) Color(0xFF2870BD) else Color(0xFF5EB1EF)
        "jade" -> if (dark) Color(0xFF2A7E68) else Color(0xFF56BA9F)
        "amber" -> if (dark) Color(0xFF8F6424) else Color(0xFFE2A336)
        else -> if (dark) Color(0xFFA84885) else Color(0xFFDD93C2)
    }
    val salt = buildSaltColors(dark, scheme, scale)
    val highlightIndication = remember(dark) {
        SaltHighlightIndication(
            if (dark) Color.White.copy(alpha = 0.065f) else Color.Black.copy(alpha = 0.045f),
        )
    }
    CompositionLocalProvider(LocalFocus provides focusColor, LocalSaltColors provides salt) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(14.dp),
                large = RoundedCornerShape(18.dp),
                extraLarge = RoundedCornerShape(24.dp),
            ),
        ) {
            CompositionLocalProvider(
                LocalRippleConfiguration provides null,
                LocalIndication provides highlightIndication,
            ) {
                content()
            }
        }
    }
}

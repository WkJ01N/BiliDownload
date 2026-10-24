package io.github.bilidownload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private fun radixScheme(dark: Boolean): ColorScheme {
    return if(!dark) lightColorScheme(
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
        background = Color(0xFFFDFCFD),
        onBackground = Color(0xFF211F26),
        surface = Color(0xFFFDFCFD),
        onSurface = Color(0xFF211F26),
        surfaceVariant = Color(0xFFF2EFF3),
        onSurfaceVariant = Color(0xFF65636D),
        surfaceTint = Color(0xFFFDFCFD),
        inverseSurface = Color(0xFF211F26),
        inverseOnSurface = Color(0xFFFDFCFD),
        outline = Color(0xFFD0CDD7),
        outlineVariant = Color(0xFFDBD8E0),
        error = Color(0xFFCA244D),
        onError = Color(0xFFFCFCFC),
        errorContainer = Color(0xFFFEEAED),
        onErrorContainer = Color(0xFFCA244D),
        surfaceBright = Color(0xFFFAF9FB),
        surfaceDim = Color(0xFFFAF9FB),
        surfaceContainer = Color(0xFFFAF9FB),
        surfaceContainerHigh = Color(0xFFF2EFF3),
        surfaceContainerHighest = Color(0xFFEAE7EC),
        surfaceContainerLow = Color(0xFFFAF9FB),
        surfaceContainerLowest = Color(0xFFFDFCFD),
        scrim = Color(0xFF211F26),
    ) else darkColorScheme(
        primary = Color(0xFFD6409F),
        onPrimary = Color(0xFFFCFCFC),
        primaryContainer = Color(0xFF591C47),
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
        surface = Color(0xFF121113),
        onSurface = Color(0xFFEEEEF0),
        surfaceVariant = Color(0xFF232225),
        onSurfaceVariant = Color(0xFFB5B2BC),
        surfaceTint = Color(0xFF121113),
        inverseSurface = Color(0xFFEEEEF0),
        inverseOnSurface = Color(0xFF121113),
        outline = Color(0xFF49474E),
        outlineVariant = Color(0xFF3C393F),
        error = Color(0xFFFF949D),
        onError = Color(0xFFFCFCFC),
        errorContainer = Color(0xFF3A141E),
        onErrorContainer = Color(0xFFFF949D),
        surfaceBright = Color(0xFF1A191B),
        surfaceDim = Color(0xFF1A191B),
        surfaceContainer = Color(0xFF1A191B),
        surfaceContainerHigh = Color(0xFF232225),
        surfaceContainerHighest = Color(0xFF2B292D),
        surfaceContainerLow = Color(0xFF1A191B),
        surfaceContainerLowest = Color(0xFF121113),
        scrim = Color(0xFFEEEEF0),
    )
}
private val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
private val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
private val title = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
private val typography = Typography(
    displayLarge = title, displayMedium = title, displaySmall = title, headlineLarge = title, headlineMedium = title, headlineSmall = title,
    titleLarge = title, titleMedium = body.copy(fontWeight = FontWeight.SemiBold), titleSmall = body.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = body, bodyMedium = body, bodySmall = label, labelLarge = body.copy(fontWeight = FontWeight.Medium), labelMedium = label, labelSmall = label)
private val LocalFocus = staticCompositionLocalOf { Color(0xFFDD93C2) }
@Composable fun radixFocusColor() = LocalFocus.current
@Composable fun BiliTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFocus provides if(dark) Color(0xFFA84885) else Color(0xFFDD93C2)) { MaterialTheme(colorScheme = radixScheme(dark), typography = typography, shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(4.dp), large = RoundedCornerShape(8.dp), extraLarge = RoundedCornerShape(8.dp)), content = content) }
}

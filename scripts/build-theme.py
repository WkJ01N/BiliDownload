"""Generate native theme tokens from the pinned official Radix scales."""
from pathlib import Path
import json

root = Path(__file__).resolve().parent.parent
palette = json.loads((root / 'branding/radix-colors.json').read_text())
aliases = json.loads((root / 'branding/winui-theme-aliases.json').read_text())
ns = 'http://schemas.microsoft.com/winfx/2006/xaml/presentation'


def build_winui_tokens(mode: str) -> dict[str, tuple[str, int]]:
    is_light = mode == 'light'
    canvas_step = 3 if is_light else 1
    card_step = 1 if is_light else 2
    subcard_step = 2 if is_light else 3
    pane_step = 2
    border_step = 5

    tokens = {
        'ApplicationPageBackgroundThemeBrush': ('mauve', canvas_step),
        'LayerFillColorDefaultBrush': ('mauve', canvas_step),
        'LayerFillColorAltBrush': ('mauve', pane_step),
        'CardBackgroundFillColorDefaultBrush': ('mauve', card_step),
        'CardBackgroundFillColorSecondaryBrush': ('mauve', subcard_step),
        'CardStrokeColorDefaultBrush': ('mauve', border_step),
        'CardStrokeColorDefaultSolidBrush': ('mauve', border_step),
        'TextFillColorPrimaryBrush': ('mauve', 12),
        'TextFillColorSecondaryBrush': ('mauve', 11),
        'TextFillColorTertiaryBrush': ('mauve', 11),
        'TextFillColorDisabledBrush': ('mauve', 8),
        'TextFillColorInverseBrush': ('gray', 1),
        'ControlFillColorDefaultBrush': ('mauve', 3),
        'ControlFillColorSecondaryBrush': ('mauve', 4),
        'ControlFillColorTertiaryBrush': ('mauve', 5),
        'ControlFillColorDisabledBrush': ('mauve', 3),
        'ControlFillColorInputActiveBrush': ('mauve', card_step if is_light else 3),
        'ControlSolidFillColorDefaultBrush': ('mauve', card_step),
        'ControlStrongFillColorDefaultBrush': ('mauve', 11),
        'ControlStrongFillColorDisabledBrush': ('mauve', 7),
        'ControlStrokeColorDefaultBrush': ('mauve', 7 if is_light else 6),
        'ControlStrokeColorSecondaryBrush': ('mauve', 8 if is_light else 7),
        'ControlStrokeColorOnAccentDefaultBrush': ('pink', 9),
        'ControlStrokeColorOnAccentSecondaryBrush': ('pink', 10),
        'ControlStrokeColorForStrongFillWhenOnImageBrush': ('mauve', 7),
        'ControlStrongStrokeColorDefaultBrush': ('mauve', 8),
        'ControlStrongStrokeColorDisabledBrush': ('mauve', 6),
        'DividerStrokeColorDefaultBrush': ('mauve', border_step),
        'SurfaceStrokeColorDefaultBrush': ('mauve', border_step),
        'SurfaceStrokeColorFlyoutBrush': ('mauve', border_step),
        'SolidBackgroundFillColorBaseBrush': ('mauve', canvas_step),
        'SolidBackgroundFillColorSecondaryBrush': ('mauve', pane_step),
        'SolidBackgroundFillColorTertiaryBrush': ('mauve', 3),
        'SolidBackgroundFillColorQuarternaryBrush': ('mauve', card_step if is_light else 3),
        'AcrylicBackgroundFillColorDefaultBrush': ('mauve', pane_step),
        'AcrylicBackgroundFillColorBaseBrush': ('mauve', pane_step),
        'AccentFillColorDefaultBrush': ('pink', 9),
        'AccentFillColorSecondaryBrush': ('pink', 10),
        'AccentFillColorTertiaryBrush': ('pink', 10),
        'AccentFillColorDisabledBrush': ('mauve', 5),
        'AccentTextFillColorPrimaryBrush': ('pink', 11),
        'AccentTextFillColorSecondaryBrush': ('pink', 11),
        'AccentTextFillColorTertiaryBrush': ('pink', 11),
        'AccentTextFillColorDisabledBrush': ('mauve', 8),
        'TextOnAccentFillColorPrimaryBrush': ('gray', 1),
        'TextOnAccentFillColorSecondaryBrush': ('gray', 1),
        'TextOnAccentFillColorDisabledBrush': ('mauve', 8),
        'NavigationViewContentBackground': ('mauve', canvas_step),
        'NavigationViewDefaultPaneBackground': ('mauve', pane_step),
        'NavigationViewExpandedPaneBackground': ('mauve', pane_step),
        'NavigationViewTopPaneBackground': ('mauve', pane_step),
        'NavigationViewItemBackgroundSelected': ('pink', 4 if is_light else 3),
        'NavigationViewItemBackgroundSelectedPointerOver': ('pink', 5 if is_light else 4),
        'NavigationViewItemForegroundSelected': ('pink', 11),
        'NavigationViewItemBackgroundPointerOver': ('mauve', 4),
        'NavigationViewItemBackgroundPressed': ('mauve', 5),
        'RadixSecondaryTextBrush': ('mauve', 11),
        # Semantic SaltUI × FlClash surface & accent tokens
        'SaltCanvasBackgroundBrush': ('mauve', canvas_step),
        'SaltCardBackgroundBrush': ('mauve', card_step),
        'SaltSubCardBackgroundBrush': ('mauve', subcard_step),
        'SaltAccentSoftBackgroundBrush': ('pink', 3),
        'SaltAccentSoftForegroundBrush': ('pink', 11),
    }
    for kind, scale in [('Success', 'jade'), ('Caution', 'amber'), ('Critical', 'ruby'), ('Attention', 'blue'), ('Neutral', 'mauve')]:
        tokens['SystemFillColor' + kind + 'Brush'] = (scale, 11)
        tokens['SystemFillColor' + kind + 'BackgroundBrush'] = (scale, 3)
        tokens['SystemFillColorSolid' + kind + 'BackgroundBrush'] = (scale, 3)

    tokens.update({
        'AccentFillColorSelectedTextBackgroundBrush': ('pink', 5),
        'FocusStrokeColorOuterBrush': ('mauve', 7),
        'FocusStrokeColorInnerBrush': ('pink', 8),
        'ProgressBarForeground': ('pink', 9),
        'ProgressBarBackground': ('mauve', 4),
        'NavigationViewSelectionIndicatorForeground': ('pink', 9),
    })

    for _ in range(10):
        for name, source in aliases.items():
            if source in tokens and name not in tokens:
                tokens[name] = tokens[source]
    return tokens


out = [f'<ResourceDictionary xmlns="{ns}" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">', ' <ResourceDictionary.ThemeDictionaries>']
for mode, key in [('light', 'Light'), ('dark', 'Default')]:
    p = palette[mode]
    tokens = build_winui_tokens(mode)

    def color(scale: str, step: int) -> str:
        return palette['light']['gray'][0] if scale == 'gray' and step == 1 else p[scale][step - 1]

    out.append(f'  <ResourceDictionary x:Key="{key}">')
    for name, (scale, step) in tokens.items():
        out.append(f'   <SolidColorBrush x:Key="{name}" Color="{color(scale, step)}"/>')
        if name.endswith('Brush') and name[:-5] not in tokens:
            out.append(f'   <Color x:Key="{name[:-5]}">{color(scale, step)}</Color>')
    for name in ['SystemAccentColor', 'SystemAccentColorLight1', 'SystemAccentColorLight2', 'SystemAccentColorLight3', 'SystemAccentColorDark1', 'SystemAccentColorDark2', 'SystemAccentColorDark3']:
        out.append(f'   <Color x:Key="{name}">{p["pink"][8]}</Color>')
    out.append('  </ResourceDictionary>')

out.append(' </ResourceDictionary.ThemeDictionaries>')
for name, radius in [
    ('NavigationViewContentGridCornerRadius', 12),
    ('NavigationViewMinimalContentGridCornerRadius', 0),
    ('TopNavigationViewContentGridCornerRadius', 0),
]:
    out.append(f' <CornerRadius x:Key="{name}">{radius}</CornerRadius>')
for name in ['NavigationViewContentGridBorderThickness', 'NavigationViewMinimalContentGridBorderThickness', 'TopNavigationViewContentGridBorderThickness', 'ExpanderBorderThickness', 'ExpanderHeaderBorderThickness', 'ExpanderChevronBorderThickness']:
    out.append(f' <Thickness x:Key="{name}">1</Thickness>')
for name in ['ButtonBorderThemeThickness', 'ComboBoxBorderThemeThickness', 'ComboBoxBackgroundBorderThickness', 'ComboBoxBackgroundBorderThicknessFocused', 'ComboBoxDropdownBorderThickness', 'TextControlBorderThemeThickness', 'TextControlBorderThemeThicknessFocused', 'TextControlBorderThemeThicknessUnfocused', 'InfoBarBorderThickness', 'CommandBarOverflowPresenterBorderThickness', 'ContentDialogBorderThemeThickness', 'CheckBoxBorderThickness']:
    out.append(f' <Thickness x:Key="{name}">1</Thickness>')
for name, value in [('ControlCornerRadius', 10), ('OverlayCornerRadius', 14), ('ListViewItemCornerRadius', 10), ('ComboBoxItemCornerRadius', 10)]:
    out.append(f' <CornerRadius x:Key="{name}">{value}</CornerRadius>')
for name, size in [('CheckBoxBorderThemeThickness', 1), ('RadioButtonBorderThemeThickness', 1), ('ControlContentThemeFontSize', 14), ('ContentControlFontSize', 14), ('ContentDialogTitleFontSize', 20), ('SubtitleTextBlockFontSize', 15), ('TitleTextBlockFontSize', 24)]:
    out.append(f' <x:Double x:Key="{name}">{size}</x:Double>')
for name, size, weight in [('TitleTextBlockStyle', 24, 'SemiBold'), ('SubtitleTextBlockStyle', 15, 'SemiBold'), ('BodyTextBlockStyle', 14, 'Normal'), ('CaptionTextBlockStyle', 12, 'Normal')]:
    out.append(f' <Style x:Key="{name}" TargetType="TextBlock"><Setter Property="FontSize" Value="{size}"/><Setter Property="FontWeight" Value="{weight}"/><Setter Property="Foreground" Value="{{ThemeResource TextFillColorPrimaryBrush}}"/></Style>')
for target in ['Button', 'TextBox', 'ComboBox']:
    out.append(f' <Style TargetType="{target}" BasedOn="{{StaticResource Default{target}Style}}"><Setter Property="FontSize" Value="14"/><Setter Property="BorderThickness" Value="1"/><Setter Property="CornerRadius" Value="10"/></Style>')
out.append('</ResourceDictionary>')
(root / 'src/BiliDownload.Windows/RadixTheme.xaml').write_text('\n'.join(out) + '\n', encoding='utf-8')


def hex_kt(val: str) -> str:
    return f'Color(0xFF{val[1:].upper()})'


def build_kt_scheme(mode: str, scale: str) -> list[str]:
    p = palette[mode]
    is_light = mode == 'light'
    canvas = p['mauve'][2 if is_light else 0]
    card = p['mauve'][0 if is_light else 1]
    subcard = p['mauve'][1 if is_light else 2]
    if scale == 'amber':
        primary = p['amber'][10 if is_light else 8]
        on_primary = palette['light']['gray'][0] if is_light else p['mauve'][11]
    else:
        primary = p[scale][8]
        on_primary = palette['light']['gray'][0]

    roles = {
        'primary': primary,
        'onPrimary': on_primary,
        'primaryContainer': p[scale][4 if is_light else 3],
        'onPrimaryContainer': p[scale][10],
        'inversePrimary': p[scale][10],
        'secondary': p['mauve'][10],
        'onSecondary': palette['light']['gray'][0],
        'secondaryContainer': p['mauve'][4],
        'onSecondaryContainer': p['mauve'][11],
        'tertiary': p[scale][10],
        'onTertiary': palette['light']['gray'][0],
        'tertiaryContainer': p[scale][2],
        'onTertiaryContainer': p[scale][10],
        'background': canvas,
        'onBackground': p['mauve'][11],
        'surface': card,
        'onSurface': p['mauve'][11],
        'surfaceVariant': p['mauve'][2],
        'onSurfaceVariant': p['mauve'][10],
        'surfaceTint': card,
        'inverseSurface': p['mauve'][11],
        'inverseOnSurface': canvas,
        'outline': p['mauve'][6],
        'outlineVariant': p['mauve'][4],
        'error': p['ruby'][10],
        'onError': palette['light']['gray'][0],
        'errorContainer': p['ruby'][2],
        'onErrorContainer': p['ruby'][10],
        'surfaceBright': card,
        'surfaceDim': subcard,
        'surfaceContainer': subcard if is_light else card,
        'surfaceContainerHigh': p['mauve'][2],
        'surfaceContainerHighest': p['mauve'][3],
        'surfaceContainerLow': subcard,
        'surfaceContainerLowest': card,
        'scrim': p['mauve'][11],
    }
    return [f'        {k} = {hex_kt(v)},' for k, v in roles.items()]


kt = [
    'package io.github.bilidownload',
    '',
    'import android.os.Build',
    'import androidx.compose.foundation.shape.RoundedCornerShape',
    'import androidx.compose.material3.*',
    'import androidx.compose.runtime.Composable',
    'import androidx.compose.runtime.CompositionLocalProvider',
    'import androidx.compose.runtime.Immutable',
    'import androidx.compose.runtime.staticCompositionLocalOf',
    'import androidx.compose.ui.graphics.Color',
    'import androidx.compose.ui.platform.LocalContext',
    'import androidx.compose.ui.text.TextStyle',
    'import androidx.compose.ui.text.font.FontWeight',
    'import androidx.compose.ui.unit.dp',
    'import androidx.compose.ui.unit.sp',
    '',
    'enum class AccentPreset(val key: String, val label: String, val swatchColor: Color) {',
    f'    Pink("Pink", "品牌粉", {hex_kt(palette["light"]["pink"][8])}),',
    f'    Blue("Blue", "海盐蓝", {hex_kt(palette["light"]["blue"][8])}),',
    f'    Jade("Jade", "翡翠绿", {hex_kt(palette["light"]["jade"][8])}),',
    f'    Amber("Amber", "琥珀金", {hex_kt(palette["light"]["amber"][10])}),',
    f'    Dynamic("Dynamic", "动态取色", {hex_kt(palette["light"]["pink"][8])});',
    '',
    '    companion object {',
    '        fun fromKey(key: String?): AccentPreset = entries.firstOrNull { it.key == key } ?: Pink',
    '    }',
    '}',
    '',
    '@Immutable',
    'data class SaltExtendedColors(',
    '    val canvas: Color,',
    '    val card: Color,',
    '    val subCard: Color,',
    '    val subtleBorder: Color,',
    '    val accentSoftBg: Color,',
    '    val accentSoftFg: Color,',
    '    val success: Color,',
    '    val successBg: Color,',
    '    val warning: Color,',
    '    val warningBg: Color,',
    '    val error: Color,',
    '    val errorBg: Color,',
    '    val info: Color,',
    '    val infoBg: Color,',
    ')',
    '',
    'private fun radixScheme(dark: Boolean, scale: String): ColorScheme {',
    '    return if (!dark) when (scale) {',
]

for scale in ['blue', 'jade', 'amber']:
    kt.append(f'        "{scale}" -> lightColorScheme(')
    kt.extend(build_kt_scheme('light', scale))
    kt.append('        )')
kt.append('        else -> lightColorScheme(')
kt.extend(build_kt_scheme('light', 'pink'))
kt.append('        )')
kt.append('    } else when (scale) {')
for scale in ['blue', 'jade', 'amber']:
    kt.append(f'        "{scale}" -> darkColorScheme(')
    kt.extend(build_kt_scheme('dark', scale))
    kt.append('        )')
kt.append('        else -> darkColorScheme(')
kt.extend(build_kt_scheme('dark', 'pink'))
kt.append('        )')
kt.extend([
    '    }',
    '}',
    '',
    'private fun buildSaltColors(dark: Boolean, scheme: ColorScheme, scale: String): SaltExtendedColors {',
    '    val canvas = if (dark) ' + hex_kt(palette['dark']['mauve'][0]) + ' else ' + hex_kt(palette['light']['mauve'][2]),
    '    val card = if (dark) ' + hex_kt(palette['dark']['mauve'][1]) + ' else ' + hex_kt(palette['light']['mauve'][0]),
    '    val subCard = if (dark) ' + hex_kt(palette['dark']['mauve'][2]) + ' else ' + hex_kt(palette['light']['mauve'][1]),
    '    val subtleBorder = if (dark) ' + hex_kt(palette['dark']['mauve'][4]) + ' else ' + hex_kt(palette['light']['mauve'][4]),
    '    val accentSoftBg = if (scale == "dynamic") scheme.primaryContainer.copy(alpha = 0.55f) else when (scale) {',
    f'        "blue" -> if (dark) {hex_kt(palette["dark"]["blue"][2])} else {hex_kt(palette["light"]["blue"][2])}',
    f'        "jade" -> if (dark) {hex_kt(palette["dark"]["jade"][2])} else {hex_kt(palette["light"]["jade"][2])}',
    f'        "amber" -> if (dark) {hex_kt(palette["dark"]["amber"][2])} else {hex_kt(palette["light"]["amber"][2])}',
    f'        else -> if (dark) {hex_kt(palette["dark"]["pink"][2])} else {hex_kt(palette["light"]["pink"][2])}',
    '    }',
    '    val accentSoftFg = if (scale == "dynamic") scheme.onPrimaryContainer else when (scale) {',
    f'        "blue" -> if (dark) {hex_kt(palette["dark"]["blue"][10])} else {hex_kt(palette["light"]["blue"][10])}',
    f'        "jade" -> if (dark) {hex_kt(palette["dark"]["jade"][10])} else {hex_kt(palette["light"]["jade"][10])}',
    f'        "amber" -> if (dark) {hex_kt(palette["dark"]["amber"][10])} else {hex_kt(palette["light"]["amber"][10])}',
    f'        else -> if (dark) {hex_kt(palette["dark"]["pink"][10])} else {hex_kt(palette["light"]["pink"][10])}',
    '    }',
    '    return SaltExtendedColors(',
    '        canvas = canvas,',
    '        card = card,',
    '        subCard = subCard,',
    '        subtleBorder = subtleBorder,',
    '        accentSoftBg = accentSoftBg,',
    '        accentSoftFg = accentSoftFg,',
    f'        success = if (dark) {hex_kt(palette["dark"]["jade"][10])} else {hex_kt(palette["light"]["jade"][10])},',
    f'        successBg = if (dark) {hex_kt(palette["dark"]["jade"][2])} else {hex_kt(palette["light"]["jade"][2])},',
    f'        warning = if (dark) {hex_kt(palette["dark"]["amber"][10])} else {hex_kt(palette["light"]["amber"][10])},',
    f'        warningBg = if (dark) {hex_kt(palette["dark"]["amber"][2])} else {hex_kt(palette["light"]["amber"][2])},',
    f'        error = if (dark) {hex_kt(palette["dark"]["ruby"][10])} else {hex_kt(palette["light"]["ruby"][10])},',
    f'        errorBg = if (dark) {hex_kt(palette["dark"]["ruby"][2])} else {hex_kt(palette["light"]["ruby"][2])},',
    f'        info = if (dark) {hex_kt(palette["dark"]["blue"][10])} else {hex_kt(palette["light"]["blue"][10])},',
    f'        infoBg = if (dark) {hex_kt(palette["dark"]["blue"][2])} else {hex_kt(palette["light"]["blue"][2])},',
    '    )',
    '}',
    '',
    'private val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)',
    'private val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)',
    'private val titleLargeStyle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)',
    'private val headlineStyle = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)',
    'private val titleMediumStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)',
    'private val typography = Typography(',
    '    displayLarge = headlineStyle, displayMedium = headlineStyle, displaySmall = headlineStyle,',
    '    headlineLarge = headlineStyle, headlineMedium = headlineStyle, headlineSmall = headlineStyle,',
    '    titleLarge = titleLargeStyle, titleMedium = titleMediumStyle, titleSmall = body.copy(fontWeight = FontWeight.SemiBold),',
    '    bodyLarge = body, bodyMedium = body, bodySmall = label,',
    '    labelLarge = body.copy(fontWeight = FontWeight.Medium), labelMedium = label, labelSmall = label,',
    ')',
    '',
    f'private val LocalFocus = staticCompositionLocalOf {{ {hex_kt(palette["light"]["pink"][7])} }}',
    'private val LocalSaltColors = staticCompositionLocalOf {',
    '    buildSaltColors(false, radixScheme(false, "pink"), "pink")',
    '}',
    '',
    '@Composable fun radixFocusColor() = LocalFocus.current',
    '@Composable fun saltColors() = LocalSaltColors.current',
    '',
    '@Composable fun BiliTheme(dark: Boolean, accent: String = "Pink", content: @Composable () -> Unit) {',
    '    val preset = AccentPreset.fromKey(accent)',
    '    val scale = preset.key.lowercase()',
    '    val context = LocalContext.current',
    '    val useDynamic = preset == AccentPreset.Dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S',
    '    val scheme = if (useDynamic) {',
    '        val base = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)',
    '        base.copy(',
    f'            background = if (dark) {hex_kt(palette["dark"]["mauve"][0])} else {hex_kt(palette["light"]["mauve"][2])},',
    f'            surface = if (dark) {hex_kt(palette["dark"]["mauve"][1])} else {hex_kt(palette["light"]["mauve"][0])},',
    '        )',
    '    } else radixScheme(dark, scale)',
    '    val focusColor = if (useDynamic) scheme.primary else when (scale) {',
    f'        "blue" -> if (dark) {hex_kt(palette["dark"]["blue"][7])} else {hex_kt(palette["light"]["blue"][7])}',
    f'        "jade" -> if (dark) {hex_kt(palette["dark"]["jade"][7])} else {hex_kt(palette["light"]["jade"][7])}',
    f'        "amber" -> if (dark) {hex_kt(palette["dark"]["amber"][7])} else {hex_kt(palette["light"]["amber"][7])}',
    f'        else -> if (dark) {hex_kt(palette["dark"]["pink"][7])} else {hex_kt(palette["light"]["pink"][7])}',
    '    }',
    '    val salt = buildSaltColors(dark, scheme, scale)',
    '    CompositionLocalProvider(LocalFocus provides focusColor, LocalSaltColors provides salt) {',
    '        MaterialTheme(',
    '            colorScheme = scheme,',
    '            typography = typography,',
    '            shapes = Shapes(',
    '                extraSmall = RoundedCornerShape(8.dp),',
    '                small = RoundedCornerShape(10.dp),',
    '                medium = RoundedCornerShape(14.dp),',
    '                large = RoundedCornerShape(18.dp),',
    '                extraLarge = RoundedCornerShape(24.dp),',
    '            ),',
    '            content = content,',
    '        )',
    '    }',
    '}',
])
(root / 'android/app/src/main/java/io/github/bilidownload/RadixTheme.kt').write_text('\n'.join(kt) + '\n', encoding='utf-8')
print('Generated Windows and Android themes from Radix ' + palette['revision'])

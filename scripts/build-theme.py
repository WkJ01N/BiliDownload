"""Generate native theme tokens from the pinned official Radix scales."""
from pathlib import Path
import json

root = Path(__file__).resolve().parent.parent
palette = json.loads((root / 'branding/radix-colors.json').read_text())
ns = 'http://schemas.microsoft.com/winfx/2006/xaml/presentation'
# Filled controls use light Pink stops in both themes, with plum text on top.
fill, hover, pressed, ink = [('lightPink', step) for step in (5, 6, 7, 12)]

def color(mode, scale, step):
    if scale == 'lightPink':
        return palette['light']['pink'][step - 1]
    return palette[mode][scale][step - 1]

tokens = {
    'ApplicationPageBackgroundThemeBrush': ('pink', 1),
    'LayerFillColorDefaultBrush': ('pink', 1), 'LayerFillColorAltBrush': ('pink', 2),
    'CardBackgroundFillColorDefaultBrush': ('mauve', 2), 'CardBackgroundFillColorSecondaryBrush': ('mauve', 2),
    'CardStrokeColorDefaultBrush': ('mauve', 6), 'CardStrokeColorDefaultSolidBrush': ('mauve', 6),
    'TextFillColorPrimaryBrush': ('mauve', 12), 'TextFillColorSecondaryBrush': ('mauve', 11),
    'TextFillColorTertiaryBrush': ('mauve', 11), 'TextFillColorDisabledBrush': ('mauve', 8),
    'TextFillColorInverseBrush': ('gray', 1),
    'ControlFillColorDefaultBrush': ('mauve', 3), 'ControlFillColorSecondaryBrush': ('mauve', 4),
    'ControlFillColorTertiaryBrush': ('mauve', 5), 'ControlFillColorDisabledBrush': ('mauve', 3),
    'ControlFillColorInputActiveBrush': ('mauve', 2), 'ControlSolidFillColorDefaultBrush': ('mauve', 2),
    'ControlStrongFillColorDefaultBrush': ('mauve', 11), 'ControlStrongFillColorDisabledBrush': ('mauve', 7),
    'ControlStrokeColorDefaultBrush': ('mauve', 7), 'ControlStrokeColorSecondaryBrush': ('mauve', 8),
    'ControlStrokeColorOnAccentDefaultBrush': hover, 'ControlStrokeColorOnAccentSecondaryBrush': pressed,
    'ControlStrokeColorForStrongFillWhenOnImageBrush': ('mauve', 7),
    'ControlStrongStrokeColorDefaultBrush': ('mauve', 8), 'ControlStrongStrokeColorDisabledBrush': ('mauve', 6),
    'DividerStrokeColorDefaultBrush': ('mauve', 6), 'SurfaceStrokeColorDefaultBrush': ('mauve', 6),
    'SurfaceStrokeColorFlyoutBrush': ('mauve', 6), 'SolidBackgroundFillColorBaseBrush': ('pink', 1),
    'SolidBackgroundFillColorSecondaryBrush': ('pink', 2), 'SolidBackgroundFillColorTertiaryBrush': ('mauve', 3),
    'SolidBackgroundFillColorQuarternaryBrush': ('mauve', 2),
    'AcrylicBackgroundFillColorDefaultBrush': ('mauve', 2), 'AcrylicBackgroundFillColorBaseBrush': ('mauve', 2),
    'AccentFillColorDefaultBrush': fill, 'AccentFillColorSecondaryBrush': hover,
    'AccentFillColorTertiaryBrush': pressed, 'AccentFillColorDisabledBrush': ('mauve', 5),
    'AccentTextFillColorPrimaryBrush': ('pink', 11), 'AccentTextFillColorSecondaryBrush': ('pink', 11),
    'AccentTextFillColorTertiaryBrush': ('pink', 11), 'AccentTextFillColorDisabledBrush': ('mauve', 8),
    'TextOnAccentFillColorPrimaryBrush': ink, 'TextOnAccentFillColorSecondaryBrush': ink,
    'TextOnAccentFillColorDisabledBrush': ('mauve', 8),
    'NavigationViewContentBackground': ('pink', 1), 'NavigationViewDefaultPaneBackground': ('pink', 2),
    'NavigationViewExpandedPaneBackground': ('pink', 2), 'NavigationViewTopPaneBackground': ('pink', 2),
    'NavigationViewItemBackgroundSelected': ('pink', 4), 'NavigationViewItemBackgroundSelectedPointerOver': ('pink', 5),
    'NavigationViewItemForegroundSelected': ('pink', 12), 'NavigationViewItemBackgroundPointerOver': ('mauve', 4),
    'NavigationViewItemBackgroundPressed': ('mauve', 5),
    'RadixSecondaryTextBrush': ('mauve', 11),
}
for kind, scale in [('Success', 'jade'), ('Caution', 'amber'), ('Critical', 'ruby'), ('Attention', 'blue'), ('Neutral', 'mauve')]:
    tokens['SystemFillColor' + kind + 'Brush'] = (scale, 11)
    tokens['SystemFillColor' + kind + 'BackgroundBrush'] = (scale, 3)
    tokens['SystemFillColorSolid' + kind + 'BackgroundBrush'] = (scale, 3)

tokens.update({'AccentFillColorSelectedTextBackgroundBrush': ('pink', 5), 'FocusStrokeColorOuterBrush': ('pink', 11), 'FocusStrokeColorInnerBrush': ('pink', 1),
               'ProgressBarForeground': ('pink', 11), 'ProgressBarBackground': ('pink', 4), 'NavigationViewSelectionIndicatorForeground': ('pink', 11)})
aliases = json.loads((root / 'branding/winui-theme-aliases.json').read_text())
for _ in range(10):
    for name, source in aliases.items():
        if source in tokens and name not in tokens: tokens[name] = tokens[source]

out = [f'<ResourceDictionary xmlns="{ns}" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">', ' <ResourceDictionary.ThemeDictionaries>']
for mode, key in [('light', 'Light'), ('dark', 'Default')]:
    out.append(f'  <ResourceDictionary x:Key="{key}">')
    for name, (scale, step) in tokens.items():
        value = color('light' if scale == 'gray' and step == 1 else mode, scale, step)
        out.append(f'   <SolidColorBrush x:Key="{name}" Color="{value}"/>')
        if name.endswith('Brush') and name[:-5] not in tokens:
            out.append(f'   <Color x:Key="{name[:-5]}">{value}</Color>')
    for suffix, step in [('', 5), ('Light1', 4), ('Light2', 3), ('Light3', 2), ('Dark1', 6), ('Dark2', 7), ('Dark3', 8)]:
        out.append(f'   <Color x:Key="SystemAccentColor{suffix}">{color(mode, "lightPink", step)}</Color>')
    out.append('  </ResourceDictionary>')
out.append(' </ResourceDictionary.ThemeDictionaries>')
for name in ['NavigationViewContentGridCornerRadius','NavigationViewMinimalContentGridCornerRadius','TopNavigationViewContentGridCornerRadius']:
    out.append(f' <CornerRadius x:Key="{name}">0</CornerRadius>')
for name in ['NavigationViewContentGridBorderThickness','NavigationViewMinimalContentGridBorderThickness','TopNavigationViewContentGridBorderThickness','ExpanderBorderThickness','ExpanderHeaderBorderThickness','ExpanderChevronBorderThickness']:
    out.append(f' <Thickness x:Key="{name}">0.5</Thickness>')
for name in ['ButtonBorderThemeThickness', 'ComboBoxBorderThemeThickness', 'ComboBoxBackgroundBorderThickness', 'ComboBoxBackgroundBorderThicknessFocused', 'ComboBoxDropdownBorderThickness', 'TextControlBorderThemeThickness', 'TextControlBorderThemeThicknessFocused', 'TextControlBorderThemeThicknessUnfocused', 'InfoBarBorderThickness', 'CommandBarOverflowPresenterBorderThickness', 'ContentDialogBorderThemeThickness', 'CheckBoxBorderThickness']:
    out.append(f' <Thickness x:Key="{name}">0.5</Thickness>')
for name, value in [('ControlCornerRadius', 4), ('OverlayCornerRadius', 8), ('ListViewItemCornerRadius', 4), ('ComboBoxItemCornerRadius', 4)]:
    out.append(f' <CornerRadius x:Key="{name}">{value}</CornerRadius>')
for name, size in [('CheckBoxBorderThemeThickness',0.5), ('RadioButtonBorderThemeThickness',0.5), ('ControlContentThemeFontSize',14), ('ContentControlFontSize',14), ('ContentDialogTitleFontSize',20), ('SubtitleTextBlockFontSize',14), ('TitleTextBlockFontSize',20)]:
    out.append(f' <x:Double x:Key="{name}">{size}</x:Double>')
for name, size, weight in [('TitleTextBlockStyle',20,'SemiBold'), ('SubtitleTextBlockStyle',14,'SemiBold'), ('BodyTextBlockStyle',14,'Normal'), ('CaptionTextBlockStyle',12,'Normal')]:
    out.append(f' <Style x:Key="{name}" TargetType="TextBlock"><Setter Property="FontSize" Value="{size}"/><Setter Property="FontWeight" Value="{weight}"/><Setter Property="Foreground" Value="{{ThemeResource TextFillColorPrimaryBrush}}"/></Style>')
for target in ['Button', 'TextBox', 'ComboBox']:
    out.append(f' <Style TargetType="{target}" BasedOn="{{StaticResource Default{target}Style}}"><Setter Property="FontSize" Value="14"/><Setter Property="BorderThickness" Value="0.5"/><Setter Property="CornerRadius" Value="4"/></Style>')
out.append('</ResourceDictionary>')
(root/'src/BiliDownload.Windows/RadixTheme.xaml').write_text('\n'.join(out)+'\n')

kt = ['package io.github.bilidownload', 'import androidx.compose.material3.*', 'import androidx.compose.runtime.Composable', 'import androidx.compose.ui.graphics.Color', 'import androidx.compose.ui.text.TextStyle', 'import androidx.compose.ui.text.font.FontWeight', 'import androidx.compose.ui.unit.sp', 'import androidx.compose.foundation.shape.RoundedCornerShape', 'import androidx.compose.ui.unit.dp', '', 'private fun radixScheme(dark: Boolean): ColorScheme {']
for mode in ['light','dark']:
    kt.append(('    return if(!dark) lightColorScheme(' if mode=='light' else '    ) else darkColorScheme('))
    roles = {
        'primary': ('pink', 11), 'onPrimary': ('gray', 1) if mode == 'light' else ink,
        'primaryContainer': ('pink', 4), 'onPrimaryContainer': ('pink', 12),
        'inversePrimary': fill if mode == 'light' else ink,
        'secondary': ('mauve', 11), 'onSecondary': ('mauve', 1),
        'secondaryContainer': fill, 'onSecondaryContainer': ink,
        'tertiary': ('pink', 11), 'onTertiary': ('pink', 1),
        'tertiaryContainer': ('pink', 3), 'onTertiaryContainer': ('pink', 11),
        'background': ('pink', 1), 'onBackground': ('mauve', 12),
        'surface': ('pink', 1), 'onSurface': ('mauve', 12),
        'surfaceVariant': ('mauve', 3), 'onSurfaceVariant': ('mauve', 11),
        'surfaceTint': ('pink', 1), 'inverseSurface': ('mauve', 12), 'inverseOnSurface': ('mauve', 1),
        'outline': ('mauve', 7), 'outlineVariant': ('mauve', 6),
        'error': ('ruby', 11), 'onError': ('ruby', 1),
        'errorContainer': ('ruby', 3), 'onErrorContainer': ('ruby', 11),
        'surfaceBright': ('pink', 2), 'surfaceDim': ('mauve', 2),
        'surfaceContainer': ('pink', 2), 'surfaceContainerHigh': ('mauve', 3),
        'surfaceContainerHighest': ('mauve', 4), 'surfaceContainerLow': ('pink', 2),
        'surfaceContainerLowest': ('pink', 1), 'scrim': ('mauve', 12),
    }
    for role,(scale,step) in roles.items():
        value = color(mode, scale, step)
        kt.append(f'        {role} = Color(0xFF{value[1:].upper()}),')
kt += ['    )', '}', 'private val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)', 'private val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)', 'private val title = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)', 'private val typography = Typography(', '    displayLarge = title, displayMedium = title, displaySmall = title, headlineLarge = title, headlineMedium = title, headlineSmall = title,', '    titleLarge = title, titleMedium = body.copy(fontWeight = FontWeight.SemiBold), titleSmall = body.copy(fontWeight = FontWeight.SemiBold),', '    bodyLarge = body, bodyMedium = body, bodySmall = label, labelLarge = body.copy(fontWeight = FontWeight.Medium), labelMedium = label, labelSmall = label)', '@Composable fun BiliTheme(dark: Boolean, content: @Composable () -> Unit) {', '    MaterialTheme(colorScheme = radixScheme(dark), typography = typography, shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(4.dp), large = RoundedCornerShape(8.dp), extraLarge = RoundedCornerShape(8.dp)), content = content)', '}']
(root/'android/app/src/main/java/io/github/bilidownload/RadixTheme.kt').write_text('\n'.join(kt)+'\n')
print('Generated Windows and Android themes from Radix ' + palette['revision'])

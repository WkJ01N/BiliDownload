"""Generate native theme tokens from the pinned official Radix scales."""
from pathlib import Path
import json

root = Path(__file__).resolve().parent.parent
palette = json.loads((root / 'branding/radix-colors.json').read_text())
ns = 'http://schemas.microsoft.com/winfx/2006/xaml/presentation'
tokens = {
    'ApplicationPageBackgroundThemeBrush': ('mauve', 1),
    'LayerFillColorDefaultBrush': ('mauve', 1), 'LayerFillColorAltBrush': ('mauve', 2),
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
    'ControlStrokeColorOnAccentDefaultBrush': ('pink', 9), 'ControlStrokeColorOnAccentSecondaryBrush': ('pink', 10),
    'ControlStrokeColorForStrongFillWhenOnImageBrush': ('mauve', 7),
    'ControlStrongStrokeColorDefaultBrush': ('mauve', 8), 'ControlStrongStrokeColorDisabledBrush': ('mauve', 6),
    'DividerStrokeColorDefaultBrush': ('mauve', 6), 'SurfaceStrokeColorDefaultBrush': ('mauve', 6),
    'SurfaceStrokeColorFlyoutBrush': ('mauve', 6), 'SolidBackgroundFillColorBaseBrush': ('mauve', 1),
    'SolidBackgroundFillColorSecondaryBrush': ('mauve', 2), 'SolidBackgroundFillColorTertiaryBrush': ('mauve', 3),
    'SolidBackgroundFillColorQuarternaryBrush': ('mauve', 2),
    'AcrylicBackgroundFillColorDefaultBrush': ('mauve', 2), 'AcrylicBackgroundFillColorBaseBrush': ('mauve', 2),
    'AccentFillColorDefaultBrush': ('pink', 9), 'AccentFillColorSecondaryBrush': ('pink', 10),
    'AccentFillColorTertiaryBrush': ('pink', 10), 'AccentFillColorDisabledBrush': ('mauve', 5),
    'AccentTextFillColorPrimaryBrush': ('pink', 11), 'AccentTextFillColorSecondaryBrush': ('pink', 11),
    'AccentTextFillColorTertiaryBrush': ('pink', 11), 'AccentTextFillColorDisabledBrush': ('mauve', 8),
    'TextOnAccentFillColorPrimaryBrush': ('gray', 1), 'TextOnAccentFillColorSecondaryBrush': ('gray', 1),
    'TextOnAccentFillColorDisabledBrush': ('mauve', 8),
    'NavigationViewContentBackground': ('mauve', 1), 'NavigationViewDefaultPaneBackground': ('mauve', 2),
    'NavigationViewExpandedPaneBackground': ('mauve', 2), 'NavigationViewTopPaneBackground': ('mauve', 2),
    'NavigationViewItemBackgroundSelected': ('pink', 5), 'NavigationViewItemBackgroundSelectedPointerOver': ('pink', 4),
    'NavigationViewItemForegroundSelected': ('pink', 11), 'NavigationViewItemBackgroundPointerOver': ('mauve', 4),
    'NavigationViewItemBackgroundPressed': ('mauve', 5),
    'RadixSecondaryTextBrush': ('mauve', 11),
}
for kind, scale in [('Success', 'jade'), ('Caution', 'amber'), ('Critical', 'ruby'), ('Attention', 'blue'), ('Neutral', 'mauve')]:
    tokens['SystemFillColor' + kind + 'Brush'] = (scale, 11)
    tokens['SystemFillColor' + kind + 'BackgroundBrush'] = (scale, 3)
    tokens['SystemFillColorSolid' + kind + 'BackgroundBrush'] = (scale, 3)

tokens.update({'AccentFillColorSelectedTextBackgroundBrush': ('pink', 5), 'FocusStrokeColorOuterBrush': ('mauve', 7), 'FocusStrokeColorInnerBrush': ('pink', 8)})
aliases = json.loads((root / 'branding/winui-theme-aliases.json').read_text())
for _ in range(10):
    for name, source in aliases.items():
        if source in tokens and name not in tokens: tokens[name] = tokens[source]

out = [f'<ResourceDictionary xmlns="{ns}" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">', ' <ResourceDictionary.ThemeDictionaries>']
for mode, key in [('light', 'Light'), ('dark', 'Default')]:
    p = palette[mode]
    def color(scale, step):
        # White foreground on step 9 is the same in both modes.
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

kt = ['package io.github.bilidownload', 'import androidx.compose.material3.*', 'import androidx.compose.runtime.Composable', 'import androidx.compose.runtime.staticCompositionLocalOf', 'import androidx.compose.runtime.CompositionLocalProvider', 'import androidx.compose.ui.graphics.Color', 'import androidx.compose.ui.text.TextStyle', 'import androidx.compose.ui.text.font.FontWeight', 'import androidx.compose.ui.unit.sp', 'import androidx.compose.foundation.shape.RoundedCornerShape', 'import androidx.compose.ui.unit.dp', '', 'private fun radixScheme(dark: Boolean): ColorScheme {']
for mode in ['light','dark']:
    kt.append(('    return if(!dark) lightColorScheme(' if mode=='light' else '    ) else darkColorScheme('))
    roles = {'primary':('pink',9), 'onPrimary':('gray',1), 'primaryContainer':('pink',5), 'onPrimaryContainer':('pink',11), 'inversePrimary':('pink',11), 'secondary':('mauve',11), 'onSecondary':('gray',1), 'secondaryContainer':('mauve',5), 'onSecondaryContainer':('mauve',12), 'tertiary':('pink',11), 'onTertiary':('gray',1), 'tertiaryContainer':('pink',3), 'onTertiaryContainer':('pink',11), 'background':('mauve',1), 'onBackground':('mauve',12), 'surface':('mauve',1), 'onSurface':('mauve',12), 'surfaceVariant':('mauve',3), 'onSurfaceVariant':('mauve',11), 'surfaceTint':('mauve',1), 'inverseSurface':('mauve',12), 'inverseOnSurface':('mauve',1), 'outline':('mauve',7), 'outlineVariant':('mauve',6), 'error':('ruby',11), 'onError':('gray',1), 'errorContainer':('ruby',3), 'onErrorContainer':('ruby',11), 'surfaceBright':('mauve',2), 'surfaceDim':('mauve',2), 'surfaceContainer':('mauve',2), 'surfaceContainerHigh':('mauve',3), 'surfaceContainerHighest':('mauve',4), 'surfaceContainerLow':('mauve',2), 'surfaceContainerLowest':('mauve',1), 'scrim':('mauve',12)}
    for role,(scale,step) in roles.items():
        value = palette['light' if role in ['onPrimary','onSecondary','onTertiary','onError'] else mode][scale][step-1]
        kt.append(f'        {role} = Color(0xFF{value[1:].upper()}),')
kt += ['    )', '}', 'private val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)', 'private val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)', 'private val title = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)', 'private val typography = Typography(', '    displayLarge = title, displayMedium = title, displaySmall = title, headlineLarge = title, headlineMedium = title, headlineSmall = title,', '    titleLarge = title, titleMedium = body.copy(fontWeight = FontWeight.SemiBold), titleSmall = body.copy(fontWeight = FontWeight.SemiBold),', '    bodyLarge = body, bodyMedium = body, bodySmall = label, labelLarge = body.copy(fontWeight = FontWeight.Medium), labelMedium = label, labelSmall = label)', 'private val LocalFocus = staticCompositionLocalOf { Color(0xFF'+palette['light']['pink'][7][1:].upper()+') }', '@Composable fun radixFocusColor() = LocalFocus.current', '@Composable fun BiliTheme(dark: Boolean, content: @Composable () -> Unit) {', '    CompositionLocalProvider(LocalFocus provides if(dark) Color(0xFF'+palette['dark']['pink'][7][1:].upper()+') else Color(0xFF'+palette['light']['pink'][7][1:].upper()+')) { MaterialTheme(colorScheme = radixScheme(dark), typography = typography, shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(4.dp), large = RoundedCornerShape(8.dp), extraLarge = RoundedCornerShape(8.dp)), content = content) }', '}']
(root/'android/app/src/main/java/io/github/bilidownload/RadixTheme.kt').write_text('\n'.join(kt)+'\n')
print('Generated Windows and Android themes from Radix ' + palette['revision'])

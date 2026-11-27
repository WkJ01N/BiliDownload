using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Windows.UI;

namespace BiliDownload_Windows.Services;

public static class ThemeAccentHelper
{
    public sealed record AccentPalette(
        string Key,
        string Label,
        Color Swatch,
        AccentSteps Light,
        AccentSteps Dark);

    public sealed record AccentSteps(
        Color Step3,
        Color Step4,
        Color Step5,
        Color Step8,
        Color Step9,
        Color Step10,
        Color Step11,
        Color OnAccent);

    private static Color Hex(uint argb) => Color.FromArgb(
        (byte)(argb >> 24),
        (byte)((argb >> 16) & 0xFF),
        (byte)((argb >> 8) & 0xFF),
        (byte)(argb & 0xFF));

    public static IReadOnlyList<AccentPalette> Presets { get; } =
    [
        new("Pink", "品牌粉", Hex(0xFFD6409F),
            new(Hex(0xFFFEE9F5), Hex(0xFFFBDCEF), Hex(0xFFF6CEE7), Hex(0xFFDD93C2), Hex(0xFFD6409F), Hex(0xFFCF3897), Hex(0xFFC2298A), Hex(0xFFFCFCFC)),
            new(Hex(0xFF37172F), Hex(0xFF4B143D), Hex(0xFF591C47), Hex(0xFFA84885), Hex(0xFFD6409F), Hex(0xFFDE51A8), Hex(0xFFFF8DCC), Hex(0xFFFCFCFC))),
        new("Blue", "海盐蓝", Hex(0xFF0090FF),
            new(Hex(0xFFE6F4FE), Hex(0xFFD5EFFF), Hex(0xFFC2E5FF), Hex(0xFF5EB1EF), Hex(0xFF0090FF), Hex(0xFF0588F0), Hex(0xFF0D74CE), Hex(0xFFFCFCFC)),
            new(Hex(0xFF0D2847), Hex(0xFF003362), Hex(0xFF004074), Hex(0xFF2870BD), Hex(0xFF0090FF), Hex(0xFF3B9EFF), Hex(0xFF70B8FF), Hex(0xFFFCFCFC))),
        new("Jade", "翡翠绿", Hex(0xFF29A383),
            new(Hex(0xFFE6F7ED), Hex(0xFFD6F1E3), Hex(0xFFC3E9D7), Hex(0xFF56BA9F), Hex(0xFF29A383), Hex(0xFF26997B), Hex(0xFF208368), Hex(0xFFFCFCFC)),
            new(Hex(0xFF0F2E22), Hex(0xFF0B3B2C), Hex(0xFF114837), Hex(0xFF2A7E68), Hex(0xFF29A383), Hex(0xFF27B08B), Hex(0xFF1FD8A4), Hex(0xFFFCFCFC))),
        new("Amber", "琥珀金", Hex(0xFFAB6400),
            new(Hex(0xFFFFF7C2), Hex(0xFFFFEE9C), Hex(0xFFFBE577), Hex(0xFFE2A336), Hex(0xFFAB6400), Hex(0xFF8F5400), Hex(0xFFAB6400), Hex(0xFFFCFCFC)),
            new(Hex(0xFF302008), Hex(0xFF3F2700), Hex(0xFF4D3000), Hex(0xFF8F6424), Hex(0xFFFFC53D), Hex(0xFFFFD60A), Hex(0xFFFFCA16), Hex(0xFF211F26))),
    ];

    public static AccentPalette Resolve(string? key) =>
        Presets.FirstOrDefault(p => string.Equals(p.Key, key, StringComparison.OrdinalIgnoreCase)) ?? Presets[0];

    public static void ApplyAccent(string? accentKey)
    {
        var palette = Resolve(accentKey);
        var radix = Application.Current.Resources.MergedDictionaries
            .FirstOrDefault(d => d.Source?.OriginalString.EndsWith("RadixTheme.xaml", StringComparison.OrdinalIgnoreCase) == true);
        if (radix == null) return;

        UpdateDictionary((ResourceDictionary)radix.ThemeDictionaries["Light"], palette.Light, isLight: true);
        UpdateDictionary((ResourceDictionary)radix.ThemeDictionaries["Default"], palette.Dark, isLight: false);
    }

    private static void UpdateDictionary(ResourceDictionary dict, AccentSteps s, bool isLight)
    {
        SetBrush(dict, "AccentFillColorDefaultBrush", s.Step9);
        SetBrush(dict, "AccentFillColorSecondaryBrush", s.Step10);
        SetBrush(dict, "AccentFillColorTertiaryBrush", s.Step10);
        SetBrush(dict, "AccentTextFillColorPrimaryBrush", s.Step11);
        SetBrush(dict, "AccentTextFillColorSecondaryBrush", s.Step11);
        SetBrush(dict, "AccentTextFillColorTertiaryBrush", s.Step11);
        SetBrush(dict, "ControlStrokeColorOnAccentDefaultBrush", s.Step9);
        SetBrush(dict, "ControlStrokeColorOnAccentSecondaryBrush", s.Step10);
        SetBrush(dict, "TextOnAccentFillColorPrimaryBrush", s.OnAccent);
        SetBrush(dict, "TextOnAccentFillColorSecondaryBrush", s.OnAccent);

        SetBrush(dict, "AccentButtonBackground", s.Step9);
        SetBrush(dict, "AccentButtonBackgroundPointerOver", s.Step10);
        SetBrush(dict, "AccentButtonBackgroundPressed", s.Step10);
        SetBrush(dict, "AccentButtonForeground", s.OnAccent);
        SetBrush(dict, "AccentButtonForegroundPointerOver", s.OnAccent);
        SetBrush(dict, "AccentButtonForegroundPressed", s.OnAccent);

        SetBrush(dict, "NavigationViewItemBackgroundSelected", isLight ? s.Step4 : s.Step3);
        SetBrush(dict, "NavigationViewItemBackgroundSelectedPointerOver", isLight ? s.Step5 : s.Step4);
        SetBrush(dict, "NavigationViewItemForegroundSelected", s.Step11);
        SetBrush(dict, "NavigationViewSelectionIndicatorForeground", s.Step9);

        SetBrush(dict, "HyperlinkButtonForeground", s.Step11);
        SetBrush(dict, "HyperlinkButtonForegroundPointerOver", s.Step11);
        SetBrush(dict, "HyperlinkButtonForegroundPressed", s.Step11);
        SetBrush(dict, "HyperlinkForeground", s.Step11);

        SetBrush(dict, "ToggleSwitchFillOn", s.Step9);
        SetBrush(dict, "ToggleSwitchFillOnPointerOver", s.Step10);
        SetBrush(dict, "ToggleSwitchFillOnPressed", s.Step10);
        SetBrush(dict, "ToggleSwitchStrokeOn", s.Step9);
        SetBrush(dict, "ToggleSwitchStrokeOnPointerOver", s.Step10);
        SetBrush(dict, "ToggleSwitchStrokeOnPressed", s.Step10);
        SetBrush(dict, "ToggleSwitchKnobFillOn", s.OnAccent);

        SetBrush(dict, "CheckBoxCheckBackgroundFillChecked", s.Step9);
        SetBrush(dict, "CheckBoxCheckBackgroundFillCheckedPointerOver", s.Step10);
        SetBrush(dict, "CheckBoxCheckBackgroundFillCheckedPressed", s.Step10);
        SetBrush(dict, "CheckBoxCheckBackgroundStrokeChecked", s.Step9);
        SetBrush(dict, "CheckBoxCheckBackgroundStrokeCheckedPointerOver", s.Step10);
        SetBrush(dict, "CheckBoxCheckBackgroundStrokeCheckedPressed", s.Step10);
        SetBrush(dict, "CheckBoxCheckGlyphForegroundChecked", s.OnAccent);

        SetBrush(dict, "AccentFillColorSelectedTextBackgroundBrush", s.Step5);
        SetBrush(dict, "FocusStrokeColorInnerBrush", s.Step8);
        SetBrush(dict, "ProgressBarForeground", s.Step9);
        SetBrush(dict, "SaltAccentSoftBackgroundBrush", s.Step3);
        SetBrush(dict, "SaltAccentSoftForegroundBrush", s.Step11);

        foreach (var key in new[]
        {
            "SystemAccentColor", "SystemAccentColorLight1", "SystemAccentColorLight2",
            "SystemAccentColorLight3", "SystemAccentColorDark1", "SystemAccentColorDark2", "SystemAccentColorDark3"
        })
        {
            if (dict.ContainsKey(key)) dict[key] = s.Step9;
        }
    }

    private static void SetBrush(ResourceDictionary dict, string key, Color color)
    {
        if (dict.TryGetValue(key, out var existing) && existing is SolidColorBrush brush)
        {
            if (brush.Color != color) brush.Color = color;
        }
        var colorKey = key.EndsWith("Brush", StringComparison.Ordinal) ? key[..^5] : null;
        if (colorKey != null && dict.ContainsKey(colorKey)) dict[colorKey] = color;
    }
}

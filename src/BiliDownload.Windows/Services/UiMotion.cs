using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Windows.Foundation;

namespace BiliDownload_Windows.Services;

public static class UiMotion
{
    public static bool IsSmokeTest { get; set; }

    public static void AnimateReveal(UIElement element, double fromOffsetY = -8, int durationMs = 220)
    {
        var transform = element.RenderTransform as CompositeTransform ?? new CompositeTransform();
        element.RenderTransform = transform;
        element.Visibility = Visibility.Visible;

        if (IsSmokeTest || durationMs <= 0)
        {
            element.Opacity = 1;
            transform.TranslateY = 0;
            return;
        }

        element.Opacity = 0;
        transform.TranslateY = fromOffsetY;

        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var sb = new Storyboard();

        var fade = new DoubleAnimation { From = 0, To = 1, Duration = TimeSpan.FromMilliseconds(durationMs), EasingFunction = ease };
        Storyboard.SetTarget(fade, element);
        Storyboard.SetTargetProperty(fade, "Opacity");
        sb.Children.Add(fade);

        var move = new DoubleAnimation { From = fromOffsetY, To = 0, Duration = TimeSpan.FromMilliseconds(durationMs), EasingFunction = ease };
        Storyboard.SetTarget(move, transform);
        Storyboard.SetTargetProperty(move, "TranslateY");
        sb.Children.Add(move);

        sb.Begin();
    }

    public static void AnimateHide(UIElement element, double toOffsetY = -6, int durationMs = 170)
    {
        if (element.Visibility == Visibility.Collapsed) return;

        var transform = element.RenderTransform as CompositeTransform ?? new CompositeTransform();
        element.RenderTransform = transform;

        if (IsSmokeTest || durationMs <= 0)
        {
            element.Visibility = Visibility.Collapsed;
            element.Opacity = 1;
            transform.TranslateY = 0;
            return;
        }

        var ease = new CubicEase { EasingMode = EasingMode.EaseIn };
        var sb = new Storyboard();

        var fade = new DoubleAnimation { From = element.Opacity, To = 0, Duration = TimeSpan.FromMilliseconds(durationMs), EasingFunction = ease };
        Storyboard.SetTarget(fade, element);
        Storyboard.SetTargetProperty(fade, "Opacity");
        sb.Children.Add(fade);

        var move = new DoubleAnimation { From = transform.TranslateY, To = toOffsetY, Duration = TimeSpan.FromMilliseconds(durationMs), EasingFunction = ease };
        Storyboard.SetTarget(move, transform);
        Storyboard.SetTargetProperty(move, "TranslateY");
        sb.Children.Add(move);

        sb.Completed += (_, _) =>
        {
            element.Visibility = Visibility.Collapsed;
            element.Opacity = 1;
            transform.TranslateY = 0;
        };
        sb.Begin();
    }

    public static void AnimateSlidePill(Border pill, double targetX, double targetWidth, bool animate, int durationMs = 230)
    {
        if (targetWidth <= 0) return;
        pill.Width = targetWidth;
        var transform = pill.RenderTransform as TranslateTransform ?? new TranslateTransform();
        pill.RenderTransform = transform;

        if (!animate || IsSmokeTest || durationMs <= 0)
        {
            transform.X = targetX;
            return;
        }

        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var sb = new Storyboard();
        var slide = new DoubleAnimation
        {
            To = targetX,
            Duration = TimeSpan.FromMilliseconds(durationMs),
            EasingFunction = ease,
        };
        Storyboard.SetTarget(slide, transform);
        Storyboard.SetTargetProperty(slide, "X");
        sb.Children.Add(slide);
        sb.Begin();
    }

    public static void AnimateScalePulse(UIElement element)
    {
        if (IsSmokeTest) return;
        element.RenderTransformOrigin = new Point(0.5, 0.5);
        var transform = element.RenderTransform as ScaleTransform ?? new ScaleTransform();
        element.RenderTransform = transform;

        var ease = new BackEase { EasingMode = EasingMode.EaseOut, Amplitude = 0.35 };
        var sb = new Storyboard();

        var sx = new DoubleAnimation { From = 0.86, To = 1.0, Duration = TimeSpan.FromMilliseconds(240), EasingFunction = ease };
        Storyboard.SetTarget(sx, transform);
        Storyboard.SetTargetProperty(sx, "ScaleX");
        sb.Children.Add(sx);

        var sy = new DoubleAnimation { From = 0.86, To = 1.0, Duration = TimeSpan.FromMilliseconds(240), EasingFunction = ease };
        Storyboard.SetTarget(sy, transform);
        Storyboard.SetTargetProperty(sy, "ScaleY");
        sb.Children.Add(sy);

        sb.Begin();
    }

    public static void BindNoticeHost(Grid host, InfoBar notice, Border progressBar, Action? onClosed = null)
    {
        notice.CloseButtonClick += (_, _) =>
        {
            onClosed?.Invoke();
            progressBar.Visibility = Visibility.Collapsed;
            AnimateHide(host);
        };
    }

    public static async void ShowAnimatedNotice(
        Grid host,
        InfoBar notice,
        Border progressBar,
        string text,
        InfoBarSeverity severity,
        bool hasActions,
        CancellationTokenSource? previousCts,
        Action<CancellationTokenSource?> assignCts,
        int autoDismissMs = 3500)
    {
        previousCts?.Cancel();
        previousCts?.Dispose();
        assignCts(null);

        notice.Title = "";
        notice.Message = text;
        notice.Severity = severity;
        notice.IsOpen = true;

        var tintKey = severity switch
        {
            InfoBarSeverity.Success => "SystemFillColorSuccessBrush",
            InfoBarSeverity.Warning => "SystemFillColorCautionBrush",
            InfoBarSeverity.Error => "SystemFillColorCriticalBrush",
            _ => "AccentFillColorDefaultBrush",
        };
        if (Application.Current.Resources.TryGetValue(tintKey, out var brushObj) && brushObj is Brush brush)
        {
            progressBar.Background = brush;
        }

        var shouldAutoDismiss = !IsSmokeTest && !hasActions && severity != InfoBarSeverity.Error && autoDismissMs > 0;
        if (!shouldAutoDismiss)
        {
            progressBar.Visibility = Visibility.Collapsed;
            AnimateReveal(host);
            return;
        }

        var cts = new CancellationTokenSource();
        assignCts(cts);

        progressBar.Visibility = Visibility.Visible;
        progressBar.Opacity = 0.14;
        progressBar.RenderTransformOrigin = new Point(0, 0.5);
        var scale = progressBar.RenderTransform as ScaleTransform ?? new ScaleTransform();
        scale.ScaleX = 1.0;
        scale.ScaleY = 1.0;
        progressBar.RenderTransform = scale;

        AnimateReveal(host);

        var sb = new Storyboard();
        var anim = new DoubleAnimation
        {
            From = 1.0,
            To = 0.0,
            Duration = TimeSpan.FromMilliseconds(autoDismissMs),
        };
        Storyboard.SetTarget(anim, scale);
        Storyboard.SetTargetProperty(anim, "ScaleX");
        sb.Children.Add(anim);
        sb.Begin();

        try
        {
            await Task.Delay(autoDismissMs, cts.Token);
            if (!cts.IsCancellationRequested && notice.IsOpen)
            {
                notice.IsOpen = false;
                progressBar.Visibility = Visibility.Collapsed;
                AnimateHide(host);
            }
        }
        catch (OperationCanceledException)
        {
            sb.Stop();
        }
    }
}

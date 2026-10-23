using BiliDownload.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
using QRCoder;
using System.Runtime.InteropServices.WindowsRuntime;
using Windows.Storage.Streams;

namespace BiliDownload_Windows.Services;

public static class LoginFlow
{
    public static async Task<LoginResult> ShowAsync(XamlRoot root)
    {
        try
        {
            var code = await App.Services.Bili.ApiAsync("https://passport.bilibili.com/x/passport-login/web/qrcode/generate");
            using var generator = new QRCodeGenerator();
            using var data = generator.CreateQrCode(code.Str("url"), QRCodeGenerator.ECCLevel.M);
            using var qr = new PngByteQRCode(data);
            using var stream = new InMemoryRandomAccessStream();
            await stream.WriteAsync(qr.GetGraphic(10).AsBuffer()); stream.Seek(0);
            var bitmap = new BitmapImage(); await bitmap.SetSourceAsync(stream);
            var status = new TextBlock { Text = "请使用哔哩哔哩客户端扫码，并在手机上确认登录。", TextWrapping = TextWrapping.Wrap };
            var panel = new StackPanel { Spacing = 16 };
            panel.Children.Add(new Image { Source = bitmap, Width = 240, Height = 240 }); panel.Children.Add(status);
            var dialog = new ContentDialog { Title = "登录 B 站", Content = panel, CloseButtonText = "关闭", XamlRoot = root };
            using var cancel = new CancellationTokenSource(TimeSpan.FromMinutes(3));
            var outcome = new LoginResult(LoginOutcome.Canceled);
            var shown = dialog.ShowAsync();
            async Task PollAsync()
            {
                try
                {
                    while (true)
                    {
                        await Task.Delay(2000, cancel.Token);
                        var result = await App.Services.Bili.ApiAsync("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=" + Uri.EscapeDataString(code.Str("qrcode_key")), cancel.Token);
                        switch (result.Num("code"))
                        {
                            case 0: App.Services.SaveSession(); outcome = new(LoginOutcome.Success); dialog.Hide(); return;
                            case 86090: status.Text = "已扫码，请在手机上确认登录。"; break;
                            case 86038: status.Text = "二维码已过期，请关闭后重新生成。"; outcome = new(LoginOutcome.Failed, status.Text); return;
                        }
                    }
                }
                catch (OperationCanceledException) { }
                catch (Exception) { status.Text = "登录验证失败，请关闭后重试。"; outcome = new(LoginOutcome.Failed, status.Text); }
            }
            var polling = PollAsync(); await shown; cancel.Cancel(); await polling;
            return outcome;
        }
        catch (Exception ex) { return new(LoginOutcome.Failed, ex is BiliException ? ex.Message : "无法生成登录二维码，请检查网络后重试。"); }
    }
}

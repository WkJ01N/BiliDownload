using BiliDownload.Core;
using System.Text.Json;

if (args.FirstOrDefault() == "--verify-components")
{
    using var http = new HttpClient(new HttpClientHandler { UseCookies = false }) { Timeout = TimeSpan.FromMinutes(15) };
    var packages = JsonSerializer.Deserialize<ComponentPackage[]>(await File.ReadAllTextAsync(args[1]))!;
    var components = new ComponentInstaller(args[2], http);
    foreach (var package in packages) {
        var path = await components.InstallAsync(package);
        await MediaDownloader.CheckFfmpegAsync(path, default);
        Console.WriteLine("组件下载、SHA-256 校验、安装和启动成功：" + package.Id);
    }
    return 0;
}

if (args.FirstOrDefault() == "--verify-local") return await LocalVerification.RunAsync(args[1], args[2]);

using var client = new BiliClient();
try
{
    var catalogs = await client.ResolveAsync(args.FirstOrDefault() ?? "https://www.bilibili.com/video/BV1xx411c7mD");
    foreach (var catalog in catalogs)
    {
        Console.WriteLine($"解析成功：{catalog.Title} / {catalog.Items.Count} 个条目");
        var variants = await client.GetVariantsAsync(catalog.Items[0]);
        Console.WriteLine("可用画质：" + string.Join(", ", variants.Select(v => v.Label)));
        var plan = await client.GetPlanAsync(catalog.Items[0], 80);
        Console.WriteLine($"取流成功：{plan.Variant.Label}, 视频 {plan.Video.Count} 段，音频 {(plan.Audio != null ? "有" : "无")}");
    }
}
catch (Exception ex) { Console.Error.WriteLine(ex is BiliException ? ex.Message : ex.GetType().Name + ": 网络或接口异常"); return 1; }
return 0;

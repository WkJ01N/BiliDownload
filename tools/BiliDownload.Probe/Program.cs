using BiliDownload.Core;

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

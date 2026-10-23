using System.Text;
using System.Text.RegularExpressions;

namespace BiliDownload.Core;

public static class FileNaming
{
    public const string DefaultTemplate = "{name}";
    public static readonly string[] Variables = ["name", "title", "part", "index", "bvid", "id"];
    public static void Validate(string template)
    {
        if (string.IsNullOrWhiteSpace(template)) throw new BiliException("文件名模板不能为空。");
        var rest = Regex.Replace(template, @"\{([^{}]+)\}", m => Variables.Contains(m.Groups[1].Value)
            ? "" : throw new BiliException($"未知的文件名变量：{m.Value}"));
        if (rest.Contains('{') || rest.Contains('}')) throw new BiliException("文件名模板的花括号不完整。");
    }
    public static string Render(MediaItem item, string template = DefaultTemplate, string? custom = null)
    {
        if (custom != null) return Normalize(custom);
        Validate(template);
        var part = item.PartTitle.Trim();
        var name = item.PartCount > 1 || item.Kind != MediaKind.Video
            ? $"{item.Title} P{item.Index}" + (part.Length == 0 || part == item.Title.Trim() ? "" : " " + part)
            : item.Title;
        var values = new Dictionary<string, string> { ["name"] = name, ["title"] = item.Title,
            ["part"] = item.PartTitle, ["index"] = item.Index.ToString(), ["bvid"] = item.Bvid, ["id"] = item.Id };
        return Normalize(Regex.Replace(template, @"\{([^{}]+)\}", m => values[m.Groups[1].Value]));
    }
    public static string Normalize(string value)
    {
        value = value.Trim();
        if (value.EndsWith(".mp4", StringComparison.OrdinalIgnoreCase)) value = value[..^4];
        value = Regex.Replace(value, "[\\p{Cc}<>:\"/\\\\|?*]", "_").Trim().TrimEnd('.', ' ');
        value = string.Concat(value.EnumerateRunes().Take(100).Select(r => r.ToString())).TrimEnd('.', ' ');
        if (string.IsNullOrWhiteSpace(value)) value = "video";
        if (Regex.IsMatch(value.Split('.')[0], @"^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", RegexOptions.IgnoreCase)) value = "_" + value;
        return value + ".mp4";
    }
    public static string MoveUnique(string staging, string directory, string filename)
    {
        var stem = Path.GetFileNameWithoutExtension(filename);
        for (var n = 0; ; n++)
        {
            var path = Path.Combine(directory, n == 0 ? filename : $"{stem} ({n}).mp4");
            try { File.Move(staging, path, false); return path; }
            catch (IOException) when (File.Exists(path)) { }
        }
    }
}

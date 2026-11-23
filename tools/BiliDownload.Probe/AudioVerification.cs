using BiliDownload.Core;
using System.Diagnostics;

internal static class AudioVerification
{
    public static async Task<int> RunAsync(string[] args)
    {
        var tool = Path.GetFullPath(args[1]);
        var inputs = Path.GetFullPath(args[2]);
        var work = Path.GetFullPath(args[3]); Directory.CreateDirectory(work);
        var item = new MediaItem("test","试听","试听","",MediaKind.Video,0,"",0,0,1,"");
        var cases = new[] { ("aac.m4a","AAC",0), ("opus.webm","Opus",0), ("lossless.m4a","FLAC",0), ("dolby.m4a","E-AC-3",0),
            ("mixed.mp4","AAC",0), ("dolby.m4a","E-AC-3",320), ("opus.webm","Opus",192), ("aac.m4a","AAC",128) };
        foreach (var (name,codec,rate) in cases)
        {
            var source = Path.Combine(inputs,name);
            var track = new AudioTrack("id",codec,0,"",codec == "E-AC-3" ? 6 : 2,1,name == "mixed.mp4",new FileInfo(source).Length,null,new("试听","测试艺术家","测试专辑"));
            var output = new AudioOutput(track,rate);
            var result = Path.Combine(work,name + "-" + rate + output.Extension);
            await AudioExporter.CheckAsync(tool,output,default);
            await AudioExporter.ExportAsync(tool,[source],output,item,result,default);
            using var tagged = TagLib.File.Create(result);
            if (tagged.Tag.Title != "试听" || tagged.Tag.Album != "测试专辑" || tagged.Tag.FirstPerformer != "测试艺术家") throw new Exception("Audio metadata mismatch: " + name);
            if (rate == 0 && await Fingerprint(tool,source) != await Fingerprint(tool,result)) throw new Exception("Changed audio packets: " + name);
            var report = await Probe(tool,result);
            var expected = rate > 0 ? "mp3" : codec switch { "E-AC-3" => "eac3", _ => codec.ToLowerInvariant() };
            if (!report.Contains("Audio: " + expected) || report.Contains("Video:") || rate > 0 && !report.Contains(rate + " kb/s")) throw new Exception("Audio format check failed: " + name + " " + report);
            if (codec == "E-AC-3" && !report.Contains(rate == 0 ? "5.1" : "stereo")) throw new Exception("Channel layout changed unexpectedly.");
            Console.WriteLine($"PASS {name} => {output.Extension} {rate}, {new FileInfo(result).Length} bytes");
        }
        await BiliAudioVerification.Run(tool,inputs,work);
        return 0;
    }

    private static async Task<string> Fingerprint(string tool, string path)
    {
        var (code,stdout,_) = await Run(tool, ["-v","error","-i",path,"-map","0:a:0","-c","copy","-f","streamhash","-hash","SHA256","-"]);
        if(code != 0 || !stdout.Contains("SHA256=")) throw new Exception("Cannot fingerprint audio");
        return stdout.Trim();
    }
    private static async Task<string> Probe(string tool, string path) => (await Run(tool,["-hide_banner","-i",path])).Stderr;
    private static async Task<(int Exit,string Stdout,string Stderr)> Run(string tool,string[] args)
    {
        using var p = new Process { StartInfo = new(tool) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true } };
        foreach(var arg in args) p.StartInfo.ArgumentList.Add(arg);
        p.Start(); var output=p.StandardOutput.ReadToEndAsync(); var errors=p.StandardError.ReadToEndAsync();
        await p.WaitForExitAsync(); return (p.ExitCode,await output,await errors);
    }
}

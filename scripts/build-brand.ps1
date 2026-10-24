# The SVG is the editable source; all platform assets are deterministic exports.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName PresentationCore,WindowsBase
$projectRoot = Split-Path -Parent $PSScriptRoot
[xml]$source = Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'branding/icon.svg')
$paths = @($source.SelectNodes("//*[local-name()='path']") | ForEach-Object { [Windows.Media.Geometry]::Parse($_.d) })
$pink = [Windows.Media.BrushConverter]::new().ConvertFromString(($source.SelectSingleNode("//*[local-name()='rect']")).fill)
function Render-Icon([int]$size) {
    $visual = [Windows.Media.DrawingVisual]::new()
    $drawing = $visual.RenderOpen()
    $drawing.PushTransform([Windows.Media.ScaleTransform]::new($size / 512.0,$size / 512.0))
    $drawing.DrawRoundedRectangle($pink,$null,[Windows.Rect]::new(0,0,512,512),100,100)
    $pen = [Windows.Media.Pen]::new(([Windows.Media.BrushConverter]::new().ConvertFromString('#fcfcfc')), $(if($size -le 32){20}else{16}))
    $pen.StartLineCap = $pen.EndLineCap = [Windows.Media.PenLineCap]::Round
    $pen.LineJoin = [Windows.Media.PenLineJoin]::Round
    foreach ($path in $paths) { $drawing.DrawGeometry($null,$pen,$path) }
    $drawing.Pop(); $drawing.Close()
    $bitmap = [Windows.Media.Imaging.RenderTargetBitmap]::new($size,$size,96,96,[Windows.Media.PixelFormats]::Pbgra32)
    $bitmap.Render($visual)
    $encoder = [Windows.Media.Imaging.PngBitmapEncoder]::new()
    $encoder.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($bitmap))
    $stream = [IO.MemoryStream]::new()
    $encoder.Save($stream)
    $bytes = $stream.ToArray(); $stream.Dispose()
    return ,$bytes
}
function Write-Icon([int]$size,[string]$relative) {
    $path = Join-Path $projectRoot $relative
    New-Item -ItemType Directory -Force (Split-Path -Parent $path) | Out-Null
    [IO.File]::WriteAllBytes($path,(Render-Icon $size))
}
Write-Icon 512 'branding/icon-preview.png'
Write-Icon 256 'src/BiliDownload.Windows/Assets/AppIcon.png'
$sizes = @(16,20,24,32,40,48,64,128,256)
$images = @($sizes | ForEach-Object { ,(Render-Icon $_) })
$stream = [IO.File]::Create((Join-Path $projectRoot 'src/BiliDownload.Windows/Assets/AppIcon.ico'))
$writer = [IO.BinaryWriter]::new($stream)
try {
    $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$sizes.Count)
    $offset = 6 + 16 * $sizes.Count
    for($i=0;$i -lt $sizes.Count;$i++) {
        $dimension = [byte]$(if($sizes[$i] -eq 256){0}else{$sizes[$i]})
        $writer.Write($dimension); $writer.Write($dimension); $writer.Write([byte]0); $writer.Write([byte]0)
        $writer.Write([uint16]1); $writer.Write([uint16]32)
        $writer.Write([uint32]$images[$i].Length); $writer.Write([uint32]$offset)
        $offset += $images[$i].Length
    }
    foreach($bytes in $images) { $writer.Write([byte[]]$bytes) }
} finally { $writer.Dispose() }
$vectorPaths = @($source.SelectNodes("//*[local-name()='path']") | ForEach-Object {
    '    <path android:fillColor="@android:color/transparent" android:strokeColor="#fcfcfc" android:strokeWidth="16" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="' + $_.d + '"/>'
}) -join "`n"
$vector = @"
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="512" android:viewportHeight="512">
  <group android:pivotX="256" android:pivotY="256" android:scaleX="0.84" android:scaleY="0.84">
$vectorPaths
  </group>
</vector>
"@
[IO.File]::WriteAllText((Join-Path $projectRoot 'android/app/src/main/res/drawable/ic_brand_foreground.xml'),$vector+"`n")
$directory = Join-Path $projectRoot 'android/app/src/main/res/mipmap-anydpi'
New-Item -ItemType Directory -Force $directory | Out-Null
$adaptive = @"
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
  <background android:drawable="@color/brand_pink"/>
  <foreground android:drawable="@drawable/ic_brand_foreground"/>
  <monochrome android:drawable="@drawable/ic_brand_foreground"/>
</adaptive-icon>
"@
[IO.File]::WriteAllText((Join-Path $directory 'ic_launcher.xml'),$adaptive+"`n")
[IO.File]::WriteAllText((Join-Path $projectRoot 'android/app/src/main/res/values/branding.xml'),'<resources><color name="brand_pink">#d6409f</color></resources>'+"`n")
Write-Output 'Brand assets exported from branding/icon.svg.'

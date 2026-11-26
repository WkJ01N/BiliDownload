# The SVG is the editable source; all platform assets are deterministic exports.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName PresentationCore,WindowsBase
$projectRoot = Split-Path -Parent $PSScriptRoot
[xml]$source = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $projectRoot 'branding/icon.svg')
$tile = $source.SelectSingleNode("//*[local-name()='rect']")
$side = [double]$tile.GetAttribute('width')
$corner = [double]$tile.GetAttribute('rx')
$center = $side / 2
$brushes = [Windows.Media.BrushConverter]::new()
$background = $brushes.ConvertFromString($tile.fill)
$adaptiveScale = $source.DocumentElement.GetAttribute('data-android-scale')
if (-not $adaptiveScale) { $adaptiveScale = '0.84' } # Previous stroked artwork.

function Get-SvgStyle($element, [string]$name, [string]$default) {
    for ($node = $element; $node -is [System.Xml.XmlElement]; $node = $node.ParentNode) {
        if ($node.HasAttribute($name)) { return $node.GetAttribute($name) }
    }
    return $default
}

$paths = @(foreach ($element in $source.SelectNodes("//*[local-name()='path']")) {
    $data = ($element.GetAttribute('d').Trim() -replace '\s+', ' ')
    $fill = Get-SvgStyle $element 'fill' '#000000'
    $stroke = Get-SvgStyle $element 'stroke' 'none'
    $evenOdd = (Get-SvgStyle $element 'fill-rule' 'nonzero') -eq 'evenodd'
    $fillType = if ($evenOdd) { 'evenOdd' } else { 'nonZero' }
    $prefix = if ($evenOdd) { 'F0 ' } else { 'F1 ' }
    $brush = if ($fill -ne 'none') { $brushes.ConvertFromString($fill) }
    $androidFill = if ($fill -eq 'none') { '@android:color/transparent' } else { $fill }
    $paint = 'android:fillColor="{0}" android:fillType="{1}"' -f $androidFill,$fillType
    $pen = $null
    if ($stroke -ne 'none') {
        $width = Get-SvgStyle $element 'stroke-width' '1'
        $cap = Get-SvgStyle $element 'stroke-linecap' 'butt'
        $join = Get-SvgStyle $element 'stroke-linejoin' 'miter'
        $pen = [Windows.Media.Pen]::new($brushes.ConvertFromString($stroke), [double]$width)
        $pen.StartLineCap = $pen.EndLineCap = if ($cap -eq 'butt') { 'Flat' } else { $cap }
        $pen.LineJoin = $join
        $paint += ' android:strokeColor="{0}" android:strokeWidth="{1}" android:strokeLineCap="{2}" android:strokeLineJoin="{3}"' -f $stroke,$width,$cap,$join
    }
    [pscustomobject]@{
        Geometry = [Windows.Media.Geometry]::Parse($prefix + $data)
        Brush = $brush
        Pen = $pen
        AndroidPath = '    <path {0} android:pathData="{1}"/>' -f $paint,$data
    }
})
function Render-Icon([int]$size, [int]$canvasWidth = $size, [int]$canvasHeight = $size, [switch]$ForegroundOnly) {
    $visual = [Windows.Media.DrawingVisual]::new()
    $drawing = $visual.RenderOpen()
    $drawing.PushTransform([Windows.Media.TranslateTransform]::new(($canvasWidth - $size) / 2,($canvasHeight - $size) / 2))
    $drawing.PushTransform([Windows.Media.ScaleTransform]::new($size / $side,$size / $side))
    if (-not $ForegroundOnly) {
        $drawing.DrawRoundedRectangle($background,$null,[Windows.Rect]::new(0,0,$side,$side),$corner,$corner)
    }
    foreach ($path in $paths) {
        $pen = $path.Pen
        if ($pen -and $size -le 32) {
            $pen = $pen.Clone()
            $pen.Thickness *= 1.25
        }
        $drawing.DrawGeometry($path.Brush,$pen,$path.Geometry)
    }
    $drawing.Pop(); $drawing.Pop(); $drawing.Close()
    $bitmap = [Windows.Media.Imaging.RenderTargetBitmap]::new($canvasWidth,$canvasHeight,96,96,[Windows.Media.PixelFormats]::Pbgra32)
    $bitmap.Render($visual)
    $encoder = [Windows.Media.Imaging.PngBitmapEncoder]::new()
    $encoder.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($bitmap))
    $stream = [IO.MemoryStream]::new()
    $encoder.Save($stream)
    $bytes = $stream.ToArray(); $stream.Dispose()
    return ,$bytes
}
function Write-Icon([int]$size, [string]$relative, [int]$canvasWidth = $size, [int]$canvasHeight = $size, [switch]$ForegroundOnly) {
    $path = Join-Path $projectRoot $relative
    New-Item -ItemType Directory -Force (Split-Path -Parent $path) | Out-Null
    [IO.File]::WriteAllBytes($path,(Render-Icon $size $canvasWidth $canvasHeight -ForegroundOnly:$ForegroundOnly))
}
$assets = 'src/BiliDownload.Windows/Assets'
Write-Icon 512 'branding/icon-preview.png'
Write-Icon 256 "$assets/AppIcon.png"
# Preserve the native canvases and optical margins of the Windows asset set.
Write-Icon 260 "$assets/Square150x150Logo.scale-200.png" 300 300
Write-Icon 76 "$assets/Square44x44Logo.scale-200.png" 88 88
Write-Icon 22 "$assets/Square44x44Logo.targetsize-24_altform-unplated.png" 24 24
Write-Icon 42 "$assets/Square44x44Logo.targetsize-48_altform-lightunplated.png" 48 48
Write-Icon 44 "$assets/StoreLogo.png" 50 50
Write-Icon 64 "$assets/LockScreenLogo.scale-200.png" 48 48 -ForegroundOnly
Write-Icon 200 "$assets/Wide310x150Logo.scale-200.png" 620 300
Write-Icon 400 "$assets/SplashScreen.scale-200.png" 1240 600
$sizes = @(16,20,24,32,40,48,64,128,256)
$images = @($sizes | ForEach-Object { ,(Render-Icon $_) })
$stream = [IO.File]::Create((Join-Path $projectRoot "$assets/AppIcon.ico"))
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
$vectorPaths = $paths.AndroidPath -join "`n"
$vector = @"
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="$side" android:viewportHeight="$side">
  <group android:pivotX="$center" android:pivotY="$center" android:scaleX="$adaptiveScale" android:scaleY="$adaptiveScale">
$vectorPaths
  </group>
</vector>
"@
[IO.File]::WriteAllText((Join-Path $projectRoot 'android/app/src/main/res/drawable/ic_brand_foreground.xml'),$vector+"`n")
$directory = Join-Path $projectRoot 'android/app/src/main/res/mipmap-anydpi'
New-Item -ItemType Directory -Force $directory | Out-Null
$adaptive = @"
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
  <background android:drawable="@color/launcher_background"/>
  <foreground android:drawable="@drawable/ic_brand_foreground"/>
  <monochrome android:drawable="@drawable/ic_brand_foreground"/>
</adaptive-icon>
"@
[IO.File]::WriteAllText((Join-Path $directory 'ic_launcher.xml'),$adaptive+"`n")
[IO.File]::WriteAllText((Join-Path $projectRoot 'android/app/src/main/res/values/branding.xml'),('<resources><color name="launcher_background">{0}</color></resources>' -f $tile.fill)+"`n")
Write-Output 'Brand assets exported from branding/icon.svg.'

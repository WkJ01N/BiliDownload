param([Parameter(Mandatory)][string]$RuntimePackage, [Parameter(Mandatory)][string]$OutputPath)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = Join-Path $RuntimePackage 'tools/MSIX/win10-x64/Microsoft.WindowsAppRuntime.1.8.msix'
$zip = [IO.Compression.ZipFile]::OpenRead($archive)
try {
    $entry = $zip.GetEntry('Microsoft.WindowsAppRuntime.Insights.Resource.dll')
    if (-not $entry) { throw 'Windows App SDK notification resource is missing.' }
    New-Item -ItemType Directory -Force (Split-Path -Parent $OutputPath) | Out-Null
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $OutputPath, $true)
} finally { $zip.Dispose() }

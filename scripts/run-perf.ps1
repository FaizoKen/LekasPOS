# Runs the in-app performance suite on a connected device/emulator using the RELEASE build
# (the real verdict — see .claude/skills/lekaspos/references/performance.md §2) and saves the
# report into perf-results/<device>/.
# Usage: .\scripts\run-perf.ps1 [-Scale QUICK|FULL] [-Serial emulator-5554] [-Apk path] [-NoInstall] [-ScreenOff]
#   -ScreenOff: switch the screen off once the run has started (the API 21 emulator's GPU
#               emulation can crash the app while it redraws progress for many minutes).
param(
    [ValidateSet('TINY', 'QUICK', 'FULL')][string]$Scale = 'QUICK',
    [string]$Serial = '',
    [string]$Apk = "$PSScriptRoot\..\app\build\outputs\apk\release\app-release.apk",
    [string]$Package = 'com.lekaspos.app',
    [string]$SdkRoot = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\Android\Sdk' }),
    [int]$TimeoutMinutes = 90,
    [switch]$NoInstall,
    [switch]$ScreenOff
)
# adb writes progress to stderr; Windows PowerShell 5.1 turns that into errors under 'Stop'.
$ErrorActionPreference = 'Continue'
$adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
$target = if ($Serial) { @('-s', $Serial) } else { @() }

function Adb { & $adb @target @args 2>$null }

function AppPid {
    $p = ((Adb shell pidof $Package) -join '').Trim()
    if ($p -match '^\d+$') { return $p }
    $line = Adb shell ps -A | Select-String "\s$([regex]::Escape($Package))\s*$" | Select-Object -First 1
    if (-not $line) { $line = Adb shell ps | Select-String "\s$([regex]::Escape($Package))\s*$" | Select-Object -First 1 }
    if ($line) { return ($line.Line.Trim() -split '\s+')[1] }
    return $null
}

if (-not $NoInstall) {
    Write-Host "Installing $Apk"
    Adb install -r $Apk | Select-Object -Last 1
    if ($LASTEXITCODE -ne 0) { throw "adb install failed ($LASTEXITCODE)" }
}
$model = ((Adb shell getprop ro.product.model) -join '').Trim() -replace '[^A-Za-z0-9_-]', '_'
$sdk = ((Adb shell getprop ro.build.version.sdk) -join '').Trim()
Adb shell am force-stop $Package | Out-Null
Adb shell am start -n "$Package/com.lekaspos.ui.diag.DiagnosticsActivity" --es autorun $Scale | Out-Null
$appPid = $null
for ($i = 0; $i -lt 30 -and -not $appPid; $i++) { Start-Sleep -Seconds 1; $appPid = AppPid }
if (-not $appPid) { throw "The app did not start" }
Write-Host "Running $Scale suite on $model (API $sdk), pid $appPid..."
if ($ScreenOff) { Start-Sleep -Seconds 3; Adb shell input keyevent 26 | Out-Null }

$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$done = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 15
    $done = Adb logcat -d -s LekasPerf:I | Select-String "LekasPerf\(\s*$appPid\):\s*DONE" | Select-Object -Last 1
    if ($done) { break }
    if ((AppPid) -ne $appPid) {
        $death = Adb logcat -d | Select-String "Process $appPid exited due to signal|Process $([regex]::Escape($Package)) \(pid $appPid\) has died" | Select-Object -Last 1
        throw "The app process $appPid died before finishing: $($death.Line)"
    }
}
if ($ScreenOff) { Adb shell input keyevent 224 | Out-Null } # wake up
if (-not $done) { throw "No result within $TimeoutMinutes minutes" }

$outDir = Join-Path "$PSScriptRoot\.." "perf-results\$model-api$sdk"
New-Item -ItemType Directory -Force $outDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$txt = Join-Path $outDir "perf-$Scale-$stamp.txt"
# The app writes perf-<time>.json and .txt next to each other; the DONE line names the JSON.
# (Files under /data/data are only pullable from emulators/rooted devices; logcat is the fallback.)
$remote = ($done.Line.Trim() -split '\s+')[-1]
if ($remote -and $remote.EndsWith('.json')) {
    Adb pull $remote (Join-Path $outDir "perf-$Scale-$stamp.json") | Out-Null
    Adb pull ($remote -replace '\.json$', '.txt') $txt | Out-Null
}
if (-not (Test-Path $txt)) {
    Adb logcat -d -s LekasPerf:I | Where-Object { $_ -match "LekasPerf\(\s*$appPid\)" } |
        ForEach-Object { $_ -replace '^.*LekasPerf\(\s*\d+\):\s?', '' } | Set-Content -Path $txt -Encoding utf8
}
Write-Host $done.Line
Get-Content $txt
Write-Host "Saved to $outDir"

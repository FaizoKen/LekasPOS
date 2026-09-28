# Measures cold start (process killed first) of the selling screen: launch-to-first-frame
# (`am start -W` TotalTime) and launch-to-usable (`reportFullyDrawn`, "Fully drawn" in logcat).
# Budget: < 2000 ms on a 1 GB / API 21 device (references/performance.md §3). Use the release APK.
# Usage: .\scripts\measure-startup.ps1 [-Runs 10] [-Serial emulator-5554]
param(
    [int]$Runs = 10,
    [string]$Serial = '',
    [string]$Package = 'com.lekaspos.app',
    [string]$Activity = 'com.lekaspos.ui.sell.SellActivity',
    [string]$SdkRoot = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\Android\Sdk' })
)
# adb writes progress to stderr; Windows PowerShell 5.1 turns that into errors under 'Stop'.
$ErrorActionPreference = 'Continue'
$adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
$target = if ($Serial) { @('-s', $Serial) } else { @() }
function Adb { & $adb @target @args 2>$null }

$total = @()
$drawn = @()
for ($i = 1; $i -le $Runs; $i++) {
    Adb shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 1500
    Adb logcat -c
    $out = Adb shell am start -S -W -n "$Package/$Activity"
    $t = ($out | Select-String 'TotalTime:\s*(\d+)').Matches | ForEach-Object { [int]$_.Groups[1].Value }
    Start-Sleep -Seconds 3
    $fd = Adb logcat -d | Select-String "Fully drawn $Package/$Activity" | Select-Object -Last 1
    $ms = $null
    if ($fd -and $fd.Line -match '\+(?:(\d+)s)?(\d+)ms') { $ms = [int]$Matches[2] + 1000 * [int]("0" + $Matches[1]) }
    $total += $t
    if ($ms) { $drawn += $ms }
    Write-Host ("run {0,2}: first frame {1,5} ms   fully drawn {2,5} ms" -f $i, $t, $(if ($ms) { $ms } else { '-' }))
}
function Stats($name, $values) {
    if (-not $values -or $values.Count -eq 0) { Write-Host "$name : no data"; return }
    $s = $values | Sort-Object
    $median = $s[[int][math]::Floor(($s.Count - 1) / 2)]
    Write-Host ("{0}: min {1} ms, median {2} ms, max {3} ms (n={4})" -f $name, $s[0], $median, $s[-1], $s.Count)
}
Stats 'first frame (TotalTime)' $total
Stats 'fully drawn            ' $drawn

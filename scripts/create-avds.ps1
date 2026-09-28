# Creates the low-end emulator profiles used for tests and profiling
# (see .claude/skills/lekaspos/references/performance.md §4):
#   lekas-api21 : Android 5.0, 1 GB RAM, 2 cores, 480x800 hdpi, 64 MB app heap
#   lekas-api36 : Android 16,  2 GB RAM, 2 cores, 720x1280 xhdpi
# Needs the system images from setup-toolchain.ps1 -WithEmulator.
# Usage: .\scripts\create-avds.ps1 [-SdkRoot D:\Android\Sdk] [-JavaHome D:\dev\jdk-21]
param(
    [string]$SdkRoot = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "D:\Android\Sdk" }),
    [string]$JavaHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "D:\dev\jdk-21" })
)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkRoot
$avdmanager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\avdmanager.bat'
$avdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $env:USERPROFILE '.android\avd' }

$profiles = @(
    @{ Name = 'lekas-api21'; Image = 'system-images;android-21;default;x86_64'; Device = 'Nexus One';
       Settings = @{ 'hw.ramSize' = '1024'; 'vm.heapSize' = '64'; 'hw.cpu.ncore' = '2'; 'disk.dataPartition.size' = '4G';
                     'hw.lcd.width' = '480'; 'hw.lcd.height' = '800'; 'hw.lcd.density' = '240' } },
    @{ Name = 'lekas-api36'; Image = 'system-images;android-36;default;x86_64'; Device = 'Nexus 4';
       Settings = @{ 'hw.ramSize' = '2048'; 'vm.heapSize' = '192'; 'hw.cpu.ncore' = '2'; 'disk.dataPartition.size' = '6G';
                     'hw.lcd.width' = '720'; 'hw.lcd.height' = '1280'; 'hw.lcd.density' = '320' } }
)

foreach ($p in $profiles) {
    Write-Host "Creating $($p.Name)"
    $ErrorActionPreference = 'Continue'
    cmd /c "echo no| `"$avdmanager`" create avd --force --name $($p.Name) --package `"$($p.Image)`" --device `"$($p.Device)`" 2>&1"
    $ErrorActionPreference = 'Stop'
    $config = Join-Path $avdHome "$($p.Name).avd\config.ini"
    if (-not (Test-Path $config)) { throw "AVD $($p.Name) was not created ($config missing)" }
    $common = @{ 'hw.keyboard' = 'yes'; 'hw.gpu.enabled' = 'yes'; 'hw.gpu.mode' = 'swiftshader_indirect';
                 'fastboot.forceColdBoot' = 'yes'; 'showDeviceFrame' = 'no'; 'skin.dynamic' = 'yes' }
    $all = $p.Settings + $common
    $lines = Get-Content $config | Where-Object { $key = ($_ -split '=', 2)[0].Trim(); -not $all.ContainsKey($key) }
    foreach ($k in $all.Keys) { $lines += "$k=$($all[$k])" }
    Set-Content -Path $config -Value $lines -Encoding ascii
    Write-Host "  configured $config"
}
Write-Host "Start one with: $SdkRoot\emulator\emulator.exe -avd lekas-api21 -no-snapshot -no-boot-anim"

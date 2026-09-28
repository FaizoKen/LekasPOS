# Installs a minimal command-line Android toolchain (no Android Studio needed):
#   - Eclipse Temurin JDK 21 (portable zip)
#   - Android SDK command-line tools + platform 36 + build-tools + platform-tools
#   - Optional: emulator and the two AOSP x86_64 system images used for testing (API 21, API 36)
# Usage (PowerShell):  .\scripts\setup-toolchain.ps1 [-DevRoot D:\dev] [-SdkRoot D:\Android\Sdk] [-WithEmulator]
# Afterwards set JAVA_HOME and ANDROID_HOME as printed at the end (or rely on local.properties).
param(
    [string]$DevRoot = "D:\dev",
    [string]$SdkRoot = "D:\Android\Sdk",
    [switch]$WithEmulator
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$downloads = Join-Path $DevRoot 'downloads'
New-Item -ItemType Directory -Force $downloads, $SdkRoot | Out-Null

function Get-File([string]$url, [string]$out) {
    if (-not (Test-Path $out)) {
        Write-Host "Downloading $url"
        Invoke-WebRequest -UseBasicParsing $url -OutFile $out -TimeoutSec 1800
    }
}

# 1. JDK 21
$jdkHome = Join-Path $DevRoot 'jdk-21'
if (-not (Test-Path "$jdkHome\bin\java.exe")) {
    $zip = Join-Path $downloads 'temurin-21.zip'
    Get-File 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk' $zip
    $tmp = Join-Path $downloads 'jdk-extract'
    New-Item -ItemType Directory -Force $tmp | Out-Null
    tar.exe -xf $zip -C $tmp
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    Move-Item $inner.FullName $jdkHome
    Remove-Item $tmp -Recurse -Force
    Remove-Item $zip -Force
}
$env:JAVA_HOME = $jdkHome
Write-Host "JDK: $jdkHome"

# 2. Android command-line tools (rev 23.0)
$cmdline = Join-Path $SdkRoot 'cmdline-tools\latest'
if (-not (Test-Path "$cmdline\bin\sdkmanager.bat")) {
    $zip = Join-Path $downloads 'cmdline-tools.zip'
    Get-File 'https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip' $zip
    $tmp = Join-Path $downloads 'cmdline-extract'
    New-Item -ItemType Directory -Force $tmp | Out-Null
    tar.exe -xf $zip -C $tmp
    New-Item -ItemType Directory -Force (Split-Path $cmdline) | Out-Null
    Move-Item (Join-Path $tmp 'cmdline-tools') $cmdline
    Remove-Item $tmp -Recurse -Force
    Remove-Item $zip -Force
}
# 3. Install packages. cmdline-tools 23+ replaced sdkmanager with the "Android CLI"
#    (android.exe; package paths use '/' instead of ';'). Metrics collection is disabled.
#    Native tools print warnings on stderr, which Windows PowerShell 5.1 turns into errors
#    under 'Stop', so relax the preference and check exit codes instead.
$androidCli = Join-Path $cmdline 'bin\android.exe'
$packages = @('platform-tools', 'platforms/android-36', 'build-tools/36.0.0')
if ($WithEmulator) {
    $packages += @('emulator', 'system-images/android-21/default/x86_64', 'system-images/android-36/default/x86_64')
}
#    The CLI (rev 23.0) can crash on exit (0xC0000409) after a successful install when its
#    output is redirected, so success is verified by the package's package.xml instead.
$ErrorActionPreference = 'Continue'
foreach ($p in $packages) {
    $marker = Join-Path (Join-Path $SdkRoot ($p -replace '/', '\')) 'package.xml'
    if (Test-Path $marker) { Write-Host "Already installed: $p"; continue }
    Write-Host "Installing $p"
    cmd /c "`"$androidCli`" --no-metrics --sdk=`"$SdkRoot`" sdk install $p 2>&1"
    if (-not (Test-Path $marker)) { throw "Installing $p failed (exit code $LASTEXITCODE, no $marker)" }
}
$ErrorActionPreference = 'Stop'

Write-Host ""
Write-Host "Done. Suggested environment variables:"
Write-Host "  JAVA_HOME   = $jdkHome"
Write-Host "  ANDROID_HOME = $SdkRoot"

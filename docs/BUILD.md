# Build, test and release

## Toolchain (pinned)

| Tool | Version | Notes |
|---|---|---|
| JDK | Temurin 21 | runs Gradle; `:app` targets Java 17 bytecode, `:core` Java 8 API |
| Gradle | 9.8.0 (wrapper, checksum-verified) | `gradlew.bat` / `gradlew` |
| Android Gradle Plugin | 9.4.1 | built-in Kotlin |
| Kotlin | 2.4.20 | |
| compileSdk / targetSdk | 36 / 36 | Google Play requirement from 2026-08-31 |
| minSdk | 21 | Android 5.0 |
| build-tools | 36.0.0 | |

On the original build machine the toolchain lives in `D:\dev\jdk-21` and `D:\Android\Sdk`.
`local.properties` (git-ignored) points Gradle at the SDK: `sdk.dir=D\:\\Android\\Sdk`.

### Setting up a new machine

Either install Android Studio (it bundles a JDK and SDK manager), or use the script, which needs
no admin rights and no Android Studio:

```powershell
.\scripts\setup-toolchain.ps1 -DevRoot D:\dev -SdkRoot D:\Android\Sdk -WithEmulator
$env:JAVA_HOME = 'D:\dev\jdk-21'; $env:ANDROID_HOME = 'D:\Android\Sdk'
```

Note: command-line tools 23+ replaced `sdkmanager` with the "Android CLI" (`android.exe`); the
script calls it with `--no-metrics`.

## Continuous integration (GitHub Actions) — where the heavy work runs

The development laptop is small (≈7 GB RAM, little disk), so emulator tests and perf runs
happen on GitHub-hosted Linux runners (4 vCPU / 16 GB, KVM-accelerated emulators; free for
public repositories):

| Workflow | When | What |
|---|---|---|
| `.github/workflows/ci.yml` | every push to `main`, every PR, manual | JVM tests, lint, release/debug/test APKs, APK-size check (< 8 MB); then instrumented tests on API 21 (1 GB) and API 36 in parallel |
| `.github/workflows/perf.yml` | manual (Actions → Performance → Run workflow, scale QUICK/FULL) | data generation + perf suite (instrumented, and the release build's in-app runner) + cold start on API 21 and API 36 |

Results: the run's summary page (APK size, test counts, perf report, cold start) and the
uploaded artifacts (`apks`, `build-reports`, `instrumented-api*`, `perf-*`). The APKs artifact
is also the easiest way to get a build onto a test phone.

The CI scripts live in `scripts/ci/` (bash). Locally, only the fast loop is needed:
`.\gradlew.bat :core:test :app:testDebugUnitTest` and, when useful, a single emulator.

## Everyday commands (repo root, PowerShell)

```powershell
$env:JAVA_HOME = 'D:\dev\jdk-21'; $env:ANDROID_HOME = 'D:\Android\Sdk'
.\gradlew.bat :core:test :app:testDebugUnitTest          # JVM tests (fast)
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
.\gradlew.bat :app:assembleRelease :app:lintRelease      # R8 release APK + lint (errors fail the build)
```

The shell used by some tools sets `NoDefaultCurrentDirectoryInExePath`, so always call
`.\gradlew.bat` with the `.\` prefix.

## Instrumented tests on the low-end emulators

```powershell
.\scripts\create-avds.ps1                                  # once: lekas-api21 (1 GB) and lekas-api36
D:\Android\Sdk\emulator\emulator.exe -avd lekas-api21 -no-snapshot -no-boot-anim   # or -no-window
.\gradlew.bat --stop                                       # optional: free RAM on small machines
$adb = 'D:\Android\Sdk\platform-tools\adb.exe'
& $adb install -r -t app\build\outputs\apk\debug\app-debug.apk
& $adb install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adb shell am instrument -w -e notClass com.lekaspos.perf.PerfSuiteTest com.lekaspos.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Running through `adb` instead of `connectedDebugAndroidTest` keeps Gradle's memory free while
the emulator runs. Every DB test must pass on the API 21 image (SQLite 3.8.x).

## Performance

```powershell
.\scripts\run-perf.ps1 -Scale QUICK   # or FULL (50,000 products, ~1M sale lines); release build
.\scripts\measure-startup.ps1 -Runs 10
```

Reports land in `perf-results/<device>/` (git-ignored). Testers can run the same suite from the
app: home screen → Diagnostics & performance test.

## Signing

Release builds are signed with the key described in `keystore.properties` at the repo root
(git-ignored):

```properties
storeFile=keystore/lekaspos-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Create an upload key once (keep it and its passwords safe, outside the repo if possible):

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore keystore\lekaspos-upload.jks `
  -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Without `keystore.properties` the release APK is signed with the debug key so it can be
installed for testing (`BuildConfig.SIGNED_WITH_RELEASE_KEY = false`). Never upload such a build.
Google Play uses Play App Signing: you upload with the upload key, Google signs for devices.

## Release checklist (details grow with each phase)

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. `.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleRelease :app:lintRelease`.
3. Instrumented tests green on `lekas-api21` and `lekas-api36`; perf suite within budget.
4. Record APK size and results in `docs/PHASES.md`.
5. Keep `app/build/outputs/mapping/release/mapping.txt` for crash de-obfuscation.
6. (Phase 6+) Google Cloud OAuth client for the release signing certificate — see README.

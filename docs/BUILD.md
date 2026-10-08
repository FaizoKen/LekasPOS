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
| `.github/workflows/relay.yml` | push to `main` touching `relay/`, manual | relay unit tests (`node --test`), deploy of the error-report relay to Cloudflare Workers, a test report over the internet (D-057) |

Results: the run's summary page (APK size, test counts, perf report, cold start) and the
uploaded artifacts (`apks`, `build-reports`, `instrumented-api*`, `perf-*`). The APKs artifact
is also the easiest way to get a build onto a test phone.

The CI scripts live in `scripts/ci/` (bash). Locally, only the fast loop is needed:
`.\gradlew.bat :core:test :app:testDebugUnitTest` and, when useful, a single emulator.

## Error-report relay (D-057)

Error reports from shops' phones go to `https://lekaspos-reports.faizoken.workers.dev/v1/report`
(`relay/`, a Cloudflare Worker on the free plan) and become issues in the **private** repository
`FaizoKen/LekasPOS-reports` (its README explains the labels; its "Readable trace" workflow
comments the retraced trace using the release's `mapping-<version>.txt`). The address is in every
APK (`ErrorReports.URL`): keep it working.

`relay.yml` deploys it with these repository settings of `FaizoKen/LekasPOS`:

| Name | Kind | What |
|---|---|---|
| `CLOUDFLARE_ACCOUNT_ID` | variable | the Cloudflare account (`b7a1867a…b24a`) |
| `CLOUDFLARE_API_TOKEN` | secret | Cloudflare → My Profile → API Tokens → custom token: *Account · Workers Scripts · Edit* (+ *Account Settings · Read*), this account only |
| `REPORTS_TOKEN` | secret | GitHub → Settings → Developer settings → fine-grained token: repository `FaizoKen/LekasPOS-reports` only, *Issues: Read and write*; it becomes the Worker's secret |

Without the two secrets the workflow tests the relay but does not deploy it. The GitHub token was
made without an expiry (2026-10-02); if either token is ever replaced, update its secret and re-run
the workflow. Locally: `cd relay; node --test`.

The report key is in every APK, so the relay guards against made-up reports with two optional
settings, `[vars]` in `relay/wrangler.toml` (not the Cloudflare dashboard: a deploy replaces those):

| Name | Default | What |
|---|---|---|
| `MAX_BUILD` | 10000 | the highest build (`versionCode`, the CI run number) taken; a higher one is refused (400, the app drops it). **Raise it well before the app's builds get near it.** Lowering it also makes issues forget builds above it. |
| `MAX_NEW_ISSUES` | 30 | new issues (new bugs and reports sent by hand) in an hour, counted from the reports repository's newest issues; past it the relay answers 429 and tills send again later. 1–100. |

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

Three keys, never mixed up (`BuildConfig.SIGNING_KEY` says which one signed a build; the
Diagnostics screen shows it):

| Key | Signs | Where it lives |
|---|---|---|
| **debug** | debug builds; release builds when no key is configured | per machine (`~/.android/debug.keystore`, created by the build) — differs on every CI runner |
| **release** (was "test") | every CI release APK: public downloads (GitHub releases, website) and testers' builds — D-051 | GitHub secrets `TEST_KEYSTORE_BASE64`, `TEST_KEYSTORE_PASSWORD`, `TEST_KEY_ALIAS` (names kept); on the maintainer's laptop `%USERPROFILE%\.lekaspos\lekaspos-test.jks` — **back it up in two safe places** |
| **upload** | Google Play uploads, if the app goes on Play | only with the app owner; never in CI secrets of a public repo without a protected environment |

Release builds read `keystore.properties` at the repo root (git-ignored):

```properties
storeFile=C:/Users/<you>/.lekaspos/lekaspos-test.jks
storePassword=...
keyAlias=lekaspos-test
keyPassword=...
keyKind=release       # or "upload" for the Play upload key
```

Every CI release APK is signed with the same release key and gets an increasing `versionCode`
(`-Plekas.ciRun=<run number>`); `versionName` is the release version (e.g. `1.0.0`, shown as
"1.0.0 (build 60)" in Settings → About). So every build installs over the previous one and keeps
the shop's data. Pull requests from forks don't get the secrets and fall back to debug signing
(About then shows "· debug").

Release key certificate: `CN=LekasPOS Test Builds, O=LekasPOS, C=MY` (named in the tester days),
SHA-256 `0a67abecd6cb31634eaca5edab9737be7f940ce9a86919b8f47e1ad99ab7d4b9`, valid until 2054.
Check any APK with `apksigner verify --print-certs <apk>`.

**The release key can never change** without every shop uninstalling (and restoring a backup).
Losing it means no more updates for anyone who downloaded the app. Keep the `.jks` file and its
password in two safe places (e.g. a USB drive in a drawer and a password manager). If it ever
leaks: a new key, new secrets, a new Android OAuth client — and everyone reinstalls.

If the app goes on Google Play: create the Play **upload** key (below), and when enrolling in Play
App Signing choose **use my own app signing key** and give Play this release key (Play's PEPK
tool encrypts it). Then Play installs are signed like the downloads: they update each other and
the existing Android OAuth client keeps working.

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore $env:USERPROFILE\.lekaspos\lekaspos-upload.jks `
  -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

## Release checklist

1. Set `versionName` in `app/build.gradle.kts` (the CI run number is the `versionCode`).
2. Push; CI green (unit, lint, instrumented API 21 + 36, tablet, release smoke); perf FULL run.
3. Record APK size and results in `docs/PHASES.md`.
4. Privacy: if the release changes what the app stores, sends, asks permission for or connects to
   (a new permission, a new connection, new fields, what error reports hold), update the privacy
   policy in **both** languages before publishing — `site/privacy.html` and `site/privasi.html`
   say the same thing and carry the same "Last updated" date — and the Data Safety answers and
   permission notes in `docs/PLAY.md`.
5. GitHub release `v<version>` on the CI build's commit, marked **latest** (not pre-release),
   assets `LekasPOS.apk` (the website's "Download" link always takes the latest release's file of
   that name) and `LekasPOS-<version>.apk`; SHA-256 in the notes. **Publishing it updates every
   shop** (D-059): from 1.6.0 the app finds the latest release within a day and offers it. So the
   tag must be a higher version than the last release (`v1.6.1`, never a reused number), the assets
   must be uploaded before the release is marked latest, and the notes need a `### What's new`
   heading with a `-` list (the app shows it; an optional `### Apa yang baharu` list is shown in
   Malay). Test builds stay **pre-releases**: only phones with "Include test versions" on see them.
6. Point `PREV_APK_URL` in `ci.yml` at the new release (the smoke test upgrades from it).
7. Attach the CI build's `mapping.txt` (artifact `mapping`, kept 90 days) to the release as
   `mapping-<version>.txt`, for reading crash traces (`retrace`). Error reports (D-057) are retraced
   from there by the reports repository's "Readable trace" workflow, so attach it to pre-releases too.
8. Google sign-in: the Android OAuth client in project `lekaspos` matches `com.lekaspos.app` +
   the release key's SHA-1 (see README); the consent screen is "In production" (D-051).
9. Version numbers in public text: the website (`site/index.html`, the English and Malay "From
   version …" / "Version … or older" update steps) and `README.md` must still be right for the new
   release; update them if the release changes how installing or updating works.
10. User guide: `site/guide.html` and `site/panduan.html` describe the release (their "For LekasPOS x.y.z" line,
   and every screen or step the release changed — skill `references/recipes.md` §0); `node scripts/check-site.mjs`
   passes (`pages.yml` runs it too).

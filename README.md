# LekasPOS

[![CI](https://github.com/FaizoKen/LekasPOS/actions/workflows/ci.yml/badge.svg)](https://github.com/FaizoKen/LekasPOS/actions/workflows/ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

A lightweight, offline-first point-of-sale app for small grocery stores and mini markets
(Malaysia first: MYR, English and Bahasa Melayu). Native Android, runs on Android 5.0+ phones
and tablets with 1–2 GB RAM. No backend: all data lives on the device; Google Drive is used
only for background sync and backup between the devices of one store.

**Download:** version 1.0.0 — [faizoken.github.io/LekasPOS](https://faizoken.github.io/LekasPOS/#download)
(install and update steps), or the latest [release](https://github.com/FaizoKen/LekasPOS/releases/latest).
Not on Google Play yet. Progress: [`docs/PHASES.md`](docs/PHASES.md). Privacy policy:
[faizoken.github.io/LekasPOS/privacy.html](https://faizoken.github.io/LekasPOS/privacy.html).

## Features

- **Selling:** barcode scanners (Bluetooth/USB keyboard or serial) and camera scanning, search
  and category grid, weighed items and scale labels, discounts, held bills, cash with rounding,
  card, e-wallet, split and credit payments; ESC/POS Bluetooth receipt printers and cash drawer.
- **Stock:** receiving and purchases, suppliers, stock counts, movement history, low stock.
- **People:** staff with PINs and roles, manager approval, shifts and cash, customers and credit,
  audit log.
- **Reports:** any period, profit and margin, best and slow sellers, stock value; CSV exports and
  product CSV import.
- **Data:** daily backups on the phone (checked for damage first), a daily copy to an SD card or
  USB drive, backup files to share or restore, and Google Drive backup — which also keeps the
  shop's tills in step and restores a lost phone in full. No Google account is required; the
  selling screen shows "Not backed up" when the data exists only on that phone.
- English and Bahasa Melayu (switchable in the app); Android 5.0 and newer, phones and tablets.

## Documentation map

| Document | What it covers |
|---|---|
| [`docs/PHASES.md`](docs/PHASES.md) | Delivery phases, status, results, open questions |
| [`docs/BUILD.md`](docs/BUILD.md) | Toolchain, build/test commands, emulators, signing, release checklist |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | Why things are the way they are (dependencies, DB, sync, IDs, …) |
| [`docs/PLAY.md`](docs/PLAY.md) | Google Play preparation: listing, Data Safety answers, release steps |
| [`site/`](site/) | Public web page and privacy policy (GitHub Pages) |
| [`.claude/skills/lekaspos/`](.claude/skills/lekaspos/SKILL.md) | Project rules: architecture, conventions, performance budget, money, schema, sync, definition of done |

## Quick start

```powershell
$env:JAVA_HOME = 'D:\dev\jdk-21'; $env:ANDROID_HOME = 'D:\Android\Sdk'   # see docs/BUILD.md
.\gradlew.bat :core:test :app:testDebugUnitTest      # unit tests
.\gradlew.bat :app:assembleRelease                   # app\build\outputs\apk\release\app-release.apk
```

No toolchain yet? `.\scripts\setup-toolchain.ps1` installs a portable JDK 21 and the Android
SDK without Android Studio or admin rights.

Every push runs the full CI on GitHub Actions (unit tests, lint, APK-size check, emulator tests
on Android 5.0 and Android 16); each run's **apks** artifact holds installable debug and release
builds. Performance runs: Actions → Performance → Run workflow. See [`docs/BUILD.md`](docs/BUILD.md).

## Project layout

```
core/     pure Kotlin business rules (money, tax, pricing, barcodes, IDs, HLC, sync rules) + JVM tests
app/      Android app: SQLite data layer, UI, hardware, sync, perf suite + instrumented tests
scripts/  toolchain setup, emulator profiles, performance runs, cold-start measurement
docs/     phases, build/release, decisions
```

## Signing

See [`docs/BUILD.md#signing`](docs/BUILD.md#signing). Every release APK (public downloads, CI
artifacts, tester builds) is signed with the one release key, so each new build installs as an
update (D-051). The Google Play upload key, if the app goes on Play, is separate and stays with
the app owner.

## Google Cloud / OAuth setup (needed from Phase 6 — Google Drive sync)

The app talks to the Google Drive REST API directly and asks only for the
`https://www.googleapis.com/auth/drive.appdata` scope (a hidden, app-private folder in the
store's Google Drive). That scope is *non-sensitive*, so no paid security assessment is needed.

1. In the [Google Cloud console](https://console.cloud.google.com/), create a project
   (e.g. "LekasPOS").
2. **APIs & Services → Library:** enable the **Google Drive API**.
3. **OAuth consent screen:** user type *External*; app name "LekasPOS", support email, app logo
   (optional), links to the privacy policy and home page; add the scope
   `.../auth/drive.appdata`. Publish the app (move from *Testing* to *In production*): with only
   non-sensitive scopes no verification is required (without a logo). While the app is in
   *Testing*, only listed **test users** can sign in, and their sign-in expires after 7 days.
4. **Credentials → Create credentials → OAuth client ID → Android**, once per signing
   certificate, with package `com.lekaspos.app` and the certificate's SHA-1:
   - the **release** key (all release APKs; named "test" in the tester days):
     `69:63:1E:7C:98:0C:32:C9:39:C1:8D:B9:B9:48:01:54:2A:1B:84:B8`;
   - debug key: `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android`
     (debug builds use package `com.lekaspos.app.debug` → make a separate client for it);
   - on Google Play with Play App Signing using this same release key (D-051), no other client
     is needed; with a key Google generates, add one for its SHA-1 (Play Console → App integrity).
   Android clients have no client secret; nothing needs to be embedded in the app.
5. All devices of one store sign in with the **same Google account** (the store account), because
   the app-data folder is private per account.

Done for this repository (2026-09-30): project **LekasPOS** (ID `lekaspos`), Drive API enabled,
consent screen External with the `drive.appdata` scope only, home page and privacy policy on the
authorized domain `faizoken.github.io`, Android client "LekasPOS tester builds (test key)" for
`com.lekaspos.app` with the release key's SHA-1. For 1.0.0 the owner moves it to *In production*
(Audience → Publish app, D-051).
New settings can take from 5 minutes to a few hours to reach Google's servers.

## License

LekasPOS is free software, licensed under the **GNU General Public License, version 3**
(see [`LICENSE`](LICENSE)). You may use, study, share and modify it; if you distribute a
modified version, you must share its source under the same license.

## Release steps

1. Follow the checklist in [`docs/BUILD.md`](docs/BUILD.md#release-checklist-details-grow-with-each-phase).
2. Build an App Bundle for Play (`.\gradlew.bat :app:bundleRelease`) signed with the upload key.
3. Play Console: upload to an internal testing track first, fill in the Data Safety form
   (answers are maintained with the privacy policy — Phase 7), then promote.

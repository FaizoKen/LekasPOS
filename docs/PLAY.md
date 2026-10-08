# Google Play: what to fill in when publishing

Prepared in Phase 7 (2026-09-30), updated for 1.6.1 (2026-10-03). The app is **not** being
published yet (owner's decision); this page makes the first upload quick. Everything here must be
re-checked against the app at upload time — the answers describe what the app does as of version
1.6.1.

## Before the first upload (owner only)

Since 1.0.0 (D-051) the app is downloaded from the website, signed with the release key, and
Google sign-in is public. Going on Play then means:

1. Play Console developer account (one-time US$25, Google identity verification). This also
   covers the developer registration Google will require for installs outside Play from 2027.
2. Upload key: create it as in `docs/BUILD.md` ("Signing"), keep it and its password safe —
   never in the repo or CI. Enroll in **Play App Signing** with **"use my own app signing key"**
   and give Play the **release key** (Play's PEPK tool encrypts it): downloaded copies and Play
   installs then update each other, and the existing Android OAuth client keeps working.
3. Google Cloud project `lekaspos`: nothing to add (branding, links and *In production* were
   done for 1.0.0). Only if Play generates a new app signing key instead: add an Android OAuth
   client for its SHA-1 — and downloaded copies can no longer update from Play.
4. Build: `.\gradlew.bat :app:bundleRelease` with the upload key in `keystore.properties`
   (`keyKind=upload`), version code above every CI build.

## Store listing (draft)

- **App name:** LekasPOS
- **Short description (EN, ≤ 80):** Offline point of sale for small shops: sell, stock, staff, reports.
- **Short description (MS, ≤ 80):** POS tanpa internet untuk kedai kecil: jualan, stok, staf, laporan.
- **Full description (EN):** LekasPOS is a free, open-source point-of-sale app for grocery
  stores and mini markets. It works fully offline and stays fast with tens of thousands of
  products. Scan barcodes (Bluetooth, USB or camera), take cash, card, e-wallet or credit
  payments, print receipts on Bluetooth thermal printers and open the cash drawer. Manage stock,
  suppliers, purchases and stock counts; staff with PINs and roles; shifts and cash; customers
  and credit. Reports for any period, CSV import and export, daily backups, and optional sync
  between the shop's tills through its own Google Drive. English and Bahasa Melayu.
- **Category:** Business. **Contact:** mail@faizo.net (also the privacy policy's contact).
- **Privacy policy:** `https://faizoken.github.io/LekasPOS/privacy.html`
- **Screenshots:** phone (selling screen, payment, reports, stock) and 10-inch tablet (two-pane
  selling screen), in both languages.

## App content answers

- **Ads:** no ads.
- **App access:** no login needed. Staff PINs are optional and set by the shop; nothing to
  provide for review (if a reviewer build has a PIN, give it in "App access").
- **Target audience:** 18+ (business tool); not designed for children.
- **Content rating:** questionnaire → utility/productivity, no user-generated content shared
  between users, no violence etc. Note: the app handles payments recorded by the shop but makes
  no purchases or money transfers itself.
- **News / health / financial features declarations:** not a financial-services app (it records
  a shop's own sales; no loans, no money movement). Credit = the shop's customer tab.
- **Government app:** no.

## Data safety (draft answers)

Key facts: no analytics/ads/crash SDKs. Data leaves the device only when the user turns on
**Google Drive sync** (to their own Drive app folder, over HTTPS), turns on the **daily sales report**
(D-065: the month's daily totals as a CSV in a visible "LekasPOS" folder of their own Drive, scope
`drive.file`, over HTTPS), allows **error reports** (D-057:
asked once, off unless allowed; to the developer through the relay), or shares a file themselves.
The daily **update check** asks GitHub for the latest release (app and Android version only, no
user data); the Play build drops it (D-059, see Permissions below). Sync files and `.lekasbak`
backup files are not encrypted by the app (only in transit, by HTTPS) — the privacy policy says so.

- **Does the app collect or share user data?** Google counts data sent off the device as
  "collected" even when it goes to the user's own cloud account, so answer **Yes, collected**
  for the data that sync sends — conservative and accurate. Everything the shop enters is
  collected only into the shop's own storage and its own Google Drive; none of it reaches the
  developer, and none of it is shared:
  - Personal info: *Name* (customers and staff, entered by the shop), *Phone number*, *Email
    address*, *Address* (customers' details, entered by the shop) — optional, app functionality.
  - Personal info: *Email address* (the Google account, kept on the phone to show which account
    syncs or receives the daily report and to connect to the same account again) — optional, app
    functionality.
  - Financial info: *Purchase history* (the shop's sales records) and *Other financial info*
    (customers' credit balances) — optional, app functionality.
  - App activity: *Other actions* (the audit log of sensitive staff actions) — optional, app
    functionality.
  - Photos and videos: *Photos* (product pictures the shop takes or picks, D-066: 240 px, kept with the
    shop's data and synced to its own Drive like products) — optional, app functionality.
  - App info and performance: *Crash logs* and *Diagnostics* (error reports, D-057) — optional
    (the user allows them), purpose *Analytics* (finding and fixing bugs); not shared. A report sent
    by hand from Diagnostics may hold an *Email address* or *Phone number* the user types for a reply
    — optional, purpose *Developer communications*.
  - Device or other IDs: the error reports' random install id (a new one each time reports are
    turned on, deleted when they are turned off; not linked to the user's identity or any device
    ID) — optional, purposes *App functionality* and *Analytics* (counting how many tills a bug
    affects); not shared.
  - Location: none.
- **Shared with third parties:** no (Google Drive stores the user's own data on their behalf;
  Cloudflare and GitHub carry and store error reports as the developer's service providers; the
  relay uses the phone's IP address only to rate-limit and does not store it).
- **Processed ephemerally:** no. **Required or optional:** optional (sync is off by default).
- **Encrypted in transit:** yes (HTTPS to Google and to the report relay).
- **Users can request deletion:** yes — uninstall / clear storage deletes local data; for synced
  data, first turn off sync on every till (Settings → Google Drive backup → Turn off sync — a till
  still syncing publishes everything again), then Drive on the web → Settings → Manage apps →
  LekasPOS → Options → Delete hidden app data (explained in the privacy policy). The daily report's
  files are ordinary files in the "LekasPOS" folder of the user's Drive, deleted there. Error reports:
  on request (the privacy policy's contact).

Re-check before submitting: if a later version changes what error reports hold, adds a server, or
any SDK that sends data, these answers and the privacy policy change first.

## Permissions to explain if Play asks

- `BLUETOOTH_CONNECT` (and `BLUETOOTH` ≤ API 30): paired receipt printers and serial scanners;
  no scanning, no location.
- `CAMERA`: optional barcode scanning (frames processed on the device) and product photos (D-066:
  Android's camera app takes the picture the shop asked for; kept small with the shop's data).
- `INTERNET`, `ACCESS_NETWORK_STATE`: Google Drive sync, error reports when the shop allows them,
  and (downloaded builds only) the daily update check against GitHub releases — the Play build
  drops the update check (D-059, next item).
- `REQUEST_INSTALL_PACKAGES`: **must not be in the Play build.** The downloaded app updates itself
  from GitHub (D-059); Play forbids apps that update themselves outside Play and allows this
  permission only for apps whose core purpose is installing apps. Before the first upload, make the
  Play build leave it out (`tools:node="remove"` in a Play-only manifest) and turn the update check
  off there; a copy installed by Play already never updates itself (`AppUpdates.selfUpdate`).
- `WAKE_LOCK`: background backups and sync can finish with the screen off.
- `RECEIVE_BOOT_COMPLETED` (from WorkManager's manifest): the daily backup, sync and update-check
  jobs are scheduled again after the phone restarts.
- No foreground service: the app declares no `FOREGROUND_SERVICE` permission, so Play's
  foreground-service declaration does not apply (check the merged manifest of the Play build).

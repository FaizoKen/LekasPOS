# Architecture

## 1. Shape of the system

```
 ┌──────────────────────────── :app (Android) ─────────────────────────────┐
 │  ui/*  (Activities, Views, RecyclerView adapters, dialogs)             │
 │     │ observes StateFlow / calls suspend functions                       │
 │  domain/*  (app-scoped state holders + use-case services)               │
 │     │ CartSession, SaleService, StockService, ShiftService, ...          │
 │  data/*  (Db, schema, migrations, DAOs — the only place with SQL)       │
 │  hw/*    (printer queue, ESC/POS transport, drawer, scanner input)      │
 │  sync/*  (SyncEngine, SyncProvider impls: Google Drive, local folder)   │
 │  perf/*  (perf data generator + perf suite, shared by tests + UI)       │
 └──────────────────────────────────┬───────────────────────────────────────┘
                                    │ depends on
 ┌──────────────────────────── :core (pure Kotlin/JVM) ────────────────────┐
 │  money, tax, pricing (cart totals, discounts, rounding), barcode       │
 │  (EAN/UPC, weighed), text (search normalisation), time (days, HLC),    │
 │  id (ID layout), sync (event model, LWW merge rules, codec), csv,      │
 │  escpos (command builder, receipt layout), report math                 │
 └──────────────────────────────────────────────────────────────────────────┘
```

Dependency direction is strictly downward: `ui → domain → data/hw/sync → :core`.
`:core` has no Android imports and no I/O; it is tested on the JVM.

## 2. Packages (`:app`, root `com.lekaspos`)

| Package | Contents |
|---|---|
| `app` | `LekasApp` (Application), `AppGraph` (manual DI: lazy singletons) |
| `data.db` | `Db` wrapper, `DbOpenHelper`, `Schema` (DDL), `Migrations`, `Seed`, SQL helpers |
| `data.*` | DAOs grouped by aggregate (`product`, `sale`, `stock`, `staff`, ...) |
| `domain.*` | State holders and services; call DAOs and `:core` rules |
| `ui.*` | One sub-package per screen (`sell`, `products`, `inventory`, `reports`, `settings`, `diag`) |
| `hw.*` | `printer` (queue, transports), `scanner` (HID key buffer, SPP), `drawer` |
| `sync` | `SyncEngine`, `SyncProvider` + `FolderProvider`, `SyncProviders`, `drive` (`DriveProvider`, `DriveAuth`) |
| `data.sync`, `data.backup` | outbox, segments (`SegmentCodec`, `SyncDao`), `Importer`, `Backfill`; `BackupFiles`, `Restore` |
| `perf` | `PerfDataGenerator`, `PerfSuite`, `PerfReport` |
| `util` | `Log`, `Dispatchers`, `StrictModeSetup`, small helpers |

## 3. Threading model

| Thread | Used for | Rule |
|---|---|---|
| main | Views, input, drawing | never touches disk, network, Bluetooth, or `Db` |
| `db-writer` (1 thread) | every DB write transaction | writes are serialized; no network/BT inside |
| read pool (`Dispatchers.IO.limitedParallelism(3)`) | DB reads, file reads | WAL lets reads run while the writer commits |
| IO (`Dispatchers.IO`) | files, backups, CSV, HTTP | streaming only |
| `printer` (1 per printer) | Bluetooth socket I/O | owns the print queue and reconnect loop |
| WorkManager executor | sync, backup, archive jobs | cancellable; resumes after process death |

Long local jobs must keep the CPU awake: when the screen turns off, the device suspends and a
background thread simply stops (seen on the emulator during a perf run). Use WorkManager (it
holds a wake lock while a worker runs) or a bounded `PARTIAL_WAKE_LOCK` (`acquire(timeout)`,
released in `finally`) — never an unbounded one.

`Db.write { tx -> }` switches to the writer thread and runs the block inside one transaction
(`beginTransactionNonExclusive`); `tx` offers `nextId()`, `hlcNow()`, cached compiled
statements (`insert/update/exec/updateOrInsert`). IDs are reserved *before* BEGIN (pass
`reserveIds` for bulk work). `Db.read { }` runs on the read pool. `writeBlocking`/`readBlocking`
exist for workers and tests; every entry point throws on the main thread. Debug builds enable
StrictMode (disk + network detection, leaked cursors/closeables/activities) with log penalties;
network on the main thread already throws on API 21+.

## 4. State and screens

- App-scoped state holders (in `AppGraph`) own screen state: the current cart, the signed-in
  staff session, printer status, sync status. They expose `StateFlow`s. Activities are thin:
  they render state and forward input. Recreating an Activity costs only a re-render.
- The current bill lives in the `cart`/`cart_line` tables; every change is persisted on the
  writer thread (UI updates first from memory, persistence follows asynchronously). After a
  crash or kill, `CartSession` reloads the open cart → "crash-safe restart back to the bill".
- One Activity per major screen. The selling screen (`SellActivity`) is the launcher and home
  (`launchMode=singleTask`). Small interactions (discount, payment) are dialogs built from our own
  layouts; the bill line's quantity is changed in place (§8b).
- Collect flows only between `onStart` and `onStop` (a scope per Activity start); cancel in
  `onStop`. Never keep an Activity/View reference in a singleton.
- Activities declare `android:configChanges="keyboard|keyboardHidden|navigation"`: Bluetooth
  HID scanners connecting, sleeping and reconnecting must not recreate the selling screen.
  Only the selling screen, the lock screen, the camera scanner and Diagnostics are recreated
  on rotation (the selling screen's layout differs by width). Every other screen also declares
  `orientation|screenSize|screenLayout|smallestScreenSize` (D-054): a rotation kept losing typed
  form input, half-written exports and open dialogs. Their layouts must therefore work at any
  width without a recreate (no `-land` / `-w600dp` resources for them). The selling screen locks
  the orientation while the payment dialog is open.
- A back-office screen restored by Android into a new process runs nothing until the signed-in
  staff member and the settings are loaded (`ScreenActivity.onStart`); `StaffSession.perms` is 0
  until then — "not loaded" never means "owner with every permission" (D-054).
- `appScope` has an exception handler: background work that fails is logged and never crashes
  the till (debug builds still crash, so tests see it).

## 5. Platform rules (targetSdk 36)

- **Edge-to-edge is enforced** on Android 15+: every Activity applies system-bar and IME
  insets to its root (`View.setOnApplyWindowInsetsListener`) via `ui.Insets.apply(root)`.
- **Predictive back**: plain screens just finish (the system animates it). A screen that
  intercepts back registers an `OnBackInvokedCallback` on API 33+ only while it needs it, and
  handles `KEYCODE_BACK` in `dispatchKeyEvent` below 33 — never `onBackPressed()` (lint
  `GestureBackNavigation`, not called for gestures).
- **Large screens** (sw ≥ 600dp) ignore orientation locks on Android 16 — every screen must
  work in both orientations and when resized.
- **Bluetooth permissions**: API 31+ `BLUETOOTH_CONNECT` (+ `BLUETOOTH_SCAN` with
  `neverForLocation` only for discovery). API ≤ 30: `BLUETOOTH` (`maxSdkVersion=30`);
  `BLUETOOTH_ADMIN` and `ACCESS_FINE_LOCATION` only if discovery is ever added (there is none:
  paired devices only, D-027). Bonded-device selection needs no location permission.
- **uiMode** is in every activity's `configChanges` (no night resources): a night-mode switch
  never recreates a screen, so a payment or a typed form is never lost to it (D-055).
- **Auto Backup is disabled** (`allowBackup=false`, empty data-extraction rules): restoring a
  copied DB onto another phone would clone the device identity and corrupt multi-device sync.
- No cleartext HTTP (`usesCleartextTraffic=false`). File sharing only through `FileProvider`.

## 6. Startup (cold start < 2 s on a 1 GB / API 21 device)

1. `LekasApp.onCreate`: install crash handler + StrictMode (debug), create `AppGraph` (no I/O).
   WorkManager uses on-demand initialization (`Configuration.Provider`; its
   `InitializationProvider` entry is removed from the manifest).
2. `SellActivity.onCreate`: inflate a flat layout, show it immediately. Kick off, off the
   main thread: open DB (migrate if needed), load the open cart, categories and settings.
3. When data arrives: bind views, call `reportFullyDrawn()` (measured by the perf scripts).
4. After the first frame + a few seconds: schedule sync/backup work, connect the printer.
No network, Play Services or Bluetooth calls happen before the selling screen is usable.

## 7. Hardware (Phase 2)

- Receipts: `ReceiptBuilder` builds a `ReceiptDoc` from the *stored* sale (reprints are
  identical); `:core` `ReceiptLayout` lays it out on a 32/42/48-column grid (CJK = 2 columns);
  the same lines feed the ESC/POS text encoder, the image renderer (`hw.printer.ReceiptRenderer`,
  any script) and the share picture/PDF. Auto mode prints text unless a character cannot be
  printed (e.g. Tamil, or Chinese on a Latin printer), then prints a picture.
- Printer: `PrinterService` owns one thread, takes `print_job` rows in order — drawer pulses
  first (D-054) — keeps an SPP connection (`hw.bt.SppLink`: secure, insecure, then RFCOMM channel
  1 by reflection for printers without an SDP record; the socket is assigned before `connect()`
  so a stop aborts it), closes it after 45 s idle, reconnects with backoff (2–60 s, stops after
  10 failures until woken) and marks a job done only after its bytes were written. Large (image)
  jobs are paced in 1 KB chunks at about 500 dot rows a second (printers without flow control).
  `reconnect()` (settings changed) keeps a live link to the same printer. Automatic sale receipts
  still waiting after 10 min (printer off) expire (FAILED, "expired") instead of flooding out
  later; reprints, shift reports and test pages wait. Printing always happens after the sale
  commit; the queue survives restarts. The loop restarts itself 10 s after any error (an out of
  memory while drawing a job fails that job), keeps a job's drawn bytes across retries, and a
  job whose bytes went out is only marked done after a restart (D-055). Picture receipts are
  drawn in 256-row bands into one reused bitmap (memory under 1 MB). The first receipt of a sale prints once: asked again it is
  a copy (REPRINT permission + audit), and so is a receipt shared from the sales history.
- Text encoding: every replacement keeps the column count of the layout ("…" → "."); characters
  the printer cannot print (also €, £, GB18030 4-byte characters) make Auto print a picture.
- Cash drawer: ESC/POS pulse (`ESC p`, pin 2 or 5) as its own DRAWER job, enqueued with cash
  sales/refunds and by the audited manual "open drawer"; pulses older than 2 min are dropped (D-031).
- Scanners: keyboard-wedge (HID) scanners are read in `SellActivity.dispatchKeyEvent` through
  `:core` `ScanBuffer` (burst timing, Enter/Tab or idle), so no field needs focus; slow typing
  goes to the search field (a scanner burst typed into the focused search field is taken out and
  scanned). Every dialog swallows Enter/Tab/Space so a scanner's Enter never presses a focused
  button, and number pads drop scanner-speed digits (`DialogKeys`, D-054). SPP scanners
  (`hw.scanner.SppScanner`) run while the selling screen is visible; a code without an Enter
  suffix is delivered after 250 ms of silence. The key-timing logic
  lives in `ui.common.ScanInput`, shared by the selling, receiving, counting and product-picker screens. Camera scanning
  (`ui.scan.CameraScanActivity`, Camera1 + ZXing 3.3.3) either adds every item to the bill or
  returns one code. All paths end in `CartSession.scan`.
- Bluetooth permissions: paired devices only (D-027); `BLUETOOTH_CONNECT` is requested when
  choosing a device on Android 12+.
- Back handling on the selling screen: `OnBackInvokedCallback` on API 33+ (registered only
  while back should close the catalogue/search), `KEYCODE_BACK` in `dispatchKeyEvent` below.

## 7a. Staff, permissions, shifts, customers (Phase 4)

- `StaffSession` (app-scoped): while no active staff member has a PIN the till runs as the seed
  owner with every permission (the Phase 1–3 behaviour). The first PIN must be an owner's; it
  signs that owner in and turns PIN login on. The signed-in staff id is kept in `meta`, so a
  restart returns to the same cashier; Lock / idle timeout (per device, `dev.lock.minutes`)
  clears it. The idle time is checked when a screen starts and before a touch or key counts
  (`StaffSession.activity()`): after the phone's screen was off the first tap used to reset it
  (D-055). When the app goes out of sight the last activity time is stored (`session.away_at`),
  so a new process still knows how long the till was idle. Wrong PINs count per person
  (`pin.fails.<id>`), 5 free tries, then 30 s doubling to 15 min, measured by the wall clock and,
  while the phone has not restarted, by the time since boot (setting the clock cannot skip it); a
  clock set back makes the wait count from now. A removed staff member cannot sign in or approve;
  only owners manage owners (D-055).
- `LockActivity` covers the selling screen whenever `state.locked`; secondary screens that find
  the till locked jump back home (`ScreenActivity.onStart`). Back on the lock screen leaves the app.
- `PermissionGate`: every sensitive service call takes an optional `Approval` and resolves an
  `Actor(staffId, approvedBy)`. UI: `withApproval(perm)` = one-shot manager PIN for one action
  (selling screen, voids, refunds, reprints, drawer, cash moves, credit); `requireAccess(perm)`
  / `guard(perm)` = a screen keeps the approval until it closes (products, stock, settings,
  staff, audit log, shift report). Approvals are audited (`approved_by`, or an APPROVAL entry).
- `ShiftService`: one open shift per till; sales/refunds/voids/credit repayments written while
  it is open carry its id; expected cash is recomputed from those events (D-038). Blind close
  for staff without SHIFT_REPORT; the report prints as a PrintJobKind.SHIFT job.
- `CustomerService` + checkout: credit tenders need the bill's customer, CREDIT_SALE, and over
  the limit a CREDIT_LIMIT approval, all re-checked inside the sale transaction (D-039).

## 7b. Reports and CSV (Phase 5)

- `ReportService` builds the report screen from summaries only (`sum_day*`, and whole months from
  `sum_month_product`, D-043); `:core` `Period`/`Preset`/`MonthSplit`/`Buckets` do the calendar
  maths (weeks start Monday). REPORTS permission.
- CSV: `:core` `CsvWriter`/`CsvReader` (streaming), `ProductCsv` (columns, header aliases, row
  validation). `ScreenActivity.exportCsv(name) { out -> … }` streams any export to a shared
  cache file (FileProvider) or a user-picked document (SAF) — D-041.
- `ProductCsvService`: preview (read + validate everything, write nothing), then import in
  200-row transactions in the app scope with progress in a StateFlow — D-042.

## 8. Sync and backup (Phase 6, details in `sync.md`)

`SyncEngine` talks only to the `SyncProvider` interface. Providers: `DriveProvider` (REST over
`HttpURLConnection`, `drive.appdata` scope, GIS tokens via `DriveAuth`) and `FolderProvider` (a
directory; used by tests to simulate several devices). Only `com.lekaspos.sync` knows about
Drive: other code sees `SyncProviders.connect/finish/forId` and the generic `AuthNeeded`.
`SyncWorker` and `BackupWorker` (WorkManager, initialised on demand, scheduled after the
selling screen is usable); `AppGraph.syncSoon()` after each sale. Screens: Settings → Sync,
Settings → Backup & restore. `BackupService` makes and prunes backups; `Restore` stages a
restore that `Db.open` applies before opening the database (D-044).

## 8a. Language and release polish (Phase 7)

- App language (Settings → App language, D-046): `AppLanguage` wraps every activity's and the
  Application's base context (`attachBaseContext`); new activities must extend `ScreenActivity` or
  add the same override. The receipt language is a separate store setting.
- Every user-visible text is a resource in `values/` and `values-ms/`; failures shown to users are
  mapped to strings (e.g. sync errors are stored as codes such as `offline`, not raw messages).
- `ScreenshotsTest` saves the main screens in both languages (CI artifact `instrumented-api*` →
  `screens-api*`) for layout review; the release smoke job gates tester builds.
- Privacy policy: `site/privacy.html` (+ `privasi.html`), published by `pages.yml`; keep it and
  `docs/PLAY.md` (Data Safety answers) true whenever data handling changes.

## 8b. Cashier-first selling screen (Phase 10, D-049)

- Every control a cashier uses carries a word, not only an icon: "Price check", "Items" (phones),
  "Menu"; the camera scanner sits inside the search box. Pills in the top bar appear only when
  there is something to see (held bills, printer, backup).
- Only registered products are sold (D-050): an unknown barcode offers "Add product" (then it is
  on the bill) or Cancel; items without a barcode are products picked from Items (sold by weight
  or with the price typed at the till). Old bills and sales with unregistered lines still load.
- The selected bill line (the one scanned or changed last, or tapped) shows Remove, −, quantity,
  +, More in place; "−" stops at one (taking the last one off is Remove). Other lines stay one row.
- Totals bar: summary, a big TOTAL, then Hold / Discount / PAY. The empty bill explains how to
  start and shows the last sale of this session (`CheckoutService.last`: change again, a copy).
- Payment dialog order: total, cash received + live change, one-tap notes, every method (3 per
  row), then the keypad — nothing a cashier needs falls below the fold on a 5-inch phone.
- Menu: cashier jobs first; the back office under one "Manage shop" submenu.
- Catalogue: "Popular" tab first once the shop has sales (`PopularItems`: ranking from
  `sum_day_product` over 30 days, kept 10 minutes; products read fresh by id). Tiles of products
  on the bill are highlighted with "×n"; picking a search result closes the search.

## 9. Errors, logging, crash safety

- Data-path exceptions are never swallowed: they propagate to the use case, which reports a
  user-facing message and appends a line to the local error log (ring buffer file,
  `files/logs/`), which the Diagnostics screen can export.
- Uncaught exceptions: logged to the error log before the default handler runs.
- Money/sale writes are atomic transactions; nothing is printed or shown as "paid" until the
  commit returns.

## 10. Dependencies (every one justified; versions pinned for minSdk 21)

| Dependency | Why | Size impact (after R8) | Pin reason |
|---|---|---|---|
| Kotlin stdlib | language runtime | ~100–200 KB | — |
| kotlinx-coroutines-android | structured concurrency, StateFlow | ~150 KB | — |
| androidx.recyclerview 1.4.0 | required list widget | ~150 KB | last minSdk-21 line |
| androidx.core 1.17.0 | ContextCompat, FileProvider (transitive anyway) | ~100 KB | 1.18+ needs minSdk 23 |
| androidx.work 2.10.5 | background sync (spec) | ~250 KB incl. Room runtime | 2.11 needs 23, 2.12 needs 24 |
| play-services-auth 21.4.0 (Phase 6) | Google Identity Services authorization | ~300 KB | 21.5+ needs minSdk 23 |
| ZXing core 3.3.3 (Phase 2) | camera barcode decode + QR encode | ~150–250 KB | 3.4+ uses Java 8 APIs missing below API 24 |
| Test: JUnit 4, androidx.test runner 1.7.0 / ext-junit 1.3.0 | tests | none in APK | minSdk 21 |

Not used, on purpose: Compose, AppCompat, Material Components, Fragments, Navigation, Room
(for our data), Hilt/Dagger/Koin, Retrofit/OkHttp, Gson/Moshi/kotlinx.serialization, Glide,
java.time desugaring. Use: platform `Theme.Material`, `android.util.JsonReader/JsonWriter`,
`HttpURLConnection`, `android.graphics.pdf.PdfDocument`, `java.util.Calendar`.
Adding anything requires an entry in `docs/DECISIONS.md` and a dependency-constraint pin
that keeps minSdk 21 (the manifest merger fails the build if a library needs more).

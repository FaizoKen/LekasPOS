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
| `sync.*` | `SyncEngine`, `SyncProvider`, `drive`, `folder`, `backup`, `archive` |
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
  (`launchMode=singleTask`). Small interactions (qty, discount, payment) are dialogs built from
  our own layouts.
- Collect flows only between `onStart` and `onStop` (a scope per Activity start); cancel in
  `onStop`. Never keep an Activity/View reference in a singleton.
- Activities declare `android:configChanges="keyboard|keyboardHidden|navigation"`: Bluetooth
  HID scanners connecting, sleeping and reconnecting must not recreate the selling screen.
  Rotation and screen-size changes still recreate (layouts differ by orientation).

## 5. Platform rules (targetSdk 36)

- **Edge-to-edge is enforced** on Android 15+: every Activity applies system-bar and IME
  insets to its root (`View.setOnApplyWindowInsetsListener`) via `ui.Insets.apply(root)`.
- **Predictive back**: use `OnBackInvokedDispatcher` on API 33+, `onBackPressed()` below,
  through one helper (`ui.BackHandler`).
- **Large screens** (sw ≥ 600dp) ignore orientation locks on Android 16 — every screen must
  work in both orientations and when resized.
- **Bluetooth permissions**: API 31+ `BLUETOOTH_CONNECT` (+ `BLUETOOTH_SCAN` with
  `neverForLocation` only for discovery). API ≤ 30: `BLUETOOTH`, `BLUETOOTH_ADMIN`
  (`maxSdkVersion=30`) and `ACCESS_FINE_LOCATION` (`maxSdkVersion=30`) only for discovery.
  Bonded-device selection needs no location permission.
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

## 7. Hardware (details arrive in Phase 2)

- Printer: ESC/POS bytes are produced by `:core` (`escpos`), sent over Bluetooth Classic SPP
  by `hw.printer`. A persistent `print_job` queue survives disconnects and restarts; the
  printer thread reconnects with backoff. Printing always happens after the sale commit.
- Cash drawer: ESC/POS pulse (`ESC p`) through the printer. Auto-open on cash payment; manual
  open requires permission and writes an audit entry.
- Scanner: HID (keyboard) scanners are read in `SellActivity.dispatchKeyEvent` with an
  inter-key timing buffer, so no text field needs focus. SPP scanners are read by a
  background socket reader. Both feed the same `BarcodeInput` pipeline.

## 8. Sync (details in `sync.md`)

`SyncEngine` talks only to the `SyncProvider` interface. Providers: `GoogleDriveProvider`
(REST over `HttpURLConnection`, `drive.appdata` scope) and `FolderProvider` (a directory; used
by tests to simulate several devices, and usable with USB/SD storage).

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
| ZXing core (Phase 2, only if it fits) | camera barcode decode + QR encode | ~250 KB | — |
| Test: JUnit 4, androidx.test runner 1.7.0 / ext-junit 1.3.0 | tests | none in APK | minSdk 21 |

Not used, on purpose: Compose, AppCompat, Material Components, Fragments, Navigation, Room
(for our data), Hilt/Dagger/Koin, Retrofit/OkHttp, Gson/Moshi/kotlinx.serialization, Glide,
java.time desugaring. Use: platform `Theme.Material`, `android.util.JsonReader/JsonWriter`,
`HttpURLConnection`, `android.graphics.pdf.PdfDocument`, `java.util.Calendar`.
Adding anything requires an entry in `docs/DECISIONS.md` and a dependency-constraint pin
that keeps minSdk 21 (the manifest merger fails the build if a library needs more).

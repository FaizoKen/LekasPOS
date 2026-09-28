# Performance budget and how it is measured

Target hardware: Android Go class phones/tablets, 1–2 GB RAM, slow eMMC, API 21 → 36.
Numbers are p95 over the listed iterations unless stated. "DB" budgets are what the perf
suite measures directly; "user" budgets include UI work and are checked on devices/emulators.

## 1. Budget

| ID | Scenario | Data scale | Budget |
|---|---|---|---|
| `apk_size` | release APK (universal) | — | < 8 MB (target < 5 MB) |
| `cold_start` | launcher tap → selling screen usable (`reportFullyDrawn`) | full | < 2000 ms (user) |
| `barcode_lookup` | barcode → product row (incl. pack/scale codes) | 50k products | ≤ 10 ms DB |
| `scan_to_cart` | lookup + add to cart model + totals recompute | 50k products, 30-line cart | ≤ 20 ms DB+logic; < 50 ms user |
| `search_*` | FTS search, first 50 rows (1–4 chars, multi-word, digits, no match) | 50k products | ≤ 50 ms DB; < 100 ms user |
| `category_page` | first 60 products of a category | 50k products | ≤ 30 ms |
| `sale_commit` | complete a 5-line cash sale (stock, summaries, outbox), fsync | 1M lines | ≤ 150 ms |
| `cart_persist` | persist one cart-line change (writer thread, async) | — | ≤ 100 ms (not user-blocking) |
| `history_page_first` / `_deep` | 50 sales, newest / ~1 year back (keyset) | 1M lines | ≤ 50 ms |
| `receipt_lookup` | sale by receipt number | 1M lines | ≤ 10 ms |
| `product_history_page` | 50 most recent lines of one product | 1M lines | ≤ 50 ms |
| `stock_level` | one product's stock | 1M lines | ≤ 5 ms |
| `low_stock_page` | first 50 low-stock products | 50k products | ≤ 300 ms |
| `report_day` | day totals + by payment + top 10 products | 1M lines | ≤ 300 ms |
| `report_month` | same for a month | 1M lines | ≤ 1000 ms |
| `report_year` | same for a year (informational) | 1M lines | ≤ 3000 ms |
| `db_open` | open existing DB + pragmas + version check | 1M lines | ≤ 300 ms |
| `heap` | Java heap after GC, steady selling | — | ≤ 48 MB, no growth over 1,000 sales |

## 2. The perf suite (`com.lekaspos.perf`, in `main` so it ships in release builds)

- `PerfDataGenerator` builds a separate database file (`perf.db`, never the real one),
  deterministically (fixed seed): categories, products with realistic EN/BM grocery names,
  valid EAN-13 barcodes, pack barcodes, scale PLUs, stock receipts, and a year of sales
  (1–8 lines each, cash/card/e-wallet mix, some refunds and voids) — using bulk inserts
  with compiled statements, then summary/stock tables built with `INSERT … SELECT`.
  Scales: `QUICK` = 5,000 products / 20,000 sales (~80k lines);
  `FULL` = 50,000 products / 250,000 sales (~1,000,000 lines).
- `PerfSuite` runs every scenario above against the generated DB through the **same DAO code
  the app uses** (warm-up, then N timed iterations with `System.nanoTime`), collects
  min/p50/p95/p99/max, compares with the budget, and runs `EXPLAIN QUERY PLAN` on every
  registered hot query (fails on a full scan of a large table).
- Output: `PerfReport` → readable text + JSON, written to logcat (tag `LekasPerf`) and to
  `Android/data/<pkg>/files/perf/perf-<timestamp>.json` (pull with adb).

Entry points:
1. **Release-build verdict** — `scripts/run-perf.ps1 -Scale QUICK|FULL [-Serial …]` installs the
   release APK, starts the Diagnostics screen with `--es autorun <scale>` (the activity is
   exported only to `android.permission.DUMP`, i.e. `adb shell`), waits for `LekasPerf: DONE`
   and pulls the text/JSON report into `perf-results/<device>/`.
2. **Instrumented test** `PerfSuiteTest` (debug build) — always enforces the query-plan checks;
   enforces timing budgets only with `-e assertBudgets true` (debuggable builds are slower):
   `adb shell am instrument -w -e class com.lekaspos.perf.PerfSuiteTest -e perfScale QUICK com.lekaspos.app.debug.test/androidx.test.runner.AndroidJUnitRunner`
3. **In-app Diagnostics screen** — home → "Diagnostics & performance test" → quick/full, on a
   real device with the release build; shows pass/fail, shares the report, "Delete test data"
   removes `perf.db`. This is how real-device numbers are collected from testers.

Emulator notes (API 21 image, observed 2026-09-28):
- `vm.heapSize` in the AVD config does not change `memoryClass`; for heap-limit tests start
  the emulator with `-prop dalvik.vm.heapgrowthlimit=64m`.
- FULL-scale generation with the Diagnostics UI visible crashed 3 times out of 4 with SIGSEGV
  in ART's concurrent GC (`GCDaemon`, `MarkSweep::ProcessMarkStack` →
  `DelayReferenceReferentVisitor`), in both debug and release builds; the same FULL run
  without UI (instrumentation) completed cleanly. This matches known Android 5.0 ART GC bugs
  under heavy allocation. Mitigations in place: no cursors (finalizable objects) in the
  generator loop, progress updates throttled to ~3/s, partial wake lock. Needs confirmation
  on real Android 5.x hardware; newer Android versions use a different GC.
- With the screen off and no wake lock, the emulator suspends like a phone and the run stalls
  (the perf runner now holds a bounded partial wake lock; `run-perf.ps1 -ScreenOff` relies on it).
- `logcat -c` does not clear the buffer; `run-perf.ps1` filters by the app's PID.
- `/data/data/...` files are pullable only with `adb root` (emulators); real devices rely on
  logcat or the Share button.
- Every newly created database logs `E/SQLiteLog: (1) no such table: meta` once (API 21
  image, debug and release). The statement still succeeds and all tests pass; the exact
  source is not pinned down (a WAL checkpoint right after creation did not change it).
  Treat it as harmless noise unless it starts appearing for existing databases.

Known scaling note: `report_year` (≈550k `sum_day_product` rows at FULL) takes ~1.3 s on
the emulator — fine for the budget there, but low-end phones may take several seconds. Phase 5
adds a per-month product summary for ranges longer than ~2 months.

## 3. Cold start

`scripts/measure-startup.ps1 -Runs 10` force-stops the app, launches it with
`adb shell am start -S -W`, and reports `TotalTime` plus the `Fully drawn` time from logcat,
min/median/max. Run on the release APK.

## 4. Low-end emulator profiles

`scripts/create-avds.ps1` creates:
- `lekas-api21` — `system-images;android-21;default;x86_64`, 1024 MB RAM, 2 cores,
  480×854 (5-inch class), 64 MB VM heap, 4 GB data partition.
- `lekas-api36` — `system-images;android-36;default;x86_64`, 2048 MB RAM (modern Android
  does not boot usefully in 1 GB; Android Go on API 36 needs 2 GB), 2 cores, 720×1280.
Emulators on a desktop CPU are faster than real low-end phones but disk-throttled
differently: treat emulator results as a regression signal, real devices as the verdict.

## 5. Techniques that keep us inside the budget

- Indexed lookups only; partial indexes for `deleted = 0`; keyset pagination.
- Summary tables for reports; FTS4 with prefix indexes for search; 1-char search via index.
- Compiled, reused `SQLiteStatement`s for hot inserts; one transaction per user action.
- Cart changes: memory first (instant UI), DB persistence async on the writer thread.
- RecyclerView with stable IDs, `DiffUtil` off the main thread for big lists, fixed-size items,
  no nested weights, flat layouts (`ConstraintLayout` is not used — plain `LinearLayout`/
  `FrameLayout` + custom views where needed).
- No work on `Application.onCreate` beyond object creation; WorkManager on-demand init.
- Streams (never whole files) for CSV, backups, sync; bounded buffers (≤ 64 KB).
- Bitmaps: only the receipt logo (≤ 576 px wide, 1-bit) — no product images.

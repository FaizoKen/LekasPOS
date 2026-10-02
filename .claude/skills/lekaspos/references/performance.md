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
| `receipt_text` | build a stored 5-line receipt + ESC/POS text job (printer thread) | 1M lines | ≤ 50 ms |
| `receipt_image` | same, rendered as a 384-dot picture (Chinese/Tamil receipts) | 1M lines | ≤ 500 ms |
| `receive_commit` | a 20-line delivery: purchase, movements, 20 average-cost updates, outbox | 1M lines | ≤ 300 ms |
| `stock_history_page` | 50 newest stock events of a popular product (3 merged index reads) | 1M lines | ≤ 50 ms |
| `movement_page` / `purchase_page` / `count_page` | first page of the stock-change log, deliveries, a count | FULL | ≤ 50 ms |
| `shift_report` | a full day's shift report (7 index-range aggregates on `shift_id`) | FULL (a shift a day) | ≤ 300 ms |
| `shift_page` / `shift_current` | past shifts page; this till's open shift | FULL | ≤ 50 / 10 ms |
| `customer_page` / `customer_phone` / `statement_page` | customer list (name prefix), phone prefix, a regular's credit statement | FULL (2,000 customers, 31k credit entries) | ≤ 50 ms |
| `audit_page` | newest 50 activity-log entries | FULL | ≤ 50 ms |
| `pin_check` | one PBKDF2-SHA256 PIN verification (4,000 iterations) — runs off the main thread | — | ≤ 300 ms |
| `low_stock_count` | how many products are low (scans the catalogue; inventory screen only) | 50k products | ≤ 300 ms |
| `history_page_first` / `_deep` | 50 sales, newest / ~1 year back (keyset) | 1M lines | ≤ 50 ms |
| `receipt_lookup` | sale by receipt number | 1M lines | ≤ 10 ms |
| `product_history_page` | 50 most recent lines of one product | 1M lines | ≤ 50 ms |
| `stock_level` | one product's stock | 1M lines | ≤ 5 ms |
| `low_stock_page` | first 50 low-stock products | 50k products | ≤ 300 ms |
| `report_day` | the whole report screen (`ReportService.build`): totals + period before, buckets, payments, cashiers, categories, top 20 | 1M lines | ≤ 300 ms |
| `report_month` | same for 30 days | 1M lines | ≤ 1000 ms |
| `report_year` / `report_calendar_year` | same for 365 days / a calendar year (whole years, months less days and loose days, `RangePlan`, D-043, D-058) | 1M lines | ≤ 3000 ms |
| `slow_movers` / `stock_value` | products with stock not sold in 30 days (list, count and value in one reading); stock at cost by category | 50k products | ≤ 1000 / 500 ms |
| `popular_ranking` | best sellers of 30 days (D-049), made in the background: the tab shows the kept ranking (D-058) | 1M lines | ≤ 1000 ms |
| `popular_items` | the Popular tab's tiles for the kept ranking: what the tab waits for | 50k products | ≤ 50 ms |
| `export_receipts_month` / `export_products` | a month of receipts (~20k) / every product as CSV, streamed | FULL | ≤ 10 s / 20 s (n = 1) |
| `import_chunk_200` | one import transaction: 200 CSV rows parsed, checked, created | FULL | ≤ 3000 ms |
| `sync_import_200_sales` / `sync_import_200_edits` | another till's 200 sales / 200 price edits applied by the importer in one transaction (real imports split into ~100 ms transactions, D-045) | FULL | ≤ 3000 / 1500 ms |
| `db_open` | open existing DB + pragmas + version check | 1M lines | ≤ 300 ms |
| `heap` | Java heap after GC, steady selling (`perf/SoakTest`: 1,000 sales scan → cart → checkout; also checks old selling screens are freed) | — | ≤ 48 MB, growth ≤ 2 MB over 1,000 sales |

Leak diagnosis: when `SoakTest` finds a closed screen still in memory it saves a heap dump
(`perf/HeapDumps`, app `files/leaks/`); `scripts/ci/instrumented.sh` pulls it and prints the
reference chain with LeakCanary's `shark-cli` (CI only; artifact `instrumented-api*/leaks-api*`).
Found this way (Phase 10): a ScrollView's scrollbar fade (~1.2 s after attach) holding closed
screens — screens that are recreated often use `android:scrollbars="none"` where no bar is needed.

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

Scaling note: before Phase 5 `report_year` read ≈550k `sum_day_product` rows (2.38 s on a
mid-range phone). Since D-043 whole months come from `sum_month_product` (≈12 rows per product
and year) and only the loose days at both ends from the per-day table.

**Real hardware (D-058).** The CI emulators run on fast x86 servers; the store's low-end ARM tablet
(Android 10, SQLite 3.22, 2.8 GB) ran FULL 4–6× slower and missed five budgets (report_year 6.6 s)
plus one plan check its older planner failed (`shift_current`). Reports now read whole years
(`sum_year_product`), a month less a few days instead of 28 days, and categories and best sellers
from one reading; on SQLite 3.22 the rolling year went from 1.08 s to 0.27 s and 30 days from
0.22 s to 0.07 s for the same rows (laptop). Check plans and timings on SQLite 3.22 too: the
`sqlite-tools-win32-x86-3220000` shell from sqlite.org runs the app's SQL as the tablet does.
Keep query results small on old Android: each row a query returns costs ~9 µs on Android 10 against
0.6 µs on Android 16 (1.5.1 returned 50,000 rows per report and missed the budgets only there).
The perf run includes an Android 10 emulator, and its notes show per report the products sold, the
pieces read, the grouping and the report's queries.

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

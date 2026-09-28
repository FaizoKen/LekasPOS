# Delivery phases

Single source of truth for phase status. Update at the end of every work session.
A phase is finished only when every Definition-of-Done item in the `lekaspos` skill holds,
the release APK is built and measured, all tests ran, and the hardware test list is handed
to the user. **The next phase starts only after the user's real-device feedback.**

| # | Phase | Status |
|---|---|---|
| 1 | Project skill, architecture, database schema, performance test harness | **done** — FULL perf passed on a real Android 15 phone; 2 GB tablet run pending |
| 2 | Selling screen, products, cash payments, receipt printing, drawer kick, scanner input | **in progress** (on stated assumptions, D-024) |
| 3 | Inventory, suppliers, stock movements | not started |
| 4 | Users, roles, PIN, shifts, cash management, audit log | not started |
| 5 | Reports and CSV import/export | not started |
| 6 | Google Drive sync, local backup/restore, merge tests | not started |
| 7 | Localization, settings, polish, low-end profiling, release build, final checklist | not started |

## Open questions for the user

1. **Tax and receipt compliance** — the spec's "Tax and receipt compliance" section is still a
   placeholder. Current assumption (Malaysia): most grocery retail charges no tax at the
   till (SST is mostly levied at manufacturer/importer level); tax rates are configurable per
   product (e.g. service tax 8%, sales tax 5%/10%) with a store-wide "prices include tax"
   switch; receipts show store name, address, business registration no. (BRN), SST no. if
   registered, TIN, receipt no., date/time, cashier, lines, discounts, tax summary, rounding,
   payments and change. For LHDN e-invoicing we assume B2C consolidated e-invoices (monthly
   export) plus an optional "request e-invoice" QR/link on receipts. Please confirm or
   correct before Phase 2 receipts are finalized.
2. **Application ID** — using `com.lekaspos.app`. It cannot change after the first Play
   upload; tell us if you own a domain you want to use instead.
3. **Customers & credit** are in the feature list but not in any phase; proposed: Phase 4
   (together with cash management, since credit repayments go through the drawer).
4. **Hardware for Phase 2** — which printer(s) (brand/model, 58 or 80 mm), scanner(s) (HID or
   SPP) and cash drawer will you test with? Cheap Chinese ESC/POS printers differ in code pages,
   QR support and Bluetooth quirks.

---

## Phase 1 — skill, architecture, schema, performance harness

Deliverables
- [x] `CLAUDE.md` + `lekaspos` skill with architecture, conventions, perf budget, money,
      schema conventions, sync design, definition of done
- [x] Gradle project: `:core` (JVM) + `:app` (Android), version catalog, R8/shrink config,
      signing config, minSdk-21 dependency pins, lint on both modules (errors fail the build)
- [x] `:core`: money/qty/rounding, pricing engine (discounts, tax, cash rounding, split
      tender), IDs, HLC, search-text normalization, EAN validation, scale-barcode templates,
      date helpers, LWW field versions — all unit-tested
- [x] `:app`: `Db` (writer thread, WAL, FULL sync), schema v1 + seed, migration framework +
      schema snapshot + migration test, DAOs for products/sales/stock/reports/cart,
      derived-table rebuild
- [x] Perf harness: data generator (TINY/QUICK/FULL), perf suite with budgets + query-plan
      checks (hot + maintenance SQL), instrumented `PerfSuiteTest`, in-app Diagnostics screen,
      `run-perf.ps1`, `measure-startup.ps1`, `create-avds.ps1`
- [x] Minimal launcher screen (placeholder for the selling screen), reports "fully drawn"
- [x] API 21 (Android 5.0.2, 1 GB) verified locally: 43/43 instrumented tests, release-build
      FULL perf PASS, cold start median 557 ms
- [x] Public repo https://github.com/FaizoKen/LekasPOS (GPL-3.0) with GitHub Actions:
      CI green — build + 92 JVM tests + lint + APK size, and **43/43 instrumented tests on
      API 21 and on API 36**; FULL perf on API 36 PASS (CI run 36453791190)

### Results so far (2026-09-29)

- Release APK: **130,198 bytes (127 KB)** — budget 8 MB. (Debug 1.8 MB, test APK 0.5 MB.)
- Cold start, release build, API 21 emulator (1 GB), 10 launches with the process killed:
  first frame median **542 ms**, usable (`reportFullyDrawn`) median **557 ms**; very first
  launch incl. database creation 684 ms — budget 2,000 ms.
- Release build, FULL scale, in-app runner with the UI visible (API 21): **PASS**, all
  scenarios within budget, 19/19 plans (worst search p95 19 ms, sale commit 16 ms, monthly
  report 151 ms, yearly 1.8 s). Report in `perf-results/` (git-ignored) and on request.
- JVM tests: **85** in `:core` + **4** in `:app` — all pass. Lint: no issues (both modules).
- Instrumented tests on the API 21 emulator (Android 5.0.2, SQLite 3.8.6, 1 GB RAM):
  **43/43 pass** (schema, migrations, DB threading/IDs/HLC, products/search, sales/refunds/
  voids/paging, stock counts, derived-table consistency, query-plan checker).
- Perf, FULL scale (50,000 products, 251,191 sales, **1,000,632 sale lines**, 318 MB DB),
  API 21 emulator, debug build via instrumentation — **every budget met, 19/19 query plans OK**:

  | scenario | p95 ms | budget |
  |---|---|---|
  | barcode_lookup | 0.84 | 10 |
  | scan_to_cart (DB + pricing) | 0.52 | 20 |
  | search (worst of 6 kinds) | 13.97 | 50 |
  | category_page | 1.92 | 30 |
  | sale_commit (5 lines, fsync, outbox) | 11.31 | 150 |
  | history page first / deep | 0.31 / 0.45 | 50 |
  | receipt_lookup | 0.26 | 10 |
  | product_history_page | 10.85 | 50 |
  | stock_level | 0.14 | 5 |
  | low_stock_page | 10.68 | 300 |
  | report day / month / year | 6.5 / 132 / 1,295 | 300 / 1,000 / 3,000 |
  | db_open | 7.2 | 300 |

  Emulator on a desktop CPU: expect real low-end phones to be several times slower — the
  in-app test on real devices is the verdict.
- FULL scale on the API 36 emulator in CI (Android 16, SQLite 3.44.3), release build via the
  in-app runner: **PASS**, 19/19 plans; generation 20 s; worst search p95 4.0 ms, sale commit
  3.4 ms, monthly report 51 ms, yearly 669 ms; cold start median ~330 ms.
- FULL scale on the API 21 emulator in CI (Android 5.0.2, 1 GB): instrumented suite (debug,
  no UI) **PASS**; release build via the in-app runner with UI **PASS** (worst search p95
  14.8 ms, sale commit 13.5 ms, monthly report 125 ms, yearly 1.6 s); cold start median
  ~546 ms usable (CI Performance run 36462003804). Second consecutive clean UI run on 5.0.2
  after the GC-crash mitigations.
- Latest CI (commit 99c38c1): green on all jobs. Release APK built by CI: 131,550 bytes.
- Found and fixed by the harness (see DECISIONS D-021): an O(n²) rebuild query on SQLite 3.8
  (partial index ignored in a correlated subquery) and a dead partial index. Found by the API 36
  CI run: the plan checker missed aliased tables in modern `EXPLAIN QUERY PLAN` output (fixed).
- Android 5.0.2 emulator: FULL generation *with the Diagnostics UI visible* crashed 3 of 4
  times inside ART's garbage collector (SIGSEGV in `GCDaemon`), debug and release alike; the
  UI-less FULL run passed. Mitigations added (no finalizable objects in the generator loop,
  throttled progress, wake lock); needs a re-run and real Android 5.x hardware to confirm.

### Real-device results (Phase 1)

| Device | Build | Scale | Result | Notes |
|---|---|---|---|---|
| Xiaomi 2312DRAABG, Android 15, 7.5 GB RAM, arm64 | 0.1.0-ci.5, test key | FULL (1,000,632 lines) | **PASS**, 19/19 plans | generation 100 s; scan 0.17 ms, worst search 15.3 ms, sale commit 5.3 ms (p95); month report 184 ms; **year report 2.38 s — closest to its 3 s budget** |
| 2 GB tablet | — | — | pending (device not available yet) | expected to exceed the year-report budget → per-month product summary planned in Phase 5 |

### Needs real-hardware testing (Phase 1)

1. Install `app-release.apk` on each test device (ideally the slowest one, plus one recent
   phone/tablet). Get it from the latest green CI run on GitHub (Actions → CI → run → artifact
   **apks**; needs a GitHub login) or from `app\build\outputs\apk\release\`. It is signed with a
   debug key — for testing only. Launch: the home screen should say
   "Ready · device XX · products: 0 …" within ~2 s of tapping the icon.
2. Home → Diagnostics & performance test → **Quick test** → note PASS/FAIL → Share report.
3. Same → **Full test** (needs ~400 MB free, 5–20 min, keep charging, screen stays on) →
   Share report. Especially valuable on any Android 5.x/6.x device.
4. Delete test data afterwards. Rotate the screen during a run: it must keep going.
5. Set the device language to Bahasa Melayu: both screens should switch language.
(No printer/scanner/drawer yet — those arrive in Phase 2.)

---

## Phase 2 — selling, products, cash, printing, drawer, scanner (planned)

- [ ] Selling screen (phone + tablet, portrait + landscape): cart list, totals, category grid,
      search with debounce, quantity edit, remove line, crash-safe current bill (`CartSession`)
- [ ] Scanner input without focus: HID keyboard buffer in `dispatchKeyEvent`, SPP scanner
      reader, scale-label templates, pack barcodes; unknown-barcode flow (add product)
- [ ] Hold/park and resume bills; item and bill discounts; price override (permission stub)
- [ ] Weighed items: manual weight entry, price/weight-embedded labels
- [ ] Payments: cash with change and 5-sen rounding, card, e-wallet/QR, split tender (record only)
- [ ] Complete sale → commit → print; receipt reprint; share receipt as image/PDF
- [ ] Refunds/returns and voids with reason + permission stub + audit entry
- [ ] Products: list, add/edit (barcodes, SKU, category, unit, cost, price, tax), categories
- [ ] ESC/POS in `:core` (58/80 mm, logo raster, text/graphic modes, QR), Bluetooth SPP
      transport, persistent print queue with reconnect, test-print screen, drawer kick
      (auto on cash + manual with audit), printer/scanner/drawer setup screens
- [ ] Android 12+ and legacy Bluetooth permissions; camera scanning decision (size budget)
- [ ] Tax/receipt compliance as answered by the user (open question 1)

## Phase 3 — inventory, suppliers, stock movements (planned)

- [ ] Stock in (receiving) with purchase records and cost update; stock adjustments with reasons
- [ ] Stock count sessions (count sheets, variance report); low-stock alerts
- [ ] Supplier list; purchase history; product stock history (sale lines + movements)

## Phase 4 — users, roles, PIN, shifts, cash management, audit (planned)

- [ ] Staff with PIN login (hashed, lockout), roles owner/manager/cashier, configurable permissions
- [ ] Permission checks + manager override on sensitive actions; audit log viewer
- [ ] Shift open/close, opening float, cash in/out/drop, counted vs expected cash, shift report
- [ ] Customers and credit ("buy now, pay later") — optional feature switch (proposed here, see open question 3)

## Phase 5 — reports, CSV import/export (planned)

- [ ] Per-month product summary (`sum_month_product`) so year/multi-month reports stay well
      under budget on low-end devices (year report measured at 2.38 s on a mid-range phone)
- [ ] Daily/weekly/monthly sales; by product, category, cashier, payment method
- [ ] Gross profit, top sellers, slow movers, stock value; export to CSV
- [ ] Product CSV import (preview, validation, errors per row, bulk insert) and export

## Phase 6 — Google Drive sync, backup/restore, merge tests (planned)

- [ ] `SyncProvider`, `FolderProvider`, `GoogleDriveProvider` (appDataFolder, resumable transfers)
- [ ] Outbox sealing, segment upload/import, snapshots, bootstrap, GC; WorkManager scheduling
- [ ] Sign-in with Google Identity Services `AuthorizationClient`; sync status UI
- [ ] Local automatic backups, manual export/import (SAF), archive old sales
- [ ] Merge test suite (references/sync.md §12)

## Phase 7 — localization, settings, polish, profiling, release (planned)

- [ ] All strings EN + BM reviewed; in-app language switch; settings screens (store, receipt,
      currency, tax, printer, scanner, drawer)
- [ ] Profiling on `lekas-api21` (1 GB) and `lekas-api36`; 1,000-sale soak (heap flat, no leaks)
- [ ] Privacy policy + Google Play Data Safety answers; release build + final checklist

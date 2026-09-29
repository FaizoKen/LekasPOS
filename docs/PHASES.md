# Delivery phases

Single source of truth for phase status. Update at the end of every work session.
A phase is finished only when every Definition-of-Done item in the `lekaspos` skill holds,
the release APK is built and measured, all tests ran, and the hardware test list is handed
to the user. **The next phase starts only after the user's real-device feedback.**

| # | Phase | Status |
|---|---|---|
| 1 | Project skill, architecture, database schema, performance test harness | **done** — FULL perf passed on a real Android 15 phone; 2 GB tablet run pending |
| 2 | Selling screen, products, cash payments, receipt printing, drawer kick, scanner input | **done** — phone tests passed (2026-09-29); printer, scanners and drawer not yet tested (hardware not available, carried forward) |
| 3 | Inventory, suppliers, stock movements | **done** — phone tests passed (2026-09-29); printer, scanners and drawer still carried forward |
| 4 | Users, roles, PIN, shifts, cash management, audit log | **built and verified in CI** — waiting for real-device feedback (tester build `v0.4.0-phase4`) |
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

## Phase 2 — selling, products, cash, printing, drawer, scanner (done)

Built on the assumptions in D-024 (Malaysian tax/receipt defaults, generic ESC/POS hardware).

### Results (2026-09-29)

| Check | Result |
|---|---|
| Release APK (R8, test key) | **568 KB** (581,917 bytes; Phase 1: 132 KB; budget 8 MB), version `0.2.0-ci.<run>` |
| JVM tests | `:core` 129, `:app` 21 — all pass |
| Instrumented tests (CI emulators) | **63/63 on API 21** (Android 5.0, SQLite 3.8.6) and **63/63 on API 36**: cart session incl. restore after restart, hold/resume, checkout (sale + drawer/receipt jobs + bill deleted in one transaction), audited discounts/overrides/cancel, refunds and voids, LWW edits + outbox, print queue, receipts, ZXing decode/encode, every screen opened, keyboard-wedge scan |
| Lint (release) | 0 errors |
| Perf FULL, API 21 emulator (1 GB) | **PASS**, 25/25 query plans indexed; scan_to_cart p95 0.8 ms, sale_commit 9.7 ms, receipt_text 4.3 ms, receipt_image 10 ms, report_month 109 ms, report_year 1.1 s |
| Perf FULL, API 36 emulator | **PASS**, 25/25 plans; receipt_text 0.5 ms, receipt_image 4.2 ms |
| Cold start to usable selling screen | API 21: 566–677 ms (median ≈ 615 ms); API 36: 594–731 ms — budget 2 s |
| Schema | unchanged (v1, D-032) |

- [x] Selling screen: one pane on phones in portrait (bill ⇄ catalogue), two panes in landscape
      and on tablets; cart list, totals, category tabs + product tiles, search with debounce,
      quantity edit, remove line; crash-safe bill (`CartSession`: memory first, then one
      ordered background writer; restored after a kill)
- [x] Scanners without focus: keyboard-wedge (HID) burst detection in `dispatchKeyEvent`
      (`:core` `ScanBuffer`), the search field also takes codes + Enter, SPP scanner reader,
      camera scanning (ZXing 3.3.3 + Camera1, continuous "sell" mode); scale-label templates;
      pack/carton barcodes; unknown barcode → add product or sell as "other item"
- [x] Hold/park and resume bills; item and bill discounts (amount or %); price override —
      all permission-checked (stub, D-028) and audited
- [x] Weighed items: weight entry on a keypad, weight- and price-embedded scale labels
- [x] Payments: cash with change and 5-sen rounding, card, e-wallet/QR, split tender, record
      only; zero-total bills; the bill is frozen while the payment dialog is open
- [x] Complete sale → one transaction (sale, stock, summaries, outbox, drawer + receipt print
      jobs, bill deleted) → print; result survives rotation; reprint (audited); share receipt
      as picture or PDF (FileProvider)
- [x] Refunds/returns (partial, pro-rata to the sen, cash rounding mirrored, restock option)
      and voids with reason + permission + audit; refunds must be voided before their sale
- [x] Products: list (keyset paging, search), add/edit (barcodes, pack barcodes, scale PLU,
      SKU, category, unit, sold by piece/weight/open price, cost, tax, stock tracking, low-stock
      level, hide), delete (tombstone); categories; tax rates; LWW edits with outbox events
- [x] ESC/POS in `:core` (58/80 mm, 32/42/48 columns, text mode with Latin transliteration or
      GB18030, image mode for any script, logo raster with dithering, QR native or raster,
      cut, drawer pulse); receipt layout in EN/BM; Bluetooth SPP transport; persistent print
      queue with reconnect/backoff; printer status on the selling screen; test page; drawer
      kick (auto on cash, manual with audit)
- [x] Settings: store & receipt (name, address, BRN, SST no., TIN, header/footer, language,
      copies, logo, e-invoice QR, prices incl. tax, 5-sen rounding, scale formats), printer &
      drawer, scanner & camera, activity (audit) log
- [x] Bluetooth: paired devices only (D-027) — `BLUETOOTH_CONNECT` on 12+, nothing at runtime
      before; camera permission on first use
- [ ] Tax/receipt compliance as answered by the user (open question 1) — defaults per D-024

### Real-device feedback (Phase 2, 2026-09-29)

The tester ran the build on a personal smartphone: the tests that could be run passed. The
Bluetooth printer, cash drawer, HID/SPP scanners and the 2 GB tablet were not available, so
items 2, 3, 5–8 and 12 below stay open and are re-checked when the hardware is at hand.
The user asked to continue with Phase 3.

### Needs real-hardware testing (Phase 2)

1. **Selling flow** on a phone (portrait) and the tablet (landscape): scan, search, catalogue
   tabs; change quantity, discount, price; hold and resume; cash, card, split payment; rotate
   the screen while the "Change" result is showing (it must come back).
2. **Bluetooth HID scanner** (keyboard mode): pair it in Android settings, then scan on the
   selling screen without tapping anything; scan while the result dialog is open (starts the
   next sale); scan into Settings → Scanner → test field.
3. **SPP (serial) scanner**, if you have one: choose it in Settings → Scanner.
4. **Camera scanning**: EAN-13 packs, in portrait and landscape, with the torch.
5. **Bluetooth receipt printer** (58 or 80 mm): pair, choose, print the test page (the digit
   ruler must fit one line), a sale receipt, a copy, the logo, the e-invoice QR code, and a
   product with a Chinese name (Auto mode prints it as a picture; with "Chinese characters"
   switched on, as text).
6. **Printer offline**: switch the printer off, make two sales (the top bar shows "Printer
   offline (2)"), switch it on — both receipts print in order and the drawer does not pop open.
7. **Cash drawer** on the printer: opens on cash sales and cash refunds, and from the menu
   (pin 2 or pin 5).
8. **Weighing-scale labels**, if your scale prints EAN-13 labels (prefix 20/21): set the label
   format in Settings → Store and the PLU on the product.
9. **Crash safety**: add items, force-stop the app in Android settings, reopen: the bill is
   still there.
10. **Refund and void**: partial refund in cash, then void; share a receipt to WhatsApp as a
    picture and as a PDF.
11. **Android 12+ permissions**: the "Nearby devices" prompt when choosing a printer; camera
    permission on first scan.
12. **Low-end device** (the 2 GB tablet): selling-screen smoothness and Diagnostics → Full test
    (now also measures receipt building and picture rendering).

## Phase 3 — inventory, suppliers, stock movements (done)

- [x] Receive stock: scan (keyboard-wedge or camera) or pick products, carton barcodes add their
      pack size, weighed goods ask for the weight; cost per unit or the invoice's line amount;
      supplier and invoice/DO number; crash-safe draft; one transaction writes the purchase,
      its lines, RECEIVE movements and moving-average cost updates (D-033, D-036)
- [x] Stock adjustments with reasons (damaged, expired, lost, theft, own use, returned to
      supplier, found, correction, other) and a note; opening stock for new products
- [x] Stock counts: count sessions (all products or one category), scan or tap and type what is
      on the shelf, counts apply at once so selling continues (D-035); variance report with
      expected vs counted and value found/missing at cost
- [x] Low-stock list; low-stock warning on the sale result for products that just ran low
- [x] Suppliers (add/edit/delete), deliveries list (all or per supplier) and delivery detail
- [x] Product stock history: sales, refunds, deliveries, adjustments and counts merged, with the
      level after each; stock-changes log for all products
- [x] Schema v2 migration (D-034); sync events for all stock work
- [x] CI (API 21/36), perf FULL, release APK size, tester build

### Results (2026-09-29)

| Check | Result |
|---|---|
| JVM tests | `:core` 135, `:app` 21 — all pass |
| Instrumented tests (CI emulators) | **68/68 on API 21** and **68/68 on API 36**, incl. migration v1 → v2, receiving with average cost and one PURCHASE sync event, adjustments, counts applying at once, history paging, low stock, suppliers, all 12 new screens opened |
| Lint (release) | 0 errors |
| Perf FULL, API 21 emulator (1 GB, SQLite 3.8.6) | **PASS**, 39/39 query plans indexed; receive_commit (20 lines) p95 19 ms, stock_history_page 2.1 ms, movement/purchase/count pages < 1 ms, low_stock_count 12 ms, sale_commit 9 ms, report_year 0.87 s |
| Perf FULL, API 36 emulator | **PASS**, 39/39 plans; receive_commit 4.3 ms |
| Cold start to usable selling screen | API 21: ≤ 481 ms (worst of 10); API 36: 451–523 ms — budget 2 s |
| Schema | v2 (D-034) — upgrades Phase 1/2 installs in place |

### Real-device feedback (Phase 3, 2026-09-29)

All Phase 3 tests passed on the tester's phone. The printer, drawer, HID/SPP scanners and the
2 GB tablet are still not available; those checks stay carried forward. The user asked to
continue with Phase 4.

### Needs real-device testing (Phase 3)

1. **Upgrade**: install over the Phase 2 build — open bills, products and sales must all still be there.
2. **Receive stock**: add a supplier, scan a few products (and one carton barcode), change a
   quantity, type an invoice line amount, save; check stock and the product's cost.
3. **Crash safety of a delivery**: enter half a delivery, force-stop the app, reopen Receive stock.
4. **Adjust stock**: damaged / found / correction, with a note; look at Stock changes.
5. **Stock count**: start a count for one category, scan or tap products and type counts, sell
   one counted item, open the count report, finish the count.
6. **Low stock**: set a low-stock level on a product, sell it below the level — the sale result
   warns; the Low stock list shows it.
7. **Stock history** of a product after all of the above (levels should add up).
8. Carried over from Phase 2 when the hardware is available: printer, drawer, HID/SPP scanners.

## Phase 4 — users, roles, PIN, shifts, cash management, audit (waiting for feedback)

- [x] Staff with PIN login: PIN login stays off until the owner sets a PIN (a one-person shop
      works as before); pick your name, type a 4–6 digit PIN; salted PBKDF2 hash (D-037); 5 free
      wrong PINs per till, then a doubling wait; stays signed in across restarts; Lock / switch
      user; lock after N idle minutes (per device); change own PIN; owner recovery code
- [x] Roles owner/manager/cashier + your own roles, 17 permissions each (owner = everything);
      the store can never lock itself out (an owner who can sign in always remains)
- [x] Manager approval with PIN for sensitive actions: discounts, price changes, cancel bill,
      voids, refunds, reprints, drawer, cash in/out, credit over the limit (one action), and
      products, stock, settings, staff, activity log, shift report (the screen keeps it)
- [x] Activity log shows who did it and who approved it; filter by action; sign-ins, lockouts,
      staff/role changes, shifts and cash movements are logged
- [x] Shifts per till: opening float, cash in / cash out / drop (with reason, opens the drawer),
      close by counting the drawer — expected cash recomputed from the shift's sales, refunds,
      voids, movements and credit repayments (D-038); blind close for cashiers; shift report on
      screen and printed; past shifts; optional "require a shift to take payments"
- [x] Customers and credit (optional switch, D-039): customer on the bill (printed on the
      receipt), pay with "Customer credit" within a limit, manager approval over it, repayments
      into the drawer, refunds/voids give the credit back, statement with running balance,
      balance adjustments, who owes how much
- [x] Schema v3 migration (D-040); sync events for staff, roles, shifts, cash and credit
- [x] CI (API 21/36), perf FULL, release APK size, tester build

### Results (2026-09-30)

| Check | Result |
|---|---|
| Release APK (R8, test key) | **726 KB** (743,960 bytes; Phase 3: 628 KB; budget 8 MB), version `0.4.0-ci.<run>` |
| JVM tests | `:core` 145 (PIN hashing checked against the JDK's PBKDF2, lockout, recovery codes, permissions, shift cash, report layout 32/42/48 columns EN/BM, credit rules), `:app` 21 — all pass |
| Instrumented tests (CI emulators) | **86/86 on API 21** (Android 5.0, SQLite 3.8.6) and **86/86 on API 36**: migration v2 → v3 incl. role defaults, PIN login and restart, lockout, manager approvals and screen approvals, last-owner protection, recovery code, roles, shift expected cash with refunds/voids/movements, void in a later shift, required shift, credit sales, limit approval, refunds/voids reversing credit, statement paging, balance rebuild, 10 new screens incl. the lock screen |
| Lint (release) | 0 errors |
| Perf FULL, API 21 emulator (1 GB) | **PASS**, 56/56 query plans indexed; shift_report (a full day) p95 6.9 ms, customer/statement pages < 1 ms, pin_check 31 ms, sale_commit 10 ms, report_year 1.16 s |
| Perf FULL, API 36 emulator | **PASS**, 56/56 plans; shift_report 1.5 ms, pin_check 12 ms |
| Cold start to usable selling screen | API 21: 557–605 ms; API 36: 405–467 ms — budget 2 s |
| Schema | v3 (D-040) — upgrades Phase 1–3 installs in place |

### Needs real-device testing (Phase 4)

1. **Upgrade**: install over the Phase 3 build — everything still there, the app opens
   straight to selling (no PIN yet).
2. **Turn on PIN login**: Settings → Staff → tap the owner → Set PIN (twice) → write down the
   recovery code. Add a cashier and a manager, each with a PIN. Menu → Lock / switch user.
3. **Sign in**: choose a name, type the PIN; try 5 wrong PINs — the till makes you wait;
   force-stop and reopen — the same person is still signed in.
4. **Manager approval**: signed in as the cashier, give a discount, change a price, cancel a
   bill, void a sale, reprint — each asks for the manager's PIN; Settings → Activity log shows
   who did it and who approved it (try the filter).
5. **Roles**: Staff → ⋮ → Roles → Cashier → switch on "Give discounts" → the cashier no longer
   needs approval for discounts.
6. **Idle lock**: Staff → ⋮ → Lock when idle → 1 min; leave the phone for a minute.
7. **Forgotten owner PIN**: lock → owner → Forgot PIN? → the recovery code → new PIN.
8. **Shift**: Menu → Shift & cash → Open shift with a float → cash sale, card sale, cash refund,
   Cash in / Cash out / Cash drop → Close shift: count the drawer; check the report's expected
   cash and difference; as the cashier the close is blind. With a printer: print the report.
9. **Require a shift**: Settings → Store → "Require an open shift…" → pay without a shift → asked
   to open one.
10. **Customers & credit**: Settings → Store → "Customers and credit" on → Add customer on the
    bill (new one with a small limit) → pay with Customer credit → result shows what they owe;
    try going over the limit (manager approval); Menu → Customers → the customer → Take a
    repayment in cash → statement; refund part of a credit sale to credit.
11. Carried over when the hardware is available: printer (now also the shift report), drawer
    (opens for floats, cash in/out, cash repayments), HID/SPP scanners (a scan on the lock screen
    must not count as a wrong PIN).

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

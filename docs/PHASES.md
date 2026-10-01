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
| 4 | Users, roles, PIN, shifts, cash management, audit log | **done** — tested on the phone (2026-09-30); printer, scanners and drawer still carried forward |
| 5 | Reports and CSV import/export | **done** — user approved (2026-09-30); printer, scanners and drawer still carried forward |
| 6 | Google Drive sync, local backup/restore, merge tests | **done** — tested on the phone (2026-09-30, build v0.6.0-phase6-fix1, sync through the store's Google Drive); archive of old sales deferred (D-045) |
| 7 | Localization, settings, polish, low-end profiling, release build, final checklist | **done** — tested on the phone (2026-09-30, build v0.7.0-phase7) |
| 8 | Feature completion: "other item" button, price check, first-run setup, promotions | **done** — tested on the phone (2026-09-30, build v0.8.0-phase8) |
| 9 | Data safety: protected by default, visible when not, folder backups, integrity check | **done** — tested on the phone (2026-09-30, build v0.9.0-phase9) |
| 10 | Cashier-first UI polish: words on every control, in-place quantity, clear totals and payment, popular items | **done** — tested on the phone (2026-09-30, build v0.10.0-phase10-fix1) |
| — | **Release 1.0.0** outside Google Play: public download, Google sign-in for every account (D-051) | **released** 2026-09-30 — https://faizoken.github.io/LekasPOS/#download |
| — | **Release 1.3.0**: faster Drive backup, new logo and two bug hunts (D-052 to D-055) | **released** 2026-10-01 |
| — | **Release 1.3.1**: the auto-lock after the phone's screen was off | **released** 2026-10-01 — latest download |

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
   **Answered 2026-09-30: keep as described.**
2. **Application ID** — using `com.lekaspos.app`. It cannot change after the first Play
   upload; tell us if you own a domain you want to use instead.
   **Answered 2026-09-30: keep `com.lekaspos.app`.**
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

## Phase 4 — users, roles, PIN, shifts, cash management, audit (done)

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

### Real-device feedback (Phase 4, 2026-09-30)

The tester ran the Phase 4 checks on the phone and reported no problems; the user asked to
continue with Phase 5. Printer, drawer, HID/SPP scanners and the 2 GB tablet stay carried forward.

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

## Phase 5 — reports, CSV import/export (done)

- [x] Per-month product summary (`sum_month_product`, schema v4, D-043): reports read whole
      months from it and only the loose days at both ends from the per-day table
- [x] Reports screen (needs the new REPORTS permission): today, yesterday, this/last week,
      this/last month, this/last year or any dates; sales, refunds, voids, discounts, net sales,
      tax, cost, gross profit and margin, average sale, change against the period before; sales
      by day, week or month; payment methods, cashiers, categories, best sellers
- [x] Stock value at cost (by category) and "not sold in this period" (slow movers)
- [x] CSV exports (D-041), shared or saved as a file: report summary, sales per day, products
      sold, receipts (also the source for the monthly consolidated e-invoice)
- [x] Products CSV (D-042): export, example file, import with a preview (new / updated /
      problems per line), English or Malay headers, categories created, tax by name or %,
      opening stock for new products, optional stock count for existing ones
- [x] Fixed on the way: the best-sellers list sorted by the tax column instead of net sales
      before its limit (since Phase 1) — with no tax configured the "top" list was arbitrary
- [x] CI (API 21/36), perf FULL, release APK size, tester build

### Results (2026-09-30)

| Check | Result |
|---|---|
| Release APK (R8, test key) | **777 KB** (795,780 bytes; Phase 4: 726 KB; budget 8 MB), version `0.5.0-ci.<run>` |
| JVM tests | `:core` 163 (CSV reading/writing incl. quotes, line breaks, BOM, delimiter detection; product CSV headers and rows; periods, month split, buckets, margins), `:app` 21 — all pass |
| Instrumented tests (CI emulators) | **95/95 on API 21** and **95/95 on API 36**: migration v3 → v4 fills the month table from day rows, month table = its days after sales/voids/refunds and after a rebuild (SQLite and :core agree on every month), split ranges = day-only answers, best sellers by net and by qty, report permission, slow movers, stock value, receipt export paging, product CSV round trip, preview problems per line, import rules, report/import screens |
| Lint (release) | 0 errors |
| Perf FULL, API 21 emulator (1 GB) | **PASS**, 65/65 query plans indexed; report screen: day 24 ms, 30 days 0.27 s, rolling year 1.25 s, calendar year 0.30 s; slow movers 0.25 s, stock value 0.10 s; CSV: a month of receipts (~20k) 0.29 s, 50,600 products 0.81 s; import 200 rows 0.28 s |
| Perf FULL, API 36 emulator | **PASS**, 65/65 plans; rolling year 0.67 s, calendar year 0.12 s |
| Cold start to usable selling screen | API 21: 597–762 ms; API 36: 546–683 ms — budget 2 s |
| Schema | v4 (D-043) — upgrades Phase 1–4 installs in place |

Note on long reports: a calendar year (whole months) now takes 0.30 s on the API 21 emulator;
a rolling 365 days still reads the loose days at both ends from the per-day table (1.25 s there).
The FULL test data sells most of its 50,000 products every month, which is the worst case for
the month table; a real shop with a few thousand products gains more.

### Real-device feedback (Phase 5, 2026-09-30)

The user replied "good, now continue" to the Phase 5 build — taken as approval; no problems
were reported. The checklist below stays for reference; hardware items remain carried forward.

### Needs real-device testing (Phase 5)

1. **Upgrade**: install over the Phase 4 build — everything still there; Reports shows past sales.
2. **Reports** (menu → Reports; a cashier needs a manager's PIN): Today, This week, This month,
   Choose dates — compare Today with your shift report; look at best sellers, categories,
   payment methods, cashiers, and the change against the period before.
3. **Stock**: the stock value at the bottom; "Not sold in this period" (tap a product to open it).
4. **Exports** (⋮ on Reports): each of the four, once shared to WhatsApp or e-mail and once
   saved as a file; open them in Google Sheets or Excel — names with Malay or Chinese letters,
   dates and amounts must look right.
5. **Products CSV** (Products → ⋮): Export, open in a spreadsheet, change a price, add a new
   product and a line with a wrong price ("abc"), save as CSV, Import — the preview must show one
   update, one new product and one problem with its line number; import and check the products.
6. **Example CSV file**: fill it in Google Sheets (download as CSV) and import it.
7. Optional: a big file (a few thousand products) — rotate the phone or leave the screen while
   it imports; it keeps going and shows the result.
8. Carried over when the hardware is available: printer, drawer, HID/SPP scanners, 2 GB tablet.

## Phase 6 — Google Drive sync, backup/restore, merge tests (done)

- [x] Backups (D-044): a daily automatic backup (last 7 kept), one before every app upgrade and
      before every restore; back up now; save or share a backup file (USB, SD card, Drive,
      WhatsApp) — all without Google Play services. Restore from a kept backup or a file,
      either as this till (the old phone is gone) or as a new till; staged, checked, applied
      at the next start
- [x] Sync core (D-045): outbox sealed into immutable, checksummed segment files per till;
      other tills' segments applied in order, idempotently, in short transactions (a sale
      never waits for a whole segment); per-field merge for catalogue/settings/staff/customers;
      stock = last count + movements; device cards, store manifest, receipt-prefix and
      device-number clash handling; a joining till publishes what it already has (backfill)
- [x] Google Drive provider (hidden app folder, `drive.appdata` only), resumable uploads,
      token refresh; Google sign-in with `AuthorizationClient`, pinned to the store's account
- [x] Background sync with WorkManager: every 30 minutes and ~2 minutes after a sale;
      Settings → Sync (turn on, status, sync now, sign in again, tills in the store, turn off);
      a pill on the selling screen only when sync needs the user or has not worked for a day
- [x] Merge test suite (references/sync.md §12): 8 multi-till scenarios on shared-folder
      "tills", each checking identical data and derived tables equal to a full rebuild
- [x] Fixed on the way: a restored till that had synced now always becomes a new till (its
      old number's later sales and receipt numbers are already in the store); on Android 5–6 a
      cut-short backup file made restore (and the backup list) spin forever — now refused;
      on SQLite 3.8 a refund-total lookup inside a subquery skipped its index, so each sale
      from another till took 26 ms to apply at 250k sales — now a direct lookup (~3 ms)
- [ ] Deferred (D-045): snapshot bootstrap and deleting old segments from Drive (not needed
      at current sizes); **archiving old sales** (moved out of Phase 6 — every budget holds
      at 1M+ sale lines, and purging synced history safely needs extra checkpoints)

### Results (2026-09-30)

| Check | Result |
|---|---|
| Release APK (R8, test key) | **1,099 KB** (1,125,040 bytes; Phase 5: 777 KB; budget 8 MB) — WorkManager and the sync/backup code, Google sign-in (play-services-auth ~+185 KB); version `0.6.0-ci.33` (tester build v0.6.0-phase6-fix1) |
| Release smoke (CI, new) | R8 release APK installed over the Phase 5 build and fresh, selling screen open 20 s: no crash on API 21 and API 36 |
| JVM tests | `:core` 159 (incl. sync file names, cursors, LWW merge rules), `:app` 21 — all pass |
| Instrumented tests (CI emulators) | **107/107 on API 21** and **107/107 on API 36**: backup while selling is consistent, restore as the same till / as a new till, cut-short and non-backup files refused, 7 automatic backups kept; merge suite (second till joins with its own data, field-by-field edits, delete vs edit, a week offline with 1,200 sales, out-of-order and repeated delivery, count vs offline sales, credit and settings, restored till rejoins without collisions, interrupted first sync); every older schema (v1–v4) migrates to v5; sync and backup screens open |
| Lint (release) | 0 errors |
| Perf FULL, API 21 emulator (1 GB) | **PASS**, 67/67 query plans indexed; sync import from another till: 200 sales 0.54 s (p95 1.1 s; was 5.2 s before the fix above), 200 price edits 0.10 s; sale commit 8 ms; reports: month 0.21 s, rolling year 1.17 s, calendar year 0.28 s; CSV import 200 rows 0.25 s |
| Perf FULL, API 36 emulator | **PASS**, 67/67 plans; 200 remote sales 36 ms, 200 edits 4 ms; rolling year 0.56 s |
| Cold start to usable selling screen | API 21: 586–710 ms; API 36: 455–544 ms — budget 2 s |
| Schema | v5 (D-045: LOCAL `sync_segment`, `sync_cursor`) — upgrades Phase 1–5 installs in place |

Not verified here: talking to the real Google Drive. The Drive code is built and reviewed, and
everything above it is tested through a shared-folder provider; the first real run is on the
phone (below), which needs the Google Cloud OAuth setup in the README.

### Tester feedback (Phase 6, 2026-09-30)

The first tester build (`0.6.0-ci.30`) closed itself ~3 s after opening, every time. Cause: R8
removed the constructor WorkManager uses to create its job database, so scheduling the background
jobs (1.5 s after the selling screen is usable) threw in the app scope. None of the checks ran
the release build long enough: the instrumented tests use the debug build, the cold-start loop
relaunches every 3 s. Fixed with a keep rule; scheduling can no longer crash the app; a CI
**release smoke** job now reproduces the tester's path (upgrade from the last tester build, then
fresh) and gates every tester build. Fixed build: v0.6.0-phase6-fix1 (`0.6.0-ci.33`).

### Real-device feedback (Phase 6, 2026-09-30)

After the fixed build and the Google Cloud setup (project `lekaspos`, done together in the
browser) the user replied "tested and all works", including sync through Google Drive. The
checklist below stays for reference; hardware items remain carried forward.

### Needs real-device testing (Phase 6)

Before the sync tests: the Google Cloud setup in the README (Drive API, consent screen with
your Google account as a **test user**, Android OAuth client for `com.lekaspos.app` with the
test key SHA-1 `69:63:1E:7C:98:0C:32:C9:39:C1:8D:B9:B9:48:01:54:2A:1B:84:B8`).

1. **Upgrade**: install over the Phase 5 build — everything still there. Settings → Backup &
   restore lists a backup "Before an app upgrade".
2. **Backup**: Back up now; ⋮ → Save a backup file (e.g. Downloads) and ⋮ → Share (WhatsApp or
   Drive). The next day, an "Automatic" backup is listed.
3. **Restore**: make a test sale, then tap an earlier backup → "Restore this till" → the app
   restarts without that sale; a "Before a restore" backup is listed (restore it to undo).
   Also ⋮ → Restore from a file… with the file saved in step 2.
4. **Turn on sync** (Settings → Sync) on phone A: till name "Counter 1" → Turn on sync →
   choose the store's Google account → allow. Status "Up to date", your account shown.
5. **Second till** (a second phone/tablet, fresh install or with its own data): same account,
   name "Counter 2". After it finishes, both have the same products, staff, customers and
   settings; each keeps its own receipt prefix (e.g. `AB-`, `CD-`).
6. **Changes travel**: change a price on A, tap Sync now on A then on B → new price on B.
   Sell on B with Wi-Fi/data off (airplane mode), turn it back on → after a sync, A's reports
   include B's sales and the stock of that product is the same on both.
7. **Offline**: a day of selling on B without internet; the "Not synced" pill appears after 24 h
   with unsent sales; it disappears after B is online again.
8. **Sign-in**: at myaccount.google.com → Security → Your connections to third-party apps →
   LekasPOS → remove access. After the next sync the selling screen shows "Sync: sign in";
   Settings → Sync → Sign in again fixes it.
9. **Turn off sync** on B: its data stays; the other till is not affected.
10. **Restore on a syncing till**: the restore warns that the till joins as a new till;
    afterwards turn sync on again — no duplicate receipts, no lost sales.
11. Optional: a phone with two Google accounts — sync always uses the store's account.
12. Carried over when the hardware is available: printer, drawer, HID/SPP scanners, 2 GB tablet.

## Phase 7 — localization, settings, polish, profiling, release (done)

Decisions (2026-09-30): no Google Play upload at the end of this phase (everything for a later
upload is prepared: Data Safety answers, listing notes, upload-key and OAuth steps); the privacy
policy is a GitHub Pages page of this repository; archiving old sales comes after the release.

- [x] Strings reviewed: every English string has a Malay one, no placeholder mismatches, no
      hard-coded UI text; "Sunting" for Edit everywhere; sync failures (offline, damaged file)
      shown as sentences in both languages instead of raw error text
- [x] Settings → **App language** (phone's language / English / Bahasa Melayu, D-046), separate
      from the receipt language; Settings → **About** (licence, privacy policy, source code,
      open-source notices)
- [x] Layout review from screenshots in both languages (small phone on API 21 and API 36, tablet
      in landscape): two-button rows stay level when a Malay label wraps; Malay backup title
      shortened. Settings screens (store, receipt, tax, printer, drawer, scanner) were built in
      Phases 2–6 and checked here
- [x] Profiling: 1,000-sale soak through scan → cart → checkout — heap after GC flat (5.4 MB on
      the 1 GB API 21 emulator, 3.3 MB on API 36; budget 48 MB); old selling screens are freed
      after recreation; perf FULL and cold start on both emulators
- [x] Privacy policy (English + Malay) at https://faizoken.github.io/LekasPOS/privacy.html
      (`site/`, GitHub Pages); Google Play preparation in `docs/PLAY.md` (listing, Data Safety,
      upload key, OAuth production steps)
- [x] Release checks: release smoke on every build (upgrade from the last tester build + fresh,
      API 21 and 36), release APK size, lint

### Results (2026-09-30)

| Check | Result |
|---|---|
| Release APK (R8, test key) | **1,103 KB** (1,129,473 bytes; budget 8 MB), version `0.7.0-ci.41` (tester build v0.7.0-phase7) |
| JVM tests | `:core` 159, `:app` 21 — all pass |
| Instrumented tests (CI emulators) | **112/112 on API 21** and **112/112 on API 36** (new: app language, 1,000-sale soak, selling-screen leak check, screenshots in both languages) |
| Release smoke | upgrade from the Phase 6 tester build and fresh install, selling screen open 20 s, no crash — API 21 and 36 |
| Tablet (API 36, 10-inch, landscape) | every screen opens (smoke test) and the main screens in both languages were reviewed from screenshots; fixed: report period buttons clipped their labels (no padding) |
| Lint (release) | 0 errors |
| Perf FULL, API 21 emulator (1 GB) | **PASS**, 67/67 query plans; sale commit 4.7 ms (p95 10 ms); word search 5 ms; reports: month 0.20 s, rolling year 1.20 s, calendar year 0.30 s; 200 sales from another till 0.32 s |
| Perf FULL, API 36 emulator | **PASS**, 67/67 plans; sale commit 1.1 ms; rolling year 0.65 s |
| Cold start to usable selling screen | API 21: 568–676 ms; API 36: 579–744 ms — budget 2 s |
| Memory | heap after GC 5.4 MB (API 21) / 3.3 MB (API 36), no growth over 1,000 sales — budget 48 MB |
| Schema | v5 (no change in Phase 7) |

### Real-device feedback (Phase 7, 2026-09-30)

The user replied "tested and all works" to the v0.7.0-phase7 build. All seven phases are done.
Still open: hardware tests (printer, cash drawer, HID/SPP scanners, 2 GB tablet) when the
hardware is available; the Google Play upload when the owner decides (`docs/PLAY.md`); archiving
of old sales as a later, optional feature — it never deletes data on its own, and app updates
keep all data (every schema upgrade is migrated in place, with a backup taken first).

### Needs real-device testing (Phase 7)

1. **Upgrade**: install over the Phase 6 build — everything still there, sync still on.
2. **App language**: Settings → App language → Bahasa Melayu, then English, then Phone's
   language — every screen follows; an open bill survives the switch; receipts keep the receipt
   language from Settings → Store.
3. **Malay screens**: go through selling, payment, products, stock, reports, staff, shifts,
   customers, backup and sync in Malay — report any text that is wrong, awkward or cut off.
4. **About**: Settings → About → Privacy policy and Source code open in the browser.
5. **A long day**: sell for a while (or leave the app open for hours) — no slowdown, no crash.
6. Optional: a 10-inch tablet in landscape, and an old Android 5/6 phone.
7. Carried over when the hardware is available: printer, drawer, HID/SPP scanners, 2 GB tablet.

## Phase 8 — feature completion before UI polish (done)

Added after a feature audit against the basics of a small grocery till (2026-09-30): everything
else was already in place (selling, payments, receipts, refunds, stock, staff, shifts, customers
and credit, reports, backups, sync). The owner chose these four before polishing the UI:

- [x] **Other item** button on the selling screen: sell anything without a barcode by typing its
      price (until now only offered when a scanned barcode was unknown)
- [x] **Price check**: scan or search to see price, stock and promotion without adding to the bill
- [x] **First-run setup**: language, store name, owner PIN, printer, or join an existing store
      through sync
- [x] **Promotions**: "N for RM X" and "buy X get Y free" on one or more products, optional dates;
      applied automatically, on the bill and the receipt, synced (schema v6, D-047); price check
      shows running deals; events of unknown kinds are now kept for later versions

Optional extras from the audit, not planned: barcode/shelf labels, expiry dates per batch,
supplier payments, loyalty points, product photos, customer display, Bluetooth scale reading.

### Results (2026-09-30)

| Check | Result |
|---|---|
| Release APK (R8, test key) | **1,126 KB** (1,153,240 bytes; budget 8 MB), version `0.8.0-ci.49` (tester build v0.8.0-phase8) |
| JVM tests | `:core` incl. `PromotionsTest` (worked examples + 2,000 random bills: no line saves more than its gross, never a negative saving); `:app` incl. a DDL syntax guard — all pass |
| Instrumented tests (CI emulators) | **119/119 on API 21** and **119/119 on API 36** (new: promotions end to end, promotions and their sales across two tills, kept sync events, price check, first-run setup); migration v5 → v6 |
| Tablet (API 36, landscape) | every screen opens, including the new ones; screenshots reviewed in both languages (Malay promotion type names shortened) |
| Release smoke | upgrade from the Phase 7 tester build and fresh install, API 21 and 36 — no crash |
| Perf FULL, API 21 emulator (1 GB) | **PASS**, 67/67 plans; scan to bill 0.19 ms (promotions included); sale commit 4.7 ms; rolling year 1.05 s; 200 sales from another till 0.34 s |
| Perf FULL, API 36 emulator | **PASS**, 67/67 plans |
| Cold start to usable selling screen | API 21: 547–654 ms; API 36: 569–673 ms — budget 2 s (the first-run welcome screen is now dismissed before timing; with it, API 21 showed an implausible ~100 ms) |
| Memory | heap after GC flat over 1,000 sales — budget 48 MB |
| Schema | v6 (D-047) — upgrades earlier installs in place; each till re-reads the store's sync files once |

Found on the way: the first v6 build had a syntax error in the new table (a lost column template) and
failed on every emulator; the JVM schema test only compared text and now rejects such slips.

### Real-device feedback (Phase 8, 2026-09-30)

The user replied "tested and all works" to v0.8.0-phase8, and asked whether a Google login should
be required for data safety — see Phase 9.

### Needs real-device testing (Phase 8)

Update **every till** of the shop to this build (promotions only apply on tills that have it).

1. **Upgrade**: install over the Phase 7 build — data and sync still there; no welcome screen.
2. **Other item**: tap "+" next to the search bar → type a price (and a name) → it is on the bill
   and the receipt; with a scanner connected, scanning still adds products normally.
3. **Price check** (menu): scan a product, a pack barcode and a scale label, then type a name —
   price, pack price, stock and promotion show; the bill is unchanged. Try the camera button.
4. **Promotions** (menu → Promotions → +):
   - "3 for RM10" on one product: scan it 1, 2, 3, 4 times — the saving appears at 3 and stays at 4;
     the line shows the promotion's name and saving; pay and check the receipt.
   - "any 3 for RM10" on two flavours of different prices: mix them — the saving is shared.
   - "buy 1 get 1 free": scan 2 — one is free.
   - Give a discount or change the price on a promotion line — your change wins.
   - An end date in the past, or the switch off — no saving.
   - Refund one item of a promotion sale — the refund is what that item actually cost.
   - On the second till: the promotion arrives with sync and applies there too.
5. **Welcome screen**: on a spare phone or after "Clear storage" (it deletes the data on that phone —
   only on a test phone): the welcome screen appears once; change the language there; "Join my shop
   with sync" opens Sync.
6. Carried over when the hardware is available: printer (the promotion line on paper), drawer,
   HID/SPP scanners, 2 GB tablet.

## Phase 9 — data safety before UI polish (done)

Question from the owner (2026-09-30): should a Google login be required to keep shops' data safe?
Decision (D-048): **no** — it would lock out phones without Google Play services and offline
first days, and still not protect a shop that never turns backup on. The real risk is the data
existing only on one phone (lost, broken, reset or uninstalled — the local backups go with it).
So: protected by default, and visible when not.

- [x] First-run setup: a "Protect your shop's data" step — Google Drive backup (recommended) or
      a daily copy to a folder (SD card / USB); can be skipped
- [x] **Not backed up** pill on the selling screen when there is data but no copy off this phone
      in the last 3 days (Drive, folder or a saved backup file); tap for what it means and the
      two fixes. Nothing shows while the data is safe
- [x] Automatic daily backup also copied to a folder the owner picks (no Google needed), last 7
      kept; a failed copy (card removed) is shown on the backup screen
- [x] Sync presented as **Google Drive backup** (protects one till too); welcome screen offers
      "Restore or join my shop from Google Drive"
- [x] Database check (`quick_check`) before each automatic backup; a damaged database is not
      backed up (the good backups stay), and the selling screen shows **Data problem**
- [x] Tests: a lost phone restored in full on a new one from Google Drive; folder copies, keep 7,
      missing folder; protection states; a damaged database keeps every good backup; the check
      finds real damage in a database file

### Results (2026-09-30)

| Check | Result |
|---|---|
| Release APK (R8, test key) | **1,139 KB** (1,166,149 bytes; budget 8 MB), version `0.9.0-ci.52` (tester build v0.9.0-phase9) |
| JVM tests | `:core` and `:app` — all pass |
| Instrumented tests (CI emulators) | **127/127 on API 21** and **127/127 on API 36** (new: `DataSafetyTest` ×7 — folder copies, keep 7, missing folder, protection states, damaged database keeps every good backup, quick_check finds real page damage; `SyncMergeTest.aLostPhoneIsRestoredWholeFromDrive`) |
| Tablet (API 36, landscape) | every screen opens; welcome, backup, Google Drive backup and settings screens reviewed in both languages |
| Release smoke | upgrade from the Phase 8 tester build and fresh install, API 21 and 36 — no crash |
| Perf FULL, API 21 emulator (1 GB) | **PASS**, 67/67 plans; scan to bill 0.19 ms; sale commit 4.8 ms; rolling year 1.15 s; 200 sales from another till 0.30 s |
| Perf FULL, API 36 emulator | **PASS**, 67/67 plans |
| Cold start to usable selling screen | API 21: 553–652 ms; API 36: 485–680 ms — budget 2 s (the protection status is read after the first frame, off the main thread) |
| Schema | unchanged (v6); the new state lives in `meta` rows |

### Needs real-device testing (Phase 9)

1. **Upgrade** over the Phase 8 build — data, sync and settings still there. Settings now lists
   **Google Drive backup** (was "Sync between tills").
2. **The pill**: on a phone with Google Drive backup off and sales or products, the selling screen
   shows **Not backed up** a few seconds after opening. Tap it — the explanation offers Google
   Drive, SD card / USB, or Later. With Google Drive backup on and working, no pill.
3. **Daily copy to a folder**: Settings → Backup & restore → ⋮ → "Daily copy to a folder (SD card / USB)…" →
   in the system picker make a new folder (Android 11+ does not allow the card's top level or
   Download itself) → Use this folder → Allow. Expect "Copied to the folder.", the backup screen
   shows the folder and "Last copy off this phone: …", and the pill is gone. A file manager shows
   `lekaspos-<date>-<time>.lekasbak` in that folder.
4. **Card or drive removed**: take it out → ⋮ → "Copy to the folder now" → "Could not copy…", and
   the backup screen shows the error. Put it back → copying works again.
5. **Restore from the folder**: Backup & restore → ⋮ → Restore from a file… → pick a `lekaspos-…` file — only
   on a test phone, it replaces the data.
6. **Lost phone drill** (spare phone, or "Clear storage" on a test phone): welcome screen → "Restore
   or join my shop from Google Drive" → sign in with the shop's account → products, sales, stock,
   customers, staff and promotions come back; the welcome screen does not return.
7. **Welcome screen** on a fresh install: the new "Protect your shop's data" section; both buttons
   open the right screen; "Start selling" still works without choosing either.
8. **Bahasa Melayu**: the pill ("Tiada sandaran"), the explanation and the backup screen fit.
9. Carried over when the hardware is available: printer, drawer, HID/SPP scanners, 2 GB tablet.

### Real-device feedback (Phase 9, 2026-09-30)

The user replied "tested and all works" to v0.9.0-phase9 and asked for the UI polish next: simple
and straightforward, light and fast, easy for a brand-new cashier — see Phase 10.

## Phase 10 — cashier-first UI polish (done)

Audit of the selling flow (2026-09-30) and what changes (D-049):

- [x] Words on every control: "Price check", "Items" (phones), "Menu"; camera scanner inside
      the search box; "2 held" pill instead of ⏸
- [x] The line just scanned (or tapped) shows **Remove · − · quantity · + · More** in place —
      no dialog per tap; "−" stops at one
- [x] Big **TOTAL** above a big **PAY**
- [x] Empty bill: three lines on how to start, and the **last sale** (change again, print a copy)
- [x] Payment: total → cash received with **live change** → one-tap notes → every method (3 per
      row) → keypad; nothing needed falls off a 5-inch screen. Result: "Received … · total …"
- [x] Menu: cashier jobs first, back office under **Manage shop ›**
- [x] Catalogue: **Popular** tab (best sellers, last 30 days) first; tiles on the bill show "×n";
      picking a search result goes back to the bill
- [x] **No "Other item"** (owner's feedback on v0.10.0-phase10, D-050): only registered products
      are sold; an unknown barcode offers "Add product" (then it is on the bill) or Cancel
- [x] Tests: popular items (ranking, 30 days, deleted left out, fresh prices), the line buttons on
      the emulator, screenshots of the payment dialog and the empty bill; perf case
      `popular_items`

### Results (2026-09-30)

| Check | Result |
|---|---|
| Release APK (R8, test key) | **1,152 KB** (1,179,834 bytes; budget 8 MB), version `0.10.0-ci.56` (tester build v0.10.0-phase10) |
| JVM tests | `:core` and `:app` — all pass |
| Instrumented tests (CI emulators) | **131/131 on API 21** and **131/131 on API 36** (new: `PopularItemsTest` ×3, the bill line's +/−/Remove on screen) |
| Memory | the soak test caught closed selling screens held ~1.2 s by the empty-bill panel's scrollbar fade (found with a heap dump + LeakCanary's shark-cli, now automatic in CI when a leak test fails); fixed — closed screens are freed at once |
| Tablet (API 36, landscape) | every screen opens; selling, payment and empty bill reviewed in both languages |
| Release smoke | upgrade from the Phase 9 tester build and fresh install, API 21 and 36 — no crash |
| Perf FULL, API 21 emulator (1 GB) | **PASS**, 69/69 plans; `popular_items` 62 ms (budget 300); scan to bill 0.23 ms p95; sale commit 6.8 ms p95; rolling year 0.73 s |
| Perf FULL, API 36 emulator | **PASS**, 69/69 plans; `popular_items` 35–44 ms |
| Cold start to usable selling screen | API 21: 379–652 ms — budget 2 s |
| Schema | unchanged (v6) |

### Needs real-device testing (Phase 10)

Try it as a new cashier would, without explanations.

1. **Upgrade** over the Phase 9 build — bill, data and settings still there.
2. **Scan** a few items: the last one is highlighted with **Remove · − · 1 · + · More**. Tap +, then −;
   scan the same item again (the highlight follows it); tap another line to give it the buttons.
   "−" stops at 1; Remove takes the line off.
3. **More** on a line: type the quantity, discount (asks for permission as before), change price.
   A weighed item shows its weight instead of −/+ (tap it to change the weight).
4. **TOTAL and PAY**: the total is easy to read from a step back; Pay opens the payment.
5. **Payment**: the amount box shows the due amount in grey; type 100 → "Change RM…" appears before
   you press Cash; tap a note button (RM60, RM100) → done. Card / e-wallet buttons are visible on
   your phone without scrolling. Split: type part in cash, then Card for the rest.
6. **After the sale**: the result shows the change and "Received … · total …". Close it — the empty
   bill shows the last sale's change; "Print a copy" with a printer.
7. **Items** (phone) / the left side (tablet): the **Popular** tab after a few sales; items on the
   bill show "×2"; search, tap a result → back to the bill.
8. **Unknown barcode**: scan one the till does not know → "Add product" (the barcode is filled in;
   a cashier without the product permission needs the manager's PIN) → Save → it is on the bill.
   There is no "Other item" any more; loose items are products picked from Items.
9. **Menu**: short list (price check, held bills, sales & refunds, shift, drawer, cancel bill);
   **Manage shop ›** opens products, stock, categories, promotions, tax, reports, settings.
10. **Hold** a bill: the "1 held" pill appears in the top bar; tap it to bring the bill back.
11. **Bahasa Melayu**: every label fits (Semak harga, Barang, Jumlah, Bayar, Buang, Lagi, Laris).
12. Carried over when the hardware is available: printer, drawer, HID/SPP scanners, 2 GB tablet.

### Real-device feedback (Phase 10, 2026-09-30)

On v0.10.0-phase10 the owner found "Other item" beside Price check confusing for a new cashier,
and said a cashier should not sell anything that is not registered. Agreed and removed (D-050):
an unknown barcode is registered on the spot (Add product → on the bill). Fixed in the next
tester build v0.10.0-phase10-fix1 (0.10.0-ci.58, 1,151 KB — 1,178,388 bytes; 131/131 on API 21 and 36,
tablet and release smoke pass); the rest of the Phase 10 list above still applies.

The owner replied "tested and all works" to v0.10.0-phase10-fix1 (2026-09-30).

## Release 1.0.0 — public, outside Google Play (released 2026-09-30)

The owner asked how other shops (any Google account, not only the tester account) can use the
app without Google Play yet. Decided with the owner (D-051):

- [x] Google sign-in **In production** (2026-09-30): home page, privacy policy, authorized domain
      `faizoken.github.io`; published by the owner — any Google account can use Drive backup
- [x] One release key for every public copy (the tester builds' key; keyKind `release`)
- [x] Version **1.0.0**, shown as "1.0.0 (build N)" in Settings → About; About → "Website & updates"
- [x] Website: Download section (always the latest release's `LekasPOS.apk`), install and update
      steps in English and Malay
- [x] CI green (131/131 on API 21 and 36, tablet, release smoke); GitHub release **v1.0.0** (latest,
      not pre-release; `LekasPOS.apk` + `LekasPOS-1.0.0.apk`, 1,151 KB — 1,178,444 bytes, build 60,
      SHA-256 `e7cc22fa…39ec`); smoke baseline v1.0.0
- Later: register the developer, package and key with Google before the 2027 rule reaches
  Malaysia (installs outside Play from registered developers only)

The owner installed 1.0.0 on their phone over the tester build: "all works" (2026-09-30). The
post-release CI run (release smoke upgrading from v1.0.0) is green.

## 1.0.1 — new app logo (test build, waiting for the owner's check)

New original logo (D-052): a torn till receipt with a slanted "L" cut out, on emerald; minimal
(two shapes) at the owner's request. CI 132/132 on API 21 and 36 (ScreenshotsTest now saves the
launcher icon as each Android draws it — legacy badge on API 21, adaptive icon on 36), tablet and
release smoke green. Test build **v1.0.1** (pre-release; build 63, 1,153 KB — 1,180,450 bytes,
SHA-256 `fdffa126…ff26`). After the owner's OK: mark v1.0.1 as the latest release (the website's
Download link follows) and point `PREV_APK_URL` at it.

## 1.1.0 — faster Google Drive backup (test build, waiting for the owner's check)

The owner found Google Drive backup slow and "stuck" before it showed that it was syncing
(2026-09-30). Causes and fixes in D-053: instant "Connecting…" and step-by-step progress; cached
Google tokens, reused connections, fewer requests per round, a short listing of new files only;
a round 10 s after any change, when the app opens and when the internet comes back. 1.1.0 also
carries the new logo of the 1.0.1 test build (not released on its own).

- [x] Tests: status steps during a round; the short listing and its fall-back to the whole folder
      on a gap; the change announcement after a commit; Drive time parsing (JVM)

Results (2026-10-01): CI **135/135 on API 21 and 36**, tablet and release smoke green; perf FULL
**PASS** on both (69/69 plans; cold start API 21 513–613 ms; 200 synced sales imported in 0.30 s
p50 on API 21). Test build **v1.1.0** (pre-release; build 65, 1,162 KB — 1,189,705 bytes,
SHA-256 `44442439…4ffa`); the public download stays v1.0.0 until the owner's OK.

### Needs real-device testing (1.1.0)

1. Install over 1.0.0 — data and backup settings still there; the new logo on the home screen.
2. Settings → Google Drive backup → **Sync now**: "Connecting to Google…" shows at once, then the
   steps, then "Everything is backed up · Last backup: just now".
3. Make a sale, open the backup screen: "1 change waiting…", and within about 10 s it is sent by
   itself (no button).
4. Turn on airplane mode, make a sale, turn it off: the change is sent within a few seconds.
5. With a second till: a price change on one shows on the other within a minute or so (the other
   till syncs when its app opens, or within 45 s of its own changes; otherwise every 30 min).

## 1.2.0 — bug hunt: consistency, safety, speed (test build, waiting for the owner's check)

The owner asked for a thorough search for bugs (2026-10-01). Lint, a StrictMode run, the perf
report and six code reviews (back office, hardware, sync and backup, database, money and
selling, selling screen) found about 70 problems; each was checked in the code and fixed, most
with a test (D-054). 1.2.0 also carries 1.1.0's faster Drive backup and the new logo.

- [x] Fixes and tests: see D-054 (permissions and audit, PIN lockout per person, scanner keys in
      dialogs, quantity overflow, split payment, rotation, top bar on small phones, sync identity
      after off/on and restores, CSV round trip, printing queue and Bluetooth, camera, reports)
- [x] CI (API 21 and 36, tablet, release smoke) and perf FULL
- [x] Test build published as a pre-release

Results (2026-10-01): CI **161/161 on API 21 and 36** (26 new tests), lint, tablet and release
smoke green; perf FULL **PASS** on both (72/72 plans; cold start API 21 122–524 ms; report_year
703 ms p50, budget 3 s; sale commit 7.4 ms p95). Test build **v1.2.0** (pre-release; build 68,
1,181 KB — 1,209,645 bytes, SHA-256 `f308740a…226c`); the public download stays v1.0.0 until the
owner's OK. Left for the owner (D-054): stock with cost 0 and the average cost; the receipt's
"weighed" guess (needs a schema change); receipt prefixes of two tills joining at once.

### Needs real-device testing (1.2.0)

1. Install over 1.0.0 or 1.1.0: data, staff, settings and backup still there.
2. Selling screen on the phone: with held bills and the printer off, the top bar still shows
   Menu at the right; extra pills move to a second line.
3. Tap + five times quickly on a line: the quantity goes up by five.
4. Pay by card RM 20 and then cash for the rest: a tap outside the payment box does nothing;
   Cancel asks before clearing the RM 20; the phone does not turn while paying.
5. With a keyboard (HID) scanner: scan while the payment box is open — nothing is paid; scan
   while the search box has the cursor — the item goes on the bill.
6. As a cashier (PIN login on): change a customer's credit limit → asks for a manager; throw a
   held bill away → asks for a manager; the audit log shows both.
7. Wrong PIN 5 times for the manager → the manager waits; the cashier can still sign in.
8. Settings → Products → Export, change a price in a spreadsheet, Import: no new products.
9. Rotate the phone on a product form with typed changes, and during a report export: nothing
   is lost.
10. Printer (when available): test page prints as text; open the drawer right after a receipt;
    a printer that needs "channel 1"; a big logo receipt prints cleanly.
11. Camera scanning: deny the permission, tap the message to allow it; hold one item in view —
    it is added once; rotate while scanning — no crash.
12. Serial (SPP) scanner without an Enter suffix: codes still arrive.

## 1.3.0 — second bug hunt: data safety, security, sync consistency (released 2026-10-01)

The owner asked again for a thorough search for bugs, with freedom to decide what to fix
(2026-10-01). Nine read-only reviews (money maths, selling, sales and reports, sync, backup and
database, inventory and CSV, staff and credit, printing and camera, back-office screens) found
about 95 candidate problems; each was checked in the code, then fixed with a test where one is
possible (D-055). The most serious: Android's default database error handler would delete the
shop's data on the first damaged page; a new till's first-run Setup could reset the store's
receipt settings on every till once it joined; a restore could apply itself at a later start
without "Restart now"; the idle lock never fired after the phone's screen had turned off; a till
with a wrong clock silently lost its edits on the other tills.

- [x] Fixes and tests: see D-055
- [x] Local: 184 `:core` + 29 `:app` JVM tests pass, lint 0 errors, release APK 1,271,037 bytes
  (R8, local debug key; 1.2.0 was 1,209,645)
- [x] CI (API 21 and 36, tablet, release smoke) and perf FULL
- [x] Test build published as a pre-release

Results (2026-10-01): CI **199/199 on API 21 and 36** (38 new tests), lint, tablet and release
smoke green; perf FULL **PASS** on both (80/80 plans; cold start API 21 531–619 ms; report_year
1.2 s p50 on API 21, budget 3 s; sale commit 10 ms p95). Every data-layer SQL statement also
prepared against the v6 schema offline. Test build **v1.3.0** (pre-release; build 70, 1,241 KB —
1,271,103 bytes, SHA-256 `068b077a…9a28`); the public download stays v1.0.0 until the owner's
OK. The owner asked to release it (2026-10-01): **v1.3.0 is the latest release** (the website's
Download link serves it, SHA-256 checked); smoke baseline v1.3.0; CI now keeps each release
APK's `mapping.txt` (artifact `mapping`, attached to the release). Left for the owner (D-055): a new customer without a credit limit; refunds of a sale voided on
another till; refunds without restock and cost of goods; a shared backup counting as "backed up".

### Needs real-device testing (1.3.0)

1. Install over 1.2.0 (or 1.0.0): data, staff, settings and backup still there.
2. Staff → auto-lock 1 minute, sign in as a cashier, let the phone's screen turn off for 2 minutes,
   wake it: the sign-in shows at once, and the first tap does nothing on the bill.
3. Settings → Backup → restore a backup, press Back while "Checking…": close and open the app —
   nothing was restored. Restore again → "Restart now": the backup's data is back and someone must
   sign in (when PINs are on).
4. Android 13 or newer: pay RM 5 by card, then press Back (or swipe back): it asks before clearing
   the RM 5. On a tablet, rotate (or switch dark mode) while paying: the RM 5 is still there.
5. Wrong PIN 5 times, then set the phone's clock 1 hour forward: still waits. Set the date back a
   year: the wait is at most 30 s, not a year.
6. With Drive backup on, set the phone's date one day wrong and tap Sync now: the backup screen
   says the date or time is wrong.
7. Second till (if available): fresh install, first-run setup with any shop name, then turn on
   Drive backup with the store's account: the first till's receipt header, BRN and tax settings do
   not change.
8. A manager whose role has "Manage staff": making themselves owner or changing the owner's PIN is
   refused.
9. Return a discounted line one item at a time: every return is a positive amount and they add up
   to the line; a double tap on Refund makes one refund.
10. Products → Export, open in a spreadsheet, save, Import: no new products, also for 8-digit
    barcodes starting with 0.
11. Printer (when available): switch it off and on while receipts wait; a long picture receipt
    prints in full.
12. Camera scanning on a cheap phone: a barcode brought closer comes into focus again.

## 1.3.1 — auto-lock after the phone's screen was off (released 2026-10-01)

The owner reported that the auto-lock did not work after the phone's screen had been off
(2026-10-01). Confirmed: many phones only *pause* the app while their screen is off, and waking
them only resumes the screen — 1.3.0 checked the idle time when a screen started again and before
a tap counted, so on those phones the till stayed signed in (showing the bill) until the first
tap. 1.3.1 also checks when a screen resumes. `IdleLockTest` proves it: a screen only paused while
the phone slept must show the sign-in by itself when it resumes — it **fails on 1.3.0's code**
(CI run 36833978633, API 21 and 36: "still signed in after the screen resumed") and passes with the
fix; a second test sleeps and wakes the emulator for real (Android 6+; on Android 5 the emulator's
lock screen stays and would block the next tests).

- [x] Fix and tests
- [x] CI green: **201/201 on API 21 and 36**, tablet and release smoke (run 36835431618)
- [x] Test build **v1.3.1** (pre-release; build 79, 1,271,195 bytes, SHA-256 `4054647d…cefa`,
      `mapping-1.3.1.txt` attached)
- [x] The owner asked to release it (2026-10-01): **v1.3.1 is the latest release** (the website's
      Download link serves it, SHA-256 checked); smoke baseline v1.3.1

### Needs real-device testing (1.3.1)

1. Install over 1.3.0: data and staff still there.
2. With a staff PIN and auto-lock 1 minute: sign in, let the phone's screen turn off (or press the
   power button), wait 2 minutes, wake it — the sign-in shows at once, without a tap.
3. Wake it again within the minute: still signed in (no lock before the idle time).

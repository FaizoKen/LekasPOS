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
| — | **Release 1.3.1**: the auto-lock after the phone's screen was off | **released** 2026-10-01 |
| — | **Release 1.4.0**: third bug hunt — cross-cutting reviews, weak features (D-056) | **released** 2026-10-02 |
| — | **Release 1.5.0**: error reports to the developer, with the shop's consent (D-057) | **released** 2026-10-02 — the relay is live |
| — | **Release 1.5.2**: reports fast on the store's tablet (D-058) — from its first error reports | **released** 2026-10-02 — QUICK and FULL passed on the store's tablet |
| — | **Release 1.6.1**: the app updates itself from GitHub releases (D-059) | **released** 2026-10-03 — the owner updated 1.6.0 → 1.6.1 inside the app on the phone |
| — | **1.7.0**: fourth bug hunt — new features, the outside world, long use; payment methods, special prices (D-060) | **released** 2026-10-03 — the owner asked to release it; offered in the app to shops on 1.6.1 |
| — | **1.7.1**: fifth bug hunt — what 1.7.0 broke, killed apps, crafted files, mobile data, a first week, large fonts (D-061) | test build v1.7.1 (pre-release) — released to everyone as part of 1.8.0 |
| — | **Release 1.8.0**: sixth bug hunt — double taps, a till left on all day, two tills, scanners everywhere; price change from the price check, cash count, "Save and add another" (D-062) | **released** 2026-10-04 — the owner asked to release it; offered in the app to shops on 1.7.0; phone checks still to run |
| — | **1.9.0 / 1.9.1**: the cashier's screen — clear a bill without a PIN, only the buttons the cashier may use (a manager's PIN shows the rest for a bill), faster taps; 1.9.1: the bill line's buttons on one line, nothing to scroll with the phone held sideways (D-063) | **released** 2026-10-04 (v1.9.1) — the owner asked to release it; offered in the app to shops on 1.8.0; phone checks still to run |
| — | **1.10.0 / 1.10.1**: one selling screen for everyone (the owner's Discount and "More" moved to Menu → Discount); the payment in two clear steps (D-064); 1.10.1: Google Drive missing from the folder picker → a backup file to Drive | test builds v1.10.0 (build 131) and **v1.10.1** (build 134) — waiting for the owner's phone check |
| — | **1.11.0**: daily sales report to the owner's Google Drive, also on a tablet with an old Drive app (D-065) | test build **v1.11.0** (build 136) — the owner adds the `drive.file` scope in Google Cloud, then the phone check |
| — | **1.12.0**: product pictures and colours, more items on the selling screen (D-066); staff on a shared till — removals on record, handover count, lock after each sale, staff check (D-067) | test build **v1.12.0** (build 139) — the owner tested it with v1.13.0 (2026-10-08): "all good" |
| — | **1.12.1**: the shift asked by itself — start, yesterday's never closed, handover; Menu → Open/Close shift; close in three steps with what stays in the drawer; the next opening checked against it; shifts on for shops with staff (D-068) | test build **v1.12.1** (build 143) — the owner tested it with v1.13.0 (2026-10-08): "all good" |
| — | **1.13.0**: customer screen on a second display — Miracast (Screen mirroring, Smart View, Wireless display), HDMI or USB-C; the bill, the item just added with its picture, the total, the change (D-069) | **released** 2026-10-08 (v1.13.0, build 145) — tested by the owner: "all good"; also carries 1.10.0–1.12.1 |

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

## 1.4.0 — third bug hunt: cross-cutting reviews, weak features (released 2026-10-02)

The owner asked for a third, free-hand search for bugs and weak features (2026-10-02). Twelve
read-only reviews took angles the earlier hunts had not (concurrency, money end to end, clocks,
Android versions and devices, start-up and failures, real-world input, security, daily shop
workflows, performance at scale, regressions of D-053 to D-055); every finding was checked in the
code, then fixed, most with a test (D-056). The most serious: a cashier given the owner's approval
for the Staff screen could make themselves owner; damage in a page read right after opening failed
every start with no way to restore; a full phone crashed the app after every start (WorkManager);
the daily backup paused sales for many seconds at opening time; a till whose clock once ran ahead
made the other tills' sales and recounts "earlier" than its stock count; CSV files merged rows that
shared a placeholder SKU into one product and imported "Nescaf�" from Excel files.

- [x] Fixes and tests: see D-056
- [x] Local: `:core` and `:app` JVM tests pass (197 + 37), lint 0 errors
- [x] CI (API 21 and 36, tablet, release smoke) and perf FULL
- [x] Test build published as a pre-release
- [x] The owner tested it on the phone: all 15 checks below passed (2026-10-02), and asked to release
      it: **v1.4.0 is the latest release** (the website's Download link serves it, SHA-256 and
      version checked); smoke baseline v1.4.0

Results (2026-10-02): CI **216/216 on API 21 and 36** (15 new tests), lint, tablet and release smoke
green (run 36958602980); perf FULL **PASS** on both (83/83 plans; cold start API 21 545–609 ms,
API 36 587–653 ms; sale commit 9.6 ms p95; report_year 1.2 s on API 21, budget 3 s; database
356 MB) — run on 8a0e1eb, the fixes after it touch no hot SQL. The first CI run found a real bug
(the sign-in stored no idle heartbeat until the first tap) and two test mistakes; a read-only review
of the whole change found four more (the store's first PIN closed the Staff screen before the
recovery code showed; a long payment took the change off the screen; a delivery order's barcode
scanned into Receive's reference field; dashed barcodes from older versions on import) — all fixed
with tests (D-056). Test build **v1.4.0** (pre-release; build 84, 1,260 KB — 1,290,312 bytes, same
release key as 1.3.1, SHA-256 `75f2a10f…5050`, `mapping-1.4.0.txt` attached), made the latest
release after the owner's phone tests.

### Needs real-device testing (1.4.0)

1. Install over 1.3.1: data, staff, settings and backup still there.
2. **Camera at the till**: scan an unknown barcode with the camera → "Add product" → it is on the
   bill; scan a weighed item's barcode → the weight is asked.
3. **Payment**: a bill of RM23.45 offers Exact, RM25, RM30, RM50; RM61.70 offers RM65, RM70, RM100.
   Turn the phone to landscape and open a weighed item's weight pad and a manager PIN: every key,
   "0" and OK can be reached (scroll if needed).
4. **Refund** a sale paid with customer credit: "Pay back with" shows Customer credit first. Refund
   an item without "put back in stock": the day's report keeps its cost (profit goes down by it).
5. **Credit**: a customer owes RM10.03 and pays it all in cash → the balance is 0.00 and the shift
   expects RM10.05; a repayment of more than is owed is refused; the activity log shows "Credit repaid".
6. **Staff** (PIN login on): as a cashier, have the owner approve the Staff screen, then try to make
   yourself owner → the owner's PIN is asked for that change. A manager cannot add "Settings" to
   their own role. On a fresh install (no PINs yet), set the owner's first PIN: the recovery
   code shows and the Staff screen stays open.
7. **Auto-lock** 1 minute: open Pay, leave it 2 minutes, press Cancel → the sign-in shows at the next
   tap. Signed in on the selling screen, restart the phone from its power menu; 2 minutes after it
   started, open the app → the sign-in shows. Open Pay, wait 2 minutes, then pay in cash: the
   change shows until you close it, then the sign-in shows.
8. **Date**: set the phone's date to 2020 and press Pay → "Check the date and time" (no sale);
   set it back → selling works.
9. **Held bills**: hold a bill → Held bills → "Delete a bill…" deletes it (with the cancel permission).
10. **Sales**: type only the number of a receipt ("123") in Sales & refunds → it is found.
11. **Shift**: close a shift with a note → the shift report shows the note.
12. **Products → Import** a CSV saved by Excel as "CSV (Comma delimited)" with accented names far
    down the file: names come in right. A file whose SKU column is all "-" imports every row as its
    own product.
13. **Receive stock** with a keyboard scanner while the invoice number field has the cursor: the item
    is added and the invoice number stays as typed. Scan a delivery order's own barcode into that field:
    it stays there (no "not found").
14. **Diagnostics → Share the error log**: a text file (or "the error log is empty").
15. Printer and drawer (when available): clear the queue while the printer is off, switch it on —
    nothing cleared prints.

## 1.5.0 — error reports to the developer, D-057 (released 2026-10-02)

The owner asked that errors, crashes and failed checks on shops' phones reach the developer by
themselves, so bugs are known and fixed quickly. Design chosen with the owner: the shop is asked
once; reports go through a small relay to a **private** GitHub repository, one issue per bug.

- [x] `:core` `diag.CrashText` (fingerprint, title, scrubbing, ANR main thread) + `ErrorReport`,
      8 unit tests
- [x] `app.ErrorReports`: crashes, `Log.e`, failed perf test, Android 11+ ANR / app ended by
      Android; queue in `files/reports`; `ReportWorker` job; crash tried at once (≤ 2.5 s); daily
      limits; only release-signed builds send on their own
- [x] The question on the selling screen (once; someone with the Settings permission; no bill open),
      Settings → Error reports, Diagnostics → "Send a report to the developer" (note + optional
      contact); English + Bahasa Melayu
- [x] Refusals (`ActionRefused`) are warnings, not error reports
- [x] R8 keeps the app's own class names (stable fingerprints): +67 KB
- [x] Relay `relay/` (Cloudflare Worker) + 10 unit tests + `relay.yml`; private repository
      `FaizoKen/LekasPOS-reports` with labels and the "Readable trace" workflow
- [x] Privacy policy (EN + MS), `docs/PLAY.md` data safety, D-057, architecture.md §9
- [x] The owner renamed the workers.dev subdomain to `faizoken` and added the two tokens; the relay
      is deployed (relay.yml) and answers. End-to-end test from this machine: four reports of one
      bug became one issue (after the duplicate fix), the readable trace was commented; the test
      issues are closed (`not-a-bug`, `duplicate`)
- [x] CI green on 646cf2b (run 36980633138): **220/220 on API 21 and 36**, tablet, release smoke,
      lint; release APK **1,378,310 bytes** (1,346 KB; 1.4.0 was 1,290,312 — +67 KB readable names,
      +21 KB the feature). The relay test was skipped (its address does not resolve before the rename)
- [x] Android 5 could not reach the relay ("Trust anchor for certification path not found"):
      `RelayTrust` bundles the four public roots Cloudflare issues from. CI on 2ece049 (run 90):
      **220/220 on API 21 and 36** — the relay answered 204 on both (Android 5 through the bundled
      roots, Android 16 through its own store); tablet, release smoke, lint green
- [x] Perf FULL (run 36985370602, ea5e8dd — the TLS change after it touches no hot path): **PASS** on
      API 21 and 36; cold start median 629 ms (API 21) / 681 ms (API 36); sale commit p95 11.1 ms (API
      21); report_year 1.2 s on API 21 (budget 3 s)
- [x] Test build **v1.5.0** (pre-release; build 90, 1,351 KB — 1,383,754 bytes, release key,
      SHA-256 `20d52e0d…8675`, `mapping-1.5.0.txt` attached); the public download stays v1.4.0 until
      the owner's phone tests
- [x] The owner tested it on the phone: all 5 checks below passed (2026-10-02), and asked to release
      it: **v1.5.0 is the latest release** (the website's Download link serves it, SHA-256 and version
      checked); smoke baseline v1.5.0

### Needs real-device testing (1.5.0)

1. Install over 1.4.0: data, staff and settings still there; the selling screen asks "Help fix
   problems?" once (with no bill open). Answer **Send reports**.
2. Settings → Error reports shows "On"; tap it, answer **Don't send** → "Off"; turn it on again.
3. Diagnostics → **Send a report to the developer**, type a note → "Report sent" (or "will be sent
   when online" with the internet off — then it goes by itself once online). The issue appears in
   `FaizoKen/LekasPOS-reports` with the note.
4. With PIN login on, a cashier without the Settings permission is never asked; the owner is.
5. Everything else works as in 1.4.0 (a quick sale, a refund, a backup).

## 1.5.1 — reports fast on the store's tablet, D-058

The first error reports (#4 QUICK, #6 FULL in `FaizoKen/LekasPOS-reports`) were the performance
test on the store's own tablet (Android V62, Android 10, SQLite 3.22): one plan check failed on
its older SQLite (`shift_current`) and, at FULL, five budgets were missed (`report_year` 6.6 s,
`report_month` 1.7 s, `slow_movers` 1.37 s, `popular_items` 445 ms, `search_multiword` p95 54 ms).

- [x] Measured first, on the same data shape (Node's SQLite and the SQLite 3.22 shell): the time
      is the per-product sort of every summary row of a period, done twice per report
- [x] Schema v7: `sum_year_product` (+ maintenance, rebuild, migration, snapshot 7.sql, migration
      test) and `shift_open` index (plan on SQLite 3.22: SEARCH, no sort)
- [x] `:core` `RangePlan` (years, months less days, probes for "no sales outside"), 5 unit tests incl.
      400 random periods that must add up exactly; `MonthSplit` removed
- [x] `ReportDao.summary`: categories and best sellers from one reading; slow movers in one reading
- [x] Popular tab: kept ranking shown at once, refreshed in the background; new instrumented test
- [x] Multi-word search sorts ids only (p95 −35%, same results)
- [x] Report correctness: instrumented test with two years of random sales and 64 periods (products,
      categories, slow movers, popular items) against the per-day rows; derived consistency covers
      the year table
- [x] Check reports keep their numbers (no "<number>" in performance reports)
- [x] Cursor windows: the first CI perf run showed the one reading slower on API 21 (its ~50,000 rows
      overflow the 2 MB window and the query ran again per window): API 28+ use a 16 MB window,
      API 21–27 two readings with small results
- [x] CI on cf39c1c (run 94): **222/222 on API 21 and 36**, tablet, release smoke (upgrade from
      1.5.0), lint; release APK **1,388,294 bytes** (1,356 KB)
- [x] Perf FULL (run 36999017940): **PASS** on both. API 36 (the tablet's path): report_year 867 →
      185 ms, report_month 162 → 76, report_calendar_year 214 → 102, slow_movers 130 → 59,
      search_multiword 6.4 → 1.7, Popular tab 34 → 0.3 (ranking 20 ms in the background). API 21:
      report_year 1,217 → 611, report_month 307 → 240, slow_movers 269 → 162
- [x] Test build **v1.5.1** (pre-release; build 94, release key, SHA-256 `1f41ca88…4488`,
      `mapping-1.5.1.txt` attached); the public download stays v1.5.0 until the owner's tests
- [x] The owner ran FULL on the tablet with 1.5.1 (#6, build 94): QUICK passed; FULL still missed
      report_month 1.8 s, report_year 4.8 s, report_calendar_year 3.3 s (slow movers 0.68 s and the
      Popular tab 4 ms passed). The perf run gained an Android 10 emulator and notes on where a
      report's time goes: on Android 10 every returned row costs ~9 µs (0.6 µs on Android 16), and
      1.5.1 returned every product's totals. 1.5.2: two readings with small results on every Android
      (Android 10 emulator: month 278 → 91 ms, year 649 → 277, calendar year 445 → 87)
- [x] Scaled by the scenarios that did not change (the tablet is ~10–12× the Android 10 emulator),
      month would still be ~1 s on the tablet: the per-category part grouped every product of the
      period. Schema v8: `sum_day/month/year_category` (a product's sales under its current category,
      moved when it changes category — here or from another till, also when its sales arrived
      first), indexes `sum_month_product_p`, `sum_year_product_p`; migration builds them read-only
      from the product totals; snapshot 8.sql, migration test, derived consistency with random
      category moves, a sync test across tills
- [x] Search: one-letter words ("julie s" from "Julie's") filter the longer words' candidates
- [x] Perf FULL on v8 (run 37009379208): **PASS** on API 21, 29 and 36, every plan check ok.
      Android 10 emulator: report_month 92 → 31 ms, report_year 195 → 68, report_calendar_year
      56 → 32, slow_movers 72, stock_value 37 (× ~11 for the tablet: all well inside budget)
- [x] CI on 3ad2ec7 failed 17 tests per API: a product from another till with no category bound
      a null into rawQuery (the sync round stopped), and one search test expected the wrong order.
      Fixed in d9d6ab9 (category 0 stands for none; no update when the product has no sales)
- [x] Stock value was the tablet's closest call (459 of 500 ms): grouped first, then the category
      names (one name lookup per category, not per product) — −28% on SQLite 3.22, same rows
- [x] CI on 4579366 (build 101): **224/224 on API 21 and 36**, tablet, release smoke, lint; release
      APK **1,392,493 bytes** (1,360 KB). Perf FULL (run 37012094791): **PASS** on API 21, 29, 36;
      stock_value on Android 10 36.7 → 31.2 ms, Android 5 65 → 50 ms
- [x] Test build **v1.5.2** (pre-release; build 101, release key, SHA-256 `bd0dc579…0efd`,
      `mapping-1.5.2.txt` attached); linked on #4 and #6. The public download stays v1.5.0
- [x] The owner ran QUICK and FULL on the store's tablet with 1.5.2: **both passed**
- [x] **Released** v1.5.2 as latest (2026-10-02); `PREV_APK_URL` → 1.5.2; #4 and #6 closed

### Needs real-device testing (1.5.2)

1. Install over 1.5.1 (or 1.5.0): data, staff, settings still there (the update builds the year and
   category totals once).
2. **Diagnostics → Run QUICK, then FULL** on the store's tablet: PASS (or, if anything fails, the
   report arrives in `LekasPOS-reports` with all its numbers and notes).
3. Reports → This year, Last year, This month: the same totals and category split as before, faster.
4. Move a sold product to another category: its sales show under the new category in Reports.
5. Selling screen: the Popular tab shows at once, also right after the app was closed and opened.
6. Search "julie s" or two words (e.g. "milo susu"): the expected products.

## 1.6.0 — the app updates itself, D-059

The owner asked that the app check for a new version and install it from inside the app (until now:
download from the website and install over). Design: the latest GitHub release, checked daily; the
download checked against GitHub's SHA-256 and the release key; Android's installer confirms.

- [x] `:core` `update.Releases` (which release, which APK, versions) + `ReleaseNotes` ("What's new"
      in English or Malay, Markdown taken out): 12 unit tests
- [x] `app.AppUpdates`: GitHub API with ETag, download (HTTPS only, redirects by hand, `.part`,
      size + SHA-256), APK checks (package, newer build, signing key), installer intent (FileProvider
      on Android 7+, a readable file on 5–6); `UpdateWorker` daily when online (release-signed builds,
      not Play installs); "updated to …" once after an update
- [x] `PublicTrust` (was `RelayTrust`): + USERTrust ECC/RSA and Sectigo E46/R46 (GitHub) beside the
      relay's roots; every chain checked with `openssl` against these roots only (api.github.com,
      github.com ECDSA and RSA, release-assets.githubusercontent.com, the relay)
- [x] UI: "Update 1.6.0" pill on the selling screen (Settings permission), Settings → App updates
      (state, "Look every day", "Include test versions", Check now), the offer with What's new and
      size, download progress (Cancel), Android 8+ install permission explained first, no open bill,
      manager's PIN when needed; English + Bahasa Melayu
- [x] `REQUEST_INSTALL_PACKAGES`; privacy policy (EN + MS), website update steps, `docs/PLAY.md`
      (the Play build must drop it), D-059, architecture.md §9a, BUILD.md release checklist
- [x] Instrumented `AppUpdatesTest`: GitHub's JSON; a live check; release 1.5.2's APK downloaded and
      checked on the emulator (SHA-256, package `com.lekaspos.app`, build, the release key's
      certificate) — the Android 5 path through the bundled roots
- [x] Local: `:core` 221 and `:app` 37 JVM tests pass, lint 0 errors; release APK **1,423,396 bytes**
      (1,390 KB; 1.5.2 was 1,392,493 — +31 KB)
- [x] CI on f22e775 (run 37026827976, build 104): **228/228 on API 21 and 36**, tablet, release smoke
      (upgrade from 1.5.2), lint. `AppUpdatesTest` on Android 5: the phone's trust store refused
      GitHub, the bundled roots took it — check and download OK; on Android 16 the download OK and the
      check skipped (GitHub answered 403: 60 checks an hour per address, CI runners share theirs)
- [x] Perf QUICK (run 37026891340): **PASS** on API 21, 29, 36; cold start median 613 / 800 / 554 ms
- [x] Test build **v1.6.0** (pre-release; build 104, 1,391 KB — 1,424,038 bytes, release key,
      SHA-256 `698d72df…286d`, `mapping-1.6.0.txt` attached); the public download stays v1.5.2
- [x] Test build **v1.6.1** (the same code, a higher version: the in-app update to see on the phone).
      CI on e226f82 (run 37030864881, build 105): **228/228 on API 21 and 36**, tablet, release smoke;
      the GitHub check this time passed on both (Android 5 through the bundled roots). Pre-release;
      1,424,032 bytes, release key, SHA-256 `56884ff0…d976`, `mapping-1.6.1.txt` attached. GitHub's
      release list (what a phone with test versions on reads) shows v1.6.1 first with its digest
- [x] The owner tested it on the phone (2026-10-03): the update from 1.6.0 to 1.6.1 inside the app
      works, and asked to release it: **v1.6.1 is the latest release** (2026-10-03; the website's
      Download link serves it, SHA-256 and version checked; public notes with What's new in English
      and Malay); smoke baseline (`PREV_APK_URL`) v1.6.1. v1.6.0 stays a test build.
      Shops on 1.5.x install 1.6.1 by hand once; from 1.6.1 on, new releases are offered in the app
- [x] Perf FULL after the release (run 37033268746, 9c04018): **PASS** on API 21, 29 and 36 — 47/47
      scenarios and 95/95 plan checks each; cold start median 583 / 900 / 659 ms. API 36 as in 1.5.2
      (×1.00–1.14). API 21 and 29 ×1.2–1.7 slower everywhere (sale commit ×2.5), and so was the
      unchanged test-data generator (51 → 131 s, 39 → 184 s): slower CI machines that run, not the app
      (1.6.x changes no database code). Still well inside budget: on API 21 sale commit p95 13 ms
      (150), report_year 216 ms (3,000), report_month 118 ms (1,000), search p95 ≤ 7 ms (50)

### Needs real-device testing (1.6.0)

The update itself can only be seen once a newer version than the phone's is published, so:
1. Install the v1.6.0 test build over 1.5.2 (from the pre-release page, as before): data, staff and
   settings still there.
2. Settings → **App updates**: "This phone has LekasPOS 1.6.0 (build N)", "Look every day" on. Tap
   **Check now** → "Up to date" (1.5.2 is the latest release).
3. Tick **Include test versions** (**v1.6.1** is a test build, a pre-release): Check now
   → "Version 1.6.1 is ready…" / the offer shows What's new and the size; the selling screen shows
   **Update 1.6.1** (with no PIN login, or signed in as the owner; not as a cashier).
4. With an item on the bill, Update now → "Finish or hold the bill first". Clear the bill.
5. **Update now** → the download (progress) → Android 8+: "Allow updates" → the settings screen →
   turn on "Allow from this source" → back → Android's installer asks → confirm → "App installed" →
   Open: "LekasPOS was updated to version 1.6.1"; sales, products, staff, settings, shift still there.
6. The internet off: Check now → "No internet connection…"; nothing else changes, selling works.
7. Untick "Include test versions": the 1.6.1 offer is gone when the phone has 1.6.0.
8. On the store's tablet (Android 10) the same once, if possible.

## 1.7.0 — fourth bug hunt: new features, the outside world, long use (D-060)

The owner asked for another free-hand search for bugs and weak features (2026-10-03). Nine read-only
reviews took angles the earlier hunts had not (the self-update, error reports and the relay, report and
category totals, promotions and money, years of use and a full phone, what the receipt says, a crash
sweep of every screen, Google Drive / folder / network failures, the website and privacy policy against
the app); every finding was checked in the code first, then fixed, most with a test (D-060). The most
serious: the daily SD/USB folder copy deleted the owner's own saved backups (and its own daily copies);
turning on Drive backup with a Google account that holds another shop merged the two shops for good; a
promotion switched off on another till kept applying after a sync round that failed later; "buy X get Y"
put the whole saving on the free item, so returning the paid items refunded their full price; a full
Google Drive showed raw JSON and was "tried again" for ever; a nearly full phone silently stopped its
daily backup and then failed sales with SQLite's English; a refused update was downloaded again every
day; reports were kept while the shop had said "Don't send"; several screens crashed when a job finished
after Back. Weak features added: **Settings → Payment methods** (DuitNow QR, Touch 'n Go, bank transfer
— each counted on its own) and **special prices with dates** (a promotion of one unit).

- [x] Fixes and tests: see D-060
- [x] Website, privacy policy (EN + MS), README, PLAY.md and the release checklist corrected
- [x] Local: `:core` 227 and `:app` 40 JVM tests pass, lint 0 errors; relay 19/19 (`node --test`); release APK
      **1,439,881 bytes** (1,406 KB; 1.6.1 was 1,424,032 — +16 KB)
- [x] CI on 56ebb80 (run 37122710829, build 110): **230/230 on API 21 and 36**, tablet, release smoke (upgrade
      from 1.6.1), lint. The first run (ddee94d) failed 3 tests per API: ErrorReportsTest turned reports off and
      expected errors to be kept — the old behaviour D-060 changes; the tests now turn reports on
- [x] Relay: tests 19/19 and deployed by relay.yml (run 37121926625); website deployed (Pages)
- [x] Perf FULL (run 37121963016, ddee94d): **PASS** on API 21, 29 and 36, every plan check ok; API 21 sale
      commit p95 11.5 ms (150), report_month 74 ms (1,000), report_year 137 ms (3,000), multi-word search p95
      4.2 ms (50); cold start API 21 ~400 ms usable
- [x] Test build **v1.7.0** (pre-release; build 110, 1,441,717 bytes, release key — same certificate as 1.6.1,
      SHA-256 `ba848688…9007`, `mapping-1.7.0.txt` attached). The public download stays v1.6.1
- [x] The owner asked to release it (2026-10-03): **v1.7.0 is the latest release** (public notes with What's new
      in English and Malay; the website's Download link serves it, SHA-256 and version checked); smoke baseline
      (`PREV_APK_URL`) v1.7.0. Shops on 1.6.1 are offered it inside the app within a day. The privacy policy's
      contact is now mail@faizo.net (the owner's choice). The checks below were not run on a phone before the
      release: worth running on the shop's phone and tablet after the update

### Needs real-device testing (1.7.0)

1. Install over 1.6.1 from inside the app (Settings → App updates → Include test versions): data,
   staff, settings, backup still there.
2. **Payment methods**: Settings → Payment methods → + "DuitNow QR" (E-wallet or QR) → it shows in Pay;
   pay a bill with it; the shift report and Reports list it on its own line. Hide it → gone from Pay,
   still named in old reports. Cash cannot be hidden.
3. **Special price**: Promotions → new → Multi-buy or special price, "How many to buy" 1, price RM3.99,
   end date tomorrow → a RM4.50 product rings up at 3.99; the receipt names the promotion.
4. **Buy 2 get 1** on three different products (4.50, 4.20, 4.00): total 8.70; refund the 4.50 item
   alone → the refund is 3.08, not 4.50.
5. **Folder backup** (SD card or USB): save a backup by hand into the same folder ("lekaspos-backup-…"),
   then Copy now a few times → the hand-saved file stays.
6. **Second shop on one account** (spare phone with a few products): turn on Google Drive backup with the
   store's account → "Join another shop?" naming the store's tills; Cancel leaves it off.
7. **Reports**: This month → "Change vs 1/9/2026 – 3/9/2026 (…)"; This year → the same days last year;
   Today → yesterday.
8. **Error reports** off → make an error happen (e.g. pick a non-backup file to restore) → turn reports on
   → nothing old is sent. A cashier (PIN login) cannot change Settings → Error reports or App updates
   without the manager's PIN.
9. **Storage** (only if a phone is nearly full): the selling screen shows "Storage almost full" and the
   message says how much is free.
10. Printer (when available): a copy of a voided sale has no e-invoice QR; a split payment where the
    e-wallet paid all but the rounding prints no "Cash 0.00".

## 1.7.1 — fifth bug hunt (D-061)

The owner asked for another bug hunt right after 1.7.0 (2026-10-03). Nine read-only reviews from new
angles (regressions of 1.7.0, Android ending the app, threads, crafted files, battery and data, a new
shop's first week, test gaps, accessibility, upgrades and mixed versions); every finding checked in the
code. The most serious: 1.7.0 deleted older "Back up now" copies (reverted); a manager could put
themselves in a wider role; hiding "Customer credit" paid credit refunds out in cash; a broken backup
could lock the till out on every start; one unreadable sync event stopped all imports for good; a
year-old store's sync used tens of MB a day per till; "50" typed for RM50 was quietly RM0.50 of the bill.

- [x] Fixes and tests: see D-061
- [x] Local: `:core` 245 and `:app` 40 JVM tests pass, lint 0 errors; English and Malay strings match
- [x] Perf FULL (run 37136972895, 5794a7c): **PASS** on API 21, 29 and 36
- [x] CI on da6c5c8 (run 37138007378, build 114): **226/226 on API 21 and 36**, tablet, release smoke (upgrade
      from 1.7.0), lint. The run before failed 2–3 tests per API: a new staff test returned a value (JUnit
      refused the class) and two sync tests expected the till's card every round (now every 15 minutes)
- [x] Test build **v1.7.1** (pre-release; build 114, 1,470,071 bytes — +28 KB, release key (same certificate),
      SHA-256 `7a87e543…877e`, `mapping-1.7.1.txt` attached). The public download stays v1.7.0
- [x] Not released on its own: its changes went public with 1.8.0 (2026-10-04); the checks below still apply

### Needs real-device testing (1.7.1)

1. Install over 1.7.0 (Settings → App updates → Include test versions): data, staff, settings still
   there; your own "Back up now" copies are all still listed in Backup & restore.
2. **Pay**: a bill of RM85, type 5 0 → Cash → it asks "Only RM0.50 in cash?" (cancel); 5 0 0 0 → change.
   A product with price 0.00 asks for its price when scanned.
3. **Large font** (phone Settings → Display → Font size largest), selling screen in portrait and
   landscape: the bill total, a selected line's − / + / Remove / More, the payment amounts and the
   change are whole and readable.
4. **Payment methods**: "Customer credit" and "Cash" cannot be hidden; cash always opens the drawer;
   renaming "E-wallet / QR" asks first.
5. **Staff** (PIN login on, a role with "Manage staff" but not "Settings"): moving yourself to a role
   with Settings is refused unless the owner approves.
6. **Promotions**: a weighed product cannot be added to a promotion (message).
7. **Products → Import** a CSV saved from Excel with columns "Item Name", "Selling Price (RM)", "Bar Code",
   "SST" (Y/N) → it reads them; an .xlsx file says to save it as CSV. Close the app half-way through a
   large import and import the same file again → it continues where it stopped.
8. **Products**: type or scan a new barcode in the search → "Tap here to add it".
9. Malay: the Hold button says "Tangguh", held bills "Bil ditangguh".
10. Printer (when available): choose the printer, press Back without Save → it is kept.

## 1.8.0 — sixth bug hunt: double taps, a till left on all day, two tills, scanners everywhere (D-062)

The owner asked for another free-hand hunt for bugs and weak features (2026-10-04). Read-only reviews
from new angles (a till that never leaves the selling screen, double taps and slow phones, two tills
editing the same things, a new till joining a store, stock counts across tills and clocks, Malay
receipts, printers and scanners, what becomes an error report, D-061's weak features); every finding
checked in the code, then the whole change reviewed again by three independent reviews, whose findings
were fixed too (among them: this hunt's own stock-count change would have shown every count as "no
loss", a UPC-E scan missed products saved with 13 digits, and the new tile refresh could crash on a
phone whose product grid was hidden). The most
serious: a double tap on Pay opened two payments; the selling screen's tiles kept the morning's stock
and prices all day; tapping the app's icon closed a half-typed product or refund; a role edited on two
tills lost one edit; a stock count's "expected" ignored the other till's sales; a printer out of paper
froze the queue at "printing" until it was switched off; a serial scanner worked only on the selling
screen; a new till joining a store kept its own PINs, so the store's staff could not sign in on it.
Weak features added: **change a price from the price check**, **count the drawer by note and coin**,
**"Save and add another"** and scanning on the product form.

- [x] Fixes and tests: see D-062
- [x] Local: `:core` 259 and `:app` 42 JVM tests pass, the instrumented tests compile (not run: CI), lint
      0 errors; release APK **1,500,841 bytes** (1,466 KB; 1.7.1's CI build was 1,470,071 — about +31 KB)
- [x] CI on 3a46809 (run 37168100137, build 117): **250/250 on API 21 and 36**, tablet, release smoke (upgrade
      from 1.7.0), lint. The run before (d9ee534, build 116) failed: the leak test found every old selling
      screen kept by the keyboard tip's watcher (Android 7.0+ keeps a rebuilt screen's window), and a sync
      test still renamed a new till's built-in rows by hand (a new till now makes them again when it joins);
      its API 21 emulator also froze half-way (adb answered nothing) — it passed on the next run, and CI now
      streams the log while the tests run
- [x] Perf FULL (run 37168780238, 3a46809): **PASS** on API 21, 29 and 36, every plan check ok; API 21 sale
      commit p95 12.9 ms (150), report_month 122 ms (1,000), report_year 217 ms (3,000), multi-word search p95
      4.8 ms (50); cold start API 21 ~580 ms usable (1.7.1: ~600)
- [x] Test build **v1.8.0** (pre-release; build 117, 1,501,877 bytes, release key — same certificate as 1.7.1,
      `0a67abec…d4b9`; SHA-256 of the APK `78569c54…12de6`, `mapping-1.8.0.txt` attached)
- [x] The owner asked to release it (2026-10-04): **v1.8.0 is the latest release** (public notes with What's new
      in English and Malay, covering 1.7.1 too; the website's Download link serves it, SHA-256 checked, and
      GitHub's latest release is v1.8.0); smoke baseline (`PREV_APK_URL`) v1.8.0. Shops on 1.7.0 are offered it
      inside the app within a day. No privacy change (nothing new stored, sent, asked for or connected to). The
      checks below (and 1.7.1's) were not run on a phone before the release: worth running on the shop's phone
      and tablet after the update

### Needs real-device testing (1.8.0)

1. Install over 1.7.1 (Settings → App updates → Include test versions): data, staff, settings still there.
2. **Price check → Change price**: scan a product → Change price → type the new price (a cashier is asked
   for the manager's PIN) → the tile shows the new price; Settings → Activity log names it "(price check)".
3. **Products → +**: with the cursor in the name field, scan the product → the barcode is added (not typed
   into the name). "Save and add another" → a new form with the same category and tax. In the Products
   list, search "milo", then scan a barcode → the search holds only the barcode.
4. **Shift**: Open shift → Count cash → notes and coins → the total fills the amount. Close the shift with a
   count that matches → the note lists the notes and coins.
5. **Pay**: the amount shown when the cash dialog opens is grey; typing 5 0 gives RM50.00 (not added on).
   Double-tap Pay quickly → one payment. Double-tap Print on the result → one receipt.
6. **Tiles**: sell a product that tracks stock → its tile shows the lower stock at once. With two tills:
   change a price on one → within a sync round the other's tile shows it, without leaving the screen.
7. **App icon**: Products → + → type half a product → Home → tap the app's icon → the form is still there.
8. **Two tills**: Settings → Staff → Roles: on till A switch on one permission, on till B another, sync →
   both are on. Count a product on A while B sells it offline → after sync the count's "expected" includes
   B's earlier sales.
9. **New till** (spare phone, fresh install, a PIN set, never sold): turn on Google Drive with the store's
   account → it says the till now uses the shop's staff and PINs; the store's staff sign in with their PINs.
10. **Customers**: remove a customer who still owes → still listed as "removed" → Receive payment works;
    editing is refused.
11. Malay receipt (Settings → Receipt language Malay): payments print "Tunai", "Kad", "E-dompet", tax
    "Cukai", rounding "Pembundaran".
12. Printer (when available): take the paper out while a long receipt prints → within ~10 s the printer
    status says it is not taking data; put paper back → the job prints after a retry.
13. Serial (SPP) scanner (when available): Stock → Count → scan → the product's count opens; Receive →
    scan → the line is added; back on the selling screen the scanner is still connected.


## 1.9.0 — the cashier's screen: clear without a PIN, only what the cashier may do, faster taps (D-063)

The owner asked (2026-10-04): clearing a bill should not need a manager; a cashier without a manager's
permission should not see the buttons that need one; audit the UI so it is easy for a cashier; make tiles
and buttons fast to tap. One read-only review of the cashier's other screens (shift, sales, customers,
sign-in, payment and result dialogs) fed the change; every finding checked in the code first.

- [x] **Clear bill** (was "Cancel bill") and deleting a held bill: no PIN, still in the activity log;
      `Perm.CANCEL_BILL` retired from the role editor
- [x] **Hidden without the permission:** Discount, line "More" (when it would offer only the quantity),
      Change price (line, price check), Open cash drawer, the "Manage shop" entries one may not use, receipt
      copies, the customer chip, shift cash in/out/drop, report and past shifts, Refund and Void, a
      customer's balance adjustment and credit limit; "Receipts" instead of "Sales & refunds"; no "No printer
      set up" or low stock on a cashier's result; data-safety pills say "tell the owner" to a cashier
- [x] **Manager PIN** (menu): the manager's buttons show for the bill on the till, a "Manager: name ✕" pill
      in the top bar, ended by the bill (paid, held, cleared), a lock, the pill or 5 minutes; an unknown
      barcode asks a cashier for the manager's PIN before the product form
- [x] **Faster taps:** no tile animations, badge-only redraw, immediate press feedback on the selling
      screen's lists and keypad dialogs, tile taps in order with each product read once per bill, the beep
      off the main thread, no keyboard call per tap; the payment keypad no longer jumps when the change
      appears; one dialog for a double tap on a weighed tile, on Open shift or Close shift
- [x] Sign-in after a lock starts at the last person's PIN ("Not you?" to switch)
- [x] Local: `:core` 259 and `:app` 42 JVM tests pass, the instrumented tests compile, lint 0 errors; release
      APK **1,508,297 bytes** (1,473 KB; 1.8.0's local build was 1,500,841 — about +7 KB)
- [x] CI on a0f2641 (run 37177213243, build 120): **251/251 on API 21 and 36**, tablet, release smoke (upgrade
      from 1.8.0), lint; release APK 1,509,248 bytes
- [x] Perf QUICK (run 37177241588, a0f2641): **PASS** on API 21, 29 and 36; cold start median to a usable till
      644 / 753 / 512 ms (1.6.1: 613 / 800 / 554)
- [x] An independent review of the change found five defects, all fixed in efb591d: a manager's help carried
      over to a held bill resumed; "Not you?" was undone when the sign-in screen was rebuilt; a three-line
      payment refusal moved the keys when typed over; the retired bit alone made someone a "helper"; a tile
      tapped right behind a weighed one was dropped silently. Also: a repayment with one payment method asks no
      "Paid with" question
- [x] CI on efb591d (run 37178161657, build 121): **251/251 on API 21 and 36**, tablet 9/9, release smoke, lint
- [x] Test build **v1.9.0** (pre-release; build 121, 1,509,416 bytes, release key — same certificate as 1.8.0,
      `0a67abec…d4b9`; SHA-256 of the APK `00791f65…bf30`, `mapping-1.9.0.txt` attached). The public download
      stays v1.8.0; the owner asked for the test build (2026-10-04)
- [ ] The owner's phone test (below)

### Needs real-device testing (1.9.0)

1. Install over 1.8.0 (Settings → App updates → Include test versions): data, staff, settings still there.
2. **Clear bill** as a cashier (PIN login on): Menu → Clear bill → Clear bill → empty, no PIN asked.
   Settings → Activity log (as the owner) lists it under the cashier.
3. **Cashier's screen:** signed in as a cashier, the totals bar shows Hold and PAY only; a selected line
   shows Remove, −, quantity, + (no More); Menu has Clear bill (with items), Held bills (when there are
   some), Receipts, Shift & cash, Lock, Manager PIN — no Open cash drawer, no Manage shop; Price check has
   no "Change price"; Shift & cash shows only Open or Close; a receipt has no Refund or Void.
4. **Manager PIN:** Menu → Manager PIN → the manager's PIN → "Manager: name ✕" in the top bar; Discount and
   More appear; give a discount; pay → the pill goes, the buttons hide again. Tap the pill → it ends at once.
5. **Unknown barcode** as a cashier: "Add product" asks for the manager's PIN first, then the form; saved,
   it is on the bill.
6. **Fast taps:** tap one product tile ten times quickly → ×10 on the tile and 10 on the bill, no tile
   flashing; tap three different tiles quickly → three lines in the order tapped. Double-tap a weighed
   tile → one weight dialog.
7. **Pay:** type 1 0 0 0 0 for a RM95 bill → the change shows and the keys do not move while typing.
8. **Sign-in:** lock the till → the PIN pad for the person who was signed in; "Not you?" → the list.
9. As the owner (or with PIN login off) everything is as before.

### 1.9.1 — the bill line's buttons on one line; nothing to scroll held sideways (the owner, 2026-10-04)

- [x] `LineControls`: Remove, −, quantity, +, More on one line under the selected item; Remove at the far
      end from + (wider gap); two lines only where one does not fit (no button is ever cut); − and + 56dp
- [x] `LineControlsTest` (instrumented): one line at 336, 411 and 600dp in that order; two lines at 230dp;
      a cashier's line (no More) one line at 300dp; a label-price line (Remove and More) fills the line;
      every button inside, apart, ≥ 56dp high
- [x] Local: `:core` 259 and `:app` 42 JVM tests pass, instrumented tests compile, lint 0 errors
- [x] **Held sideways** (the owner: keys and buttons hidden below the fold, "like on payment"): keypad
      dialogs side by side (`PadDialog`: amounts, quantities, weights, prices, discounts, stock count and
      adjust, manager approval and PIN dialogs), the payment in three columns (`dialog_payment_wide`; two
      notes and two methods to a row under 720dp), the sale's result with its buttons beside the change
      (`dialog_result_wide`), the sign-in with its keys beside the name; 50dp keys on a short sideways
      screen. `SidewaysLayoutTest`: each fits 290dp at 780 × 336 and 640 × 336dp, every button inside, ≥ 48dp
- [x] CI run 37179987006 (bfb1071) failed 3 tests per API: `LineControlsTest` found − and + measured at their
      icon's width (44dp) instead of 56dp — fixed (a child's fixed size is now kept)
- [x] CI on c0f3e33 (run 37180987053, build 124): **258/258 on API 21 and 36** (with `LineControlsTest` and
      `SidewaysLayoutTest`), tablet 9/9, release smoke, lint
- [x] Test build **v1.9.1** (pre-release; build 124, 1,514,780 bytes, release key — same certificate as 1.8.0;
      SHA-256 of the APK `b6fdd908…7e62`, `mapping-1.9.1.txt` attached). The public download stays v1.8.0

Phone check: tap a line on the bill → one row under it: Remove, −, the quantity, +, More (a cashier:
no More). Tap + quickly several times → only the count changes. Turn the phone (two panes) → the buttons
take two rows and none is cut. Large font (phone settings) → the same.
Held sideways: open the payment, a quantity (More → Quantity), a discount, the manager PIN and the sale's
result with the phone turned → everything shows at once, nothing to scroll; the sign-in too.
- [x] The owner asked to release it (2026-10-04): **v1.9.1 is the latest release** (public notes with What's new
      in English and Malay, covering 1.9.0 too; the website's Download link serves it, SHA-256 checked, and
      GitHub's latest release is v1.9.1); smoke baseline (`PREV_APK_URL`) v1.9.1. Shops on 1.8.0 are offered it
      inside the app within a day. No privacy change (nothing new stored, sent, asked for or connected to).
      The checks above were not run on a phone before the release: worth running on the shop's phone and
      tablet after the update. v1.9.0 stays a test build
- [x] Perf FULL on the released code (run 37182177887, 6e70f65): **PASS** on API 21, 29 and 36, every plan check
      ok (103/103 on API 21); API 21 sale commit p95 9.9 ms (150), report_month 74 ms (1,000), report_year
      174 ms (3,000), multi-word search p95 3.1 ms (50), scan to cart 0.26 ms; cold start median to a usable
      till 586 / 571 / 387 ms

## 1.10.0 — one selling screen for everyone; the payment in two clear steps (D-064)

The owner asked (2026-10-05): "make manager/owner main cashier UI same as normal cashier UI. clean, easy and
simple" and "make pay screen a lot more better and simple. currently is hard to understanding and looks weird".

- [x] **One selling screen:** the totals bar is Hold and Pay for everyone; the selected line is Remove, −,
      quantity, + for everyone ("More" is gone — the quantity button types a quantity or weight). Discounts
      and a line's price: **Menu → Discount** (the whole bill, the selected line's discount, its price; opens
      straight away when only one applies), for whoever may, or after a manager's PIN
- [x] **Payment, step 1 — how the customer pays:** "Total to pay" in big figures; Cash: the amount due and up
      to four notes (one tap pays) and "Other amount"; other ways to pay (one tap pays the rest); "Split
      payment"; Cancel at the top. A part payment shows what is paid, what is still to pay and the bill total
- [x] **Payment, step 2 — only when asked for:** Back, "To pay RM…", the cash the customer gave with the
      change as it is typed, the keypad, Done; for a split payment, "Pay this part with" and every method
- [x] Taps within 500 ms of a payment step appearing are ignored (a double tap on Pay would pay); keyboard
      digits on step 1 go to the cash amount; held sideways, both steps in columns
- [x] Tests: `SidewaysLayoutTest` measures both steps as `PaymentViews` builds them (sideways at 780 and
      640dp with six methods; upright on a 360dp phone), `LineControlsTest` without More, `ScreenshotsTest`
      adds the cash step (`pay-cash`)
- [x] Local: `:core` and `:app` JVM tests pass, the instrumented tests compile
- [x] CI run 37336660068 (94ae693): `SidewaysLayoutTest` failed — held sideways "Split payment" was cut (a
      wrap_content row sizes itself by its other columns: every column is now match_parent); upright the split
      step measured 540dp of 520 (its extra heading dropped)
- [x] CI run 37338392499 (9606f41): upright passes; held sideways a payment method in a short last row was
      squashed to 1px — **an old bug too**: since methods became editable (1.7.0), a fourth method upright, or
      Credit with a customer on the bill, could be invisible. Fillers now take the row's height
- [x] CI on 586ed9e (run 37340295469, build 131): **259/259 on API 21 and 36**, tablet 9/9, release smoke (upgrade
      from 1.9.1), lint; release APK **1,519,594 bytes** (1.9.1's build 124: 1,514,780 — about +5 KB). Screenshots
      (`pay`, `pay-cash`, `sell`; English and Malay; phone and tablet) reviewed: the owner's selling screen is the
      cashier's; both payment steps whole, nothing to scroll
- [x] Test build **v1.10.0** (pre-release, 2026-10-06; build 131, 1,519,594 bytes, release key; SHA-256 of the APK
      `160a4377…2c8d`, `mapping-1.10.0.txt` attached). The public download stays v1.9.1
- [ ] The owner's phone check (below) — on v1.10.1, which carries all of 1.10.0

Seen in passing (not changed): on Android 5 the keypad's ⌫ key shows an empty box (the system font has no
such character; the key still deletes, and a long press clears).

### Needs real-device testing (1.10.0)

1. **Owner's screen:** signed in as the owner (or PIN login off) → the totals bar shows Hold and Pay only; a
   selected line shows Remove, −, quantity, + — the same as a cashier's.
2. **Menu → Discount** (owner) with a line selected → "Discount on the whole bill", "Discount on <item>",
   "Change the price of <item>"; each works. With no line selected (tap the selected line again) → the bill
   discount opens at once. A cashier sees no Discount in the menu until Menu → Manager PIN.
3. **Pay, cash note:** RM23.45 bill → Pay → RM23.45, RM25, RM30, RM50, RM100, Other amount → tap RM50 → the
   result shows change RM26.55.
4. **Pay, other amount:** Pay → Other amount → type 6 1 6 0 for RM59.60 → "Change RM2.00" shows → Done.
   Back on the keypad step returns to step 1.
5. **Pay, card / e-wallet:** Pay → E-wallet / QR → paid at once.
6. **Split:** Pay → Split payment → type 2 0 0 0 → Card → step 1 says "Still to pay", "Paid: Card RM20.00" →
   pay the rest with a note. Cancel then asks before dropping the card part.
7. **Double tap on Pay** → the payment opens and nothing is paid.
8. Held sideways (phone and tablet): both steps show whole, nothing to scroll.

### 1.10.1 — Google Drive missing from the folder picker: a backup file to Drive instead

The owner (2026-10-06): an Android 10 tablet whose old Google Drive app cannot be updated shows Drive in the file
picker and the share sheet, but not in the folder picker, so the daily folder copy cannot go to Drive.

- [x] The backup file actions were already there (⋮ → Share a backup file, Save a backup file…, Restore from a
      file…; D-044). Now: the folder picker closed with no folder chosen (and none set) → "Google Drive not in the
      list?" with Share and Save as a file; with no folder and no sync the Backup screen says where to find them;
      a picked file that cannot be read (a Drive file offline) says so instead of "not a LekasPOS backup file",
      and "Checking…" shows while Drive downloads it; "Save a backup file…" (and CSV exports) retry with mode
      "w" when the app refuses "wt"; a share that failed half-way leaves no half file; a second tap on Share is
      ignored while the first runs. `BackupTest.aPickedFileThatCannotBeReadIsNotCalledNoBackup`
- [x] Local: JVM tests and lint pass, the instrumented tests compile; release APK 1,521,421 bytes
- [x] CI on 1bab076 (run 37410266682, build 134): **260/260 on API 21 and 36** (with the new `BackupTest`), tablet,
      release smoke (upgrade from 1.9.1), lint; release APK **1,522,590 bytes** (1.10.0's build 131: 1,519,594 — about
      +3 KB). Screenshots `backup` (English, Malay) show the Drive line under "No copy of this data off this phone"
- [x] Test build **v1.10.1** (pre-release, 2026-10-06; build 134, release key — same certificate; SHA-256 of the APK
      `152868b8…fded`, `mapping-1.10.1.txt` attached). The public download stays v1.9.1
- [ ] The owner's phone check (1.10.0's list above, and below)

Phone check (1.10.1), on the Android 10 tablet:

9. **Backup to Drive on the old tablet** (no folder set, sync off): Settings → Backup & restore → the top says
   "Google Drive not in the folder list?…". ⋮ → Daily copy to a folder → Back without choosing → "Google Drive
   not in the list?" → **Share** → Drive "Save to Drive" → the file `lekaspos-backup-<date>.lekasbak` is in
   Drive. Again with **Save as a file** → Drive in the picker's side menu → Save → "Backup file saved."
10. **Restore from Drive:** ⋮ → Restore from a file… → Drive → the file → "Checking…" → the restore question
   (Cancel leaves everything as it was). A photo or PDF → "This is not a LekasPOS backup file." Wi-Fi off and a
   Drive file not opened before → "This file could not be opened…", no crash.

## 1.11.0 — daily sales report to the owner's Google Drive (D-065)

The owner (2026-10-06): the tablet's old Google Drive app cannot be a folder, so the daily folder copy
cannot go to Drive; "make auto export sales report to google drive even on old device". Chosen with options:
the Daily sales report, one CSV per month, once a day, CSV.

- [x] Settings → **Daily sales report to Google Drive** (Settings permission or a manager's PIN): Turn on →
      Google's consent (scope `drive.file`: only files the app makes; sync's account when the shop syncs) →
      the first upload at once; then the account, the last upload, Upload now, Sign in again, Turn off. On and
      off are in the activity log
- [x] Every 3 hours while online (and soon after a start when a day waits) a finished day not written yet
      goes into `LekasPOS/<yyyy-mm> daily-sales.csv` at the top of My Drive (the "Daily sales" export: a row per
      day, empty days too), replacing that month's file; the first upload and Upload now also write the month
      before; the first 3 days of a month rewrite the month before (late-synced sales of other tills); missed
      months are caught up (`MonthFiles`, `:core`)
- [x] Straight to Google's Drive API (`DriveProvider.folder` / `putInFolder`), not the Drive app: any Drive app
      version works; needs Google Play services and internet. Per till (`meta drive_report.*`, LOCAL); a backup
      restored as a new till does not bring it along. Failures shown like sync's (offline, sign-in, Drive full)
- [x] Privacy policy (English and Malay, "Last updated 6 October 2026"), Play Data Safety draft, README (OAuth
      scopes), skill references, D-065
- [x] Tests: `MonthFilesTest` (7, JVM), `DailyReportUploadTest` (4, instrumented, a folder in memory)
- [x] Local: JVM tests and lint pass, instrumented tests compile; release APK 1,539,909 bytes (about +17 KB)
- [ ] **Owner's step before the phone check:** Google Cloud console → project `lekaspos` → Google Auth Platform
      → Data access → Add or remove scopes → tick `.../auth/drive.file` (or paste
      `https://www.googleapis.com/auth/drive.file` under "Manually add scopes") → Update → Save. Non-sensitive:
      no verification. It can take a few minutes to reach Google's servers
- [x] CI on 949babb (run 37416703176, build 136): **264/264 on API 21 and 36** (with `DailyReportUploadTest` 4/4),
      tablet, release smoke (upgrade from 1.9.1), lint, website (privacy pages); release APK **1,541,020 bytes**
      (1.10.1's build 134: 1,522,590 — about +18 KB)
- [x] Test build **v1.11.0** (pre-release, 2026-10-06; build 136, release key — same certificate; SHA-256 of the APK
      `f2027a8b…66c2`, `mapping-1.11.0.txt` attached). The public download stays v1.9.1
- [ ] The owner's phone check (below), after the owner's step above. Not yet tried against real Google Drive:
      CI has no Google account (the tests use a folder in memory)

### Needs real-device testing (1.11.0)

1. **Turn on (the old Android 10 tablet):** Settings → Daily sales report to Google Drive → Turn on → Google
   asks for the account (or names the shop's sync account) and "See, edit, create and delete only the
   specific Google Drive files you use with this app" → Allow → "Uploading to Google Drive…" → "In Google Drive
   → LekasPOS: 2026-09 daily-sales.csv, 2026-10 daily-sales.csv". The Settings row: "On · account · last
   upload: …".
2. **In Drive** (any phone or the web): My Drive → LekasPOS → the two files; open October in Google Sheets →
   a row for each day to yesterday, with the sales, totals and profit the Reports screen shows for those days.
3. **The next day:** without touching the app (online, any time after midnight; at most ~3 hours, or at once
   when the app is opened) → the October file has yesterday's row; still two files, not new copies.
4. **Upload now** with Wi-Fi off → "The last upload had no internet…", no crash; Wi-Fi on → Upload now works.
5. **Folder deleted:** delete LekasPOS in Drive → Upload now → a new LekasPOS folder with the files.
6. **Turn off** → asks first → the row says Off; the files stay in Drive. A cashier: a manager's PIN first.
7. **Bahasa Melayu:** the row, dialog and messages in Malay.

## 1.12.0 — pictures and colours, more items on the screen (D-066); staff on a shared till (D-067)

The owner (2026-10-08): "make it can put item image and color to make easier to identify", "make cashier main
UI wider to see lot item easier but still easy to select", and "same tills can be use different staff in same
day... please improve something to avoid staff fraud and avoid staff stealing (audit and think properly)".

- [x] **Pictures and colours (D-066):** product form → "On the selling screen": Take photo (camera app) / Choose
      picture / Remove picture, and 12 colours; categories get a colour too (their dot on the chip, the colour of
      their products without one). A coloured tile is filled with it, white text; the picture sits on top.
      Pictures are 240 px JPEGs (~13 KB) in the database (`product_image`), colours in `product_look` — schema
      **v9**, new sync entities (tills still on 1.11 keep them aside until updated), in backups like any data.
      `PictureCache` decodes off the main thread (≤ 10 MB). Sync segments close early at 2 M characters
- [x] **More items:** beside the bill, the bill is 36 % of the width (320–420dp) instead of 40 %; Settings →
      "Item size on the selling screen": Large (as before) / **Medium (default)** / Small — a 360dp phone shows 3
      a row (was 2), a 1280dp tablet 7 (was 5)
- [x] **Shared till (D-067)**, after a code audit of how cash can go missing:
      - every item taken off and every quantity lowered is in the activity log with its value; taken off or
        cleared **after the payment screen showed the total** is marked so (kept with the bill when it is parked
        or the app restarts, `cart.pay_shown`); a cleared bill names its items
      - stock written off (value at cost), a product's cost and barcodes are on record
      - the till locks after 5 idle minutes by default (a till set to "never" moved to 5 once); new choice
        "after each sale"
      - someone signing in during another person's shift: **count the drawer** (closes that shift, opens theirs
        with the counted cash, one transaction) or **Not now** (on record). Store setting, on by default
      - a manager's PIN for one bill lends only discounts, prices, customers and credit; voids, refunds, the
        drawer, cash in/out, copies, products and the back office ask for the PIN again
      - **Reports → Staff check**: per person — sales, taken off after the total, bills cleared, items taken off,
        voids, refunds, discounts, drawer without a sale, copies, cash out, stock written off, sold in another's
        shift, cash over/short of their shifts. The shift report gains "Checks"; the daily report in Drive gains
        the day's checks columns; held bills say whose they are
- [x] Tests: `:core` `StaffChecksTest` (checks, ranking, shift report section, tile colours); instrumented
      `StaffCheckTest` (removals and after-pay, the flag through hold/resume/restart, the manager's help, the
      handover and Not now, handover off, lock after each sale, the once-only lock default, write-offs),
      `PicturesTest`, `SyncMergeTest.aProductsColourAndPictureReachTheOtherTills` (backfill and merge),
      `SellingFixesTest.everyLineTakenOffIsAudited`, `DailyReportUploadTest` (new columns); `ScreenshotsTest` adds
      coloured tiles and a picture; migration v8 → v9 (snapshot `9.sql`)
- [x] Local: `:core` and `:app` JVM tests pass, instrumented tests compile, lint 0 errors
- [x] Privacy policy (English and Malay, 8 October 2026): product pictures, the camera's second use, the audit
      log's new entries, the daily report's checks columns; Play Data Safety draft (Photos)
- [x] CI on a52bde5 (run 37758272292): **276/276 on API 21 and 36**, tablet, release smoke (upgrade from 1.9.1),
      lint. The tablet screenshot showed a row stretched to its picture tile's height with empty tiles: tiles in
      a row with a picture now show their initials in its place (1627437); review fixes (b9ceecb): full-width
      category dialog and picture row, a manager's help also lends what it was asked for ("Add product"), a
      camera app's thumbnail when it ignores where to save, perf data with tile colours
- [x] CI on 1627437 (run 37760744551, build 139): **276/276 on API 21 and 36**, tablet, release smoke, lint; release
      APK **1,579,610 bytes** (1.11.0's build 136: 1,541,020 — about +39 KB). Tablet screenshot: 7 tiles a row
      (was 5), coloured tiles, a picture, initials in its row
- [x] Test build **v1.12.0** (pre-release, 2026-10-08; build 139, release key — same certificate `0a67abec…d4b9`;
      SHA-256 of the APK `37979785…deee`, `mapping-1.12.0.txt` attached). The public download stays v1.9.1
- [x] Perf QUICK (run 37762725913): **PASS** on API 21, 29 and 36; all 107 query plans ok on API 21 (with the new
      `products_by_ids` join, `audit_by_staff`, `audit_of_till`, `shift_over_short`); API 21 search p95 ≤ 2.9 ms,
      scan to cart 0.63 ms, sale commit 10.3 ms; cold start to a usable till about 0.6 s
- [x] The owner's phone check (below): tested with v1.13.0 — "i was test. all good" (2026-10-08)

### Needs real-device testing (1.12.0)

1. **Install over 1.11.0** (Settings → App updates → Include test versions): data, staff, settings still there.
   Settings → Staff: "The till locks after 5 min idle" if it was "never" before.
2. **Picture by camera:** Manage shop → Products → a product → "Take photo" → allow the camera → take it → the
   square preview shows → Save. On the selling screen (Items) the tile has the picture on top. Turn the phone
   while the camera app is open → the picture still arrives in the form.
3. **Picture from the gallery / Drive:** "Choose picture" → a photo → the preview; "Remove picture" → gone after
   Save. A PDF or a video → "This picture could not be read".
4. **Colours:** a product → Red → its tile is red with white text; a category → Blue → its chip has a blue dot and
   its products without a colour are blue. The "×2" badge stays readable on a coloured tile.
5. **Item size:** Settings → "Item size on the selling screen" → Small → more tiles a row at once (no restart);
   Large → as before. On the tablet held sideways the bill is narrower and the tiles take the rest.
6. **Other till (sync on):** the picture and colours appear on the other till after its next sync.
7. **Taken off after the total:** add 3 items → Pay → Cancel → remove one → Settings → Activity log: "taken off
   after the total was shown" with its value; Clear bill after Pay → the cleared bill names its items.
8. **Lock after each sale:** Settings → Staff → ⋮ → When the till locks → "after each sale" → pay a bill → close
   the change → the PIN pad. Turn it back.
9. **Handover:** shift open by the owner → lock → the cashier signs in → "Take over the till?" → Count the drawer
   → count → "Your shift has started". Shift & cash shows the cashier's shift with that float; the owner's shift
   report (Shifts) shows "Closed by" the cashier and the over/short. Again with "Not now" → not asked again in
   that shift. Store settings → "Count the drawer when the cashier changes" off → not asked.
10. **Manager PIN:** as a cashier, Menu → Manager PIN → a discount works without a second PIN; Open cash drawer
    and a receipt's Void ask for the PIN again.
11. **Staff check:** Reports → Today → Staff check → each person's lines (sales, cleared, taken off, drawer …).
    The shift report shows "Checks". The Drive daily report has the new columns (next upload).
12. **Bahasa Melayu:** the new screens, buttons and messages in Malay.

## 1.12.1 — the shift asked by itself; what stays in the drawer (D-068)

The owner (2026-10-08), after the open/close routine was explained: "ok improve something to make this never missing
and not make cashier confusing... please audit and do your best".

- [x] **Asked by itself** when someone starts using the till (sign-in, or the screen opening with PIN login off) and
      when the day changes on a till left on: no shift → "Start the shift" (count the drawer); yesterday's shift never
      closed → one count closes it and starts today's; another person's shift → the handover (1.12.0). Once per
      person and shift/day; never over a payment or a sale's change; "Not now" always possible
- [x] **Menu → Open shift / Close shift** directly ("Shift & cash" stays for whoever moves cash or sees reports)
- [x] **Close in three steps:** count all the cash → how much stays in the drawer (the shift's opening amount
      pre-filled) → "Counted / Leave in the drawer / Take out for the owner" with the note. A cashier is told what to
      take out and what to leave
- [x] **The next opening** comes pre-filled with what was left (OK keeps it); another amount → "Not what was left:
      Count again / Keep", recorded for the owner (activity log, staff check, the shift report's checks)
- [x] **Shifts on for shops with staff:** "Require an open shift" turns on once when a cashier or manager can sign in
      with a PIN (on record); the owner can turn it off and it stays off
- [x] Tests: `:core` `ShiftGuideTest`; instrumented `StaffCheckTest` (opening checked against what was left, yesterday's
      shift closed by today's first count, shifts on for staff, once)
- [x] Local: `:core` and `:app` JVM tests pass, instrumented tests compile, lint 0 errors
- [x] CI on 6ffe022 (run 37767975160, build 143): **279/279 on API 21 and 36**, tablet, release smoke, lint; release APK
      **1,589,160 bytes** (1.12.0's build 139: 1,579,610 — about +10 KB)
- [x] Test build **v1.12.1** (pre-release, 2026-10-08; build 143, release key — same certificate `0a67abec…d4b9`; SHA-256
      of the APK `6dedb828…847c`, `mapping-1.12.1.txt` attached). The public download stays v1.9.1
- [x] The owner's phone check (below, with 1.12.0's list): tested with v1.13.0 — "all good" (2026-10-08)

### Needs real-device testing (1.12.1)

1. **Shifts on:** with a cashier who has a PIN, after the update Settings → Store & receipt shows "Require an open
   shift to take payments" on; the activity log says why.
2. **Start of day:** no shift open → sign in → "Start the shift" with the keypad → count → OK. "Cancel" → Pay asks again.
3. **Close:** Menu → Close shift → count (e.g. RM850) → "Leave in the drawer" shows RM100 → OK → "Counted RM850, leave
   RM100, take out RM750" → Close. A cashier sees "Take out RM750 for the owner and leave RM100 in the drawer".
4. **Next opening:** Menu → Open shift → RM100 already there → OK. Type RM80 instead → "Not what was left" → Keep →
   Reports → Staff check shows "Opened with another amount than was left" −RM20.
5. **Forgot to close:** leave a shift open overnight → in the morning sign in (or wait a minute on the screen) →
   "Yesterday's shift is still open" → Count the drawer → today's shift starts; the old one is closed.
6. **Bahasa Melayu:** the new questions and steps in Malay.

## 1.13.0 — customer screen on a second display (D-069)

The owner (2026-10-08): "i decide to add customer screen feature... i have others screen but only can receive screen
cast... so can i show customer screen using screen cast (Tablet > other screen) but only showing customer screen on
other screen, not on tablet".

- [x] **Customer screen** on a second display (Android `Presentation`): Miracast ("Screen mirroring", "Smart View",
      "Wireless display"), HDMI or USB-C. The tablet keeps the selling screen. Google Home / Chromecast "Cast screen"
      only mirrors — said in Settings
- [x] Shows: a **welcome** with the shop's name (also while the till is locked); the **bill** — every line, the item
      just added large with its picture (or initials on its colour), a bill discount, **Total** / **Total to pay**
      while the payment is open, the number of items; after the sale **Thank you, Paid, Change** for 30 s or until the
      next item. In the receipt language; sized to the screen; works standing upright too
- [x] Stays on the other screen when the cashier opens another screen of the app (it never mirrors the back office);
      appears when a screen is connected, goes when it is removed
- [x] Settings → **Customer screen (second screen)**, per till: on (default) / off, whether a screen is connected now,
      "Connect a screen" (Android's cast settings)
- [x] Tests: `:core` `CustomerViewTest`; instrumented `CustomerScreenTest` on Android's simulated second display (the
      welcome, the bill, another screen opened over the selling screen); pictures `en-customer-welcome.png`,
      `en-customer-bill.png` with the screenshots
- [x] Local: JVM tests and lint pass, instrumented tests compile
- [x] CI on a27fa4f (run 37773750589, build 145): **280/280 on API 21 and 36** (with `CustomerScreenTest` on the simulated
      second display), tablet, release smoke, lint; release APK **1,603,588 bytes** (1.12.1's build 143: 1,589,160 — about
      +14 KB). Pictures of the customer screen (welcome, bill) reviewed
- [x] Test build **v1.13.0** (pre-release, 2026-10-08; build 145, release key — same certificate `0a67abec…d4b9`;
      SHA-256 of the APK `0d2d90b2…5f4b`, `mapping-1.13.0.txt` attached). The public download stays v1.9.1
- [x] The owner's check with the TV (below): "i was test. all good" (2026-10-08)

### Needs real-device testing (1.13.0)

1. **Connect the TV:** on the tablet, swipe down from the top → "Screen mirroring" / "Smart View" / "Cast" → pick the TV
   (or Settings → Customer screen → Connect a screen). With LekasPOS open, the TV shows the welcome with the shop's name;
   the tablet stays the selling screen. Settings → Customer screen says "showing on <the TV>".
2. **Sell:** scan or tap items → the TV shows each line, the last item large (with its picture if it has one), the total.
3. **Pay:** Pay → the TV says "Total to pay" → pay cash → "Thank you, Paid RM…, Change RM…" → back to the welcome.
4. **Other screens:** open Menu → Manage shop → Products or Reports → the TV keeps the customer screen (never the
   tablet's screen).
5. **Turn off:** Settings → Customer screen → Turn off → the TV mirrors the tablet again (Android's normal cast); turn on.
6. If the TV only copies the tablet: the cast is Google Home's "Cast screen" — use Screen mirroring / Smart View, or a
   cable. Tell us the tablet's and the TV's (or dongle's) make.
- [x] The owner asked to release it (2026-10-08, "yup"): **v1.13.0 is the latest release** (public notes with What's new in
      English and Malay, covering 1.10.0 to 1.13.0; the website's Download link serves it, SHA-256 checked, and GitHub's
      latest release is v1.13.0); smoke baseline (`PREV_APK_URL`) v1.13.0. Shops on 1.6.1 or later are offered it inside
      the app within a day. Privacy policy already updated for 1.12.0 (8 October 2026); 1.13.0 sends nothing new.
      Open: the Google Cloud `drive.file` scope step of 1.11.0 (the daily report to Drive) was never confirmed
- [x] Perf FULL on the released code (run 37778514740, d3c583c): **PASS** on API 21, 29 and 36 (50,600 products, 1,003,158 sale
      lines), all 107 query plans ok; API 21 sale commit p95 19.1 ms (150), multi-word search p95 4.8 ms (50), scan to cart
      0.58 ms, report_month 164 ms (1,000), report_year 224 ms (3,000); cold start to a usable till about 0.7 s

---
name: lekaspos
description: Project rules for building LekasPOS, the offline-first Android POS in this repo. Load before any work here — architecture rules, coding conventions, performance budget, money handling, database schema conventions, sync design, and the definition-of-done checklist. Also covers how phases are delivered.
---

# LekasPOS project skill

Read this whole file before working. Load a reference file when the task touches its area:

| Area | Reference |
|---|---|
| Modules, layers, threading, startup, UI rules | `references/architecture.md` |
| Tables, columns, indexes, migrations, SQLite 3.8.4 limits | `references/database.md` |
| Money, quantities, tax, discounts, rounding | `references/money.md` |
| Google Drive sync, IDs, HLC, merge rules, backups | `references/sync.md` |
| Performance budget, perf suite, how to measure | `references/performance.md` |

Human-facing docs live in `docs/` (`PHASES.md`, `DECISIONS.md`, `BUILD.md`, README, privacy).
When a decision changes: update the reference file here **and** add an entry to
`docs/DECISIONS.md` (date, decision, why, alternatives rejected).

## 1. How work is delivered

- Seven phases, in order (see `docs/PHASES.md`). Finish and verify one before the next.
- End of every phase: build the release APK and report its size, run every test suite,
  run the perf suite, list what still needs real-hardware testing, update `docs/PHASES.md`,
  then **stop and wait** for the user's real-device feedback.
- Heavy verification runs in GitHub Actions (`ci.yml`: tests on API 21 + API 36 emulators on
  every push; `perf.yml`: QUICK/FULL perf + cold start on demand). The local machine is weak:
  JVM tests and single builds locally, emulators only when unavoidable, never two at once.
- Every CI release APK is signed with the one **release** key (GitHub secrets `TEST_*`, named in
  the tester days; D-051) — it must never change. Version = `versionName` (e.g. `1.0.0`) + the CI
  run number as `versionCode` ("build N"). Tester builds: GitHub **pre-releases**. Public
  releases: a normal "latest" GitHub release with assets `LekasPOS.apk` (the website's Download
  link) and `LekasPOS-<version>.apk` — docs/BUILD.md "Release checklist". Not on Google Play yet.
- Before publishing a tester build, the CI **release smoke** jobs must pass: the R8 release APK
  installed over the last tester build and fresh, selling screen open 20 s, no crash
  (`scripts/ci/release-smoke.sh`; update `PREV_APK_URL` in `ci.yml` after each release). The
  instrumented tests run only the debug build — R8 problems show up only here.
  Never hand testers debug-signed builds; the Play **upload** key is separate (docs/BUILD.md).
- Work autonomously inside a phase. Ask the user only for decisions that are genuinely
  theirs (business rules, legal/tax facts, accounts, money).
- Schema changes after Phase 1 shipped to the tester are real migrations (bump DB version),
  never edits of an older version's DDL.

## 2. Architecture rules (summary)

- Two Gradle modules: `:core` (pure Kotlin/JVM, all business rules, no Android imports) and
  `:app` (Android: SQLite, UI, Bluetooth, Drive, WorkManager). Business math never lives in
  `:app`.
- The local SQLite DB is the only source of truth. The app is fully usable offline and
  without a Google account. Selling never waits on network, Bluetooth or sync.
- Raw framework SQLite (`android.database.sqlite`) behind `Db`: one writer thread for all
  writes, a small read pool, WAL, `synchronous=FULL`. SQL lives only in `com.lekaspos.data`.
- Manual DI via `AppGraph` (lazy singletons). App-scoped state holders own screen state
  (e.g. the current cart), so activity recreation is cheap and crash restart restores the bill.
- One Activity per major screen, platform `Theme.Material` styles, custom dialogs.
  Activities declare `configChanges="keyboard|keyboardHidden|navigation"` so Bluetooth
  HID scanners connecting/disconnecting never recreate the screen; back-office screens also
  handle rotation themselves (D-054 — see architecture.md §4).
- Coroutines for async; every scope is tied to a lifecycle and cancelled. No `GlobalScope`.
- Sync, backup and archive sit behind the `SyncProvider` interface; nothing outside
  `com.lekaspos.sync` knows about Google Drive.

## 3. Coding conventions

- Kotlin official style, 4-space indent, ≤ 120 columns. Package root `com.lekaspos`.
- `Money` (minor units) and `Qty` (milli-units) value classes in domain code; raw `Long`
  only at the SQL boundary. Parse and format money with `:core` helpers only.
- SQL: constant strings with `?` bind args only — never concatenate values into SQL. Hot-path
  writes reuse compiled `SQLiteStatement`s on the writer thread. Every cursor is closed (`use`).
- No `!!` in production code. No `GlobalScope`, no `runBlocking` on the main thread.
- No `java.time` (minSdk 21, no desugaring): epoch millis + `java.util.Calendar/TimeZone`
  through `:core` date helpers.
- Every user-visible string is a resource with English (`values/`) and Bahasa Melayu
  (`values-ms/`) entries. Currency and number formatting use store settings, not device locale.
- Touch targets ≥ 48dp everywhere, ≥ 56dp on the selling screen. Layouts must work on a
  5-inch phone and a 10-inch tablet, portrait and landscape.
- Logging via `com.lekaspos.util.Log` only; never log PINs, tokens or customer data.
  Release builds strip debug/verbose logs with R8. `Log.e` is for real failures: each one becomes
  an error report to the developer when the shop allows them (D-057); expected outcomes (offline,
  printer off, a rule refusing an action) use `Log.w`.
- Tests: JVM unit tests for all `:core` rules; instrumented tests for SQL (they must pass on
  an API 21 image — the SQLite 3.8.4 check). Test method names are camelCase (no backticks:
  DEX rejects spaces in method names below API 30).

## 4. Performance budget (hard limits — details in `references/performance.md`)

| Metric | Budget |
|---|---|
| Release APK | < 8 MB (target < 5 MB) |
| Cold start → selling screen usable (`reportFullyDrawn`) | < 2 s on 1 GB RAM / API 21 |
| Scan → item shown in cart | < 50 ms p95 (DB lookup ≤ 10 ms p95) |
| Product search, 50,000 products | < 100 ms p95 (DB part ≤ 50 ms p95) |
| Complete-sale transaction (5 lines, fsync) | ≤ 150 ms p95 |
| Any list page (50 rows) at 1,000,000+ sale lines | ≤ 50 ms p95 |
| Daily / monthly report at 1,000,000+ sale lines | ≤ 300 ms / ≤ 1 s |
| Main thread | zero disk/network/BT (`Db` throws on main; debug StrictMode logs the rest) |
| Memory | steady heap ≤ 48 MB, no growth over a 1,000-sale soak |

Rules that keep these true: indexed access paths only (verify with `EXPLAIN QUERY PLAN` in the
perf suite), keyset pagination (never OFFSET on big tables), reports read summary tables,
streaming I/O for files (never whole files or whole tables in memory).

## 5. Money rules (summary — details in `references/money.md`)

- Amounts: `Long` minor units of the store currency (MYR: 1 = 1 sen). Qty: milli-units.
  Rates and percentage discounts: basis points.
- Rounding: half-up (away from zero) to minor units, applied at defined points only: line
  gross, percentage discounts, tax per rate group, cash rounding. Allocations (bill discount,
  tax to lines) use largest remainder so parts always sum exactly to the whole.
- Tax: per-product tax rate; store setting says whether prices include tax. Tax is computed
  per rate group on the sale, then allocated to lines.
- Cash rounding (MYR: nearest 5 sen) applies only to the part settled in cash and is stored
  as its own `rounding` amount. Card/e-wallet pay the exact amount.
- User input is parsed from digits/strings, never via `toDouble()`.

## 6. Database conventions (summary — details in `references/database.md`)

- Tables: singular `snake_case`. Money columns `INTEGER` minor units; quantity columns
  `INTEGER` milli; timestamps `*_at` epoch ms UTC; booleans `INTEGER` 0/1; enums stable
  `INTEGER` codes defined in `:core` (never renumber).
- Every table has a sync class: **LWW** (catalog/settings: `ver_hlc`, `ver_dev`, `fver`,
  `deleted` tombstone), **EVENT** (sales, payments, movements, audit: append-only, `hlc`),
  **LOCAL** (cart, outbox, meta — never synced) or **DERIVED** (stock levels, summaries —
  rebuildable from events). Declared in `references/database.md` for every table.
- Synced rows use app-generated 63-bit IDs: `(device_no shl 41) or seq`. IDs with
  `device_no = 0` are seed rows identical on every device.
- Cross-aggregate references are not foreign keys (sync may deliver them out of order);
  intra-aggregate children (sale → lines/payments) use `ON DELETE CASCADE`.
- Derived columns must be recomputable from events regardless of arrival order.
- Target SQLite 3.8.4. Forbidden: UPSERT, RETURNING, window functions, row values,
  expression indexes, json1, FTS5, generated columns, `IIF`, `FILTER`, `NULLS FIRST/LAST`,
  `ALTER TABLE RENAME/DROP COLUMN`, `unixepoch()`, `wal_checkpoint(TRUNCATE)`.
- Partial index predicates: only `deleted = 0` or a lone `col IS NOT NULL`; no correlated
  subqueries against large tables, and no partial-index lookups inside any subquery (3.8 ignores
  the index there → a scan per call); repeat the predicate in the query. Register hot and
  maintenance SQL for `QueryPlans` checks.
- Every schema change: bump `DB_VERSION`, add a `Migration`, commit the new schema snapshot
  (`app/src/androidTest/assets/schemas/<v>.sql`), keep the migration test green.

## 7. Sync design (summary — details in `references/sync.md`)

- Each device appends its own changes (as events) to `outbox` in the same transaction as
  the change, seals them into immutable, numbered segment files and uploads them. A device
  never writes or overwrites another device's files.
- Other devices download new segments and apply them idempotently: EVENT rows `INSERT OR
  IGNORE` by ID (derived data updated only if the row was new); LWW rows merge per field by
  `(hlc, device_no)`. Stock = last count + movements after it, ordered by HLC.
- Hybrid logical clock (HLC) timestamps make ordering robust to clock skew.
- Periodic compacted snapshots let a new device restore quickly. Drive scope:
  `drive.appdata` only. Sign-in via Google Identity Services `AuthorizationClient`.
- WorkManager runs sync with backoff; uploads/downloads resume; status is always visible.
- Local automatic backups and manual backup-file export/import work without Play Services.

## 8. Definition of done (check every item before calling a feature or phase done)

- [ ] Behaviour matches the spec; edge cases handled (empty, huge, offline, crash mid-way).
- [ ] No main-thread disk/network/BT (debug StrictMode run clean); no leaked scopes/listeners.
- [ ] New `:core` logic has unit tests; new SQL has instrumented tests that pass on API 21.
- [ ] Hot queries use indexes (`EXPLAIN QUERY PLAN` check in the perf suite); lists paginate.
- [ ] Schema change → version bump + migration + schema snapshot + migration test.
- [ ] Synced write → outbox event in same transaction + merge rule + merge test.
- [ ] Sensitive action → permission check + audit log entry.
- [ ] Money handled only via `:core` helpers; totals tested to the sen.
- [ ] All strings in resources, English + Bahasa Melayu; touch targets ≥ 48dp (56dp selling).
- [ ] Portrait + landscape, 5-inch phone + 10-inch tablet layouts checked.
- [ ] Lint has no errors; release build (R8) installs and runs the changed flow.
- [ ] Perf suite within budget; release APK size recorded (< 8 MB).
- [ ] Privacy policy / Data Safety answers still true.
- [ ] `docs/PHASES.md` updated; skill + `docs/DECISIONS.md` updated if a decision changed.
- [ ] Phase end only: tests + APK size + hardware test list reported, then wait for feedback.

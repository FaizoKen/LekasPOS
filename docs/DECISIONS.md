# Decision log

Newest last. Each entry: decision, why, alternatives rejected. When a decision changes, add a
new entry that supersedes the old one (do not rewrite history) and update the skill.

### D-001 — Raw framework SQLite, not Room (2026-09-28)
Why: hand-tuned SQL everywhere it matters (FTS4 with prefix indexes, partial indexes, compiled
statements on the writer thread, per-field LWW merges, summary tables, keyset pagination); no
annotation processing (faster builds, no KSP/Kotlin version coupling); full control of pragmas.
SQL errors are caught by instrumented tests that run on API 21 and API 36.
Rejected: Room (compile-time checks, but fights the custom sync/merge layer; WorkManager still
brings Room's runtime for its own DB, so there is no size win either way).

### D-002 — Platform `Theme.Material` + androidx.core + RecyclerView; no AppCompat/Material/Fragments/Compose
Why: smallest APK and fastest cold start on 1 GB devices; API 21 has Material theme, vector
drawables, ripples natively. Per-app language is done by wrapping the base context.
Rejected: AppCompat + Material Components (~1–1.5 MB, slower inflation), Compose (spec).

### D-003 — Two modules: `:core` (pure Kotlin/JVM) and `:app`
Why: business rules (money, tax, pricing, merge, ESC/POS, CSV) are testable on the JVM in
seconds and cannot accidentally depend on Android. Rejected: single module (weaker boundary),
many feature modules (build complexity, no benefit at this size).

### D-004 — Toolchain: AGP 9.4.1, Gradle 9.8.0, Kotlin 2.4.20, JDK 21; compileSdk/targetSdk 36; minSdk 21
Why: latest stable tools; Google Play requires targetSdk 36 for new apps and updates from
2026-08-31. Consequences of targeting 36: edge-to-edge enforced, predictive back, no
orientation locks on large screens (handled in architecture rules).

### D-005 — Library pins that keep minSdk 21
Checked from the AAR manifests: WorkManager 2.10.5 (2.11 → minSdk 23, 2.12 → 24),
androidx.core 1.17.0 (1.18+ → 23), RecyclerView 1.4.0 (21), play-services-auth 21.4.0
(21.5+ → 23, 22.0 → 24), androidx.test runner 1.7.0 / ext-junit 1.3.0 / core 1.7.0 (21).
The manifest merger fails the build if a transitive upgrade needs more than 21.

### D-006 — Globally unique 63-bit IDs: `(device_no shl 41) or seq`
Why: `INTEGER PRIMARY KEY` speed and compactness, no ID translation during sync, origin device
visible in every ID, no coordination server. `device_no` = 22-bit random, checked against
the store's device list on join. Rejected: UUID strings (big indexes), random 64-bit
(undetectable collisions), local IDs + mapping tables (complex, bug-prone).

### D-007 — Hybrid logical clock + per-field last-writer-wins for master data
Why: the spec asks for LWW by timestamp + device; HLC keeps causality under clock skew and
per-field LWW keeps concurrent edits of different fields (price on one device, name on
another). Rejected: row-level LWW (loses edits), wall-clock only (skew).

### D-008 — Stock = last count + movements after it; sale lines are the sale movements
Why: counts are absolute facts ordered by HLC, so offline sales made before a count are not
subtracted twice. Sale lines carry `stock_qty` + `hlc` instead of duplicating 1M+ rows into
`stock_movement` (~80 MB saved at 1M lines). `stock_level` is a transactional cache,
rebuildable from events.

### D-009 — Reports read per-day summary tables
Why: monthly/yearly reports stay fast at 1M+ lines on slow storage and keep working after old
sales are archived. Summaries are updated in the sale transaction and rebuildable.

### D-010 — `PRAGMA synchronous=FULL` in WAL mode
Why: "no data loss on power loss" — NORMAL can lose the last commits on power cut. Cost: one
fsync per commit on the writer thread, which never blocks the UI.

### D-011 — FTS4 `tokenize=simple` + normalization in Kotlin
Why: FTS5 and `unicode61` are not guaranteed on Android 5.0's SQLite; our normalizer strips
accents, lowercases, splits CJK into single characters, so search is identical on all API
levels. One-letter searches use the `name_key` index instead of FTS.

### D-012 — Android Auto Backup disabled
Why: restoring the DB onto a second phone would clone `device_no` and corrupt multi-device
sync; the app has its own local + Drive backups.

### D-013 — No `java.time`, no core-library desugaring
Why: minSdk 21 lacks `java.time`; desugaring adds size and startup cost. Epoch millis +
`java.util.Calendar/TimeZone` via `:core` helpers are enough.

### D-014 — Sync via Google Drive `appDataFolder`, GIS `AuthorizationClient`, one Google account per store
Why: `drive.appdata` is a non-sensitive scope (no paid security assessment); all devices of a
store share the hidden app folder by signing in with the store's account. REST via
`HttpURLConnection` (no Google API client library).

### D-015 — Snapshots and backups are SQLite files built by streaming rows
Why: fastest restore for a new device (download, unzip, open); built from a WAL read
transaction so selling continues. `VACUUM INTO` (3.27) and `ATTACH` (disables WAL on Android)
are not usable.
(Amended in Phase 6: backups are file copies under the writer, see D-044; new tills join by
publishing their rows, not from a snapshot, see D-045.)

### D-016 — Outbox is written only while sync is enabled
Why: otherwise it grows forever on single-device shops. Enabling sync starts with a full
snapshot upload, which carries every row's version information.
(Phase 6: "full snapshot upload" became the backfill of D-045 — every row published as an event
with its version information.)

### D-017 — Receipt numbers per device and per document kind
Why: unique without coordination; format `{prefix}{kind}{seq:06}` with a per-device prefix.

### D-018 — Perf suite ships inside the app (Diagnostics screen)
Why: real low-end devices are the verdict; testers can run the same suite on the release
build and send numbers. It uses a separate `perf.db` that can be deleted from the screen.

### D-019 — Refunds are negative sale documents; voids are events
Why: refunds keep their own numbered document (credit-note style) and naturally net out in
reports; voids remove a mistaken sale from reports and stock without deleting anything.

### D-021 — Partial indexes restricted to two forms; no correlated subqueries on large tables (2026-09-28)
Found by the first FULL perf run on the API 21 emulator (SQLite 3.8.6): the refund rebuild's
correlated subquery ignored the partial index `sale_ref` and scanned `sale` once per sale
(≈62 billion row visits at 250k sales), and `product_sku (WHERE deleted = 0 AND sku IS NOT
NULL)` was never used at all. Rule: predicates are only `deleted = 0` or a lone
`col IS NOT NULL`; rebuilds aggregate with GROUP BY instead of correlated subqueries;
`QueryPlans` now also checks maintenance SQL for correlated scans. Schema v1 was adjusted
before it shipped to any tester.

### D-022 — Open source (GPL-3.0), public repo `FaizoKen/LekasPOS`, heavy verification on GitHub Actions (2026-09-29)
User decision. The development laptop (≈7 GB RAM, small disk) could not run the API 36 emulator
and lost the API 21 one to memory pressure, so builds, emulator tests (API 21 + 36) and perf runs
moved to GitHub-hosted Linux runners (KVM, free for public repositories): `ci.yml` on every push,
`perf.yml` on demand. Local emulator images were deleted; `scripts/setup-toolchain.ps1
-WithEmulator` + `scripts/create-avds.ps1` recreate them if ever needed.

### D-023 — Shared test signing key for CI builds (2026-09-29)
User request. Tester builds are signed with one dedicated test key (RSA 3072, stored as GitHub
secrets and on the maintainer's laptop) and carry CI-run-based versionCodes, so each new build
installs as an update and keeps the testers' data. Kept separate from the future Play upload
key, which only the app owner holds. Fork PRs get no secrets and stay debug-signed.

### D-024 — Phase 2 starts on stated assumptions (2026-09-29)
User decision ("start phase 2 with your assumptions"): Malaysian tax/receipt defaults (no tax
at the till unless rates are configured; prices include tax; 5-sen cash rounding; receipt shows
store name, address, BRN, SST no. and TIN when set; optional e-invoice request QR, off by default;
consolidated monthly e-invoice export in Phase 5), application ID `com.lekaspos.app`, customers &
credit in Phase 4, and generic ESC/POS printing (58/80 mm, Bluetooth SPP) with HID and SPP
scanners until the user names hardware models.

### D-025 — Camera scanning: ZXing core 3.3.3 + platform Camera1 API
Why: fits the size budget (~150–250 KB after R8, formats limited to EAN/UPC/Code128/Code39/QR)
and needs no CameraX (~1 MB). ZXing 3.4+ uses Java 8 library APIs missing below API 24, so the
3.3 line is pinned; an instrumented test runs decode/encode on the API 21 image. Camera1 is
deprecated but works on every API level we support. Apache-2.0 → GPL-3.0 compatible.

### D-026 — Printing: receipt model in :core, persistent queue, reconnecting SPP transport
A receipt is laid out once as elements (`:core` `receipt`), then encoded either as ESC/POS text
(fast; Latin code pages; GB18030 for Chinese-capable printers) or as a raster image (any script).
Jobs live in `print_job` (survive disconnects and restarts); a single printer thread reconnects
with backoff and prints in order. The cash drawer is kicked through the printer (`ESC p`), inside
the sale receipt job for cash sales; manual opens are permission-checked and audited.

### D-027 — Bluetooth: paired devices only, pairing in Android settings
Printers and SPP scanners are chosen from the phone's paired devices, which needs only
`BLUETOOTH_CONNECT` (runtime, API 31+) or the install-time `BLUETOOTH` permission (API ≤ 30) and
no location permission on any version. Pairing itself happens in Android's Bluetooth settings
(the app links there), as printer manuals already describe. Rejected for now: in-app discovery,
which would need `BLUETOOTH_SCAN` on API 31+ and `ACCESS_FINE_LOCATION` on API 23–30 for little gain.

### D-028 — Permissions stubbed until Phase 4, audit entries written from Phase 2
All sensitive actions go through `PermissionGate` (allows the single owner session for now) and
write `audit_log` entries, so Phase 4 only adds PIN login, roles and manager override.
(Done in Phase 4: see D-037.)

### D-029 — The open bill: memory first, one ordered writer, explicit local IDs
`CartSession` (app-scoped) changes the bill in memory, publishes it at once and queues the
database write; one background writer commits queued changes in order, batching bursts into one
transaction. Cart and cart-line IDs are handed out in memory (seeded from `MAX(id)`), so a scan
never waits for an insert. Checkout waits for the queue, then commits the sale, its print jobs
and the deletion of the bill in one transaction. Rejected: writing synchronously before showing
the line (an fsync per scan), Room/LiveData.

### D-030 — Hardware settings are per device, in `meta`; store settings are synced
Printer, paper, drawer, SPP scanner and camera settings belong to one till and live in the LOCAL
`meta` table (`dev.*` keys). Store name, receipt text, tax and rounding live in the LWW `setting`
table, one register per key, and will sync. Rejected: SharedPreferences (a second storage with
its own main-thread disk reads).

### D-031 — Cash-drawer pulses are their own print jobs, and expire
A cash sale enqueues a DRAWER job before its RECEIPT job (same transaction). The printer thread
skips a drawer pulse that is more than 2 minutes old, so a printer that comes back online later
never pops the drawer open unexpectedly; receipts still print. Manual opens are audited.

### D-032 — No schema change in Phase 2 (DB stays at version 1)
Everything Phase 2 needs fits schema v1. The default owner staff row (id 1, a seed row) is created
with `INSERT OR IGNORE` when the database opens, so Phase 1 installs get it without a migration.

### D-033 — Product cost = moving weighted average, updated on receiving (2026-09-29)
Each delivery line moves the product's cost to (on-hand qty × cost + line amount) / (on-hand +
received), rounded once; with nothing (or less than nothing) on hand the received cost is used.
The new cost is an ordinary LWW edit of `product.cost`, so it syncs like any edit and importers
never recompute averages. Why: profit reports stay right when purchase prices move, with no
extra tables. Rejected: last purchase cost (swings profit on every price change), FIFO cost
layers (complex, awkward to merge across devices).

### D-034 — Schema v2 (Phase 3), the first real migration
`count_session` (LWW) groups counts; `stock_count` gains `expected` and `unit_cost` (what the app
had and the cost at count time, for variance reports); indexes `stock_count_session` (plain, not
partial: session lists count rows in a correlated subquery, see D-021) and `stock_movement_hlc`
(the stock-changes log). Migration 1→2 uses `ALTER TABLE … ADD COLUMN` (fine on SQLite 3.8) and
`CREATE TABLE/INDEX`; `MigrationTest` checks it lands on exactly the fresh v2 schema.

### D-035 — Stock counts apply as they are entered
A counted quantity becomes the product's stock at once (a `stock_count` event); the session only
groups counts and is closed at the end. So the shop keeps selling while counting: a sale after a
product was counted is subtracted from the count. Rejected: collecting counts in a draft and
applying them all at "finish" — sales made in between would be wiped out.

### D-036 — Stock work sync events
A delivery syncs as one PURCHASE event (purchase + lines); its RECEIVE movements carry the line
ids, so an importer regenerates them idempotently. Adjustments and opening stock sync as
STOCK_MOVE, counts as STOCK_COUNT, count sessions and suppliers as LWW rows. Stock adjustments
need MANAGE_STOCK; the movement row (with staff and reason) is their record, so no separate
audit entry. The delivery being typed is a LOCAL draft (`meta` key `draft.receive`).

### D-037 — Staff PIN login, roles and manager approval (Phase 4, 2026-09-30)
PIN login is off until the owner sets a PIN: a one-person shop keeps working exactly as before.
The first PIN must be an owner's, and while anyone can sign in at least one active owner must
be able to — so the store can never lock itself out of staff management. PINs are 4–6 digits,
stored as one salted PBKDF2-HMAC-SHA256 record (`p2:iterations:salt:hash`, 4,000 iterations,
built on `Mac` because `PBKDF2WithHmacSHA256` needs Android 8) in the single LWW field
`pin_hash`, so a PIN change syncs atomically and works on every till. A short PIN cannot resist
offline guessing whatever the hash; the hash keeps PINs out of plain sight, and the till
throttles guessing: 5 wrong PINs, then waits of 30 s doubling to 15 min (per device, in `meta`).
The owner gets a recovery code (12 characters, shown once, stored hashed in `setting`) — the
only way back in without a server. Roles are permission bitmasks (17 permissions, owner role
= all; seed Manager/Cashier defaults). Sensitive actions take a manager's PIN approval: once
for one action, or held by a screen until it closes; `audit_log.approved_by` records it. The
signed-in cashier survives a restart; the till locks on demand or after an idle time set per
device. Rejected: PIN-only login without choosing a name (needs unique PINs and checking every
staff hash), unhashed PINs, lockout per staff member (5 free guesses per person).

### D-038 — Shifts and cash: expected cash is recomputed from shift-tagged events
A shift belongs to one till (LWW row edited only by that till). Sales, refunds, voids, cash
movements and credit repayments made while it is open carry its id; expected cash = float +
cash taken − cash refunds − cash of documents voided in this shift + cash in − cash out − drops
+ credit repaid in cash (`:core` `ShiftCash`). A void belongs to the shift it happens in, so an
earlier shift's report never changes. Closing stores counted and expected cash and audits the
difference; staff without SHIFT_REPORT close "blind". Shifts are optional unless the store
switches on "require an open shift to take payments". Rejected: running totals on the shift
row (conflict-prone under sync, can drift), a shift per staff member (the drawer is per till).

### D-039 — Customers and credit ("pay later") as an optional feature
Off by default (store setting). A customer can be attached to the bill; paying with "Customer
credit" writes a CHARGE credit entry in the sale transaction and needs CREDIT_SALE; going over
the customer's limit (0 = no limit) needs a manager's CREDIT_LIMIT approval, re-checked inside
the transaction. Refunds to credit and voids of credit sales add reversing (negative) CHARGE
entries; repayments are PAYMENT entries with a method (cash repayments count in the shift's
drawer); ADJUST corrects a balance (audited). `customer_balance` is DERIVED. Credit entries sync
as their own CREDIT events (the SALE importer does not derive credit). Receipts show the
customer's name. Rejected: storing the balance on the customer row (LWW would lose concurrent
charges from two tills).

### D-040 — Schema v3 (Phase 4)
`credit_entry.shift_id` (added last with `ALTER TABLE … ADD COLUMN`), partial indexes
`credit_entry_shift` and `sale_void_shift` (`shift_id IS NOT NULL`, usable with `shift_id = ?` on
SQLite 3.8), and the seed Manager/Cashier roles get their default permissions when they were
never edited (`ver_hlc = 0`). Every other Phase 4 table already existed in v1.

### D-041 — CSV files: UTF-8 with BOM, ISO dates, English headers, shared or saved (Phase 5)
Exports are RFC 4180 CSV (CRLF, quotes doubled), UTF-8 with a byte-order mark so Excel shows
Malay and Chinese text, dates as yyyy-mm-dd and amounts as plain decimals ("12.50"), with fixed
English column names so an accountant's spreadsheet or another tool can rely on them. Files are
streamed page by page (constant memory) and either shared from the app cache through
FileProvider (WhatsApp, e-mail) or saved where the user picks (Storage Access Framework:
Downloads, Drive, USB) — no storage permission. Imports come from the system file picker; the
reader accepts comma, semicolon or tab, any line ending and quoted line breaks, and falls back
to Windows-1252 when a file is not valid UTF-8 (Excel's "CSV" on Windows). The receipt list
export (receipt no, date, time, cashier, customer and TIN, net, tax, total, payments) is the
source for LHDN's monthly consolidated e-invoice until the compliance question is answered.
Rejected: XLSX (a large library), a storage permission, localized headers (break re-imports).

### D-042 — Product CSV import: preview, then import in chunks; empty cells change nothing
The same columns export and import, so a catalogue can be edited in a spreadsheet and brought
back; headers match loosely in English or Malay and only name and price are required. The file
is read twice: a preview validates every row (money, quantities, sold by, yes/no, barcodes, tax
by name or percentage, duplicate barcodes in the file, barcodes owned by another product) and
shows the counts and the first 200 problems by line; then the import runs in the app scope in
transactions of 200 rows, skipping rows with problems. A row updates the product owning one of
its barcodes, else the one with its SKU, else creates a product. Empty cells leave a product's
value unchanged ("none" removes a tax); new categories are created by name. The stock column is
the opening stock of new products; existing products' stock changes only when the user asks,
as a stock count. Updates are LWW edits (sync like hand edits); one audit entry records the
import. Rejected: matching by name (too many false matches), all-or-nothing imports (one bad
line would block thousands), deleting products missing from the file.

### D-043 — Per-month product totals; ranges read whole months + loose days
`sum_month_product` (DERIVED, key month = yyyymm and product) is maintained with the per-day
table in the sale transaction and rebuilt from it (SQLite's strftime on epoch days gives the
same months as `:core`). A report range is split into loose days at both ends (per-day table)
and whole months in between (per-month table), so a year of best sellers reads about 12 rows
per product instead of 365. Reports need the new REPORTS permission (profit and cost). Slow
movers = products with stock that did not sell in the period (one `NOT IN` over the sold ids);
stock value = on-hand quantity × current average cost. Schema v4 migrates the per-month table
from existing per-day rows.

### D-044 — Backups are consistent file copies; restores are staged and applied at start (Phase 6, 2026-09-30)
A backup (`.lekasbak` = ZIP of `backup.json` + the database file) is made on the writer thread:
`wal_checkpoint(FULL)`, then the main file (and any WAL left) is copied while writes wait
(reads and the UI keep going; a sale waits for the copy, not the other way round). Automatic: daily via
WorkManager (last 7 kept) and before every schema upgrade (last 3). Manual: back up now, save
or share a file (SAF / share sheet — USB, SD card, Drive, WhatsApp) with no Play Services.
A restore is unpacked and checked into `files/restore/`; the app restarts and `Db.open` swaps
the files before opening (the replaced database is kept as a backup first). Two modes: take
over the backed-up till's identity, or become a new till (new device number, receipt prefix,
ID range; pending outbox dropped). A till that ever published to a sync folder always gets a
new identity on restore: the original went on selling after the backup, so its later IDs and
receipt numbers are already taken in the store. Its sync is off until turned on again, and the
original's segments already contained in the backup are marked as read.
Rejected: streaming rows into a new file (D-015, slower and more code for the same result),
`VACUUM INTO` (SQLite 3.27), `ATTACH` (disables WAL on Android), a read transaction without
writes waiting (the framework gives no read-only transaction before API 35), swapping the
file while the app runs (open cursors, cached statements).

### D-045 — Sync = per-till immutable segment files; joining = publishing existing rows (Phase 6)
Each till seals its outbox into numbered gzip NDJSON segments (≤ 2,000 events; header with
store, device, seq, count, HLC range; SHA-256 and count stored as file properties) and uploads
them under `seg-<store>-<dev>-<seq>`. Other tills download new segments per device in order
(a gap stops that device until it arrives), verify the checksum, and apply each segment in one
transaction together with its cursor, so an interrupted sync resumes and nothing applies twice.
The importer is idempotent and order-independent: EVENT rows `INSERT OR IGNORE` (derived data
only when new; a void arriving before its sale is applied when the sale comes), LWW rows merge
per field (an edit arriving before the creation makes a placeholder whose fields the creation
fills where they are older), stock counts rebuild the product's level from events. Each till
publishes a device card (name, app version, receipt prefix, cursors, last seen); the store
manifest is created by the first till and adopted by the others. A till joining (or re-joining)
publishes every row it already has as events ("backfill") instead of downloading a snapshot and
merging it: one code path (the importer) for all data, tested by the merge suite. An
interrupted backfill is repeated by the next sync (imports are idempotent). Receipt-prefix
clashes are resolved on joining; a device-number clash stops sync with a message.
Google Drive provider (D-014): REST v3 over `HttpURLConnection`, `appDataFolder` only, multipart
upload ≤ 5 MB else resumable (resumes from Drive's committed range), a 401 fetches a fresh
token once. Tokens come silently from GIS `AuthorizationClient`; when Google needs the user,
background sync stops with "sign-in needed" (pill on the selling screen) until the user signs in
on the sync screen. WorkManager: every 30 minutes and ~2 minutes after a sale (network + battery
not low, exponential backoff). Dependency: play-services-auth 21.4.0 (pinned in D-005) adds
~185 KB to the release APK (894,975 → 1,084,540 bytes with the sync screen); it is the only
supported way to get a Drive token without the deprecated Google Sign-In API.
Deferred: snapshot bootstrap for very large stores and deleting segments every till has read
(`Cursors.deletable`) — segments are gzip-compressed and hold at most 2,000 events each.
Archiving old sales (planned for Phase 6) is deferred too: the perf suite passes every budget
at 1M+ sale lines, and purging synced history safely needs local stock and summary
checkpoints that `DerivedRebuild` respects — more risk than benefit before the first release.
Rejected: one shared database file on Drive (no concurrent writers), per-row files (thousands
of API calls), Drive change feeds (need a broader scope), Firebase (backend, cost, privacy).

### D-046 — App language chosen in the app, stored in a preferences file (Phase 7, 2026-09-30)
Settings → App language: the phone's language, English or Bahasa Melayu, per phone (the
receipt language stays a store setting). Every activity and the Application wrap their base
context with the chosen locale (`AppLanguage.wrap`); changing it restarts the screens (the open
bill lives in app-scoped state, so nothing is lost). The choice must be known before the first
screen inflates and before any database opens, so it is a tiny SharedPreferences file read once
at process start — the one deliberate disk read on the main thread (StrictMode allowed for that
read only; < 1 ms). Rejected: the `meta` table (the database opens later, off the main thread),
AppCompat's per-app locales (no AppCompat, D-002), Android 13's LocaleManager alone (API 33+
only; two code paths). App-context strings (the seed role names) follow the language the app
had when the database was first created.
### D-047 — Promotions as automatic line discounts; schema v6; unknown sync events are kept (Phase 8, 2026-09-30)
Promotions ("N for RM X", "buy X get Y free", on one or more products, optional dates) are LWW
master data (`promotion`, products as a comma-separated id list — rarely edited concurrently,
last writer wins for the whole list). They are kept in memory (`PromotionService`) and applied
by the pure `:core` rules (`references/money.md` §11) before `PricingEngine`: the saving becomes
the line's discount, so tax, bill discounts, cash rounding, refunds and reports need no special
cases. Sale lines store the promotion id and its name at the time (receipt reprints stay
identical after renames or deletes). Only unchanged whole-unit piece lines take part; a line the
cashier discounted or re-priced keeps the cashier's price. Changes need MANAGE_PRODUCTS and are
audited (PROMOTION_CHANGE).
Sync across versions: from v6 an event of a kind this version does not know is kept in the LOCAL
`sync_deferred` table and applied once an update knows it (before it was skipped for good). The
v5 → v6 migration clears the sync cursors once, so a till updated late re-reads the store's
files and picks up the promotions it skipped (imports are idempotent).
Rejected: promotions as price changes (lose the shelf price and the receipt line), a separate
promotion discount column (every report and refund would need to know it), per-row product
membership table (more sync rows for no gain at this size).
### D-048 — Data safety: no required Google login; protected by default, visible when not (Phase 9, 2026-09-30)
The owner asked whether a Google login should be required so shops cannot lose their data. It is
not required. A required login would lock out phones without Google Play services, block the
first sale on a day without internet, and still not protect a shop that never turns backup on.
The real risk is the data existing on one phone only: lost, broken, reset or the app removed —
and the automatic backups on the phone go with it. So:
- First-run setup has a "Protect your shop's data" step: Google Drive backup (recommended) or a
  daily copy to a folder (SD card / USB drive); it can be skipped.
- The selling screen shows a **Not backed up** pill when there is data (a product or a sale) but
  no copy off this phone in the last 3 days. A copy off the phone is a successful Google Drive
  sync round, a copy to the folder, or a saved/shared backup file (`meta` timestamps). Tapping
  it explains the risk and offers both fixes. Nothing shows while the data is safe.
- The automatic daily backup is also copied to the folder the owner picked (system folder
  picker, persisted permission, `lekaspos-<date>-<time>.lekasbak`, newest 7 kept, only our own
  files are ever listed or deleted). A failed copy is shown on the backup screen.
- Before each automatic backup the database is checked (`PRAGMA quick_check`). A damaged
  database is not backed up at all — rotating would replace good backups with damaged ones —
  and the selling screen shows **Data problem** until a later check passes.
- Sync is presented as "Google Drive backup": it protects a single till too, and a new phone
  gets the whole shop back by turning it on with the same account ("Restore or join my shop
  from Google Drive" on the welcome screen). An instrumented test restores a lost phone from
  Drive in full.
Rejected: required Google sign-in (above); nagging dialogs at every start (owners learn to
dismiss them — a quiet pill that only appears when there is a real risk is kept honest);
backups to the phone's shared Downloads folder (lost with the phone, and needs storage
permissions on old Android); our own cloud (no backend, by design).
### D-049 — Cashier-first selling screen (Phase 10, 2026-09-30)
The owner asked for a UI that a brand-new cashier can use without guessing: straightforward, no
decoration, still light and fast. An audit of the selling flow found: icon-only controls (⏸ held
bills, ＋ other item, ▦ catalogue, ⋮ menu), a quantity change that took a dialog per tap, a
16-item menu mixing till jobs with back-office screens, a payment dialog whose card/e-wallet
buttons fell below the fold on a 5-inch phone, no change shown while typing the cash received,
search results that hid the bill after a pick, and the change gone once the result closed.
Decided:
- Words on every control a cashier uses (icon above a short label), camera inside the search
  box, pills only when there is something to see (held bills appear as "2 held").
- The selected bill line (scanned last, or tapped) shows Remove, −, quantity, +, More in place.
  "−" stops at one: removing is its own red button, never an extra tap on "−".
- A big TOTAL above a big PAY; the empty bill says how to start and shows the last sale's change
  and a reprint (kept in memory for the session; the sale itself is in the database as always).
- Payment: total, cash received with live change, one-tap notes, all methods (3 per row), keypad
  last. The result shows "Received … · total …" under a highlighted change.
- Menu: cashier jobs; everything else under "Manage shop ›" (one extra tap for the owner).
- Catalogue: a "Popular" tab (best sellers of the last 30 days from `sum_day_product`, ranking
  cached 10 minutes, products read fresh), tiles marked "×n" when on the bill.
Rejected: a full-screen payment activity (a dialog over the bill keeps context and is cheaper),
swipe-to-delete on lines (invisible to new cashiers), a first-run tutorial overlay (the empty
bill's three lines do the same job every time), hiding menu entries by permission (the gate
already asks for a manager; hidden entries confuse owners), product photos (size and speed).
### D-050 — Only registered products are sold; no "Other item" (Phase 10 feedback, 2026-09-30)
The owner pointed out that the "Other item" button (sell anything by typing a price, added in
Phase 8) confuses new cashiers and lets a cashier sell things the system does not know: no
stock, no cost or profit, a free-typed price with no permission check. Removed: the button, the
"Sell as other item" choice for an unknown barcode, and the dialog. An unknown barcode now offers
only "Add product" (name and price; the barcode is filled in; it needs the product permission or
a manager's PIN) and the product then goes on the bill — the catalogue fills itself as the shop
sells. Items without a barcode (loose vegetables, kuih) are registered once as products sold by
weight or with "price entered at the till", and picked from Items / Popular. "Price check" takes
the freed place on phones. Old held bills and past sales with unregistered lines still load,
print and report ("Other items" in reports).
Rejected: keeping it behind a permission (the unknown-barcode "Add product" already asks for the
same approval, and registering keeps stock and reports right); a store setting (one more switch
the owner must understand).
### D-051 — Public release 1.0.0 outside Google Play (2026-09-30)
The owner wants any shop to use the app — including Google Drive backup with its own Google
account — without publishing on Google Play yet. Decided with the owner:
- **Google sign-in "In production"** (Google Auth Platform → Audience → Publish app). The only
  scope is `drive.appdata`, classed non-sensitive: no verification review, no user cap, and no
  7-day expiry of sign-ins (Testing mode expired them weekly). No logo (a logo starts a brand
  review); home page and privacy policy on `faizoken.github.io` (authorized domain).
- **One signing key for every public copy, forever**: the key the tester builds already used
  (keyKind `release`; the GitHub secrets keep their `TEST_*` names). Changing it would force every
  shop to uninstall. If the app goes on Google Play later, enroll in Play App Signing with **this
  same key** ("use my own key"), so downloaded copies update from Play and the existing Android
  OAuth client keeps working. The owner keeps two backups of the key file and its password.
- **Distribution**: GitHub Releases (a normal, "latest" release with a fixed asset name
  `LekasPOS.apk` plus a versioned copy) and a Download section on the website with install and
  update steps; Settings → About → "Website & updates" (no app store updates the app).
- **Version 1.0.0**: `versionName` is the release version; the CI run number stays the
  `versionCode` (every build installs over the previous) and shows as "(build N)" in About. The
  public release is the CI build of the tagged commit; the release smoke test upgrades from it.
- Coming: Google will require apps installed outside Play on certified devices to come from a
  registered developer (identity, package name, signing key) — Brazil, Indonesia, Singapore and
  Thailand from September 2026, all countries in 2027. Before that reaches Malaysia, register
  `com.lekaspos.app` and this key in the Android Developer Console (or through Play Console).
Rejected: a new release key (the owner's phone and testers would have to reinstall; no gain);
staying in Testing with listed users (100 at most, weekly re-sign-in); an in-app update check
against GitHub (network use beyond Drive; a link to the website is enough for now).
### D-052 — App logo: torn receipt with a slanted "L" (2026-09-30)
The launcher icon was the stock Material "shopping basket" — used by countless apps. The owner
asked for a unique, modern logo: minimal, readable when small, with its own identity. New mark,
drawn from scratch: a white till receipt with a torn (zig-zag) edge and a slanted "L" for
*Lekas* ("fast") cut out of it, on an emerald gradient (#22AB7A → #0C5A41). Two shapes, two
colours; legible at 16–24 px; fits the adaptive icon's 66dp safe circle; a monochrome layer for
Android 13+ themed icons; the legacy (API 21–25) icon is the same mark on a round badge. The L is
wound opposite to the receipt, so it is a hole without `fillType` (API 24+). The website and the
README use the same mark (`site/icon.svg`).
Rejected: a receipt with a lightning bolt (the standard "electricity bill" icon in Malaysian
payment apps), speed lines and text lines (clutter at small sizes). Not checked against trademark
registers: before registering the name or logo as a trademark, search MyIPO.
### D-053 — Faster Google Drive backup that never looks stuck (2026-09-30)
The owner found Drive backup slow and "stuck" before it showed that it was syncing. Causes found:
the status changed only after the sync lock was free and the Google token had arrived; a new
token (and the security-provider check) on every round; a new TLS connection for every request
(`disconnect()` after each); a "does it exist?" lookup before every upload and before every
device-card update; the whole segment folder listed every round (slower every month); 30 s
connect / 60 s read timeouts; after a sale, WorkManager waited ≥ 2 minutes (and only sales
triggered it). Fixed:
- **Feedback**: "Connecting to Google…" the moment sync is asked for; then "Sending changes (1 of
  2)", "Getting changes from the other tills (1 of 3)", "Finishing"; plain results ("Everything
  is backed up", "3 changes waiting — they go by themselves in a few seconds", "Last backup: 2
  minutes ago"). Turning on and signing in show "Connecting…" at once (no second tap).
- **Speed**: tokens cached per account (45 min); connections reused; first uploads without a
  lookup (a retry looks first); the device card patched by id and re-sent only when it changed
  or every 15 min; the listing asks only for files created since the newest one seen (minus 15
  min, by Drive's clock), the whole folder once a day and whenever a short listing shows a gap;
  15 s / 30 s timeouts. A quiet round is about one request.
- **Sooner** (`AutoSync`, in the app): 10 s after any committed change that queued sync events
  (a sale, a price, a count, a customer …, via `Db.onOutboxCommit`), 5 s after the selling screen
  opens, 3 s after the internet comes back, at once for "Sync now" or when the sync screen opens
  with changes waiting; automatic rounds at least 45 s apart. WorkManager stays as the fallback
  while the app is closed.
Rejected: WorkManager expedited work (quota-limited, no delay, not for every sale); a foreground
service (a permanent notification for a background chore); parallel uploads (rounds are small
now; order and idempotence stay simple).
### D-054 — Bug hunt: consistency, safety and speed fixes (2026-10-01)
The owner asked for a thorough search for bugs "to make it more consistent, fast and optimized".
Evidence: lint, a StrictMode run, the perf report, and six read-only code reviews (back office,
hardware, sync and backup, database, money and selling, selling screen); every finding was
checked in the code before it was fixed, and each fix has a test where one is possible.
Decisions that change behaviour:
- **Permissions and audit filled in**: a customer's credit limit needs "Go over a credit limit"
  (0 = no limit, so whoever edits a customer could lift the limit that stops their own credit
  sales) and is audited (new action 26); CSV import sets stock only with "Receive, adjust and
  count stock"; throwing a held bill away is a cancelled bill (same permission, same audit entry);
  sharing a receipt from the sales history is a copy like a reprint; the receipt logo needs the
  Settings permission; store settings, the logo and tax rates are audited (new action 27).
- **Fail closed after Android restores a screen**: nothing is allowed until the signed-in staff
  member is read from the database (a restored back-office screen ran with every permission).
- **Wrong PINs are counted per person**, and only that person's right PIN clears the count (a
  cashier's own sign-in gave fresh guesses at the manager's PIN); every wait is audited with who
  was at the till.
- **Voids** need an open shift when the store requires shifts (like sales and refunds), and a
  refund worth 0.00 blocks the void of its sale like any other refund.
- **The first receipt prints once**: asked for again (a second tap, the result shown again) it is
  a copy (permission + audit).
- **Selling**: a quantity above 99,999 (pieces or kg) is refused, and a change whose amounts
  cannot be computed is refused before anything is written (it crashed the till and left a bill
  that could never load; a stored bill now always loads). − and + count from the quantity the line
  has now (fast taps were lost). Removing the last line drops the bill discount (a customer stays).
  A promotion that changes during payment applies after it. A cash remainder that rounds to 0.00
  is settled with Cash. A partial refund's own figures add up to the sen.
- **Scanners in dialogs**: every dialog swallows Enter/Tab/Space (a scanner's Enter pressed the
  payment dialog's focused "Exact" button and completed the sale); number pads drop
  scanner-speed digits; a scan into the focused search field is taken out and scanned.
- **Payment**: a touch outside, Back or Cancel never silently drops a split payment half entered
  (Cancel asks); a double tap does not pay the rest; the screen does not rotate while paying; the
  idle auto-lock never locks during a payment, and keypad taps in dialogs count as activity.
- **Rotation**: every screen except the selling screen, lock screen, camera and Diagnostics
  handles rotation itself (typed forms, running exports and open dialogs were lost).
- **Top bar**: pills that do not fit beside the store name move to a line below (on a 360dp
  phone four pills pushed the Menu button off the screen).
- **Sync identity** (both found by review, neither seen in the field yet): turning sync off and
  on into another Google account or an emptied folder restarts this till's file numbers at 1 (other
  tills never read it again); an older copy of a till that has since published (a backup from
  before it first synced restored as "the same till") is refused and becomes a new till after a
  restart; "restore this till" over data of the same till that has synced gives a new identity; a
  crash between the restore swap and its identity reset is finished at the next start; a restore
  whose safety copy of the current data fails is not done at all.
- **Sync speed at scale**: listings are filtered while read (a year of files is never held in
  memory); unsent segments and local clean-up are key ranges, not full scans; one status refresh
  at a time; a round waiting for another counts as planned; the fallback job is cancelled only
  when nothing is left; failed automatic rounds are retried (1, 2, 3 min); one retry on a new
  connection when a kept-open one died; every device card must download when joining.
- **CSV**: exported text a spreadsheet would run as a formula starts with `'` (stripped again on
  import); the product export carries each product's number (`#…`) so an edited file updates the
  same products, also those without barcode or SKU; barcodes a spreadsheet turned into
  "9.55E+12" are refused; a save that fails deletes its unfinished file and runs on after the
  screen closes.
- **Printing**: drawer pulses before waiting receipts; automatic receipts still waiting after 10
  minutes expire (they can be printed again from the sale); RFCOMM channel 1 as a last resort;
  image jobs paced to the head's speed; "reconnect" keeps a live link to the same printer; text
  replacements keep the column count (€, £ and 4-byte Chinese print as a picture); the test page
  prints as text on a Latin printer; SPP scanners without an Enter suffix work; the camera asks
  for permission once, beeps after the result, adds an item again only after it left the view.
- **Database**: edits write only the fields the user changed (a field another till changed
  meanwhile stays); reports drop the zero rows voids leave behind, "sold" means more sold than
  returned, category totals follow the product's current category; the product and pick screens
  find switched-off products; switched-off products raise no low-stock alert; shift reports take
  tax and discount of documents voided in the shift off it (like cash, D-038); a delivery is
  received once even when Save is tapped twice; the customer list no longer repeats rows from
  page 2 on; stock reads after a count use an index range (`references/database.md`).
Not changed (for the owner): stock with cost 0 lowers the average cost when stock is received
(a business rule); the receipt still decides "weighed" from the quantity (needs the sell mode
stored on each sale line — a schema change, later); default receipt prefixes can repeat between
tills 1 in 676 (a taken prefix is replaced when a till joins; not two tills joining at once).
Rejected: counting wrong PINs per till (the reason for this change); a UNIQUE receipt number
(existing data may already hold one pair); locking the whole app in portrait (tablets and
landscape shops).
### D-055 — Second bug hunt: data safety, security and sync consistency (2026-10-01)
The owner asked again for a thorough search for bugs, fixing whatever is real. Nine read-only
reviews (money maths, selling, sales and reports, sync, backup and database, inventory and CSV,
staff and credit, printing and camera, back-office screens) found about 95 candidate problems;
each was checked in the code before it was fixed, most with a test. Decisions that change
behaviour:
- **A damaged database is never deleted.** Every SQLite open passes `KeepDamagedDatabase`:
  Android's default handler deleted `lekaspos.db` and its WAL on the first SQLITE_CORRUPT (one bad
  page after a power cut wiped the shop, an empty store opened, and its daily backups then rotated
  the good ones away). A database too damaged to open moves to `files/backups/damaged-*.db` and
  an empty store opens, so a backup can be restored in the app; for 7 days "Data problem" shows
  and automatic backups pause.
- **Store settings: only what the user changed is written.** `saveStore(before, after)`: a fresh
  install's first-run Setup wrote every default setting with a new version, and once that till
  joined, its empty header, BRN, SST number and tax switch won on every till. A till joining a
  different existing store also yields its own settings to the store's.
- **Restore only after "Restart now".** A checked restore was armed before the question: leaving
  the screen (Back while checking, the idle lock) applied the old backup at some later start and
  silently replaced a day's sales. After a restore nobody is signed in (the backup brought back
  last night's owner sign-in) and wrong-PIN counts stay this phone's. A restored till that keeps
  its identity skips 2^30 IDs and continues receipt numbers after the highest used (same phone)
  or gets a new receipt prefix (another phone); a backup older than the sync tables counts as
  this till.
- **Idle lock that works**: checked when a screen starts or resumes and before a touch or key
  counts (after the phone's screen turned off, the first tap reset the idle time, so the till
  never locked); the last activity time is stored when the app goes out of sight, so a new
  process honours it. 1.3.1 (owner's report on 1.3.0): the check on resume too — many phones only
  pause the app while their screen is off, so waking them never restarted the screen and the till
  stayed signed in until the first tap.
- **PINs**: a removed staff member cannot sign in or approve (they stayed signed in on other
  tills); only owners make owners or change an owner's role, PIN or removal (a manager whose role
  manages staff is refused there; the owner does it); wrong-PIN waits are
  also measured by the time since boot (setting the clock forward skipped them) and a clock set
  back makes the wait count from now (it blocked the owner's PIN for years); a new PIN or the
  recovery code clears the count.
- **Sync**: a local edit is stamped above the field's stored version (a till with a clock days
  behind lost every edit on the other tills, silently); a phone more than 10 min from Google's
  clock is told to fix the date; two tills creating stores at once settle on one; a folder emptied
  while tills keep syncing is detected and republished; at most 1,000 files per till per round
  (a joining till held a year of listings in memory); no blind retry of a create whose answer was
  lost (duplicate device cards); timeouts on Google Play services; a refused token asks to sign in.
- **Same barcode on two products**: the row created last wins on every till (it was "changed or
  imported last on this till", so two tills sold different products for one scan); CSV updates
  by barcode pick the same one; the product's edit screen names the other product.
- **Voided twice** on two offline tills: the first void counts; the later one's credit reversal is
  cancelled (fixed id −(void id), the same row on every till) and shift reports count one void;
  cash, card and e-wallet stay in each till's own shift (each drawer really paid them back).
- **Refunds**: partial returns use cumulative rounding (a part could refund more than was left of
  a heavily discounted line and the last return charged the customer); a double tap refunds once;
  a whole kilo of a weighed product can be returned by weight.
- **Selling**: Back on Android 13+ asks before dropping a split payment; a payment half entered
  survives the screen being rebuilt; credit to a removed customer is refused; screen jobs never
  crash the till; a bill write that failed is repaired by rewriting the whole bill; removing the
  last line is audited like a cancel; the drawer opens when only change is given; a manager's
  approval of a credit sale is in the audit log; sales, refunds, voids and credit repayments read
  the open shift inside their own transaction.
- **Printing and camera**: the printer loop restarts after any error and drops a job it cannot
  draw for lack of memory; picture receipts are drawn in bands (under 1 MB); one decoder per camera
  thread; periodic focus on phones without continuous focus; QR read every 4th frame while
  selling; shared files kept per kind for 15 min (a share no longer deletes the previous one).
- **Inventory and CSV**: a received delivery cannot come back (draft token); counts find
  switched-off products and refuse a finished session; the count report and session list do not
  load everything; CSV export skips deleted categories and tax rates; a barcode a spreadsheet
  stripped of its leading zero gets it back; a failed import audits what it did.
- **Back office**: dialogs count as activity; the app language overrides only the locale (screens
  kept their old size after a rotation); Diagnostics needs the Settings permission; the Sync
  screen updates in place; picker results wait for the sign-in to load; `uiMode` never recreates
  a screen; reports and receipts exports use the stored business day.
- **Smaller**: backups when the clock was set back, the backup just written never pruned, folder
  copies dated by the backup they copy, temp files of a failed backup deleted, pre-upgrade backups
  written atomically and pruned by time, foreign keys turned on after migrations, the perf test's
  database never takes a store restore, `DerivedRebuild` recomputes sale status, day boundaries
  where midnight does not exist, scale labels of a second scale brand, margin overflow.
Not changed (for the owner): a customer created without a credit limit (0 = no limit) needs no
manager, so a cashier can sell on credit without a limit to a new customer (a business rule);
a refund of a sale voided on another till, or two tills refunding the same items, both stay (the
money left the drawer) and are not flagged yet; a refund without restock still takes its cost off
cost of goods; sharing a backup counts as "backed up" when the share sheet opens.
Rejected: renumbering a till from 1 when its files vanished from the folder (tills that already
read further would skip the new files); observing remote clocks without the 24 h limit (one bad
till would pull every till's clock into its future); forcing a new identity for every restore on
another phone (single-till shops keep their device number; the ID gap makes it safe).
### D-056 — Third bug hunt: cross-cutting reviews and weak features (2026-10-02)
The owner asked for a third, free-hand search for bugs and weak features. Twelve read-only reviews
took angles the earlier hunts (by module) had not: concurrency and cancellation, money end to end,
time and clocks, Android versions and devices, start-up and failure handling, real-world input,
security, a shop's daily workflows, performance at scale, and regressions in the D-053, D-054 and
D-055 changes. Each finding was checked in the code before it was fixed, most with a test.
Decisions that change behaviour:
- **Cost of goods.** A refund whose goods do not go back on the shelf keeps their cost in cost of
  goods (refund line cost 0; spoiled or broken returns vanished from profit); a refund's cost share
  follows the quantities. Stock whose cost was never entered (cost 0) takes the delivery's cost
  instead of halving it; a product without stock tracking takes its latest delivery's cost (its
  level only grew, so price rises never reached the cost). Both were "left for the owner" before.
- **Credit repayments** are never more than is owed (more is a balance adjustment: CREDIT_LIMIT +
  audit) and every one is audited (new audit action 28). Cash repayments are rounded to the 5-sen
  step like a sale; paying off the debt books the difference as "Cash rounding", so the drawer and
  the balance both come out right.
- **Damaged data.** Damage found right after opening (a `meta` page) is set aside like damage at
  the open: an empty store opens and Backup & restore is reachable (it failed at every start); no
  welcome wizard while the data problem shows; a restore over a damaged database no longer marks
  the restored data damaged; the set-aside marker is written before the files move.
- **Start-up and background jobs.** WorkManager's initialisation and scheduling failures are
  handled (a full disk crashed the app moments after every start). Half-written backups (`*.part`)
  are deleted when the store opens; the "replaced" copy of a restore goes through `.part`; a stopped
  backup job is not damage; the pre-upgrade backup just written is never pruned and is compressed
  for speed. Shift open/close and this till's settings follow the commit even when their screen is
  closing (a "shift required" till could not sell until restarted). Turning sync off changes the
  flag inside the transaction that stores it, and finishes after the screen closes.
- **Error log.** Warnings, errors and crashes go to `files/logs/errors.log` (256 KB, one older
  file kept); Diagnostics → "Share the error log". It stays on the phone unless shared (privacy
  policy updated); like all logs it holds no PINs, tokens or customer data.
- **Daily backup at a quiet moment.** The automatic backup waits while a bill is being rung up or a
  sale was made in the last 3 minutes, but never past 36 h since the last one (the copy pauses
  sales; it ran when the till was switched on in the morning). Its scratch copies are not synced.
- **Owners and roles.** Owner-only changes (making an owner; an owner's role, PIN, removal) need the
  signed-in owner or an owner's PIN for that one change — an owner's approval of the whole Staff
  screen no longer counts (the cashier could make themselves owner). A role is widened only with
  permissions the person granting them holds, and nobody but an owner widens their own role.
- **Idle lock.** A locked till drops input (the rest of a scan that woke it went into the bill as a
  cut-short code); camera scans are checked like keys; an idle time that ran out during a payment
  locks the till as soon as the payment ends; back-office screens leave when the till locks for any
  reason; the last sale's result is cleared on lock (its Share was a free first copy); recent
  activity is stored at the sign-in and every 30 s, so a power cut counts as idle time. Wrong-PIN
  waits are measured by the time since boot also on Android 5 and 6.
- **Restore and copies off the phone.** A restored database loses any triggers and views (the app
  makes none; a doctored backup could bring one that deletes audit entries); who restored which
  backup is in the restored activity log; saving or sharing a backup copy is audited. Opening stock
  on the product form needs the stock permission; changing how a product is sold, its tax rate or
  its pack prices is audited like a price change.
- **Clocks.** Payment is refused while the phone's date is before 2026-09-01 (a clock reset to 1970
  or 2000 filed sales under that date for good) and asks first when the time is more than an hour
  before this till's own last sale (`ClockCheck`). The HLC follows other tills relative to its own
  last time (a till whose clock was set back stopped following them). Stock events are stamped
  after what they follow — a sale or movement after the products' last counts, a count after the
  product's latest sale, movement and count — so a till whose clock once ran a year ahead no longer
  makes the other tills' sales and recounts "earlier" than its count (`StockDao.stampAfterCounts`).
- **First sync of a large store.** The outbox is sealed into segment files every 10 backfill
  chunks (the whole history went into the outbox table first: up to a gigabyte more database).
- **Selling.** The camera hands unknown barcodes, weighed and open-price items to the selling
  screen (Add product / weight / price — D-050 worked only with keyboard scanners). Refunds pay
  back the way the sale was paid by default (customer credit first; cash was paid out for goods
  taken on credit). Quick-cash buttons offer the notes customers hand over (next 5, next 10, RM20
  for a small bill, next 50, next 100). A held bill can be deleted with a visible button. Number and
  PIN pads scroll (their "0" and OK were cut off on phones in landscape and in split screen). The
  bill is priced again when Pay is pressed (a promotion that started or ended at midnight). A scale
  label with no weight or a 0.00 price is weighed or priced instead of sold as 0.001 kg or free.
- **Back office.** A receipt number alone ("123", "R45") finds this till's sale or refund; the
  report's "Total received" is "Total (after refunds)" (it counted sales on credit); the activity
  log filters promotion changes and repayments; closing a shift takes an optional note shown on the
  shift report; the screen stays on during an import or a sync round; a phone without a file picker
  says so instead of closing the app; a refused Bluetooth permission offers App info.
- **Input.** A CSV file is UTF-8 only when the whole file is (judged by 32 KB, "Nescafé" on row 700
  was imported as "Nescaf�"); Excel's UTF-16 "Unicode text" is read; rows of separators only are
  skipped; SKU placeholders ("-", "0", "N/A"…) mean no SKU and a SKU twice in a file is refused (the
  rows merged into one product with every barcode); barcode cells written as numbers ("…568.00",
  "9,556,001,234,568") get their digits back. Barcodes are stored in the form a scan finds
  (`Gtin.canonical`: spaces of the printed digits, GTIN-14 as EAN-13, line breaks); the code exactly
  as scanned wins over its padded form; an EAN-13 also finds a stored GTIN-14; a scanned SKU finds
  its product. Scans typed into a focused field on Receive, Count and Pick are taken out and scanned
  (`FieldScan`). Scanner digits are read by key code (numeric-keypad scanners without NumLock, other
  keyboard layouts). Number and PIN pads take scanner bursts up to 60 ms per key, as the selling
  screen. Customer phone search matches "012…", "6012…" and "+6012…".
- **Printing.** A sent job is recorded before the next one runs (a drawer pulse in between printed
  a receipt twice); a job cleared from the queue while the printer connected is not printed.
- **Review of these changes** (one more read-only review of the whole change, then CI). The store's
  first PIN signed its owner in only after turning the PIN login on: for a moment nobody was signed
  in, the new "leave when the till locks" rule closed the Staff screen (and Setup), and the one-time
  owner recovery code was never shown — the owner is now signed in first. A payment longer than the
  idle time locked the till within seconds of the sale and took the change off the screen: the
  result now stays until it is closed, for at most a minute, then the till locks (a scan for the
  next customer locks first). A delivery order's own barcode scanned into Receive's reference field
  stays there when it is no product. A barcode an older version kept with its dashes is still found
  by an import (and gets its digits added). The sign-in itself stores the first idle heartbeat.
Not changed (for the owner, or later): folder and saved backups still hold the PIN hashes (a 4–6
digit PIN cannot be protected by hashing; encrypting backups would tie every restore to the
recovery code, and a lost code would lose the data — the copies are audited instead, and PINs are
best typed out of sight); renumbering a till's files after its Drive data was deleted (rare;
D-055's `fullFrom` is the way to reuse); automatic rounds making one Drive file each and the daily
full listing growing over the years (compaction, sync.md §8); the camera opened on the main thread;
money that does not fit at very large font sizes; a store time zone setting (each till's own zone
dates its sales); a settings form rebuilt after Android ended the app re-saving every field; voids
made twice before 1.3.0 not repaired; a price-embedded scale code typed by hand. Weak features the
review listed, for the owner to choose from: editable payment methods (DuitNow, e-wallet brands),
a fuller "hutang" book (who owes most, sharing a statement), a quick "add product" form at the
till, out-of-stock lists, selling prices on receiving, counting notes and coins at shift close,
"pay part, rest on credit" from the payment screen.
Rejected: blocking every sale until the phone's time passes the till's last sale (a clock that ran
ahead and was corrected would stop the shop for hours); computing the backup's counts outside the
writer pause (the header must describe exactly the data copied).
### D-057 — Error reports to the developer, with the shop's consent (1.5.0, 2026-10-02)
The owner asked that errors, crashes and failed checks reach the developer by themselves, so bugs
on shops' phones are known and fixed without waiting for a shop to send the error log (D-056).
- **What is reported:** crashes, errors the app logs (`Log.e`), the performance test failing, and on
  Android 11+ (`ApplicationExitInfo`, read at the next start) freezes (ANR, the main thread's stack)
  and the app ended by Android (native crash; low memory while in use; too many resources; could
  not start). Warnings are not reports; the error log's lines before an error go with it.
- **What a report holds:** kind, title, the stack trace, the log before it, app version/build and
  signing, Android version, phone model, RAM, free storage, database size, app language, a random
  install id (new each time reports are turned on) — never sales, products, customers, staff, PINs,
  tokens or the Google account. Free text goes through `core.diag.CrashText.scrub` (e-mail and
  Bluetooth addresses, chosen files and folders, quoted values, numbers of 7+ digits).
- **Consent:** asked once on the selling screen (someone with the Settings permission, no bill
  open), changeable in Settings → Error reports, per phone (a preferences file: it must work when
  the database is what failed). Off deletes what waits. Diagnostics → "Send a report to the
  developer" sends one report by hand, with the user's note and optional contact, whatever the
  setting. Only release-signed builds send on their own (test and CI builds would be noise).
- **On the phone:** one file per bug in `files/reports` (repeats counted), at most 20, 14 days. A
  WorkManager job sends them when online (exponential backoff), at most 10 a day and each bug once a
  day; a crash is also tried once at once, for at most 2.5 s, so a crash at every start is heard.
  Never on the main thread or in a sale's way; a report that cannot be kept or sent is dropped.
- **Grouping:** a fingerprint (16 hex) from the exception types and the app's own frames without
  line numbers, so the same bug is one fingerprint on every till and in every build. Release builds
  therefore keep the names of the app's own classes (`-keep,allowshrinking,allowoptimization
  class com.lekaspos.**`): +67 KB (1,290,284 → 1,357,473 bytes); libraries are still renamed.
- **Where they go:** `relay/` — a Cloudflare Worker (free plan) deployed by `relay.yml` — checks a
  report (format, sizes, 20 a minute per address) and files it in the **private** repository
  `FaizoKen/LekasPOS-reports`: one issue per fingerprint (label `fp:…`), counts/tills/builds kept in
  the issue, a comment for each new build, a closed issue reopened (`regression`) when a build newer
  than every build seen before reports it again (not when labelled `wontfix`/`not-a-bug`). The relay
  holds the only GitHub token (fine-grained: Issues on that repository). That repository's "Readable
  trace" workflow comments the R8-retraced trace from the release's `mapping-<version>.txt`.
- **No new dependency:** HttpURLConnection, `android.util.JsonWriter/JsonReader`, Play services'
  `ProviderInstaller` (already there for Drive) for current TLS on old Android.
- **Old Android's certificates:** the relay's certificate (Cloudflare, Google Trust Services WE1 →
  GTS Root R4, ECDSA) was reached from Android 16 but not from Android 5 in CI. `app.RelayTrust`
  asks the phone's trust store first and, only when it refuses, accepts a chain ending at one of four
  bundled public roots (GTS Root R1/R4, ISRG Root X1/X2 — the CAs Cloudflare issues from; 5.4 KB),
  for the relay's connections only. Rejected: a custom domain with a chosen CA (still not trusted by
  Android 5–7) and plain HTTP (reports would travel unencrypted).
- **Duplicates:** GitHub lists a new issue a moment late, so reports of one bug at the same moment
  can file two issues; the next report counts the extra ones into the oldest and closes them.
Rejected: Firebase Crashlytics (Firebase SDKs need Android 6+ since 2025; a Google backend and
SDK; rejected for privacy in D-045); Sentry (a good product, but another company holding the
reports, a size cost, 5k events a month); ACRA (needs its own server, or an e-mail app that is
not automatic); filing GitHub issues straight from the app (the token would be in every APK);
the public repository (a slip in scrubbing or a spammed relay would be public for good, and a shop's
contact must stay private); sending without asking (the privacy policy promised nothing leaves
the phone by default); Google Play's Android vitals alone (not on Play yet, no logged errors).

### D-058 — Reports fast on the store's tablet: years, months less days, one reading (1.5.1, 2026-10-02)
The first error reports (D-057) were two failed performance tests from the store's own tablet
(Android 10, SQLite 3.22, 2.8 GB, low-end ARM). QUICK passed every timing but one plan check;
FULL (50,000 products, 1M sale lines) missed five budgets: `report_year` 6.6 s (3 s),
`report_month` 1.7 s (1 s), `slow_movers` 1.37 s (1 s), `popular_items` 445 ms (300 ms),
`search_multiword` p95 54 ms (50 ms) — 4–6× slower than the CI emulators (fast x86 servers), so
the budgets had only been met on emulators. Measured on the same data (Node's SQLite and the
SQLite 3.22 shell from sqlite.org) before changing anything; every change gives the same rows.
- **`shift_current`**: SQLite 3.22 scanned and sorted `shift` for this till's open shift (only an
  `opened_at` index). v7 index `shift_open (device_no, closed_at, opened_at)`: a direct lookup on
  every SQLite version (checked on 3.22).
- **Reports read fewer rows.** The time was the `GROUP BY product` sort of every product row of
  the period (a year of a big store: ~425,000 rows), done twice (categories, best sellers). Now:
  a per-year table `sum_year_product` (v7; Σ of its months; maintained with the month table, rebuilt
  by `DerivedRebuild`, filled by the migration); `RangePlan` (`:core`, exhaustively tested) reads a
  period as whole years, whole months and loose days, a month or year partly outside the period
  whole when its days outside had no sales ("this month", "this year": the rest is the future — an
  index probe), and a nearly whole one as the whole less the days or months outside; categories and
  best sellers from one reading (`ReportDao.summary`). Rolling year: 425,000 → 152,000 rows in one
  pass instead of two (1.08 s → 0.27 s on SQLite 3.22); 30 days: 70,000 → 41,000 rows (0.22 s →
  0.07 s); this year: 293,000 → 51,000 rows. Slow movers: one reading for the list, count and value.
- **Small results, two readings (1.5.2).** 1.5.1 read a period once and returned every product's
  totals to the app (~50,000 rows for a year) to make categories and best sellers in Kotlin. The
  CI emulators (Android 5 and 16) looked good; the tablet did not (report_year 4.8 s, report_month
  1.8 s, report_calendar_year 3.3 s). An Android 10 emulator, added to the perf run with notes on
  where each report's time goes, showed why: each row a query returns costs ~9 µs on Android 10
  (0.6 µs on Android 16) — a 16 MB cursor window held all rows, so nothing ran twice, yet the year
  took 649 ms against 130 ms of SQL. Now every Android reads the period twice into small results:
  the best sellers (ORDER BY … LIMIT) and the categories straight from the rows (by the product's
  current category): on the Android 10 emulator month 278 → 91 ms, year 649 → 277 ms, calendar year
  445 → 87 ms; the tablet runs ~6–8× slower than that emulator. Exports, which must return every
  product, use a 16 MB window on API 28+ (the default 2 MB one runs the query again per window).
  The perf run now includes Android 10 (API 29): the store's tablet runs it.
- **Category totals (v8).** Calibrated on the scenarios that did not change, the tablet runs ~11×
  that emulator run: the month report would still be ~1.0 s (budget 1 s). Its notes showed the
  categories as the larger half (every product row of the period joined to its product). Category
  totals per day, month and year (`sum_*_category`, 0 = no category) are now kept like the product
  totals, by the product's current category: `Summaries.apply` adds each sale there, and
  `Summaries.recategorize` moves a product's sales from its old to its new category when it changes
  — on a local edit (`ProductDao.update`), a synced edit, or when another till's product arrives
  after its sales (`Importer`; until then its sales count under the category their rows were filed
  under). The upgrade builds them by reading the product totals once (no rows rewritten: rewriting
  the category of every row took 29 s at FULL size). A month's categories are ~24 rows a piece.
  A bulk category change of products with long histories costs a few hundred row updates each.
- **Popular tab** (on the selling screen): its ranking reads a month of sales, ~0.3 s at FULL on the
  tablet even after the above. The tab now shows the ranking kept in this till's `meta`
  (`dev.popular`) at once and makes a fresh one in the background when it is older than 10 minutes;
  only a ranking older than 3 days (or none) is waited for. Perf scenarios: `popular_ranking`
  (background, ≤ 1 s) and `popular_items` (the tab's tiles, ≤ 50 ms).
- **Multi-word search**: only the id and sort key of the 2,000 FTS candidates are sorted; columns and
  stock are read for the page of 50 (p95 −35%, same results). Still 49 ms on the tablet (budget 50):
  its slowest samples hold a one-letter word ("Julie's" → "julie s", "F&N" → "f n") and the FTS
  index keeps 2- and 3-letter prefixes, so "s*" merged every word starting with s. With longer words
  to search by, a one-letter word now filters their candidates (`' ' || name_key LIKE '% s%'`):
  2.3–2.6× faster, same results (it matches names, not SKUs).
- **Error reports of checks** keep their numbers (the performance report is the app's own text, no
  shop data; "sale_lines <number>" hid the scale).
Rejected: product-ordered month table `(product_id, month)` (faster with one year of history, no
better after three — it scans every month ever); seeks per product instead of the sort (slower);
a month-driven popular query or a top-K two-pass (no faster on SQLite 3.22); raising the budgets
(the tablet is the store's real till).

### D-020 — Tax model (pending user confirmation of the compliance section)
Configurable tax rates per product, store-wide "prices include tax", per-rate-group rounding,
MYR 5-sen cash rounding on by default. See `docs/PHASES.md` open question 1.

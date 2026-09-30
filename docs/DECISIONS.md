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
### D-020 — Tax model (pending user confirmation of the compliance section)
Configurable tax rates per product, store-wide "prices include tax", per-rate-group rounding,
MYR 5-sen cash rounding on by default. See `docs/PHASES.md` open question 1.

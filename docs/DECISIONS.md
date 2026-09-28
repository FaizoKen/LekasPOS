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

### D-016 — Outbox is written only while sync is enabled
Why: otherwise it grows forever on single-device shops. Enabling sync starts with a full
snapshot upload, which carries every row's version information.

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

### D-020 — Tax model (pending user confirmation of the compliance section)
Configurable tax rates per product, store-wide "prices include tax", per-rate-group rounding,
MYR 5-sen cash rounding on by default. See `docs/PHASES.md` open question 1.

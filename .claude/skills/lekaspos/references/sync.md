# Sync, backup and archive design

Status: designed in Phase 1, implemented in Phase 6. IDs, HLC, LWW columns and the outbox
exist from Phase 1/2 so no data needs converting later.

## 1. Principles

1. The local DB is the source of truth. Everything works offline and without an account.
2. Sync is **log shipping between devices of one store**, with a cloud folder as the dumb
   mailbox. No server logic is required, so any blob store can be a provider.
3. A device only ever creates, and deletes, **its own** files. It never modifies or deletes
   another device's file.
4. Applying the same data twice, or in any order, gives the same result (idempotent,
   commutative merges). Sync may be interrupted anywhere and simply resumed.
5. Selling never waits on sync. Sync runs in WorkManager jobs, off the main thread.

## 2. Identity

| Name | Scope | Where | Notes |
|---|---|---|---|
| `store_uuid` | one per store | `meta` + remote manifest | created by the first device |
| `device_uuid` | one per install | `meta` | random UUID |
| `device_no` | one per install | `meta` | 22-bit random, prefix of all IDs this device creates |
| HLC | per device | `meta.hlc_last` | see §3 |

On joining a store the device checks the remote device list; if its `device_no` is already
taken (≈1 in 400,000 per pair) it re-rolls before creating any synced rows, or — if it already
has local rows — asks the user to reset. A `device_no` must never be active on two devices;
restoring a backup onto a second phone therefore offers "replace old device" or "new device".
Android Auto Backup is disabled for the same reason.

## 3. Hybrid logical clock (HLC)

64-bit value: `physicalMillis shl 16 or counter`. `now()`: `l = max(lastL, wallClock)`;
counter increments when `l` did not advance. On import: `observe(remote)` advances the local
clock past the remote HLC so later local edits order after what we have seen — unless the
remote physical time is more than 24 h ahead of this till's own time (the later of its wall clock
and its last HLC: a till whose clock was set back still follows the others, D-056) — a device with a
wrong clock — which is not adopted. Ties are broken by `device_no`. A local LWW edit is stamped above the version the
field already holds (`Lww.stampAbove`, D-055), so a till whose clock is behind still wins with
its later edit everywhere. Imports observe inside each applied chunk's transaction. Every Drive
answer's `Date` header gives this phone's clock offset; more than 10 min off → `sync.clock_off`
→ the Sync screen says the date is wrong.

## 4. Events

Every synced change is one event appended to `outbox` in the same transaction as the change
(only while sync is enabled; enabling sync starts with a full snapshot upload instead).

| Event | Payload | Apply rule |
|---|---|---|
| `LWW(entity, id, hlc, dev, fields{})` | only the changed fields | per field: apply if `(hlc, dev)` > the field's current version |
| `SALE` | sale row + lines + payments | `INSERT OR IGNORE` all; if new: stock, summaries, derived status (customer credit arrives as its own CREDIT events) |
| `SALE_VOID` | void row | insert-or-ignore; if new and sale present: reverse stock + summaries, mark voided |
| `STOCK_MOVE` | movement rows | insert-or-ignore; if new: apply to `stock_level` when after the last count |
| `STOCK_COUNT` | count rows | insert-or-ignore; if newer than the current count: recompute the product's level |
| `PURCHASE` | purchase + lines (+ generated movements share line IDs) | insert-or-ignore |
| `CASH_MOVE`, `CREDIT`, `AUDIT` | row | insert-or-ignore (+ derived balance for credit) |

LWW entities: `setting` (per key), `role`, `staff`, `tax_rate`, `category`, `product`,
`product_barcode`, `supplier`, `customer`, `payment_method`, `shift`, `count_session`.

Stock work (D-036): a PURCHASE event regenerates its RECEIVE movements with the purchase-line
ids; the moving-average cost change travels separately as an ordinary LWW `product.cost` edit,
so importers never recompute averages (D-033). A STOCK_COUNT row carries `expected`/`unit_cost`
for reports only; stock levels use just its qty and HLC.

Staff, shifts and credit (D-037..D-039): a PIN change is one LWW field (`pin_hash` holds the
whole salted record), so the same PIN works on every till. A shift is an LWW row edited only by
the till that opened it; its expected cash is recomputed from `shift_id`-tagged events (sales,
payments, voids, cash movements, credit repayments), never shipped as a total. A credit sale
writes its CHARGE entries as separate CREDIT events in the sale transaction; refunds to credit
and voids add reversing CHARGE entries (negative amounts). `customer_balance` = sum of entry
deltas, rebuilt by `DerivedRebuild.customerBalances`. Wrong-PIN counters and the signed-in
staff member are LOCAL (`meta`: `pin.*`, `session.staff`), per till.

### Conflict semantics (these become the merge tests)

- Same field edited on two devices → higher `(hlc, device_no)` wins on every device.
- Different fields of the same product edited on two devices → both edits kept.
- Delete vs. edit → `deleted` is just another LWW field; history is never lost.
- Price changed on A while B sells offline at the old price → B's sale keeps its line prices
  (lines are snapshots); the product ends with A's price.
- Stock: level = last count (by HLC) + movements after it. A count on A and offline sales on
  B made before the count (by HLC) are subsumed by the count; sales after it are subtracted.
  Local stock events are stamped after what they follow (`StockDao.stampAfterCounts` /
  `stampAfterEverything`): a sale or movement above the products' last counts, a count above the
  product's latest sale, movement and count — so a count from a till whose clock ran ahead never
  hides later sales or recounts of the other tills (D-056). The till's own clock is not moved.
- Same barcode assigned to two products concurrently → both kept; a scan (and a CSV update by
  barcode) picks the barcode row created last (`created_at`, then id — the same on every till,
  D-055); the product's edit screen names the other product.
- Same sale voided on two tills offline → the first void by (hlc, till, id) counts: every later
  void's credit reversal is cancelled by a charge with the fixed id −(void id) that every till
  makes itself (INSERT OR IGNORE), and shift reports count the first void only (cash, card and
  e-wallet stay in each till's own shift: each drawer paid them back) (D-055). A refund
  of a sale voided on another till, or two tills refunding the same items, both stay (the money
  left the drawer); not flagged yet.
- Receipt numbers never collide: per-device prefix + per-device sequence.
- A device offline for weeks uploads its whole backlog; nothing is lost or double-counted.

## 5. Files (as built in Phase 6 — D-045)

Remote layout: a flat folder (Google Drive `appDataFolder`); names from `core/sync/SyncNames`,
small key/values (`sha256`, `count`) stored with each file (Drive `appProperties`, sidecar
`.name.props` in `FolderProvider`).

| File | Written by | Mutable? |
|---|---|---|
| `store-{store}.json` — store manifest | first device | never rewritten |
| `dev-{store}-{dev}.json` — device card (name, app version, last seen, last seq, receipt prefix, cursors) | that device | rewritten only by its owner |
| `seg-{store}-{dev}-{seq}.ndjson.gz` — events | that device | immutable |
| (not built yet) snapshots, archives, blobs (logo) | — | — |

Segment = gzip NDJSON: header line `{v, store, dev, seq, count, firstHlc, lastHlc}` then one
event per line `{"e": entity, "o": op, "r": rowId, "h": hlc, "p": payload}`. Integrity: SHA-256
compared before applying, header checked, count checked; a bad file stops sync with CORRUPT.
Streaming read/write only (`SegmentCodec`).

## 6. One sync round (`SyncEngine.sync`, under a mutex)

0. First round after enabling (or after an interrupted one): **backfill** — every existing row
   is published as an event (`Backfill`, chunks of 300). The outbox is sealed into segments every 10 chunks
   while it runs (the whole history went into the outbox table first: up to a gigabyte, D-056). `meta sync.backfilled = store` marks it
   done; imports are idempotent, so repeating it is harmless.
1. Seal: one write transaction per segment moves ≤ 2,000 outbox rows into
   `files/sync/out/seg-<seq>.ndjson.gz`, records it in `sync_segment`, deletes those rows.
2. Upload every unsent segment in order (`put` with `replace = false`: an already uploaded name
   is left alone after a crash); mark uploaded; local copies deleted after 14 days (from
   `meta sync.cleaned_to` on, never the whole history). Uploads stop at the first failure, so the
   unsent segments are exactly those after the last uploaded one (a key range, not a scan).
3. Import: list `seg-{store}-*`; for every other device `d` apply `cursor[d]+1, +2, …` in
   order (`Cursors.next`), each downloaded to `cacheDir/sync-in`, verified, and applied in
   ~100 ms chunks (`hlc.observe` inside each chunk; the cursor moves with the last). A gap stops
   that device until it appears. The listing is filtered while it is read (`list(keep)`): only
   files after a cursor are kept, the rest only counted (D-054), and at most 1,000 files per till
   per round (`Report.more` → AutoSync runs the next round 2 s later, D-055). On a whole-folder
   listing, a cursor beyond a till's highest file means that till numbered again from 1 (it moved
   the store to this folder, or the folder was emptied): the cursor is dropped and the till read
   again (re-applying is harmless). Whole-folder listings also check the folder (D-055): a missing
   store manifest is created again; when this till's own first files are gone it publishes
   everything again under its next numbers and its card's `fullFrom` lets readers missing older
   files jump there; a till in a store other than the lowest-id one moves to it; a receipt prefix
   used by another card is changed by the till with the higher device number.
4. Publish the device card; reload settings / staff when those entities changed.
5. `meta sync.last_ok / sync.last_error` (`"sign-in"` when the provider needs the user).

Unknown kinds (v6, D-047): an event whose entity this version does not know is stored in LOCAL
`sync_deferred` (not skipped) and applied at the start of a later round once an update knows it.

Importer rules (`data/sync/Importer`): EVENT rows `INSERT OR IGNORE` by id, derived data only
when new (a void before its sale is applied when the sale arrives; a purchase regenerates its
RECEIVE movements with line ids); stock counts rebuild the product's level; LWW rows merge per
field by `(hlc, dev)` — a change before the creation inserts a placeholder with base (0,0) that
the creation fills; products are re-indexed for search.

## 7. Joining, restore and identity

- Enabling sync: pick the store manifest (own store if present, else create when none, else
  adopt the first), refuse on a device-number clash (another card with our number, different
  uuid; every card must download — an unreadable one fails the enable), take a free receipt
  prefix if ours is used by another card, publish this till's card, then backfill + sync. After
  creating a manifest the folder is listed again: two tills that created stores at once settle
  on the lowest id. A till joining a different existing store yields its own store settings
  (`setting.ver_hlc = 0`), so a new till's first-run Setup never overwrites the store's receipt
  header, BRN or tax switch (D-055). Before that (D-054):
  - the folder already holds **more** of this till's files (or its card a higher `lastSeq`) than
    this database ever sealed → it is an older copy of the till (a backup from before it first
    synced was restored as "the same till"): refused with `OLD_COPY`, `meta identity.renew = 1`,
    and the next start gives the database a new identity (`Restore.renewIfAsked`) before
    anything is written with the old number;
  - the folder holds **none** of this till's files but it sealed some before (another Google
    account, an emptied folder) → its numbering restarts at 1 (segments, local files, outbox and
    cursors cleared; backfill again). Other tills read a till's files in order from 1:
    continuing at k+1 they would never read it.
- Disabling: outbox cleared, backfill flag cleared (re-enabling publishes everything again). The in-memory
  flag changes inside that transaction (restored if it fails) and the call outlives the screen (D-056).
- Restore (D-044): a database that published segments (or had sync on) always gets a **new
  identity** on restore; so does "restore this till" over data of the same till that has synced
  (whatever the backup contains, D-054); sync is turned off; the old device's cursor is set to its
  last sealed segment (that data is in the backup), so only what the original did afterwards is
  imported. The new device number never gives the old receipt prefix. `files/restore-pending`
  keeps the mode from the swap until `afterOpen` has run (a crash in between still gets the
  identity reset); a restore whose safety copy of the current data fails is not done at all.
  A backup older than the sync tables counts as this till (D-055).
- A checked restore is applied only after "Restart now" (`Restore.prepare` + `arm`); one never
  confirmed is deleted at the next start. After any restore nobody is signed in and the
  wrong-PIN counts are this phone's (`Restore.Carry`). A restored till that keeps its identity
  skips 2^30 IDs (never meets IDs it handed out after the backup); on the same phone its receipt
  numbers continue after the highest used, on another phone it gets a new receipt prefix (D-055).
  A restored database loses any triggers and views (the app makes none: a doctored backup could
  bring one), and its activity log records who restored which backup (D-056).

## 8. Not built yet (deferred, D-045)

Snapshot bootstrap for very large stores; remote GC of segments every device has read
(`Cursors.deletable` exists); archive of old sales; Drive Changes API (full listing is used).

## 9. Provider interface (as built)

```kotlin
interface SyncProvider {
    val id: String                                    // "gdrive", "folder"
    suspend fun list(prefix: String, since: Long? = null, keep: (RemoteFile) -> Boolean = { true }): List<RemoteFile>
    suspend fun put(name: String, file: File, props: Map<String, String> = emptyMap(), replace: Boolean = false): RemoteFile
    suspend fun get(remote: RemoteFile, dest: File)
    suspend fun delete(remote: RemoteFile)
}
open class AuthNeeded(message: String) : IOException(message)   // provider needs the user
```
`SyncProviders` (the only place that knows it is Drive): `connect()` / `finish(intent)` for the
sync screen, `forId(meta sync.provider)` for background work.

## 10. Google Drive specifics

- Auth: GIS `Identity.getAuthorizationClient(ctx).authorize()` with scope
  `https://www.googleapis.com/auth/drive.appdata` only. Background: silent; a needed
  resolution throws `SignInNeeded` (an `AuthNeeded`) → status "sign-in needed", selling-screen
  pill, "Sign in again" on the sync screen. Account e-mail from `about?fields=user(emailAddress)`.
- All devices of a store use the **same Google account**. Each signing certificate (test key,
  upload key, Play signing key, debug) needs an Android OAuth client in Google Cloud (README).
- REST over `HttpURLConnection`: list with `name contains` + exact prefix filter (optionally
  `createdTime > …`), multipart upload ≤ 5 MB else resumable (resumes from the `Range` Drive
  reports), `alt=media` download, PATCH via `X-HTTP-Method-Override`, one retry with a fresh
  token after 401. Connections are **reused** (a response read to the end is closed, never
  disconnected); 15 s connect / 30 s read timeouts; `ProviderInstaller` once per process for
  modern TLS on API 21. Access tokens are cached per account for 45 min (D-053).
- Round cost (D-053): a segment's first upload skips the "does it exist?" lookup (`fresh`; a
  retry after a crash looks first, marked by `meta sync.upload_try`); the device card is PATCHed
  by its remembered id and re-sent only when it changed or every 15 min; the segment listing asks
  only for files created after the newest one seen minus 15 min (`sync.list_since`, by Drive's
  clock) — the whole folder once a day, on the first round and when a short listing shows a gap.
  A quiet round is ~1 request.
- Scheduling (D-053): in the app, `AutoSync` runs a round 10 s after any committed change that
  queued sync events (`Db.onOutboxCommit`), 5 s after the selling screen opens, 3 s after the
  internet comes back (network callback) and at once for "Sync now"; automatic rounds ≥ 45 s
  apart. WorkManager (`app/Work.kt`) stays as the fallback while the app is closed: periodic
  30 min (network + battery not low, backoff from 30 s) + one-off "sync-soon" 2 min after a
  change (cancelled once the app has synced). `SyncWorker` ends quietly when sign-in is needed.
- Status: `SyncEngine.status` (enabled, running, phase connect/prepare/send/receive/finish with
  done/total, pending events, last OK, last error, needs sign-in, account, till name, other
  tills). "Connecting…" shows the moment a sync is asked for (`starting()`), before waiting for
  Google or a running round. The selling screen shows a pill only for "sign in" or "not synced
  for 24 h with pending changes".

## 11. Backup (works without Google Play Services — D-044)

- `.lekasbak` = ZIP of `backup.json` (format, time, reason, store/device ids, schema, app
  version, store name, sale/product counts) + `lekaspos.db` (+ `-wal`). Made on the writer
  thread after `wal_checkpoint(FULL)` (writes wait for the copy).
- Automatic: daily (`BackupWorker`, keep 7) and before every schema upgrade (keep 3);
  "replaced-*" copies before a restore (keep 3). Manual: back up now, save via SAF, share.
- Restore: staged into `files/restore/`, armed by "Restart now", app restarts, applied in
  `Db.open` before opening.
- Damage (D-055): every open uses `KeepDamagedDatabase` — Android's default handler deleted the
  database on the first SQLITE_CORRUPT. A database too damaged to open moves to
  `files/backups/damaged-<time>.db` and an empty store opens so a backup can be restored; for 7
  days the status shows "Data problem" and automatic backups pause (no rotation). Backup times
  in the future (a clock set back) count as due / not fresh; the backup just written is never
  pruned.
- Data safety (D-048): before each automatic backup `PRAGMA quick_check`; damage → no backup,
  no pruning, `meta dev.db_problem` → "Data problem" pill. The automatic backup is copied to
  the owner's folder (`BackupFolder`: SAF tree with persisted permission; files
  `lekaspos-<date>-<HHmm>.lekasbak`, newest 7, only our own names are listed/deleted).
  `BackupService.protection`: NO_DATA / PROTECTED (a copy off the phone < 3 days old: Drive
  sync `sync.last_ok`, folder `dev.backup_folder_ok`, saved file `dev.backup_export_ok`) /
  AT_RISK ("Not backed up" pill) / DAMAGED. Google login is never required.
- Archive of old sales: not built yet.

## 12. Merge test plan (`androidTest/sync/SyncMergeTest`)

N separate databases sharing a `FolderProvider` folder. Scenarios: second till joins with
existing data (backfill), per-field edits and delete vs edit, a week offline (1,200 sales,
several segments), out-of-order (edit before creation, void before sale) and repeated
delivery, count vs offline sales, credit + settings, restored till rejoining without ID or
receipt collisions, interrupted backfill, a lost phone restored in full on a new one. Every
scenario asserts identical LWW/EVENT tables and derived tables equal to `DerivedRebuild.all`. Test data
must be written through the app's synced paths (e.g. `ProductDao.create`, not the raw bulk `insert`).

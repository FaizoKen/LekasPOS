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
remote physical time is more than 24 h in the future (a device with a wrong clock), which is
not adopted and is reported in sync status. Ties are broken by `device_no`.

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
- Same barcode assigned to two products concurrently → both kept; scan picks the most
  recently changed and the product list flags the duplicate.
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
   is published as an event (`Backfill`, chunks of 300). `meta sync.backfilled = store` marks it
   done; imports are idempotent, so repeating it is harmless.
1. Seal: one write transaction per segment moves ≤ 2,000 outbox rows into
   `files/sync/out/seg-<seq>.ndjson.gz`, records it in `sync_segment`, deletes those rows.
2. Upload every unsent segment in order (`put` with `replace = false`: an already uploaded name
   is left alone after a crash); mark uploaded; local copies deleted after 14 days.
3. Import: list `seg-{store}-*`; for every other device `d` apply `cursor[d]+1, +2, …` in
   order (`Cursors.next`), each downloaded to `cacheDir/sync-in`, verified, and applied in one
   transaction together with `cursor[d] = seq`. A gap stops that device until it appears.
   `hlc.observe(max)` afterwards.
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
  uuid), take a free receipt prefix if ours is used by another card, then backfill + sync.
- Disabling: outbox cleared, backfill flag cleared (re-enabling publishes everything again).
- Restore (D-044): a database that published segments (or had sync on) always gets a **new
  identity** on restore; sync is turned off; the old device's cursor is set to its last sealed
  segment (that data is in the backup), so only what the original did afterwards is imported.

## 8. Not built yet (deferred, D-045)

Snapshot bootstrap for very large stores; remote GC of segments every device has read
(`Cursors.deletable` exists); archive of old sales; Drive Changes API (full listing is used).

## 9. Provider interface (as built)

```kotlin
interface SyncProvider {
    val id: String                                    // "gdrive", "folder"
    suspend fun list(prefix: String): List<RemoteFile>
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
- REST over `HttpURLConnection`: list with `name contains` + exact prefix filter, multipart
  upload ≤ 5 MB else resumable (resumes from the `Range` Drive reports), `alt=media` download,
  PATCH via `X-HTTP-Method-Override`, one retry with a fresh token after 401. 30 s connect /
  60 s read timeouts; `ProviderInstaller` for modern TLS on API 21.
- Scheduling (`app/Work.kt`): periodic 30 min (network + battery not low, exponential backoff
  from 30 s) + one-off "sync-soon" 2 min after a sale (KEEP, at most one enqueue per minute,
  `AppGraph.syncSoon`) + "Sync now". `SyncWorker` ends quietly when sign-in is needed.
- Status: `SyncEngine.status` (enabled, running, phase prepare/sync, records prepared, pending
  events, last OK, last error, needs sign-in, account, till name, other tills). The selling
  screen shows a pill only for "sign in" or "not synced for 24 h with pending changes".

## 11. Backup (works without Google Play Services — D-044)

- `.lekasbak` = ZIP of `backup.json` (format, time, reason, store/device ids, schema, app
  version, store name, sale/product counts) + `lekaspos.db` (+ `-wal`). Made on the writer
  thread after `wal_checkpoint(FULL)` (writes wait for the copy).
- Automatic: daily (`BackupWorker`, keep 7) and before every schema upgrade (keep 3);
  "replaced-*" copies before a restore (keep 3). Manual: back up now, save via SAF, share.
- Restore: staged into `files/restore/`, app restarts, applied in `Db.open` before opening.
- Archive of old sales: not built yet.

## 12. Merge test plan (`androidTest/sync/SyncMergeTest`)

N separate databases sharing a `FolderProvider` folder. Scenarios: second till joins with
existing data (backfill), per-field edits and delete vs edit, a week offline (1,200 sales,
several segments), out-of-order (edit before creation, void before sale) and repeated
delivery, count vs offline sales, credit + settings, restored till rejoining without ID or
receipt collisions, interrupted backfill. Every scenario asserts identical LWW/EVENT tables
and derived tables equal to `DerivedRebuild.all`. Test data must be written through the app's
synced paths (e.g. `ProductDao.create`, not the raw bulk `insert`).

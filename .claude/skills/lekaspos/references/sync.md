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

## 5. Files

Remote layout (Google Drive `appDataFolder`, flat names; `appProperties` carry the same
fields for queries):

| File | Written by | Mutable? |
|---|---|---|
| `store-{store}.json` — store manifest | creating device | never rewritten |
| `dev-{store}-{dev}.json` — device card (name, app version, last seen, import vector) | that device | rewritten only by its owner |
| `seg-{store}-{dev}-{seq:08}.ndjson.gz` — events | that device | immutable |
| `snap-{store}-{dev}-{seq:06}.db.gz` + `.json` manifest (vector clock) | any device | immutable |
| `arch-{store}-{dev}-{yyyymm}.ndjson.gz` — archived sales | that device | immutable |
| `blob-{store}-{sha256}` — logo and other binaries | any device | immutable |

Segment = gzip NDJSON: header line `{v, store, dev, seq, count, firstHlc, lastHlc}` then one
event per line. Integrity: `count` and SHA-256 (stored in `appProperties`) are verified before
applying; a bad file is re-downloaded, never half-applied. Streaming read/write only.

## 6. Upload (per device)

1. Seal: one write transaction moves outbox rows `> lastSealed` into a new local segment file
   (fsynced first), records it in `sync_segment`, deletes those outbox rows.
2. Upload every unsent segment (multipart ≤ 5 MB, resumable above; session URI persisted
   so a broken upload resumes at the last acknowledged byte). Before re-uploading after a
   crash, look the name up first to avoid duplicates (importers also de-duplicate).
3. Mark uploaded; keep local copies 14 days, then delete.

## 7. Import (per provider)

1. List what's new (Drive Changes API page token; full listing as fallback).
2. For every other device `d`: apply segments `cursor[d]+1, +2, …` in order, each in one
   transaction together with `cursor[d] = seq` (crash-safe, exactly-once effect).
   A gap (missing seq) stops that device's stream until it appears.
3. If a needed segment was garbage-collected, bootstrap from the newest snapshot instead.
4. Advance the HLC past the highest imported HLC.

## 8. Snapshots, bootstrap, garbage collection

- Snapshot = a compact SQLite file with all LWW, EVENT and DERIVED tables (no LOCAL tables),
  built by streaming rows from one read transaction (WAL: selling continues) into a new DB
  file, then gzip. Its manifest has the vector clock `{dev → last seq included}`.
- Taken when idle/charging, at most daily, only if enough changed.
- **New device:** download newest snapshot → it becomes the local DB (LOCAL tables reset,
  new identity) → import segments after the vector → ready.
- **Merge a standalone device into a store:** upload its own snapshot; others merge it row by
  row with the same LWW/insert-or-ignore rules (a snapshot is just a big batch of events).
- **GC:** a device deletes its own segment `(d, s)` once a snapshot covers it and every
  device seen in the last 90 days has imported it (from the device cards).

## 9. Provider interface

```kotlin
interface SyncProvider {
    val id: String                                   // "gdrive", "folder", later "server"
    suspend fun state(): ProviderState               // ready / needs sign-in / offline / error
    suspend fun putDeviceCard(store: String, card: DeviceCard)
    suspend fun listDeviceCards(store: String): List<DeviceCard>
    suspend fun listSegments(store: String, since: ListCursor?): SegmentListing
    suspend fun uploadSegment(store: String, meta: SegmentMeta, file: File, progress: ResumeState)
    suspend fun downloadSegment(ref: RemoteSegment, dest: File, progress: ResumeState)
    suspend fun deleteOwnSegments(store: String, refs: List<RemoteSegment>)
    suspend fun uploadSnapshot(store: String, meta: SnapshotMeta, file: File, progress: ResumeState)
    suspend fun latestSnapshot(store: String): RemoteSnapshot?
    suspend fun downloadSnapshot(ref: RemoteSnapshot, dest: File, progress: ResumeState)
    suspend fun uploadArchive(store: String, meta: ArchiveMeta, file: File, progress: ResumeState)
}
```
Several providers may be enabled at once (e.g. Drive + own server later): the engine keeps
cursors and upload flags per provider; merges are idempotent, so duplicates are harmless.

## 10. Google Drive specifics

- Auth: Google Identity Services `Identity.getAuthorizationClient(ctx).authorize()` with
  scope `https://www.googleapis.com/auth/drive.appdata` only (non-sensitive → no paid
  security assessment). Background jobs call `authorize()` silently; if it needs UI, status
  becomes "Sign-in needed" and the user taps to fix. No ID token needed; the account email
  is shown from `about.get?fields=user`.
- All devices of a store sign in with the **same Google account** (appDataFolder is private
  per account + app).
- REST over `HttpURLConnection`, gzip, 30 s connect / 60 s read timeouts, `ProviderInstaller`
  on API 21 for modern TLS. Retries: WorkManager exponential backoff (30 s → 5 h).
- Scheduling: periodic every 30 min (network connected, battery not low) + an expedited-ish
  one-off 2 min after the last sale (debounced) + manual "Sync now".
- Status shown in the app bar: last successful sync time, pending events, last error.

## 11. Backup and archive (work without Google Play Services)

- **Automatic local backup** daily (and before migrations/restores): consistent copy made
  like a snapshot (all tables incl. LOCAL + identity), gzip, `files/backups/`, keep 7.
- **Manual export/import**: the same `.lekasbak` file via the Storage Access Framework
  (USB, SD card, Downloads, any cloud app). Import asks: replace this device (take over its
  identity) or restore as a new device.
- **Archive**: sales older than N months → monthly archive files to Drive (and/or export),
  then purged locally; `sum_*` tables keep reports working; a stock checkpoint preserves
  stock levels.

## 12. Merge test plan (Phase 6)

Pure merge rules in `:core` (JVM tests) + multi-device scenarios in instrumented tests using
N separate databases and a `FolderProvider`: concurrent same-field edits, different-field
edits, delete vs edit, offline device with a week of sales, out-of-order and duplicate
segment delivery, stock count vs offline sales, clock skew, snapshot bootstrap + catch-up,
interrupted upload/download resume. Every scenario asserts all devices converge to
identical table contents.

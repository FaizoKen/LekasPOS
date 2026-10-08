# Code map — where everything is

The *where is it* map of the code: every source file with its purpose, the central APIs, every screen, background
job, test, script and stable code. Verified against commit `a75b0a3` (2026-10-08, versionName `1.13.0`, schema v9);
line numbers drift — search for the name when a line does not match. Anything not checked line by line is marked
**UNVERIFIED**. Keep it current: a new file, screen, worker, test class or stable code is added here in the same change.

Path abbreviations used throughout:

| Short | Real path |
|---|---|
| `C/` | `core/src/main/kotlin/com/lekaspos/core/` |
| `A/` | `app/src/main/java/com/lekaspos/` |
| `CT/` | `core/src/test/kotlin/com/lekaspos/core/` |
| `UT/` | `app/src/test/java/com/lekaspos/` |
| `AT/` | `app/src/androidTest/java/com/lekaspos/` |
| `RES/` | `app/src/main/res/` |

File counts: `:core` main 50, `:app` main 162, `:core` JVM tests 39, `:app` JVM tests 14, instrumented tests 54 (319 `.kt` files).
Not repeated here (already documented): the rules in `CLAUDE.md`, `.claude/skills/lekaspos/SKILL.md`,
`references/{architecture,database,money,sync,performance}.md`, `docs/BUILD.md`. This file is the *where is it* map.

---

## 1. Layering in one table

| Layer | Package(s) | May call | Notes |
|---|---|---|---|
| UI | `com.lekaspos.ui.*` | domain services via `graph.*`, DAOs inside `graph.db().read{}`/`write{}` | Some simple CRUD screens write DAOs directly (`CategoriesActivity`, `TaxRatesActivity`, `PaymentMethodsActivity`, `SuppliersActivity`, `ProductEditActivity`, `PrinterSettingsActivity`, `SetupActivity` — see §5). |
| Domain | `com.lekaspos.domain.*` | DAOs, `:core`, `hw`, `sync` | App-scoped services, created lazily in `AppGraph`. Check permissions themselves (`graph.permissions.actor(...)`). |
| Data | `com.lekaspos.data.*` | `:core` only | The only place with SQL (plus `perf`). `Db`/`Db.Tx` is the only SQLite entry point. |
| HW | `com.lekaspos.hw.*` | data, `:core` | Printer thread, Bluetooth SPP, camera decoder. |
| Sync | `com.lekaspos.sync.*` | data, `:core` | Only package that knows Google Drive. |
| App | `com.lekaspos.app` | everything | `LekasApp`, `AppGraph`, WorkManager workers, updates, error reports. |
| Core | `com.lekaspos.core.*` | nothing Android | Pure Kotlin/JVM, Java 8 API (`-Xjdk-release=1.8`). |

---

## 2. `:core` — `core/src/main/kotlin/com/lekaspos/core/` (pure Kotlin, JVM-tested)

Business rules shared by app and tests. No Android imports, no I/O. Compiled against the Java 8 API
(`core/build.gradle.kts`), and `:app` lint runs with `checkDependencies = true`, so Java 8 calls missing
below API 24 (`Map.getOrDefault`, streams…) fail lint.

### 2.1 `core.barcode`
Retail barcode maths: GS1 check digits, UPC-E/A/EAN variants for lookup, configurable scale labels.

| File | Main declarations | Purpose |
|---|---|---|
| `C/barcode/Gtin.kt` | `object Gtin` (`checkDigit`, `isValid`, `lookupVariants`, `canonical`, `upcEToUpcA`, `upcAToUpcE`, `restoreLeadingZero`, `withoutLeadingZero`, `asciiDigits`) | GS1 barcodes; stored barcodes are `Gtin.canonical`, scans try `lookupVariants` (exact code first). |
| `C/barcode/ScaleTemplate.kt` | `class ScaleTemplate(pattern)`, `data class ScaleCode(itemCode, weightMilli, priceMinor)` | In-store scale label layouts like `20IIIIIWWWWWC` (money.md §7). |

### 2.2 `core.cart`
The open bill's in-memory model.

| File | Main declarations | Purpose |
|---|---|---|
| `C/cart/Cart.kt` | `data class CartItem`, `data class CartAdd`, `data class Cart` (`price(inclTax, promotions)`) | Bill lines (qty in milli of the selling unit, `packQty`, `baseQty`), merging, pricing entry point. |

### 2.3 `core.credit`
| File | Main declarations | Purpose |
|---|---|---|
| `C/credit/CreditMath.kt` | `object CreditMath` (`delta`, `overLimit`) | Customer credit balance = Σ entry deltas; CHARGE negative = reversal (D-039). |

### 2.4 `core.csv`
Streaming CSV in/out and the product CSV format (D-041, D-042).

| File | Main declarations | Purpose |
|---|---|---|
| `C/csv/Csv.kt` | `class CsvWriter` (`BOM`, `row`, `rows`), `class CsvReader` (`Reason` enum, `MAX_FIELD/MAX_RECORD/MAX_FIELDS`) | RFC 4180 writer (CRLF, BOM) and bounded streaming reader with delimiter detection. |
| `C/csv/CsvInput.kt` | `object CsvInput` | Sniffs an imported file's bytes (xlsx/xls refusal, UTF-16, UTF-8 vs Windows-1252). |
| `C/csv/ImportProgress.kt` | `data class ImportProgress` | Resume point of a product import, stored in `meta` (`import.products.progress`). |
| `C/csv/ProductCsv.kt` | `object ProductCsv` (`enum Column`, `enum Problem`, `Row`, `COLUMNS`, `format`, `EXAMPLE_PREFIX`, `MAX_*`) | Product CSV columns, header aliases (EN/MS), row validation. |

### 2.5 `core.diag`
| File | Main declarations | Purpose |
|---|---|---|
| `C/diag/CrashText.kt` | `object CrashText` (`scrub`, fingerprint, title; `APP_PACKAGE`) | Fingerprints and scrubs stack traces for error reports (D-057). |
| `C/diag/ErrorReport.kt` | `data class ErrorReport` (kinds `crash/anr/native/killed/error/check/manual`, `MAX_*`) | One report's fields as sent to the relay. |

### 2.6 `core.display`
| File | Main declarations | Purpose |
|---|---|---|
| `C/display/CustomerView.kt` | `data class CustomerLine`, `sealed class CustomerView` (Welcome/Bill/Thanks; `THANKS_MS`) | What the second-display customer screen shows (D-069). |

### 2.7 `core.escpos`
| File | Main declarations | Purpose |
|---|---|---|
| `C/escpos/EscPos.kt` | `enum TextMode {LATIN, GB18030}`, `DrawerPulse`, `PrinterProfile`, `class EscPos`, `object EscPosText`, `object ReceiptEncoder` | ESC/POS command builder, text encoding, receipt → bytes. |
| `C/escpos/MonoImage.kt` | `class MonoImage` | 1-bit raster image in ESC/POS layout. |

### 2.8 `core.id`
| File | Main declarations | Purpose |
|---|---|---|
| `C/id/Ids.kt` | `object Ids` (`DEVICE_BITS=22`, `SEQ_BITS=41`, `make`, `randomDeviceNo`), `class IdAllocator` (`ensure`, `nextId`, `remaining`, `ReservationStore`) | 63-bit ids `(device_no shl 41) or seq`; `nextId()` throws if not reserved (`check(next <= limit)`, Ids.kt:79). |

### 2.9 `core.inventory`
| File | Main declarations | Purpose |
|---|---|---|
| `C/inventory/AdjustReason.kt` | `enum AdjustReason(code, kind, direction)` | Manual adjustment reasons stored as text codes in `stock_movement.reason`. |
| `C/inventory/CostMath.kt` | `object CostMath` | Moving weighted-average cost (D-033), stock values. |
| `C/inventory/ReceiveDraft.kt` | `data class ReceiveLine`, `data class ReceiveDraft` (`add`, token) | A delivery being received; saved as a draft in `meta`. |
| `C/inventory/StockTimeline.kt` | `sealed class StockEvent`, `object StockTimeline` | Stock history rows with running level. |

### 2.10 `core.model`
| File | Main declarations | Purpose |
|---|---|---|
| `C/model/Codes.kt` | `SaleKind`, `SaleStatus`, `PaymentKind`, `SellMode`, `BarcodeKind`, `MovementKind`, `CashMoveKind`, `CreditKind`, `DiscountKind`, `CartStatus`, `PrintJobStatus`, `SysRole`, `AuditAction`, `Perm`, `CountSessionStatus`, `PrintJobKind`, `PromoKind`, `Entity`, `TileColor`, `EventOp` | **All stable integer codes** (see §9). Never renumber. |

### 2.11 `core.money`
| File | Main declarations | Purpose |
|---|---|---|
| `C/money/Checked.kt` | `object Checked` (`add`, `sub`, `mul`, `floorMod`) | Overflow-checked Long maths (no `Math.*Exact`: API 24). |
| `C/money/CurrencySpec.kt` | `data class CurrencySpec` (`MYR`, `symbol`, `decimals`, `cashStep`) | Store currency format/rounding (from settings, never device locale). |
| `C/money/Money.kt` | `value class Money`, `value class Qty` (`SCALE = 1000`) | Typed minor units / milli-units. |
| `C/money/MoneyFormat.kt` | `object MoneyFormat` (`format`, `plain`, `parse`, `parsePlain`, `keypad`, `formatQty`, `parseQty`) | Exact formatting/parsing, never via Double. |
| `C/money/Rounding.kt` | `object Rounding` (`roundHalfUp`, `mulDivHalfUp`, `toStep`, `allocate`) | The only rounding rules (half-up, largest remainder). |

### 2.12 `core.pricing`
| File | Main declarations | Purpose |
|---|---|---|
| `C/pricing/Pricing.kt` | `sealed class Discount {None, Amount(minor), Percent(bp)}`, `PriceLine`, `PricedLine`, `TaxGroup`, `PricedCart`, `object PricingEngine` (`price`) | Bill totals, discounts, tax groups (money.md §3). |
| `C/pricing/Promotions.kt` | `Promotion`, `PromoLine`, `AppliedPromo`, `object Promotions` (`apply`) | "N for X" / "buy X get Y" savings (money.md §11). |
| `C/pricing/QuickCash.kt` | `object QuickCash` | One-tap cash note buttons. |
| `C/pricing/Settlement.kt` | `object Settlement` (`cashDue`, `cash`, `exact`, `cashRefund`, `Result {Settled, Partial, Rejected}`, `Reason`) | Tenders, change, cash rounding. |

### 2.13 `core.receipt`
| File | Main declarations | Purpose |
|---|---|---|
| `C/receipt/Receipt.kt` | `StoreInfo`, `ReceiptItem`, `ReceiptTax`, `ReceiptPayment`, `ReceiptDoc`, `ReceiptText` | Receipt document model (built from the stored sale). |
| `C/receipt/ReceiptLayout.kt` | `sealed class PrintLine`, `class ReceiptLayout` (`MIN_COLS=24`, `MAX_COLS=64`, `percent`) | Lays a receipt out on a 32/42/48-column grid (CJK = 2 cols). |

### 2.14 `core.refund`
| File | Main declarations | Purpose |
|---|---|---|
| `C/refund/Refunds.kt` | `RefundSource`, `RefundPart`, `object Refunds` | What can still be returned per line; refund amounts = net per unit (money.md §6). |

### 2.15 `core.report`
| File | Main declarations | Purpose |
|---|---|---|
| `C/report/MonthFiles.kt` | `object MonthFiles` (`due`, `REFRESH_DAYS=3`, `MAX_MONTHS=13`) | Which monthly CSVs the Drive daily report must (re)write (D-065). |
| `C/report/Periods.kt` | `data class Period(from,to)`, `enum Preset`, `object Months`, `enum Granularity`, `DayTotals`, `Bucket`, `object Buckets`, `object ReportMath` | Calendar maths for reports (local epoch days, weeks start Monday). |
| `C/report/RangePlan.kt` | `object RangePlan` (`enum Kind {DAYS, MONTH, YEAR}`, `Piece`, `MONTH_COST`, `YEAR_COST`) | Splits a period into year/month/day summary reads (D-058). |

### 2.16 `core.scan`
| File | Main declarations | Purpose |
|---|---|---|
| `C/scan/ScanBuffer.kt` | `class ScanBuffer` (`IDLE_MS=250`, `MAX_LENGTH=128`) | Tells a keyboard-wedge scanner burst from human typing. |

### 2.17 `core.shift`
| File | Main declarations | Purpose |
|---|---|---|
| `C/shift/CashCount.kt` | `object CashCount` | Drawer counted note by note. |
| `C/shift/ShiftGuide.kt` | `object ShiftGuide` (`enum Ask {NONE, OPEN, NEW_DAY, HANDOVER}`, `ask`), `data class LeftInDrawer` (`encode/decode` "amount\|staffId\|at") | What the till asks about the shift (D-068). |
| `C/shift/ShiftReport.kt` | `ShiftCash`, `MethodTotal`, `ShiftReport`, `ReportRow`, `ReportSection`, `ShiftText`, `class ShiftReportLayout` | Shift report maths and printable layout (D-038). |

### 2.18 `core.staff`
| File | Main declarations | Purpose |
|---|---|---|
| `C/staff/PinHash.kt` | `object PinHash` (PBKDF2 "p2:iter:salt:hash", `ITERATIONS=4000`, `MIN/MAX_LENGTH`), `object PinLockout` (`FREE_TRIES=5`, 30 s→15 min), `object RecoveryCode` (12 chars) | PIN storage, wrong-PIN waits, owner recovery code (D-037). |
| `C/staff/StaffChecks.kt` | `Tally`, `ActionTotal`, `data class Checks` (`of`, `ACTIONS`, `warning`), `data class StaffCheck` (`rank`) | Folds audit-log totals into "what the owner should look at" (D-067). |

### 2.19 `core.sync`
| File | Main declarations | Purpose |
|---|---|---|
| `C/sync/Lww.kt` | `data class Version(hlc, dev)`, `class FieldVersions` (`decode/encode/of/with`), `object Lww` (`winningFields`, `stampAbove`) | Per-field LWW decisions; `fver` codec. |
| `C/sync/SyncNames.kt` | `object SyncNames` (`STORE_PREFIX`, `SEGMENT_EXT=".ndjson.gz"`, file names), `object Cursors` (`next`, `deletable`) | Remote file naming and import cursors. |

### 2.20 `core.text`
| File | Main declarations | Purpose |
|---|---|---|
| `C/text/DigitEntry.kt` | `class DigitEntry(maxDigits)` (`put`, `press`, `replacing`, `DELETE="DEL"`) | Number-pad state: digits from the right; preset shown grey, replaced by first key. |
| `C/text/SearchText.kt` | `object SearchText` (`normalize`, `tokens`, `ftsQuery`, `key`, `prefixUpperBound`, `isCjkChar`, `MAX_QUERY_TOKENS=6`) | Normalization for FTS4 / `*_key` columns. |
| `C/text/TextWidth.kt` | `object TextWidth` | Fixed-pitch column widths (CJK = 2). |

### 2.21 `core.time`
| File | Main declarations | Purpose |
|---|---|---|
| `C/time/ClockCheck.kt` | `object ClockCheck` (`NOT_BEFORE`, `enum Verdict`) | Refuses to date a sale with an obviously wrong clock. |
| `C/time/DateText.kt` | `object DateText` (`dateTime`, `isoDate`, `time`) | Locale-independent date text. |
| `C/time/Days.kt` | `object Days` (`epochDay`, `startOfDay`, `toYmd`, `fromYmd`, `weekStart`, `monthStart`, `DAY_MS`) | Business-day arithmetic without `java.time`. |
| `C/time/Hlc.kt` | `class Hlc` (`now`, `observe`, `current`, `pack`, `MAX_FUTURE_MS=24h`) | Hybrid logical clock. |

### 2.22 `core.update`
| File | Main declarations | Purpose |
|---|---|---|
| `C/update/Releases.kt` | `object Releases` (`APK_NAME="LekasPOS.apk"`, `MAX_APK_BYTES`), `data class Update`, `object ReleaseNotes` (reads `### What's new` / `### Apa yang baharu`) | Picks the GitHub release that updates the app (D-059). |

---

## 3. `:app` — `app/src/main/java/com/lekaspos/`

### 3.1 `com.lekaspos.app` — process, DI, background jobs, updates, error reports
Process entry point and app-wide singletons. `LekasApp.onCreate` does no disk I/O: it installs the error log,
error reports, StrictMode (debug), creates `AppGraph`, and registers the customer display. WorkManager is
initialised on demand (`Configuration.Provider`).

| File | Main declarations | Purpose |
|---|---|---|
| `A/app/LekasApp.kt` | `class LekasApp : Application, Configuration.Provider` (`graph`, `companion graph(ctx)`) | Application; `attachBaseContext(AppLanguage.wrap)`; `onTrimMemory` trims pictures; WorkManager config with init/scheduling exception handlers. |
| `A/app/AppGraph.kt` | `class AppGraph(app, dbName = Schema.FILE_NAME)` | Manual DI (see §4.1). |
| `A/app/Work.kt` | `object Work`, `BackupWorker`, `ReportWorker`, `UpdateWorker`, `DailyReportWorker`, `SyncWorker` | Every WorkManager job (see §7). |
| `A/app/AppLanguage.kt` | `object AppLanguage` (`PHONE=""`, `ENGLISH="en"`, `MALAY="ms"`, `get`, `set`, `screens`, `wrap`, `inLanguage`) | Screen language in prefs file `lekas_ui` (D-046). |
| `A/app/AppUpdates.kt` | `class AppUpdates` (`Status`, `Problem`, `load`, `check`, `download`, `installIntent`, `background`, `atStart`, `setAutomatic`, `setTestVersions`), `internal object ReleaseJson` | Self-update from GitHub releases; prefs file `lekas_updates` (D-059). |
| `A/app/ErrorReports.kt` | `object ErrorReports` (`URL`, `UNASKED/ON/OFF`, `Outcome`, `init`, `consent`, `setConsent`, `atStart`, `check`, `sendByHand`, `sendPending`, `later`, `expected`) | Crash/`Log.e`/ANR reports to the relay, consent in prefs `lekas_reports` (D-057). |
| `A/app/PublicTrust.kt` | `internal object PublicTrust` (`sockets`) | Bundled CA roots for HTTPS to GitHub and the relay on old Android. |

### 3.2 `com.lekaspos.data.db` — the database
Opening, schema, migrations, seeds, SQL helpers, damage handling, derived rebuilds.

| File | Main declarations | Purpose |
|---|---|---|
| `A/data/db/Db.kt` | `class Db` (+ `class Db.Tx`, `companion open/assertNotMainThread`, `MetaReservations`) | The only SQLite entry point (see §4.2). |
| `A/data/db/DbOpenHelper.kt` | `class DbOpenHelper : SQLiteOpenHelper` | WAL; `onCreate` = `Schema.STATEMENTS` + `Seed.insert` + `Meta.createIdentity`; `onUpgrade` = `Migrations.migrate`; `onDowngrade` throws; `onOpen` turns FKs on + `synchronous=FULL`, `cache_size=-4000`, `journal_size_limit`. |
| `A/data/db/Schema.kt` | `object Schema` (`VERSION = 9` at :11, `FILE_NAME="lekaspos.db"`, `STATEMENTS`, `LWW_TABLES`, `EVENT_TABLES`, `DERIVED_TABLES`, `LOCAL_TABLES`, `GENERATED_ID_TABLES`, `normalize`; DDL constants `SUM_YEAR_PRODUCT`, `SHIFT_OPEN_INDEX`, `SUM_CATEGORY`, `SUM_PRODUCT_INDEXES`, `PROMOTION`, `PRODUCT_LOOK`, `PRODUCT_IMAGE`, `SYNC_DEFERRED`) | Authoritative DDL of the current version; DDL needed by a migration is a named constant reused in `STATEMENTS`. |
| `A/data/db/Migrations.kt` | `class Migration(from, to, migrate)`, `object Migrations` (`ALL` :17, `migrate`) | v1→v9 steps (one version at a time, `require(to == from + 1)`). |
| `A/data/db/Seed.kt` | `data class SeedNames`, `object Seed` (`Ids.ROLE_OWNER=1/MANAGER=2/CASHIER=3`, `PM_CASH=1/CARD=2/EWALLET=3/CREDIT=4`, `STAFF_OWNER=1`; `insert`, `renameUntouched`, `ensureOwner`) | Seed rows at version (0,0) with device_no 0 ids. |
| `A/data/db/Meta.kt` | `object Meta` (key constants, `get/getLong/put/increment/createIdentity/docSeqKey`) | LOCAL key/value store (`meta`). |
| `A/data/db/Sql.kt` | top-level `args`, `queryList`, `queryOne`, `longOrNull`, `long`, `stringOrNull`, `pragma`, `SQLiteStatement.bindAll`, `Cursor.longOrNull/stringOrNull/bool` | DAO helpers; every cursor closed with `use`. `rawQuery` binds strings only on API 21. |
| `A/data/db/DerivedRebuild.kt` | `object DerivedRebuild` (`summaries`, `refundedAmounts`, `saleStatus`, `stockLevels`, `customerBalances`, `all`, `MAINTENANCE_QUERIES`) | Set-based rebuild of every DERIVED table; must equal the incremental paths. |
| `A/data/db/KeepDamagedDatabase.kt` | `object KeepDamagedDatabase : DatabaseErrorHandler` (`setAside`, `checkSetAside`, `problem`, `storePath`) | Never deletes a corrupt DB; moves it to `files/backups/damaged-<time>.db` (D-055). |

### 3.3 `com.lekaspos.data.*` — DAOs by aggregate
Each DAO is a Kotlin `object` with constant SQL, `read` functions taking `SQLiteDatabase`, and write functions taking
`Db.Tx`. LWW writes go through `LwwWriter`; EVENT writes append an `Outbox` row when `tx.syncEnabled`. DAOs with
hot queries expose `HOT_QUERIES: List<Pair<name, sql>>` registered in `perf/QueryPlans.hotQueries()`.

| File | Main declarations | Purpose |
|---|---|---|
| `A/data/audit/AuditDao.kt` | `data class AuditRow`, `object AuditDao` (`log` :35, `recent`, `byAction`, `countByAction`, `totalsByStaff`, `totalsOfTill`, `exists`, `existsWithDetail`, `HOT_QUERIES`) | EVENT `audit_log`; `log` appends `Entity.AUDIT` outbox. |
| `A/data/backup/BackupFiles.kt` | `object BackupFiles` (`EXT=".lekasbak"`, `Header`, `Invalid(kind)`, `write`, `writeClosed`, `integrity`, `readHeader`, `unpack`, `unpackedDb`) | The backup ZIP format (D-044). |
| `A/data/backup/BackupFolder.kt` | `interface BackupFolder`, `class FileBackupFolder`, `class TreeBackupFolder` | Daily copy to an SAF tree / folder (D-048). |
| `A/data/backup/Restore.kt` | `object Restore` (`Mode {REPLACE, NEW_DEVICE}`, `stage`, `prepare`, `arm`, `cancelStaged`, `applyIfStaged`, `afterOpen`, `renewIfAsked`, `newIdentity`, `Carry`, `Who`, `takeAudit`, `backupDir`) | Staged restore applied in `Db.open` before opening. |
| `A/data/cart/CartDao.kt` | `CartLine`, `StoredCart`, `HeldCartRow`, `object CartDao` (`insertCart`, `putLine`, `deleteLine(s)`, `setBillDiscount`, `setCustomer`, `setPayShown`, `deleteCart`, `setStatus`, `rewriteOpen`, `parkOpen`, `loadOpen`, `load`, `held`, `heldCount`, `maxIds`) | LOCAL `cart`/`cart_line` persistence of open/held bills. |
| `A/data/catalog/CatalogDao.kt` | `Category`, `TaxRate`, `PaymentMethod`, `PaymentMethodRow`; `object CategoryDao`, `object TaxRateDao`, `object PaymentMethodDao` | LWW categories, tax rates, payment methods. |
| `A/data/customer/CustomerDao.kt` | `Customer`, `CustomerItem`, `CreditEntry`, `object CustomerDao` (`page`, `byPhone`, `phoneForms`, `get`, `getLive`, `balance`, `insert/update/delete`, `insertCredit`, `applyBalance`, `statement`, `BALANCES`, `REBUILD_BALANCES`, `HOT_QUERIES`) | LWW `customer`, EVENT `credit_entry` (`Entity.CREDIT`), DERIVED `customer_balance`. |
| `A/data/print/PrintJobDao.kt` | `data class PrintJob`, `object PrintJobDao` (`enqueue`, `next`, `hasReceipt`, `expireReceipts`, `markDone/Attempt/Failed`, `cancelPending`, `purge`, `pendingCount`) | LOCAL persistent print queue (drawer pulses first). |
| `A/data/product/ProductDao.kt` | `Product`, `Barcode`, `SellableProduct`, `ScanHit`, `ProductListItem`, `ExportRow`; `object ProductDao` (`insert` (raw, no outbox — perf/tests only), `create` (LWW), `update(tx, shown, edited, current, now)`, `delete`, barcode CRUD, `findByCode`, `sellableById`, `search`, `byCategory`, `managePage`, `sellPage`, `popularIds`, `listByIds`, `exportPage`, `owners/ownerOf/bySku/codeOwners`, `reindex`, `ftsBody`, `FTS_CANDIDATES=2000`, `HOT_QUERIES`) | LWW `product`/`product_barcode` + DERIVED `product_fts`. |
| `A/data/product/ProductLookDao.kt` | `data class ProductLook(color, imageId)`, `object ProductLookDao` (`get`, `update(tx, id, shown, edited, now)`, `addImage`, `imageData`) | LWW `product_look` + EVENT `product_image` (D-066). |
| `A/data/promo/PromotionDao.kt` | `data class PromotionRow` (`runsOn`), `object PromotionDao` (`list`, `get`, `insert`, `update`, `delete`, `parseProducts`) | LWW `promotion`. |
| `A/data/purchase/PurchaseDao.kt` | `PurchaseLineIn`, `PurchaseIn`, `PurchaseRow`, `PurchaseLineRow`, `object PurchaseDao` (`commit`, `page`, `get`, `lines`, `HOT_QUERIES`) | EVENT `purchase`/`purchase_line` (+RECEIVE movements), `Entity.PURCHASE`. |
| `A/data/report/ReportDao.kt` | `Totals`, `PaymentTotal`, `ProductTotal`, `StaffTotal`, `CategoryTotal`, `SlowMover`, `StockValue`, `ReceiptRow`; `object ReportDao` (`totals`, `days`, `byPayment`, `byStaff`, `summary`, `products`, `byCategory`, `eachProduct`, `slowMovers`, `stockValue`, `receipts`, `receiptsOfDays`, `HOT_QUERIES`) | Report reads from `sum_*` tables. |
| `A/data/report/SummaryRange.kt` | `object SummaryRange` (`plan`, `all`, `qty`, `categories`, `HAS_SALES`) | Builds the UNION of year/month/day summary pieces for a period. |
| `A/data/sale/ReceiptNumbers.kt` | `object ReceiptNumbers` (`defaultPrefix`, `prefix`, `format`, `candidates`, `isNumber`, `prefixesOf`) | Per-device receipt numbers `{prefix}{R?}{seq:06}`. |
| `A/data/sale/SaleDao.kt` | `object SaleDao` (`commit` :59, `void` :142, `recomputeRefunded`, `applyRemote`, `applyRemoteVoid`, `exportRows`, `history`, `byReceipt`, `lines`, `productHistory`, `lastOwnSoldAt`, `isVoided`, `HOT_QUERIES`) | The complete-sale transaction (lines, payments, stock, summaries, outbox `Entity.SALE`) and voids (`Entity.SALE_VOID`). |
| `A/data/sale/SaleModels.kt` | `SaleDraft`, `SaleLineDraft`, `PaymentDraft`, `CommittedSale`, `SaleRow`, `SaleLineRow`, `ProductSaleRow` | Value objects for sales. |
| `A/data/sale/SaleQueries.kt` | `SaleHeader`, `SaleLineFull`, `PaymentRow`, `object SaleQueries` (`header`, `lines`, `payments`, `refundedByLine`, `refundsOf`, `receiptNo`, `soldByWeight`, `HOT_QUERIES`) | Read side of one sale (detail, refund). |
| `A/data/sale/Summaries.kt` | `object Summaries` (`Input`, `LineSum`, `PaySum`, `apply(tx, input, sign, voided)`, `recategorize`, `MONTHS_FROM_DAYS`, `YEARS_FROM_MONTHS`, `CATEGORIES_FROM_PRODUCTS`, `HOT_QUERIES`) | Incremental maintenance of every `sum_*` table. |
| `A/data/settings/Settings.kt` | `object SettingKeys`, `data class StoreSettings` (`toMap`, `from`), `object SettingsDao` (`all`, `put` :173, `putChanged`), `data class DeviceSettings` (`load`, `save`, `dev.*` keys) | Store settings (LWW `setting`) and per-till `meta dev.*` settings. |
| `A/data/shift/ShiftDao.kt` | `Shift`, `DocSum`, `MethodSum`, `CreditSum`, `ShiftTotals`, `CashMove`, `object ShiftDao` (`current`, `get`, `page`, `open`, `close`, `totals`, `insertMove`, `moves`, `overShortByOpener`, `HOT_QUERIES`) | LWW `shift`, EVENT `cash_movement` (`Entity.CASH_MOVE`). |
| `A/data/staff/StaffDao.kt` | `Role` (`effective`, `isOwner`), `Staff` (`perms`, `canSignIn`), `object StaffDao`, `object RoleDao` | LWW `staff`, `role`. |
| `A/data/stock/CountSessionDao.kt` | `CountSession`, `CountRow`, `CountSummary`, `object CountSessionDao` (`list`, `get`, `create`, `finish`, `counts`, `summary`, `countedQty`, `HOT_QUERIES`) | LWW `count_session` + its `stock_count` rows. |
| `A/data/stock/StockDao.kt` | `LowStockItem`, `MovementRow`, `object StockDao` (`applyDelta`, `level`, `stampAfterCounts`, `stampAfterEverything`, `levelBefore`, `countImported`, `rebuild`, `lowStock*`, `lowAmong`, `insertMovement`, `insertMovementRowIfNew` (internal), `movementPage`, `insertCount`, `HOT_QUERIES`) | EVENT `stock_movement`/`stock_count`, DERIVED `stock_level`. |
| `A/data/stock/StockHistoryDao.kt` | `HistoryEntry` (`Type`), `object StockHistoryDao` (`page`) | One product's merged stock history. |
| `A/data/supplier/SupplierDao.kt` | `Supplier`, `object SupplierDao` | LWW `supplier`. |
| `A/data/sync/Outbox.kt` | `object Outbox` (`append` :14, `json`, `writeRow`) | Appends one sync event + flags `tx.outboxQueued()`. |
| `A/data/sync/LwwWriter.kt` | `object LwwWriter` (`insert` :20, `update` :45, `delete` :69) | Local LWW writes with `fver` stamping + outbox. |
| `A/data/sync/Importer.kt` | `class SyncEvent`, `class Importer(db)` (`knows`, `apply` :46; `LWW_TABLES` :197, `EVENT_ENTITIES` :213, `NOT_FIELDS`, `knownEntities`, `MOVE_COLS`) | Applies other tills' events idempotently. |
| `A/data/sync/Backfill.kt` | `object Backfill` (`run` :30, `order`) | Publishes all existing rows when sync is turned on. |
| `A/data/sync/SyncDao.kt` | `OutboxRow`, `SegmentRow`, `object SyncDao` (outbox batches, segments, cursors, `restartPublishing`, `yieldSettings`, `yieldStaff`, `defer`, `deferredPage`, `BATCH_CHARS`), `object SegmentCodec` (`Header`, `Corrupt`, `write`, `read`, `sha256`, `parse`, `writeValue`) | LOCAL sync bookkeeping and the NDJSON.gz segment codec. |

### 3.4 `com.lekaspos.domain` (+ subpackages) — app-scoped services
Use cases and state holders. Each takes `AppGraph`, reaches the DB via `graph.db()`, checks permissions
with `graph.permissions.actor(perm, approval)` and audits sensitive actions in the same transaction. Writes that
must also update memory run in `withContext(NonCancellable)`.

| File | Main declarations | Purpose |
|---|---|---|
| `A/domain/SettingsRepo.kt` | `class SettingsRepo` | Store + device settings as StateFlows (see §4.7). |
| `A/domain/StaffSession.kt` | `class StaffSession`, `class Approval`, `data class Actor`, `class PermissionGate` | Who is signed in, idle lock, PIN checks; permission resolution (see §4.5, §4.6). |
| `A/domain/backup/BackupService.kt` | `class BackupService` (`Entry`, `Protection`, `backupNow`, `backupIfDue`, `setFolder`, `copyToFolderNow`, `refreshProtection`, `list`, `export`, `header`, `stageRestore`, `cancelRestore`, `restartIntoRestore`, `restart`, `delete`, `Postponed`) | Backups and restores (see §4.10). |
| `A/domain/customer/CustomerService.kt` | `class CustomerService` (`page`, `get`, `save`, `delete`, `receivePayment`, `adjust`, `statement`, `debtors`) | Customers & credit (D-039). |
| `A/domain/inventory/InventoryService.kt` | `class InventoryService` (`receive`, `adjust`, `startCount`, `count`, `finishCount`, `loadDraft`, `saveDraft`) | Stock work (MANAGE_STOCK, audited write-offs). |
| `A/domain/print/ReceiptBuilder.kt` | `object ReceiptBuilder` (`build`, `layout`, `needsImage`) | `ReceiptDoc` from the stored sale. |
| `A/domain/products/ProductCsvService.kt` | `class ProductCsvService` (`export`, `template`, `preview`, `startImport`, `import`, `state`, `mayImportStock`) | Product CSV export/import (D-042). |
| `A/domain/promo/PromotionService.kt` | `class PromotionService` (`version`, `load`, `all`, `active`, `forProduct`, `save`, `delete`) | Promotions kept in memory. |
| `A/domain/report/DailyReportUpload.kt` | `class DailyReportUpload` (`Status`, `load`, `turnOn`, `turnOff`, `atStart`, `upload`) | Daily sales CSV to the owner's Drive (D-065). |
| `A/domain/report/ReportService.kt` | `class ReportService` (`Report`, `Stock`, `enum Export {SUMMARY, DAILY, PRODUCTS, RECEIPTS}`, `build`, `slowMovers`, `stock`, `staffCheck`, `export`, `write`) | Reports and CSV exports from summaries. |
| `A/domain/sale/SaleActions.kt` | `class ActionRefused(reason)`, `class SaleActions` (`refundInfo`, `refund`, `void` :153, `print`, `recordShare`, `openDrawer`) | Refunds, voids, reprints, drawer; canonical "permission + audit in one tx" example. |
| `A/domain/sell/BarcodeLookup.kt` | `sealed class Resolution {Plain, Scale, NotFound}`, `object BarcodeLookup` (`resolve`, `unitPrice`, `labelQty`) | What a scanned code means. |
| `A/domain/sell/CartSession.kt` | `class CartSession` | The open bill (see §4.3). |
| `A/domain/sell/CheckoutService.kt` | `data class Tender`, `class CheckoutService` | Completes a sale (see §4.4). |
| `A/domain/sell/PopularItems.kt` | `class PopularItems` (`load`) | "Popular" tab ranking (30 days, cached 10 min, meta `dev.popular`). |
| `A/domain/sell/PriceCheck.kt` | `class PriceCheck` (`Pack`, `Info`, `lookup`, `setPrice`) | Price check without touching the bill. |
| `A/domain/shift/ShiftService.kt` | `class ShiftService` | Shifts and cash (see §4.8). |
| `A/domain/staff/StaffService.kt` | `class StaffService` (`staff`, `roles`, `save`, `delete`, `setPin`, `changeOwnPin`, `newRecoveryCode`, `hasRecoveryCode`, `recover`, `saveRole` :179, `deleteRole`) | Staff, roles, PINs (MANAGE_STAFF; owner rules). |

### 3.5 `com.lekaspos.hw.*` — hardware
| File | Main declarations | Purpose |
|---|---|---|
| `A/hw/bt/Bluetooth.kt` | `data class Paired`, `object Bluetooth` (`SPP`, `adapter`, `runtimePermissions`, `hasPermission`, `paired`, `settingsIntent`), `class SppLink` (`open`, `output`, `input`) | Paired-device listing and an RFCOMM link (secure → insecure → channel 1). |
| `A/hw/camera/BarcodeDecoder.kt` | `class BarcodeDecoder` (`decode`) | ZXing 3.3.3 decode of preview frames. |
| `A/hw/printer/Images.kt` | `object Images` (`gray`, `toBitmap`, `qr`, `logoFile`, `saveLogo`, `deleteLogo`, `logo`) | Bitmap ↔ MonoImage, logo file `files/receipt_logo.png`, QR. |
| `A/hw/printer/PrinterService.kt` | `class PrinterService` | Print queue thread (see §4.11). |
| `A/hw/printer/ReceiptRenderer.kt` | `class ReceiptRenderer(cols, widthPx)` (`height`, `draw`, `bitmap`, `mono`, `BAND_ROWS=256`) | Draws receipts for image printing, sharing and PDF. |
| `A/hw/printer/TestPage.kt` | `object TestPage` (`lines`) | Printer test page. |
| `A/hw/scanner/SppScanner.kt` | `class SppScanner` (`Status`, `codes: SharedFlow<String>`, `status`, `start`, `hold`, `release`, `poke`, `stop`, `restart`) | Serial (SPP) Bluetooth scanner, held by screens that take scans. |

### 3.6 `com.lekaspos.sync` (+ `.drive`) — sync engine and providers
Log shipping through a dumb folder; only this package knows Google Drive.

| File | Main declarations | Purpose |
|---|---|---|
| `A/sync/SyncEngine.kt` | `class SyncEngine` | One sync round, enable/disable (see §4.9). |
| `A/sync/AutoSync.kt` | `class AutoSync` (`changed`, `now`, `start`) | In-app sync scheduling (10 s after a change, 5 s after start, network back; rounds ≥ 45 s apart). |
| `A/sync/SyncProvider.kt` | `data class RemoteFile`, `open class AuthNeeded : IOException`, `interface SyncProvider` (`id`, `list`, `put`, `get`, `delete`, `clockOffset`), `class FolderProvider(dir)` | Provider interface + directory provider (tests, `.name.props` sidecars). |
| `A/sync/SyncProviders.kt` | `object SyncProviders` (`GDRIVE`, `Connect`, `available`, `connect`, `finish`, `forId`, `REPORT_FOLDER`, `ReportAccess`, `connectReports`, `finishReports`, `reportFolder`) | Chooses/connects the provider; Drive report folder. |
| `A/sync/ReportFolder.kt` | `interface ReportFolder` (`put`) | Where the daily report CSV goes. |
| `A/sync/drive/DriveAuth.kt` | `object DriveAuth` (`Result`, `SignInNeeded`, `playServicesAvailable`, `authorize`, `fromIntent`, `silentToken`, `forget`) | GIS `AuthorizationClient`, scope `drive.appdata` (or `drive.file` for reports). |
| `A/sync/drive/DriveProvider.kt` | `class DriveProvider : SyncProvider` (`HttpError`, `accountEmail`, `SCOPE`) | Drive REST v3 over `HttpURLConnection`. |

### 3.7 `com.lekaspos.perf` — perf suite (ships in release builds)
| File | Main declarations | Purpose |
|---|---|---|
| `A/perf/PerfDataGenerator.kt` | `enum PerfScale {TINY(300,600,20), QUICK(5000,20000,120), FULL(50000,250000,365)}`, `class PerfDataGenerator` (`generate`, `DB_NAME="perf.db"`, `exists`, `delete`) | Deterministic `perf.db`. |
| `A/perf/PerfSuite.kt` | `class PerfSuite(ctx, db, scale)` (`run`; private `measure(id, budgetMs, warmup, n)` :415) | Every timed scenario + `QueryPlans.check`. |
| `A/perf/PerfReport.kt` | `DeviceInfo`, `PerfResult` (`pass`), `PlanCheck`, `PerfReport` (`toText`, `toJson`, `passed`) | Report model. |
| `A/perf/PerfRunner.kt` | `class PerfRunner` (`State`, `start`, `cancel`, `deleteData`, `cleanLeftovers`, `LOG_TAG="LekasPerf"`) | App-scoped run owner; writes `Android/data/<pkg>/files/perf/perf-<time>.{txt,json}`. |
| `A/perf/QueryPlans.kt` | `object QueryPlans` (`LARGE_TABLES` :29, `KEY_LOOKUPS` :39, `INDEX_ORDERED` :45, `INDEX_RANGES` :61, `hotQueries` :66, `maintenanceQueries`, `check`) | `EXPLAIN QUERY PLAN` checker. |

### 3.8 `com.lekaspos.util`
| File | Main declarations | Purpose |
|---|---|---|
| `A/util/Log.kt` | `object Log` (`TAG="Lekas"`, `d{}`, `i`, `w`, `e`, `expected`) | The only logger; `w`/`e` also append to `ErrorLog`; `e` of an "expected" throwable is downgraded to `w`. |
| `A/util/ErrorLog.kt` | `object ErrorLog` (`init`, `append`, `tail`, `files`, `onError`, `onCrash`) | `files/logs/errors.log` (256 KB + `errors.1.log`), own thread; uncaught-exception hook. |
| `A/util/Storage.kt` | `object Storage` (`freeBytes`, `lowBelow`, `backupNeeds`, `isFull`, `text`) | Full-phone detection. |
| `A/util/StrictModeSetup.kt` | `object StrictModeSetup` (`enable`) | Debug-only StrictMode (disk/network log, leaked SQLite/closeables/activities). |

### 3.9 `com.lekaspos.ui` (root) and `ui.common` — shared UI
| File | Main declarations | Purpose |
|---|---|---|
| `A/ui/Insets.kt` | `object Insets` (`apply(root, topBar?)`) | Edge-to-edge padding (status/nav bars, cutout, IME). |
| `A/ui/Ui.kt` | `fun Context.colorOf(id)` | Color lookup working on API 21–22. |
| `A/ui/common/ScreenActivity.kt` | `abstract class ScreenActivity : Activity, DialogHost` | Base of every back-office screen (see §4.12). |
| `A/ui/common/DialogHost.kt` | `interface DialogHost`, `class DialogTracker`, `object DialogKeys` (`BURST_GAP_MS=60`, `BURST_IDLE_MS=300`, `pressesFocused`), `fun Dialog.keys(handler)`, `fun Dialog.trackedBy(ctx)`, private `ActivityCallback` | Dialog tracking, scanner-safe keys, idle-lock activity in dialogs. |
| `A/ui/common/Dialogs.kt` | `object Dialogs` (`canShow`, `scrolling`, `message`, `confirm`, `input`, `choose`, `padded`) | AlertDialog helpers that never show on a finishing activity. |
| `A/ui/common/PadDialog.kt` | `Context.sideways()`, `Context.keyHeightPx(dp)`, `AlertDialog.wide()`, `class PadDialog(ctx, title)` (`info`, `pad`, `positive`, `neutral`, `negative`, `create`, `sidewaysBody`) | Number/PIN pad dialog layout, upright vs sideways (D-063). |
| `A/ui/common/Keypad.kt` | `class Keypad(ctx, maxDigits, onChange)` (`digits`, `replacing`, `view`, `set`, `preset`, `clear`, `onKey`) | On-screen number pad over `:core DigitEntry`; drops scanner bursts. |
| `A/ui/common/PinPad.kt` | `class PinPad` | PIN entry pad. |
| `A/ui/common/Form.kt` | `class Form(ctx)` (fields, switches, choices, `section`, `row`, `info`, `button`, `save()/restore()`) | Code-built forms (most back-office forms). |
| `A/ui/common/Rows.kt` | `class RowHolder` (`set`), `class RowAdapter<T>` (`submit`, `append`, `items`), `RecyclerView.onNearEnd` | Standard list rows (`R.layout.item_row`) and keyset paging trigger. |
| `A/ui/common/LaunchGuard.kt` | `class LaunchGuard` (`allow`, `SAME_MS=700`) | Same screen not started twice within 700 ms. |
| `A/ui/common/TapOnce.kt` | `class TapOnce(gapMs=1000)` | Drops double taps on an action. |
| `A/ui/common/TapViews.kt` | `class TapList`, `class TapScroll` | RecyclerView / ScrollView without pressed-state delay. |
| `A/ui/common/ScanInput.kt` | `class FieldScan`, `class ScanInput`, `fun scanChar(KeyEvent)` | Keyboard-wedge scanning on back-office screens. |
| `A/ui/common/CsvFiles.kt` | `object CsvFiles` (`MIME`, `shareFile`, `sharedFile`, `cleanShared`, `KIND_CSV/RECEIPT/BACKUP`, `writer`, `openForWriting`, `share`, `createDocumentIntent`, `openDocumentIntent`, charset detection) | CSV/file sharing via FileProvider (`cache/shared/<kind>/`) or SAF. |
| `A/ui/common/KeyboardTip.kt` | `object KeyboardTip` (`watch`, `unwatch`) | Tells the user once that a keyboard-mode scanner hides the soft keyboard. |
| `A/ui/common/FitTextView.kt` | `class FitTextView`, `class FitButton`, `TextFit` | One-line text that shrinks to fit. |
| `A/ui/common/PictureCache.kt` | `class PictureCache` (`catalogChanged`, `trim`, …) | LRU of decoded product pictures (≤ 10 MB). |
| `A/ui/common/TileColors.kt` | `object TileColors` | Shades of `TileColor` codes. |
| `A/ui/common/ColorPicker.kt` | `class ColorPicker` | Tile colour swatches. |
| `A/ui/common/WrapRow.kt` | `class WrapRow` | Wrapping row layout. |

### 3.10 UI screens by package (one-line purposes; details in §5)
| File | Main declarations | Purpose |
|---|---|---|
| `A/ui/sell/SellActivity.kt` | `class SellActivity : Activity, LineActions, DialogHost` (1885 lines) | Launcher and selling screen (not a `ScreenActivity`). |
| `A/ui/sell/SellAdapters.kt` | `CartRow`, `interface LineActions`, `CartAdapter`, `TileSpec`, `ProductTileAdapter`, `CategoryChipAdapter` | Bill list, tiles, category chips. |
| `A/ui/sell/SellDialogs.kt` | `AmountDialog` (Kind MONEY/PIECES/WEIGHT/PERCENT), `DiscountDialog`, `showHeldBills`, `showUnknownBarcode`, `weightText`, `TextView.replacing`, `AlertDialog.forwardKeys`, `View.visible` | Selling-screen dialogs; `AmountDialog` is the reusable keypad dialog. |
| `A/ui/sell/PaymentDialog.kt` | `class PaymentDialog` | Two-step payment (D-064). |
| `A/ui/sell/PaymentViews.kt` | `class PaymentViews` | Payment button rows (measured by tests). |
| `A/ui/sell/PriceCheckDialog.kt` | `class PriceCheckDialog` | Price check. |
| `A/ui/sell/LineControls.kt` | `class LineControls` | Remove / − / qty / + under the selected line. |
| `A/ui/sell/TopBarLayout.kt` | `class TopBarLayout` | Top bar with pills and Menu. |
| `A/ui/sell/Beeper.kt` | `class Beeper` | Scan beeps on their own thread. |
| `A/ui/staff/LockActivity.kt` | `class LockActivity : Activity, DialogHost` | PIN sign-in over the selling screen. |
| `A/ui/staff/StaffActivities.kt` | `StaffActivity`, `StaffEditActivity`, `RolesActivity`, `RoleEditActivity`, `changeOwnPin`, private `asOwnerIfRefused` | Settings → Staff. |
| `A/ui/staff/StaffUi.kt` | `permLabel` :30, `waitText`, `checkMessage`, `Activity.withApproval` :71, `object ApprovalDialog` (`show`, `help`), `askPin`, `askNewPin`, `showRecoveryCode` | Permission labels, approval dialogs, PIN dialogs. |
| `A/ui/shift/ShiftActivities.kt` | `cashCountButton`, `openShift` :68, `closeShift` :122, `offerShiftCount` :176, `ShiftActivity`, `ShiftReportActivity`, `ShiftsActivity` | Shift & cash. |
| `A/ui/shift/CashCountDialog.kt` | `class CashCountDialog` | Notes/coins count. |
| `A/ui/products/ProductListActivity.kt` | `ProductListActivity` | Product list, CSV menu. |
| `A/ui/products/ProductEditActivity.kt` | `ProductEditActivity` (`newIntent`, `EXTRA_BARCODE`) | Product form (writes DAOs directly in one tx). |
| `A/ui/products/ProductImportActivity.kt` | `ProductImportActivity` | CSV import preview/run. |
| `A/ui/products/PromotionActivities.kt` | `promoDeal`, `PromotionsActivity`, `PromotionEditActivity` | Promotions. |
| `A/ui/products/LookEditor.kt` | `class LookEditor` | Tile colour + picture part of the product form. |
| `A/ui/products/Pictures.kt` | `object Pictures` | Photo → 240 px JPEG base64. |
| `A/ui/catalog/CategoriesActivity.kt` | `CategoriesActivity` | Categories CRUD. |
| `A/ui/catalog/TaxRatesActivity.kt` | `TaxRatesActivity` | Tax rates CRUD. |
| `A/ui/catalog/PaymentMethodsActivity.kt` | `PaymentMethodsActivity` | Payment methods CRUD. |
| `A/ui/inventory/InventoryActivity.kt` | `InventoryActivity`, `ScreenActivity.adjustProduct` | Inventory hub. |
| `A/ui/inventory/InventoryUi.kt` | `data class StockProduct`, `object InventoryUi` (`resolve`, `product`, `qty`, `askQty` :110, `askAdjust`, labels) | Shared stock dialogs (PadDialog examples). |
| `A/ui/inventory/ReceiveActivity.kt` | `ReceiveActivity` | Receive a delivery. |
| `A/ui/inventory/CountActivities.kt` | `CountSessionsActivity`, `CountActivity`, `CountReportActivity` | Stock takes. |
| `A/ui/inventory/StockActivities.kt` | `StockHistoryActivity`, `LowStockActivity`, `MovementsActivity` | Stock history and lists. |
| `A/ui/inventory/PurchasesActivity.kt` | `PurchasesActivity`, `PurchaseDetailActivity` | Deliveries. |
| `A/ui/inventory/SuppliersActivity.kt` | `SuppliersActivity`, `editSupplier` | Suppliers. |
| `A/ui/inventory/ProductPickActivity.kt` | `ProductPickActivity` (`intent`, `EXTRA_PRODUCT_ID`) | Picks a product for stock work. |
| `A/ui/sales/SalesActivity.kt` | `SalesActivity` | Sales history + receipt lookup. |
| `A/ui/sales/SaleDetailActivity.kt` | `SaleDetailActivity` | One sale: copy, share, refund, void. |
| `A/ui/sales/RefundActivity.kt` | `RefundActivity` | Refund/return. |
| `A/ui/sales/ReceiptShare.kt` | `object ReceiptShare` (`chooseAndShare`, `share`, `preview`) | Receipt picture/PDF sharing. |
| `A/ui/customers/CustomerActivities.kt` | `CustomersActivity` (`pickIntent`), `CustomerActivity`, `editCustomer`, `pickCustomer` | Customers and credit. |
| `A/ui/reports/ReportActivities.kt` | `ReportsActivity`, `SlowMoversActivity` | Reports + exports. |
| `A/ui/reports/StaffCheckActivity.kt` | `StaffCheckActivity` | Staff check (D-067). |
| `A/ui/settings/SettingsActivity.kt` | `SettingsActivity` | Settings hub. |
| `A/ui/settings/StoreSettingsActivity.kt` | `StoreSettingsActivity` | Store & receipt (synced). |
| `A/ui/settings/PrinterSettingsActivity.kt` | `PrinterSettingsActivity`, `bluetoothRefused` | Printer & drawer (per till). |
| `A/ui/settings/ScannerSettingsActivity.kt` | `ScannerSettingsActivity` | Scanner & camera (per till). |
| `A/ui/settings/AuditLogActivity.kt` | `AuditLogActivity` | Activity log (labels for every `AuditAction`). |
| `A/ui/settings/BackupActivity.kt` | `BackupActivity` (`EXTRA_PICK_FOLDER`) | Backup & restore. |
| `A/ui/settings/SyncActivity.kt` | `SyncActivity` | Google Drive backup/sync. |
| `A/ui/settings/SetupActivity.kt` | `SetupActivity` | First-run welcome. |
| `A/ui/settings/DriveReportUi.kt` | `object DriveReportUi` (`open`, `subtitle`, `onResult`) | Daily report to Drive settings. |
| `A/ui/settings/UpdateUi.kt` | `object UpdateUi` (`settings`, `subtitle`, `onResult`, pill) | App updates UI. |
| `A/ui/settings/ReportsChoice.kt` | `object ReportsChoice` (`ask`) | Error-reports consent question. |
| `A/ui/scan/CameraScanActivity.kt` | `CameraScanActivity` (`sellIntent`, `pickIntent`, `EXTRA_CODE`) | Camera1 + ZXing scanner. |
| `A/ui/diag/DiagnosticsActivity.kt` | `DiagnosticsActivity` | Device info, perf test, error log share, manual report. |
| `A/ui/display/CustomerDisplay.kt` | `class CustomerDisplay : ActivityLifecycleCallbacks, DisplayListener` | Keeps the customer `Presentation` on the second display. |
| `A/ui/display/CustomerScreen.kt` | `class CustomerScreen : Presentation` | Draws `:core CustomerView`. |

---

## 4. Central classes — public API

### 4.1 `AppGraph` (`A/app/AppGraph.kt`)
`class AppGraph(app: Application, dbName: String = Schema.FILE_NAME)`. Obtain with `LekasApp.graph(context)` or
`ScreenActivity.graph`. Tests build their own with `TestGraph.create()` (another `dbName`).

| Member | Type | What it is |
|---|---|---|
| `appScope` (:56) | `CoroutineScope` | `SupervisorJob + Dispatchers.Default` + handler that `Log.e`s (rethrows in DEBUG). For work that must outlive a screen. |
| `db()` (:81) | `suspend fun: Db` | Lazily opened on `Dispatchers.IO`; a failed open is retried by the next caller. Only `lekaspos.db` gets `onOutboxCommit = { syncSoon() }`. |
| `bootCount()` | `Int?` | `Settings.Global.BOOT_COUNT` on API 24+. |
| `settings` | `SettingsRepo` | store + device settings |
| `staff` | `StaffSession` | signed-in staff, idle lock |
| `permissions` | `PermissionGate` | permission checks/approvals |
| `staffAdmin` | `StaffService` | staff/roles/PINs |
| `shifts` | `ShiftService` | shifts and cash |
| `customers` | `CustomerService` | customers and credit |
| `reports` | `ReportService` | reports and exports |
| `productCsv` | `ProductCsvService` | product CSV |
| `cart` | `CartSession` | the open bill |
| `priceCheck` | `PriceCheck` | price check |
| `popular` | `PopularItems` | Popular tab |
| `promotions` | `PromotionService` | promotions |
| `checkout` | `CheckoutService` | completes sales |
| `sales` | `SaleActions` | refunds, voids, reprints, drawer |
| `inventory` | `InventoryService` | stock work |
| `printer` | `PrinterService(app, this)` | print queue |
| `sppScanner` | `SppScanner(app, this)` | serial scanner |
| `perfRunner` | `PerfRunner(app, appScope)` | in-app perf run |
| `backups` | `BackupService(this, app)` | backups/restores |
| `sync` | `SyncEngine(this, app)` | sync |
| `autoSync` | `AutoSync(this, app)` | in-app sync scheduling |
| `updates` | `AppUpdates(app)` | self-update |
| `dailyReport` | `DailyReportUpload(this, app)` | Drive daily report |
| `pictures` | `PictureCache(this)` | product pictures |
| `customerDisplay` | `CustomerDisplay(app, this)` | second display |
| `syncSoon()` (:144) | fun | After an outbox commit: `autoSync.changed()` + `Work.syncSoon` at most once a minute. Only for the app's own DB. |
| `syncSoonDone()` | fun | Resets that minute gap. |
| `catalogChanges` / `catalogChanged()` (:164/:166) | `StateFlow<Int>` / fun | Tells the selling screen to re-read tiles (sync import, CSV import); also `pictures.catalogChanged()`. |
| `seedNames()` | `SeedNames` | Seed row names in the current screen language. |

### 4.2 `Db` / `Db.Tx` (`A/data/db/Db.kt`) and helpers
| Member | Line | Notes |
|---|---|---|
| `sqlite`, `name`, `deviceNo`, `storeUuid`, `ids: IdAllocator`, `hlc: Hlc`, `file` | 29–65 | identity of this till |
| `writerDispatcher` (thread `db-writer`), `readDispatcher` (`Dispatchers.IO.limitedParallelism(3)`) | 40–46 | |
| `syncEnabled` (`@Volatile var`, from `meta sync_enabled`) | 50 | outbox written only while true (D-016) |
| `onOutboxCommit: (() -> Unit)?` | 57 | runs on the writer thread after a commit that queued an event |
| `suspend fun <T> read(block: (SQLiteDatabase) -> T)` | 67 | on the read pool |
| `suspend fun <T> write(reserveIds = 1000, block: (Tx) -> T)` | 70 | one transaction on the writer thread; nested write joins the outer tx |
| `fun readBlocking` / `fun writeBlocking` | 74 / 80 | for workers/tests; `assertNotMainThread()` |
| `fun onWriterThread(block)` | 94 | writer thread **outside** a tx (backup copy) |
| `transaction(...)` (private) | 107 | `ids.ensure(reserveIds)` **before** `beginTransactionNonExclusive`; persists `hlc_last`; calls `onOutboxCommit` after commit |
| `close()` | 140 | |
| `Db.Tx`: `db`, `deviceNo`, `syncEnabled`, `nextId()`, `hlcNow()`, `outboxQueued()`, `stmt(sql)` (cached compiled statement, writer thread only), `exec`, `update` (rows changed), `insert` (rowid / −1 if ignored), `updateOrInsert` (UPSERT substitute) | 150–190 | |
| `companion open(context, name, seedNames, storeData = true)` | 200 | deletes unfinished backups, applies a staged restore, backs up before upgrade (keeps 3), `KeepDamagedDatabase` fallback, audits a restore |
| `companion assertNotMainThread()` | 319 | `check(Looper.myLooper() != mainLooper) { "database access on the main thread" }` |

`Sql.kt` helpers: `args(vararg)` → `Array<String?>`; `SQLiteDatabase.queryList/queryOne/longOrNull/long/stringOrNull/pragma`;
`SQLiteStatement.bindAll` (null/Long/Int/Boolean/String/ByteArray/Double); `Cursor.longOrNull/stringOrNull/bool`.
`Meta.get/getLong/put/increment` (INSERT OR REPLACE, `meta` is LOCAL).

### 4.3 `CartSession` (`A/domain/sell/CartSession.kt`) — main-thread mutators, ordered background writer
| Member | Line | Notes |
|---|---|---|
| `data class State(loaded, cartId, openedAt, cart, priced, lastKey, heldCount, busy, paying, customerId, customerName, payShown)`; `canEdit` | 51 | |
| `state: StateFlow<State>` | 88 | |
| `class PaymentDraft`, `var payment`; `data class Prompt`, `var prompt` | 101–118 | survive Activity recreation |
| `suspend load()` | 136 | restores the open bill once per process |
| `suspend scan(code): ScanResult` (`Added/NeedsWeight/NeedsPrice/NotFound/Busy`) | 156 | all scan paths end here |
| `addProduct`, `addAtPrice`, `add(template)`, `itemFor(hit)` | 196–258 | |
| `setQty`, `changeQty`, `remove` | 290–343 | removals audited `LINE_REMOVE(_AFTER_PAY)` |
| `setLineDiscount(key, d, approval)`, `overridePrice`, `setBillDiscount` | 385–410 | |
| `clear()`, `setCustomer` | 428–451 | |
| `suspend hold(label)`, `heldBills()`, `resume(id)`, `deleteHeld(id)` | 463–533 | |
| `suspend <T> checkout(reserveIds, block: (Tx, State) -> T)` | 562 | flushes pending writes, runs `block` + `CartDao.deleteCart` in one tx, then `resetEmpty` |
| `setPaying(on)` | 584 | freezes the bill; sets `pay_shown` |
| `suspend flush()`, `suspend reprice()` | 597 / 741 | |

### 4.4 `CheckoutService` (`A/domain/sell/CheckoutService.kt`)
`data class Tender(methodId, kind, name, opensDrawer, applied, tendered, change)`.
`Done`, `sealed Outcome` (`Completed`, `Refused`, `Failed`), `outcome: StateFlow`, `outcomeAt`, `last: StateFlow<Done?>`.
`start(tenders, rounding, approvals)` (:98) runs `complete` in `appScope`; `acknowledge()` (:119) clears the outcome and
locks if `lockAfterSale`; `suspend complete(...)` (:129) → `graph.cart.checkout { SaleDao.commit … CustomerDao.insertCredit … AuditDao.log … PrintJobDao.enqueue(DRAWER/RECEIPT) }` then `printer.wake()`;
`companion draft(...)` builds the `SaleDraft` purely (JVM-tested in `UT/domain/sell/CheckoutDraftTest.kt`).

### 4.5 `StaffSession` (`A/domain/StaffSession.kt`)
`data class Signed`, `data class State(loaded, loginRequired, current)` with `locked`; `sealed Check {Ok, WrongPin, Wait, NotAllowed}`.
`state` (:59), `staffId` (:62, owner id 1 while no login), `perms` (:69 — **0 until loaded**, `Perm.ALL` only when loaded and login off),
`load()` (:101), `reload()` (:106), `signIn()` (:169), `lock()` (:217), `screenStarted()` (:240), `screenStopped()`, `activity()` (:275: true = just
locked, drop the event), `dialogActivity()` (:289), `recordApproval()` (:302), `touch()` (:314), `lockIfIdle()` (:343), `waitMs()`, `check(staffId, pin, perm)` (:415).
Meta keys: `session.staff`, `session.away_at`, `session.seen_at`, `pin.fails.<id>`, `pin.last_fail.<id>`, `pin.last_fail_rt.<id>`.

### 4.6 `PermissionGate` (`A/domain/StaffSession.kt:492`)
| Function | Meaning |
|---|---|
| `allowed(perm)` (:498) | role has it, or an elevation (screen approval / till help) grants it |
| `shown(perm)` (:504) | `allowed` or a manager's till help covers it → **use to hide/show controls** |
| `ownRole(perm)` | role only |
| `actor(perm, approval?)` (:516) | `Actor(staffId, approvedBy)` or throws `ActionRefused(NOT_ALLOWED)` — **domain services call this** |
| `actorOrNull` (:519) | same, null instead of throwing |
| `approve(staffId, pin, perm)` (:528) | PIN check → `Approval` |
| `approveHelp`, `helper: StateFlow<Approval?>`, `startHelp(approval, lend)`, `endHelp`, `endHelpIfExpired` | manager help on the till (lends `Perm.TILL_HELP or lend`, 5 min) |
| `elevate(approval)` / `release(token)` / `holds(token)` / `clear()` | screen-held approvals (ScreenActivity keeps tokens until it finishes) |
`class Approval internal constructor(perm, staffId, name)` (:483) — only `approve*` create it; `data class Actor(staffId, approvedBy)` (:486).

### 4.7 `SettingsRepo` (`A/domain/SettingsRepo.kt`)
`store: StateFlow<StoreSettings>`, `device: StateFlow<DeviceSettings>`, `loaded`, `load()` (:48), `reload()` (:75, called by sync import),
`saveStore(before, after)` (:89; NonCancellable; `actor(Perm.SETTINGS)`; writes only keys the screen changed that differ from the DB; one
`SETTINGS_CHANGE` audit), `saveStore(after)`, `languageChanged()`, `recordChange(what, approvedBy)`, `saveDevice(d, then)` (:136; NonCancellable,
**no permission check — callers check**), `needsSetup()`, `markSetupDone()` (meta `dev.setup_done`).

### 4.8 `ShiftService` (`A/domain/shift/ShiftService.kt`)
`current: StateFlow<Shift?>` (:53), `load()` (:60), `currentId`, `open(openingFloat)` (:74; audits `SHIFT_OPEN`, maybe `FLOAT_DIFFERENCE`),
`moveCash(kind, amount, reason, approval)` (:106; `CASH_MOVE`), `report(shiftId)` (:128), `close(counted, note, leave)` (:140), `handover(...)` (:184),
`class Prompt`, `prompt(staffId)` (:224), `handoverDue`, `leftInDrawer()` (meta `shift.left`), `markAsked`, `useShiftsWithStaff()` (once per shop: skipped when the synced log has `SHIFTS_ON_DETAIL`), `continueShift`
(`SHIFT_CONTINUED`), `print(shiftId, approval)`, `page(after)`.

### 4.9 `SyncEngine` (`A/sync/SyncEngine.kt`)
`Status` (:44), `Report` (:74), `Problem(reason: NOT_ENABLED/DEVICE_CLASH/CORRUPT/OLD_COPY/OTHER_STORE)` (:85), `DeviceCard` (:103), `status` (:121),
`recentlyDone(withinMs)` (:132), `refreshStatus()` (:139), `starting()`, `notStarted()`, `enable(provider, deviceName, account, progress, join)` (:190),
`takePinsNotice()`, `disable()` (:354), `provider()` (:382), `sync(provider, onStart)` (:390), `cards(...)` (:1029).
After an import `reloadChanged` (:504) reloads settings (`Entity.SETTING`), staff (`ROLE`/`STAFF`), promotions, and calls `catalogChanged()` for
`CATALOG_ENTITIES` (:1104). Meta keys (companion :1100–1135): `sync.provider`, `sync.account`, `sync.device_name`, `sync.list_since`, `sync.full_list_at`,
`sync.upload_try`, `sync.full_from`, `sync.deferred_tried`, `sync.clock_off`, `sync.pins_notice`; error codes `sign-in`, `offline`, `corrupt`.

### 4.10 `BackupService` (`A/domain/backup/BackupService.kt`)
`Entry`, `Protection` (NO_DATA/PROTECTED/AT_RISK/DAMAGED — see sync.md §11), `protection: StateFlow`, `dir`, `backupNow(auto, quietOnly)` (:91),
`backupIfDue()` (:118, called by `BackupWorker`), `setFolder(uri, name)`, `copyToFolderNow()`, `refreshProtection()`, `list()`, `export(open)`,
`restoreFailure()`, `header(open)` (throws when unreadable, null when not a backup), `stageRestore(open, mode)`, `cancelRestore()`,
`restartIntoRestore(activity)`, `restart(activity)`, `delete(file)`, `class Postponed` (worker retries).

### 4.11 `PrinterService` (`A/hw/printer/PrinterService.kt`)
`sealed Status {NotConfigured, NoBluetooth, NoPermission, BluetoothOff, Idle, Connecting, Printing, Ready, Offline(error, retryAt, gaveUp)}`,
`status`, `pending: StateFlow<Int>`, `start()` (:88, own thread `printer`, restarts the loop 10 s after any error), `wake()` (:123, starts if needed —
call after enqueueing a job), `reconnect()` (:135, after settings change). Jobs come only from `print_job` rows (`PrintJobDao.enqueue`).

### 4.12 `ScreenActivity` (`A/ui/common/ScreenActivity.kt`) — base of every back-office screen
| Member | Line | Use |
|---|---|---|
| `attachBaseContext` → `AppLanguage.wrap` | 51 | app language |
| `graph` | 53 | `LekasApp.graph(this)` |
| `scope` (MainScope + failure handler; cancelled in `onDestroy`) | 71 | loads/saves of this screen |
| `setScreen(title, layout?) : View?` | 87 | inflates `R.layout.screen` (top bar + `content`), applies `Insets`, `KeyboardTip`; returns the inflated content layout |
| `setScreenTitle`, `addAction(icon, desc, onClick)` | 100/104 | top bar |
| `onStart` | 124 | idle check → home; `whenLoaded()` (staff + settings); locked → home; idle-lock loop; SPP scanner hold; then `enter()` → `guard` check → `onStarted(scope)` |
| `startActivityForResult` override (`LaunchGuard`) | 191 | double-tap protection for every launch |
| `dispatchTouchEvent` / `dispatchKeyEvent` | 209/217 | `staff.activity()`; keys then `screenKey(event)` |
| `protected open screenKey(event): Boolean` | 230 | keyboard-wedge scans on this screen |
| `requireAccess(perm) { }` | 242 | allowed → run; else manager approval **held by the screen** until it closes |
| `protected guard(perm)` | 257 | call in `onCreate`: screen unusable without perm; approval asked in `onStart`, cancel finishes |
| `startPicker(intent, request): Boolean` | 266 | safe picker launch |
| `exportCsv(fileName) { out -> rows }` | 285 | share (cache + FileProvider) or save (SAF); runs in `appScope` |
| `whenLoaded()` | 353 | `staff.load(); settings.load()` |
| `withApproval(perm) { approval -> }` | 359 | one-shot manager approval for one action (not kept) |
| `onStop` | 372 | cancels started scope, `screenStopped`, releases SPP |
| `protected open onStarted(scope)` | 381 | collect flows here (scope = onStart..onStop) |
| `protected open serialScans(): ((String) -> Unit)?` | 384 | SPP codes for this screen |
| `onDestroy` | 386 | releases held approvals if finishing, dismisses tracked dialogs |
| `outlivingScreen { }` | 404 | run in `appScope`, await here; failure after leaving → toast |
| `keepScreenOn(on)`, `toast(...)` | 423/431 | |
| `launchUi { }` | 436 | `scope.launch` + rethrow cancellation + `ActionRefused` → `Log.w`, others → `Log.e`, then error dialog |
| `companion errorText(activity, e)` | 458 | maps every `ActionRefused.Reason` and `BackupFiles.Invalid.Kind` to a string |

---

## 5. Screen catalogue (all 47 `<activity>` entries in `app/src/main/AndroidManifest.xml`)

configChanges legend — **K** = `keyboard|keyboardHidden|navigation|uiMode` (recreated on rotation); **B** = K +
`orientation|screenSize|screenLayout|smallestScreenSize` (handles rotation itself, D-054). Only Sell, Lock, CameraScan and Diagnostics are K.
"Guard" = `guard(perm)` in onCreate (screen-level); "actions" = `requireAccess`/`withApproval`/domain `actor` on individual actions.
Menu = selling screen's Menu (`SellActivity.showMenu`, SellActivity.kt:1580); "Manage shop ›" is its submenu.

| Activity (file) | cfg | What | Reached from | Permission | Services / DAOs |
|---|---|---|---|---|---|
| `ui.sell.SellActivity` (sell/SellActivity.kt) | K, `singleTop`, exported LAUNCHER | Selling screen/home | launcher; `goHome()` of every screen | per control: `shown()`/`allowed()`; payment, discounts via approvals | cart, checkout, staff, permissions, shifts, sales, printer, sppScanner, popular, priceCheck, customers, sync, autoSync, backups, updates, dailyReport, pictures; ProductDao, CategoryDao, PaymentMethodDao, SaleDao |
| `ui.staff.LockActivity` (staff/LockActivity.kt) | K | PIN sign-in, Back leaves app | SellActivity when `state.locked` | — | staff, staffAdmin, settings |
| `ui.products.ProductListActivity` | B | Products (paged, search, scan) | Menu › Products | actions: MANAGE_PRODUCTS (add, CSV import/export) | productCsv, ProductDao |
| `ui.products.ProductEditActivity` | B | Add/edit product, barcodes, look, stock | ProductList (+/tap/scan), ProductPick & Receive (new product), SellActivity (unknown barcode → add), Reports | save/delete `requireAccess(MANAGE_PRODUCTS)`; stock fields need MANAGE_STOCK | ProductDao, ProductLookDao, StockDao, CategoryDao, TaxRateDao, AuditDao (direct tx at :526) |
| `ui.products.ProductImportActivity` | B | CSV import preview/run | ProductList overflow → Import | guard MANAGE_PRODUCTS | productCsv |
| `ui.products.PromotionsActivity` | B | Promotion list | Menu › Promotions | actions MANAGE_PRODUCTS | promotions |
| `ui.products.PromotionEditActivity` | B | Add/edit promotion | PromotionsActivity | guard MANAGE_PRODUCTS | promotions, ProductDao (pick via ProductPick) |
| `ui.catalog.CategoriesActivity` | B | Categories CRUD | Menu › Categories; Settings → Categories | actions `requireAccess(MANAGE_PRODUCTS)` | CategoryDao (direct `db().write`) |
| `ui.catalog.TaxRatesActivity` | B | Tax rates CRUD | Menu › Tax rates (SETTINGS); Settings → Tax rates | actions `requireAccess(SETTINGS)` | TaxRateDao, AuditDao (direct) |
| `ui.catalog.PaymentMethodsActivity` | B | Payment methods | Settings → Payment methods | actions `requireAccess(SETTINGS)` | PaymentMethodDao, AuditDao (direct) |
| `ui.sales.SalesActivity` | B | Sales & refunds list, receipt lookup | Menu → "Sales & refunds" (REFUND or VOID) / "Receipts" | none (row actions on detail) | SaleDao |
| `ui.sales.SaleDetailActivity` | B | One sale: copy, share, refund, void | SalesActivity, StockHistoryActivity | buttons shown by `shown(REPRINT/REFUND/VOID)` (SaleDetailActivity.kt:95-98); print/share `withApproval(REPRINT)` (:53,:64), void `withApproval(VOID)` (:125); Refund opens RefundActivity (:72) | sales (SaleActions), SaleQueries, ReceiptBuilder |
| `ui.sales.RefundActivity` | B | Refund/return | SaleDetailActivity | `withApproval(REFUND)` on confirm | sales, PaymentMethodDao |
| `ui.settings.SettingsActivity` | B | Settings hub (language, tiles, customer screen, store, printer, scanner, staff, shift, customers, tax, payment methods, categories, activity log, sync, backup, Drive report, error reports, updates, diagnostics, about) | Menu › Settings (SETTINGS or MANAGE_STAFF or VIEW_AUDIT) | none on hub; rows guard themselves; error reports/updates/Drive report `withApproval(SETTINGS)` | settings, updates, dailyReport, customerDisplay |
| `ui.settings.StoreSettingsActivity` | B | Store & receipt (synced) | Settings → Store & receipt | save/logo `requireAccess(SETTINGS)` (+ `saveStore` checks) | settings, printer |
| `ui.settings.PrinterSettingsActivity` | B | Printer & cash drawer (per till) | Settings; selling-screen printer pill; Setup | save `requireAccess(SETTINGS)`; drawer test `withApproval(OPEN_DRAWER)` | settings, printer, sales, PrintJobDao (direct) |
| `ui.settings.ScannerSettingsActivity` | B | Scanner & camera (per till) | Settings | save `requireAccess(SETTINGS)` | settings, sppScanner |
| `ui.settings.AuditLogActivity` | B | Activity log, filter by action | Settings → Activity log | guard VIEW_AUDIT | AuditDao, StaffDao |
| `ui.settings.BackupActivity` | B | Backups, folder, restore | Settings; "Data problem"/"Not backed up" pills; Setup | guard SETTINGS | backups |
| `ui.settings.SyncActivity` | B | Google Drive backup (sync) | Settings; sign-in/stale pills; Setup | guard SETTINGS | sync, autoSync, backups, SyncDao |
| `ui.settings.SetupActivity` | B | First-run welcome | SellActivity when `settings.needsSetup()` | none | settings, Seed (direct) |
| `ui.inventory.InventoryActivity` | B | Inventory hub | Menu › Inventory (MANAGE_STOCK) | entries: Receive/Adjust/Count `requireAccess(MANAGE_STOCK)` | inventory, StockDao |
| `ui.inventory.ReceiveActivity` | B | Receive a delivery (draft) | Inventory → Receive stock | guard MANAGE_STOCK | inventory, SupplierDao |
| `ui.inventory.ProductPickActivity` | B | Pick a product (result `EXTRA_PRODUCT_ID`) | Inventory → Adjust, Receive, PromotionEdit | none (caller checks) | ProductDao |
| `ui.inventory.SuppliersActivity` | B | Suppliers | Inventory → Suppliers | actions `requireAccess(MANAGE_STOCK)` | SupplierDao (direct) |
| `ui.inventory.PurchasesActivity` | B | Deliveries | Inventory → Deliveries; Suppliers | none | PurchaseDao, SupplierDao |
| `ui.inventory.PurchaseDetailActivity` | B | One delivery | PurchasesActivity | none | PurchaseDao (`get`, `lines`) |
| `ui.inventory.CountSessionsActivity` | B | Stock counts list | Inventory → Stock count | guard MANAGE_STOCK | inventory, CountSessionDao |
| `ui.inventory.CountActivity` | B | Count one session (scan/search) | CountSessionsActivity | guard MANAGE_STOCK | inventory, CountSessionDao, ProductDao, StockDao, CategoryDao |
| `ui.inventory.CountReportActivity` | B | A count's variance report | CountSessionsActivity | none | CountSessionDao |
| `ui.inventory.StockHistoryActivity` | B | One product's stock history | ProductEdit, CountReportActivity rows (CountActivities.kt:340), LowStockActivity rows (StockActivities.kt:149), MovementsActivity rows (:204) | none; top-bar "Adjust" → `adjustProduct` = `requireAccess(MANAGE_STOCK)` (StockActivities.kt:81) | StockHistoryDao, StockDao, inventory |
| `ui.inventory.LowStockActivity` | B | Low-stock list (tap → stock history) | Inventory → Low stock | none | StockDao |
| `ui.inventory.MovementsActivity` | B | Stock-change log | Inventory → Stock changes | none | StockDao |
| `ui.staff.StaffActivity` | B | Staff list | Settings → Staff; Setup | guard MANAGE_STAFF | staffAdmin, staff |
| `ui.staff.StaffEditActivity` | B | Add/edit staff, PIN | StaffActivity | guard MANAGE_STAFF | staffAdmin |
| `ui.staff.RolesActivity` | B | Roles | StaffActivity menu → Roles | guard MANAGE_STAFF | staffAdmin |
| `ui.staff.RoleEditActivity` | B | Role permissions (`Perm.ROLE_EDITOR` switches) | RolesActivity | guard MANAGE_STAFF | staffAdmin |
| `ui.shift.ShiftActivity` | B | Shift & cash: open/close, cash in/out/drop | Menu → Shift & cash (CASH_MOVE or SHIFT_REPORT); Settings → Shift & cash | cash buttons shown by `shown(CASH_MOVE)` then `withApproval(CASH_MOVE)`; report buttons by `allowed(SHIFT_REPORT)` | shifts, ShiftDao, StaffDao |
| `ui.shift.ShiftReportActivity` | B | Shift report (print) | ShiftActivity, ShiftsActivity | guard SHIFT_REPORT | shifts |
| `ui.shift.ShiftsActivity` | B | Past shifts | ShiftActivity → Past shifts | row `requireAccess(SHIFT_REPORT)` | shifts |
| `ui.customers.CustomersActivity` | B | Customers (also pick mode) | Menu → Customers (credit on); Settings → Customers; selling screen customer pick | actions `withApproval(CUSTOMERS)` | customers |
| `ui.customers.CustomerActivity` | B | One customer: statement, repayment, adjust | CustomersActivity | `withApproval(CUSTOMERS / CREDIT_LIMIT)` | customers, PaymentMethodDao |
| `ui.reports.ReportsActivity` | B | Sales report + exports | Menu › Reports (REPORTS) | guard REPORTS | reports |
| `ui.reports.SlowMoversActivity` | B | Not sold in period | ReportsActivity button | guard REPORTS | reports |
| `ui.reports.StaffCheckActivity` | B | Staff check (D-067) | ReportsActivity button (shown when VIEW_AUDIT) | guard VIEW_AUDIT | reports (`staffCheck`) |
| `ui.scan.CameraScanActivity` | K | Camera scanner (sell / pick modes) | Sell search-box camera; price check; ProductPick; Receive; ProductEdit | none (CAMERA runtime permission) | cart, staff |
| `ui.diag.DiagnosticsActivity` | K, exported with `android:permission="android.permission.DUMP"` | Device info, perf test, share error log, send report | Menu › Diagnostics (SETTINGS); Settings → Diagnostics; `adb shell am start … --es autorun QUICK` | guard SETTINGS | perfRunner, ErrorLog, ErrorReports |

Non-activity components: FileProvider `${applicationId}.files` (`RES/xml/file_paths.xml`: `cache/shared/`, `files/updates/`, `cache/photos/`);
WorkManager's `InitializationProvider` initializer removed (`tools:node="remove"`). `allowBackup=false`, `usesCleartextTraffic=false`,
`enableOnBackInvokedCallback=true`, `localeConfig=@xml/locales_config` (en, ms).

---

## 6. Navigation summary
- **Menu** (`SellActivity.showMenu`, :1580): Discount (DISCOUNT, or PRICE_OVERRIDE with a line selected) · Clear bill · Held bills · Sales & refunds / Receipts ·
  Customers (credit enabled) · Open shift / Close shift (`openShift`/`closeShift`) · Shift & cash · Open cash drawer (OPEN_DRAWER) · Lock / switch user (login on) ·
  Manager PIN (lacks a perm, no help yet) · **Manage shop ›** Products, Categories, Promotions (MANAGE_PRODUCTS) · Inventory (MANAGE_STOCK) · Reports (REPORTS) ·
  Settings (SETTINGS|MANAGE_STAFF|VIEW_AUDIT) · Tax rates, Diagnostics (SETTINGS). The permission tests use SellActivity's private
  `allowed(perm) = graph.permissions.shown(perm)` (SellActivity.kt:633): role **or** a manager's help on the till.
- **Settings hub** rows (SettingsActivity.kt:57-84): App language · Item size · Customer screen · Store & receipt · Printer & cash drawer · Barcode scanner & camera ·
  Staff · Shift & cash · Customers · Tax rates · Payment methods · Categories · Activity log · Google Drive backup (Sync) · Backup & restore · Daily sales report to
  Google Drive · Error reports · App updates · Diagnostics · User guide (opens `guide_url`) · About.

---

## 7. Background work (`A/app/Work.kt`; all workers are `CoroutineWorker`)

| Unique name | Worker | Scheduled by | Type / period | Constraints & backoff | Does |
|---|---|---|---|---|---|
| `backup-daily` | `BackupWorker` | `Work.schedule()` from `SellActivity.onStart` (after the bill loads + `HARDWARE_DELAY_MS` = 1500 ms, SellActivity.kt:374-380), once per process; `again=true` after sync enabled (SyncActivity.kt:280) | periodic 24 h, `ExistingPeriodicWorkPolicy.UPDATE` | battery not low (no storage-not-low on purpose) | `backups.backupIfDue()`; `Postponed` → retry; other errors retry < 3 |
| `sync-periodic` | `SyncWorker` | `Work.schedule()` | periodic 30 min, KEEP | CONNECTED + battery not low; EXPONENTIAL 30 s | skips if `sync.recentlyDone(15 min)` on first attempt; `sync.sync(provider)`; `AuthNeeded`/`Problem` → failure; else retry < 5 |
| `sync-soon` | `SyncWorker` | `Work.syncSoon()` via `AppGraph.syncSoon()` (≤ 1/min, only if `db.syncEnabled`) | one-off, 2 min delay, KEEP | CONNECTED + battery not low; EXP 30 s | fallback if app closes; cancelled by `AutoSync` after an in-app round (`Work.cancelSyncSoon`) |
| `error-reports` / `error-reports-later` / `error-reports-later-2` | `ReportWorker` | `ErrorReports` (`Work.sendReports`, `later` = 6 h) | one-off, KEEP, tagged with its name | CONNECTED; EXP 1 min | `ErrorReports.sendPending`: DONE / LATER (reschedule under the other name) / RETRY (< 8) |
| `app-updates-daily` (+ `app-updates-soon`) | `UpdateWorker` | `AppUpdates.schedule()` (from `atStart` :163 and `setAutomatic` :137) via `Work.updates(on, soon)`; on only for release-signed, non-debug, non-Play installs with automatic on | periodic 24 h KEEP (+ one-off when last check > 1 day) | CONNECTED + battery not low + storage not low; EXP 10 min | `updates.background()` checks + downloads; retry < 3 |
| `daily-report-drive` (+ `daily-report-drive-soon`) | `DailyReportWorker` | `DailyReportUpload.turnOn/turnOff/atStart` via `Work.dailyReport(on, soon)` | periodic 3 h KEEP (+ one-off when a day waits) | CONNECTED; EXP 10 min | `dailyReport.upload()`; `AuthNeeded` → success (waits for owner); IOException → `Log.w`, retry < 5 |

In-app (not WorkManager): `AutoSync` (sync rounds while the app runs), `PrinterService` thread, `SppScanner`, `PerfRunner` (bounded wake lock),
`ProductCsvService.startImport` (in `appScope`), `CheckoutService.start` (in `appScope`). Every `Work.*` call is wrapped in `safely { }` (never throws).

---

## 8. Tests

### 8.1 How to run
- JVM: `.\gradlew.bat :core:test :app:testDebugUnitTest` (local). `:app` JVM tests have **no Android runtime** (no Robolectric; no `unitTests.isReturnDefaultValues`), so only pure code is testable there.
- Instrumented: CI `scripts/ci/instrumented.sh` (API 21 1 GB and API 36) runs `am instrument -w -e notClass com.lekaspos.perf.PerfSuiteTest com.lekaspos.app.debug.test/androidx.test.runner.AndroidJUnitRunner`.
  Tablet job runs only `ScreensSmokeTest,ScreenshotsTest` (`scripts/ci/screenshots.sh`). Perf: `perf.yml` → `scripts/ci/perf.sh <scale>`.
- Test method names: camelCase only (DEX < API 30 rejects spaces).
- **Release smoke** is not a test class: `scripts/ci/release-smoke.sh` (CI job `release-smoke`, API 21 + 36) installs the R8 release APK over the
  `PREV_APK_URL` release and fresh, keeps the selling screen open 20 s and fails on `FATAL EXCEPTION`. It is the only check of the minified build.
- Category index: **API 21 SQLite checks** = every instrumented DB test run on the API 21 image (explicitly `SchemaTest`, `QueryPlansTest`, `ZxingTest`
  for API-21 library behaviour); **migration** = `MigrationTest` + JVM `SchemaSnapshotTest`; **merge** = `SyncMergeTest`, `VoidConflictTest`, `LwwWriteTest`;
  **perf** = `PerfSuiteTest`, `SoakTest`, `QueryPlansTest`; **screenshots** = `ScreenshotsTest` (+ `CustomerScreenTest` pictures); **every screen** = `ScreensSmokeTest`.

### 8.2 Fixtures and helpers (`AT/testing/`)
| Helper | API | Use |
|---|---|---|
| `TestDb` (`AT/testing/TestDb.kt`) | `context` (target), `testContext` (test APK assets), `fresh(name)` → `Db` on a new file, `delete(db)`, `product(db, name, price, codes, categoryId, sku, trackStock, active, cost, taxRateId)` (via synced `ProductDao.create`), `sellable(db, id)`, `saleDraft(db, items, soldAt, payKind, staffId, billDiscount)` (priced with `PricingEngine`, cash via `Settlement`) | DAO-level tests: `db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(id to 1000L)), tz) }` |
| `TestGraph` (`AT/testing/TestGraph.kt`) | `create(name)` (fresh file + `staff.load()`), `reopen(name)` (process restart), `unloaded(name)` (nothing loaded — like a screen restored by Android), `close(graph)`, `destroy(graph)` | Domain tests through real services: `val g = TestGraph.create(); g.cart…; g.checkout.complete(...)`; always `destroy` in `@After`. |
| UI tests | use the **app's own** `LekasApp.graph(ctx)`; in `@Before`: `settings.markSetupDone()`, `ErrorReports.setConsent(ctx, false)`, `staff.load()` (ScreensSmokeTest.kt:79-84); `ActivityScenario.launch` | Screens with PIN login off. |
| `AT/perf/HeapDumps.kt` | saves `files/leaks/*.hprof` when `SoakTest` finds a leak | CI prints the chain with shark-cli. |

### 8.3 `:core` JVM tests (`CT/`)
| File | Purpose |
|---|---|
| `barcode/BarcodeTest.kt` | Gtin check digits/variants, ScaleTemplate parsing. |
| `cart/CartTest.kt` | Cart add/merge/pack lines and pricing. |
| `csv/CsvInputTest.kt` | Detecting xlsx/encodings of imported files. |
| `csv/CsvTest.kt` | CsvWriter/CsvReader RFC 4180, limits, delimiters. |
| `csv/ImportProgressTest.kt` | Import resume point. |
| `csv/ProductCsvRulesTest.kt` | Product CSV headers, tax cells, examples, limits. |
| `diag/CrashTextTest.kt` | Fingerprints, titles, scrubbing. |
| `display/CustomerViewTest.kt` | Customer screen states. |
| `escpos/EscPosTest.kt` | ESC/POS bytes, text modes. |
| `escpos/MonoImageTest.kt` | 1-bit raster layout. |
| `id/IdsTest.kt` | Id layout, allocator reservations. |
| `inventory/InventoryMathTest.kt` | Average cost, stock values. |
| `money/MoneyFormatTest.kt` | Format/parse money & qty. |
| `money/RoundingTest.kt` | Half-up, allocation (money.md §9 table). |
| `pricing/PricingEngineTest.kt` | Bill totals, tax, discounts. |
| `pricing/PromotionsTest.kt` | money.md §11 examples. |
| `pricing/QuickCashTest.kt` | Cash note buttons. |
| `pricing/SettlementTest.kt` | Tenders, change, cash rounding. |
| `receipt/ReceiptLayoutTest.kt` | Receipt grid layout. |
| `refund/RefundsTest.kt` | Refundable parts, amounts. |
| `report/MonthFilesTest.kt` | Months due for the Drive report. |
| `report/PeriodsTest.kt` | Presets, comparisons. |
| `report/RangePlanTest.kt` | Year/month/day pieces. |
| `scan/ScanBufferTest.kt` | Scanner vs typing. |
| `shift/CashCountTest.kt` | Drawer count. |
| `shift/ShiftGuideTest.kt` | OPEN/NEW_DAY/HANDOVER, `LeftInDrawer`. |
| `shift/ShiftReportTest.kt` | Shift report maths/layout. |
| `staff/StaffChecksTest.kt` | `Checks.of`, ranking (D-067). |
| `staff/StaffRulesTest.kt` | PIN hash, lockout, `Perm` rules (UNVERIFIED exact coverage). |
| `sync/LwwTest.kt` | Field versions, `winningFields`, `stampAbove`. |
| `sync/SyncNamesTest.kt` | File names, cursors. |
| `text/DigitEntryTest.kt` | Keypad entry and preset. |
| `text/SearchTextTest.kt` | Normalization, FTS query. |
| `text/TextWidthTest.kt` | Column widths. |
| `time/ClockCheckTest.kt` | Clock sanity. |
| `time/DateTextTest.kt` | Date text. |
| `time/DaysTest.kt` | Epoch days, weeks, months. |
| `time/HlcTest.kt` | HLC now/observe/future limit. |
| `update/ReleasesTest.kt` | Release choice, notes parsing. |

### 8.4 `:app` JVM tests (`UT/`)
| File | Purpose |
|---|---|
| `data/backup/BackupFolderNamesTest.kt` | Owner's own backup names are never pruned as daily copies. |
| `data/customer/PhoneFormsTest.kt` | Phone number forms (+60 vs 0). |
| `data/db/SchemaSnapshotTest.kt` | **Schema guard**: `<VERSION>.sql` snapshot equals `Schema.STATEMENTS` (writes a missing snapshot then fails); no syntax slips; LWW tables have LWW columns; every table in exactly one sync class; no forbidden SQL (`ON CONFLICT`, `RETURNING`, ` OVER (`, `IIF(`, `FILTER (`, `NULLS FIRST/LAST`, `fts5`, `GENERATED ALWAYS`); snapshots exist for 1..VERSION. Reads `src/androidTest/assets/schemas` relative to `app/`. |
| `data/sale/ReceiptNumbersTest.kt` | Receipt lookup by partial number. |
| `data/settings/SettingsModelTest.kt` | Store/device settings defaults, round trip, bad values. |
| `domain/sale/RefundDraftTest.kt` | Refund drafts (rounding mirror, cost). |
| `domain/sell/CheckoutDraftTest.kt` | `CheckoutService.draft` columns. |
| `hw/printer/RenderHeightTest.kt` | Image receipt height. |
| `hw/printer/RenderRunsTest.kt` | Text runs at printer columns. |
| `hw/printer/TestPageTest.kt` | Test page fits and uses text mode. |
| `perf/QueryPlanAliasTest.kt` | `QueryPlans.tableAliases` for modern plan output. |
| `sync/drive/DriveErrorsTest.kt` | Drive errors → codes. |
| `sync/drive/DriveTimeTest.kt` | RFC 3339 times without java.time. |
| `ui/common/CsvCharsetTest.kt` | Charset of imported files decided by the whole file. |

### 8.5 Instrumented tests (`AT/`) — all must pass on the **API 21 image** (SQLite 3.8.x) = the SQLite 3.8.4 check
| File | Kind | Purpose |
|---|---|---|
| `app/AppUpdatesTest.kt` | network | GitHub JSON, HTTPS on Android 5, SHA-256 and APK checks. |
| `app/ErrorReportsTest.kt` | network | Report files kept/cleaned; relay reachable (test header). |
| `data/LwwWriteTest.kt` | DB/sync | Local LWW edits: field versions and outbox events. |
| `data/backup/BackupTest.kt` | DB | Consistent backup while selling, restore identity rules, invalid files, keeps 7. |
| `data/backup/DataSafetyTest.kt` | DB | Folder copy, protection status, quick_check. |
| `data/db/DamagedDatabaseTest.kt` | DB | Corrupt DB kept aside, empty store opens. |
| `data/db/DbTest.kt` | DB | Main-thread refusal, writer serialization, rollback, id reservation, HLC persistence, nested writes. |
| `data/db/DerivedConsistencyTest.kt` | DB | Incremental derived tables == `DerivedRebuild`. |
| `data/db/MigrationTest.kt` | **migration** | Every `assets/schemas/<v>.sql` migrates to the fresh schema; v3 role perms. |
| `data/db/SchemaTest.kt` | **SQLite check** | WAL/sync pragmas, every declared table exists, seeds, FTS4 prefix, logs SQLite version. |
| `data/print/PrintJobDaoTest.kt` | DB | Queue order, drawer first, receipt expiry. |
| `data/product/ProductDaoTest.kt` | DB | Barcode variants, packs, PLUs, duplicates, search. |
| `data/sale/SaleDaoTest.kt` | DB | Commit, receipt numbers, outbox only when sync on, refunds, voids, history paging. |
| `data/sale/VoidConflictTest.kt` | DB/sync | Same credit sale voided on two tills. |
| `data/shift/ShiftDaoTest.kt` | DB | Voids belong to the shift they happen in. |
| `data/stock/StockTest.kt` | DB | Stock = last count + later events by HLC. |
| `domain/FirstRunSetupTest.kt` | domain | Welcome screen only on fresh install. |
| `domain/customer/CustomerCreditTest.kt` | domain | Credit sales, repayments, limits, refunds/voids, paging. |
| `domain/inventory/InventoryTest.kt` | domain | Receive, average cost, counts, adjustments, low stock. |
| `domain/print/ReceiptBuilderTest.kt` | domain | Receipt from stored sale; CJK → image. |
| `domain/products/ProductCsvServiceTest.kt` | domain | Export/import round trips, problems, barcode rules. |
| `domain/promo/PromotionFlowTest.kt` | domain | Promotions end to end. |
| `domain/report/DailyReportUploadTest.kt` | domain | Drive daily report with an in-memory folder. |
| `domain/report/ReceiptDaysTest.kt` | domain | Receipts export by stored business day. |
| `domain/report/ReportTest.kt` | domain | Month/year tables equal days, report screen + permission, categories, voids. |
| `domain/sale/SaleActionsTest.kt` | domain | Refunds, voids, copies, drawer, audit. |
| `domain/sell/CartSessionTest.kt` | domain | Scan/merge/restart, held bills, manager help, absurd qty. |
| `domain/sell/PopularItemsTest.kt` | domain | Popular ranking. |
| `domain/sell/PriceCheckTest.kt` | domain | Price check lookups. |
| `domain/sell/SellingFixesTest.kt` | domain | Review fixes: credit, audit, cart writer, drawer. |
| `domain/shift/ShiftTest.kt` | domain | Expected cash, required shift, cash moves permission. |
| `domain/staff/StaffCheckTest.kt` | domain | Shared till: removals, handover, help, staff check (D-067). |
| `domain/staff/StaffTest.kt` | domain | PIN login, approvals, lockout waits, idle lock. |
| `hw/ZxingTest.kt` | API 21 | ZXing 3.3.3 works below API 24. |
| `hw/printer/BandedRenderTest.kt` | render | Banded image render == whole page. |
| `perf/PerfSuiteTest.kt` | **perf** | Generates data, runs suite; query plans always enforced; budgets only with `-e assertBudgets true`; `-e perfScale TINY\|QUICK\|FULL`. Excluded from the normal instrumented run. |
| `perf/QueryPlansTest.kt` | **plan check** | Registered queries pass; checker flags full scans, correlated scans, sorts. |
| `perf/SoakTest.kt` | perf/leak | 1,000-sale soak heap budget, leaked screens (heap dump). |
| `sync/SyncMergeTest.kt` | **merge** | 35 multi-till scenarios over a `FolderProvider`; `assertConverged` compares `Schema.LWW_TABLES + EVENT_TABLES` (minus version columns), `stock_level`, `customer_balance` and a hard-coded column list of every `sum_*` table, then checks derived == `DerivedRebuild.all`. |
| `ui/AppLanguageTest.kt` | UI | Screens follow the chosen language. |
| `ui/BackOfficeReviewTest.kt` | UI | Back-office fixes (app DB, PIN login off). |
| `ui/CustomerScreenTest.kt` | UI | Customer screen on a simulated second display. |
| `ui/IdleLockTest.kt` | UI | Idle lock after screen off/on. |
| `ui/LineControlsTest.kt` | UI | Line buttons fit. |
| `ui/ScreensSmokeTest.kt` | **open every screen** | Explicit lists of Activity classes in `secondaryScreensOpen` (:96), `stockScreensOpenWithTheirData`, `staffShiftAndCustomerScreensOpen`, `reportAndImportScreensOpen`, plus lock screen and selling-screen checks; a new screen must be added to one of these lists. |
| `ui/ScreenshotsTest.kt` | **screenshots** | Main screens in EN + MS to external `files/screens/` (CI artifact). |
| `ui/SidewaysLayoutTest.kt` | UI | Payment, `PadDialog` (`sidewaysBody()`), PIN dialog fit a 336dp-high sideways phone. |
| `ui/TopBarLayoutTest.kt` | UI | Top bar pills never cut off. |
| `ui/common/SharedFilesTest.kt` | files | Shared-file cleanup rules. |
| `ui/products/PicturesTest.kt` | images | Photo → square JPEG. |
| `util/ErrorLogTest.kt` | log | Error log written in release builds. |

Schema snapshots: `app/src/androidTest/assets/schemas/1.sql` … `9.sql` (one per DB version; frozen).

---

## 9. Non-code parts

### 9.1 Gradle
| Item | Where | Notes |
|---|---|---|
| Version catalog | `gradle/libs.versions.toml` | agp 9.4.1, kotlin 2.4.20, coroutines 1.11.0, androidx.core 1.17.0, recyclerview 1.4.0, zxing 3.3.3, work 2.10.5, play-services-auth 21.4.0; tests junit 4.13.2, androidx.test runner/core 1.7.0, ext-junit 1.3.0. Pins explained inline. |
| Settings | `settings.gradle.kts` | modules `:core`, `:app`; `FAIL_ON_PROJECT_REPOS`. |
| Root build | `build.gradle.kts` | plugin aliases only. |
| Gradle props | `gradle.properties` | `-Xmx1536m`, caching + configuration cache on, `parallel=false`, Kotlin in-process. |
| `:core` build | `core/build.gradle.kts` | Kotlin JVM, Java 8 target, `-Xjdk-release=1.8`, `com.android.lint` plugin for `checkDependencies`. |
| `:app` build | `app/build.gradle.kts` | namespace `com.lekaspos`, applicationId `com.lekaspos.app` (debug suffix `.debug`), minSdk 21, target/compile 36, **`versionName = "1.13.0"` at :33**, `versionCode = -Plekas.ciRun` (else 1); `localeFilters en, ms`; bundle language split off; release `isMinifyEnabled` + `isShrinkResources`; `BuildConfig.SIGNING_KEY` (`debug` / `release` / `upload`); Java/JVM 17. |
| Signing | `app/build.gradle.kts:12-17,50-73` + git-ignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`, `keyKind`) | CI writes it from secrets `TEST_KEYSTORE_BASE64`, `TEST_KEYSTORE_PASSWORD`, `TEST_KEY_ALIAS` (ci.yml:37-55). |
| Lint config | `app/build.gradle.kts:96-104` (no `lint.xml`) | `abortOnError`, `checkDependencies`, `checkReleaseBuilds`; disabled: `OldTargetApi`, `GradleDependency`, `NewerVersionAvailable`. CI runs `:app:lintRelease`. |
| R8 rules | `app/proguard-rules.pro` | strip `android.util.Log.v/d`; `-repackageclasses 'l'`; keep `SourceFile,LineNumberTable`; `-keep,allowshrinking,allowoptimization class com.lekaspos.** { *; }` (names stable for report fingerprints); `-keep class * extends androidx.room.RoomDatabase { <init>(); }` (WorkManager's DB, crashed a release build). |

### 9.2 GitHub Actions (`.github/workflows/`)
| Workflow | Trigger | Jobs |
|---|---|---|
| `ci.yml` | push `main`, PRs, manual; concurrency cancels older runs | **build**: release key from secrets → `:core:test :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest -Plekas.ciRun=<run>`; APK < 8,388,608 bytes; artifacts `apks` (14 d), `mapping` (90 d), `build-reports`. **release-smoke** (API 21 1 GB / API 36 2 GB): `scripts/ci/release-smoke.sh` with `PREV_APK_URL` (ci.yml:136, now `…/v1.13.0/LekasPOS-1.13.0.apk`). **tablet** (API 36 `pixel_tablet`, landscape): `scripts/ci/screenshots.sh`. **instrumented** (API 21 + 36): `scripts/ci/instrumented.sh`, artifact `instrumented-api*`. |
| `perf.yml` | manual, input `scale` QUICK/FULL | build APKs → perf on API 21, 29 (SQLite 3.22, the store tablet's Android 10) and 36 via `scripts/ci/perf.sh`; artifacts `perf-<scale>-api*` (30 d). |
| `relay.yml` | push/PR touching `relay/**`, manual | `node --test` in `relay/`; on `main`: `wrangler@4 deploy` + `secret put REPORTS_TOKEN` (needs `CLOUDFLARE_API_TOKEN`, var `CLOUDFLARE_ACCOUNT_ID`); then POSTs a test report (`x-lekas-test: 1`) expecting 204. |
| `pages.yml` | push touching `site/**`, manual | Deploys `site/` to GitHub Pages (https://faizoken.github.io/LekasPOS/). |

### 9.3 Scripts
| Script | What |
|---|---|
| `scripts/setup-toolchain.ps1` | Installs JDK 21, SDK cmdline tools, platform 36, build-tools, platform-tools; `-WithEmulator` adds emulator + API 21/36 images. |
| `scripts/create-avds.ps1` | Creates `lekas-api21` (1 GB) and `lekas-api36` (2 GB) AVDs. |
| `scripts/run-perf.ps1` | `-Scale TINY\|QUICK\|FULL [-Serial] [-Apk] [-NoInstall] [-ScreenOff]`: installs release APK, starts Diagnostics with `--es autorun`, waits for `LekasPerf: DONE`, pulls reports to `perf-results/<device>/`. |
| `scripts/measure-startup.ps1` | `-Runs N`: cold start via `am start -S -W` + "Fully drawn" from logcat. |
| `scripts/ci/instrumented.sh` | Installs debug + test APKs, runs all instrumented tests except `PerfSuiteTest` (`TEST_TIMEOUT` 30m, per-test 300 s), streams logcat, pulls `files/screens`, analyses `files/leaks/*.hprof` with shark-cli 2.14. |
| `scripts/ci/release-smoke.sh` | Installs `PREV_APK_URL` (if set), runs it, installs new release over it, runs 20 s, then `pm clear` and runs fresh; fails on `FATAL EXCEPTION` or dead process. |
| `scripts/ci/screenshots.sh` | Tablet landscape: `ScreensSmokeTest,ScreenshotsTest`, pulls screenshots. |
| `scripts/ci/perf.sh` | `[TINY\|QUICK\|FULL]`: PerfSuiteTest (debug), release in-app runner (Diagnostics autorun), cold start ×10 (details beyond the header UNVERIFIED). |

### 9.4 `relay/` — Cloudflare Worker for error reports (D-057)
- `relay/worker.js`: `POST /v1/report` only (404 other paths, 405 other methods); header `x-lekas-key` must equal `REPORT_KEY` (401); body ≤ 64 KB (413);
  per-IP rate limit `LIMITER` 20/min (429); `clean()` validates (kinds `crash, anr, native, killed, error, check, manual`; build ≤ `MAX_BUILD`, default 10000);
  `x-lekas-test: 1` → 204 without filing. `file()` files GitHub issues in `REPORTS_REPO` (`FaizoKen/LekasPOS-reports`): `manual` → new issue labelled `manual`;
  others → one issue per fingerprint label `fp:<fp>` (labels `[kind, fp:…]`), stats block updated, comments on a new build, reopened with `regression`
  when a closed bug returns in a newer build (unless `not_planned`/`wontfix`/`not-a-bug`); at most `MAX_NEW_ISSUES` (default 30) new issues/hour (429).
- `relay/worker.test.js` (`node --test`), `relay/wrangler.toml` (name `lekaspos-reports`, `REPORT_KEY` must match `ErrorReports.KEY` at ErrorReports.kt:58, optional `MAX_BUILD`/`MAX_NEW_ISSUES`, ratelimit binding), `relay/package.json`.

### 9.5 `site/`
`guide.html` / `panduan.html` (the user guide, English / Malay — keep both true, `recipes.md` §0), `llms.txt` (summary for AI assistants),
`index.html` (download button → `releases/latest/download/LekasPOS.apk`, install/update steps EN + MS), `privacy.html` (EN) and `privasi.html` (MS) — must say the
same and share "Last updated", `style.css`, `icon.svg`, `img/` (screenshots, e.g. `en-pay.png`, `customer-bill.png`). Published by `pages.yml`.

### 9.6 Resources (`RES/`)
`values/` + `values-ms/`: `strings.xml` (496/495 entries), `strings_{a11y,cashier,display,fraud,hw,look,promo,reports,safety,staff,sync,update}.xml` (same files in both
languages); `themes.xml` (styles `Theme.Lekas`, `Widget.Lekas.{Button.Primary,Button.Secondary,Button.Danger,Key,Toggle,…}`, `Text.Lekas.{Body,Caption,Label,Section,Display,Tag,Empty}`),
`colors.xml`, `ids.xml`; `values-land/layouts.xml` and `values-sw600dp/layouts.xml` alias `activity_sell` → `activity_sell_two_pane` (selling screen only);
`layout/screen.xml` (ScreenActivity scaffold: `root`, `top_bar`, `back`, `title`, `actions`, `content`), `list_plain.xml`, `list_header.xml`, `list_with_search.xml`, `item_row.xml`,
`top_action.xml`, selling-screen layouts; `xml/file_paths.xml`, `xml/locales_config.xml`, `xml/data_extraction_rules.xml`.

---

## 10. Stable INTEGER / string codes — never renumber, never reuse (append only)

All in `C/model/Codes.kt` unless stated. Stored in the DB and in sync files.

| Object (line) | Stored in | Values |
|---|---|---|
| `SaleKind` (:8) | `sale.kind` | SALE=0, REFUND=1 |
| `SaleStatus` (:13) | `sale.status` | COMPLETED=0, VOIDED=1 |
| `PaymentKind` (:18) | `payment_method.kind`, `payment.kind` | CASH=1, CARD=2, EWALLET=3, CREDIT=4, OTHER=9 |
| `SellMode` (:26) | `product.sell_mode`, `cart_line.sell_mode` | UNIT=0, WEIGHT=1, OPEN_PRICE=2 |
| `BarcodeKind` (:32) | `product_barcode.kind` | BARCODE=0, SCALE_PLU=1 |
| `MovementKind` (:37) | `stock_movement.kind` | RECEIVE=1, ADJUST=2, WASTE=3, RETURN_TO_SUPPLIER=4, TRANSFER_IN=5, TRANSFER_OUT=6, OPENING=7 |
| `CashMoveKind` (:47) | `cash_movement.kind` | CASH_IN=1, CASH_OUT=2, DROP=3 |
| `CreditKind` (:53) | `credit_entry.kind` | CHARGE=1, PAYMENT=2, ADJUST=3 |
| `DiscountKind` (:59) | `cart.bill_disc_kind`, `cart_line.disc_kind` | NONE=0, AMOUNT=1, PERCENT=2 |
| `CartStatus` (:65) | `cart.status` | OPEN=0, HELD=1 |
| `PrintJobStatus` (:70) | `print_job.status` | PENDING=0, DONE=1, FAILED=2 |
| `SysRole` (:76) | `role.sys_role` | NONE=0, OWNER=1, MANAGER=2, CASHIER=3 |
| `AuditAction` (:84) | `audit_log.action` | SALE_VOID=1, REFUND=2, PRICE_OVERRIDE=3, LINE_DISCOUNT=4, BILL_DISCOUNT=5, DRAWER_OPEN=6, REPRINT=7, PRODUCT_PRICE_CHANGE=8, PRODUCT_DELETE=9, BILL_CANCEL=10, APPROVAL=11, SIGN_IN=12, PIN_LOCKOUT=13, STAFF_CHANGE=14, ROLE_CHANGE=15, SHIFT_OPEN=16, SHIFT_CLOSE=17, CASH_IN=18, CASH_OUT=19, CASH_DROP=20, CREDIT_ADJUST=21, CREDIT_OVER_LIMIT=22, OWNER_PIN_RESET=23, PRODUCT_IMPORT=24, PROMOTION_CHANGE=25, CREDIT_LIMIT_CHANGE=26, SETTINGS_CHANGE=27, CREDIT_PAYMENT=28, LINE_REMOVE=29, LINE_REMOVE_AFTER_PAY=30, BILL_CANCEL_AFTER_PAY=31, STOCK_WRITE_OFF=32, SHIFT_CONTINUED=33, FLOAT_DIFFERENCE=34 (next free: 35). Sets: `CLEARED`, `REMOVED`, `AFTER_PAY`. |
| `Perm` (:167) | `role.perms` bitmask (Long); `audit_log.amount` of APPROVAL | VOID=1<<0, REFUND=1<<1, PRICE_OVERRIDE=1<<2, DISCOUNT=1<<3, OPEN_DRAWER=1<<4, REPRINT=1<<5, MANAGE_PRODUCTS=1<<6, SETTINGS=1<<7, CANCEL_BILL=1<<8 (retired, D-063), MANAGE_STOCK=1<<9, MANAGE_STAFF=1<<10, CASH_MOVE=1<<11, SHIFT_REPORT=1<<12, VIEW_AUDIT=1<<13, CUSTOMERS=1<<14, CREDIT_SALE=1<<15, CREDIT_LIMIT=1<<16, REPORTS=1<<17 (next free bit: 18); ALL=−1. `LIST` (role-editor order), `ROLE_EDITOR` (= LIST − CANCEL_BILL), `DEFAULT_MANAGER`, `DEFAULT_CASHIER`, `TILL_HELP` (DISCOUNT, PRICE_OVERRIDE, CUSTOMERS, CREDIT_SALE), `effective`, `has`, `merge`, `lacksAny`, `addsTo`. Frozen numeric values used in migrations: 129919 (manager v3), 49184 (cashier v3), 131072 (REPORTS v4). |
| `CountSessionStatus` (:256) | `count_session.status` | OPEN=0, FINISHED=1 |
| `PrintJobKind` (:262) | `print_job.kind` | RECEIPT=1, REPRINT=2, TEST=3, DRAWER=4, SHIFT=5 (`ref_id` = shift id) |
| `PromoKind` (:273) | `promotion.kind` | MULTI_PRICE=0, BUY_GET_FREE=1 |
| `Entity` (:282) | `outbox.entity`, segment `"e"`, `audit_log.entity`, `sync_deferred.entity` | LWW: SETTING=1, ROLE=2, STAFF=3, TAX_RATE=4, CATEGORY=5, PRODUCT=6, BARCODE=7, SUPPLIER=8, CUSTOMER=9, PAYMENT_METHOD=10, SHIFT=11, COUNT_SESSION=12, PROMOTION=13, PRODUCT_LOOK=14; EVENT: SALE=20, SALE_VOID=21, STOCK_MOVE=22, STOCK_COUNT=23, PURCHASE=24, CASH_MOVE=25, CREDIT=26, AUDIT=27, PRODUCT_IMAGE=28 (next free: LWW 15–19, EVENT 29) |
| `TileColor` (:316) | `product_look.color`, `category.color` | NONE=0, RED=1, PINK=2, PURPLE=3, INDIGO=4, BLUE=5, TEAL=6, GREEN=7, LIME=8, AMBER=9, ORANGE=10, BROWN=11, GREY=12 (`known()` maps unknown → NONE) |
| `EventOp` (:342) | `outbox.op`, segment `"o"` | LWW=1, INSERT=2 |
| `AdjustReason` (`C/inventory/AdjustReason.kt:13`) | `stock_movement.reason` text `"code[: note]"` | damaged, expired, lost, theft, own_use, returned, found, correction, other (codes are strings; never rename) |
| `DeviceSettings` modes (`A/data/settings/Settings.kt:254-263`) | meta `dev.printer.mode`, `dev.tiles` | MODE_AUTO=0, MODE_TEXT=1, MODE_IMAGE=2; TILES_LARGE=0, TILES_MEDIUM=1, TILES_SMALL=2; paper 58/80/81 |
| `ErrorReports` consent (`A/app/ErrorReports.kt:60-62`) | prefs `lekas_reports.consent` | UNASKED=0, ON=1, OFF=2 |
| `DrawerPulse.pin` (`C/escpos/EscPos.kt:19`) | meta `dev.drawer.pin` | 0 = connector pin 2, 1 = pin 5 |
| Enum **names** persisted by `name` (renaming breaks restore of state only) | `Preset` in ReportsActivity instance state (`report.preset`, ReportActivities.kt:61/87) | — |
| Enums **not persisted** (safe to reorder, but check): `TextMode` (derived from `dev.printer.chinese`), `Granularity`, `RangePlan.Kind`, `ShiftGuide.Ask`, `Restore.Mode`, `ReportService.Export`, `ActionRefused.Reason`, `PerfScale` (name used by scripts/CI: TINY/QUICK/FULL) | — | UNVERIFIED that none is ever stored by ordinal elsewhere; grep before changing. |

Store setting keys (`SettingKeys`, Settings.kt:20, "never rename a key"): `store.name/address/phone/email/brn/sst_no/tin`, `receipt.header/footer/lang/logo/copies/einvoice_qr/einvoice_url`,
`tax.prices_include`, `currency.symbol/decimals/cash_step`, `scale.templates`, `shift.required`, `shift.handover`, `credit.enabled`, `owner.recovery`.
Per-till meta keys: `dev.printer.{address,name,paper,mode,chinese,codepage,cut,feed,auto,native_qr}`, `dev.drawer.{enabled,pin}`, `dev.scanner.{address,name}`,
`dev.camera.enabled`, `dev.lock.{minutes,after_sale}`, `dev.tiles`, `dev.customer_screen`, `dev.setup_done`, `dev.popular`, `dev.backup_*`, `dev.db_problem`.
Other LOCAL meta: identity (`device_uuid`, `device_no`, `store_uuid`, `created_at`, `id_reserved`, `hlc_last`, `sync_enabled`, `receipt_prefix`, `doc_seq_<kind>`),
`sync.*`, `session.*`, `pin.*`, `shift.left`, `upgrade.lock_default`, `upgrade.shifts_on`, `draft.receive`, `draft.receive.done`, `import.products.progress`,
`drive_report.*`, `identity.renew`.

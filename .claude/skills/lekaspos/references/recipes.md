# Recipes — how to make common changes

Verified against commit `a75b0a3` (2026-10-08, `versionName 1.13.0`, schema v9). Paths: `C/` = `core/src/main/kotlin/com/lekaspos/core/`,
`A/` = `app/src/main/java/com/lekaspos/`, `AT/` = `app/src/androidTest/java/com/lekaspos/`, `UT/` = `app/src/test/java/com/lekaspos/`,
`RES/` = `app/src/main/res/`. Each recipe names a real example to copy. Anything not checked in code is marked **UNVERIFIED**.
Rules already in `SKILL.md`/`references/*.md` are referenced, not repeated in full.

Local verification loop for every recipe (Windows, repo root): `.\gradlew.bat :core:test :app:testDebugUnitTest` and, when UI/resources
changed, `.\gradlew.bat :app:lintRelease`. Instrumented tests (API 21 + 36) run in CI (`ci.yml`) on push; do not run an emulator next to Gradle
on the laptop.

---

## 0. Change something users see — keep the user guide true

The user guide is the shops' manual: `site/guide.html` (English) and `site/panduan.html` (Bahasa Melayu), published
with the website by `pages.yml` (https://faizoken.github.io/LekasPOS/guide.html). Both are plain HTML (no build step)
styled by `site/style.css` (`body.guide`). `site/llms.txt` summarises them for AI assistants.

When a change alters what an owner, manager or cashier sees or does — a label, a step, a default, who may do it, a
message, a new feature — in the same change:
1. Find every mention: search both files for the old label (EN in `guide.html`, the `values-ms` text in `panduan.html`)
   and for the feature's chapter (`id="…"` anchors are the same in both files, e.g. `#payment`, `#shifts`).
2. Edit **both** languages. Quote labels exactly as the strings say (`values/` for English, `values-ms/` for Malay) and
   put them in `<b>…</b>`; menu paths as **Menu → Manage shop › → …**. Keep `id`s identical in both files; a new section
   gets the same new `id` in both (the table of contents is a hand-kept `<nav class="toc">` list — add it there too).
3. Plain language: short sentences, the shop's point of view, no code words (say "till", not "device"; "a manager's
   PIN", not "approval token"). Facts must match the code; `references/features.md` is the quick check.
4. If a screen in a screenshot changed visibly, refresh the screenshots (§0a).
5. Update the "For LekasPOS x.y.z · Last updated …" line at the top of both files when the release ships, and
   `references/features.md` for the behaviour.
6. Run `node scripts/check-site.mjs` (repo root): links, pictures, balanced tags, unique ids, and the same sections in
   both languages. `pages.yml` runs it before publishing the website.

## 0a. Refresh the guide's screenshots

The pictures in `site/img/` come from the CI `ScreenshotsTest` (phone, API 36) and the tablet job — never from a
personal phone (no shop data in public pictures):
1. Take the latest green CI run on `main`: `gh run list --workflow ci.yml --limit 1`.
2. `gh run download <run-id> -n instrumented-api36 -D <tmp>` (phone pictures in `screens-api36/`, `en-*.png` and
   `ms-*.png`) and `-n tablet` (in `screens-tablet/`).
3. Copy the changed phone pictures over `site/img/<lang>-<name>.png` (same names). Tablet pictures are resized to
   1280 px wide and saved as `site/img/<lang>-tablet-<name>.png` (PowerShell `System.Drawing` or any image tool) to
   keep the page light. `customer-bill.png` comes from `en-customer-bill.png`.
4. A new screen in the guide: add it to `ScreenshotsTest`'s screen map first (recipe 1 §7), then copy as above.

---

## 1. Add a new back-office screen

**Copy:** `A/ui/settings/AuditLogActivity.kt` (guarded, paged list) or `A/ui/inventory/SuppliersActivity.kt` (list + edit dialog, action-level
permission). For a form screen copy `A/ui/settings/StoreSettingsActivity.kt` (code-built `Form`, `STATE_FORM` save/restore).

Steps:
1. **Class** in the right `ui.<area>` package, extending `com.lekaspos.ui.common.ScreenActivity` (never plain `Activity`: you would lose
   `AppLanguage.wrap`, `Insets`, the idle lock, `LaunchGuard`, the loaded-staff gate and the failure handler — ScreenActivity.kt:49-436).
   ```kotlin
   class WidgetsActivity : ScreenActivity() {
       private val adapter = RowAdapter<Widget>(bind = { h, w -> h.set(w.name, w.note) }, onClick = { edit(it) })
       override fun onCreate(savedInstanceState: Bundle?) {
           super.onCreate(savedInstanceState)
           val v = setScreen(getString(R.string.widgets_title), R.layout.list_plain) ?: return   // top bar + Insets
           v.findViewById<RecyclerView>(R.id.list).apply { layoutManager = LinearLayoutManager(this@WidgetsActivity); adapter = this@WidgetsActivity.adapter; onNearEnd { loadMore() } }
           addAction(R.drawable.ic_add, R.string.widgets_add) { edit(null) }                    // optional top-bar action
           guard(Perm.SETTINGS)          // only if the whole screen is useless without the permission (else per-action requireAccess)
       }
       override fun onStarted(scope: CoroutineScope) { launchUi { adapter.submit(graph.db().read { WidgetDao.page(it, null) }) } }
       private fun edit(w: Widget?) = requireAccess(Perm.SETTINGS) { /* dialog, then launchUi { graph.<service>.save(...) } */ }
   }
   ```
   - Collect flows / load data only in `onStarted(scope)` (scope lives onStart..onStop). One-off jobs: `launchUi { }` (logs + error dialog,
     rethrows `CancellationException`). A save that must finish after Back: `outlivingScreen { }` (ScreenActivity.kt:404).
   - Lists: `RowAdapter` + `RecyclerView.onNearEnd` + keyset DAO pages (copy `AuditLogActivity.reload/loadMore`, :68-92). Empty text: `R.id.list_empty`.
   - Scanner input on this screen: override `screenKey(event)` (keyboard wedge, via `ui.common.ScanInput`) and `serialScans()` (SPP) — copy
     `ProductPickActivity` (ProductPickActivity.kt:42 `ScanInput`, :88-91 `screenKey` with `FieldScan` when the search field has focus, `serialScans`); the same pair exists in `CountActivity`, `ReceiveActivity`, `ProductListActivity`, `ProductEditActivity`.
   - Activity results: platform API (`startActivityForResult` + `@Deprecated onActivityResult`), pickers via `startPicker(intent, req)` (:266).
     Results can arrive before loading: call `whenLoaded()` before permission checks in `onActivityResult` (ScreenActivity.kt:347-356).
   - Layout must work at any width/orientation **without recreation** (no `-land`/`-sw600dp` resources for back-office screens, D-054).
   - State that must survive the process being killed (typed form): `onSaveInstanceState` with `Form.save()`/`restore()` (copy
     `StoreSettingsActivity`, `STATE_FORM`).
2. **Manifest** (`app/src/main/AndroidManifest.xml`): copy any back-office entry, e.g. lines 134-137:
   ```xml
   <activity
       android:name=".ui.settings.WidgetsActivity"
       android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenSize|screenLayout|smallestScreenSize|uiMode"
       android:exported="false" />
   ```
   Add `android:windowSoftInputMode="adjustResize|stateHidden"` when it has text fields. Only Sell/Lock/CameraScan/Diagnostics omit the
   rotation flags.
3. **Entry point** — pick one:
   - Settings hub: add an `Entry(R.string.widgets_title, R.string.widgets_sub, WidgetsActivity::class.java)` to the list in
     `SettingsActivity.onCreate` (SettingsActivity.kt:57-84). The hub has no guard; the screen must guard itself.
   - Selling-screen menu: in `SellActivity.showMenu` (SellActivity.kt:1580) add the string id to `items` (cashier jobs) or `office`
     ("Manage shop ›" submenu) **only when `allowed(perm)`** — SellActivity's private `allowed` is `graph.permissions.shown(perm)` (SellActivity.kt:633), so a manager's
     help also shows it (D-063: only what the person may do is listed), and a `when` branch
     `R.string.widgets_title -> startActivity(Intent(this, WidgetsActivity::class.java))`. Menu item ids are the string resource ids.
   - From another screen: `startActivity(Intent(this, WidgetsActivity::class.java))` or a `companion fun intent(ctx, id)` with extras
     (copy `StockHistoryActivity.intent`). LaunchGuard is automatic (ScreenActivity overrides `startActivityForResult`; SellActivity has its own, :553-557).
4. **Permission**: screen-level `guard(perm)` in `onCreate` (approval asked in `onStart`, Cancel finishes); action-level
   `requireAccess(perm) { }` (approval kept until the screen closes) or `withApproval(perm) { approval -> service.call(..., approval) }`
   (one action). The **domain service must also check** `graph.permissions.actor(perm, approval)` — UI checks alone are not enough.
   Hide controls with `graph.permissions.shown(perm)` (ShiftActivities.kt:279).
5. **Strings**: `RES/values/<file>.xml` **and** `RES/values-ms/<same file>.xml` (see recipe 8).
6. **Insets**: automatic through `setScreen()` (ScreenActivity.kt:89). A non-ScreenActivity must call `Insets.apply(root, topBar)` itself (SellActivity.kt:234).
7. **Tests**:
   - Add the class to a list in `AT/ui/ScreensSmokeTest.kt` (`secondaryScreensOpen`, :96, or a sibling test when it needs extras/data).
   - If it is a main screen, add a `"name" to YourActivity::class.java` pair to the screen map in `AT/ui/ScreenshotsTest.kt` (:71-83), captured in EN and MS by `mainScreensInBothLanguages`.
   - Domain/DAO tests for any new logic (recipes 5, 6).
8. **Docs**: `docs/PHASES.md` entry; `references/architecture.md` if it changes a rule; `references/codemap.md` (screen catalogue) and
   `references/features.md`; the user guide in both languages (§0).

---

## 2. Add a dialog with a number pad

**Reuse first:** `AmountDialog` (`A/ui/sell/SellDialogs.kt:49`) handles one money amount, piece count, weight or percent with all conventions:
```kotlin
AmountDialog(activity, title, AmountDialog.Kind.MONEY, graph.settings.store.value.currency, initial = current) { minor -> launchUi { … } }.show()
```
**Custom pad dialog — copy** `InventoryUi.askQty` (`A/ui/inventory/InventoryUi.kt:110-145`) or `InventoryUi.askAdjust` (pad + spinner + text field):
```kotlin
val display = TextView(a, null, 0, R.style.Text_Lekas_Display).apply { gravity = Gravity.END; setBackgroundResource(R.drawable.field_bg) }
lateinit var keypad: Keypad
keypad = Keypad(a, maxDigits = 9) { digits -> display.text = render(digits); display.replacing(keypad.replacing) } // grey while preset
val pd = PadDialog(a, title)                       // upright: title bar + info over pad; sideways: info+buttons left, pad right (D-063)
pd.info(TextView(a, null, 0, R.style.Text_Lekas_Caption).apply { text = hint })
pd.info(display, lp())
pd.pad(keypad.view)
pd.negative(a.getString(R.string.cancel))
pd.positive(a.getString(R.string.ok)) { d -> val v = value(keypad.digits) ?: return@positive; d.dismiss(); onOk(v) } // stays open until valid
val d = pd.create()
d.keys { e -> keypad.onKey(e) }                    // hardware keys; swallows Enter/Tab/Space; drops scanner bursts
keypad.preset(start)                               // shown grey, first key replaces it (:core DigitEntry)
d.show()
return d.trackedBy(a)                              // closed with the screen; dialog taps count as activity for the idle lock
```
Conventions (all verified):
- Digits fill from the right; parse with `:core` (`MoneyFormat.keypad(digits, currency)` for money; `digits.toLong() * 1000` for whole pieces;
  raw digits = milli for weights). Never `toDouble()`.
- **Always `PadDialog`** for a number/PIN pad (sideways layout); key height comes from `Context.keyHeightPx(56)` inside `Keypad`.
- **Always `.keys { keypad.onKey(it) }`** before showing, and **`.trackedBy(activity)`** after. A dialog with a text field: `d.keys { e -> if (note.hasFocus()) false else keypad.onKey(e) }` (InventoryUi.kt:213).
- A dialog without its own key handler gets the default guard from `trackedBy` (Enter/Tab/Space never press a focused button; DialogHost.kt:76-90).
- Never show on a finishing activity: `Dialogs.*` helpers check `Dialogs.canShow`; for a hand-built dialog shown after an async job, check
  `Dialogs.canShow(activity)` first.
- Touch targets ≥ 48dp (Keypad keys 56dp, 50dp on a short sideways screen).
- Tests: `AT/ui/SidewaysLayoutTest.kt` measures `PadDialog(...).sidewaysBody()` on a 336dp-high phone (`aKeypadDialogAndAPinDialogFitWhole`, :169) — add
  your dialog there if it carries more than the usual info rows. `:core` `DigitEntryTest` covers the entry logic.
- PIN entry: `PinPad` + `ui/staff/StaffUi.kt` `askPin`/`askNewPin`/`pinDialog` (uses PadDialog), approvals via `ApprovalDialog.show`.

---

## 3. Add a setting — three kinds

| Kind | Synced? | Stored in | Defaults live in | Example to copy |
|---|---|---|---|---|
| Store-wide | yes (LWW per key) | table `setting` (key/value) | `StoreSettings` constructor defaults + `StoreSettings.from` (A/data/settings/Settings.kt:49-156) | `handoverCount` / `SettingKeys.SHIFT_HANDOVER` (D-067) |
| Per till | no (LOCAL) | `meta` rows `dev.*` | `DeviceSettings` defaults + `load` (Settings.kt:209-316) | `tileSize` / `dev.tiles` (D-066) |
| Per phone, outside the DB | no | SharedPreferences file | constants in the owning object | `AppLanguage` (`lekas_ui`), `ErrorReports` consent (`lekas_reports`), `AppUpdates` (`lekas_updates`) |

### 3a. Store-wide (synced) setting — `SettingKeys.SHIFT_HANDOVER` end to end
1. `A/data/settings/Settings.kt`: add `const val MY_KEY = "area.name"` to `SettingKeys` (:20; **never rename a key**); add a field with its default
   to `StoreSettings` (:49); add it to `toMap()` (:95, booleans via `flag()` "1"/"0"); parse it in `from()` (:123) with a safe fallback (copy `b(...)`/`toIntOrNull()?.coerceIn(...)`).
2. UI: a `Form` control in `StoreSettingsActivity` (copy `handover = f.switch(...)` :139 and `handoverCount = handover.isChecked` in `save()` :190). Saving
   calls `graph.settings.saveStore(old, next)` behind `requireAccess(Perm.SETTINGS)`.
3. Read it where needed: `graph.settings.store.value.myField` (e.g. ShiftService.kt:229).
How it syncs (no extra code): `SettingsRepo.saveStore` writes **only keys the screen changed** that differ from the DB (`SettingsDao.putChanged`), stamps
`Lww.stampAbove(hlcNow, held)`, appends `Entity.SETTING` outbox when sync is on (Settings.kt:173-190), and audits `SETTINGS_CHANGE` with the key names.
Other tills apply it in `Importer.setting` (Importer.kt:145: newer `(hlc, dev)` wins) and `SyncEngine.reloadChanged` calls `settings.reload()`.
`Backfill.settings` republishes all keys when sync is turned on. **Defaults are never written** (a fresh till's defaults would override the store's
real values; `SyncDao.yieldSettings` makes a joining till yield). Tests: extend `UT/data/settings/SettingsModelTest.kt` (defaults, round trip, bad values);
a merge check exists in `AT/sync/SyncMergeTest.kt` (`creditAndSettingsTravelBetweenTills`, `aTillJoiningAStoreTakesTheStoresSettings`).

### 3b. Per-till setting (`meta dev.*`) — `tileSize` end to end
1. `DeviceSettings` (Settings.kt:209): add the field + default; a `private const val X = "dev.area.name"` key (:265-284 — must start with `dev.` because
   `load` reads the range `key >= 'dev.' AND key < 'dev/'`, :289); parse in `load()` with clamping (:295-315); write in `save()` (:318-340).
2. UI: copy `SettingsActivity.chooseTiles` (:236) → `graph.settings.saveDevice(graph.settings.device.value.copy(tileSize = …))` (NonCancellable; optional
   `then` lambda, e.g. `graph.printer.reconnect()` in PrinterSettingsActivity.kt:179). `saveDevice` does **not** check permissions — hardware settings
   are saved behind `requireAccess(Perm.SETTINGS)` (PrinterSettingsActivity.kt:172-176); look-only settings (tiles, customer screen) need none.
3. Consumers collect `graph.settings.device` (SellActivity applies `tileSize` at :428).
Not synced; included in `.lekasbak` backups (the whole DB); a restore brings back the **backup's** `dev.*` values (`Restore.afterOpen` deletes `session.*`, `pin.*`, `print_job`, and `drive_report.*` for
a NEW_DEVICE restore — Restore.kt:298-307). Other LOCAL state that is not a user setting uses its own meta key (e.g. `shift.left`, `upgrade.*` one-shot flags).
Tests: `SettingsModelTest.paperSizesAndPrinterProfile` style JVM test for parsing.

### 3c. Preferences-file setting
Use only when the value is needed **before the DB opens** (screen language in `attachBaseContext`, AppLanguage.kt:37) or must belong to the phone
**independently of the data** (error-report consent; update settings). Pattern: `context.getSharedPreferences(PREFS, MODE_PRIVATE)`, keys as private
consts (AppUpdates.kt:581-604), read off the main thread (one deliberate small read for the language, D-046). Not in backups (`allowBackup=false`,
not in `.lekasbak`), never synced.

---

## 4. Add a permission

1. `C/model/Codes.kt` `object Perm` (:167): `const val NEW_THING = 1L shl 18` (next free bit; 0–17 used, 8 retired). Never reuse a bit.
2. Add it to `Perm.LIST` (:210) in role-editor order (`ROLE_EDITOR` = LIST − CANCEL_BILL follows automatically). Decide whether it belongs in
   `DEFAULT_MANAGER` / `DEFAULT_CASHIER` (seed roles of **new** stores, used by `Seed.insert`) and in `TILL_HELP` (what a manager's help lends for one bill; never
   anything that takes money out of the drawer — void, refund, drawer, cash moves — D-067).
3. Label: `permLabel()` in `A/ui/staff/StaffUi.kt:30` → `R.string.perm_new_thing`, strings in `RES/values/strings_staff.xml` and `RES/values-ms/strings_staff.xml`
   (perm labels live there; `perm_reports` is in `strings_reports.xml`). The role editor (`StaffActivities.kt:390`) and the activity log's APPROVAL detail
   (`AuditLogActivity.permNames`, :103) pick it up from `LIST`.
4. **Existing stores' roles** (decide with the owner — a business rule): seed roles are rows with version (0,0). To grant the bit to roles nobody edited,
   add a data migration like v3→v4 (`Migrations.kt:56`): `db.execSQL("UPDATE role SET perms = perms | 262144 WHERE id = 2 AND ver_hlc = 0")` — use the
   **frozen numeric value**, not `Perm.X`, so the step never changes later. This is a DB version bump: follow recipe 5 (snapshot identical DDL under the
   new number). Edited roles (`ver_hlc > 0`) and owners (`Perm.effective(SysRole.OWNER, …) = ALL`) need nothing. Test it like
   `MigrationTest.v3GivesUneditedSeedRolesTheirDefaultPermissions` (MigrationTest.kt:64).
5. Enforce in the **domain**: `val actor = graph.permissions.actor(Perm.NEW_THING, approval)` at the start of the service call; pass `actor.approvedBy` to
   `AuditDao.log(...)`. Copy `SaleActions.void` (SaleActions.kt:153-190).
6. UI: `requireAccess`/`guard`/`withApproval` as in recipe 1 §4; show/hide controls with `graph.permissions.shown(Perm.NEW_THING)`; menu items with
   `allowed(...)`.
7. Tests: `:core` `StaffRulesTest` for `Perm` helpers if you touch `merge`/`addsTo`; instrumented domain test like `ShiftTest.cashMovementsNeedPermissionOrAManager`
   (approval path + refusal `ActionRefused.Reason.NOT_ALLOWED`).

---

## 5. Add a column or a table (schema change)

Never edit a shipped version's DDL. Current `Schema.VERSION = 9` (Schema.kt:11). Example to copy: v8→v9 (`Migrations.kt:96-101`, D-066): two new tables
via named DDL constants + one `ALTER TABLE cart ADD COLUMN pay_shown …`.

1. **Decide the sync class** (database.md §4) — LWW / EVENT / LOCAL / DERIVED — and whether a new column on a **synced** table is safe:
   older tills drop fields they do not know (`Importer.lww`/`insert` filter by `PRAGMA table_info`, Importer.kt:81,164) **for good**. Either make it a
   new entity (what D-066 did: `product_look` instead of new `product` columns) or clear cursors in the migration so updated tills re-read the store's
   files (`DELETE FROM sync_cursor`, as v5→v6 did, Migrations.kt:78; D-047).
2. **`A/data/db/Schema.kt`**:
   - Bump `VERSION`.
   - New table: a named `const val` DDL (copy `PRODUCT_LOOK` :80 / `PRODUCT_IMAGE` :90; LWW tables append `$LWW` for `deleted, created_at, updated_at,
     ver_hlc, ver_dev, fver`; EVENT tables have `hlc INTEGER NOT NULL`), placed in `STATEMENTS` (:109) and listed in exactly one of `LWW_TABLES` /
     `EVENT_TABLES` / `DERIVED_TABLES` / `LOCAL_TABLES` (:583-597).
   - New column: add it **last** in that table's DDL with the **same spelling** you use in `ALTER TABLE … ADD COLUMN` (SQLite appends the text; the
     migrated `sqlite_master` must equal a fresh one — cart's `pay_shown` is last, Schema.kt:513).
   - Indexes: plain, or partial only `WHERE deleted = 0` or a lone `WHERE col IS NOT NULL` (D-021).
3. **`A/data/db/Migrations.kt`**: append `Migration(9, 10) { db -> … }` to `ALL` (reuse the Schema constants). Column renames/drops = table rebuild inside
   the migration (FKs are off during upgrade, DbOpenHelper.kt:39-43). Data fixes use frozen literals.
4. **SQLite 3.8.4 only** (database.md §2): no UPSERT/`RETURNING`/window functions/row values/expression indexes/json1/FTS5/generated columns/`IIF`/
   `FILTER`/`NULLS FIRST|LAST`/`RENAME|DROP COLUMN`/`unixepoch()`/`wal_checkpoint(TRUNCATE)`. `SchemaSnapshotTest.noForbiddenSqlForApi21` greps a few of these.
5. **Snapshot**: run `.\gradlew.bat :app:testDebugUnitTest` — `SchemaSnapshotTest.currentSchemaMatchesItsSnapshot` writes the missing
   `app/src/androidTest/assets/schemas/10.sql` and **fails once on purpose**; review the file, commit it, re-run (green). Never edit `1.sql`…`9.sql`.
6. **Tests**:
   - `AT/data/db/MigrationTest.everySnapshotMigratesToTheFreshSchema` automatically covers every snapshot → latest. Add a focused test when the migration
     moves data (copy `v3GivesUneditedSeedRolesTheirDefaultPermissions`).
   - `AT/data/db/SchemaTest.everyDeclaredTableExists` covers the class lists. DAO tests for the new SQL (must pass on API 21).
7. **Write paths**: LWW rows only through `LwwWriter.insert/update/delete` (or a DAO wrapping it); EVENT rows with `tx.nextId()` + `tx.hlcNow()` + `tx.insert`
   + `Outbox.append` when `tx.syncEnabled` (copy `ShiftDao.insertMove`, ShiftDao.kt:161-171). LOCAL rows: plain SQL. DERIVED: update in the same
   transaction as the event that changes it **and** add the set-based rebuild to `DerivedRebuild` (`all()` :125) — `DerivedConsistencyTest` and
   `SyncMergeTest.assertConverged` compare them.
8. **Query plans**: a table that grows → add to `QueryPlans.LARGE_TABLES` (QueryPlans.kt:29); register its hot SQL (recipe 10).
9. **Docs**: `references/database.md` §6 table catalog + §9 history line; `docs/DECISIONS.md` entry; sync class in the skill if new; `PHASES.md`.
10. A migration runs behind an automatic "upgrade" backup (`Db.backupBeforeUpgrade`, keeps 3) — nothing to do, but a migration that can fail on real data
    blocks the app at start: keep it simple and set-based.

---

## 6. Add a synced entity or a new sync event kind

**Copy:** D-066's `product_look` (LWW) and `product_image` (EVENT) — every touch point below exists for them.

1. **Entity code**: `C/model/Codes.kt` `object Entity` (:282). LWW codes are 1–14 (next 15), EVENT 20–28 (next 29). Append only.
2. **Schema**: recipe 5 (table + class list + migration + snapshot).
3. **Local write = outbox in the same transaction**:
   - LWW: `LwwWriter.insert(tx, "table", Entity.X, id, fields, now)` / `update(tx, "table", Entity.X, id, changes, now)` / `delete` (tombstone). Edits
     write only fields the user changed **and** that differ from the stored row (copy `ProductLookDao.update(tx, id, shown, edited, now)`, ProductLookDao.kt:36,
     or `ProductDao.update(tx, shown, edited, current, now)`, ProductDao.kt:442).
   - EVENT: copy `ProductLookDao.addImage` (:55-66) — `id = tx.nextId()`, `hlc = tx.hlcNow()`, `tx.insert(INSERT, *values)`, then
     `if (tx.syncEnabled) Outbox.append(tx, Entity.X, EventOp.INSERT, id, hlc, Outbox.json { w -> Outbox.writeRow(w, COLS, values) })`.
   - `Outbox.append` also calls `tx.outboxQueued()` so `Db.onOutboxCommit` → `AppGraph.syncSoon()` fires after commit. Never insert into `outbox` by hand.
   - Pass enough `reserveIds` to `db.write(reserveIds = …)` for every `nextId()` (ids are reserved before BEGIN; `IdAllocator.nextId` throws otherwise).
4. **Import / merge rule** (`A/data/sync/Importer.kt`):
   - LWW: add `Entity.X to "table"` to `LWW_TABLES` (:197). The generic per-field merge (`lww`, :77) handles it; add side effects in `after()` (:134) if
     derived data depends on fields (product: re-index FTS, recategorize summaries).
   - EVENT: add to `EVENT_ENTITIES` (:213) and a branch in `apply()` (:46). Plain rows: `insert(tx, "table", p)` (INSERT OR IGNORE, true only when new);
     update derived data **only when new** (copy the `Entity.CREDIT` branch, :64-66). Child rows travel inside the parent's payload (copy SALE/PURCHASE).
   - Payload values arrive as `Long`/`Double`/`String`/`null`/`Map`/`List` (SegmentCodec.parse, SyncDao.kt:326); booleans are written as 0/1. Cast to `Long`.
   - Tills on an older version keep unknown entities in `sync_deferred` and apply them after updating (`Importer.knows`, `knownEntities`); a known entity
     whose apply fails is set aside and retried daily (sync.md §6).
5. **Backfill** (`A/data/sync/Backfill.kt`): LWW tables are iterated from `Importer.LWW_TABLES` automatically — add the table name to `order()` (:49,
   parents before children; a missing name sorts first because `indexOf` = −1). EVENT tables need an explicit `rows(db, "table", Entity.X, "", progress)`
   line in `run()` (:30-46; big rows: smaller `chunk`, as `product_image` uses 20).
6. **Reloads after import**: if screens hold it in memory, extend `SyncEngine.reloadChanged` (:504) or `CATALOG_ENTITIES` (:1104) so the selling screen
   re-reads tiles.
7. **Audit/activity log**: if the change is sensitive, `AuditDao.log` in the same tx (recipe 7).
8. **Tests**: a scenario in `AT/sync/SyncMergeTest.kt` — copy `aProductsColourAndPictureReachTheOtherTills` (:206): data written **before** sync is on
   (backfill), `enable(a)`, `enable(b)`, `syncAll(a, b)`, assert on the other till, a concurrent field edit, then `assertConverged(a, b)`.
   `content()` already compares every `Schema.LWW_TABLES + EVENT_TABLES` table; **new `sum_*` columns must be added to its hard-coded `sums` map** (:134-144).
   Use synced write paths in tests (`ProductDao.create`, `TestDb.product`), never `ProductDao.insert` (no outbox). Also `AT/data/LwwWriteTest.kt` style test
   for local field versions + outbox rows, and `SaleDaoTest.outboxIsWrittenOnlyWhileSyncIsEnabled` style check.
9. **Docs**: sync.md §4 table (event, payload, apply rule) + LWW entity list; database.md §6; DECISIONS entry.

---

## 7. Add an audit (activity log) action

1. `C/model/Codes.kt` `AuditAction` (:84): append `const val NEW_ACTION = 35` with a KDoc saying what `amount` and `detail` hold. Never renumber.
2. Write it **in the same transaction** as the action: `AuditDao.log(tx, AuditAction.NEW_ACTION, actor.staffId, now, Entity.X, entityId, amount, detail.take(300), actor.approvedBy)`
   (AuditDao.kt:35; reserve one extra id; it emits `Entity.AUDIT` outbox itself). Copy `InventoryService.adjust` (InventoryService.kt:68-84) or `SaleActions.void` (:170).
   Never put PINs, tokens or customer personal data in `detail`.
3. Label: `AuditLogActivity.actionName` (`A/ui/settings/AuditLogActivity.kt:105-140`) → `R.string.audit_new_action` (EN+MS); add to `ACTIONS` (filter list, :145) and,
   if `amount` is money, to `MONEY_ACTIONS` (:156).
4. If it should count in the staff check / shift report "Checks": extend `C/staff/StaffChecks.kt` (`Checks` field, `of()` branch, `ACTIONS`), the shift report /
   staff-check rendering, and the daily CSV columns (`ReportService.dailyCsv`) if wanted. JVM test in `CT/staff/StaffChecksTest.kt`.
5. Tests: assert the entry exists in the domain test (e.g. `AuditDao.recent`/`exists`), like `SaleActionsTest.copiesAndDrawerOpensAreQueuedAndAudited`.

---

## 8. Add a user-visible string

1. Pick the themed file (`strings.xml`, `strings_staff.xml`, `strings_reports.xml`, `strings_sync.xml`, `strings_cashier.xml`, `strings_fraud.xml`, `strings_look.xml`,
   `strings_display.xml`, `strings_promo.xml`, `strings_safety.xml`, `strings_update.xml`, `strings_hw.xml`, `strings_a11y.xml`) and add the **same name** to
   `RES/values/<file>` (English) and `RES/values-ms/<file>` (Bahasa Melayu). Both directories contain the same file set.
2. Placeholders positional and identical in both languages: `%1$s`, `%2$d`; plurals with `<plurals>` (`sell_items`, strings.xml:40). Escape `'` as `\'`,
   `&` as `&amp;`. Non-translatable text: `translatable="false"` (only `app_name`). Typographic exceptions: `tools:ignore` (strings_sync.xml:107).
3. Use `getString(R.string.x, …)` / `resources.getQuantityString`. Money/qty text via `MoneyFormat.format(minor, currency)` / `formatQty` (store
   currency, not device locale). Dates via `DateText`.
4. Errors shown to users are mapped to strings, never raw messages: `ActionRefused.Reason` → `ScreenActivity.errorText` (ScreenActivity.kt:458-480 — add a branch
   for a new reason); sync errors are stored as codes (`offline`, `sign-in`, `corrupt`) and translated by screens.
5. Receipts use the **receipt language** store setting (`receipt.lang`), not the app language; seed names come from `AppGraph.seedNames()`.
6. Check: `:app:lintRelease` (abortOnError: missing translations and format mismatches fail; UNVERIFIED which exact lint IDs are errors in this setup),
   then the CI `ScreenshotsTest` pictures (Malay is often longer) and `TopBarLayoutTest` when touching the selling screen's top bar.

---

## 9. Add a report figure or a CSV export

Reports read **summary tables only** (`sum_*`, DERIVED), through `ReportDao` and `SummaryRange`/`RangePlan` for product/category rows.

A new figure on the report screen:
1. Data: if it is a sum of existing summary columns, add a query to `A/data/report/ReportDao.kt` (register in its `HOT_QUERIES`, :342). If it needs a new
   column in a summary table: recipe 5 (DERIVED column, migration that fills it from events), update `Summaries.apply` (incremental, signs for
   refunds/voids) **and** `DerivedRebuild.summaries` (set-based rebuild), `Summaries.MONTHS_FROM_DAYS/YEARS_FROM_MONTHS` when month/year tables carry it,
   and the `sums` column list in `SyncMergeTest.content()` (:134).
2. Domain: add the field to `ReportService.Report` (ReportService.kt:44) and fill it in `companion build(r, p, topN, compare)` (:139). Permission is already
   `actor(Perm.REPORTS)` in `build`.
3. UI: `ReportsActivity.render` builds a `Form` — add `f.row(getString(R.string.report_x), money(value))` (`render`, ReportActivities.kt:159). Strings EN+MS.
4. Tests: `AT/domain/report/ReportTest.kt` (figure after sales, refunds, voids — "a void leaves zero rows behind"); a perf check if it adds a query
   (`report_day/month/year` scenarios run `ReportService.build`, PerfSuite.kt:252-269).

A new CSV export:
1. Add a value to `ReportService.Export` (:64) and a branch in `write()` (:102): `out.append(CsvWriter.BOM)` is already written; `w.row(...)` header with English
   snake_case names (D-041), money via `MoneyFormat.plain(v, currency.decimals)`, dates `DateText.isoDate`. Stream large data in keyset pages of 500
   with `coroutineContext.ensureActive()` (copy `Export.RECEIPTS`). Return the data row count.
2. UI: add the menu item in `ReportsActivity.exportMenu` (ReportActivities.kt:247-262) and call
   `exportCsv("lekaspos-<name>-<from>_<to>.csv") { out -> graph.reports.export(kind, p, out, tz) }` — `ScreenActivity.exportCsv` (:285) offers Share
   (cache file + FileProvider) or Save (SAF) and runs in `appScope`; failures delete the half-written file.
   Exports outside reports follow the same shape (`ProductListActivity.kt:146`: `exportCsv("lekaspos-products.csv") { out -> graph.productCsv.export(out).toLong() }`).
3. Tests: domain test reading the CSV back with `CsvReader` (copy `ReportTest.receiptsExportEveryReceiptOnceInTimeOrder`); perf scenario for big exports
   (`export_receipts_month`, PerfSuite.kt:317).

---

## 10. Add a perf scenario / query plan check

Query plans (every new SQL that touches a large table or pages a list):
1. Declare the SQL as a constant in its DAO and add `"name" to SQL` to that DAO's `HOT_QUERIES` (pattern: `AuditDao.HOT_QUERIES`, AuditDao.kt:125). A new DAO's
   list must be appended to `QueryPlans.hotQueries()` (QueryPlans.kt:66). Rebuild/maintenance statements go to `DerivedRebuild.MAINTENANCE_QUERIES`.
2. Classify it in `QueryPlans` when needed: `INDEX_ORDERED` (paged lists: no temp B-tree sort), `KEY_LOOKUPS` (must SEARCH, not scan through an index),
   `INDEX_RANGES` ("after an HLC" reads must show `hlc>?`); a new big table goes to `LARGE_TABLES`.
3. Write keyset pages as `WHERE a <= ? AND (a < ? OR id < ?) ORDER BY a DESC, id DESC LIMIT ?` (AuditDao.kt:58-60); repeat partial-index predicates literally
   (`AND deleted = 0`); `CROSS JOIN` to force join order.
4. Checked by `AT/perf/QueryPlansTest.registeredQueriesPassOnAnEmptyDatabase` in **every** CI instrumented run (API 21 planner), and by the perf suite on
   generated data (`PerfSuiteTest`, `perf.yml`).

Timed scenario:
1. In `PerfSuite.run` (`A/perf/PerfSuite.kt:88`), add `progress.update("my_id")` and
   `add(measure("my_id", budgetMs, warmup = 5, n = 60) { i -> MyDao.page(r, …) })` — use the **same DAO/service code the app uses**; `r` is the perf DB's
   `SQLiteDatabase`, `samples` holds sample barcodes/categories/terms. Writes: copy `sale_commit`/`receive_commit` (:160-180).
2. If it needs data the generator does not make, extend `PerfDataGenerator` (deterministic `Random(seed)`, bulk compiled statements, then `DerivedRebuild`).
3. Add the budget row to `references/performance.md` §1 (ID, scenario, data scale, budget).
4. Run: CI Actions → Performance (`perf.yml`, QUICK or FULL; API 21, 29, 36), or locally `.\scripts\run-perf.ps1 -Scale QUICK` on one device (release build).
   `PerfSuiteTest` enforces budgets only with `-e assertBudgets true`.

---

## 11. Release a new version

From `docs/BUILD.md` "Release checklist", `.github/workflows/ci.yml` and the 1.13.0 history (commits `bbae2fb`, `d3c583c`):

1. Bump `versionName` in `app/build.gradle.kts:33` (semantic, always higher than the last release tag; `versionCode` = CI run number via
   `-Plekas.ciRun`, never set by hand). Update `docs/PHASES.md` (new section with checklist + "Needs real-device testing" list) and DECISIONS if needed.
2. Push to `main`. CI (`ci.yml`) must be green: build (JVM tests, `lintRelease`, release/debug/test APKs, **APK < 8,388,608 bytes**), release smoke API 21/36
   (R8 APK installed over `PREV_APK_URL` and fresh, 20 s alive), tablet landscape (smoke + screenshots), instrumented API 21 + 36. Record the run, build
   number and release APK size in PHASES.md (commit message pattern: `"<ver>: CI green (build N), test build published, release APK X bytes"`).
3. **Test build** = GitHub **pre-release** `v<version>` on the CI build's commit with the CI `apks` artifact's `app-release.apk` (release-key signed;
   `apksigner verify --print-certs` must show SHA-256 `0a67abec…d4b9`) and `mapping-<version>.txt` from the `mapping` artifact. Only phones with
   "Include test versions" see pre-releases. Exact `gh` commands are not recorded in the repo — **UNVERIFIED** (likely `gh run download` + `gh release create --prerelease`).
4. After the owner's real-device check and their go-ahead: privacy check (BUILD.md step 4: `site/privacy.html` + `site/privasi.html` same content and
   "Last updated", `docs/PLAY.md` Data Safety) when data handling changed.
5. **Public release** = GitHub release marked **latest** (not pre-release), tag higher than any before, assets **`LekasPOS.apk`** (website Download link:
   `releases/latest/download/LekasPOS.apk`, site/index.html:25) and **`LekasPOS-<version>.apk`**, uploaded **before** marking latest; notes with SHA-256 and a
   `### What's new` heading followed by a `-` list (optional `### Apa yang baharu`), parsed by `:core ReleaseNotes`. Publishing updates every shop within a
   day (`UpdateWorker`, D-059). Attach `mapping-<version>.txt` (the reports repo's "Readable trace" workflow retraces with it).
6. Point **`PREV_APK_URL`** in `.github/workflows/ci.yml:136` at the new release's `LekasPOS-<version>.apk` (commit pattern: `"<ver> released as latest;
   smoke baseline v<ver>"`).
7. Run Actions → Performance (FULL) on the released code and record it in PHASES.md.
8. Check public text (`site/index.html` update steps, `README.md`, the user guide's "For LekasPOS x.y.z" line and what changed — §0) still matches; relay `MAX_BUILD` (`relay/wrangler.toml`, default 10000) stays well above
   the CI run number.

---

## 12. Debugging

Where things are written:
| What | Where | How to read |
|---|---|---|
| Logcat | tag **`Lekas`** (`util.Log`), perf **`LekasPerf`**, StrictMode (debug) under `StrictMode` | `adb logcat -s Lekas` (release strips `v`/`d`; `Log.d{}` compiled out) |
| Local error log | `files/logs/errors.log` (+ `errors.1.log`, 256 KB each), warnings + errors + crashes (`ErrorLog`) | Diagnostics → "Share the error log"; on a debug build `adb exec-out run-as com.lekaspos.app.debug cat files/logs/errors.log` |
| Error reports (release-signed builds, shop consent) | `files/reports/*.json` → `ReportWorker` → relay → issues in private `FaizoKen/LekasPOS-reports` (label `fp:<fingerprint>`, kind label) | Read the issue; traces retraced by that repo's "Readable trace" workflow from `mapping-<version>.txt`. Diagnostics → "Send a report" files a `manual` issue. |
| Damaged database | `files/backups/damaged-<time>.db` + "Data problem" status for 7 days | `KeepDamagedDatabase` |
| Perf reports | `Android/data/<pkg>/files/perf/perf-<time>.{txt,json}`; CI artifacts `perf-<scale>-api*` | `scripts/run-perf.ps1` pulls to `perf-results/<device>/` |
| Leak dumps | `files/leaks/*.hprof` (SoakTest) | CI prints the chain (artifact `instrumented-api*/leaks-api*`) |
| Screenshots | external `files/screens/` | CI artifacts `screens-api*`, `tablet` |

Reproducing on Android 5 (API 21, SQLite 3.8): per `docs/BUILD.md` — `.\scripts\create-avds.ps1` once, start `lekas-api21`, `.\gradlew.bat --stop`, then
`adb install -r -t app\build\outputs\apk\debug\app-debug.apk` and the androidTest APK, and run one class:
`adb shell am instrument -w -e class com.lekaspos.data.sale.SaleDaoTest com.lekaspos.app.debug.test/androidx.test.runner.AndroidJUnitRunner`.
Prefer CI (`ci.yml` API 21 job, artifact `instrumented-api21` with `logcat-api21.txt`). Release-only (R8) problems show **only** in the release smoke job
(`results/release-smoke-*-api*.txt`).

Pitfalls recorded in the code/DECISIONS:
- **R8**: the app's classes keep their names (`-keep,allowshrinking,allowoptimization class com.lekaspos.**`); reflection in libraries needs a keep rule —
  WorkManager's Room `WorkDatabase_Impl.<init>()` was stripped and the release build crashed 1.5 s after start (app/proguard-rules.pro:24-28). Any new
  reflection-using dependency: add a rule and rely on release smoke. Library classes are repackaged into `l.` (CrashText knows it).
- **SQLite 3.8 partial indexes** (D-021, database.md §2): only `deleted = 0` or a lone `col IS NOT NULL`; ignored inside correlated subqueries and scalar
  subqueries of UPDATE → O(n²). Use `GROUP BY` + join or a top-level SELECT then update.
- **Main-thread DB**: `Db.assertNotMainThread()` throws `IllegalStateException("database access on the main thread")` from `readBlocking`/`writeBlocking`/
  `onWriterThread`/`open`; `read{}`/`write{}` are `suspend` and switch dispatchers. Debug StrictMode only *logs* other disk/network on main.
- **Activity recreation**: back-office screens don't recreate on rotation (configChanges) but are still recreated after process death — nothing but the
  saved instance state and app-scoped holders survives; `StaffSession.perms` is 0 until `whenLoaded()`. The selling screen *is* recreated on rotation;
  payment/prompt state lives in `CartSession.payment`/`prompt`.
- **Dialogs on finishing activities**: `Dialogs.*` never show when `isFinishing || isDestroyed` (BadTokenException crashes, D-060); every dialog
  `.trackedBy(activity)` so `onDestroy` dismisses it.
- **Cancellation**: catch-alls rethrow `CancellationException` first (ScreenActivity.kt:139-141, 437-440); `ErrorReports.expected` treats it (and
  `ActionRefused`, offline, full storage, `AuthNeeded`, `BackupFiles.Invalid`) as non-faults.
- **Debug crashes in background**: `AppGraph.appScope` rethrows in DEBUG, so a failing background job fails instrumented tests (intended).

---

## 13. Gotchas (rule → where it is enforced)

| # | Gotcha | Rule | Enforced / found by |
|---|---|---|---|
| 1 | Calling the DB on the main thread | Use `graph.db().read{}`/`write{}` from coroutines; blocking variants only in workers/tests | `Db.assertNotMainThread` (Db.kt:319); `DbTest.mainThreadAccessIsRefused` |
| 2 | `tx.nextId()` more times than reserved | Pass `reserveIds` ≥ ids used; reservation is committed before BEGIN | `IdAllocator.nextId` `check` (Ids.kt:79); `DbTest.writeThatOutgrowsItsIdReservationFailsInsteadOfReusing` |
| 3 | Dynamic SQL through `tx.insert/exec/update` | Those cache compiled statements forever per SQL string (writer thread only); build dynamic SQL with `tx.db.execSQL` (as `LwwWriter`) | `Db.statement` `check` (Db.kt:136) |
| 4 | Values concatenated into SQL | `?` binds only; only `:core` constants may be interpolated (e.g. `SaleStatus` in DerivedRebuild, entity codes in `deferredPage`) | review; `rawQuery` binds strings on API 21 (`args()`) |
| 5 | Forgetting the outbox | Every synced write appends its event in the same tx via `LwwWriter`/`Outbox.append`, only `if (tx.syncEnabled)` | `SaleDaoTest.outboxIsWrittenOnlyWhileSyncIsEnabled`, `LwwWriteTest`, `SyncMergeTest` |
| 6 | Writing whole rows on edit | LWW edits write only fields changed on the screen **and** different from the stored row (3-way: shown/edited/current) | `ProductDao.update`, `ProductLookDao.update`, `Perm.merge` for roles, `SettingsRepo.saveStore` |
| 7 | New column on a synced table | Older tills drop unknown fields permanently; prefer a new entity or clear `sync_cursor` in the migration | `Importer` column filter (Importer.kt:81,164); D-047, D-066 |
| 8 | Editing an old schema / forgetting the snapshot | Bump `VERSION`, add a `Migration`, commit `<v>.sql`; ADD COLUMN text last in fresh DDL | `SchemaSnapshotTest`, `MigrationTest` |
| 9 | A table without a sync class / LWW columns | List it in exactly one class list; LWW tables need `deleted, ver_hlc, ver_dev, fver` | `SchemaSnapshotTest.everyTableHasExactlyOneSyncClass`, `statementsHaveNoObviousSyntaxSlips` |
| 10 | SQL newer than SQLite 3.8.4 / bad partial index | See database.md §2; register SQL for plan checks | `SchemaSnapshotTest.noForbiddenSqlForApi21` (DDL only), API 21 CI job, `QueryPlansTest` |
| 11 | Unregistered hot query / OFFSET paging | Register in `HOT_QUERIES` (+ `INDEX_ORDERED`); keyset pages | `QueryPlans.check` in `QueryPlansTest` and `PerfSuiteTest` |
| 12 | Derived data that cannot be rebuilt | Every incremental update must match `DerivedRebuild.all` | `DerivedConsistencyTest`, `SyncMergeTest.assertConverged` (+ its hard-coded `sums` columns) |
| 13 | Money with Float/Double or `Math.*Exact` | `:core` `Rounding`/`Checked`/`MoneyFormat`; Long minor units, milli qty, bp rates | review; `:core` tests mirror money.md §9 |
| 14 | API 24+ Java calls (`getOrDefault`, `putIfAbsent`, streams, `Math.addExact`, `String.join`), `java.time` | Use Kotlin stdlib (`getOrPut`) and `:core` helpers | lint `NewApi` with `checkDependencies` (`:app:lintRelease`); `:core` `-Xjdk-release=1.8` |
| 15 | UI-only permission checks | Domain calls `graph.permissions.actor(perm, approval)`; UI uses `guard`/`requireAccess`/`withApproval` + `shown()` for visibility | `ActionRefused(NOT_ALLOWED)`; domain tests |
| 16 | Treating "not loaded" as "owner" | `StaffSession.perms` is 0 until loaded; call `whenLoaded()` before checks in `onActivityResult` | StaffSession.kt:69; `TestGraph.unloaded` tests |
| 17 | `requireAccess` vs `withApproval` confusion | `requireAccess`: approval held by the screen until it finishes; `withApproval`: one action, approval passed to the service | ScreenActivity.kt:242, :359; StaffUi.kt:71 |
| 18 | Sensitive action without audit | `AuditDao.log` in the same tx with `approvedBy` | DoD checklist; domain tests |
| 19 | Collecting flows in `onCreate` / keeping Activity refs in singletons | Collect in `onStarted(scope)`; app-scoped holders keep state | `SoakTest` leak check, StrictMode `detectActivityLeaks` |
| 20 | Activity not extending `ScreenActivity` | Loses language, insets, idle lock, LaunchGuard, load gate; Sell/Lock reimplement these by hand | AppLanguageTest, ScreensSmokeTest |
| 21 | Missing `configChanges` | Back-office: full set incl. `orientation\|screenSize\|screenLayout\|smallestScreenSize\|uiMode`; no `-land` resources for them | manifest review; tablet CI job |
| 22 | Same screen opened twice | `LaunchGuard` drops a second start of the same component within 700 ms (also in tests launching twice quickly) | LaunchGuard.kt:11-27 |
| 23 | Dialog key handling | Keypad dialogs: `.keys { keypad.onKey(it) }`; all dialogs `.trackedBy(activity)`; scanner Enter never presses a button | DialogHost.kt (`DialogKeys`, `keys`, `trackedBy`); selling-screen scans: `ScreensSmokeTest.sellingScreenTakesKeyboardWedgeScans`; no dedicated dialog-key test found (UNVERIFIED) |
| 24 | Showing a dialog after Back | `Dialogs.canShow`; `launchUi` | Dialogs.kt:23-28 |
| 25 | Swallowing `CancellationException` | Rethrow first in every catch-all | ScreenActivity, CheckoutService, ApprovalDialog |
| 26 | Write + in-memory update cut by a closing screen | Wrap in `withContext(NonCancellable)` | SettingsRepo.saveStore/saveDevice, StaffService.committed |
| 27 | `Log.e` for expected failures | `Log.w` for offline/printer off/rule refusals; `Log.e` = an error report | `util.Log.expected` → `ErrorReports.expected` |
| 28 | Logging PINs/tokens/customer data | Never | review; `CrashText.scrub` for reports |
| 29 | String in one language only / mismatched placeholders | Same name in `values/` and `values-ms/`, positional placeholders | lint (`abortOnError`) |
| 30 | Touch targets < 48dp (56dp selling) | Use `Widget.Lekas.*` styles, `keyHeightPx` | SidewaysLayoutTest, LineControlsTest, TopBarLayoutTest |
| 31 | `:app` JVM unit tests touching Android classes | No Robolectric/default values: keep `UT/` tests on pure code (companion functions like `CheckoutService.draft`) | Gradle `testDebugUnitTest` |
| 32 | Merge-test data written with raw inserts | Use synced paths (`ProductDao.create`/`TestDb.product`), not `ProductDao.insert` | sync.md §12 |
| 33 | Reusing an enum/code value | Append only (`Codes.kt` header); string codes (`AdjustReason`, setting keys) never renamed | review |
| 34 | Adding a dependency | Needs size + need justification in `docs/DECISIONS.md`, minSdk-21 pin in `libs.versions.toml` | manifest merger fails on higher minSdk; APK-size step in CI |
| 35 | R8-only crashes | Reflection keep rules; instrumented tests use the debug build | release-smoke job (`scripts/ci/release-smoke.sh`) |
| 36 | Forgetting `PREV_APK_URL` after a release | Point it at the new release asset | ci.yml:136 |
| 37 | Long local job stalls with the screen off | WorkManager or a bounded `PARTIAL_WAKE_LOCK`; `keepScreenOn` for screen jobs | PerfRunner, architecture.md §3 |
| 38 | WorkManager scheduling throwing | Go through `Work.*` (wrapped in `safely`); schedule off the main thread | Work.kt:164-172; LekasApp exception handlers |
| 39 | Running Gradle without `.\` / emulator alongside Gradle on the laptop | `.\gradlew.bat …`; one emulator, never with Gradle | docs/BUILD.md, CLAUDE.md |
| 40 | Seed rows | Ids with device_no 0 and version (0,0); data migrations only touch rows with `ver_hlc = 0`; renames via `Seed.renameUntouched` | Seed.kt; `SyncMergeTest.builtInRowsNamedInTwoLanguagesEndTheSame` |

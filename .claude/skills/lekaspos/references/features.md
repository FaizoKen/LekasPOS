# Features — what the app does today (behaviour reference)

The current behaviour of LekasPOS **1.13.0**, feature by feature, for anyone changing the code. It complements
the design references (`architecture.md`, `database.md`, `money.md`, `sync.md`): those say *how* it is built,
this says *what it does* and where. `docs/DECISIONS.md` is the history of *why* (D-numbers below); when it and
this file disagree, the code wins — fix this file.

Paths: `ui/`, `domain/`, `data/`, `hw/`, `sync/`, `app/` are under `app/src/main/java/com/lekaspos/`; `core/` is
`core/src/main/kotlin/com/lekaspos/core/`; strings are `app/src/main/res/values{,-ms}/strings*.xml`.
The user-facing description of the same behaviour is the user guide (`site/guide.html`, `site/panduan.html`);
keep both true (see `recipes.md` → "Change something users see").

---

## 1. Access: PIN login, roles, permissions, approvals

- **PIN login off** (no active staff member has a PIN): the till runs as the seed staff "Owner" (id 1) with every
  permission; actions are recorded as that owner. No sign-in screen, no staff chip, no "Lock / switch user", no
  "Manager PIN" (`domain/StaffSession.kt`, `StaffDao.loginRequired`).
- PIN login turns on when the first PIN is set; it must be an **owner's** (`error_owner_pin_first`). That owner is
  signed in at once and an **owner recovery code** is created (12 chars `XXXX-XXXX-XXXX` from
  `23456789ABCDEFGHJKMNPQRSTUVWXYZ`, shown once; `core/staff/PinHash.kt` `RecoveryCode`). "Forgot PIN?" (owners only)
  takes it and sets a new PIN (`OWNER_PIN_RESET`). Staff ⋮ → New recovery code (owners only) replaces it.
- PINs: 4–6 digits, salted PBKDF2 (`PinHash`, D-037). Wrong PINs count **per person** (`pin.fails.<id>`):
  5 free, then 30 s doubling to 15 min (`PinLockout`), measured by wall clock and time since boot (clock changes
  cannot skip it). Every wrong PIN from the 5th is audited (`PIN_LOCKOUT`; the target person is stored but not shown).
- **Roles** (`role`, LWW): seed Owner (always every permission, `Perm.effective`), Manager, Cashier, plus custom
  roles. Seed roles cannot be deleted; a role in use cannot be deleted. Role editor = `Perm.ROLE_EDITOR`
  (17 switches; `CANCEL_BILL` retired by D-063 and kept untouched).
- **Owner-only rules** (`domain/staff/StaffService.kt`, while PIN login is on): only the signed-in owner, or an
  owner's one-shot approval, may make/edit/remove an owner or set an owner's PIN; a role is widened only with
  permissions the granter holds and never by its own holder; assigning roles/PINs only within the actor's
  permissions; at least one active owner with a PIN must remain (`error_last_owner`). D-056, D-061.

### 1.1 Permissions (`core/model/Codes.kt` `object Perm`)

| Perm | Label (`perm_*`) | Default Manager | Default Cashier |
|---|---|---|---|
| `DISCOUNT` | Give discounts | ✓ | |
| `PRICE_OVERRIDE` | Change prices on a bill | ✓ | |
| `VOID` | Void sales | ✓ | |
| `REFUND` | Refunds | ✓ | |
| `REPRINT` | Print receipt copies | ✓ | ✓ |
| `OPEN_DRAWER` | Open the cash drawer | ✓ | |
| `CASH_MOVE` | Cash in, cash out and drops | ✓ | |
| `SHIFT_REPORT` | See expected cash and shift reports | ✓ | |
| `CUSTOMERS` | Manage customers, take repayments | ✓ | ✓ |
| `CREDIT_SALE` | Sell on credit | ✓ | ✓ |
| `CREDIT_LIMIT` | Go over credit limits, adjust balances | ✓ | |
| `MANAGE_PRODUCTS` | Edit products and categories (also promotions, CSV, price-check price change, adding an unknown barcode) | ✓ | |
| `MANAGE_STOCK` | Receive, adjust and count stock | ✓ | |
| `REPORTS` | See sales reports, profit and stock value | ✓ | |
| `VIEW_AUDIT` | See the activity log (also Reports → Staff check) | ✓ | |
| `SETTINGS` | Change settings (store/printer/scanner, tax rates, payment methods, backup, sync, daily report, updates, error reports, diagnostics) | | |
| `MANAGE_STAFF` | Manage staff and roles | | |

Bit values and the defaults: `Codes.kt` (`DEFAULT_MANAGER`, `DEFAULT_CASHIER`); never renumber.

### 1.2 Three ways a missing permission is supplied (`ui/staff/StaffUi.kt`, `ui/common/ScreenActivity.kt`)

1. **One-shot** `withApproval(perm)`: a "Manager approval" PIN pad lists staff who can sign in and hold `perm`;
   used once; the action's audit entry carries `approved_by`. Used for discounts, price change, drawer, cash moves,
   credit sale/over-limit, copies, void, refund, customers, repayments, credit limit, App updates…
2. **Screen-kept** `requireAccess(perm)` / `guard(perm)`: the screen keeps the approval until it closes (survives
   rotation); recorded as `APPROVAL`. Products, Inventory screens, Reports, Activity log, Staff, Settings screens.
   `guard` asks on open and closes the screen on cancel. Not loaded yet ≠ owner: `perms` is 0 until loaded (D-054).
3. **Manager's help at the till** (Menu → Manager PIN, `PermissionGate.startHelp`): lends `Perm.TILL_HELP`
   (DISCOUNT, PRICE_OVERRIDE, CUSTOMERS, CREDIT_SALE that the manager holds) plus the permission it was started
   for (an unknown barcode's "Add product" lends MANAGE_PRODUCTS). The manager's other controls are *shown* but ask
   the PIN again. Ends on pay/hold/clear/empty bill, resume, lock or any sign-in, tapping the "Manager: X ✕" pill,
   or 5 min (`HELP_MS`). D-063, D-067.
- `PermissionGate.shown(perm)` decides visibility: own role, a screen-held approval, or a helping manager. **A control
  needing a permission the person lacks is hidden, not greyed** (selling screen and cashier screens, D-063). New
  controls must follow this.

### 1.3 Locking (`domain/StaffSession.kt`)

- Per till "When the till locks" (Staff ⋮; meta `dev.lock.minutes`, `dev.lock.after_sale`): 1/2/5/10/30 min idle,
  "after each sale and after N min idle", or "only when someone locks it". **Default 5 min**; a till that had
  "never" was moved to 5 once (`SettingsRepo.lockByDefault`, 1.12.0).
- Idle = no touch/key/scan (dialogs count); checked every 15 s, on start/resume, and before an input counts (the
  input that finds the till idle is dropped). `session.away_at` / `session.seen_at` keep idle time across process
  death and power cuts. Never locks during a payment; a lock due meanwhile happens when the result closes (≤ 1 min).
- Locking clears the last sale's result, held approvals and the manager's help. `LockActivity` covers the selling
  screen; other screens jump home when the till locks. Back on the lock screen leaves the app.

---

## 2. Selling screen (`ui/sell/SellActivity.kt`, `domain/sell/*`)

### 2.1 Layout
- Phone upright: one pane (bill ⇄ catalogue via "Items"). Any landscape, or smallest width ≥ 600dp: two panes;
  bill pane 36 % of width, 320–420dp, ≤ half (`billPaneDp`). Recreated on rotation (like the lock screen, camera and
  Diagnostics; back-office screens are not, D-054). Orientation locked while the payment is open.
- Top bar (`TopBarLayout`): store name (blank → "LekasPOS"), staff chip (Lock / Change my PIN), pills, **Menu**. Pill
  order: held (`held_pill`) → one safety pill → printer pill → manager help → update (`update_pill`, needs SETTINGS
  shown and a non-Play install). Pills that do not fit wrap to a second line.
- Safety pill, first match wins (`renderSafety`): Data problem → Backup: sign in (sync on, auth needed) → Not
  backed up (sync on, changes waiting, no success for 24 h) → Storage almost full (free < max(300 MB, DB + 100 MB,
  backup need); opens for everyone) → Not backed up (sync on, no copy off phone for 3 days) → Not backed up
  (`safety_pill_at_risk`, sync off). Without SETTINGS a tap shows `safety_tell_owner`. A shop with no products and
  no sales shows none (`BackupService.FRESH_MS` = 3 days).
- Totals bar: customer chip (credit on and (customer set or CREDIT_SALE shown)), summary ("n items · discount ·
  incl. tax/tax"), **Total**, **Hold**, **Pay** (both disabled when empty or frozen). Same for every role (D-064).
- Empty bill: help text and the **last sale** of this app run (`CheckoutService.last`: change again, "Print a copy").

### 2.2 Adding items
- Every path ends in `CartSession.scan(code)` or `addProductById`. Frozen bill (payment/checkout/resume) → error beep.
- Code lookup (`domain/sell/BarcodeLookup.kt`): exact barcode or pack barcode with `Gtin.lookupVariants` (UPC↔EAN,
  GTIN-14, lost leading 0, UPC-E) → scale templates (first whose PLU is known) → unique SKU → not found.
- Keyboard-wedge (`core/scan/ScanBuffer.kt`, `ui/common/ScanInput.kt`): ≤ 60 ms/char average; Enter/Tab with ≥ 3
  chars, or ≥ 6 chars + 250 ms silence = a scan; slower typing goes to search; a burst typed into a focused field is
  taken back out; digits read by key code. Dialogs swallow Enter/Tab/Space; keypads drop scanner-speed digits
  (`DialogKeys`). A scan while the result is open starts the next bill.
- SPP scanners (`hw/scanner/SppScanner.kt`): while a scan-taking screen is in front; CR/LF or 250 ms ends a code.
- Camera (`ui/scan/CameraScanActivity.kt`, Camera1 + ZXing 3.3.3): sell mode adds every code (same code ignored
  1.5 s while in view); codes needing a weight/price or unknown go back to the selling screen.
- Search: 150 ms debounce, ≤ 60 tiles; ≥ 2 digits = barcode prefix; words = name/SKU word prefixes (all words,
  accent/case-insensitive, ≤ 6 words); one letter = name prefix; active products only. Enter: try as code (≥ 3 chars,
  no spaces), else ≥ 6 digits → unknown-barcode flow, else a single result is added.
- Catalogue: chips Popular (if sales in 30 days; `PopularItems`: top 40 by qty over 30 days from `sum_day_product`,
  cached 10 min, rebuilt if > 3 days) → All → categories (by name). Tiles: picture, colour (product, else category),
  price (`/unit` for weighed, "Enter price" for open price), stock if tracked, "×n" badge when on the bill. Sizes per
  till `dev.tiles` (Large 150 / Medium 112 / Small 88dp min width × font scale, 2–10 per row). Taps are queued; a
  400 ms shield after a search pick.
- Sell modes: piece (adds 1; price 0.00 asks the price, 0.00 accepted), weight (asks weight, 3 decimals, > 0, each
  weighing its own line; **a weighed product priced 0.00 is added at 0.00 — no price is asked**, nor for a pack
  barcode with no pack price of a 0.00 product: `CartSession.scan` guards only `SellMode.UNIT`), open price (asks
  price, > 0). Pack barcode: line "Name x24" at the pack price (or price ×
  pieces), stock in base units. Scale label: weight → weighed line; price → line amount = label price ("label
  price", Remove only); weight 0 / price 0 → asks.
- Unknown barcode (`showUnknownBarcode`): "Barcode not found" → Add product (MANAGE_PRODUCTS or manager help) →
  product form with the code → saved product goes on the bill. **No "Other item"**: only registered products are
  sold (D-050); old unregistered lines report as "Other items".
- Merging: the same plain piece product adds to its line; never merges weighed, scale-label, price-changed,
  discounted lines or a different pack. `CartSession.MAX_QTY` = 99,999 per line.

### 2.3 The bill
- Selected line (last scanned/changed, or tapped) shows `LineControls`: Remove · gap · − · qty · + (two rows when
  narrow). − stops at 1. Weighed: no −/+; the qty button types a weight. Scale price label: Remove only.
- Bill persisted by `CartSession` (memory first, one ordered writer; reloaded after a kill). Held bills are LOCAL
  (`cart` rows), survive restarts, never sync.
- **Audit of removals** (D-067): every removal/lowered quantity → `LINE_REMOVE` with value; after the payment
  dialog showed the total (`cart.pay_shown`, kept through hold/resume/restart) → `LINE_REMOVE_AFTER_PAY`; Clear bill
  → `BILL_CANCEL` / `BILL_CANCEL_AFTER_PAY` with the item list; deleting a held bill → `BILL_CANCEL` ("held bill: …").
  None needs a PIN (D-063).
- Hold: optional name; the held list shows name or time, the staff name (PIN login on), total repriced, lines.
  Resume holds the current bill in its place. Delete via "Delete a bill…" or long-press.

### 2.4 Menu (`showMenu`) — hidden unless allowed

Discount (bill not empty and DISCOUNT, or PRICE_OVERRIDE with a selected line) · Clear bill (bill not empty) · Held
bills (any held) · Sales & refunds (REFUND or VOID shown) else Receipts · Customers (credit on) · Open shift / Close
shift (always) · Shift & cash (CASH_MOVE or SHIFT_REPORT) · Open cash drawer (OPEN_DRAWER) · Lock / switch user (PIN
login on) · Manager PIN (someone signed in lacks a permission and no help active) · **Manage shop ›**: Products,
Categories, Promotions (MANAGE_PRODUCTS), Inventory (MANAGE_STOCK), Reports (REPORTS), Settings (SETTINGS or
MANAGE_STAFF or VIEW_AUDIT), Tax rates, Diagnostics (SETTINGS). Price check is a screen button, not in the Menu.
- Discount (`DiscountDialog`): bill / selected line — Amount (> 0, capped) or Percent (1–100); "No discount";
  line price change (0.00 allowed). Audited `BILL_DISCOUNT`, `LINE_DISCOUNT`, `PRICE_OVERRIDE`. A discounted or
  re-priced line leaves promotions. An emptied bill drops its bill discount.

### 2.5 Price check (`ui/sell/PriceCheckDialog.kt`, `domain/sell/PriceCheck.kt`)
Lookup as a scan, else name/SKU search (≤ 5, hidden products not found by name). Shows price/unit, packs, stock or
"Stock not tracked", the promotion that applies, "Hidden from the selling screen". **Change price** (one product,
not open-price, MANAGE_PRODUCTS shown): one-shot approval, money keypad (> 0), shows cost and margin; lines already
on the bill keep their price; audited `PRODUCT_PRICE_CHANGE` "(price check)". D-062.

---

## 3. Payment and checkout

### 3.1 Pay (`openPayment`)
1. `shift.required` and no shift on this till → "No shift open" → open shift → continue.
2. `core/time/ClockCheck.kt`: date before 2026-09-01 → `clock_wrong` (no sale); > 1 h before this till's last sale →
   `clock_suspect` with "Sell anyway".
3. Reads active payment methods (Customer credit only with credit on and a customer on the bill), reprices with
   today's promotions, **freezes** the bill, locks orientation; double taps open one dialog.

### 3.2 `PaymentDialog` (D-064)
- Step 1: "Total to pay" (or "Still to pay" + "Paid: …" + "Bill total"); "In cash (rounded)" when rounding changes
  it; Cash: due amount + up to 4 notes (`core/pricing/QuickCash.kt`) + "Other amount" — one tap pays in full; other
  methods — one tap pays the rest; "Split payment" (when > 1 method). A hardware digit jumps to the cash keypad.
- Step 2 keypad (fills from the right, ≤ 9 digits): cash with live change and Done (nothing typed = due), or a
  split part with a button per method (nothing typed = the rest). Back returns to step 1.
- Taps within **500 ms** of a step appearing or a part being taken are ignored (`GUARD_MS`). Zero/negative total →
  only "Complete sale".
- Rules (`core/pricing/Settlement.kt`): non-cash ≤ remaining (`pay_error_exceeds`), never gives change; cash ≥
  rounded due settles (change = given − rounded due, rounding stored); cash < exact remaining = part payment (the
  first such asks `pay_part_cash`); cash between exact and rounded-up due refused (`pay_error_cash`); a remainder
  rounding to 0 is settled by cash 0.00. Cancel after a part asks `pay_cancel_split` (drops the parts; the bill
  stays). Part payments live in memory only (`PaymentDraft`): a process death loses them; the bill survives.
- Credit: needs a customer (`error_needs_customer`) and CREDIT_SALE; over the limit (limit > 0) → "Over the credit
  limit" → Allow needs CREDIT_LIMIT, audited `CREDIT_OVER_LIMIT`; re-checked in the transaction (D-039).

### 3.3 `CheckoutService.complete`
One write transaction (app scope, survives the screen closing): sale + lines + payments, stock, summaries, outbox
event, credit charge, DRAWER job (methods with "opens drawer" that took money or gave change, drawer enabled) and
RECEIPT job(s) (auto-print on; `receipt.copies` 1–3), open bill deleted. Refusal → "Sale not saved" with the reason
(nothing written, bill kept); storage full → `pay_failed_storage`.
- Result dialog: Change (or Paid), "Received … · total …", receipt number, printing state or `result_no_printer`
  (SETTINGS holders only), customer balance, "Running low: …" (MANAGE_STOCK holders), Print receipt / Print a copy,
  Share (no permission, not a copy), New sale; a scan closes it. "After each sale" lock fires when it closes.

---

## 4. Receipts and printing

- `domain/print/ReceiptBuilder.kt` builds from the **stored** sale; `core/receipt/ReceiptLayout.kt` lays out 32/42/48
  columns (CJK = 2); labels in `core/receipt/Receipt.kt` in the **receipt language** (`receipt.lang`, defaults to the
  app language until saved). Content order: logo (per-till picture) · name · address · Tel · e-mail · Reg. No · SST No ·
  TIN · header · RECEIPT/REFUND/VOIDED/COPY · No · Date · Original (refunds) · Cashier · Customer · lines (discount or
  promotion name) · Items · Subtotal · Item discounts · Bill discount · tax lines (exclusive) · Rounding · TOTAL ·
  payments · Change · "Incl. tax" lines (inclusive) · note · footer or "Thank you!" · e-invoice QR (sales only, not
  voided; `{receipt}`, `{total}` = `12.35`, `{date}` = `YYYYMMDD`).
- Receipt numbers (`data/sale/ReceiptNumbers.kt`): `{prefix}{R if refund}{seq:06}`, prefix = two letters + "-" per
  till, chosen automatically (no UI); changed when two synced tills clash or after some restores. D-017.
- First receipt vs copy (`domain/sale/SaleActions.kt`): first print within 24 h of a sale that never queued a receipt
  is the original; anything else is a COPY (REPRINT, audited `REPRINT`). Sharing from history is a copy ("shared").
- `hw/printer/PrinterService.kt`: one thread, persistent `print_job` queue; drawer pulses first; SPP link (secure,
  insecure, RFCOMM ch. 1); closes after 45 s idle; retries 2 s, 5 s, 10 s, 30 s, then 60 s, gives up after 10 until
  woken ("offline, tap Retry"); a chunk not sent in 10 s = stalled ("check the paper and the cover"); automatic
  RECEIPT jobs older than 10 min expire; DRAWER pulses older than 2 min are dropped; images paced ≈ 500 dot rows/s;
  finished jobs forgotten after 7 days. Print modes Auto/Text/Picture; Auto prints a picture when text cannot carry
  a character (CJK without GB18030, Tamil, €, £, emoji). D-026, D-031, D-054, D-055, D-062.
- Drawer opens: payment methods with "Opens the cash drawer" (sale, refund, repayment), shift float > 0, cash in/out/
  drop, Menu → Open cash drawer (`DRAWER_OPEN`), printer settings test. Only when "Cash drawer connected" is on.

## 5. Sales history, refunds, voids (`ui/sales/*`, `domain/sale/SaleActions.kt`)

- List of every till's sales, newest first; search by receipt number (exact, digits only = this till first, `R…` =
  refunds). Detail buttons: Print copy (printer + REPRINT), Share (REPRINT), Refund / return (sale, not voided,
  REFUND), Void (not voided, VOID).
- Refund: per-line quantities (partial, repeatable), "Put the returned items back into stock" (default on), reason
  required, "Pay back with" (default: credit if any credit was used, else the method that paid most); amounts pro
  rata to the sen (`core/refund/Refunds.kt`), cash rounding mirrored; its own receipt number (`…-R000045`); joins the
  current shift; credit back for credit. Refused for a refund document, a voided sale, nothing chosen.
- Void: reason required; the sale leaves reports and stock is reversed; the void belongs to this till's **current**
  shift ("Voided (cash)"); credit reversed; drawer may open. A sale with live refunds cannot be voided ("void the
  refunds first"); refunds can be voided. Audited `SALE_VOID`. D-019.

## 6. Products, categories, tax rates, payment methods

- Product form (`ui/products/ProductEditActivity.kt`): Name*, Selling price* (empty allowed only for open price →
  0), Sold by (piece default / weight / open price; unit pcs↔kg follows), Unit, look (picture 240×240 JPEG q80 centre
  crop, ≤ 40 MB source; 12 colours or none — `product_look`, `product_image`, D-066), barcodes (several, canonical
  `Gtin.canonical`), pack barcodes (2–100,000 pieces, optional price), Scale PLU (one, leading zeros dropped),
  category, tax, cost, SKU, Track stock (on), Low-stock alert (0 = none), Opening stock (new products,
  MANAGE_STOCK), Show in search and catalogue (on). Save and Delete need MANAGE_PRODUCTS. "Save and add another" only
  in series mode (from Products +), keeps category and tax.
- Edits write only the fields changed on the screen (another till's concurrent change survives). Duplicate barcode
  → confirm; the barcode created last wins on scans. Audited `PRODUCT_PRICE_CHANGE` for price, sold-by, tax, cost,
  barcodes, packs (creating a product is not audited); delete → tombstone + `PRODUCT_DELETE`.
- **Hidden** products: out of tiles, search, Popular, price-check name search, low stock; **still sell when scanned**.
- Categories: name + colour, alphabetical (no reordering), long-press delete (products keep no category);
  recategorising moves past sales in category summaries (`Summaries.recategorize`). Not audited.
- Tax rates (SETTINGS): name, code (shown in the list only — no code prints it), rate 0–100 % (2 decimals);
  deleting untaxes products. `tax.prices_include` (default on) in Store & receipt. Per-rate-group tax: `money.md`.
- Payment methods (SETTINGS; Settings only): seed Cash (opens drawer), Card, E-wallet / QR, Customer credit; add kinds
  E-wallet/QR (default), Card, Other; "Opens the cash drawer"; "Shown at the till" (locked on for Cash and Credit).
  No delete (hide), no reorder; renaming a used method asks (`pm_rename_used`). Record-only: no terminal/gateway.

## 7. Promotions (`core/pricing/Promotions.kt`, `domain/promo/PromotionService.kt`, D-047, D-060)

Kinds: multi-buy "N for RM X" (N = 1 → "Special price", price > 0) and "Buy X get Y free". Quantities 1–1,000;
optional start/end day (inclusive, local); Running switch; ≥ 1 product, piece products only. Eligible lines: piece
products added as single units, no pack/scale/weight, no manual discount or price change. Any mix of the products
counts; dearest first; for buy-X-get-Y the cheapest of each set are free; never a negative saving; one promotion per
product (lowest id). The saving becomes the line's discount, shared by price within a group; a bill discount
applies after. Line shows "· name −saving"; receipt prints the name. Tills ≤ 1.6.1 ignore special prices. Audited
`PROMOTION_CHANGE`.

## 8. Product CSV (`core/csv/ProductCsv.kt`, `domain/products/ProductCsvService.kt`, D-041, D-042)

- Export `lekaspos-products.csv` (plain barcodes joined " | ", category/tax names, stock for tracked, cost, `#id`);
  template `lekaspos-products-template.csv` (two `EXAMPLE - ` rows, never imported).
- Import: delimiter `, ; tab`; UTF-8 (BOM optional), UTF-16, Windows-1252; ≤ 100,000 product lines; preview first
  (new / updates / problems with line numbers, ignored columns, new categories, unmatched taxes), then 200-line
  transactions in the app scope, resumable by file hash. Required: name, price. Match: `id` → barcode → unique SKU.
  Existing: name and price always set; other empty cells change nothing; barcodes only added; never deletes; never
  creates tax rates. Stock: new products get opening stock (MANAGE_STOCK, tracked, ≠ 0); existing only with the
  switch (recorded as a stock count). Headers matched loosely, English or Malay aliases (`ProductCsv.kt`). One
  `PRODUCT_IMPORT` audit entry. Cannot set pack barcodes, scale PLU, pictures, colours.

## 9. Inventory (`ui/inventory/*`, `domain/inventory/InventoryService.kt`)

- **Stock** = latest count + movements after it − sales after it + restocked refunds (voids excluded), ordered by
  HLC; never blocks a sale (negative allowed). Untracked products: no stock figure, no alert. D-008.
- **Receive**: supplier, Invoice/DO ref, lines (a plain scan adds 1 piece without asking; a carton barcode adds its
  pack size; weighed asks weight; only "Add item" (the picker) asks how many — `ReceiveActivity.add`); edit qty,
  cost per unit, or line amount (keeps the invoice amount, derives unit cost); crash-safe LOCAL draft; one
  transaction writes purchase + lines + RECEIVE movements + **moving-average cost** (`core/inventory/CostMath.kt`:
  (onHand × oldCost + amount) ÷ (onHand + qty); on hand ≤ 0, old cost 0 or untracked → amount ÷ qty). Not editable
  afterwards. Cost change not audited. D-033, D-036.
- **Adjust**: reasons Damaged, Expired (→ "Written off"), Lost, Theft, Own use (→ Adjusted), Returned to supplier
  (always out), Found (always in), Correction, Other (either way); note optional. Every removal audited
  `STOCK_WRITE_OFF` with value at cost (D-067).
- **Counts** (D-035): sessions (all or one category, several open, sync); each entry applies at once (OK on an empty
  pad = 0); scans outside the scope still count; report = expected vs counted, found/missing/net at count-time cost;
  "Finish count" only closes it. Not audited.
- Low stock: shown, tracked, level > 0, stock ≤ level. Suppliers: name*, contact, phone, e-mail, address, note
  (long-press delete). Deliveries: read-only. Stock history per product (all kinds, level after each) and Stock
  changes (non-sale movements).

## 10. Shifts and cash (`domain/shift/ShiftService.kt`, `core/shift/*`, D-038, D-067, D-068)

- One open shift per till (`shift`, LWW). Store settings `shift.required` (default off; turned on once per till
  when a non-owner can sign in with a PIN — `useShiftsWithStaff`, audited; the "done" marker `upgrade.shifts_on` is
  LOCAL meta, so a till that joins later turns the store-wide setting on again even after the owner turned it off —
  see §20) and `shift.handover` (default on).
- The till asks by itself (`core/shift/ShiftGuide.kt`, `SellActivity.checkShift`, once per person and shift/day,
  never over a payment or result): no shift and required → "Start the shift"; a shift from an earlier day → "Yesterday's
  shift is still open" (count closes it and opens today's); another person's shift today with handover on → "Take
  over the till?" (count = `handover` closes theirs and opens yours in one transaction; "Not now" = `SHIFT_CONTINUED`).
- Open (no permission): float keypad pre-filled with what the last close left (LOCAL meta `shift.left`); a different
  count → "Not what was left" → `FLOAT_DIFFERENCE`. "Count notes & coins" (MYR only, `CashCountDialog`).
- Cash in / out (reason required) / drop (CASH_MOVE, one-shot), open the drawer; need an open shift.
- Close (no permission), three steps: count → leave in the drawer (pre-filled with the float, ≤ count) → confirm with
  note. Without SHIFT_REPORT a blind close ("take out X, leave Y"); with it the report opens and prints if auto-print.
- Expected cash = float + cash sales + cash refunds (negative) − cash of documents voided during the shift + cash in −
  cash out − drops + credit repaid in cash. Over/short = counted − expected. Report labels: `core/shift/ShiftReport.kt`
  `ShiftText` (screen in app language, paper in receipt language). "Checks" (`core/staff/StaffChecks.kt`): cleared,
  taken off, after the total was shown, drawer without a sale, copies, sold on without a count, float differences.

## 11. Customers and credit (`domain/customer/CustomerService.kt`, `core/credit/*`, D-039, D-063)

`credit.enabled` (default off) shows Menu → Customers, the customer chip and the credit method. Customer: name*,
phone, e-mail, address, TIN, note, credit limit (empty = no limit; CREDIT_LIMIT, audited `CREDIT_LIMIT_CHANGE`).
Statement = `credit_entry` rows (charge, reversal, payment, adjust) with running balance. Repayment (CUSTOMERS):
≤ owed (`error_more_than_owed`), method choice, cash rounded to 5 sen with a "Cash rounding" adjust to reach 0,
joins the shift. Adjust balance (CREDIT_LIMIT, reason). Delete only at balance 0 (not audited). Refunds/voids
reverse credit.

## 12. Reports (`domain/report/ReportService.kt`, `core/report/*`, D-043, D-058)

- Periods (weeks start Monday): Today … Last year, Choose dates (≤ today). Comparison: same length just before, or
  the same days of the month(s)/year before (`core/report/Periods.kt`).
- Summary: Sales (n, takings before refunds), Refunds, Voided sales (count; excluded everywhere), Discounts, Net sales
  (without tax), Tax, Rounding, Total (after refunds), Cost of goods (average cost when sold), Gross profit, Gross
  margin, Average sale, Change vs … Then By day (≤ 31 days) / week (≤ 190) / month, Payment methods, Cashiers,
  Categories (net · profit, current category), Best sellers (top 20 by net), Stock on hand (value at cost by
  category, not period-dependent). Read from summary tables only (`SummaryRange`/`RangePlan`).
- Not sold in this period (≤ 200, stock > 0, highest value first). Staff check (VIEW_AUDIT): per person sales,
  after-total removals, cleared, removed, voids, refunds, discounts, drawer without sale, copies, cash out (not drops),
  write-offs, sold on without a count, float differences, over/short of shifts they opened; ranked by after-total
  value, then cash short, then removed value.
- CSV exports (REPORTS; UTF-8 BOM, CRLF, `'` formula guard, fixed English headers): `lekaspos-summary-…`,
  `lekaspos-daily-sales-…` (17 columns: date … `cash_over_short`), `lekaspos-products-sold-…`, `lekaspos-receipts-…`
  (monthly consolidated e-invoice source; includes voided with status). Share or save (SAF), streamed.
- Daily sales report to Google Drive (D-065): per till, SETTINGS, scope `drive.file`, folder "LekasPOS" in My Drive,
  `YYYY-MM daily-sales.csv` (the daily-sales export of the month so far), every 3 h while online for finished days,
  previous month rewritten in the first 3 days, missed months caught up (≤ 13). `DailyReportUpload`/`Worker`.

## 13. Activity log (`ui/settings/AuditLogActivity.kt`, `data/audit/AuditDao.kt`; codes in `Codes.kt`)

Append-only EVENT table, synced, all tills, newest first, filter by action. Actions and when written:
SALE_VOID · REFUND · PRICE_OVERRIDE · LINE_DISCOUNT · BILL_DISCOUNT · DRAWER_OPEN (no-sale opens) · REPRINT (copies,
shares) · PRODUCT_PRICE_CHANGE (price, sold-by, tax, cost, barcodes, packs, price check) · PRODUCT_DELETE ·
BILL_CANCEL (clear bill, deleted held bill) · APPROVAL (screen-kept approvals, manager help, credit approvals) · SIGN_IN ·
PIN_LOCKOUT · STAFF_CHANGE · ROLE_CHANGE · SHIFT_OPEN · SHIFT_CLOSE (over/short) · CASH_IN · CASH_OUT · CASH_DROP ·
CREDIT_ADJUST · CREDIT_OVER_LIMIT · OWNER_PIN_RESET · PRODUCT_IMPORT · PROMOTION_CHANGE · CREDIT_LIMIT_CHANGE ·
SETTINGS_CHANGE (store settings keys, logo, tax rates, payment methods, lock, daily report, auto shift turn-on,
backups saved/shared, restores) · CREDIT_PAYMENT · LINE_REMOVE · LINE_REMOVE_AFTER_PAY · BILL_CANCEL_AFTER_PAY ·
STOCK_WRITE_OFF · SHIFT_CONTINUED · FLOAT_DIFFERENCE. Details are written in English. Not audited: product
creation, categories, suppliers, deliveries, counts, customer create/edit/delete.

## 14. Settings catalogue

Store-wide (`setting`, LWW per key, `data/settings/Settings.kt`, keys in `SettingKeys`): `store.name/address/phone/
email/brn/sst_no/tin`, `receipt.header/footer/lang/copies(1)/logo(off)/einvoice_qr(off)/einvoice_url`,
`tax.prices_include(on)`, `currency.cash_step(5)`, `shift.required(off)`, `shift.handover(on)`,
`credit.enabled(off)`, `scale.templates(20IIIIIWWWWWC,21IIIIIPPPPPC)`. Store & receipt saves only changed keys,
audited. Currency is fixed MYR/RM/2 decimals (no UI).

Per till (`meta`, LOCAL, inside backups): `dev.printer.*` (address, paper 58/80/80-42, mode auto, gb18030 off,
codepage 0, cut on, feed 4, auto on, native QR off), `dev.drawer.*` (enabled on, pin 2), `dev.scanner.*`,
`dev.camera.enabled(on)`, `dev.tiles(medium)`, `dev.customer_screen(on)`, `dev.lock.minutes(5)`,
`dev.lock.after_sale(off)`, `dev.backup_folder*`, `dev.setup_done`, `drive_report.*`, `sync.*`, `receipt_prefix`,
`device_no`, `shift.left`. Per phone (SharedPreferences, not in backups): `lekas_ui` (app language), `lekas_reports`
(error-report consent), `lekas_updates` (daily check on, test versions off). Receipt logo picture:
`files/receipt_logo.png` (per phone, not synced, not in backups).

Settings screen order: App language · Item size · Customer screen · Store & receipt · Printer & cash drawer · Barcode
scanner & camera · Staff · Shift & cash · Customers · Tax rates · Payment methods · Categories · Activity log · Google
Drive backup · Backup & restore · Daily sales report to Google Drive · Error reports · App updates · Diagnostics ·
About. The hub has no guard; each screen guards itself.

## 15. Data safety: backups and restore (`domain/backup/BackupService.kt`, `data/backup/*`, D-044, D-048, D-053)

- Automatic daily backup (WorkManager): `quick_check` first (damage → none made, none deleted, "Data problem");
  waits for no open bill and 3 min after a sale (unless > 36 h); skipped when free space < DB × 1.5 + 50 MB; keeps 7.
  Upgrade backups only when the DB version changes (keep 3, one per version per day) — the help text says "every
  upgrade". "Before a restore" keep 3. Hand-made never deleted. All in `files/backups` (lost with the phone).
- Folder copy (SAF tree, persisted): newest automatic backup as `lekaspos-YYYY-MM-DD-HHMM.lekasbak`, keep 7 of that
  pattern only. Save/Share a backup file (`lekaspos-backup-YYYY-MM-DD.lekasbak`). A "copy off this phone" = last
  Drive sync, folder copy or saved/shared file; fresh for 3 days.
- `.lekasbak` = ZIP of `backup.json` + the database; not encrypted. Restore: checks (not readable, not a backup,
  newer DB version, damaged), dialog "Restore this till"/"Replace the old phone" vs "Add as a new till", test-open on a
  scratch copy, staged, applied by `Db.open` at the next start after "Restart now" (Restore.kt). Applying first backs
  up the current data (or refuses, changing nothing). After: print queue empty, nobody signed in, audited. Identity is
  kept only if that data never synced; otherwise (or "new till") new device number, new prefix, sync and daily
  report off.
- Damaged DB that cannot open: files moved aside (`damaged-<time>.db`), an empty store opens, "Data problem" for 7
  days.

## 16. Google Drive backup / sync (user-visible side; design in `sync.md`, D-045, D-053)

Settings → Google Drive backup (SETTINGS). Needs Play services. Till name → "Turn on sync" → GIS consent
`drive.appdata` → first round in the background (publish own data, then download). Cadence while open: 10 s after a
change, 5 s after the selling screen opens, 3 s after reconnect, rounds ≥ 45 s apart, failed rounds retried 1/2/3 min;
closed: WorkManager every 30 min + one-off 2 min after a change. Status texts: connecting, preparing, sending n of m,
receiving n of m, checking, finishing; idle: sign-in, offline, corrupt file, Drive full, Drive busy, other error,
clock wrong (> 10 min), waiting, "Everything is backed up". Syncs all store data and events; never per-till settings,
held bills, print queue, logo picture, prefs. A fresh phone joining takes the shop's staff/PINs (its own PINs
dropped, `sync_pins_dropped`); a phone with data finding another shop is asked ("Join another shop?"). Prefix clashes
are fixed automatically; till-number clash → `sync_error_clash`. Turn off: data stays, unsent outbox discarded.

## 17. Updates, error reports, diagnostics, about

- Updates (`app/AppUpdates.kt`, `ui/settings/UpdateUi.kt`, D-059, D-060): daily `UpdateWorker` (release-signed builds,
  not Play installs) reads the latest GitHub release (test versions: last 10 incl. pre-releases), downloads the APK,
  checks size + SHA-256, package, `versionName` = tag, higher `versionCode`, same signing key; refused files are
  remembered by SHA-256. Pill "Update x.y.z" for SETTINGS holders; Update now needs no open bill and SETTINGS; Android
  8+ asks "install unknown apps" once. Release notes need a `### What's new` list (optional `### Apa yang baharu`).
- Error reports (`app/ErrorReports.kt`, D-057): asked once (SETTINGS holders, no open bill); per phone; ≤ 20 queued,
  14 days, ≤ 10 sent a day, one per fingerprint a day; crashes, `Log.e`, failed perf tests, ANRs/exits (API 30+);
  expected outcomes never reported; text scrubbed; release-signed builds only. Diagnostics → Send a report works
  even when off.
- Diagnostics (SETTINGS): device line, Share the error log (`files/logs/errors.log`), Send a report, Quick test (5,000
  products) / Full test (50,000 products, 1M lines, ~350 MB), Delete test data, Share report. Separate database.
- About: version (`versionName` + build = CI run number), licence, notices, Website & updates, Privacy policy (EN or
  MS page by app language).

## 18. Customer screen (`ui/display/*`, `core/display/CustomerView.kt`, D-069)

Per till `dev.customer_screen` (default on). Android `Presentation` on the first presentation display (HDMI, USB-C,
Miracast); follows the activity in front so the back office is never mirrored. Views: Welcome (also while locked),
Bill (lines, last item highlighted and large with picture/initials on landscape, bill discount, Total / Total to pay,
item count), Thanks (Paid, Change; 30 s or until the next item). Texts in the receipt language; 1280×720 design
scaled. Google Cast "Cast screen" cannot host it.

## 19. Hardware and Android permissions

Bluetooth Classic SPP ESC/POS printers only (no USB/LAN/BLE); drawer via printer (pin 2/5); HID scanners (USB/BT) and
SPP scanners (paired only, D-027); camera (EAN-13/8, UPC-A/E, Code 128, Code 39, QR); scale labels only (no scale
link); presentation displays. Runtime permissions: Nearby devices (API 31+, choosing a printer/serial scanner),
Camera (scanner, product photo), install unknown apps (API 26+, first update). No location, contacts, storage,
notifications. Google scopes: `drive.appdata` (sync), `drive.file` (daily report).

## 20. Known gaps and inconsistencies (found while writing the user guide, 2026-10-08)

Behaviour worth a code fix (decide with the owner):
- **A weighed product with price 0.00 sells free**: the scan path asks the price only for piece products
  (`CartSession.scan`, `SellMode.UNIT && price == 0`), and `SellActivity.askWeight` adds the line at the stored
  price. Same for a pack barcode without a pack price of a 0.00 product. The guide warns owners to give weighed
  products a price.
- **`shift.required` comes back**: `ShiftService.useShiftsWithStaff` keeps its "done" marker per till
  (`upgrade.shifts_on`, LOCAL), so a till joining the shop later switches the store-wide setting on again after the
  owner switched it off. The guide tells owners to check it after adding a till.
- A **damaged database** that cannot open leaves an empty till; good backups are protected only 7 days
  (`KeepDamagedDatabase.HOLD_MS`), then daily backups of the empty data start to rotate them out.
- Saving or sharing a backup file marks the data "protected" even when the file stays in the phone's Downloads or
  the share is cancelled (`BackupService` `BACKUP_EXPORT_OK` is set when the file is written).

Small things a future change could fix (none loses data):
- `error_needs_shift` says "menu → Shift & cash"; the Menu has "Open shift" directly and cashiers do not see Shift & cash.
- `error_credit_off` says "Settings → Store"; the screen is "Store & receipt".
- Tax rate "Code on receipts (optional)" is stored and listed but never printed.
- The last-sale panel's button always says "Print a copy", even when it prints the original receipt (auto-print off).
- `backup_help` says a backup is made "before every app upgrade"; only DB-version-changing upgrades make one.
- The daily-report help lists 8 columns; the file has 17 (the checks columns were added in 1.12.0).
- Receive stock / Stock count / product picker reuse the selling screen's unknown-barcode text ("goes on this bill").
- `PIN_LOCKOUT` entries do not show whose PIN was tried; customer create/edit/delete is not audited.
- Malay plural resources have only one form ("1 ditangguh" is fine, "Semua 1 baris akan dibuang" reads oddly); BM
  "Batalkan" means both void and the cleared-bill audit label ("Bil dibatalkan"); payment methods are "Cara bayaran"
  in Reports but "Kaedah bayaran" in Settings.
- `shift_float_hint` is unused. Part payments are lost if Android kills the process mid-payment (the bill survives).
- The product form records opening stock even with Track stock off (the CSV import does not).

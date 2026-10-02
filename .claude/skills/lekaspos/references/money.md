# Money, quantities, tax, discounts, rounding

All of this is implemented once, in `:core` (`com.lekaspos.core.money` and
`com.lekaspos.core.pricing`), and unit-tested there. `:app` never does money arithmetic itself.
The worked examples below are mirrored by unit tests; change both together.

## 1. Units

| Kind | Type | Unit | Example |
|---|---|---|---|
| Money | `Money` (`Long`) | minor unit of the store currency | RM 12.90 → `1290` |
| Quantity | `Qty` (`Long`) | milli-unit of the selling unit | 3 pcs → `3000`; 0.253 kg → `253` |
| Rate | `Int` | basis points | 6% → `600`; 12.5% → `1250` |

- Currency config (store settings): ISO code (default `MYR`), symbol (`RM`), decimals
  (0–3, default 2), cash rounding step in minor units (MYR: `5`; `0`/`1` = none).
- Arithmetic uses `Math.addExact/multiplyExact`; overflow throws (never silently wraps).
- Never `Float`/`Double` for money, qty or rates. Parsing is from strings/digits.

## 2. The one rounding rule

`roundHalfUp(n, d)` = n/d rounded to the nearest integer, halves away from zero
(symmetric for negatives, so refunds mirror sales). It is applied only at these points:

1. line gross = `roundHalfUp(unitPrice × qty, 1000)`
2. percentage discount = `roundHalfUp(base × bp, 10000)`
3. tax per rate group (exclusive `roundHalfUp(base × r, 10000)`, inclusive
   `roundHalfUp(base × r, 10000 + r)`)
4. cash rounding = `roundHalfUp(amount, step) × step`

Splitting an amount across lines (bill discount, tax) uses **largest remainder**: each part
gets `floor(T × wᵢ / W)`, the leftover units go to the largest fractional remainders (ties →
earlier line). Parts always sum exactly to the whole.

## 3. A sale, step by step

For each line *i*:
- `qty` (milli of the selling unit), `unitPrice`, `gross = roundHalfUp(unitPrice × qty, 1000)`
  (or the fixed price from a price-embedded scale label).
- `lineDiscount`: amount (capped at gross) or percent of gross.
- `baseQty` = qty in the product's base unit (packs × pack size; derived weight for
  price-embedded labels). Used for stock and for quantity reports.

Bill level:
- `billDiscount`: amount or percent of Σ(gross − lineDiscount); allocated to lines by
  largest remainder with weights (gross − lineDiscount).
- `netᵢ = grossᵢ − lineDiscountᵢ − billDiscountAllocᵢ`
- Tax per rate *r*: `base_r = Σ netᵢ` over lines with rate *r*;
  exclusive `tax_r = roundHalfUp(base_r × r, 10000)`;
  inclusive `tax_r = roundHalfUp(base_r × r, 10000 + r)`.
  Allocated back to lines by largest remainder (weights netᵢ) for per-line reports.
- `subtotal = Σ gross`, `discount = Σ lineDiscount + billDiscount`, `tax = Σ tax_r`
- `totalBeforeRounding = Σ net + (pricesIncludeTax ? 0 : tax)`
- `rounding` (cash only, see §5), `total = totalBeforeRounding + rounding`

## 4. Payments and change

- Tenders: cash, card, e-wallet/QR, customer credit, other — **recorded only**, no gateway.
- Non-cash tenders may not exceed the amount still due (no change from card).
- Cash may exceed it: `change = cashTendered − cashApplied`.
- A sale is complete when Σ applied == total. Split payments are ordered as entered.

## 5. Cash rounding (Malaysia: nearest 5 sen)

- Applies only when **cash settles the final remainder**. Card/e-wallet pay exact amounts.
- `due = roundHalfUp(remaining, step) × step`; `rounding = due − remaining` (stored on the
  sale, shown on the receipt as "Rounding"). MYR: …1, …2 → down to …0; …3, …4 → up to …5;
  …6, …7 → down to …5; …8, …9 → up to …10.
- Refunds mirror it (rounding on the absolute value, sign restored).

## 6. Refunds, returns, voids

- A **refund/return** is a new sale document with `kind = REFUND`, `ref_sale_id` → original,
  lines referencing original lines with **negative** qty/gross/net/tax, negative payments.
  Refund amount per line is the original line's net per unit × returned qty, so a partial
  return refunds exactly what was paid (allocated discounts included).
- A refund line takes back the line's **cost** only when the goods go back on the shelf
  (restock): goods thrown away keep their cost in cost of goods (refund line cost 0, D-056). The
  cost share of a part follows the quantities (`round(cost × returned so far ÷ qty)`), whatever
  earlier refunds stored.
- A **void** cancels a whole completed sale (wrong transaction): `sale_void` event, sale
  status becomes voided, excluded from reports and stock. Needs permission + reason + audit.
- Removing a line from the open bill before payment is a cart edit (audited if it needs
  permission), not a void event.

## 7. Weighed items and scale barcodes

- Products with `sell_mode = WEIGHT` sell by kg (or the product's unit) with 3 decimals:
  manual weight entry → qty in milli-kg.
- Scale labels are EAN-13 with a configurable template, e.g. `20IIIIIWWWWWC`:
  literal prefix digits, `I` item code (PLU → `product_barcode` with `kind = SCALE_PLU`),
  `W` weight in grams (`qty = W`), `P` price in minor units (price-embedded), `X` ignored,
  `C` check digit (validated). Several templates can be active (e.g. prefixes 20–29).
- Price-embedded label: `gross = P` exactly as printed on the label; `unitPrice` = product
  price per kg; `baseQty = roundHalfUp(P × 1000, unitPrice)` (derived weight, for stock/reports).

## 8. Formatting and input

- Format with store settings (symbol, decimals, `,` grouping, `.` decimal) — not the device
  locale, so receipts are identical on every device. Negative: `-RM1.00`.
- POS keypad entry is digits-only, filled from the right: `1`,`2`,`5`,`0` → `12.50`.
- Text entry (`12.5`, `12.50`, `1,234.5`) is parsed exactly; more decimals than the currency
  allows is an error, never a silent rounding.

## 9. Worked examples (mirrored in unit tests)

| Case | Input | Expected |
|---|---|---|
| weighed line | 1290/kg × 0.253 kg | gross 326 (326.37 → 326) |
| half rounds up | 99 × 0.125 | 12.375 → 12; 100 × 0.125 = 12.5 → 13 |
| pieces | 120 × 3 pcs | 360 |
| percent discount | 10% of 1999 | 199.9 → 200 |
| exclusive tax | net 1000 @ 8% | tax 80, total 1080 |
| inclusive tax | net 1060 @ 6% | tax 60, total 1060 |
| inclusive tax | net 1000 @ 8% | 74.07 → tax 74 |
| bill discount split | 100 over weights 1:1:1 | 34, 33, 33 |
| cash rounding MYR | 1001, 1002, 1003, 1004 | 1000, 1000, 1005, 1005 |
| cash rounding MYR | 1006, 1007, 1008, 1009 | 1005, 1005, 1010, 1010 |
| split tender | total 1003, card 500, cash rest | cash due 505 (503 → 505), rounding +2 |
| refund mirror | refund −1003 in cash | −1005, rounding −2 |

## 10. Cost and stock value (Phase 3, D-033)

- Product cost (minor units per base unit) is the **moving weighted average**: receiving
  `q` units for line amount `T` with `h` on hand at cost `c` gives
  `roundHalfUp(h × c + T × 1000, h + q)`; with `h ≤ 0`, or `c = 0` (a cost never entered), the new
  cost is `roundHalfUp(T × 1000, q)` (D-056). A product without stock tracking has no stock to
  average with: `h` is 0, so it takes its latest delivery's cost.
- Credit repayments in cash are rounded to the cash step like a sale; paying off the whole debt
  books the difference as an ADJUST "Cash rounding" entry, so the balance ends at 0 (D-056).
- A delivery line keeps the invoice amount `T` exactly when the user types it; the unit cost is
  derived from it. Line value = `roundHalfUp(qty × unitCost, 1000)`.
- Count variance value = `roundHalfUp((counted − expected) × unitCost, 1000)` with the cost stored
  on the count (negative = loss).

## 11. Promotions (Phase 8, D-047)

`:core` `Promotions.apply` (pure), called by `Cart.price(inclTax, promotions)` before
`PricingEngine`. Two kinds (`PromoKind`), each on a set of products (any mix counts):

- **MULTI_PRICE** "N for P": every group of N units costs P (N ≥ 2, P ≥ 0).
- **BUY_GET_FREE** "buy X get Y": in every set of X + Y units, the Y cheapest are free.

Rules:
- Only **eligible** lines take part: a catalogue product sold by the piece (`sellMode UNIT`,
  pack size 1000), whole units (qty multiple of 1000), no scale-label price, no manual price
  change, no manual line discount. Packs, weighed goods, "other items" never take part.
- A product in several running promotions takes the one with the **lowest id**.
- Units are sorted **dearest first** (ties by line order) and grouped in that order, so mixed
  prices give the customer the bigger saving; for buy-get-free the last Y of each set are free.
- A group's saving = regular − P, **never negative**; it is shared over the group's lines by
  price with largest remainder (§2), so a line never saves more than its gross.
- The saving becomes the line's `Discount.Amount`, so bill discounts, tax, cash rounding and
  refunds (net per unit, §6) work unchanged. The sale line stores `promo_id` and the name at the
  time (`promo_name`); receipts print that name instead of "Discount".

| Case | Input | Expected |
|---|---|---|
| 3 for RM10 | 3 × 390 | saving 170 |
| 3 for RM10 | 4 × 390 / 6 × 390 | 170 / 340 |
| mix and match 3 for RM10 | 2 × 390 + 2 × 350 | group 390+390+350: saving 130 → 90 and 40 |
| buy 1 get 1 | 2 / 3 / 4 × 500 | 500 / 500 / 1000 |
| buy 2 get 1 | 600 + 500 + 400 | 400 free |
| deal dearer than shelf | 3 × 300 for 1000 | no saving |
| in the bill (6% inclusive) | 3 × 390 promo + 350 | net 1000 + 350, tax 57 on the promo line |

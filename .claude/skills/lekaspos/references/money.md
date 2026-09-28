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

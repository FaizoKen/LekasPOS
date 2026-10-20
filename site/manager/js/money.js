// Money and quantities written as the app writes them (core/money/MoneyFormat.kt): from the shop's
// currency settings, never the browser's locale. Amounts are minor units (sen), quantities milli-units.

export const MYR = { symbol: 'RM', decimals: 2 };

/** The shop's currency from its settings (`currency.symbol`, `currency.decimals`), like StoreSettings.from. */
export function currencyOf(settings) {
  const d = Number.parseInt(settings['currency.decimals'] ?? '', 10);
  return {
    symbol: settings['currency.symbol'] ?? MYR.symbol,
    decimals: d >= 0 && d <= 3 ? d : MYR.decimals,
  };
}

function grouped(digits) {
  const first = digits.length % 3;
  let out = '';
  for (let i = 0; i < digits.length; i++) {
    if (i > 0 && (i - first) % 3 === 0) out += ',';
    out += digits[i];
  }
  return out;
}

/** "RM1,234.50", "-RM0.05"; [withSymbol] false gives "1,234.50" (MoneyFormat.format). */
export function format(minor, c = MYR, withSymbol = true) {
  let v = BigInt(minor);
  const negative = v < 0n;
  if (negative) v = -v;
  const scale = 10n ** BigInt(c.decimals);
  let s = (negative ? '-' : '') + (withSymbol ? c.symbol : '') + grouped((v / scale).toString());
  if (c.decimals > 0) s += '.' + (v % scale).toString().padStart(c.decimals, '0');
  return s;
}

/** "-1234.50": no symbol, no grouping, '.' decimal (MoneyFormat.plain, for CSV). */
export function plain(minor, decimals) {
  let v = BigInt(minor);
  const negative = v < 0n;
  if (negative) v = -v;
  const scale = 10n ** BigInt(decimals);
  let s = (negative ? '-' : '') + (v / scale).toString();
  if (decimals > 0) s += '.' + (v % scale).toString().padStart(decimals, '0');
  return s;
}

/** 3000 → "3", 253 → "0.253", 1500 → "1.5" (MoneyFormat.formatQty). */
export function qty(milli) {
  let v = BigInt(milli);
  const negative = v < 0n;
  if (negative) v = -v;
  let s = (negative ? '-' : '') + (v / 1000n).toString();
  let frac = v % 1000n;
  if (frac !== 0n) {
    let digits = 3;
    while (frac % 10n === 0n) {
      frac /= 10n;
      digits--;
    }
    s += '.' + frac.toString().padStart(digits, '0');
  }
  return s;
}

/** A percentage of basis points: 2550 → "25.5%". */
export function percent(bp) {
  if (bp == null) return '–';
  return plain(bp, 2).replace(/\.?0+$/, '') + '%';
}

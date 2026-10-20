// Calendar and report maths, the same as the app's :core (core/time/Days.kt, core/report/Periods.kt,
// core/money/Rounding.kt). Epoch days are local dates counted from 1970-01-01; a period is [from, to).
// Amounts are Numbers of minor units (sen): sums of a shop's sales stay far below 2^53.

export const DAY_MS = 86_400_000;

/** Local epoch day of [ms] in this browser's time zone (the tills use their own, the shop's). */
export function epochDay(ms) {
  const offset = -new Date(ms).getTimezoneOffset() * 60_000;
  return Math.floor((ms + offset) / DAY_MS);
}

/** Epoch millis of the first instant of local [day] in this browser's time zone. */
export function startOfDay(day) {
  const ymd = toYmd(day);
  return new Date(Math.floor(ymd / 10000), Math.floor(ymd / 100) % 100 - 1, ymd % 100).getTime();
}

export function today() {
  return epochDay(Date.now());
}

/** yyyymmdd of [day] (Howard Hinnant's civil_from_days, as Days.toYmd). */
export function toYmd(day) {
  const z = day + 719468;
  const era = Math.floor(z / 146097);
  const doe = z - era * 146097;
  const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365);
  const y = yoe + era * 400;
  const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100));
  const mp = Math.floor((5 * doy + 2) / 153);
  const d = doy - Math.floor((153 * mp + 2) / 5) + 1;
  const m = mp < 10 ? mp + 3 : mp - 9;
  return (m <= 2 ? y + 1 : y) * 10000 + m * 100 + d;
}

/** Epoch day of yyyymmdd [ymd] (days_from_civil, as Days.fromYmd). */
export function fromYmd(ymd) {
  const year = Math.floor(ymd / 10000);
  const month = Math.floor(ymd / 100) % 100;
  const day = ymd % 100;
  if (month < 1 || month > 12 || day < 1 || day > 31) throw new RangeError(`bad date ${ymd}`);
  const y = month <= 2 ? year - 1 : year;
  const era = Math.floor(y / 400);
  const yoe = y - era * 400;
  const mp = (month + 9) % 12;
  const doy = Math.floor((153 * mp + 2) / 5) + day - 1;
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146097 + doe - 719468;
}

/** ISO day of week: 1 = Monday … 7 = Sunday. */
export function dayOfWeek(day) {
  return (((day + 3) % 7) + 7) % 7 + 1;
}

export function weekStart(day) {
  return day - (dayOfWeek(day) - 1);
}

export function monthStart(day) {
  return fromYmd(Math.floor(toYmd(day) / 100) * 100 + 1);
}

export function nextMonthStart(day) {
  const ymd = toYmd(day);
  let year = Math.floor(ymd / 10000);
  let month = Math.floor(ymd / 100) % 100 + 1;
  if (month === 13) { month = 1; year++; }
  return fromYmd(year * 10000 + month * 100 + 1);
}

function yearStart(day) {
  return fromYmd(Math.floor(toYmd(day) / 10000) * 10000 + 101);
}

const pad2 = (v) => String(v).padStart(2, '0');

/** "20/10/2026" — the app's date (core DateText.date), whatever the browser's locale. */
export function dateText(day) {
  const ymd = toYmd(day);
  return `${pad2(ymd % 100)}/${pad2(Math.floor(ymd / 100) % 100)}/${Math.floor(ymd / 10000)}`;
}

/** "14:05" in this browser's time zone. */
export function timeText(ms) {
  const d = new Date(ms);
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

/** "20/10/2026 14:05" (DateText.dateTime). */
export function dateTimeText(ms) {
  return `${dateText(epochDay(ms))} ${timeText(ms)}`;
}

/** "2026-10-20" */
export function isoDate(day) {
  const ymd = toYmd(day);
  const p = (v, w) => String(v).padStart(w, '0');
  return `${p(Math.floor(ymd / 10000), 4)}-${p(Math.floor(ymd / 100) % 100, 2)}-${p(ymd % 100, 2)}`;
}

/** Epoch day of "yyyy-mm-dd", or null. */
export function parseIso(s) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s || '');
  if (!m) return null;
  try {
    const day = fromYmd(Number(m[1]) * 10000 + Number(m[2]) * 100 + Number(m[3]));
    return isoDate(day) === s ? day : null;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------------------- periods

export class Period {
  constructor(from, to) {
    if (!(to > from)) throw new RangeError('empty period');
    this.from = from;
    this.to = to;
  }

  get days() {
    return this.to - this.from;
  }

  previous() {
    return new Period(this.from - this.days, this.from);
  }

  sameDaysBefore() {
    const ymd = toYmd(this.from);
    if (ymd % 100 !== 1) return this.previous();
    const lastYmd = toYmd(this.to - 1);
    const months = (Math.floor(lastYmd / 10000) - Math.floor(ymd / 10000)) * 12 +
      (Math.floor(lastYmd / 100) % 100 - Math.floor(ymd / 100) % 100) + 1;
    return this.monthsBefore(Math.floor(ymd / 100) % 100 === 1 && months > 1 ? Math.floor((months + 11) / 12) * 12 : months);
  }

  sameDaysYearBefore() {
    return this.monthsBefore(12);
  }

  monthsBefore(back) {
    const ymd = toYmd(this.from);
    const lastYmd = toYmd(this.to - 1);
    const endYmd = toYmd(this.to);
    const end = endYmd % 100 === 1 ? fromYmd(shiftMonths(endYmd, back)) : fromYmd(shiftMonths(lastYmd, back)) + 1;
    return new Period(fromYmd(shiftMonths(ymd, back)), end);
  }

  equals(o) {
    return o && o.from === this.from && o.to === this.to;
  }
}

function shiftMonths(ymd, months) {
  const index = Math.floor(ymd / 10000) * 12 + (Math.floor(ymd / 100) % 100 - 1) - months;
  const year = Math.floor(index / 12);
  const month = index - year * 12 + 1;
  const first = fromYmd(year * 10000 + month * 100 + 1);
  const length = nextMonthStart(first) - first;
  return year * 10000 + month * 100 + Math.min(ymd % 100, length);
}

/** Ready-made periods (core Preset); weeks start on Monday, "this …" runs up to and including today. */
export const PRESETS = ['TODAY', 'YESTERDAY', 'THIS_WEEK', 'LAST_WEEK', 'THIS_MONTH', 'LAST_MONTH', 'THIS_YEAR', 'LAST_YEAR'];

export function presetPeriod(preset, t) {
  switch (preset) {
    case 'TODAY': return new Period(t, t + 1);
    case 'YESTERDAY': return new Period(t - 1, t);
    case 'THIS_WEEK': return new Period(weekStart(t), t + 1);
    case 'LAST_WEEK': { const w = weekStart(t); return new Period(w - 7, w); }
    case 'THIS_MONTH': return new Period(monthStart(t), t + 1);
    case 'LAST_MONTH': { const m = monthStart(t); return new Period(monthStart(m - 1), m); }
    case 'THIS_YEAR': return new Period(yearStart(t), t + 1);
    case 'LAST_YEAR': { const y = yearStart(t); return new Period(yearStart(y - 1), y); }
    default: throw new RangeError(`unknown preset ${preset}`);
  }
}

/** What a report of [preset] is compared with (Preset.comparison). */
export function presetComparison(preset, p) {
  switch (preset) {
    case 'THIS_MONTH':
    case 'LAST_MONTH': return p.sameDaysBefore();
    case 'THIS_YEAR':
    case 'LAST_YEAR': return p.sameDaysYearBefore();
    default: return p.previous();
  }
}

/** Days for up to a month, weeks for up to about half a year, months beyond (Granularity.forPeriod). */
export function granularity(p) {
  if (p.days <= 31) return 'DAY';
  if (p.days <= 190) return 'WEEK';
  return 'MONTH';
}

/**
 * Every bucket of [p] in order (empty ones too), filled from [days] (rows of sum_day: day, sales, refunds,
 * total, netEx, tax, cost, discount) — Buckets.of.
 */
export function buckets(days, p, g) {
  const starts = [];
  let s = p.from;
  while (s < p.to) {
    starts.push(s);
    s = g === 'DAY' ? s + 1 : g === 'WEEK' ? weekStart(s) + 7 : nextMonthStart(s);
  }
  const acc = starts.map(() => ({ sales: 0, refunds: 0, total: 0, netEx: 0, tax: 0, cost: 0, discount: 0 }));
  for (const d of days) {
    if (d.day < p.from || d.day >= p.to) continue;
    let lo = 0;
    let hi = starts.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (starts[mid] <= d.day) lo = mid;
      else hi = mid - 1;
    }
    const a = acc[lo];
    a.sales += d.sales;
    a.refunds += d.refunds;
    a.total += d.total;
    a.netEx += d.netEx;
    a.tax += d.tax;
    a.cost += d.cost;
    a.discount += d.discount;
  }
  return starts.map((start, i) => ({
    start,
    end: i + 1 < starts.length ? starts[i + 1] : p.to,
    ...acc[i],
    grossProfit: acc[i].netEx - acc[i].cost,
  }));
}

// ---------------------------------------------------------------------------------------- rounding

/** n / d rounded half away from zero (Rounding.roundHalfUp), on BigInts. */
export function roundHalfUp(n, d) {
  n = BigInt(n);
  d = BigInt(d);
  if (d === 0n) throw new RangeError('division by zero');
  const q = n / d;
  const r = n % d;
  if (r === 0n) return q;
  const absR = r < 0n ? -r : r;
  const absD = d < 0n ? -d : d;
  if (absR >= absD - absR) return (n < 0n) === (d < 0n) ? q + 1n : q - 1n;
  return q;
}

export function mulDivHalfUp(a, b, d) {
  return roundHalfUp(BigInt(a) * BigInt(b), d);
}

const INT_MIN = -2147483648n;
const INT_MAX = 2147483647n;

/** Gross margin in basis points of net sales; null without net sales (ReportMath.marginBp). */
export function marginBp(netEx, cost) {
  if (netEx <= 0) return null;
  let bp = mulDivHalfUp(BigInt(netEx) - BigInt(cost), 10000n, netEx);
  if (bp < INT_MIN) bp = INT_MIN;
  if (bp > INT_MAX) bp = INT_MAX;
  return Number(bp);
}

/** Change from [before] to [now] in basis points of [before]; null when [before] is not positive. */
export function changeBp(now, before) {
  return before <= 0 ? null : Number(mulDivHalfUp(BigInt(now) - BigInt(before), 10000n, before));
}

/** Average per sale, half-up; 0 without sales. */
export function average(total, count) {
  return count <= 0 ? 0 : Number(roundHalfUp(total, count));
}

/** Value of [qtyMilli] at [unitCost] per unit, half-up (ReportMath.value). */
export function value(qtyMilli, unitCost) {
  return Number(mulDivHalfUp(qtyMilli, unitCost, 1000n));
}

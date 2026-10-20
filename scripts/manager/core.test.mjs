// The manager web page's ports of :core rules give the app's answers: JSON with 64-bit integers,
// calendar and report maths (cases from core PeriodsTest/DaysTest), money text (MoneyFormatTest).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parse, stringify, JsonError } from '../../site/manager/js/json64.js';
import * as D from '../../site/manager/js/days.js';
import { format, plain, qty, percent, currencyOf } from '../../site/manager/js/money.js';
import { decodeFver, encodeFver, compareVersion } from '../../site/manager/js/importer.js';

test('json keeps every digit of 64-bit ids and clocks', () => {
  const v = parse('{"id":9223372036854775807,"h":115292150460684697,"n":-5,"d":1.5,"s":"a\\"b\\u00e9","l":[1,null,true]}');
  assert.equal(v.id, 9223372036854775807n);
  assert.equal(v.h, 115292150460684697n);
  assert.equal(v.n, -5n);
  assert.equal(v.d, 1.5);
  assert.equal(v.s, 'a"bé');
  assert.deepEqual(v.l, [1n, null, true]);
  assert.equal(Object.getPrototypeOf(v), null);
  assert.equal(parse('{"__proto__":1}').__proto__, 1n); // a key, not the prototype
  assert.equal(parse('99999999999999999999'), 1e20); // beyond a Long: a double, as on the till
  assert.equal(stringify({ a: 115292150460684697n, b: 'x' }), '{"a":115292150460684697,"b":"x"}');
  for (const bad of ['{"a":}', '{"a":1', '[1,]', '01', '"\\x"', '{"a":1} x']) assert.throws(() => parse(bad), JsonError);
});

test('field versions are read and written like FieldVersions', () => {
  const m = decodeFver('price:115292150460684697:7;name:5:3');
  assert.equal(m.get('price').hlc, 115292150460684697n);
  assert.equal(m.get('name').dev, 3n);
  assert.equal(encodeFver(m), 'name:5:3;price:115292150460684697:7'); // sorted by field
  assert.equal(encodeFver(new Map()), null);
  assert.equal(compareVersion({ hlc: 5n, dev: 2n }, { hlc: 5n, dev: 1n }), 1);
  assert.equal(compareVersion({ hlc: 4n, dev: 9n }, { hlc: 5n, dev: 1n }), -1);
});

const d = (ymd) => D.fromYmd(ymd);
const P = (a, b) => new D.Period(a, b);
const same = (a, b) => assert.deepEqual([a.from, a.to], [b.from, b.to]);

test('days as in DaysTest', () => {
  assert.equal(D.toYmd(0), 19700101);
  assert.equal(D.fromYmd(19700101), 0);
  assert.equal(D.toYmd(D.fromYmd(20240229)), 20240229);
  assert.equal(D.dayOfWeek(0), 4); // a Thursday
  assert.equal(D.weekStart(d(20260930)), d(20260928));
  assert.equal(D.monthStart(d(20260930)), d(20260901));
  assert.equal(D.nextMonthStart(d(20261215)), d(20270101));
  assert.equal(D.isoDate(d(20261020)), '2026-10-20');
  assert.equal(D.dateText(d(20260929)), '29/09/2026');
  assert.equal(D.parseIso('2026-02-30'), null);
  assert.equal(D.parseIso('2026-10-20'), d(20261020));
  for (let day = -800; day < 80000; day += 37) assert.equal(D.fromYmd(D.toYmd(day)), day);
});

test('presets follow the calendar (PeriodsTest)', () => {
  const today = d(20260930);
  same(D.presetPeriod('TODAY', today), P(today, today + 1));
  same(D.presetPeriod('YESTERDAY', today), P(today - 1, today));
  same(D.presetPeriod('THIS_WEEK', today), P(d(20260928), today + 1));
  same(D.presetPeriod('LAST_WEEK', today), P(d(20260921), d(20260928)));
  same(D.presetPeriod('THIS_MONTH', today), P(d(20260901), today + 1));
  same(D.presetPeriod('LAST_MONTH', today), P(d(20260801), d(20260901)));
  same(D.presetPeriod('THIS_YEAR', today), P(d(20260101), today + 1));
  same(D.presetPeriod('LAST_YEAR', today), P(d(20250101), d(20260101)));
  same(D.presetPeriod('LAST_MONTH', d(20260115)), P(d(20251201), d(20260101)));
  same(P(d(20260801), d(20260901)).previous(), P(d(20260701), d(20260801)));
});

test('a period is compared with the same days of the month or year before (PeriodsTest)', () => {
  same(P(d(20261001), d(20261004)).sameDaysBefore(), P(d(20260901), d(20260904)));
  same(P(d(20260901), d(20261001)).sameDaysBefore(), P(d(20260801), d(20260901)));
  same(P(d(20260301), d(20260331)).sameDaysBefore(), P(d(20260201), d(20260301)));
  same(P(d(20260101), d(20261004)).sameDaysBefore(), P(d(20250101), d(20251004)));
  same(P(d(20250101), d(20260101)).sameDaysBefore(), P(d(20240101), d(20250101)));
  same(P(d(20260101), d(20260201)).sameDaysBefore(), P(d(20251201), d(20260101)));
  same(P(d(20260701), d(20261001)).sameDaysBefore(), P(d(20260401), d(20260701)));
  same(P(d(20260928), d(20261005)).sameDaysBefore(), P(d(20260921), d(20260928)));
  const first = d(20260601);
  same(D.presetComparison('TODAY', D.presetPeriod('TODAY', first)), P(first - 1, first));
  same(D.presetComparison('THIS_WEEK', P(first, first + 7)), P(first - 7, first));
  same(D.presetComparison('THIS_MONTH', D.presetPeriod('THIS_MONTH', first)), P(d(20260501), d(20260502)));
  const jan15 = d(20270115);
  same(D.presetComparison('THIS_YEAR', D.presetPeriod('THIS_YEAR', jan15)), P(d(20260101), d(20260116)));
  same(D.presetComparison('THIS_YEAR', P(d(20280101), d(20280301))), P(d(20270101), d(20270301)));
});

test('buckets include empty ones and clip to the period (PeriodsTest)', () => {
  const p = P(d(20260924), d(20261006));
  const row = (day, o) => ({ day, sales: 0, refunds: 0, total: 0, netEx: 0, tax: 0, cost: 0, discount: 0, ...o });
  const rows = [
    row(d(20260924), { sales: 3, total: 3000, netEx: 2800, cost: 1900 }),
    row(d(20260928), { sales: 1, total: 500, netEx: 470, cost: 300 }),
    row(d(20261005), { sales: 2, total: 1000, netEx: 940, cost: 600 }),
    row(d(20261010), { sales: 9, total: 9999 }),
  ];
  const byDay = D.buckets(rows, p, 'DAY');
  assert.equal(byDay.length, 12);
  assert.equal(byDay[0].sales, 3);
  assert.equal(byDay[1].sales, 0);
  const byWeek = D.buckets(rows, p, 'WEEK');
  assert.deepEqual(byWeek.map((b) => b.start), [d(20260924), d(20260928), d(20261005)]);
  assert.deepEqual(byWeek.map((b) => b.end), [d(20260928), d(20261005), d(20261006)]);
  assert.deepEqual(byWeek.map((b) => b.sales), [3, 1, 2]);
  const byMonth = D.buckets(rows, p, 'MONTH');
  assert.deepEqual(byMonth.map((b) => b.start), [d(20260924), d(20261001)]);
  assert.deepEqual(byMonth.map((b) => b.netEx), [2800 + 470, 940]);
  assert.deepEqual(byMonth.map((b) => b.grossProfit), [3270 - 2200, 940 - 600]);
  assert.equal(D.granularity(p), 'DAY');
  assert.equal(D.granularity(P(0, 90)), 'WEEK');
  assert.equal(D.granularity(P(0, 365)), 'MONTH');
});

test('margins, averages and stock values (PeriodsTest)', () => {
  assert.equal(D.marginBp(10000, 7000), 3000);
  assert.equal(D.marginBp(3000, 2000), 3333);
  assert.equal(D.marginBp(10000, 10500), -500);
  assert.equal(D.marginBp(0, 100), null);
  assert.equal(D.marginBp(1, 300000), -2147483648);
  assert.equal(D.changeBp(12500, 10000), 2500);
  assert.equal(D.changeBp(5000, 10000), -5000);
  assert.equal(D.changeBp(5000, 0), null);
  assert.equal(D.average(1000, 3), 333);
  assert.equal(D.average(1000, 0), 0);
  assert.equal(D.value(2520, 450), 1134);
  assert.equal(Number(D.roundHalfUp(-5, 2)), -3); // half away from zero
  assert.equal(Number(D.roundHalfUp(5, 2)), 3);
});

test('money and quantities are written like MoneyFormat', () => {
  assert.equal(format(123450), 'RM1,234.50');
  assert.equal(format(-5), '-RM0.05');
  assert.equal(format(123456789, { symbol: 'RM', decimals: 2 }, false), '1,234,567.89');
  assert.equal(format(1000, { symbol: '¥', decimals: 0 }), '¥1,000');
  assert.equal(plain(-123450, 2), '-1234.50');
  assert.equal(plain(7, 3), '0.007');
  assert.equal(qty(3000), '3');
  assert.equal(qty(253), '0.253');
  assert.equal(qty(1500), '1.5');
  assert.equal(qty(-2500), '-2.5');
  assert.equal(percent(2550), '25.5%');
  assert.deepEqual(currencyOf({ 'currency.symbol': '$', 'currency.decimals': '3' }), { symbol: '$', decimals: 3 });
  assert.deepEqual(currencyOf({ 'currency.decimals': '9' }), { symbol: 'RM', decimals: 2 });
});

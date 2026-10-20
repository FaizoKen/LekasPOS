// What the manager web page shows, read from its copy of the shop with the app's own queries
// (data/report/ReportDao.kt, ReportService.kt, SaleDao, StockDao, ShiftDao, AuditDao …), so every number
// is the one the till shows. Totals come from the day rows (sum_day*); a laptop sums a year of them at
// once, so the app's month and year tables are not needed. Ids leave as strings (64-bit: see json64.js).

import * as days from './days.js';
import { plain, qty as qtyText } from './money.js';

export const SaleKind = { SALE: 0, REFUND: 1 };
export const SaleStatus = { COMPLETED: 0, VOIDED: 1 };
export const PaymentKind = { CASH: 1, CARD: 2, EWALLET: 3, CREDIT: 4, OTHER: 9 };
export const CashMoveKind = { CASH_IN: 1, CASH_OUT: 2, DROP: 3 };
export const CreditKind = { CHARGE: 1, PAYMENT: 2, ADJUST: 3 };
export const AuditAction = {
  SALE_VOID: 1, REFUND: 2, PRICE_OVERRIDE: 3, LINE_DISCOUNT: 4, BILL_DISCOUNT: 5, DRAWER_OPEN: 6, REPRINT: 7,
  PRODUCT_PRICE_CHANGE: 8, PRODUCT_DELETE: 9, BILL_CANCEL: 10, APPROVAL: 11, SIGN_IN: 12, PIN_LOCKOUT: 13,
  STAFF_CHANGE: 14, ROLE_CHANGE: 15, SHIFT_OPEN: 16, SHIFT_CLOSE: 17, CASH_IN: 18, CASH_OUT: 19, CASH_DROP: 20,
  CREDIT_ADJUST: 21, CREDIT_OVER_LIMIT: 22, OWNER_PIN_RESET: 23, PRODUCT_IMPORT: 24, PROMOTION_CHANGE: 25,
  CREDIT_LIMIT_CHANGE: 26, SETTINGS_CHANGE: 27, CREDIT_PAYMENT: 28, LINE_REMOVE: 29, LINE_REMOVE_AFTER_PAY: 30,
  BILL_CANCEL_AFTER_PAY: 31, STOCK_WRITE_OFF: 32, SHIFT_CONTINUED: 33, FLOAT_DIFFERENCE: 34,
};
/** A count outside a count session in the stock changes (StockDao.KIND_HAND_COUNT). */
export const KIND_HAND_COUNT = 0;

const SEQ_BITS = 41n;
const ID_KEY = /(^id$|_id$|^hlc$|_by$)/;

/** A row with ids and clocks as strings and every other integer as a Number. */
function norm(row) {
  for (const k of Object.keys(row)) {
    const v = row[k];
    if (ID_KEY.test(k)) {
      if (typeof v === 'bigint' || typeof v === 'number') row[k] = String(v);
    } else if (typeof v === 'bigint') {
      row[k] = Number(v);
    }
  }
  return row;
}

const all = (db, sql, bind) => db.selectObjects(sql, bind).map(norm);
const first = (db, sql, bind) => {
  const r = db.selectObject(sql, bind);
  return r ? norm(r) : null;
};
const num = (v) => (v == null ? 0 : Number(v));
const id = (s) => (s == null || s === '' ? null : BigInt(s));
export const tillOf = (idText) => Number(BigInt(idText) >> SEQ_BITS);

// ----------------------------------------------------------------------------------- store facts

/** Store settings (LWW `setting`) as key → value. */
export function settings(db) {
  const out = {};
  for (const [k, v] of db.selectArrays('SELECT key, value FROM setting')) out[k] = v;
  return out;
}

export function counts(db) {
  return {
    sales: num(db.selectValue('SELECT COUNT(*) FROM sale')),
    products: num(db.selectValue('SELECT COUNT(*) FROM product WHERE deleted = 0')),
    customers: num(db.selectValue('SELECT COUNT(*) FROM customer')),
    lastSoldAt: db.selectValue('SELECT MAX(sold_at) FROM sale') ?? null,
  };
}

export function staffNames(db) {
  const out = {};
  for (const [i, name] of db.selectArrays('SELECT id, name FROM staff')) out[String(i)] = name;
  return out;
}

// ----------------------------------------------------------------------------------- reports

const TOTALS =
  'SELECT COALESCE(SUM(sale_count),0) AS saleCount, COALESCE(SUM(refund_count),0) AS refundCount, ' +
  'COALESCE(SUM(void_count),0) AS voidCount, COALESCE(SUM(gross),0) AS gross, COALESCE(SUM(discount),0) AS discount, ' +
  'COALESCE(SUM(net_ex),0) AS netEx, COALESCE(SUM(tax),0) AS tax, COALESCE(SUM(rounding),0) AS rounding, ' +
  'COALESCE(SUM(total),0) AS total, COALESCE(SUM(cost),0) AS cost, COALESCE(SUM(refund_total),0) AS refundTotal, ' +
  'COALESCE(SUM(items),0) AS items FROM sum_day WHERE day >= ? AND day < ?';

export function totals(db, p) {
  const t = first(db, TOTALS, [p.from, p.to]);
  t.grossProfit = t.netEx - t.cost;
  return t;
}

const DAYS =
  'SELECT day, sale_count AS sales, refund_count AS refunds, total, net_ex AS netEx, tax, cost, discount ' +
  'FROM sum_day WHERE day >= ? AND day < ? ORDER BY day';

const BY_PAYMENT =
  'SELECT d.method_id, m.name, d.kind, SUM(d.amount) AS amount, SUM(d.count) AS count FROM sum_day_payment d ' +
  'LEFT JOIN payment_method m ON m.id = d.method_id WHERE d.day >= ? AND d.day < ? ' +
  'GROUP BY d.method_id HAVING SUM(d.count) != 0 OR SUM(d.amount) != 0 ORDER BY 4 DESC';

const BY_STAFF =
  'SELECT d.staff_id, s.name, SUM(d.sale_count) AS saleCount, SUM(d.total) AS total, SUM(d.net_ex) AS netEx ' +
  'FROM sum_day_staff d LEFT JOIN staff s ON s.id = d.staff_id WHERE d.day >= ? AND d.day < ? ' +
  'GROUP BY d.staff_id HAVING SUM(d.sale_count) != 0 OR SUM(d.total) != 0 OR SUM(d.net_ex) != 0 ORDER BY 4 DESC';

const NOT_ZERO = 'HAVING SUM(qty) != 0 OR SUM(net_ex) != 0 OR SUM(tax) != 0 OR SUM(cost) != 0';

const PRODUCTS =
  'SELECT t.product_id, p.name, t.qty, t.net_ex AS netEx, t.tax, t.cost FROM ' +
  '(SELECT product_id, SUM(qty) AS qty, SUM(net_ex) AS net_ex, SUM(tax) AS tax, SUM(cost) AS cost FROM sum_day_product ' +
  `WHERE day >= ? AND day < ? GROUP BY product_id ${NOT_ZERO}) t LEFT JOIN product p ON p.id = t.product_id `;

// A product's sales count under its current category; those of a product whose row has not arrived yet,
// under the category of the sale (Summaries.CATEGORIES_FROM_PRODUCTS). 0 = no category.
const CATEGORIES =
  'SELECT g.cat AS category_id, c.name, g.qty, g.netEx, g.cost FROM (SELECT COALESCE(CASE WHEN p.id IS NULL ' +
  'THEN s.category_id ELSE p.category_id END, 0) AS cat, SUM(s.qty) AS qty, SUM(s.net_ex) AS netEx, SUM(s.cost) AS cost ' +
  'FROM sum_day_product s LEFT JOIN product p ON p.id = s.product_id WHERE s.day >= ? AND s.day < ? GROUP BY 1) g ' +
  'LEFT JOIN category c ON c.id = g.cat';

/** Categories of [p], most net sales first (groups that add up to nothing are left out, D-054). */
export function categories(db, p) {
  return all(db, CATEGORIES, [p.from, p.to])
    .filter((c) => c.qty !== 0 || c.netEx !== 0 || c.cost !== 0)
    .map((c) => ({ ...c, category_id: c.category_id === '0' ? null : c.category_id, name: c.category_id === '0' ? null : c.name, grossProfit: c.netEx - c.cost }))
    .sort((a, b) => b.netEx - a.netEx || cmpId(a.category_id, b.category_id));
}

function cmpId(a, b) {
  if (a === b) return 0;
  if (a == null) return -1;
  if (b == null) return 1;
  const x = BigInt(a);
  const y = BigInt(b);
  return x < y ? -1 : x > y ? 1 : 0;
}

/** Products sold in [p], best first by net sales (or quantity); [limit] null = all of them. */
export function products(db, p, { byQty = false, limit = 20 } = {}) {
  const order = byQty ? 'ORDER BY t.qty DESC, t.product_id' : 'ORDER BY t.net_ex DESC, t.product_id';
  const rows = all(db, PRODUCTS + order + (limit == null ? '' : ' LIMIT ?'), limit == null ? [p.from, p.to] : [p.from, p.to, limit]);
  for (const r of rows) {
    if (r.product_id === '0') r.name = null; // items sold without a product: "Other items"
    r.grossProfit = r.netEx - r.cost;
  }
  return rows;
}

/** Everything the report screen shows for [p], compared with [compare] (ReportService.build). */
export function report(db, p, compare, topN = 20) {
  const t = totals(db, p);
  const g = days.granularity(p);
  return {
    period: p,
    compare,
    totals: t,
    previous: totals(db, compare),
    granularity: g,
    buckets: days.buckets(all(db, DAYS, [p.from, p.to]), p, g),
    payments: all(db, BY_PAYMENT, [p.from, p.to]),
    staff: all(db, BY_STAFF, [p.from, p.to]),
    categories: categories(db, p),
    topProducts: products(db, p, { limit: topN }),
    marginBp: days.marginBp(t.netEx, t.cost),
    averageSale: days.average(t.total + t.refundTotal, t.saleCount),
  };
}

const STOCK_VALUE =
  'SELECT g.cat AS category_id, c.name, g.n AS products, g.v AS valueMilli FROM (SELECT p.category_id AS cat, COUNT(*) AS n, ' +
  'COALESCE(SUM(l.qty * p.cost), 0) AS v FROM stock_level l CROSS JOIN product p ON p.id = l.product_id ' +
  'WHERE l.qty > 0 AND p.deleted = 0 AND p.track_stock = 1 GROUP BY p.category_id) g ' +
  'LEFT JOIN category c ON c.id = g.cat ORDER BY 4 DESC';

/** Stock on hand at cost by category (ReportService.stock). */
export function stockValue(db) {
  const raw = db.selectObjects(STOCK_VALUE);
  let milli = 0n;
  let products = 0;
  const cats = raw.map((r) => {
    milli += BigInt(r.valueMilli);
    products += Number(r.products);
    return { category_id: r.category_id == null ? null : String(r.category_id), name: r.name, products: Number(r.products), value: days.value(r.valueMilli, 1) };
  });
  return { categories: cats, products, value: days.value(milli, 1) };
}

const SLOW =
  'SELECT p.id, p.name, p.unit, l.qty, p.cost FROM stock_level l CROSS JOIN product p ON p.id = l.product_id ' +
  'WHERE l.qty > 0 AND p.deleted = 0 AND p.track_stock = 1 AND l.product_id NOT IN ' +
  '(SELECT product_id FROM sum_day_product WHERE day >= ? AND day < ? AND product_id IS NOT NULL ' +
  'GROUP BY product_id HAVING SUM(qty) > 0) ORDER BY l.qty * p.cost DESC, p.id';

/** Products with stock that did not sell in [p], most stock value first: the first [limit], and the count and value of all. */
export function slowMovers(db, p, limit = 200) {
  const rows = db.selectObjects(SLOW, [p.from, p.to]);
  let milli = 0n;
  for (const r of rows) milli += BigInt(r.qty) * BigInt(r.cost);
  return {
    items: rows.slice(0, limit).map((r) => norm({ ...r, value: days.value(r.qty, r.cost) })),
    count: rows.length,
    value: days.value(milli, 1),
  };
}

// ----------------------------------------------------------------------------------- staff check (D-067)

const AUDIT_BY_STAFF =
  'SELECT staff_id, action, COUNT(*) AS count, COALESCE(SUM(amount), 0) AS amount FROM audit_log WHERE at >= ? AND at < ? GROUP BY staff_id, action';
const OVER_SHORT =
  'SELECT opened_by, COUNT(*) AS shifts, COALESCE(SUM(counted_cash - expected_cash), 0) AS difference, ' +
  'SUM(CASE WHEN counted_cash < expected_cash THEN 1 ELSE 0 END) AS short FROM shift ' +
  'WHERE opened_at >= ? AND opened_at < ? AND deleted = 0 AND closed_at IS NOT NULL GROUP BY opened_by';
const AUDIT_OF_TILL =
  'SELECT action, COUNT(*) AS count, COALESCE(SUM(amount), 0) AS amount FROM audit_log WHERE at >= ? AND at <= ? AND id >= ? AND id < ? GROUP BY action';

const tally = () => ({ count: 0, amount: 0 });

/** Activity-log groups folded into the checks (core/staff/StaffChecks.kt Checks.of). */
export function checksOf(rows) {
  const c = {
    cleared: tally(), removed: tally(), afterPay: tally(), voids: tally(), refunds: tally(), discounts: 0, drawerOpens: 0,
    copies: 0, cashOut: tally(), writeOffs: tally(), continued: 0, floatDiffs: tally(),
  };
  const add = (t, r) => { t.count += r.count; t.amount += r.amount; };
  const A = AuditAction;
  for (const r of rows) {
    switch (r.action) {
      case A.BILL_CANCEL: add(c.cleared, r); break;
      case A.BILL_CANCEL_AFTER_PAY: add(c.cleared, r); add(c.afterPay, r); break;
      case A.LINE_REMOVE: add(c.removed, r); break;
      case A.LINE_REMOVE_AFTER_PAY: add(c.removed, r); add(c.afterPay, r); break;
      case A.SALE_VOID: add(c.voids, r); break;
      case A.REFUND: add(c.refunds, r); break;
      case A.LINE_DISCOUNT: case A.BILL_DISCOUNT: case A.PRICE_OVERRIDE: c.discounts += r.count; break;
      case A.DRAWER_OPEN: c.drawerOpens += r.count; break;
      case A.REPRINT: c.copies += r.count; break;
      case A.CASH_OUT: add(c.cashOut, r); break;
      case A.STOCK_WRITE_OFF: add(c.writeOffs, r); break;
      case A.SHIFT_CONTINUED: c.continued += r.count; break;
      case A.FLOAT_DIFFERENCE: add(c.floatDiffs, r); break;
      default: break;
    }
  }
  c.empty = [c.cleared, c.removed, c.afterPay, c.voids, c.refunds, c.cashOut, c.writeOffs, c.floatDiffs].every((t) => t.count === 0 && t.amount === 0) &&
    c.discounts === 0 && c.drawerOpens === 0 && c.copies === 0 && c.continued === 0;
  return c;
}

/** Per person: sales, checks and the cash of the shifts they opened, the most to look at first (ReportService.staffCheck). */
export function staffCheck(db, p) {
  const from = days.startOfDay(p.from);
  const to = days.startOfDay(p.to);
  const names = staffNames(db);
  const sales = new Map(all(db, BY_STAFF, [p.from, p.to]).map((s) => [s.staff_id, s]));
  const audit = new Map();
  for (const r of all(db, AUDIT_BY_STAFF, [from, to])) {
    if (r.staff_id == null) continue;
    if (!audit.has(r.staff_id)) audit.set(r.staff_id, []);
    audit.get(r.staff_id).push({ action: Number(r.action), count: r.count, amount: r.amount });
  }
  const shifts = new Map(all(db, OVER_SHORT, [from, to]).map((s) => [s.opened_by, s]));
  const ids = new Set([...sales.keys(), ...audit.keys(), ...shifts.keys()]);
  const list = [...ids].map((sid) => {
    const s = sales.get(sid);
    const sh = shifts.get(sid);
    return {
      staff_id: sid, name: names[sid] ?? s?.name ?? null, sales: { count: s?.saleCount ?? 0, amount: s?.total ?? 0 },
      checks: checksOf(audit.get(sid) ?? []), shifts: sh?.shifts ?? 0, overShort: sh?.difference ?? 0, shortShifts: sh?.short ?? 0,
    };
  }).filter((x) => x.sales.count > 0 || !x.checks.empty || x.shifts > 0);
  return list.sort((a, b) => b.checks.afterPay.amount - a.checks.afterPay.amount ||
    Math.min(a.overShort, 0) - Math.min(b.overShort, 0) ||
    (b.checks.removed.amount + b.checks.cleared.amount) - (a.checks.removed.amount + a.checks.cleared.amount) ||
    (a.name ?? '').localeCompare(b.name ?? ''));
}

// ----------------------------------------------------------------------------------- sales

const SALE_ROW = 'SELECT id, kind, receipt_no, sold_at, total, status, line_count, staff_id, refunded, ref_sale_id FROM sale ';

/** Newest first, a page after the last row of the previous one ({sold_at, id}); or the receipt numbered [receipt]. */
export function sales(db, { after = null, limit = 50, receipt = null, day = null } = {}) {
  if (receipt) {
    return all(db, SALE_ROW + 'WHERE receipt_no = ? OR receipt_no LIKE ? ORDER BY sold_at DESC, id DESC LIMIT ?',
      [receipt, `%-${receipt}`, limit]);
  }
  const where = [];
  const bind = [];
  if (day != null) {
    // The stored business day, inside the sold_at window it can lie in (ReportDao.receiptsOfDays).
    where.push('sold_at >= ? AND sold_at < ? AND day = ?');
    bind.push((day - 1) * days.DAY_MS, (day + 2) * days.DAY_MS, day);
  }
  if (after) {
    where.push('sold_at <= ? AND (sold_at < ? OR id < ?)');
    bind.push(after.sold_at, after.sold_at, id(after.id));
  }
  return all(db, SALE_ROW + (where.length ? `WHERE ${where.join(' AND ')} ` : '') + 'ORDER BY sold_at DESC, id DESC LIMIT ?', [...bind, limit]);
}

/** One receipt with its lines, payments, voids and refunds. */
export function sale(db, saleId) {
  const sid = id(saleId);
  const s = first(db, 'SELECT s.*, st.name AS staff_name, c.name AS customer_name FROM sale s ' +
    'LEFT JOIN staff st ON st.id = s.staff_id LEFT JOIN customer c ON c.id = s.customer_id WHERE s.id = ?', [sid]);
  if (!s) return null;
  s.lines = all(db, 'SELECT id, line_no, product_id, name, barcode, unit, qty, base_qty, unit_price, gross, discount, bill_discount, ' +
    'net, tax_bp, tax, cost, price_overridden, promo_name FROM sale_line WHERE sale_id = ? ORDER BY line_no', [sid]);
  s.payments = all(db, 'SELECT p.id, p.method_id, COALESCE(m.name, p.kind) AS name, p.kind, p.amount, p.tendered, p.change_given, p.ref ' +
    'FROM payment p LEFT JOIN payment_method m ON m.id = p.method_id WHERE p.sale_id = ? ORDER BY p.id', [sid]);
  s.voids = all(db, 'SELECT v.id, v.reason, v.at, v.staff_id, st.name AS staff_name, ap.name AS approved_name FROM sale_void v ' +
    'LEFT JOIN staff st ON st.id = v.staff_id LEFT JOIN staff ap ON ap.id = v.approved_by WHERE v.sale_id = ? ORDER BY v.hlc, v.id', [sid]);
  s.refunds = all(db, SALE_ROW + 'WHERE ref_sale_id = ? AND ref_sale_id IS NOT NULL ORDER BY sold_at', [sid]);
  s.original = s.ref_sale_id ? first(db, SALE_ROW + 'WHERE id = ?', [id(s.ref_sale_id)]) : null;
  s.till = tillOf(s.id);
  return s;
}

// ----------------------------------------------------------------------------------- products and stock

const PRODUCT_COLS =
  'SELECT p.id, p.name, p.name_key, p.sku, p.unit, p.sell_mode, p.price, p.cost, p.track_stock, p.low_stock, p.active, ' +
  'p.category_id, c.name AS category, COALESCE(l.qty, 0) AS stock, ' +
  "(SELECT GROUP_CONCAT(b.code, ' ') FROM product_barcode b WHERE b.product_id = p.id AND b.deleted = 0) AS barcodes " +
  'FROM product p LEFT JOIN category c ON c.id = p.category_id LEFT JOIN stock_level l ON l.product_id = p.id ';
const LOW_FILTER = 'p.active = 1 AND p.low_stock > 0 AND p.track_stock = 1 AND COALESCE(l.qty, 0) <= p.low_stock';

/**
 * Products by name, a page after {name_key, id}; [search] matches name, SKU or a barcode; [filter]:
 * 'low' (at or below the alert level), 'hidden', or null.
 */
export function productList(db, { search = '', filter = null, after = null, limit = 100 } = {}) {
  const where = ['p.deleted = 0'];
  const bind = [];
  const q = search.trim();
  if (q) {
    const like = "LIKE ? ESCAPE '\\'";
    where.push(`(p.name ${like} OR p.name_key ${like} OR p.sku ${like} OR EXISTS (SELECT 1 FROM product_barcode b ` +
      `WHERE b.product_id = p.id AND b.deleted = 0 AND b.code ${like}))`);
    const pattern = `%${q.replace(/[\\%_]/g, (m) => '\\' + m)}%`;
    bind.push(pattern, pattern.toLowerCase(), pattern, pattern);
  }
  if (filter === 'low') where.push(LOW_FILTER);
  if (filter === 'hidden') where.push('p.active = 0');
  if (after) {
    where.push('p.name_key >= ? AND (p.name_key > ? OR p.id > ?)');
    bind.push(after.name_key, after.name_key, id(after.id));
  }
  return all(db, PRODUCT_COLS + `WHERE ${where.join(' AND ')} ORDER BY p.name_key, p.id LIMIT ?`, [...bind, limit]);
}

export function lowStockCount(db) {
  return num(db.selectValue(`SELECT COUNT(*) FROM product p LEFT JOIN stock_level l ON l.product_id = p.id WHERE p.deleted = 0 AND ${LOW_FILTER}`));
}

/** One product: its row, barcodes and its latest stock history (sales, changes and counts, newest first). */
export function product(db, productId, historyLimit = 100) {
  const pid = id(productId);
  const p = first(db, PRODUCT_COLS + 'WHERE p.id = ?', [pid]);
  if (!p) return null;
  p.barcode_rows = all(db, 'SELECT id, code, kind, pack_qty, pack_price FROM product_barcode WHERE product_id = ? AND deleted = 0 ORDER BY id', [pid]);
  p.tax = first(db, 'SELECT name, rate_bp FROM tax_rate WHERE id = (SELECT tax_rate_id FROM product WHERE id = ?)', [pid]);
  const lim = historyLimit;
  const sold = all(db, 'SELECT l.id, l.hlc, s.sold_at AS at, l.stock_qty AS qty, s.receipt_no, s.kind, s.status, l.sale_id ' +
    'FROM sale_line l JOIN sale s ON s.id = l.sale_id WHERE l.product_id = ? ORDER BY l.hlc DESC, l.id DESC LIMIT ?', [pid, lim]);
  const moves = all(db, 'SELECT id, hlc, at, qty, kind, reason, staff_id FROM stock_movement WHERE product_id = ? ORDER BY hlc DESC, id DESC LIMIT ?', [pid, lim]);
  const countsRows = all(db, 'SELECT id, hlc, at, qty AS counted, expected, session_id, staff_id FROM stock_count WHERE product_id = ? ORDER BY hlc DESC, id DESC LIMIT ?', [pid, lim]);
  const tag = (type) => (r) => ({ ...r, type });
  p.history = [...sold.map(tag('sale')), ...moves.map(tag('move')), ...countsRows.map(tag('count'))]
    .sort((a, b) => cmpId(b.hlc, a.hlc) || cmpId(b.id, a.id)).slice(0, lim);
  return p;
}

const MOVES =
  'SELECT m.id, m.product_id, p.name, p.unit, m.kind, m.qty, m.unit_cost, m.ref_id, m.reason, m.staff_id, m.at, m.hlc ' +
  'FROM stock_movement m LEFT JOIN product p ON p.id = m.product_id ';
const HAND_COUNTS =
  'SELECT c.id, c.product_id, p.name, p.unit, c.qty AS counted, c.expected, c.note AS reason, c.staff_id, c.at, c.hlc ' +
  'FROM stock_count c LEFT JOIN product p ON p.id = c.product_id WHERE c.session_id IS NULL ';

/** Every stock change made by hand, newest first: movements and counts outside a session (StockDao.movementPage). */
export function movements(db, { after = null, limit = 50 } = {}) {
  const k = after ? [BigInt(after.hlc), BigInt(after.hlc), id(after.id), limit] : [limit];
  const moves = all(db, MOVES + (after ? 'WHERE m.hlc <= ? AND (m.hlc < ? OR m.id < ?) ' : '') + 'ORDER BY m.hlc DESC, m.id DESC LIMIT ?', k);
  const hand = all(db, HAND_COUNTS + (after ? 'AND c.hlc <= ? AND (c.hlc < ? OR c.id < ?) ' : '') + 'ORDER BY c.hlc DESC, c.id DESC LIMIT ?', k)
    .map((c) => ({ ...c, kind: KIND_HAND_COUNT, qty: c.counted - (c.expected ?? c.counted) }));
  return [...moves, ...hand].sort((a, b) => cmpId(b.hlc, a.hlc) || cmpId(b.id, a.id)).slice(0, limit);
}

export function purchases(db, { after = null, limit = 50 } = {}) {
  const head = 'SELECT p.id, p.ref_no, p.total, p.note, p.at, p.staff_id, s.name AS supplier, ' +
    '(SELECT COUNT(*) FROM purchase_line l WHERE l.purchase_id = p.id) AS lines FROM purchase p LEFT JOIN supplier s ON s.id = p.supplier_id ';
  return after
    ? all(db, head + 'WHERE p.at <= ? AND (p.at < ? OR p.id < ?) ORDER BY p.at DESC, p.id DESC LIMIT ?', [after.at, after.at, id(after.id), limit])
    : all(db, head + 'ORDER BY p.at DESC, p.id DESC LIMIT ?', [limit]);
}

export function purchase(db, purchaseId) {
  const pid = id(purchaseId);
  const p = first(db, 'SELECT p.*, s.name AS supplier FROM purchase p LEFT JOIN supplier s ON s.id = p.supplier_id WHERE p.id = ?', [pid]);
  if (!p) return null;
  p.lines = all(db, 'SELECT l.id, l.product_id, pr.name, pr.unit, l.qty, l.unit_cost, l.total FROM purchase_line l ' +
    'LEFT JOIN product pr ON pr.id = l.product_id WHERE l.purchase_id = ? ORDER BY l.id', [pid]);
  return p;
}

// ----------------------------------------------------------------------------------- shifts (D-038)

const SHIFT_COLS = 'SELECT id, device_no, opened_by, opened_at, opening_float, closed_by, closed_at, counted_cash, expected_cash, note FROM shift ';

export function shifts(db, { after = null, limit = 50 } = {}) {
  return after
    ? all(db, SHIFT_COLS + 'WHERE deleted = 0 AND opened_at <= ? AND (opened_at < ? OR id < ?) ORDER BY opened_at DESC, id DESC LIMIT ?',
      [after.opened_at, after.opened_at, id(after.id), limit])
    : all(db, SHIFT_COLS + 'WHERE deleted = 0 ORDER BY opened_at DESC, id DESC LIMIT ?', [limit]);
}

export function openShifts(db) {
  return all(db, SHIFT_COLS + 'WHERE deleted = 0 AND closed_at IS NULL ORDER BY opened_at DESC');
}

const FIRST_VOID =
  'NOT EXISTS (SELECT 1 FROM sale_void w WHERE w.sale_id = v.sale_id AND w.hlc <= v.hlc ' +
  'AND (w.hlc < v.hlc OR (w.id >> 41) < (v.id >> 41) OR ((w.id >> 41) = (v.id >> 41) AND w.id < v.id)))';

/** A shift's report: sales, payments, the drawer's expected cash and the checks (ShiftDao.totals + ShiftService.build). */
export function shiftReport(db, shiftId) {
  const sid = id(shiftId);
  const s = first(db, SHIFT_COLS + 'WHERE id = ?', [sid]);
  if (!s) return null;
  const docs = all(db, 'SELECT kind, COUNT(*) AS count, SUM(total) AS total, SUM(discount) AS discount, SUM(tax) AS tax FROM sale WHERE shift_id = ? GROUP BY kind', [sid]);
  const v = first(db, 'SELECT COUNT(*) AS count, SUM(s.total) AS total, SUM(s.discount) AS discount, SUM(s.tax) AS tax FROM sale_void v ' +
    `CROSS JOIN sale s ON s.id = v.sale_id WHERE v.shift_id = ? AND ${FIRST_VOID}`, [sid]);
  const pay = all(db, 'SELECT method_id, kind, COUNT(*) AS count, SUM(amount) AS amount, SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END) AS positive ' +
    'FROM payment WHERE shift_id = ? GROUP BY method_id, kind', [sid]);
  const voided = all(db, 'SELECT p.method_id, p.kind, COUNT(*) AS count, SUM(p.amount) AS amount, SUM(CASE WHEN p.amount > 0 THEN p.amount ELSE 0 END) AS positive ' +
    `FROM sale_void v CROSS JOIN payment p ON p.sale_id = v.sale_id WHERE v.shift_id = ? AND (p.kind <> ${PaymentKind.CREDIT} OR ${FIRST_VOID}) GROUP BY p.method_id, p.kind`, [sid]);
  const moves = {};
  for (const r of all(db, 'SELECT kind, SUM(amount) AS amount FROM cash_movement WHERE shift_id = ? GROUP BY kind', [sid])) moves[r.kind] = r.amount;
  const credit = all(db, 'SELECT c.kind, c.method_id, m.kind AS method_kind, COUNT(*) AS count, SUM(c.amount) AS amount FROM credit_entry c ' +
    'LEFT JOIN payment_method m ON m.id = c.method_id WHERE c.shift_id = ? GROUP BY c.kind, c.method_id', [sid]);
  const methods = {};
  for (const [mid, name] of db.selectArrays('SELECT id, name FROM payment_method')) methods[String(mid)] = name;
  const staff = staffNames(db);

  const sum = (rows, k) => rows.reduce((a, r) => a + num(r[k]), 0);
  const sale = docs.filter((d) => d.kind === 0);
  const refund = docs.filter((d) => d.kind === 1);
  const methodIds = [...new Set([...pay.map((r) => r.method_id), ...voided.map((r) => r.method_id)])];
  const byMethod = methodIds.map((m) => {
    const p = pay.filter((r) => r.method_id === m);
    const vv = voided.filter((r) => r.method_id === m);
    return { name: methods[m] ?? `#${m}`, kind: (p[0] ?? vv[0]).kind, count: sum(p, 'count'), amount: sum(p, 'amount') - sum(vv, 'amount') };
  });
  const cashPay = pay.filter((r) => r.kind === PaymentKind.CASH);
  const cash = {
    openingFloat: s.opening_float,
    cashSales: sum(cashPay, 'positive'),
    cashRefunds: sum(cashPay, 'amount') - sum(cashPay, 'positive'),
    voided: sum(voided.filter((r) => r.kind === PaymentKind.CASH), 'amount'),
    cashIn: moves[CashMoveKind.CASH_IN] ?? 0,
    cashOut: moves[CashMoveKind.CASH_OUT] ?? 0,
    drops: moves[CashMoveKind.DROP] ?? 0,
    creditRepayments: sum(credit.filter((r) => r.kind === CreditKind.PAYMENT && r.method_kind === PaymentKind.CASH), 'amount'),
  };
  cash.expected = cash.openingFloat + cash.cashSales + cash.cashRefunds - cash.voided + cash.cashIn - cash.cashOut - cash.drops + cash.creditRepayments;
  const first41 = BigInt(s.device_no) << SEQ_BITS;
  const checkRows = all(db, AUDIT_OF_TILL, [s.opened_at, s.closed_at ?? Date.now(), first41, first41 + (1n << SEQ_BITS)])
    .map((r) => ({ action: Number(r.action), count: r.count, amount: r.amount }));
  return {
    ...s,
    openedByName: staff[s.opened_by] ?? null,
    closedByName: s.closed_by ? staff[s.closed_by] ?? null : null,
    sales: sum(sale, 'count'), salesTotal: sum(sale, 'total'),
    refunds: sum(refund, 'count'), refundsTotal: sum(refund, 'total'),
    voids: v.count, voidsTotal: num(v.total),
    discounts: sum(docs, 'discount') - num(v.discount),
    tax: sum(docs, 'tax') - num(v.tax),
    methods: byMethod,
    cash,
    creditCharged: sum(credit.filter((r) => r.kind === CreditKind.CHARGE), 'amount'),
    creditRepaid: credit.filter((r) => r.kind === CreditKind.PAYMENT)
      .map((r) => ({ name: r.method_id ? methods[r.method_id] ?? '-' : '-', count: r.count, amount: r.amount })),
    checks: checksOf(checkRows),
    moveList: all(db, 'SELECT id, kind, amount, reason, staff_id, at FROM cash_movement WHERE shift_id = ? ORDER BY at, id', [sid]),
  };
}

// ----------------------------------------------------------------------------------- customers and credit (D-039)

/** Customers, the most owed first; [search] matches name or phone. Removed ones are listed while they owe. */
export function customers(db, { search = '' } = {}) {
  const where = ['(c.deleted = 0 OR COALESCE(b.balance, 0) != 0)'];
  const bind = [];
  const q = search.trim();
  if (q) {
    const pattern = `%${q.replace(/[\\%_]/g, (m) => '\\' + m)}%`;
    where.push("(c.name LIKE ? ESCAPE '\\' OR c.phone LIKE ? ESCAPE '\\')");
    bind.push(pattern, pattern);
  }
  return all(db, 'SELECT c.id, c.name, c.phone, c.credit_limit, c.deleted, COALESCE(b.balance, 0) AS balance FROM customer c ' +
    `LEFT JOIN customer_balance b ON b.customer_id = c.id WHERE ${where.join(' AND ')} ORDER BY balance DESC, c.name_key, c.id LIMIT 1000`, bind);
}

/** One customer with their statement, newest first, each entry with the balance after it (CustomerService.statement). */
export function customer(db, customerId) {
  const cid = id(customerId);
  const c = first(db, 'SELECT c.*, COALESCE(b.balance, 0) AS balance FROM customer c LEFT JOIN customer_balance b ON b.customer_id = c.id WHERE c.id = ?', [cid]);
  if (!c) return null;
  let balance = 0;
  const entries = all(db, 'SELECT e.id, e.kind, e.amount, e.note, e.at, e.sale_id, s.receipt_no, m.name AS method FROM credit_entry e ' +
    'LEFT JOIN sale s ON s.id = e.sale_id LEFT JOIN payment_method m ON m.id = e.method_id WHERE e.customer_id = ? ORDER BY e.hlc, e.id', [cid])
    .map((e) => {
      const delta = e.kind === CreditKind.PAYMENT ? -e.amount : e.amount; // CreditMath.delta
      balance += delta;
      return { ...e, delta, balanceAfter: balance };
    });
  c.entries = entries.reverse();
  return c;
}

// ----------------------------------------------------------------------------------- activity log

const AUDIT_COLS = 'SELECT id, action, staff_id, approved_by, entity, entity_id, amount, detail, at FROM audit_log ';

export function audit(db, { action = 0, after = null, limit = 100 } = {}) {
  const where = [];
  const bind = [];
  if (action) { where.push('action = ?'); bind.push(action); }
  if (after) { where.push('at <= ? AND (at < ? OR id < ?)'); bind.push(after.at, after.at, id(after.id)); }
  return all(db, AUDIT_COLS + (where.length ? `WHERE ${where.join(' AND ')} ` : '') + 'ORDER BY at DESC, id DESC LIMIT ?', [...bind, limit]);
}

export function roles(db) {
  return all(db, 'SELECT id, name, sys_role, perms FROM role WHERE deleted = 0');
}

// ----------------------------------------------------------------------------------- CSV exports (ReportService.write)

const BOM = '﻿';
const NUMBER = /^[+-]?[0-9][0-9.,]*%?$/;

/** [s] with a `'` in front when a spreadsheet would read it as a formula (CsvWriter.defuse). */
export function defuse(s) {
  if (!s) return s;
  const c = s[0];
  const risky = c === '=' || c === '@' || c === '\t' || c === '\r' || ((c === '+' || c === '-') && !NUMBER.test(s));
  return risky ? `'${s}` : s;
}

function csvField(text) {
  const s = defuse(text ?? '');
  const quote = s.length > 0 && (s[0] === ' ' || s[s.length - 1] === ' ' || /[",\n\r]/.test(s));
  return quote ? `"${s.replace(/"/g, '""')}"` : s;
}

class Csv {
  constructor() {
    this.parts = [BOM];
    this.rows = 0;
  }

  row(...fields) {
    this.parts.push(fields.map((f) => csvField(f == null ? '' : String(f))).join(',') + '\r\n');
    this.rows++;
  }

  text() {
    return this.parts.join('');
  }
}

const OTHER = '(other items)';

/** [what] (summary, daily, products, receipts) of [p] as the app's CSV text; [d] = currency decimals. */
export function exportCsv(db, what, p, d) {
  const w = new Csv();
  const m = (v) => plain(v, d);
  const date = days.isoDate;
  if (what === 'summary') {
    const rep = report(db, p, p.previous(), 50);
    const t = rep.totals;
    const stock = stockValue(db);
    w.row('section', 'item', 'count', 'amount');
    w.row('period', 'from', null, date(p.from));
    w.row('period', 'to', null, date(p.to - 1));
    w.row('sales', 'sales', t.saleCount, m(t.total + t.refundTotal));
    w.row('sales', 'refunds', t.refundCount, m(-t.refundTotal));
    w.row('sales', 'voided', t.voidCount, null);
    w.row('sales', 'gross', null, m(t.gross));
    w.row('sales', 'discounts', null, m(t.discount));
    w.row('sales', 'net_sales_ex_tax', null, m(t.netEx));
    w.row('sales', 'tax', null, m(t.tax));
    w.row('sales', 'rounding', null, m(t.rounding));
    w.row('sales', 'total', null, m(t.total));
    w.row('sales', 'cost', null, m(t.cost));
    w.row('sales', 'gross_profit', null, m(t.grossProfit));
    w.row('sales', 'margin_percent', null, rep.marginBp == null ? null : plain(rep.marginBp, 2));
    for (const x of rep.payments) w.row('payment', x.name ?? `#${x.method_id}`, x.count, m(x.amount));
    for (const x of rep.staff) w.row('cashier', x.name ?? '-', x.saleCount, m(x.total));
    for (const x of rep.categories) w.row('category', x.name ?? '-', qtyText(x.qty), m(x.netEx));
    for (const x of rep.topProducts) w.row('top_product', x.name ?? OTHER, qtyText(x.qty), m(x.netEx));
    for (const x of stock.categories) w.row('stock_value', x.name ?? '-', x.products, m(x.value));
  } else if (what === 'daily') {
    w.row('date', 'sales', 'refunds', 'net_sales_ex_tax', 'tax', 'total', 'discount', 'cost', 'gross_profit',
      'bills_cleared', 'bills_cleared_value', 'items_taken_off', 'items_taken_off_value', 'after_total_shown',
      'after_total_shown_value', 'drawer_opened_no_sale', 'cash_over_short');
    for (const b of days.buckets(all(db, DAYS, [p.from, p.to]), p, 'DAY')) {
      const from = days.startOfDay(b.start);
      const to = days.startOfDay(b.start + 1);
      const k = checksOf(all(db, AUDIT_BY_STAFF, [from, to]).filter((r) => r.staff_id != null)
        .map((r) => ({ action: Number(r.action), count: r.count, amount: r.amount })));
      const overShort = all(db, OVER_SHORT, [from, to]).reduce((a, r) => a + r.difference, 0);
      w.row(date(b.start), b.sales, b.refunds, m(b.netEx), m(b.tax), m(b.total), m(b.discount), m(b.cost), m(b.grossProfit),
        k.cleared.count, m(k.cleared.amount), k.removed.count, m(k.removed.amount), k.afterPay.count, m(k.afterPay.amount),
        k.drawerOpens, m(overShort));
    }
  } else if (what === 'products') {
    w.row('product', 'qty', 'net_sales_ex_tax', 'tax', 'cost', 'gross_profit');
    for (const t of products(db, p, { limit: null })) w.row(t.name ?? OTHER, qtyText(t.qty), m(t.netEx), m(t.tax), m(t.cost), m(t.grossProfit));
  } else if (what === 'receipts') {
    w.row('receipt_no', 'date', 'time', 'type', 'status', 'cashier', 'customer', 'customer_tin', 'items',
      'subtotal', 'discount', 'net_ex_tax', 'tax', 'rounding', 'total', 'payments');
    const rows = all(db, 'SELECT s.id, s.kind, s.receipt_no, s.sold_at, s.status, s.line_count, s.subtotal, s.discount, s.tax, s.rounding, ' +
      's.total, s.prices_incl_tax, st.name AS staff, c.name AS customer, c.tin, ' +
      "(SELECT GROUP_CONCAT(COALESCE(m.name, p.kind), ' + ') FROM payment p LEFT JOIN payment_method m ON m.id = p.method_id WHERE p.sale_id = s.id) AS payments, s.day " +
      'FROM sale s LEFT JOIN staff st ON st.id = s.staff_id LEFT JOIN customer c ON c.id = s.customer_id ' +
      'WHERE s.sold_at >= ? AND s.sold_at < ? AND s.day >= ? AND s.day < ? ORDER BY s.sold_at, s.id',
    [(p.from - 1) * days.DAY_MS, (p.to + 1) * days.DAY_MS, p.from, p.to]);
    for (const s of rows) {
      const netEx = s.subtotal - s.discount - (s.prices_incl_tax ? s.tax : 0);
      w.row(s.receipt_no, date(s.day), days.timeText(s.sold_at), s.kind === SaleKind.REFUND ? 'refund' : 'sale',
        s.status === SaleStatus.VOIDED ? 'voided' : 'completed', s.staff, s.customer, s.tin, s.line_count,
        m(s.subtotal), m(s.discount), m(netEx), m(s.tax), m(s.rounding), m(s.total), s.payments);
    }
  } else {
    throw new RangeError(`unknown export ${what}`);
  }
  return { text: w.text(), rows: w.rows - 1 };
}

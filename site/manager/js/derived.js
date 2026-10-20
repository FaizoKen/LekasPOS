// The derived tables, worked out for what an import changed (importer.js Touched) with the SQL of the
// app's DerivedRebuild, StockDao and SaleDao — what every till keeps up to date one event at a time
// (references/database.md §7). The month, year and category totals are not kept here: the web page sums
// the day rows, and files a product's sales under its current category as it reads them (reports.js).

const OK = 0; // SaleStatus.COMPLETED
const VOIDED = 1;
const SALE = 0; // SaleKind
const REFUND = 1;
const CREDIT_CHARGE = 1n; // CreditKind.CHARGE
const CREDIT_PAYMENT = 2; // CreditKind.PAYMENT
const PAYMENT_CREDIT = 4; // PaymentKind.CREDIT
const SEQ_BITS = 41n;

/** Note of the charge that cancels a duplicate void's credit reversal (SaleDao.DUPLICATE_VOID_NOTE). */
export const DUPLICATE_VOID_NOTE = 'duplicate void';

const NET_EX = '(s.subtotal - s.discount - CASE WHEN s.prices_incl_tax = 1 THEN s.tax ELSE 0 END)';

/** Day totals, filtered by `WHERE ...` [where] on sale `s` (DerivedRebuild.summaries). */
const SUM_DAY = (where) =>
  'INSERT INTO sum_day(day, sale_count, refund_count, void_count, gross, discount, net_ex, tax, rounding, ' +
  'total, cost, refund_total, items) SELECT s.day, ' +
  `SUM(CASE WHEN s.status = ${OK} AND s.kind = ${SALE} THEN 1 ELSE 0 END), ` +
  `SUM(CASE WHEN s.status = ${OK} AND s.kind = ${REFUND} THEN 1 ELSE 0 END), ` +
  `SUM(CASE WHEN s.status = ${VOIDED} THEN 1 ELSE 0 END), ` +
  `SUM(CASE WHEN s.status = ${OK} THEN s.subtotal ELSE 0 END), ` +
  `SUM(CASE WHEN s.status = ${OK} THEN s.discount ELSE 0 END), ` +
  `SUM(CASE WHEN s.status = ${OK} THEN ${NET_EX} ELSE 0 END), ` +
  `SUM(CASE WHEN s.status = ${OK} THEN s.tax ELSE 0 END), ` +
  `SUM(CASE WHEN s.status = ${OK} THEN s.rounding ELSE 0 END), ` +
  `SUM(CASE WHEN s.status = ${OK} THEN s.total ELSE 0 END), ` +
  `SUM(CASE WHEN s.status = ${OK} THEN s.cost ELSE 0 END), ` +
  `SUM(CASE WHEN s.status = ${OK} AND s.kind = ${REFUND} THEN -s.total ELSE 0 END), ` +
  `SUM(CASE WHEN s.status = ${OK} AND s.kind = ${SALE} THEN s.line_count ELSE 0 END) ` +
  `FROM sale s ${where} GROUP BY s.day`;

const SUM_DAY_PRODUCT = (where) =>
  'INSERT INTO sum_day_product(day, product_id, category_id, qty, net_ex, tax, cost) ' +
  'SELECT s.day, COALESCE(l.product_id, 0), MIN(l.category_id), SUM(l.base_qty), ' +
  'SUM(l.net - CASE WHEN s.prices_incl_tax = 1 THEN l.tax ELSE 0 END), SUM(l.tax), SUM(l.cost) ' +
  `FROM sale_line l JOIN sale s ON s.id = l.sale_id WHERE s.status = ${OK} ${where} ` +
  'GROUP BY s.day, COALESCE(l.product_id, 0)';

const SUM_DAY_PAYMENT = (where) =>
  'INSERT INTO sum_day_payment(day, method_id, kind, amount, count) ' +
  'SELECT s.day, p.method_id, MIN(p.kind), SUM(p.amount), COUNT(DISTINCT p.sale_id) ' +
  `FROM payment p JOIN sale s ON s.id = p.sale_id WHERE s.status = ${OK} ${where} GROUP BY s.day, p.method_id`;

const SUM_DAY_STAFF = (where) =>
  'INSERT INTO sum_day_staff(day, staff_id, sale_count, total, net_ex) ' +
  `SELECT s.day, COALESCE(s.staff_id, 0), SUM(CASE WHEN s.kind = ${SALE} THEN 1 ELSE 0 END), ` +
  `SUM(s.total), SUM(${NET_EX}) FROM sale s WHERE s.status = ${OK} ${where} GROUP BY s.day, COALESCE(s.staff_id, 0)`;

const DAY_TABLES = ['sum_day', 'sum_day_product', 'sum_day_payment', 'sum_day_staff'];

// SaleDao / DerivedRebuild statements.
const STATUS_VOIDED = `UPDATE sale SET status = ${VOIDED} WHERE id IN (SELECT sale_id FROM sale_void) AND status != ${VOIDED}`;
const STATUS_COMPLETED = `UPDATE sale SET status = ${OK} WHERE status = ${VOIDED} AND id NOT IN (SELECT sale_id FROM sale_void)`;
const REFUNDED_TOTAL =
  'SELECT SUM(total) FROM sale WHERE ref_sale_id = ? AND ref_sale_id IS NOT NULL ' +
  `AND kind = ${REFUND} AND status = ${OK}`;
const REFUND_TOTALS =
  'SELECT ref_sale_id, -SUM(total) FROM sale WHERE ref_sale_id IS NOT NULL ' +
  `AND kind = ${REFUND} AND status = ${OK} GROUP BY ref_sale_id`;
const BALANCES =
  `INSERT INTO customer_balance(customer_id, balance) SELECT customer_id, SUM(CASE kind WHEN ${CREDIT_PAYMENT} ` +
  'THEN -amount ELSE amount END) FROM credit_entry GROUP BY customer_id';

// StockDao statements.
const LAST_COUNT = 'SELECT qty, hlc, id FROM stock_count WHERE product_id = ? ORDER BY hlc DESC, (id >> 41) DESC LIMIT 1';
const MOVES_AFTER = 'SELECT SUM(qty) FROM stock_movement WHERE product_id = ? AND hlc >= ? AND (hlc > ? OR (id >> 41) > ?)';
const SALES_AFTER =
  'SELECT SUM(l.stock_qty) FROM sale_line l JOIN sale s ON s.id = l.sale_id ' +
  'WHERE l.product_id = ? AND l.hlc >= ? AND (l.hlc > ? OR (l.id >> 41) > ?) ' +
  `AND s.status = ${OK}`;
const COUNT_BEFORE =
  'SELECT qty, hlc, id FROM stock_count WHERE product_id = ? AND hlc <= ? AND (hlc < ? OR id < ?) ' +
  'ORDER BY hlc DESC, id DESC LIMIT 1';
const MOVES_BETWEEN =
  'SELECT SUM(qty) FROM stock_movement WHERE product_id = ? AND hlc >= ? AND hlc <= ? ' +
  'AND (hlc > ? OR (id >> 41) > ?) AND (hlc < ? OR (id >> 41) < ?)';
const SALES_BETWEEN =
  'SELECT SUM(l.stock_qty) FROM sale_line l JOIN sale s ON s.id = l.sale_id ' +
  'WHERE l.product_id = ? AND l.hlc >= ? AND l.hlc <= ? AND (l.hlc > ? OR (l.id >> 41) > ?) ' +
  `AND (l.hlc < ? OR (l.id >> 41) < ?) AND s.status = ${OK}`;
const NEVER_COUNTED =
  'INSERT INTO stock_level(product_id, qty, count_hlc, count_dev) SELECT product_id, SUM(q), 0, 0 FROM (' +
  'SELECT product_id, qty AS q FROM stock_movement ' +
  'UNION ALL SELECT l.product_id, l.stock_qty FROM sale_line l JOIN sale s ON s.id = l.sale_id ' +
  `WHERE s.status = ${OK} AND l.product_id IS NOT NULL AND l.stock_qty != 0) ` +
  'WHERE product_id NOT IN (SELECT product_id FROM stock_count) GROUP BY product_id';

/** Above these, a whole-table rebuild is cheaper than one product, day or sale at a time. */
const MANY_DAYS = 400;
const MANY_PRODUCTS = 2000;
const MANY_SALES = 5000;
const CHUNK = 500;

const big = (v) => (v == null ? null : BigInt(v));
const devOf = (id) => BigInt(id) >> SEQ_BITS;

function chunks(values) {
  const out = [];
  for (let i = 0; i < values.length; i += CHUNK) out.push(values.slice(i, i + CHUNK));
  return out;
}

const marks = (n) => new Array(n).fill('?').join(',');

/** Brings every derived table up to date with what [t] (a Touched) says changed. Runs in one transaction. */
export function applyDerived(db, t) {
  const one = (sql, bind) => db.selectArray(sql, bind);
  const value = (sql, bind) => one(sql, bind)?.[0] ?? null;

  // 1. Status: voided exactly when a void of the sale is stored; then what a void touches.
  if (t.voided.size > 0) {
    db.exec(STATUS_VOIDED);
    db.exec(STATUS_COMPLETED);
    for (const id of t.voided) {
      const s = one('SELECT day, kind, ref_sale_id FROM sale WHERE id = ?', [id]);
      if (!s) continue; // the sale has not arrived yet
      t.days.add(Number(s[0]));
      if (Number(s[1]) === REFUND && s[2] != null) t.refunded.add(big(s[2]));
      for (const r of db.selectArrays(
        'SELECT DISTINCT product_id FROM sale_line WHERE sale_id = ? AND product_id IS NOT NULL AND stock_qty != 0', [id])) {
        t.stock.add(big(r[0]));
      }
      creditOnce(db, id, t);
    }
  }

  // 2. What each sale has had refunded (completed refunds only).
  if (t.refunded.size > MANY_SALES) {
    db.exec('UPDATE sale SET refunded = 0 WHERE refunded != 0');
    for (const [saleId, amount] of db.selectArrays(REFUND_TOTALS)) {
      db.exec({ sql: 'UPDATE sale SET refunded = ? WHERE id = ?', bind: [amount, saleId] });
    }
  } else {
    for (const id of t.refunded) {
      const refunded = -BigInt(value(REFUNDED_TOTAL, [id]) ?? 0);
      db.exec({ sql: 'UPDATE sale SET refunded = ? WHERE id = ? AND refunded != ?', bind: [refunded, id, refunded] });
    }
  }

  // 3. Stock levels: last count + everything after it (StockDao.rebuild).
  if (t.stock.size > MANY_PRODUCTS) {
    db.exec('DELETE FROM stock_level');
    db.exec(NEVER_COUNTED);
    for (const [pid] of db.selectArrays('SELECT DISTINCT product_id FROM stock_count')) rebuildStock(db, big(pid));
  } else {
    for (const pid of t.stock) rebuildStock(db, pid);
  }

  // 4. What each count of those products expected: the level just before it (StockDao.levelBefore).
  const countProducts = new Set([...t.counts, ...t.stock]);
  for (const part of chunks([...countProducts])) {
    const counted = db.selectArrays(`SELECT DISTINCT product_id FROM stock_count WHERE product_id IN (${marks(part.length)})`, part);
    for (const [pid] of counted) {
      for (const [id, hlc] of db.selectArrays('SELECT id, hlc FROM stock_count WHERE product_id = ? ORDER BY hlc, id', [pid])) {
        const expected = levelBefore(db, big(pid), big(hlc), devOf(id));
        db.exec({ sql: 'UPDATE stock_count SET expected = ? WHERE id = ? AND (expected IS NULL OR expected != ?)', bind: [expected, id, expected] });
      }
    }
  }

  // 5. Totals per day.
  if (t.days.size > MANY_DAYS) {
    for (const table of DAY_TABLES) db.exec(`DELETE FROM ${table}`);
    db.exec(SUM_DAY('WHERE 1'));
    db.exec(SUM_DAY_PRODUCT(''));
    db.exec(SUM_DAY_PAYMENT(''));
    db.exec(SUM_DAY_STAFF(''));
  } else {
    for (const part of chunks([...t.days])) {
      const inDays = `IN (${marks(part.length)})`;
      for (const table of DAY_TABLES) db.exec({ sql: `DELETE FROM ${table} WHERE day ${inDays}`, bind: part });
      db.exec({ sql: SUM_DAY(`WHERE s.day ${inDays}`), bind: part });
      db.exec({ sql: SUM_DAY_PRODUCT(`AND s.day ${inDays}`), bind: part });
      db.exec({ sql: SUM_DAY_PAYMENT(`AND s.day ${inDays}`), bind: part });
      db.exec({ sql: SUM_DAY_STAFF(`AND s.day ${inDays}`), bind: part });
    }
  }

  // 6. Customer balances.
  if (t.balances) {
    db.exec('DELETE FROM customer_balance');
    db.exec(BALANCES);
  }
}

/** The cached level of one product from its events (StockDao.rebuild). */
export function rebuildStock(db, pid) {
  const count = db.selectArray(LAST_COUNT, [pid]);
  const base = count ? BigInt(count[0]) : 0n;
  const cHlc = count ? BigInt(count[1]) : 0n;
  const cDev = count ? devOf(count[2]) : 0n;
  const moves = BigInt(db.selectValue(MOVES_AFTER, [pid, cHlc, cHlc, cDev]) ?? 0);
  const sales = BigInt(db.selectValue(SALES_AFTER, [pid, cHlc, cHlc, cDev]) ?? 0);
  db.exec({
    sql: 'INSERT OR REPLACE INTO stock_level(product_id, qty, count_hlc, count_dev) VALUES(?, ?, ?, ?)',
    bind: [pid, base + moves + sales, cHlc, cDev],
  });
}

/** The stock of [pid] just before its count stamped ([hlc], [dev]) (StockDao.levelBefore). */
export function levelBefore(db, pid, hlc, dev) {
  const prev = db.selectArray(COUNT_BEFORE, [pid, hlc, hlc, dev << SEQ_BITS]);
  const base = prev ? BigInt(prev[0]) : 0n;
  const fromHlc = prev ? BigInt(prev[1]) : 0n;
  const fromDev = prev ? devOf(prev[2]) : -1n;
  const moves = BigInt(db.selectValue(MOVES_BETWEEN, [pid, fromHlc, hlc, fromHlc, fromDev, hlc, dev]) ?? 0);
  const sales = BigInt(db.selectValue(SALES_BETWEEN, [pid, fromHlc, hlc, fromHlc, fromDev, hlc, dev]) ?? 0);
  return base + moves + sales;
}

/**
 * The same sale voided on two tills: the first void (by hlc, till, id) counts, every later one gets a
 * charge with the fixed id −(void id) that cancels its credit reversal (SaleDao.creditOnce).
 */
function creditOnce(db, saleId, t) {
  const voids = db.selectArrays('SELECT id, hlc, shift_id, at FROM sale_void WHERE sale_id = ?', [saleId])
    .map((r) => ({ id: BigInt(r[0]), hlc: BigInt(r[1]), shift: r[2] == null ? null : BigInt(r[2]), at: r[3] }));
  if (voids.length < 2) return;
  const customer = db.selectArray('SELECT customer_id FROM sale WHERE id = ?', [saleId])?.[0];
  if (customer == null) return;
  const credit = db.selectArrays(`SELECT method_id, amount FROM payment WHERE sale_id = ? AND kind = ${PAYMENT_CREDIT} ORDER BY id`, [saleId])
    .filter((r) => BigInt(r[1]) !== 0n);
  const amount = credit.reduce((a, r) => a + BigInt(r[1]), 0n);
  if (amount === 0n) return;
  const cmp = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
  voids.sort((a, b) => cmp(a.hlc, b.hlc) || cmp(devOf(a.id), devOf(b.id)) || cmp(a.id, b.id));
  for (const v of voids.slice(1)) {
    db.exec({
      sql: 'INSERT OR IGNORE INTO credit_entry(id, customer_id, kind, amount, sale_id, method_id, staff_id, ' +
        'note, at, hlc, shift_id) VALUES(?,?,?,?,?,?,NULL,?,?,?,?)',
      bind: [-v.id, customer, CREDIT_CHARGE, amount, saleId, credit[0][0], DUPLICATE_VOID_NOTE, v.at, v.hlc, v.shift],
    });
    if (db.changes() > 0) t.balances = true;
  }
}

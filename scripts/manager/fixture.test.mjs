// The real check of the manager web page (D-082): the sync folder and backup files that two tills made with
// the app's own code (app/src/androidTest/.../sync/ManagerFixtureTest.kt, pulled by scripts/ci/instrumented.sh)
// are read here. The web page's copy, read from the folder as from Google Drive, must hold exactly the tills'
// data — every synced row, sale status, refunded amounts, stock, count expectations, balances, day totals —
// and give the same reports and CSV files as each till's own database (opened from its backup file, the
// web page's other way in). Runs in CI with FIXTURE_DIR set; skipped elsewhere.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { memoryDb, dbFromBytes, DiskFolder } from './helpers.mjs';
import { Store } from '../../site/manager/js/store.js';
import { readBackup } from '../../site/manager/js/backup.js';
import * as R from '../../site/manager/js/reports.js';
import * as D from '../../site/manager/js/days.js';

const dir = process.env.FIXTURE_DIR;

// Schema.LWW_TABLES and Schema.EVENT_TABLES
const LWW = ['setting', 'role', 'staff', 'tax_rate', 'category', 'product', 'product_barcode', 'supplier', 'customer',
  'payment_method', 'shift', 'count_session', 'promotion', 'product_look', 'promotion_price'];
const EVENT = ['cash_movement', 'sale', 'sale_line', 'payment', 'sale_void', 'stock_movement', 'stock_count', 'purchase',
  'purchase_line', 'credit_entry', 'audit_log', 'product_image'];
const SUMS = {
  sum_day: ['day', 'sale_count, refund_count, void_count, gross, discount, net_ex, tax, rounding, total, cost, refund_total, items'],
  sum_day_product: ['day, product_id', 'qty, net_ex, tax, cost'],
  sum_day_payment: ['day, method_id', 'amount, count'],
  sum_day_staff: ['day, staff_id', 'sale_count, total, net_ex'],
};

const text = (v) => (v == null ? '∅' : String(v));

/** What SyncMergeTest compares between tills, for the tables the web page keeps. */
function content(db) {
  const out = {};
  const rows = (sql) => db.selectArrays(sql).map((r) => r.map(text).join('|'));
  for (const t of [...LWW, ...EVENT]) {
    const cols = db.selectArrays(`PRAGMA table_info(${t})`).map((r) => r[1])
      .filter((c) => !['ver_hlc', 'ver_dev', 'fver', 'updated_at', 'created_at'].includes(c));
    out[t] = rows(`SELECT ${cols.join(', ')} FROM ${t} ORDER BY ${t === 'setting' ? 'key' : 'id'}`);
  }
  out.stock_level = rows('SELECT product_id, qty FROM stock_level WHERE qty != 0 ORDER BY product_id');
  out.customer_balance = rows('SELECT customer_id, balance FROM customer_balance WHERE balance != 0 ORDER BY customer_id');
  for (const [t, [keys, totals]] of Object.entries(SUMS)) {
    const n = keys.split(', ').length;
    out[t] = rows(`SELECT ${keys}, ${totals} FROM ${t} ORDER BY ${keys}`)
      .filter((r) => !r.split('|').slice(n).every((v) => v === '0' || v === '∅'));
  }
  return out;
}

const json = (v) => JSON.stringify(v, (_, x) => (typeof x === 'bigint' ? String(x) : x));

function readFolder() {
  const files = readdirSync(dir)
    .filter((n) => !n.endsWith('.props') && !n.endsWith('.lekasbak'))
    .map((name) => {
      const p = join(dir, `${name}.props`);
      const props = {};
      if (existsSync(p)) {
        for (const line of readFileSync(p, 'utf8').split('\n')) {
          const at = line.indexOf('=');
          if (at > 0) props[line.slice(0, at)] = line.slice(at + 1);
        }
      }
      return { name, bytes: new Uint8Array(readFileSync(join(dir, name))), props };
    });
  return new DiskFolder(dir, files);
}

test('the web page holds the tills\' data and gives their reports (files made by the app)', { skip: dir ? false : 'FIXTURE_DIR is not set: the files are made by ManagerFixtureTest in CI' }, async () => {
  const folder = readFolder();
  assert.ok(folder.files.some((f) => f.name.startsWith('seg-')), `no sync files in ${dir}`);
  const web = await memoryDb();
  const store = new Store(web);
  store.createForDrive();
  const r = await store.sync(folder);
  assert.equal(r.deferred, 0, 'an event of the app was set aside');
  assert.equal(r.tills, 2);
  assert.equal((await store.sync(folder)).files, 0, 'a second round read files again');
  const mine = content(web);

  const [from, to] = web.selectArray('SELECT MIN(day), MAX(day) + 1 FROM sale').map(Number);
  const p = new D.Period(from - 1, to + 1);
  const views = (db) => ({
    report: R.report(db, p, p.previous(), 100),
    stock: R.stockValue(db),
    slow: R.slowMovers(db, p),
    staff: R.staffCheck(db, p),
    csv: ['summary', 'daily', 'products', 'receipts'].map((w) => R.exportCsv(db, w, p, 2).text),
    low: R.lowStockCount(db),
    sales: R.sales(db, { limit: 500 }),
    customers: R.customers(db).map((c) => R.customer(db, c.id)),
  });
  const webViews = json(views(web));

  for (const name of ['till-a.lekasbak', 'till-b.lekasbak']) {
    const { db: bytes, header } = await readBackup(new Blob([readFileSync(join(dir, name))]));
    assert.equal(header.format >= 1, true);
    const till = await dbFromBytes(bytes);
    assert.equal(till.selectValue('PRAGMA integrity_check'), 'ok');
    const theirs = content(till);
    for (const t of Object.keys(theirs)) assert.deepEqual(mine[t], theirs[t], `${name}: ${t} differs from the web page's`);
    assert.equal(webViews, json(views(till)), `${name}: the reports differ`);
    // Opened as the web page opens a backup file, the till's own database reads the same too.
    new Store(till).adoptBackup(header);
    assert.equal(json(views(till)), webViews);
  }
  // The shop did what the fixture says: a refund, voids (one sale on both tills), tax, a delivery, a count.
  assert.ok(Number(web.selectValue('SELECT COUNT(*) FROM sale WHERE kind = 1')) >= 1);
  assert.ok(Number(web.selectValue('SELECT COUNT(*) FROM sale_void')) >= 3);
  assert.ok(Number(web.selectValue('SELECT COUNT(*) FROM credit_entry WHERE id < 0')) === 1, 'the second void of the credit sale');
  assert.ok(Number(web.selectValue('SELECT SUM(tax) FROM sale')) > 0);
  assert.ok(Number(web.selectValue('SELECT COUNT(*) FROM purchase')) === 1);
  assert.ok(Number(web.selectValue('SELECT COUNT(*) FROM stock_count WHERE session_id IS NOT NULL')) === 1);
});

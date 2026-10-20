// The manager web page reads two tills' sync files into the same data the tills hold: LWW merges,
// sales, refunds, voids (also before their sale), stock, deliveries, totals per day and the reports.
// The real check against the app's own databases is fixture.test.mjs (files made by the app in CI).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { memoryDb, MemoryFolder, idOf, hlcOf } from './helpers.mjs';
import { Store, SyncError } from '../../site/manager/js/store.js';
import * as R from '../../site/manager/js/reports.js';
import * as D from '../../site/manager/js/days.js';

const STORE = 'st0re-uuid';
const A = 3_000_001; // till numbers are 22-bit random: ids beyond 2^53
const B = 4_100_007;
const DAY = D.fromYmd(20261019);
const T0 = Date.UTC(2026, 9, 19, 2, 0); // 10:00 in Malaysia
const h = (n) => hlcOf(T0 + n * 1000);

const lww = (entity, id, hlc, dev, f) => ({ e: entity, o: 1, r: id, h: hlc, p: { id, hlc, dev: BigInt(dev), f } });
const setting = (key, value, hlc, dev) => ({ e: 1, o: 1, h: hlc, p: { key, value, hlc, dev: BigInt(dev) } });

function saleEvent({ id, kind = 0n, receipt, dev, day = DAY, at, hlc, lines, pays, ref = null, staff = 1n }) {
  const subtotal = lines.reduce((a, l) => a + l.net, 0n);
  const cost = lines.reduce((a, l) => a + l.cost, 0n);
  const sale = {
    id, kind, receipt_no: receipt, device_no: BigInt(dev), doc_seq: 1n, ref_sale_id: ref, shift_id: null, staff_id: staff,
    customer_id: null, opened_at: BigInt(at - 30000), sold_at: BigInt(at), day: BigInt(day), line_count: BigInt(lines.length),
    subtotal, discount: 0n, tax: 0n, rounding: 0n, total: subtotal, paid: subtotal, change_due: 0n, cost,
    prices_incl_tax: 1n, status: 0n, refunded: 0n, note: null, hlc,
  };
  const ls = lines.map((l, i) => ({
    id: l.id, sale_id: id, line_no: BigInt(i + 1), product_id: l.product, ref_line_id: null, name: l.name, barcode: null,
    unit: 'pcs', category_id: l.category ?? null, qty: l.qty, base_qty: l.qty, unit_price: l.price, gross: l.net, discount: 0n,
    bill_discount: 0n, net: l.net, tax_rate_id: null, tax_bp: 0n, tax: 0n, cost: l.cost, price_overridden: 0n,
    stock_qty: -l.qty, hlc, promo_id: null, promo_name: null,
  }));
  const ps = pays.map((p, i) => ({
    id: p.id, sale_id: id, method_id: p.method, kind: p.kind, amount: p.amount, tendered: p.amount, change_given: 0n,
    ref: null, shift_id: null, paid_at: BigInt(at),
  }));
  return { e: 20, o: 2, r: id, h: hlc, p: { sale, lines: ls, pays: ps } };
}

const C1 = idOf(A, 1);
const P1 = idOf(A, 2);
const P2 = idOf(A, 3);
const P3 = idOf(A, 50);
const S1 = idOf(A, 10);
const S2 = idOf(A, 11);
const R1 = idOf(A, 12);
const S4 = idOf(A, 60);

function tillA() {
  const at = T0 + 3_600_000;
  return [
    setting('store.name', 'Kedai Ali', h(1), A),
    lww(10, 1n, 0n, 0, { name: 'Cash', kind: 1n, opens_drawer: 1n, sort: 0n, active: 1n, deleted: 0n, created_at: 1n }),
    lww(10, 2n, 0n, 0, { name: 'Card', kind: 2n, opens_drawer: 0n, sort: 1n, active: 1n, deleted: 0n, created_at: 1n }),
    lww(3, 1n, 0n, 0, { name: 'Owner', role_id: 1n, active: 1n, deleted: 0n, created_at: 1n }),
    lww(5, C1, h(2), A, { name: 'Drinks', name_key: 'drinks', created_at: BigInt(T0) }),
    lww(6, P1, h(3), A, { name: 'Milo', name_key: 'milo', category_id: C1, price: 1890n, cost: 1200n, track_stock: 1n, low_stock: 9000n, created_at: BigInt(T0) }),
    lww(6, P2, h(4), A, { name: 'Roti', name_key: 'roti', price: 350n, cost: 200n, created_at: BigInt(T0) }),
    { e: 23, h: h(10), p: { id: idOf(A, 4), product_id: P1, qty: 10000n, session_id: null, staff_id: 1n, note: null, at: BigInt(T0), hlc: h(10), expected: 0n, unit_cost: 1200n } },
    saleEvent({
      id: S1, receipt: 'AA-000001', dev: A, at, hlc: h(20),
      lines: [
        { id: idOf(A, 20), product: P1, name: 'Milo', category: C1, qty: 2000n, price: 1890n, net: 3780n, cost: 2400n },
        { id: idOf(A, 21), product: P2, name: 'Roti', qty: 1000n, price: 350n, net: 350n, cost: 200n },
      ],
      pays: [{ id: idOf(A, 22), method: 1n, kind: 1n, amount: 4130n }],
    }),
    saleEvent({
      id: S2, receipt: 'AA-000002', dev: A, at: at + 60000, hlc: h(30),
      lines: [{ id: idOf(A, 30), product: P1, name: 'Milo', category: C1, qty: 1000n, price: 1890n, net: 1890n, cost: 1200n }],
      pays: [{ id: idOf(A, 31), method: 2n, kind: 2n, amount: 1890n }],
    }),
    saleEvent({
      id: R1, kind: 1n, ref: S1, receipt: 'AA-R000001', dev: A, at: at + 120000, hlc: h(40),
      lines: [{ id: idOf(A, 40), product: P1, name: 'Milo', category: C1, qty: -1000n, price: 1890n, net: -1890n, cost: -1200n }],
      pays: [{ id: idOf(A, 41), method: 1n, kind: 1n, amount: -1890n }],
    }),
    { e: 27, h: h(45), p: { id: idOf(A, 45), action: 30n, staff_id: 1n, approved_by: null, entity: null, entity_id: null, amount: 350n, detail: 'Roti', at: BigInt(at), hlc: h(45) } },
  ];
}

function tillB() {
  const at = T0 + 86_400_000;
  return [
    lww(6, P1, h(50), B, { price: 1990n }), // B changed Milo's price after A made it
    lww(6, P3, h(51), B, { price: 999n }), // an edit of a product B heard of from a third till: before its creation
    { e: 21, h: h(60), p: { id: idOf(B, 1), sale_id: S2, reason: 'wrong item', staff_id: 1n, approved_by: null, shift_id: null, at: BigInt(at), hlc: h(60) } },
    { e: 21, h: h(61), p: { id: idOf(B, 6), sale_id: S4, reason: 'test', staff_id: 1n, approved_by: null, shift_id: null, at: BigInt(at), hlc: h(61) } },
    saleEvent({
      id: idOf(B, 2), receipt: 'BB-000001', dev: B, day: DAY + 1, at, hlc: h(70),
      lines: [{ id: idOf(B, 7), product: P1, name: 'Milo', category: C1, qty: 1000n, price: 1990n, net: 1990n, cost: 1200n }],
      pays: [{ id: idOf(B, 8), method: 2n, kind: 2n, amount: 1990n }],
    }),
    { e: 22, h: h(80), p: { id: idOf(B, 3), product_id: P2, kind: 2n, qty: 5000n, unit_cost: null, ref_id: null, reason: 'found', staff_id: 1n, at: BigInt(at), hlc: h(80) } },
    { e: 24, h: h(90), p: {
      purchase: { id: idOf(B, 4), supplier_id: null, ref_no: 'DO-1', total: 1800n, note: null, staff_id: 1n, at: BigInt(at), hlc: h(90) },
      lines: [{ id: idOf(B, 5), purchase_id: idOf(B, 4), product_id: P2, qty: 10000n, unit_cost: 180n, total: 1800n }],
    } },
    { e: 99, h: h(95), p: { something: 'from a newer app' } },
  ];
}

async function synced() {
  const folder = new MemoryFolder();
  folder.putJson(`store-${STORE}.json`, { store: STORE, createdAt: BigInt(T0), createdBy: BigInt(A) });
  folder.putJson(`dev-${STORE}-${A}.json`, { dev: BigInt(A), uuid: 'a', name: 'Counter A', app: '1.20.1', lastSeen: BigInt(T0), lastSeq: 1n, prefix: 'AA-' });
  folder.putJson(`dev-${STORE}-${B}.json`, { dev: BigInt(B), uuid: 'b', name: 'Counter B', app: '1.20.1', lastSeen: BigInt(T0), lastSeq: 1n, prefix: 'BB-' });
  folder.putSegment(STORE, A, 1, tillA());
  folder.putSegment(STORE, B, 1, tillB());
  const db = await memoryDb();
  const store = new Store(db);
  assert.equal(store.check(), 'empty');
  store.createForDrive();
  assert.equal(store.check(), 'ok');
  const result = await store.sync(folder, () => {}, T0 + 2 * 86_400_000);
  return { folder, db, store, result };
}

const p2 = new D.Period(DAY, DAY + 2);

test('two tills: data, derived tables and reports', async () => {
  const { db, result, store } = await synced();
  assert.equal(result.files, 2);
  assert.equal(result.deferred, 1);
  assert.equal(result.tills, 2);
  assert.equal(store.meta('web.store'), STORE);
  assert.deepEqual(JSON.parse(store.meta('web.cards')).map((c) => c.name), ['Counter A', 'Counter B']);

  // LWW: B's later price wins, A's other fields stay; an edit before its creation keeps a placeholder.
  assert.deepEqual(db.selectArray('SELECT name, price, cost, category_id FROM product WHERE id = ?', [P1]), ['Milo', 1990, 1200, C1]);
  assert.match(db.selectValue('SELECT fver FROM product WHERE id = ?', [P1]), new RegExp(`^price:\\d+:${B}$`));
  assert.deepEqual(db.selectArray('SELECT name, price, ver_hlc, fver IS NOT NULL FROM product WHERE id = ?', [P3]), ['', 999, 0, 1]);
  assert.equal(db.selectValue("SELECT value FROM setting WHERE key = 'store.name'"), 'Kedai Ali');

  // Sales: S2 voided by B; S1 refunded 18.90; a void whose sale has not arrived waits.
  assert.equal(db.selectValue('SELECT status FROM sale WHERE id = ?', [S2]), 1);
  assert.equal(db.selectValue('SELECT refunded FROM sale WHERE id = ?', [S1]), 1890);
  assert.equal(db.selectValue('SELECT COUNT(*) FROM sale_void WHERE sale_id = ?', [S4]), 1);

  // Stock: Milo counted 10 → −2 (S1) +1 (refund) −1 (B's sale); S2 voided. Roti: −1 +5 +10.
  assert.equal(db.selectValue('SELECT qty FROM stock_level WHERE product_id = ?', [P1]), 8000);
  assert.equal(db.selectValue('SELECT qty FROM stock_level WHERE product_id = ?', [P2]), 14000);
  assert.equal(db.selectValue('SELECT COUNT(*) FROM stock_movement WHERE kind = 1'), 1); // the delivery's RECEIVE

  // Day totals.
  assert.deepEqual(db.selectArray('SELECT sale_count, refund_count, void_count, gross, net_ex, total, cost, refund_total, items FROM sum_day WHERE day = ?', [DAY]),
    [1, 1, 1, 2240, 2240, 2240, 1400, 1890, 2]);
  assert.deepEqual(db.selectArray('SELECT sale_count, total, cost FROM sum_day WHERE day = ?', [DAY + 1]), [1, 1990, 1200]);

  // The report.
  const rep = R.report(db, p2, p2.previous());
  assert.equal(rep.totals.saleCount, 2);
  assert.equal(rep.totals.total + rep.totals.refundTotal, 6120); // "Sales (2)": takings before refunds
  assert.equal(rep.totals.grossProfit, 4230 - 2600);
  assert.deepEqual(rep.payments.map((x) => [x.name, x.amount, x.count]), [['Cash', 2240, 2], ['Card', 1990, 1]]);
  assert.deepEqual(rep.categories.map((x) => [x.name, x.netEx, x.qty]), [['Drinks', 3880, 2000], [null, 350, 1000]]);
  assert.deepEqual(rep.topProducts.map((x) => [x.name, x.netEx]), [['Milo', 3880], ['Roti', 350]]);
  assert.deepEqual(rep.staff.map((x) => [x.name, x.saleCount, x.total]), [['Owner', 2, 4230]]);
  assert.equal(rep.buckets.length, 2);
  assert.equal(R.lowStockCount(db), 1); // Milo: 8 left, alert at 9

  // Lists.
  const list = R.sales(db, { limit: 10 });
  assert.equal(list[0].receipt_no, 'BB-000001');
  assert.equal(R.sales(db, { receipt: 'R000001' })[0].id, String(R1));
  const s1 = R.sale(db, String(S1));
  assert.equal(s1.lines.length, 2);
  assert.equal(s1.refunds[0].receipt_no, 'AA-R000001');
  assert.equal(s1.till, A);
  assert.equal(R.productList(db, { search: 'mil' })[0].name, 'Milo');
  assert.equal(R.productList(db, { filter: 'low' }).length, 1);
  // Stock changes: the delivery, the change by hand and the count outside a count session (StockDao.movementPage).
  assert.deepEqual(R.movements(db).map((m) => m.kind), [1, 2, R.KIND_HAND_COUNT]);
  assert.equal(R.purchases(db)[0].ref_no, 'DO-1');
  assert.equal(R.audit(db)[0].detail, 'Roti');
  const check = R.staffCheck(db, p2);
  assert.equal(check[0].checks.afterPay.amount, 350);

  // CSV like the app's.
  const daily = R.exportCsv(db, 'daily', p2, 2);
  assert.equal(daily.rows, 2);
  assert.ok(daily.text.startsWith('﻿date,sales,refunds,'));
  assert.ok(daily.text.includes('\r\n2026-10-19,1,1,22.40,0.00,22.40,0.00,14.00,8.40,0,0.00,1,3.50,1,3.50,0,0.00\r\n'));
  const receipts = R.exportCsv(db, 'receipts', p2, 2);
  assert.equal(receipts.rows, 4);
  assert.ok(receipts.text.includes('AA-000002,2026-10-19,'));
  assert.ok(receipts.text.includes(',sale,voided,'));
});

test('a void before its sale, a creation after its edit, and reading everything twice', async () => {
  const { folder, db, store } = await synced();
  const at = T0 + 7_200_000;
  folder.putSegment(STORE, A, 2, [
    lww(6, P3, h(40), A, { name: 'Kopi', name_key: 'kopi', price: 500n, cost: 300n, created_at: BigInt(T0) }),
    saleEvent({
      id: S4, receipt: 'AA-000003', dev: A, at, hlc: h(41),
      lines: [{ id: idOf(A, 61), product: P2, name: 'Roti', qty: 2000n, price: 350n, net: 700n, cost: 400n }],
      pays: [{ id: idOf(A, 62), method: 1n, kind: 1n, amount: 700n }],
    }),
  ]);
  const r = await store.sync(folder, () => {}, T0 + 2 * 86_400_000 + 1000);
  assert.equal(r.files, 1);
  // The creation is older than B's price edit: B's price stays, the creation fills in the rest.
  assert.deepEqual(db.selectArray('SELECT name, price, cost FROM product WHERE id = ?', [P3]), ['Kopi', 999, 300]);
  // The sale arrived after B's void of it: voided at once, no stock or totals.
  assert.equal(db.selectValue('SELECT status FROM sale WHERE id = ?', [S4]), 1);
  assert.equal(db.selectValue('SELECT qty FROM stock_level WHERE product_id = ?', [P2]), 14000);
  assert.equal(db.selectValue('SELECT void_count FROM sum_day WHERE day = ?', [DAY]), 2);

  const snapshot = () => ['sale', 'sum_day', 'sum_day_product', 'sum_day_payment', 'sum_day_staff', 'stock_level', 'product']
    .map((t) => JSON.stringify(db.selectArrays(`SELECT * FROM ${t} ORDER BY 1, 2`), (_, v) => (typeof v === 'bigint' ? String(v) : v)).replace(/"updated_at":\d+/g, ''));
  const before = snapshot();
  // Nothing new: nothing downloaded.
  const gets = folder.gets;
  assert.equal((await store.sync(folder, () => {}, T0 + 2 * 86_400_000 + 2000)).files, 0);
  assert.equal(folder.gets - gets, 2); // the two tills' cards
  // Every file read again (cursors forgotten): the same data.
  db.exec('DELETE FROM sync_cursor');
  store.setMeta('web.list_since', null);
  assert.equal((await store.sync(folder, () => {}, T0 + 2 * 86_400_000 + 3000)).files, 3);
  const after = snapshot().map((s) => s.replace(/,\d{13}\]/g, ']'));
  assert.deepEqual(after, before.map((s) => s.replace(/,\d{13}\]/g, ']')));
});

test('a damaged file stops the round; a folder without a shop says so', async () => {
  const empty = new MemoryFolder();
  const db = await memoryDb();
  const store = new Store(db);
  store.createForDrive();
  await assert.rejects(store.sync(empty), (e) => e instanceof SyncError && e.code === 'NO_STORE');

  const folder = new MemoryFolder();
  folder.putJson(`store-${STORE}.json`, { store: STORE });
  folder.putSegment(STORE, A, 1, tillA());
  folder.files[1].props.sha256 = '0'.repeat(64);
  await assert.rejects(store.sync(folder), /checksum/);
  assert.equal(db.selectValue('SELECT COUNT(*) FROM sale'), 0);
});

test('customers: balances from credit entries, a statement with the balance after each entry', async () => {
  const { folder, db, store } = await synced();
  const SITI = idOf(A, 70);
  const credit = (n, kind, amount) => ({ e: 26, h: h(100 + n), p: { id: idOf(A, 71 + n), customer_id: SITI, kind: BigInt(kind), amount: BigInt(amount), sale_id: null, method_id: kind === 2 ? 1n : null, staff_id: 1n, note: null, at: BigInt(T0 + n * 60000), hlc: h(100 + n), shift_id: null } });
  folder.putSegment(STORE, A, 2, [
    lww(9, SITI, h(99), A, { name: 'Siti', name_key: 'siti', phone: '012-345 6789', credit_limit: 5000n, created_at: BigInt(T0) }),
    credit(0, 1, 3000), credit(1, 2, 1000), credit(2, 3, 50),
  ]);
  await store.sync(folder, () => {}, T0 + 3 * 86_400_000);
  assert.equal(db.selectValue('SELECT balance FROM customer_balance WHERE customer_id = ?', [SITI]), 2050);
  const [c] = R.customers(db);
  assert.deepEqual([c.name, c.balance, c.credit_limit], ['Siti', 2050, 5000]);
  const s = R.customer(db, c.id);
  assert.deepEqual(s.entries.map((e) => [e.delta, e.balanceAfter]), [[50, 2050], [-1000, 2000], [3000, 3000]]);
  assert.equal(s.entries[1].method, 'Cash');
});

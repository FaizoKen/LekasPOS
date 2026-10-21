// The web page changing a shop (D-083): each save is one sync file of the page's own, with its till number's card,
// holding the events a till writes for the same action. Another reader of the folder (a second browser, or a till:
// fixture.test.mjs checks the app itself in CI) ends with exactly the page's data. Lost answers and offline saves
// never lose, repeat or overwrite a file.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { memoryDb, MemoryFolder, idOf, hlcOf } from './helpers.mjs';
import { Store } from '../../site/manager/js/store.js';
import { readSegment, parseCard } from '../../site/manager/js/segment.js';
import { reason, MovementKind, BarcodeKind } from '../../site/manager/js/rules.js';

const STORE = 'st0re-uuid';
const A = 3_000_001;
const T0 = Date.UTC(2026, 9, 19, 2, 0);
const h = (n) => hlcOf(T0 + n * 1000);
const MYR = { symbol: 'RM', decimals: 2 };
const lww = (entity, id, hlc, dev, f) => ({ e: entity, o: 1, r: id, h: hlc, p: { id, hlc, dev: BigInt(dev), f } });

const C1 = idOf(A, 1);
const P1 = idOf(A, 2);
const B1 = idOf(A, 3);
const SUP = idOf(A, 5);
const CUS = idOf(A, 6);
const TAX = idOf(A, 7);

function shop() {
  const folder = new MemoryFolder();
  folder.putJson(`store-${STORE}.json`, { store: STORE, createdAt: BigInt(T0), createdBy: BigInt(A) });
  folder.putJson(`dev-${STORE}-${A}.json`, { dev: BigInt(A), uuid: 'a', name: 'Counter A', app: '1.20.2', lastSeen: BigInt(T0), lastSeq: 1n, prefix: 'AA-' });
  folder.putSegment(STORE, A, 1, [
    lww(2, 1n, 0n, 0, { name: 'Owner', sys_role: 1n, perms: 0n, deleted: 0n, created_at: 1n }),
    lww(3, 1n, 0n, 0, { name: 'Owner', role_id: 1n, active: 1n, deleted: 0n, created_at: 1n }),
    lww(4, TAX, h(1), A, { name: 'SST', code: 'S', rate_bp: 600n, created_at: BigInt(T0) }),
    lww(5, C1, h(2), A, { name: 'Drinks', name_key: 'drinks', color: 0n, sort: 0n, created_at: BigInt(T0) }),
    lww(6, P1, h(3), A, { name: 'Milo', name_key: 'milo', category_id: C1, unit: 'pcs', sell_mode: 0n, price: 1890n, cost: 1200n, track_stock: 1n, low_stock: 0n, active: 1n, created_at: BigInt(T0) }),
    lww(7, B1, h(4), A, { product_id: P1, code: '9556001234568', kind: 0n, pack_qty: 1000n, pack_price: null, created_at: BigInt(T0) }),
    lww(8, SUP, h(5), A, { name: 'Borong', name_key: 'borong', created_at: BigInt(T0) }),
    lww(9, CUS, h(6), A, { name: 'Siti', name_key: 'siti', credit_limit: 0n, created_at: BigInt(T0) }),
    { e: 23, o: 2, r: idOf(A, 8), h: h(10), p: { id: idOf(A, 8), product_id: P1, qty: 10000n, session_id: null, staff_id: 1n, note: null, at: BigInt(T0), hlc: h(10), expected: 0n, unit_cost: 1200n } },
  ]);
  return folder;
}

const LWW = ['role', 'staff', 'tax_rate', 'category', 'product', 'product_barcode', 'supplier', 'customer', 'product_look'];
const EVENT = ['stock_movement', 'stock_count', 'purchase', 'purchase_line', 'audit_log', 'product_image'];

/**
 * Every synced row and the stock (fixture.test.mjs `content`). Field versions are left out: a reader that gets an
 * edit before the row's creation (a lower till number is read first) keeps other versions for the same values.
 */
function content(db) {
  const out = {};
  const text = (v) => (v == null ? '∅' : String(v));
  for (const t of [...LWW, ...EVENT]) {
    const cols = db.selectArrays(`PRAGMA table_info(${t})`).map((r) => r[1]).filter((c) => !['ver_hlc', 'ver_dev', 'fver', 'updated_at'].includes(c));
    out[t] = db.selectArrays(`SELECT ${cols.join(', ')} FROM ${t} ORDER BY id`).map((r) => r.map(text).join('|'));
  }
  out.stock_level = db.selectArrays('SELECT product_id, qty FROM stock_level ORDER BY product_id').map((r) => r.join('|'));
  return out;
}

async function webOf(folder) {
  const db = await memoryDb();
  const store = new Store(db);
  store.createForDrive();
  await store.sync(folder);
  return { db, store };
}

const opts = { staffId: 1n, currency: MYR, deviceName: 'LekasPOS Manager (test)' };
const all = (db, sql, bind) => db.selectArrays(sql, bind);

test('a new product from the web page: one file of its own, its card, the same rows for every reader', async () => {
  const folder = shop();
  const { db, store } = await webOf(folder);
  const r = await store.commit(folder, (w) => w.saveProduct({
    shown: null,
    edited: { name: 'Teh Tarik Café', sku: 'TT-1', categoryId: C1, unit: 'pcs', sellMode: 0, price: 350n, cost: 200n, taxRateId: TAX, trackStock: true, lowStock: 5000n, active: true },
    codes: [
      { id: null, code: '9556001000019', kind: BarcodeKind.BARCODE, packQty: 1000n, packPrice: null },
      { id: null, code: '9556001000026', kind: BarcodeKind.BARCODE, packQty: 24000n, packPrice: 7800n },
      { id: null, code: '123', kind: BarcodeKind.SCALE_PLU, packQty: 1000n, packPrice: null },
    ],
    look: { shown: { color: 0, imageId: null }, edited: { color: 7, imageId: null }, picture: 'AAAA' },
    opening: 12000n,
  }), opts);
  assert.equal(r.seq, 1);
  const me = store.identity();
  assert.ok(me.dev > 0 && me.dev < 2 ** 22 && me.dev !== A);
  const card = parseCard(folder.text(`dev-${STORE}-${me.dev}.json`));
  assert.equal(card.name, 'LekasPOS Manager (test)');
  assert.equal(card.app, 'web');
  assert.equal(card.prefix, null);
  assert.equal(card.lastSeq, 1);
  const seg = folder.files.find((f) => f.name === `seg-${STORE}-${me.dev}-00000001.ndjson.gz`);
  assert.equal(seg.props.count, '7');
  const { header, events } = await readSegment(seg.bytes, { store: STORE, dev: me.dev, seq: 1 });
  assert.equal(header.count, 7);
  // product, its three codes, the opening stock, the picture, the look — in the till's order
  assert.deepEqual(events.map((e) => e.entity), [6, 7, 7, 7, 22, 28, 14]);
  const pid = events[0].rowId;
  assert.equal(Number(pid >> 41n), me.dev);
  assert.equal(events[0].payload.f.name_key, 'teh tarik cafe');
  assert.equal(events[0].payload.f.deleted, 0n);
  // The page's copy holds it, with its stock, picture and colour
  const row = db.selectArray('SELECT name, price, cost, track_stock, ver_dev FROM product WHERE id = ?', [pid]);
  assert.deepEqual(row.map(String), ['Teh Tarik Café', '350', '200', '1', String(me.dev)]);
  assert.equal(String(db.selectValue('SELECT qty FROM stock_level WHERE product_id = ?', [pid])), '12000');
  assert.equal(String(db.selectValue('SELECT color FROM product_look WHERE id = ?', [pid])), '7');
  assert.equal(db.selectValue('SELECT data FROM product_image WHERE product_id = ?', [pid]), 'AAAA');
  assert.equal(String(db.selectValue('SELECT image_id FROM product_look WHERE id = ?', [pid])), String(db.selectValue('SELECT id FROM product_image')));
  // A second reader (another browser, a till) ends with the same data; reading again changes nothing
  const other = await webOf(folder);
  assert.deepEqual(content(other.db), content(db));
  assert.equal((await store.sync(folder)).files, 0);
  // Nothing in the activity log: creating a product is not audited (as on the till)
  assert.equal(Number(db.selectValue('SELECT COUNT(*) FROM audit_log')), 0);
});

test('an edit writes only what the form changed, above what the field holds, with the till\'s activity log', async () => {
  const folder = shop();
  const { db, store } = await webOf(folder);
  const shown = { id: P1, name: 'Milo', sku: null, categoryId: C1, unit: 'pcs', sellMode: 0, price: 1890n, cost: 1200n, taxRateId: null, trackStock: true, lowStock: 0n, active: true };
  const shownCodes = [{ id: B1, code: '9556001234568', kind: 0, packQty: 1000n, packPrice: null }];
  // Meanwhile a till changed the cost (the form still shows the old one) — and its clock runs a day ahead.
  folder.putSegment(STORE, A, 2, [lww(6, P1, hlcOf(Date.now() + 20 * 3_600_000), A, { cost: 1300n })]);
  await store.commit(folder, (w) => w.saveProduct({
    shown, shownCodes,
    edited: { ...shown, price: 1990n, sellMode: 2, taxRateId: TAX },
    codes: [{ id: null, code: '9556001000019', kind: 0, packQty: 12000n, packPrice: 21000n }],
  }), opts);
  const p = db.selectArray('SELECT price, cost, sell_mode, tax_rate_id FROM product WHERE id = ?', [P1]).map(String);
  assert.deepEqual(p, ['1990', '1300', '2', String(TAX)], 'the till\'s cost stays: the form did not change it');
  // The web page's clock is behind the till's: its edit still wins, stamped above the version it saw
  const fver = db.selectValue('SELECT fver FROM product WHERE id = ?', [P1]);
  assert.match(fver, /price:\d+:\d+/);
  assert.deepEqual(all(db, 'SELECT code, pack_qty, pack_price, deleted FROM product_barcode WHERE product_id = ? ORDER BY code', [P1]).map((r) => r.map(String).join('|')),
    ['9556001000019|12000|21000|0', '9556001234568|1000|null|1']);
  const log = all(db, 'SELECT action, amount, detail, staff_id FROM audit_log ORDER BY id').map((r) => r.map(String).join('|'));
  assert.deepEqual(log, [
    '8|1990|Milo: RM18.90 -> RM19.90|1',
    '8|1990|Milo: sold by piece -> open price|1',
    '8|1990|Milo: tax rate changed|1',
    '8|1990|Milo: barcodes +9556001000019 -9556001234568|1',
    '8|1990|Milo: packs: 9556001000019 ×12 = RM210.00|1',
  ]);
  const other = await webOf(folder);
  assert.deepEqual(content(other.db), content(db));
});

test('stock: a count, a removal with its reason, a delivery at the moving average; and a delete', async () => {
  const folder = shop();
  const { db, store } = await webOf(folder);
  await store.commit(folder, (w) => w.setStock(P1, 7000n), opts);
  assert.equal(String(db.selectValue('SELECT qty FROM stock_level WHERE product_id = ?', [P1])), '7000');
  await store.commit(folder, (w) => w.adjustStock(P1, reason('damaged'), 1000n, true, 'box crushed'), opts);
  assert.equal(String(db.selectValue('SELECT qty FROM stock_level WHERE product_id = ?', [P1])), '6000');
  await store.commit(folder, (w) => w.receive({ supplierId: SUP, refNo: ' DO-9 ', note: '', lines: [
    { productId: P1, qty: 6000n, unitCost: 1400n, total: 8400n },
    { productId: P1, qty: 12000n, unitCost: 1500n, total: 18000n },
  ] }), opts);
  // 6 on hand at 12.00, +6 at 14.00 → 13.00; +12 at 15.00 → 14.00
  assert.equal(String(db.selectValue('SELECT cost FROM product WHERE id = ?', [P1])), '1400');
  assert.equal(String(db.selectValue('SELECT qty FROM stock_level WHERE product_id = ?', [P1])), '24000');
  assert.equal(db.selectValue('SELECT ref_no FROM purchase'), 'DO-9');
  assert.equal(Number(db.selectValue('SELECT COUNT(*) FROM stock_movement WHERE kind = ?', [MovementKind.RECEIVE])), 2);
  const log = all(db, 'SELECT action, amount, detail FROM audit_log ORDER BY id').map((r) => r.map(String).join('|'));
  assert.deepEqual(log, ['32|3600|Milo: -3 (counted 7)', '32|1200|Milo: -1 (damaged: box crushed)']);
  assert.equal(String(db.selectValue('SELECT expected FROM stock_count WHERE qty = 7000')), '10000');
  await store.commit(folder, (w) => w.deleteProduct(P1), opts);
  assert.equal(String(db.selectValue('SELECT deleted FROM product WHERE id = ?', [P1])), '1');
  assert.equal(String(db.selectValue('SELECT deleted FROM product_barcode WHERE id = ?', [B1])), '1');
  assert.equal(db.selectValue('SELECT detail FROM audit_log WHERE action = 9'), 'Milo');
  assert.equal(store.identity() != null, true);
  assert.equal(Number(store.meta('web.seg_seq')), 4);
  const other = await webOf(folder);
  assert.deepEqual(content(other.db), content(db));
});

test('categories, suppliers and customers; a credit limit is on record', async () => {
  const folder = shop();
  const { db, store } = await webOf(folder);
  const cat = (await store.commit(folder, (w) => w.saveCategory(null, 'Snek', 5), opts)).result;
  await store.commit(folder, (w) => w.saveCategory({ id: C1, name: 'Drinks', color: 0, sort: 0 }, 'Minuman', 4), opts);
  assert.deepEqual(all(db, 'SELECT name, name_key, color FROM category WHERE deleted = 0 ORDER BY name').map((r) => r.map(String).join('|')), ['Minuman|minuman|4', 'Snek|snek|5']);
  await store.commit(folder, (w) => w.deleteCategory(cat), opts);
  await store.commit(folder, (w) => w.saveSupplier({ id: SUP, name: 'Borong' }, { name: 'Borong Jaya', phone: '03-1234' }), opts);
  assert.equal(db.selectValue('SELECT name_key FROM supplier WHERE id = ?', [SUP]), 'borong jaya');
  await store.commit(folder, (w) => w.saveCustomer({ id: CUS, name: 'Siti', creditLimit: 0n }, { name: 'Siti', phone: '012-345 6789', creditLimit: 50000n }), opts);
  assert.equal(db.selectValue('SELECT phone FROM customer WHERE id = ?', [CUS]), '0123456789');
  assert.equal(db.selectValue('SELECT detail FROM audit_log WHERE action = 26'), 'Siti: none → 500.00');
  await store.commit(folder, (w) => w.deleteCustomer(CUS), opts);
  // Nothing written for an edit that changes nothing
  const before = folder.files.length;
  const r = await store.commit(folder, (w) => w.saveSupplier({ id: SUP, name: 'Borong Jaya', phone: '03-1234' }, { name: 'Borong Jaya', phone: '03-1234' }), opts);
  assert.equal(r.events, 0);
  assert.equal(folder.files.length, before);
  const other = await webOf(folder);
  assert.deepEqual(content(other.db), content(db));
});

test('an answer lost on the way back: the file is read, never sent twice under one number; offline: nothing changes', async () => {
  const folder = shop();
  const { db, store } = await webOf(folder);
  await store.commit(folder, (w) => w.saveCategory(null, 'One', 0), opts); // the card and file 1
  const dev = store.identity().dev;
  folder.failNext = 'after';
  await assert.rejects(store.commit(folder, (w) => w.saveCategory(null, 'Two', 0), opts));
  assert.equal(Number(db.selectValue("SELECT COUNT(*) FROM category WHERE name = 'Two'")), 0, 'not applied: the save failed here');
  folder.failNext = 'before';
  await assert.rejects(store.commit(folder, (w) => w.saveCategory(null, 'Lost', 0), opts));
  await store.commit(folder, (w) => w.saveCategory(null, 'Three', 0), opts);
  const names = all(db, 'SELECT name FROM category WHERE deleted = 0 ORDER BY name_key').map((r) => r[0]);
  assert.deepEqual(names, ['Drinks', 'One', 'Three', 'Two'], 'the file that arrived was read before the next one');
  const mine = folder.files.filter((f) => f.name.startsWith(`seg-${STORE}-${dev}-`)).map((f) => f.name).sort();
  assert.deepEqual(mine, [1, 2, 3].map((n) => `seg-${STORE}-${dev}-0000000${n}.ndjson.gz`));
  const ids = all(db, 'SELECT id FROM category').map((r) => String(r[0]));
  assert.equal(new Set(ids).size, ids.length);
  const other = await webOf(folder);
  assert.deepEqual(content(other.db), content(db));
});

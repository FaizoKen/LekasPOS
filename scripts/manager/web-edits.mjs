// The web page changes the shop the app made (D-083): run in CI on the folder ManagerFixtureTest left (pulled by
// scripts/ci/instrumented.sh). It reads the folder as the page reads Google Drive and makes a manager's day of changes
// through the page's own code (Store.commit, Writer) — products with barcodes, packs, a scale code, a colour, a
// picture and opening stock, price and cost edits, a delete, counts, write-offs, a delivery, categories, suppliers,
// customers and a credit limit — each save one sync file of the page's own beside the tills'. ManagerEditsTest then
// has both tills read them with the app's own sync, and fixture.test.mjs requires the tills to end with the page's data.
// Usage: node scripts/manager/web-edits.mjs <folder>. Writes web-edits.json there: the page's till number and its files.
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { memoryDb, readDiskFolder } from './helpers.mjs';
import { Store } from '../../site/manager/js/store.js';
import { reason, gtinCheckDigit, BarcodeKind, STAFF_OWNER } from '../../site/manager/js/rules.js';
import { currencyOf } from '../../site/manager/js/money.js';
import * as R from '../../site/manager/js/reports.js';

const dir = process.argv[2];
if (!dir) throw new Error('usage: node scripts/manager/web-edits.mjs <folder>');

// A real JPEG (8×8 grey), as the page makes from a photo: base64 without line breaks (Pictures.kt: NO_WRAP).
const PICTURE = '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/' +
  '2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAIAAgDASIAAhEBAxEB/8QAFQABAQAA' +
  'AAAAAAAAAAAAAAAAAAf/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFAEBAAAAAAAAAAAAAAAAAAAAAP/EABQRAQAAAAAAAAAAAAAAAAAAAAD/2gAMAwEAAhEDEQA/AJ8AB//Z';

const folder = readDiskFolder(dir);
const db = await memoryDb();
const store = new Store(db);
store.createForDrive();
await store.sync(folder);
const currency = currencyOf(R.settings(db));
const opts = { staffId: STAFF_OWNER, currency, deviceName: 'Lekas Manager (CI)' };
const commit = async (build) => (await store.commit(folder, build, opts)).result;

const one = (sql, bind) => {
  const v = db.selectValue(sql, bind);
  if (v == null) throw new Error(`nothing for ${sql}`);
  return BigInt(v);
};
const productId = (name) => one('SELECT id FROM product WHERE name = ? AND deleted = 0', [name]);
const code = (body) => body + gtinCheckDigit(body);

// The shown product and codes, as the page's form loads them (worker productForEdit).
const shownOf = (w, id) => {
  const p = w.product(id);
  return { product: p, codes: w.barcodes(id), look: w.look(id) };
};

// Categories and suppliers
const drinks = one("SELECT id FROM category WHERE name = 'Minuman'");
const snacks = await commit((w) => w.saveCategory(null, 'Snek', 5));
await commit((w) => w.saveCategory(w.category(drinks), 'Minuman Sejuk', 4));
// Their order on the selling screen (D-086): Snek first, the others after it as they were.
const others = db.selectArrays('SELECT id FROM category WHERE deleted = 0 AND id != ? ORDER BY sort, name_key', [snacks]).map((r) => BigInt(r[0]));
await commit((w) => w.reorderCategories([snacks, ...others]));
const supplier = await commit((w) => w.saveSupplier(null, { name: 'Borong Jaya Sdn Bhd', phone: '03-5511 2233' }));
await commit((w) => w.saveSupplier({ id: supplier, name: 'Borong Jaya Sdn Bhd', phone: '03-5511 2233' },
  { name: 'Borong Jaya Sdn Bhd', contact: 'En. Rahman', phone: '03-5511 2233' }));

// A new product with everything the form has
const kopi = await commit((w) => w.saveProduct({
  shown: null,
  edited: { name: 'Kopi O Kaw', sku: 'KOK-1', categoryId: snacks, unit: 'pcs', sellMode: 0, price: 250n, cost: 120n, taxRateId: null, trackStock: true, lowStock: 5000n, active: true },
  codes: [
    { id: null, code: code('955600100003'), kind: BarcodeKind.BARCODE, packQty: 1000n, packPrice: null },
    { id: null, code: code('955600100004'), kind: BarcodeKind.BARCODE, packQty: 24000n, packPrice: 5500n },
    { id: null, code: '42', kind: BarcodeKind.SCALE_PLU, packQty: 1000n, packPrice: null },
  ],
  look: { shown: { color: 0, imageId: null }, edited: { color: 9, imageId: null }, picture: PICTURE },
  opening: 20000n,
}));

// A till's product edited: price, tax, a pack barcode (the form shows it as the page's copy holds it)
const milo = productId('Milo 1kg');
const sst = one("SELECT id FROM tax_rate WHERE name = 'SST 6%'");
await commit((w) => {
  const s = shownOf(w, milo);
  return w.saveProduct({
    shown: s.product, shownCodes: s.codes,
    edited: { ...s.product, price: s.product.price + 200n, taxRateId: sst },
    codes: [...s.codes, { id: null, code: code('955600100009'), kind: BarcodeKind.BARCODE, packQty: 12000n, packPrice: 3600n }],
    look: { shown: s.look, edited: { color: 5, imageId: s.look.imageId }, picture: null },
  });
});

// Made by mistake and deleted
const temp = await commit((w) => w.saveProduct({
  shown: null,
  edited: { name: 'Sementara', sku: null, categoryId: null, unit: 'kg', sellMode: 1, price: 1200n, cost: 0n, taxRateId: null, trackStock: false, lowStock: 0n, active: true },
}));
await commit((w) => w.deleteProduct(temp));

// Stock: a count, a write-off, stock found; a delivery from the new supplier
const roti = productId('Roti Gardenia');
const sabun = productId('Sabun Breeze');
await commit((w) => w.setStock(roti, 25000n));
await commit((w) => w.adjustStock(sabun, reason('damaged'), 1000n, true, 'pecah'));
await commit((w) => w.adjustStock(milo, reason('found'), 2000n, false, ''));
await commit((w) => w.receive({ supplierId: supplier, refNo: 'INV-55', note: 'web', lines: [
  { productId: kopi, qty: 48000n, unitCost: 130n, total: 6240n },
  { productId: roti, qty: 10000n, unitCost: 290n, total: 2900n },
] }));

// Customers: a limit raised (on record), a new customer
const siti = one("SELECT id FROM customer WHERE name = 'Siti'");
await commit((w) => {
  const c = db.selectObject('SELECT id, name, phone, email, address, tin, note, credit_limit AS creditLimit FROM customer WHERE id = ?', [siti]);
  return w.saveCustomer(c, { ...c, creditLimit: 80000n });
});
await commit((w) => w.saveCustomer(null, { name: 'Ah Kow', phone: '012-777 8888', creditLimit: 0n }));

// The new product's price changed once more
await commit((w) => {
  const s = shownOf(w, kopi);
  return w.saveProduct({ shown: s.product, shownCodes: s.codes, edited: { ...s.product, price: 270n }, codes: s.codes });
});

const me = store.identity();
const written = [...(folder.written ?? [])].sort();
const files = written.flatMap((n) => (n.startsWith('seg-') ? [n, `${n}.props`] : [n]));
writeFileSync(join(dir, 'web-edits.json'), JSON.stringify({ dev: me.dev, files, kopi: String(kopi), milo: String(milo), temp: String(temp) }, null, 2));
console.log(`web-edits: till ${me.dev}, ${written.filter((n) => n.startsWith('seg-')).length} files of changes`);

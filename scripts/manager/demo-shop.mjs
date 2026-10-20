// A made-up shop for trying the manager web page and taking the user guide's pictures: two tills, six weeks
// of sales, refunds, voids, shifts, deliveries and activity-log entries, written as the tills' sync files
// (MemoryFolder), read in by the page's own Store, and saved as a backup file the page can open.
// Usage: node scripts/manager/demo-shop.mjs <out.lekasbak> [days]
import { readFileSync, writeFileSync } from 'node:fs';
import { memoryDb, MemoryFolder, idOf, hlcOf, sqlite, zip } from './helpers.mjs';
import { Store } from '../../site/manager/js/store.js';
import { APP_SCHEMA } from '../../site/manager/js/schema.js';

const out = process.argv[2] ?? 'demo.lekasbak';
const DAYS = Number(process.argv[3] ?? 42);
const STORE = 'demo-store-0001';
const TZ = 8 * 3_600_000; // Malaysia
const DAY_MS = 86_400_000;
const T1 = 2_345_001;
const T2 = 3_456_002;

let seed = 20261020;
const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
const pick = (a) => a[Math.floor(rnd() * a.length)];
const int = (a, b) => a + Math.floor(rnd() * (b - a + 1));

const seqs = new Map();
const nextId = (dev) => { const s = (seqs.get(dev) ?? 0) + 1; seqs.set(dev, s); return idOf(dev, s); };
let counter = 0;
const hlcAt = (ms) => hlcOf(ms, (counter = (counter + 1) % 60000));
const events = new Map([[T1, []], [T2, []]]);
const emit = (dev, e) => events.get(dev).push(e);
const lww = (dev, entity, id, ms, f, verDev = dev) => {
  const h = hlcAt(ms);
  emit(dev, { e: entity, o: 1, r: id, h, p: { id, hlc: h, dev: BigInt(verDev), f } });
};
const seedRow = (entity, id, f) => emit(T1, { e: entity, o: 1, r: id, h: 0n, p: { id, hlc: 0n, dev: 0n, f: { ...f, deleted: 0n, created_at: 1n } } });

const todayDay = Math.floor((Date.now() + TZ) / DAY_MS);
const firstDay = todayDay - DAYS + 1;
const dayStart = (day) => day * DAY_MS - TZ;
const t0 = dayStart(firstDay) - DAY_MS;

// Built-in rows (Seed.insert), the shop, staff
seedRow(2, 1n, { name: 'Owner', sys_role: 1n, perms: -1n });
seedRow(2, 2n, { name: 'Manager', sys_role: 2n, perms: 129919n });
seedRow(2, 3n, { name: 'Cashier', sys_role: 3n, perms: 49186n });
seedRow(3, 1n, { name: 'Aisyah', role_id: 1n, active: 1n });
[['Cash', 1n, 1n], ['Card', 2n, 0n], ['E-wallet', 3n, 0n], ['Customer credit', 4n, 0n]].forEach(([name, kind, drawer], i) =>
  seedRow(10, BigInt(i + 1), { name, kind, opens_drawer: drawer, sort: BigInt(i), active: 1n }));
const setting = (key, value) => { const h = hlcAt(t0); emit(T1, { e: 1, o: 1, h, p: { key, value, hlc: h, dev: BigInt(T1) } }); };
setting('store.name', 'Kedai Runcit Maju');
setting('store.address', 'No. 12, Jalan Melati, Shah Alam');
setting('currency.symbol', 'RM');
setting('currency.decimals', '2');
setting('shift.required', 'true');
const RAVI = nextId(T1);
const MEI = nextId(T1);
lww(T1, 3, RAVI, t0, { name: 'Ravi', role_id: 3n, active: 1n, created_at: BigInt(t0) });
lww(T1, 3, MEI, t0, { name: 'Mei Ling', role_id: 2n, active: 1n, created_at: BigInt(t0) });
const SUPPLIER = nextId(T1);
lww(T1, 8, SUPPLIER, t0, { name: 'Borong Sentosa Sdn Bhd', name_key: 'borong sentosa sdn bhd', phone: '03-5511 2233', created_at: BigInt(t0) });
// Customers who buy on credit and pay back (credit entries without their sales: enough for the Customers tab)
setting('credit.enabled', 'true');
for (const [name, phone, limit, entries] of [
  ['Siti Aminah', '012-345 6789', 30000n, [[1, 4520], [1, 1890], [2, 5000], [1, 2350]]],
  ['Ah Kow', '016-778 2211', 0n, [[1, 12900], [2, 6000]]],
  ['Pak Rahman', '019-220 4455', 20000n, [[1, 2200], [2, 2200]]],
]) {
  const cid = nextId(T1);
  lww(T1, 9, cid, t0, { name, name_key: name.toLowerCase(), phone, credit_limit: limit, created_at: BigInt(t0) });
  entries.forEach(([kind, amount], i) => {
    const at = t0 + (i + 1) * 5 * DAY_MS;
    const h = hlcAt(at);
    emit(T1, { e: 26, h, p: { id: nextId(T1), customer_id: cid, kind: BigInt(kind), amount: BigInt(amount), sale_id: null, method_id: kind === 2 ? 1n : 4n, staff_id: 1n, note: null, at: BigInt(at), hlc: h, shift_id: null } });
  });
}

const CATS = ['Minuman', 'Roti & Kek', 'Makanan Kering', 'Susu & Telur', 'Barang Rumah', 'Snek'];
const catIds = CATS.map((name, i) => {
  const id = nextId(T1);
  lww(T1, 5, id, t0, { name, name_key: name.toLowerCase(), sort: BigInt(i), created_at: BigInt(t0) });
  return id;
});
const RAW = [
  ['Milo 1kg', 0, 2890, 2150], ['Nescafé Classic 200g', 0, 1890, 1390], ['Teh Boh 100 uncang', 0, 1250, 890], ['Air Mineral Spritzer 1.5L', 0, 180, 110],
  ['100Plus 325ml', 0, 230, 150], ['Coca-Cola 1.5L', 0, 450, 320], ['Kopi O Kaw 3 dalam 1', 0, 990, 690], ['Jus Oren Peel Fresh 1L', 0, 690, 480],
  ['Roti Gardenia Putih', 1, 395, 300], ['Roti Massimo Gandum', 1, 450, 340], ['Kek Lapis Mini', 1, 350, 220], ['Bun Kaya', 1, 120, 70],
  ['Beras Faiza 5kg', 2, 2690, 2190], ['Gula Prai 1kg', 2, 285, 240], ['Tepung Gandum Blue Key 1kg', 2, 295, 230], ['Minyak Masak Saji 5kg', 2, 3290, 2790],
  ['Maggi Kari 5 bungkus', 2, 650, 480], ['Sardin Ayam Brand 425g', 2, 790, 590], ['Kicap Habhal 345ml', 2, 380, 270], ['Sos Cili Life 340g', 2, 420, 300],
  ['Susu Dutch Lady 1L', 3, 790, 610], ['Telur Gred A (10 biji)', 3, 650, 520], ['Susu Pekat Manis F&N', 3, 380, 290], ['Mentega Planta 240g', 3, 690, 520],
  ['Sabun Dobi Breeze 2kg', 4, 1690, 1290], ['Pencuci Pinggan Sunlight 900ml', 4, 590, 420], ['Tisu Premier 10 gulung', 4, 1390, 990], ['Ubat Nyamuk Ridsect', 4, 750, 540],
  ['Twisties 60g', 5, 220, 150], ['Mister Potato 75g', 5, 390, 280], ['Coklat Cadbury 90g', 5, 690, 490], ['Biskut Hup Seng Krim', 5, 590, 430],
];
const products = RAW.map(([name, cat, price, cost], i) => {
  const id = nextId(T1);
  const low = i % 5 === 0 ? 8000n : i % 3 === 0 ? 5000n : 0n;
  lww(T1, 6, id, t0, { name, name_key: name.toLowerCase(), category_id: catIds[cat], price: BigInt(price), cost: BigInt(cost), track_stock: 1n, low_stock: low, unit: 'pcs', created_at: BigInt(t0) });
  lww(T1, 7, nextId(T1), t0, { product_id: id, code: `955${String(1000000000 + i * 7919).slice(-10)}`, kind: 0n, pack_qty: 1000n, created_at: BigInt(t0) });
  emit(T1, { e: 23, h: hlcAt(t0), p: { id: nextId(T1), product_id: id, qty: BigInt(int(700, 1500) * 1000), session_id: null, staff_id: 1n, note: null, at: BigInt(t0), hlc: hlcAt(t0), expected: 0n, unit_cost: BigInt(cost) } });
  return { id, name, cat: catIds[cat], price, cost };
});
// A price change on the second till, later than the first till's
lww(T2, 6, products[0].id, dayStart(firstDay + 10), { price: 2990n });
products[0].price = 2990;

function audit(dev, ms, action, staff, amount, detail) {
  const h = hlcAt(ms);
  emit(dev, { e: 27, h, p: { id: nextId(dev), action: BigInt(action), staff_id: staff, approved_by: null, entity: null, entity_id: null, amount: amount == null ? null : BigInt(amount), detail, at: BigInt(ms), hlc: h } });
}

function sale(dev, ms, day, staff, shift, items, method, kind = 0n, ref = null, refLines = null) {
  const id = nextId(dev);
  const h = hlcAt(ms);
  const lines = items.map(([p, q], i) => {
    const qty = BigInt(q * 1000);
    const net = BigInt(p.price * q);
    return {
      id: refLines ? nextId(dev) : nextId(dev), sale_id: id, line_no: BigInt(i + 1), product_id: p.id, ref_line_id: refLines?.[i] ?? null,
      name: p.name, barcode: null, unit: 'pcs', category_id: p.cat, qty, base_qty: qty, unit_price: BigInt(p.price), gross: net,
      discount: 0n, bill_discount: 0n, net, tax_rate_id: null, tax_bp: 0n, tax: 0n, cost: BigInt(p.cost * q), price_overridden: 0n,
      stock_qty: -qty, hlc: h, promo_id: null, promo_name: null,
    };
  });
  const total = lines.reduce((a, l) => a + l.net, 0n);
  const cost = lines.reduce((a, l) => a + l.cost, 0n);
  let rounding = 0n;
  if (method === 1n) {
    const r = ((total % 5n) + 5n) % 5n;
    rounding = r === 0n ? 0n : r <= 2n ? -r : 5n - r;
  }
  const payTotal = total + rounding;
  const prefix = dev === T1 ? 'AA-' : 'AB-';
  const doc = (seqs.get(`doc${dev}${kind}`) ?? 0) + 1;
  seqs.set(`doc${dev}${kind}`, doc);
  const s = {
    id, kind, receipt_no: `${prefix}${kind === 1n ? 'R' : ''}${String(doc).padStart(6, '0')}`, device_no: BigInt(dev), doc_seq: BigInt(doc),
    ref_sale_id: ref, shift_id: shift, staff_id: staff, customer_id: null, opened_at: BigInt(ms - 45000), sold_at: BigInt(ms), day: BigInt(day),
    line_count: BigInt(lines.length), subtotal: total, discount: 0n, tax: 0n, rounding, total: payTotal, paid: payTotal, change_due: 0n,
    cost, prices_incl_tax: 1n, status: 0n, refunded: 0n, note: null, hlc: h,
  };
  const pays = [{ id: nextId(dev), sale_id: id, method_id: method, kind: method, amount: payTotal, tendered: payTotal, change_given: 0n, ref: null, shift_id: shift, paid_at: BigInt(ms) }];
  emit(dev, { e: 20, o: 2, r: id, h, p: { sale: s, lines, pays } });
  return { id, total: payTotal, lines, items, method };
}

for (let day = firstDay; day <= todayDay; day++) {
  const base = dayStart(day);
  const isToday = day === todayDay;
  const nowCut = isToday ? Date.now() - 5 * 60_000 : Infinity;
  for (const [dev, staff] of [[T1, RAVI], [T2, MEI]]) {
    if (dev === T2 && (day - firstDay) % 7 === 3) continue; // the second till rests one day a week
    const openAt = base + 8 * 3_600_000 + int(0, 20) * 60_000;
    if (openAt > nowCut) continue;
    const shift = nextId(dev);
    const float = 20000n;
    lww(dev, 11, shift, openAt, { device_no: BigInt(dev), opened_by: staff, opened_at: BigInt(openAt), opening_float: float, created_at: BigInt(openAt) });
    audit(dev, openAt, 16, staff, Number(float), null);
    let cash = float;
    const weekend = [5, 6].includes(((day + 3) % 7 + 7) % 7);
    const n = int(weekend ? 45 : 30, weekend ? 85 : 60);
    const done = [];
    for (let i = 0; i < n; i++) {
      const ms = openAt + Math.floor((i + rnd()) * (13 * 3_600_000 / n));
      if (ms > nowCut) break;
      const items = Array.from({ length: int(1, 4) }, () => [pick(products), int(1, 3)]);
      const method = rnd() < 0.6 ? 1n : rnd() < 0.5 ? 2n : 3n;
      const s = sale(dev, ms, day, staff, shift, items, method);
      if (method === 1n) cash += s.total;
      done.push(s);
      if (rnd() < 0.05) audit(dev, ms - 20000, 29, staff, items[0][0].price, items[0][0].name);
    }
    if (done.length > 10 && rnd() < 0.35) {
      // A refund of one item from an earlier sale of the shift
      const orig = pick(done.slice(0, -3));
      const [p] = orig.items[0];
      const ms = Math.min(openAt + 11 * 3_600_000, nowCut - 60_000);
      const r = sale(dev, ms, day, staff, shift, [[p, 1]], 1n, 1n, orig.id, [orig.lines[0].id]);
      // a refund pays money back: its amounts are negative
      const ev = events.get(dev)[events.get(dev).length - 1];
      for (const l of ev.p.lines) { l.qty = -l.qty; l.base_qty = -l.base_qty; l.gross = -l.gross; l.net = -l.net; l.cost = -l.cost; l.stock_qty = -l.stock_qty; }
      for (const k of ['subtotal', 'total', 'paid', 'cost']) ev.p.sale[k] = -ev.p.sale[k];
      ev.p.sale.rounding = -ev.p.sale.rounding;
      ev.p.pays[0].amount = -ev.p.pays[0].amount;
      ev.p.pays[0].tendered = -ev.p.pays[0].tendered;
      cash -= r.total;
      audit(dev, ms, 2, staff, Number(r.total), `${orig.lines[0].name}: wrong flavour`);
    }
    if (done.length > 5 && rnd() < 0.25) {
      const v = done[done.length - 2];
      const ms = Math.min(openAt + 12 * 3_600_000, nowCut - 30_000);
      const h = hlcAt(ms);
      emit(dev, { e: 21, h, p: { id: nextId(dev), sale_id: v.id, reason: 'Customer changed mind', staff_id: MEI, approved_by: null, shift_id: shift, at: BigInt(ms), hlc: h } });
      if (v.method === 1n) cash -= v.total;
      audit(dev, ms, 1, MEI, Number(v.total), 'Customer changed mind');
    }
    if (rnd() < 0.3) {
      const ms = openAt + 4 * 3_600_000;
      if (ms < nowCut) {
        const h = hlcAt(ms);
        emit(dev, { e: 25, h, p: { id: nextId(dev), shift_id: shift, kind: 2n, amount: 1500n, reason: 'Ais batu', staff_id: staff, at: BigInt(ms), hlc: h } });
        cash -= 1500n;
        audit(dev, ms, 19, staff, 1500, 'Ais batu');
      }
    }
    if (rnd() < 0.08) audit(dev, openAt + 6 * 3_600_000, 31, staff, 1290, 'Bill of 3 items');
    if (rnd() < 0.06) audit(dev, openAt + 7 * 3_600_000, 6, staff, null, null);
    if (!isToday) {
      const closeAt = base + 22 * 3_600_000 + int(0, 30) * 60_000;
      const diff = rnd() < 0.2 ? -BigInt(int(1, 20) * 50) : 0n;
      lww(dev, 11, shift, closeAt, { closed_by: staff, closed_at: BigInt(closeAt), counted_cash: cash + diff, expected_cash: cash, note: diff ? 'Kurang sikit' : null });
      audit(dev, closeAt, 17, staff, Number(diff), null);
    }
  }
  if ((day - firstDay) % 7 === 1) {
    const ms = base + 10 * 3_600_000;
    if (ms < Date.now()) {
      const pid = nextId(T1);
      const lines = products.filter((_, i) => i % 3 === (day % 3)).slice(0, 8).map((p) => {
        const q = int(150, 400);
        return { id: nextId(T1), purchase_id: pid, product_id: p.id, qty: BigInt(q * 1000), unit_cost: BigInt(p.cost), total: BigInt(p.cost * q) };
      });
      const h = hlcAt(ms);
      emit(T1, { e: 24, h, p: { purchase: { id: pid, supplier_id: SUPPLIER, ref_no: `DO-${String(day).slice(-4)}`, total: lines.reduce((a, l) => a + l.total, 0n), note: null, staff_id: 1n, at: BigInt(ms), hlc: h }, lines } });
    }
  }
}

// The tills' files, read in as the web page reads Google Drive
const folder = new MemoryFolder();
folder.putJson(`store-${STORE}.json`, { store: STORE, createdAt: BigInt(t0), createdBy: BigInt(T1) });
for (const [dev, name] of [[T1, 'Kaunter 1'], [T2, 'Kaunter 2']]) {
  const evs = events.get(dev);
  let seq = 0;
  for (let i = 0; i < evs.length; i += 2000) folder.putSegment(STORE, dev, ++seq, evs.slice(i, i + 2000));
  folder.putJson(`dev-${STORE}-${dev}.json`, { dev: BigInt(dev), uuid: `u${dev}`, name, app: '1.20.1', lastSeen: BigInt(Date.now() - 60_000), lastSeq: BigInt(seq), prefix: dev === T1 ? 'AA-' : 'AB-' });
}
const db = await memoryDb();
const store = new Store(db);
store.createForDrive();
const r = await store.sync(folder);
db.exec("DELETE FROM meta WHERE key LIKE 'web.%'");
db.exec(`PRAGMA user_version = ${APP_SCHEMA}`);
const s3 = await sqlite();
const bytes = s3.capi.sqlite3_js_db_export(db.pointer);
const sales = db.selectValue('SELECT COUNT(*) FROM sale');
const header = {
  format: 1, createdAt: Date.now(), reason: 'manual', store: STORE, device: `u${T1}`, deviceNo: T1, schema: APP_SCHEMA,
  app: '1.20.1', name: 'Kedai Runcit Maju', sales, products: products.length,
};
writeFileSync(out, zip({ 'backup.json': new TextEncoder().encode(JSON.stringify(header)), 'lekaspos.db': bytes }));
console.log(`${out}: ${sales} sales, ${products.length} products, ${r.files} files, ${r.events} events`);

// `--fixture <dir>`: the shop also as ManagerFixtureTest leaves it (folder files, `name.props`, two backup
// files), to try scripts/manager/fixture.test.mjs without an emulator: FIXTURE_DIR=<dir> node --test …
const at = process.argv.indexOf('--fixture');
if (at > 0) {
  const { mkdirSync } = await import('node:fs');
  const fx = process.argv[at + 1];
  mkdirSync(fx, { recursive: true });
  for (const f of folder.files) {
    writeFileSync(`${fx}/${f.name}`, f.bytes);
    if (Object.keys(f.props).length) writeFileSync(`${fx}/${f.name}.props`, Object.entries(f.props).map(([k, v]) => `${k}=${v}`).join('\n'));
  }
  const file = readFileSync(out);
  writeFileSync(`${fx}/till-a.lekasbak`, file);
  writeFileSync(`${fx}/till-b.lekasbak`, file);
  console.log(`${fx}: ${folder.files.length} files and two backups`);
}

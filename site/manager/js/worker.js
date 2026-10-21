// The manager web page's worker: holds the copy of the shop (SQLite in the browser's private storage,
// OPFS), reads the tills' files from Google Drive or a backup file into it, answers the page's questions
// and makes its changes (D-083: editing.js, one sync file per save). Everything heavy happens here so the
// page stays smooth. Messages: {id, method, args} → {id, result} or {id, error: {code, message, field}};
// progress as {type: 'progress', ...}. Reading the folder and saving a change never run at the same time.

import sqlite3InitModule from '../vendor/sqlite/sqlite3.mjs';
import { Store, SyncError } from './store.js';
import { DriveFolder } from './drive.js';
import { readBackup, BadBackup } from './backup.js';
import * as R from './reports.js';
import * as D from './days.js';
import { Editing } from './editing.js';
import { EditError, readProduct, readBarcodes, readLook, codeOwners as owners } from './writer.js';
import { currencyOf } from './money.js';

const DB_FILE = '/shop.db';
let sqlite3 = null;
let pool = null;
let db = null;
let store = null;
let editing = null; // who changes the shop, for [store]
let busy = null; // the running import, if any
let chain = Promise.resolve(); // reads of the folder and saves, one after another
/** Google Drive's address: a local stand-in on localhost while trying the page (scripts/manager/serve.mjs --fake-drive). */
let driveBase;

/** Runs [fn] after every read or save already asked for. */
function exclusive(fn) {
  const run = chain.then(fn);
  chain = run.catch(() => {});
  return run;
}

function setStore(s) {
  store = s;
  editing = new Editing(store);
}

async function start() {
  sqlite3 = await sqlite3InitModule({ print: () => {}, printErr: () => {} });
  try {
    pool = await sqlite3.installOpfsSAHPoolVfs({ name: 'lekaspos-manager', directory: '/lekaspos-manager', initialCapacity: 4 });
  } catch (e) {
    // Another tab holds the files, or the browser has no OPFS (private window): kept in memory only.
    if (/locked|access handle|NoModificationAllowed|InvalidState/i.test(String(e?.message ?? e))) throw new SyncError('IN_USE');
    pool = null;
  }
  openDb();
}

function openDb() {
  db = pool ? new pool.OpfsSAHPoolDb(DB_FILE) : new sqlite3.oo1.DB(':memory:');
  db.exec('PRAGMA cache_size = -32000');
  setStore(new Store(db));
  if (store.check() === 'reset') wipe();
}

function wipe() {
  db?.close();
  if (pool) pool.unlink(DB_FILE);
  db = pool ? new pool.OpfsSAHPoolDb(DB_FILE) : new sqlite3.oo1.DB(':memory:');
  db.exec('PRAGMA cache_size = -32000');
  setStore(new Store(db));
}

function progress(p) {
  postMessage({ type: 'progress', ...p });
}

const period = (p) => new D.Period(p.from, p.to);

function status() {
  const state = store.check();
  if (state !== 'ok') return { source: null, persistent: !!pool };
  const settings = R.settings(db);
  return {
    source: store.meta('web.source'),
    persistent: !!pool,
    store: store.meta('web.store'),
    account: store.meta('web.account'),
    syncedAt: Number(store.meta('web.synced_at') ?? 0) || null,
    cards: JSON.parse(store.meta('web.cards') ?? '[]'),
    me: store.identity()?.dev ?? null,
    backup: JSON.parse(store.meta('web.backup') ?? 'null'),
    deferred: Number(db.selectValue('SELECT COUNT(*) FROM sync_deferred') ?? 0),
    settings,
    counts: R.counts(db),
  };
}

async function sync({ token }) {
  if (busy) return busy;
  busy = exclusive(async () => {
    const drive = new DriveFolder(token, driveBase);
    if (store.check() !== 'ok' || store.meta('web.source') !== 'drive') {
      wipe();
      store.createForDrive();
    }
    const account = await drive.account().catch(() => null);
    const known = store.meta('web.account');
    if (known && account && known !== account) {
      // Another Google account: another shop. Start again rather than mix two shops.
      wipe();
      store.createForDrive();
    }
    if (account) store.setMeta('web.account', account);
    try {
      return await store.sync(drive, progress);
    } catch (e) {
      if (!(e instanceof SyncError) || e.code !== 'STORE_MOVED') throw e;
      // The tills moved to another store in this account (two tills had each made one): follow them.
      wipe();
      store.createForDrive();
      store.setMeta('web.account', account);
      return store.sync(drive, progress);
    }
  });
  try {
    return await busy;
  } finally {
    busy = null;
  }
}

function openBackup(a) {
  return exclusive(() => openBackupNow(a));
}

async function openBackupNow({ file }) {
  progress({ phase: 'backup', done: 0, total: 1 });
  const { header, db: bytes } = await readBackup(file);
  db.close();
  if (pool) {
    pool.unlink(DB_FILE);
    await pool.importDb(DB_FILE, bytes);
    db = new pool.OpfsSAHPoolDb(DB_FILE);
  } else {
    db = new sqlite3.oo1.DB(':memory:');
    const p = sqlite3.wasm.allocFromTypedArray(bytes);
    db.checkRc(sqlite3.capi.sqlite3_deserialize(db.pointer, 'main', p, bytes.length, bytes.length,
      sqlite3.capi.SQLITE_DESERIALIZE_FREEONCLOSE | sqlite3.capi.SQLITE_DESERIALIZE_RESIZEABLE));
  }
  setStore(new Store(db));
  try {
    if (db.selectValue('PRAGMA quick_check') !== 'ok') throw new BadBackup('DAMAGED', 'quick_check failed');
    store.adoptBackup(header);
  } catch (e) {
    wipe();
    throw e;
  }
  progress({ phase: 'done', done: 1, total: 1 });
  return status();
}

function overview({ today }) {
  const day = (from, to) => R.totals(db, new D.Period(from, to));
  const week = D.weekStart(today);
  const month = D.monthStart(today);
  const last14 = new D.Period(today - 13, today + 1);
  return {
    today: day(today, today + 1),
    yesterday: day(today - 1, today),
    week: day(week, today + 1),
    month: day(month, today + 1),
    lastMonthSoFar: (() => { const p = new D.Period(month, today + 1).sameDaysBefore(); return { ...R.totals(db, p), period: p }; })(),
    days: D.buckets(db.selectObjects('SELECT day, sale_count AS sales, refund_count AS refunds, total, net_ex AS netEx, tax, cost, discount FROM sum_day WHERE day >= ? AND day < ?', [last14.from, last14.to])
      .map((r) => Object.fromEntries(Object.entries(r).map(([k, v]) => [k, Number(v)]))), last14, 'DAY'),
    lowStock: R.lowStockCount(db),
    // Each till's sales: the latest one and its receipt number's prefix ("AA-", what the receipts show).
    tills: db.selectObjects('SELECT device_no AS dev, MAX(sold_at) AS lastSale, COUNT(*) AS sales, ' +
      '(SELECT receipt_no FROM sale x WHERE x.device_no = s.device_no ORDER BY x.sold_at DESC LIMIT 1) AS receipt FROM sale s GROUP BY device_no ORDER BY device_no')
      .map((r) => ({ dev: Number(r.dev), lastSale: Number(r.lastSale), sales: Number(r.sales), prefix: String(r.receipt ?? '').replace(/R?\d+$/, '') })),
    openShifts: R.openShifts(db),
    staff: R.staffNames(db),
    checksToday: R.staffCheck(db, new D.Period(today, today + 1)),
  };
}

// ------------------------------------------------------------------------------- changing the shop (D-083)

const text = (v) => (v == null ? null : String(v));

/** The lists the forms choose from (CategoryDao, CatalogDao.taxRates, SupplierDao orders), ids as text. */
function editLists() {
  return {
    categories: db.selectArrays('SELECT id, name, color, sort FROM category WHERE deleted = 0 ORDER BY sort, name_key')
      .map(([id, name, color, sort]) => ({ id: text(id), name, color: Number(color), sort: Number(sort) })),
    taxes: db.selectArrays('SELECT id, name, rate_bp FROM tax_rate WHERE deleted = 0 ORDER BY rate_bp, name')
      .map(([id, name, rate]) => ({ id: text(id), name, rateBp: Number(rate) })),
    suppliers: db.selectArrays('SELECT id, name, contact, phone, email, address, note FROM supplier WHERE deleted = 0 ORDER BY name_key, id')
      .map(([id, name, contact, phone, email, address, note]) => ({ id: text(id), name, contact, phone, email, address, note })),
  };
}

/** The product form's starting point: the product, its barcodes and look exactly as stored (BigInts), its picture. */
function productForEdit({ id }) {
  const product = id ? readProduct(db, id) : null;
  if (id && (!product || product.deleted)) throw new EditError('GONE');
  const look = product ? readLook(db, product.id) : { color: 0, imageId: null };
  return {
    product,
    codes: product ? readBarcodes(db, product.id) : [],
    look,
    picture: look.imageId ? db.selectValue('SELECT data FROM product_image WHERE id = ?', [look.imageId]) ?? null : null,
    stock: product ? BigInt(db.selectValue('SELECT qty FROM stock_level WHERE product_id = ?', [product.id]) ?? 0) : 0n,
    ...editLists(),
  };
}

/** A customer as the form edits them (CustomerDao columns, the limit as BigInt). */
function customerForEdit({ id }) {
  const r = db.selectArray('SELECT id, name, phone, email, address, tin, note, credit_limit FROM customer WHERE id = ? AND deleted = 0', [BigInt(id)]);
  if (!r) throw new EditError('GONE');
  return { id: BigInt(r[0]), name: r[1], phone: r[2], email: r[3], address: r[4], tin: r[5], note: r[6], creditLimit: BigInt(r[7]) };
}

/** Other products using each of [codes] (also in its other UPC/EAN forms): code → [{id, name}]. */
function codeOwners({ codes, except }) {
  const out = {};
  for (const c of codes) {
    const found = owners(db, c, except ? BigInt(except) : null);
    if (found.length) out[c] = found.map(([id, name]) => ({ id: text(id), name }));
  }
  return out;
}

const deviceName = (name) => String(name || 'Lekas Manager').slice(0, 60);

/** Makes change [action] (editing.js) as the person signed in; the copy then holds it, read back from the folder. */
function edit({ token, action, args, device }) {
  return exclusive(async () => {
    if (store.check() !== 'ok' || store.meta('web.source') !== 'drive') throw new EditError('NOT_DRIVE');
    const drive = new DriveFolder(token, driveBase);
    const currency = currencyOf(R.settings(db));
    const r = await editing.run(drive, action, args ?? {}, { currency, deviceName: deviceName(device), progress });
    return { result: text(r.result), events: r.events, status: status(), access: editing.access() };
  });
}

async function signIn({ staffId, pin, token, device }) {
  const r = await editing.signIn(staffId, pin);
  if (!r.ok && token && editing.pending.length) {
    // Wrong PINs from the 5th on are on record at once, like on a till (StaffSession: PIN_LOCKOUT).
    const currency = currencyOf(R.settings(db));
    exclusive(() => editing.flush(new DriveFolder(token, driveBase), { currency, deviceName: deviceName(device), progress })).catch(() => {});
  }
  return { ...r, access: editing.access() };
}

const METHODS = {
  status,
  sync,
  openBackup,
  forget: () => exclusive(() => { wipe(); return status(); }),
  setDriveBase: ({ base }) => { driveBase = base || undefined; return true; },
  access: () => editing.access(),
  signIn,
  signOut: () => { editing.signOut(); return editing.access(); },
  editLists,
  productForEdit,
  customerForEdit,
  codeOwners,
  edit,
  overview,
  report: ({ p, compare, topN }) => R.report(db, period(p), period(compare), topN ?? 20),
  products: ({ p, byQty, limit }) => R.products(db, period(p), { byQty, limit }),
  stockValue: () => R.stockValue(db),
  slowMovers: ({ p }) => R.slowMovers(db, period(p)),
  staffCheck: ({ p }) => R.staffCheck(db, period(p)),
  sales: (a) => R.sales(db, a),
  sale: ({ id }) => R.sale(db, id),
  productList: (a) => R.productList(db, a),
  product: ({ id }) => R.product(db, id),
  movements: (a) => R.movements(db, a),
  purchases: (a) => R.purchases(db, a),
  purchase: ({ id }) => R.purchase(db, id),
  shifts: (a) => R.shifts(db, a),
  shiftReport: ({ id }) => R.shiftReport(db, id),
  audit: (a) => R.audit(db, a),
  staffNames: () => R.staffNames(db),
  roles: () => R.roles(db),
  customers: (a) => R.customers(db, a),
  customer: ({ id }) => R.customer(db, id),
  exportCsv: ({ what, p, decimals }) => R.exportCsv(db, what, period(p), decimals),
};

const ready = start();

onmessage = async (ev) => {
  const { id, method, args } = ev.data;
  try {
    await ready;
    const fn = METHODS[method];
    if (!fn) throw new Error(`unknown method ${method}`);
    postMessage({ id, result: await fn(args ?? {}) });
  } catch (e) {
    postMessage({ id, error: { code: e?.code ?? e?.kind ?? 'ERROR', message: String(e?.message ?? e), field: e?.field ?? null } });
  }
};

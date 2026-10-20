// The manager web page's worker: holds the copy of the shop (SQLite in the browser's private storage,
// OPFS), reads the tills' files from Google Drive or a backup file into it, and answers the page's
// questions. Everything heavy happens here so the page stays smooth. Messages: {id, method, args} →
// {id, result} or {id, error: {code, message}}; progress as {type: 'progress', ...}.

import sqlite3InitModule from '../vendor/sqlite/sqlite3.mjs';
import { Store, SyncError } from './store.js';
import { DriveFolder } from './drive.js';
import { readBackup, BadBackup } from './backup.js';
import * as R from './reports.js';
import * as D from './days.js';

const DB_FILE = '/shop.db';
let sqlite3 = null;
let pool = null;
let db = null;
let store = null;
let busy = null; // the running import, if any

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
  store = new Store(db);
  if (store.check() === 'reset') wipe();
}

function wipe() {
  db?.close();
  if (pool) pool.unlink(DB_FILE);
  db = pool ? new pool.OpfsSAHPoolDb(DB_FILE) : new sqlite3.oo1.DB(':memory:');
  db.exec('PRAGMA cache_size = -32000');
  store = new Store(db);
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
    backup: JSON.parse(store.meta('web.backup') ?? 'null'),
    deferred: Number(db.selectValue('SELECT COUNT(*) FROM sync_deferred') ?? 0),
    settings,
    counts: R.counts(db),
  };
}

async function sync({ token }) {
  if (busy) return busy;
  busy = (async () => {
    const drive = new DriveFolder(token);
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
  })();
  try {
    return await busy;
  } finally {
    busy = null;
  }
}

async function openBackup({ file }) {
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
  store = new Store(db);
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

const METHODS = {
  status,
  sync,
  openBackup,
  forget: () => { wipe(); return status(); },
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
    postMessage({ id, error: { code: e?.code ?? e?.kind ?? 'ERROR', message: String(e?.message ?? e) } });
  }
};

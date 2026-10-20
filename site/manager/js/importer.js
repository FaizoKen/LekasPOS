// Applies the tills' sync events to the web page's copy of the shop — the same rules as the app's
// data/sync/Importer.kt and SaleDao.applyRemote (references/sync.md §4): EVENT rows are inserted once
// by id, LWW rows merge field by field on (hlc, till). It only stores rows; the derived tables (stock,
// totals per day, sale status, balances) are worked out afterwards for what changed (derived.js).
// Table and column names come from this code or are checked against the table's real columns.

import { stringify } from './json64.js';

export const Entity = Object.freeze({
  SETTING: 1, ROLE: 2, STAFF: 3, TAX_RATE: 4, CATEGORY: 5, PRODUCT: 6, BARCODE: 7, SUPPLIER: 8, CUSTOMER: 9,
  PAYMENT_METHOD: 10, SHIFT: 11, COUNT_SESSION: 12, PROMOTION: 13, PRODUCT_LOOK: 14, PROMOTION_PRICE: 15,
  SALE: 20, SALE_VOID: 21, STOCK_MOVE: 22, STOCK_COUNT: 23, PURCHASE: 24, CASH_MOVE: 25, CREDIT: 26, AUDIT: 27,
  PRODUCT_IMAGE: 28,
});

/** LWW entity → table (Importer.LWW_TABLES). */
export const LWW_TABLES = new Map([
  [Entity.ROLE, 'role'], [Entity.STAFF, 'staff'], [Entity.TAX_RATE, 'tax_rate'], [Entity.CATEGORY, 'category'],
  [Entity.PRODUCT, 'product'], [Entity.BARCODE, 'product_barcode'], [Entity.SUPPLIER, 'supplier'],
  [Entity.CUSTOMER, 'customer'], [Entity.PAYMENT_METHOD, 'payment_method'], [Entity.SHIFT, 'shift'],
  [Entity.COUNT_SESSION, 'count_session'], [Entity.PROMOTION, 'promotion'], [Entity.PRODUCT_LOOK, 'product_look'],
  [Entity.PROMOTION_PRICE, 'promotion_price'],
]);

const EVENT_ENTITIES = new Set([
  Entity.SETTING, Entity.SALE, Entity.SALE_VOID, Entity.STOCK_MOVE, Entity.STOCK_COUNT, Entity.PURCHASE,
  Entity.CASH_MOVE, Entity.AUDIT, Entity.CREDIT, Entity.PRODUCT_IMAGE,
]);

const NOT_FIELDS = new Set(['id', 'ver_hlc', 'ver_dev', 'fver', 'updated_at']);

// Column lists of SaleDao and StockDao (the order the tills write them in).
export const SALE_COLS = [
  'id', 'kind', 'receipt_no', 'device_no', 'doc_seq', 'ref_sale_id', 'shift_id', 'staff_id',
  'customer_id', 'opened_at', 'sold_at', 'day', 'line_count', 'subtotal', 'discount', 'tax',
  'rounding', 'total', 'paid', 'change_due', 'cost', 'prices_incl_tax', 'status', 'refunded',
  'note', 'hlc',
];
export const LINE_COLS = [
  'id', 'sale_id', 'line_no', 'product_id', 'ref_line_id', 'name', 'barcode', 'unit', 'category_id',
  'qty', 'base_qty', 'unit_price', 'gross', 'discount', 'bill_discount', 'net', 'tax_rate_id',
  'tax_bp', 'tax', 'cost', 'price_overridden', 'stock_qty', 'hlc', 'promo_id', 'promo_name',
];
export const PAY_COLS = ['id', 'sale_id', 'method_id', 'kind', 'amount', 'tendered', 'change_given', 'ref', 'shift_id', 'paid_at'];
export const VOID_COLS = ['id', 'sale_id', 'reason', 'staff_id', 'approved_by', 'shift_id', 'at', 'hlc'];
export const MOVE_COLS = ['id', 'product_id', 'kind', 'qty', 'unit_cost', 'ref_id', 'reason', 'staff_id', 'at', 'hlc'];

const insertSql = (table, cols, ignore = true) =>
  `INSERT${ignore ? ' OR IGNORE' : ''} INTO ${table}(${cols.join(', ')}) VALUES(${cols.map(() => '?').join(',')})`;
const INSERT_SALE = insertSql('sale', SALE_COLS);
const INSERT_LINE = insertSql('sale_line', LINE_COLS);
const INSERT_PAY = insertSql('payment', PAY_COLS);
const INSERT_VOID = insertSql('sale_void', VOID_COLS);
const INSERT_MOVE = insertSql('stock_movement', MOVE_COLS);

const SALE_KIND_SALE = 0n;
const SALE_KIND_REFUND = 1n;
const MOVE_RECEIVE = 1n;

// ------------------------------------------------------------------------------- versions (core Lww.kt)

/** (hlc, till) — ties broken by the till's number. */
export function compareVersion(a, b) {
  if (a.hlc !== b.hlc) return a.hlc < b.hlc ? -1 : 1;
  if (a.dev !== b.dev) return a.dev < b.dev ? -1 : 1;
  return 0;
}

const version = (hlc, dev) => ({ hlc: BigInt(hlc ?? 0), dev: BigInt(dev ?? 0) });
const SEED = version(0, 0);

/** The `fver` column: `field:hlc:dev` entries joined by `;`, sorted by field (FieldVersions). */
export function decodeFver(s) {
  const m = new Map();
  if (!s) return m;
  for (const entry of s.split(';')) {
    if (!entry) continue;
    const parts = entry.split(':');
    if (parts.length !== 3) throw new Error(`bad fver entry '${entry}'`);
    m.set(parts[0], version(BigInt(parts[1]), BigInt(parts[2])));
  }
  return m;
}

export function encodeFver(m) {
  if (m.size === 0) return null;
  return [...m.keys()].sort().map((k) => `${k}:${m.get(k).hlc}:${m.get(k).dev}`).join(';');
}

// ------------------------------------------------------------------------------- what an import changed

/** What a run of events changed, for derived.js. Ids are BigInts. */
export class Touched {
  constructor() {
    this.days = new Set(); // business days of sales stored or voided
    this.stock = new Set(); // products whose stock changed
    this.counts = new Set(); // products with a new count
    this.refunded = new Set(); // sales whose refunded amount may have changed
    this.voided = new Set(); // sales that got a void (or arrived after one)
    this.balances = false;
    this.entities = new Set();
    this.events = 0;
  }

  get any() {
    return this.entities.size > 0;
  }
}

// ------------------------------------------------------------------------------- the importer

export class Importer {
  /** [db]: an sqlite3 oo1 DB; [touched]: collects what changed; [now]: clock for updated_at. */
  constructor(db, touched = new Touched(), now = () => Date.now()) {
    this.db = db;
    this.touched = touched;
    this.now = now;
    this.columns = new Map();
  }

  columnsOf(table) {
    let c = this.columns.get(table);
    if (!c) {
      c = new Map();
      for (const r of this.db.selectObjects(`PRAGMA table_info(${table})`)) {
        c.set(r.name, { type: r.type || '', notNull: r.notnull !== 0, hasDefault: r.dflt_value != null });
      }
      this.columns.set(table, c);
    }
    return c;
  }

  /** Whether events of [entity] can be applied; others wait in sync_deferred (D-047). */
  knows(entity) {
    return EVENT_ENTITIES.has(entity) || LWW_TABLES.has(entity);
  }

  run(sql, bind) {
    this.db.exec({ sql, bind });
    return this.db.changes();
  }

  one(sql, bind) {
    return this.db.selectArray(sql, bind);
  }

  /** Applies [e] ({entity, op, rowId, hlc, payload}); true when it changed something. */
  apply(e) {
    const p = e.payload;
    let changed;
    switch (e.entity) {
      case Entity.SETTING: changed = this.setting(p); break;
      case Entity.SALE: changed = this.sale(obj(p.sale), list(p.lines), list(p.pays)); break;
      case Entity.SALE_VOID: changed = this.saleVoid(p); break;
      case Entity.STOCK_MOVE: changed = this.movement(MOVE_COLS.map((c) => p[c])); break;
      case Entity.STOCK_COUNT:
        changed = this.insert('stock_count', p);
        if (changed) {
          this.touched.stock.add(BigInt(p.product_id));
          this.touched.counts.add(BigInt(p.product_id));
        }
        break;
      case Entity.PURCHASE: changed = this.purchase(obj(p.purchase), list(p.lines)); break;
      case Entity.CASH_MOVE: changed = this.insert('cash_movement', p); break;
      case Entity.AUDIT: changed = this.insert('audit_log', p); break;
      case Entity.PRODUCT_IMAGE: changed = this.insert('product_image', p); break;
      case Entity.CREDIT:
        changed = this.insert('credit_entry', p);
        if (changed) this.touched.balances = true;
        break;
      default: {
        const table = LWW_TABLES.get(e.entity);
        changed = table ? this.lww(table, p) : false;
      }
    }
    if (changed) this.touched.entities.add(e.entity);
    return changed;
  }

  /** Keeps an event this page cannot apply (a newer app's kind, or one that failed) for a later try. */
  defer(e) {
    this.run('INSERT INTO sync_deferred(entity, op, row_id, hlc, payload) VALUES(?, ?, ?, ?, ?)',
      [e.entity, e.op ?? 0, e.rowId ?? null, e.hlc, stringify(e.payload)]);
  }

  // ------------------------------------------------------------------ LWW

  lww(table, p) {
    const id = BigInt(p.id);
    const incoming = version(p.hlc, p.dev);
    const cols = this.columnsOf(table);
    const all = obj(p.f);
    const fields = new Map();
    for (const k of Object.keys(all)) if (cols.has(k) && !NOT_FIELDS.has(k)) fields.set(k, all[k]);
    if (fields.size === 0) return false;
    const now = BigInt(this.now());
    const current = this.one(`SELECT ver_hlc, ver_dev, fver FROM ${table} WHERE id = ?`, [id]);
    if (!current) {
      // A creation carries created_at; a change can arrive before its creation (from a third till).
      const creation = fields.has('created_at');
      const values = new Map(fields);
      for (const [name, c] of cols) {
        if (values.has(name) || name === 'id' || !c.notNull || c.hasDefault) continue;
        values.set(name, c.type.toUpperCase() === 'TEXT' ? '' : 0n);
      }
      values.set('updated_at', now);
      if (!values.has('created_at')) values.set('created_at', now);
      const base = creation ? incoming : SEED;
      values.set('ver_hlc', base.hlc);
      values.set('ver_dev', base.dev);
      const fv = new Map();
      if (!creation) for (const k of fields.keys()) fv.set(k, incoming);
      values.set('fver', creation ? null : encodeFver(fv));
      const names = ['id', ...values.keys()];
      this.run(`INSERT INTO ${table}(${names.join(', ')}) VALUES(${names.map(() => '?').join(',')})`, [id, ...values.values()]);
      return true;
    }
    const base = version(current[0], current[1]);
    const versions = decodeFver(current[2]);
    const of = (f) => versions.get(f) ?? base;
    const winners = [...fields.keys()].filter((f) => compareVersion(incoming, of(f)) > 0);
    // Built-in rows (Cash, Owner …) are made by every till in its own language at version (0, 0): at
    // that tie the larger name wins on every till (Importer.lww).
    const theirs = fields.get('name');
    if (compareVersion(incoming, SEED) === 0 && typeof theirs === 'string' && !winners.includes('name') &&
        compareVersion(of('name'), SEED) === 0) {
      const ours = this.one(`SELECT name FROM ${table} WHERE id = ?`, [id])?.[0];
      if (ours != null && theirs > ours) winners.push('name');
    }
    if (winners.length === 0) return false;
    for (const w of winners) versions.set(w, incoming);
    this.run(`UPDATE ${table} SET ${winners.map((w) => `${w} = ?`).join(', ')}, fver = ?, updated_at = ? WHERE id = ?`,
      [...winners.map((w) => fields.get(w)), encodeFver(versions), now, id]);
    return true;
  }

  setting(p) {
    const key = p.key;
    const incoming = version(p.hlc, p.dev);
    const current = this.one('SELECT ver_hlc, ver_dev FROM setting WHERE key = ?', [key]);
    if (current && compareVersion(incoming, version(current[0], current[1])) <= 0) return false;
    const values = [p.value ?? null, BigInt(this.now()), incoming.hlc, incoming.dev, key];
    if (this.run('UPDATE setting SET value = ?, updated_at = ?, ver_hlc = ?, ver_dev = ? WHERE key = ?', values) === 0) {
      this.run('INSERT INTO setting(value, updated_at, ver_hlc, ver_dev, key) VALUES(?, ?, ?, ?, ?)', values);
    }
    return true;
  }

  // ------------------------------------------------------------------ EVENT rows

  /** INSERT OR IGNORE of [row] with the columns [table] really has; true if new. */
  insert(table, row) {
    const cols = this.columnsOf(table);
    const names = Object.keys(row).filter((k) => cols.has(k));
    if (!names.includes('id')) return false;
    return this.run(insertSql(table, names), names.map((k) => row[k])) > 0;
  }

  /** A sale or refund from another till (SaleDao.applyRemote): stored once, its derived data later. */
  sale(sale, lines, pays) {
    const s = SALE_COLS.map((c) => sale[c] ?? null);
    s[SALE_COLS.indexOf('status')] = 0n;
    s[SALE_COLS.indexOf('refunded')] = 0n;
    if (this.run(INSERT_SALE, s) === 0) return false;
    const id = BigInt(sale.id);
    const t = this.touched;
    for (const l of lines) {
      this.run(INSERT_LINE, LINE_COLS.map((c) => l[c] ?? null));
      if (l.product_id != null && (l.stock_qty ?? 0n) != 0n) t.stock.add(BigInt(l.product_id));
    }
    for (const p of pays) this.run(INSERT_PAY, PAY_COLS.map((c) => p[c] ?? null));
    t.days.add(Number(sale.day));
    const kind = BigInt(sale.kind);
    if (kind === SALE_KIND_REFUND && sale.ref_sale_id != null) t.refunded.add(BigInt(sale.ref_sale_id));
    if (kind === SALE_KIND_SALE) t.refunded.add(id); // its refunds may have arrived first
    if (this.one('SELECT 1 FROM sale_void WHERE sale_id = ? LIMIT 1', [id])) t.voided.add(id); // the void came first
    return true;
  }

  saleVoid(row) {
    if (this.run(INSERT_VOID, VOID_COLS.map((c) => row[c] ?? null)) === 0) return false;
    this.touched.voided.add(BigInt(row.sale_id));
    return true;
  }

  movement(values) {
    if (this.run(INSERT_MOVE, values) === 0) return false;
    this.touched.stock.add(BigInt(values[1]));
    return true;
  }

  /** A delivery: purchase and lines once, its RECEIVE movements made again with the line ids (D-036). */
  purchase(purchase, lines) {
    if (!this.insert('purchase', purchase)) return false;
    const id = purchase.id;
    for (const l of lines) {
      this.insert('purchase_line', l);
      this.movement([l.id, l.product_id, MOVE_RECEIVE, l.qty, l.unit_cost, id, null, purchase.staff_id ?? null, purchase.at, purchase.hlc]);
    }
    return true;
  }
}

function obj(v) {
  return v && typeof v === 'object' && !Array.isArray(v) ? v : Object.create(null);
}

function list(v) {
  return Array.isArray(v) ? v.filter((x) => x && typeof x === 'object' && !Array.isArray(x)) : [];
}

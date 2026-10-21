// The changes the web page makes to a shop (D-083): each save becomes the same sync events a till writes for the
// same action — the rows, field versions, stock stamps and activity-log entries of the app's DAOs (LwwWriter,
// ProductDao, ProductLookDao, StockDao, PurchaseDao, CategoryDao, SupplierDao, CustomerDao, AuditDao) and the
// screens that call them (ProductEditActivity.persist, InventoryService, CategoriesActivity, CustomerService).
// A Writer only reads the page's copy: the events go to Google Drive first, and the copy takes them through the
// importer like every till's (store.js commit). Ids and amounts are BigInts; table names are this file's own.

import {
  Entity, EventOp, AuditAction, MovementKind, SellMode, searchKey, gtinLookupVariants, stampAbove, hlcPack,
  hlcPhysical, MAX_FUTURE_MS, mulDivHalfUp, movingAverage, reasonDelta, reasonEncode, normalizePhone, idOf,
} from './rules.js';
import { decodeFver } from './importer.js';
import { format, plain, qty as qtyText } from './money.js';

/** A change the rules refuse ([code]: what is wrong; [field]: which input). */
export class EditError extends Error {
  constructor(code, field = null, detail = null) {
    super(code);
    this.code = code;
    this.field = field;
    this.detail = detail;
  }
}

const big = (v) => (v == null ? null : BigInt(v));
const bool = (v) => (v ? 1n : 0n);
const SALE_COMPLETED = 0n;

/** SQLite values of a row's fields (booleans as 0/1, numbers as BigInt), keys in their order. */
function sqlValues(fields) {
  const out = {};
  for (const [k, v] of Object.entries(fields)) {
    out[k] = typeof v === 'boolean' ? bool(v) : typeof v === 'number' ? BigInt(v) : v ?? null;
  }
  return out;
}

const same = (a, b) => (a ?? null) === (b ?? null);

/** The fields of an edit to write: those the screen changed ([shown] → [edited]) that the row does not hold already. */
function lwwChanges(shown, edited, current) {
  const out = {};
  for (const [k, v] of Object.entries(edited)) if (!same(shown[k], v) && !same(current[k], v)) out[k] = v;
  return out;
}

// ------------------------------------------------------------------------------- rows as the forms show them

/** A product as stored, or null: {id, name, sku, categoryId, unit, sellMode, price, cost, taxRateId, trackStock, lowStock, active, deleted}. */
export function readProduct(db, id) {
  const r = db.selectArray('SELECT id, name, sku, category_id, unit, sell_mode, price, cost, tax_rate_id, track_stock, low_stock, active, deleted ' +
    'FROM product WHERE id = ?', [BigInt(id)]);
  if (!r) return null;
  return {
    id: BigInt(r[0]), name: r[1], sku: r[2] ?? null, categoryId: big(r[3]), unit: r[4], sellMode: Number(r[5]), price: BigInt(r[6]),
    cost: BigInt(r[7]), taxRateId: big(r[8]), trackStock: Number(r[9]) !== 0, lowStock: BigInt(r[10]), active: Number(r[11]) !== 0,
    deleted: Number(r[12]) !== 0,
  };
}

/** The product's barcodes (not removed), in id order: {id, code, kind, packQty, packPrice}. */
export function readBarcodes(db, productId) {
  return db.selectArrays('SELECT id, code, kind, pack_qty, pack_price FROM product_barcode WHERE product_id = ? AND deleted = 0 ORDER BY id', [BigInt(productId)])
    .map((r) => ({ id: BigInt(r[0]), code: r[1], kind: Number(r[2]), packQty: BigInt(r[3]), packPrice: big(r[4]) }));
}

/** Its colour and picture on the selling screen: {color, imageId}. */
export function readLook(db, productId) {
  const r = db.selectArray('SELECT color, image_id FROM product_look WHERE id = ?', [BigInt(productId)]);
  return r ? { color: Number(r[0]), imageId: big(r[1]) } : { color: 0, imageId: null };
}

/** Other products (not deleted) with [code], also in its other UPC/EAN forms: [[id, name]] (ProductDao.codeOwners). */
export function codeOwners(db, code, exceptProductId) {
  const seen = new Map();
  for (const c of gtinLookupVariants(code)) {
    for (const [pid, name] of db.selectArrays('SELECT p.id, p.name FROM product_barcode b CROSS JOIN product p ON p.id = b.product_id ' +
      'WHERE b.code = ? AND b.deleted = 0 AND p.deleted = 0 AND p.id != ? LIMIT 5', [c, exceptProductId ?? 0n])) {
      if (!seen.has(String(pid))) seen.set(String(pid), [BigInt(pid), name]);
    }
  }
  return [...seen.values()].slice(0, 5);
}

export function readCategory(db, id) {
  const r = db.selectArray('SELECT id, name, color, sort, deleted FROM category WHERE id = ?', [BigInt(id)]);
  return r ? { id: BigInt(r[0]), name: r[1], color: Number(r[2]), sort: Number(r[3]), deleted: Number(r[4]) !== 0 } : null;
}

/** "sold by piece" in the activity log (ProductEditActivity.sellModeName: English, like every detail). */
const sellModeName = (m) => (m === SellMode.WEIGHT ? 'weight' : m === SellMode.OPEN_PRICE ? 'open price' : 'piece');

export class Writer {
  /**
   * [dev]: the web page's till number; [clock]: its Clock; [nextSeq]: the next id sequence (persisted by the store);
   * [staffId]: who makes the change (BigInt); [currency]: the shop's, for the activity log's amounts.
   */
  constructor(db, { dev, clock, nextSeq, staffId, currency, now = Date.now() }) {
    this.db = db;
    this.dev = dev;
    this.clock = clock;
    this.nextSeq = nextSeq;
    this.staffId = staffId;
    this.currency = currency;
    this.now = BigInt(now);
    this.events = [];
    this.stamped = new Map(); // "table:id:field" → the last version this save gave it
  }

  nextId() {
    return idOf(this.dev, this.nextSeq());
  }

  push(entity, op, rowId, hlc, payload) {
    this.events.push({ entity, op, rowId, hlc, payload });
  }

  value(sql, bind) {
    return this.db.selectValue(sql, bind);
  }

  // ------------------------------------------------------------------------------- LWW rows (LwwWriter)

  lwwInsert(table, entity, id, fields) {
    const hlc = this.clock.now();
    const f = { ...sqlValues(fields), deleted: 0n, created_at: this.now };
    for (const k of Object.keys(f)) this.stamped.set(`${table}:${id}:${k}`, hlc);
    this.push(entity, EventOp.LWW, id, hlc, { id, hlc, dev: BigInt(this.dev), f });
  }

  /** Writes [changes] to row [id]; false when the row does not exist. */
  lwwUpdate(table, entity, id, changes) {
    const row = this.db.selectArray(`SELECT ver_hlc, ver_dev, fver FROM ${table} WHERE id = ?`, [id]);
    const fresh = [...this.stamped.keys()].some((k) => k.startsWith(`${table}:${id}:`));
    if (!row && !fresh) return false;
    const keys = Object.keys(changes);
    if (keys.length === 0) return true;
    const base = row ? BigInt(row[0]) : 0n;
    const versions = row ? decodeFver(row[2]) : new Map();
    let held = 0n;
    for (const k of keys) {
      const v = versions.get(k)?.hlc ?? base;
      if (v > held) held = v;
      const mine = this.stamped.get(`${table}:${id}:${k}`);
      if (mine != null && mine > held) held = mine;
    }
    const hlc = stampAbove(this.clock.now(), held);
    for (const k of keys) this.stamped.set(`${table}:${id}:${k}`, hlc);
    this.push(entity, EventOp.LWW, id, hlc, { id, hlc, dev: BigInt(this.dev), f: sqlValues(changes) });
    return true;
  }

  lwwDelete(table, entity, id) {
    return this.lwwUpdate(table, entity, id, { deleted: 1n });
  }

  // ------------------------------------------------------------------------------- EVENT rows

  /** AuditDao.log: who did which sensitive action ([by]: {staffId, at} of an earlier moment, e.g. a sign-in). */
  audit(action, entity, entityId, amount, detail, by = {}) {
    const id = this.nextId();
    const hlc = this.clock.now();
    this.push(Entity.AUDIT, EventOp.INSERT, id, hlc, {
      id, action: BigInt(action), staff_id: 'staffId' in by ? big(by.staffId) : this.staffId, approved_by: null, entity: big(entity),
      entity_id: big(entityId), amount: big(amount), detail: detail ?? null, at: by.at != null ? BigInt(by.at) : this.now, hlc,
    });
    return id;
  }

  /** count_hlc of the products' last counts: new stock events come after them (StockDao.stampAfterCounts). */
  countFloor(productIds) {
    let floor = 0n;
    for (const pid of productIds) {
      const v = big(this.value('SELECT count_hlc FROM stock_level WHERE product_id = ?', [pid])) ?? 0n;
      if (v > floor) floor = v;
    }
    return floor;
  }

  level(productId) {
    return big(this.value('SELECT qty FROM stock_level WHERE product_id = ?', [productId])) ?? 0n;
  }

  /** StockDao.insertMovement: a stock change by hand (or the opening stock of a new product). */
  movement(productId, kind, qty, unitCost, refId, reasonText) {
    const id = this.nextId();
    const hlc = stampAbove(this.clock.now(), this.countFloor([productId]));
    this.push(Entity.STOCK_MOVE, EventOp.INSERT, id, hlc, {
      id, product_id: productId, kind: BigInt(kind), qty: BigInt(qty), unit_cost: big(unitCost), ref_id: big(refId),
      reason: reasonText ?? null, staff_id: this.staffId, at: this.now, hlc,
    });
    return id;
  }

  /** StockDao.stampAfterEverything: a count comes after everything known about the product's stock. */
  stampAfterEverything(now, productId) {
    const countHlc = big(this.value('SELECT count_hlc FROM stock_level WHERE product_id = ?', [productId])) ?? 0n;
    const ahead = hlcPack(hlcPhysical(now) + BigInt(MAX_FUTURE_MS), 0);
    const bound = ahead > countHlc + 2n ? ahead : countHlc + 2n;
    const lastSale = big(this.value('SELECT MAX(hlc) FROM sale_line WHERE product_id = ? AND hlc < ?', [productId, bound])) ?? 0n;
    const lastMove = big(this.value('SELECT MAX(hlc) FROM stock_movement WHERE product_id = ? AND hlc < ?', [productId, bound])) ?? 0n;
    let floor = countHlc;
    if (lastSale > floor) floor = lastSale;
    if (lastMove > floor) floor = lastMove;
    return stampAbove(now, floor);
  }

  /** StockDao.insertCount: [qty] counted becomes the product's stock (a new baseline). */
  count(productId, qty, sessionId = null, note = null) {
    const id = this.nextId();
    const hlc = this.stampAfterEverything(this.clock.now(), productId);
    const dev = BigInt(this.dev);
    const movesAfter = big(this.value('SELECT SUM(qty) FROM stock_movement WHERE product_id = ? AND hlc >= ? AND (hlc > ? OR (id >> 41) > ?)',
      [productId, hlc, hlc, dev])) ?? 0n;
    const salesAfter = big(this.value('SELECT SUM(l.stock_qty) FROM sale_line l JOIN sale s ON s.id = l.sale_id ' +
      'WHERE l.product_id = ? AND l.hlc >= ? AND (l.hlc > ? OR (l.id >> 41) > ?) AND s.status = ?', [productId, hlc, hlc, dev, SALE_COMPLETED])) ?? 0n;
    const expected = this.level(productId) - (movesAfter + salesAfter);
    const cost = big(this.value('SELECT cost FROM product WHERE id = ?', [productId]));
    this.push(Entity.STOCK_COUNT, EventOp.INSERT, id, hlc, {
      id, product_id: productId, qty: BigInt(qty), session_id: big(sessionId), staff_id: this.staffId, note: note ?? null,
      at: this.now, hlc, expected, unit_cost: cost,
    });
    return id;
  }

  // ------------------------------------------------------------------------------- products (ProductEditActivity)

  product(id) {
    return readProduct(this.db, id);
  }

  barcodes(productId) {
    return readBarcodes(this.db, productId);
  }

  look(productId) {
    return readLook(this.db, productId);
  }

  /** ProductDao.fields: the columns an edit can change, as stored. */
  static productFields(p) {
    return {
      name: p.name, name_key: searchKey(p.name), sku: p.sku ?? null, category_id: big(p.categoryId), unit: p.unit,
      sell_mode: BigInt(p.sellMode), price: BigInt(p.price), cost: BigInt(p.cost), tax_rate_id: big(p.taxRateId),
      track_stock: bool(p.trackStock), low_stock: BigInt(p.lowStock), active: bool(p.active),
    };
  }

  codeOwners(code, exceptProductId) {
    return codeOwners(this.db, code, exceptProductId);
  }

  /**
   * Saves the product form (ProductEditActivity.persist). [shown]: the product as the form first showed it (null for
   * a new one); [edited]: what it says now; [shownCodes] / [codes]: its barcodes then and now ({id|null, code, kind,
   * packQty, packPrice}, the scale PLU among them); [look] {shown, edited, picture}; [opening]: a new product's stock
   * (milli-units, 0 = none) when the person may set stock. Returns the product's id.
   */
  saveProduct({ shown, edited, shownCodes = [], codes = [], look = null, opening = 0n }) {
    const before = shown ? this.product(shown.id) : null;
    if (shown && (!before || before.deleted)) throw new EditError('GONE');
    const picture = look?.picture ?? null;
    if (!before) {
      const id = this.nextId();
      const ids = codes.map(() => this.nextId());
      this.lwwInsert('product', Entity.PRODUCT, id, Writer.productFields(edited));
      codes.forEach((c, i) => this.addBarcode(ids[i], id, c));
      if (BigInt(opening) > 0n && edited.trackStock) this.movement(id, MovementKind.OPENING, opening, edited.cost, null, null);
      const imageId = picture ? this.addImage(id, picture) : null;
      this.saveLook(id, { color: 0, imageId: null }, { color: look?.edited?.color ?? 0, imageId: imageId ?? look?.edited?.imageId ?? null });
      return id;
    }
    const id = before.id;
    const changes = lwwChanges(Writer.productFields(shown), Writer.productFields(edited), Writer.productFields(before));
    if (Object.keys(changes).length) this.lwwUpdate('product', Entity.PRODUCT, id, changes);
    const imageId = picture ? this.addImage(id, picture) : null;
    if (look) this.saveLook(id, look.shown, { ...look.edited, imageId: imageId ?? look.edited.imageId ?? null });
    const c = this.currency;
    const label = 'name' in changes ? edited.name : before.name;
    const price = BigInt(edited.price);
    if ('price' in changes) {
      this.audit(AuditAction.PRODUCT_PRICE_CHANGE, Entity.PRODUCT, id, price, `${label}: ${format(before.price, c)} -> ${format(price, c)}`);
    }
    if ('sell_mode' in changes) {
      this.audit(AuditAction.PRODUCT_PRICE_CHANGE, Entity.PRODUCT, id, price, `${label}: sold by ${sellModeName(before.sellMode)} -> ${sellModeName(edited.sellMode)}`);
    }
    if ('tax_rate_id' in changes) this.audit(AuditAction.PRODUCT_PRICE_CHANGE, Entity.PRODUCT, id, price, `${label}: tax rate changed`);
    if ('cost' in changes) {
      this.audit(AuditAction.PRODUCT_PRICE_CHANGE, Entity.PRODUCT, id, BigInt(edited.cost), `${label}: cost ${format(before.cost, c)} -> ${format(edited.cost, c)}`);
    }
    const codesBefore = [...new Set(shownCodes.map((x) => x.code))];
    const codesAfter = [...new Set(codes.map((x) => x.code))];
    const added = codesAfter.filter((x) => !codesBefore.includes(x));
    const removed = codesBefore.filter((x) => !codesAfter.includes(x));
    if (added.length || removed.length) {
      const text = [...added.map((x) => `+${x}`), ...removed.map((x) => `-${x}`)].join(' ');
      this.audit(AuditAction.PRODUCT_PRICE_CHANGE, Entity.PRODUCT, id, price, `${label}: barcodes ${text}`.slice(0, 300));
    }
    const pack = (x) => (x.packQty !== 1000n || x.packPrice != null ? `${x.code}|${x.packQty}|${x.packPrice ?? ''}` : null);
    const packsBefore = [...new Set(shownCodes.map(pack).filter(Boolean))];
    const packsAfterList = codes.filter((x) => pack(x));
    const packsAfter = [...new Set(packsAfterList.map(pack))];
    if (packsBefore.length !== packsAfter.length || packsBefore.some((x) => !packsAfter.includes(x))) {
      const seenPack = new Set();
      const text = packsAfterList.filter((x) => !seenPack.has(pack(x)) && seenPack.add(pack(x)))
        .map((x) => `${x.code} ×${qtyText(x.packQty)}${x.packPrice != null ? ` = ${format(x.packPrice, c)}` : ''}`).join(', ');
      this.audit(AuditAction.PRODUCT_PRICE_CHANGE, Entity.PRODUCT, id, price, `${label}: packs: ${text}`);
    }
    // Removed = shown on the form and taken off; a barcode another till added meanwhile stays.
    const keep = new Set(codes.filter((x) => x.id != null).map((x) => String(x.id)));
    const shownIds = new Set(shownCodes.map((x) => String(x.id)));
    for (const b of this.barcodes(id)) if (shownIds.has(String(b.id)) && !keep.has(String(b.id))) this.lwwDelete('product_barcode', Entity.BARCODE, b.id);
    for (const w of codes) {
      if (w.id == null) {
        this.addBarcode(this.nextId(), id, w);
      } else {
        const old = shownCodes.find((x) => String(x.id) === String(w.id));
        if (old && (old.code !== w.code || old.kind !== w.kind || old.packQty !== w.packQty || !same(old.packPrice, w.packPrice))) this.updateBarcode(old, w);
      }
    }
    return id;
  }

  addBarcode(id, productId, c) {
    this.lwwInsert('product_barcode', Entity.BARCODE, id, {
      product_id: productId, code: c.code, kind: BigInt(c.kind), pack_qty: BigInt(c.packQty), pack_price: big(c.packPrice),
    });
  }

  /** ProductDao.updateBarcode: the fields the form changed that the row does not hold already. */
  updateBarcode(before, after) {
    const r = this.db.selectArray('SELECT code, kind, pack_qty, pack_price FROM product_barcode WHERE id = ?', [before.id]);
    if (!r) return false;
    const fields = (x) => ({ code: x.code, kind: BigInt(x.kind), pack_qty: BigInt(x.packQty), pack_price: big(x.packPrice) });
    const current = { code: r[0], kind: BigInt(r[1]), pack_qty: BigInt(r[2]), pack_price: big(r[3]) };
    return this.lwwUpdate('product_barcode', Entity.BARCODE, before.id, lwwChanges(fields(before), fields(after), current));
  }

  /** ProductLookDao.update: colour and picture on the selling screen (D-066). */
  saveLook(productId, shown, edited) {
    const exists = this.value('SELECT COUNT(*) FROM product_look WHERE id = ?', [productId]) > 0;
    const fields = (l) => ({ color: BigInt(l.color ?? 0), image_id: big(l.imageId) });
    if (!exists) {
      if ((edited.color ?? 0) === 0 && edited.imageId == null) return false;
      this.lwwInsert('product_look', Entity.PRODUCT_LOOK, productId, fields(edited));
      return true;
    }
    const current = this.look(productId);
    const changes = lwwChanges(fields(shown), fields(edited), fields(current));
    if (Object.keys(changes).length === 0) return false;
    return this.lwwUpdate('product_look', Entity.PRODUCT_LOOK, productId, changes);
  }

  /** ProductLookDao.addImage: a picture ([data]: JPEG as base64) is a new row, never changed. */
  addImage(productId, data) {
    if (!data) throw new EditError('PICTURE');
    const id = this.nextId();
    const hlc = this.clock.now();
    this.push(Entity.PRODUCT_IMAGE, EventOp.INSERT, id, hlc, { id, product_id: productId, data, staff_id: this.staffId, at: this.now, hlc });
    return id;
  }

  /** ProductDao.delete + PRODUCT_DELETE: a tombstone (history keeps pointing at it) and its barcodes. */
  deleteProduct(productId) {
    const p = this.product(productId);
    if (!p || p.deleted) throw new EditError('GONE');
    this.lwwDelete('product', Entity.PRODUCT, p.id);
    for (const b of this.barcodes(p.id)) this.lwwDelete('product_barcode', Entity.BARCODE, b.id);
    this.audit(AuditAction.PRODUCT_DELETE, Entity.PRODUCT, p.id, null, p.name);
  }

  // ------------------------------------------------------------------------------- stock (InventoryService)

  /** "Change stock → Count" (D-080): [qty] counted becomes the stock; a count down is on record like a write-off. */
  setStock(productId, qty) {
    qty = BigInt(qty);
    if (qty < 0n) throw new EditError('QTY', 'qty');
    const p = this.product(productId);
    if (!p || p.deleted) throw new EditError('GONE');
    const before = this.level(productId);
    this.count(productId, qty);
    if (qty < before) {
      const value = mulDivHalfUp(p.cost, before - qty, 1000n);
      this.audit(AuditAction.STOCK_WRITE_OFF, Entity.PRODUCT, productId, value, `${p.name}: -${qtyText(before - qty)} (counted ${qtyText(qty)})`.slice(0, 300));
    }
  }

  /** "Change stock → Remove / Add": a movement with its reason; every removal is on record with its value at cost. */
  adjustStock(productId, r, qty, removing, note) {
    const p = this.product(productId);
    if (!p || p.deleted) throw new EditError('GONE');
    const delta = reasonDelta(r, qty, removing);
    const text = reasonEncode(r, note);
    this.movement(productId, r.kind, delta, null, null, text);
    if (delta < 0n) {
      const value = mulDivHalfUp(p.cost, -delta, 1000n);
      this.audit(AuditAction.STOCK_WRITE_OFF, Entity.PRODUCT, productId, value, `${p.name}: -${qtyText(-delta)} (${text})`.slice(0, 300));
    }
  }

  /**
   * A delivery (PurchaseDao.commit): the purchase and its lines (their RECEIVE movements are made from the lines by
   * every reader) and each product's cost moved to the moving average (D-033). [lines]: {productId, qty, unitCost, total}.
   */
  receive({ supplierId = null, refNo = '', note = '', lines }) {
    if (!lines?.length) throw new EditError('EMPTY');
    const id = this.nextId();
    const hlc = stampAbove(this.clock.now(), this.countFloor([...new Set(lines.map((l) => String(l.productId)))].map(BigInt)));
    let total = 0n;
    for (const l of lines) {
      if (BigInt(l.qty) <= 0n || BigInt(l.total) < 0n || BigInt(l.unitCost) < 0n) throw new EditError('LINE');
      total += BigInt(l.total);
    }
    const purchase = {
      id, supplier_id: big(supplierId), ref_no: refNo?.trim() ? refNo.trim() : null, total, note: note?.trim() ? note.trim() : null,
      staff_id: this.staffId, at: this.now, hlc,
    };
    const rows = [];
    const onHand = new Map();
    const cost = new Map();
    for (const l of lines) {
      const pid = BigInt(l.productId);
      const p = this.product(pid);
      if (!p) throw new EditError('GONE');
      const key = String(pid);
      if (!onHand.has(key)) onHand.set(key, p.trackStock ? this.level(pid) : 0n);
      if (!cost.has(key)) cost.set(key, p.cost);
      const lineId = this.nextId();
      rows.push({ id: lineId, purchase_id: id, product_id: pid, qty: BigInt(l.qty), unit_cost: BigInt(l.unitCost), total: BigInt(l.total) });
      const newCost = movingAverage(onHand.get(key), cost.get(key), l.qty, l.total);
      if (p.trackStock) onHand.set(key, onHand.get(key) + BigInt(l.qty)); // the line's RECEIVE movement
      if (newCost !== cost.get(key)) {
        this.lwwUpdate('product', Entity.PRODUCT, pid, { cost: newCost });
        cost.set(key, newCost);
      }
    }
    this.push(Entity.PURCHASE, EventOp.INSERT, id, hlc, { purchase, lines: rows });
    return id;
  }

  // ------------------------------------------------------------------------------- categories, suppliers, customers

  category(id) {
    return readCategory(this.db, id);
  }

  /** CategoriesActivity: a new category, or the name and colour of one. */
  saveCategory(shown, name, color) {
    const fields = (c) => ({ name: c.name, name_key: searchKey(c.name), color: BigInt(c.color ?? 0), sort: BigInt(c.sort ?? 0) });
    if (!shown) {
      const id = this.nextId();
      this.lwwInsert('category', Entity.CATEGORY, id, fields({ name, color, sort: 0 }));
      return id;
    }
    const current = this.category(shown.id);
    if (!current || current.deleted) throw new EditError('GONE');
    this.lwwUpdate('category', Entity.CATEGORY, current.id, lwwChanges(fields(shown), fields({ ...shown, name, color }), fields(current)));
    return current.id;
  }

  deleteCategory(id) {
    const c = this.category(id);
    if (!c || c.deleted) throw new EditError('GONE');
    this.lwwDelete('category', Entity.CATEGORY, c.id);
  }

  static supplierFields(s) {
    return {
      name: s.name, name_key: searchKey(s.name), contact: s.contact ?? null, phone: s.phone ?? null, email: s.email ?? null,
      address: s.address ?? null, note: s.note ?? null,
    };
  }

  /** SupplierDao: a new supplier, or the fields that changed from what the form showed. */
  saveSupplier(shown, edited) {
    if (!shown) {
      const id = this.nextId();
      this.lwwInsert('supplier', Entity.SUPPLIER, id, Writer.supplierFields(edited));
      return id;
    }
    if (!this.value('SELECT 1 FROM supplier WHERE id = ? AND deleted = 0', [BigInt(shown.id)])) throw new EditError('GONE');
    const old = Writer.supplierFields(shown);
    const changes = {};
    for (const [k, v] of Object.entries(Writer.supplierFields(edited))) if (!same(old[k], v)) changes[k] = v;
    if (Object.keys(changes).length) this.lwwUpdate('supplier', Entity.SUPPLIER, BigInt(shown.id), changes);
    return BigInt(shown.id);
  }

  deleteSupplier(id) {
    if (!this.value('SELECT 1 FROM supplier WHERE id = ? AND deleted = 0', [BigInt(id)])) throw new EditError('GONE');
    this.lwwDelete('supplier', Entity.SUPPLIER, BigInt(id));
  }

  static customerFields(c) {
    return {
      name: c.name, name_key: searchKey(c.name), phone: normalizePhone(c.phone), email: c.email ?? null, address: c.address ?? null,
      tin: c.tin ?? null, note: c.note ?? null, credit_limit: BigInt(c.creditLimit ?? 0),
    };
  }

  /**
   * CustomerService.save: a new customer or the fields that changed; a changed credit limit is on record
   * (CREDIT_LIMIT_CHANGE, "none" for no limit). The caller checks who may change the limit.
   */
  saveCustomer(shown, edited) {
    let id;
    if (!shown) {
      id = this.nextId();
      this.lwwInsert('customer', Entity.CUSTOMER, id, Writer.customerFields(edited));
    } else {
      id = BigInt(shown.id);
      if (!this.value('SELECT 1 FROM customer WHERE id = ? AND deleted = 0', [id])) throw new EditError('GONE');
      const old = Writer.customerFields(shown);
      const changes = {};
      for (const [k, v] of Object.entries(Writer.customerFields(edited))) if (!same(old[k], v)) changes[k] = v;
      if (Object.keys(changes).length) this.lwwUpdate('customer', Entity.CUSTOMER, id, changes);
    }
    const oldLimit = BigInt(shown?.creditLimit ?? 0);
    const newLimit = BigInt(edited.creditLimit ?? 0);
    if (newLimit !== oldLimit) {
      const text = (v) => (v === 0n ? 'none' : plain(v, this.currency.decimals));
      this.audit(AuditAction.CREDIT_LIMIT_CHANGE, Entity.CUSTOMER, id, newLimit, `${edited.name}: ${text(oldLimit)} → ${text(newLimit)}`);
    }
    return id;
  }

  /** CustomerService.delete: only a customer who owes nothing (their history stays). */
  deleteCustomer(id) {
    id = BigInt(id);
    if (!this.value('SELECT 1 FROM customer WHERE id = ? AND deleted = 0', [id])) throw new EditError('GONE');
    const balance = big(this.value('SELECT balance FROM customer_balance WHERE customer_id = ?', [id])) ?? 0n;
    if (balance !== 0n) throw new EditError('HAS_BALANCE');
    this.lwwDelete('customer', Entity.CUSTOMER, id);
  }
}


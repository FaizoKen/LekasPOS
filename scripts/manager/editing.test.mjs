// Who may change the shop from the web page (D-083): the owner while no one has a PIN; once PINs are used, a staff
// member signs in with theirs (the till's waits after wrong PINs, both on record) and their role decides what they
// may change. A sign-in ends after a while without a change.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { memoryDb, MemoryFolder, idOf, hlcOf } from './helpers.mjs';
import { Store } from '../../site/manager/js/store.js';
import { Editing, IDLE_MS } from '../../site/manager/js/editing.js';
import { Perm, AuditAction } from '../../site/manager/js/rules.js';

const STORE = 'st0re-uuid';
const A = 3_000_001;
const T0 = Date.UTC(2026, 9, 19, 2, 0);
const h = (n) => hlcOf(T0 + n * 1000);
const MYR = { symbol: 'RM', decimals: 2 };
const lww = (entity, id, hlc, dev, f) => ({ e: entity, o: 1, r: id, h: hlc, p: { id, hlc, dev: BigInt(dev), f } });
const CASHIER_ROLE = 3n;
const SITI = idOf(A, 20);
const ALI = idOf(A, 21);
const CUS = idOf(A, 6);
const P1 = idOf(A, 2);

/** A PIN record as PinHash makes it (fewer rounds: a test). */
async function pinRecord(pin) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(pin), 'PBKDF2', false, ['deriveBits']);
  const bits = new Uint8Array(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: 1000 }, key, 256));
  const hex = (b) => [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `p2:1000:${hex(salt)}:${hex(bits)}`;
}

async function shop({ pins }) {
  const folder = new MemoryFolder();
  folder.putJson(`store-${STORE}.json`, { store: STORE });
  folder.putJson(`dev-${STORE}-${A}.json`, { dev: BigInt(A), uuid: 'a', name: 'Counter A', app: '1.20.2', lastSeen: BigInt(T0), lastSeq: 1n, prefix: 'AA-' });
  const events = [
    lww(2, 1n, 0n, 0, { name: 'Owner', sys_role: 1n, perms: 0n, deleted: 0n, created_at: 1n }),
    // The cashier role: customers, but not products, stock or credit limits.
    lww(2, CASHIER_ROLE, 0n, 0, { name: 'Cashier', sys_role: 3n, perms: Perm.CUSTOMERS, deleted: 0n, created_at: 1n }),
    lww(3, 1n, 0n, 0, { name: 'Owner', role_id: 1n, active: 1n, deleted: 0n, created_at: 1n }),
    lww(5, idOf(A, 1), h(1), A, { name: 'Drinks', name_key: 'drinks', color: 0n, sort: 0n, created_at: BigInt(T0) }),
    lww(6, P1, h(2), A, { name: 'Milo', name_key: 'milo', price: 1890n, cost: 1200n, track_stock: 1n, created_at: BigInt(T0) }),
    lww(9, CUS, h(3), A, { name: 'Ah Kow', name_key: 'ah kow', credit_limit: 0n, created_at: BigInt(T0) }),
  ];
  if (pins) {
    events.push(
      lww(3, 1n, h(4), A, { pin_hash: await pinRecord('9081') }),
      lww(3, SITI, h(5), A, { name: 'Siti', role_id: CASHIER_ROLE, active: 1n, pin_hash: await pinRecord('2468'), created_at: BigInt(T0) }),
      lww(3, ALI, h(6), A, { name: 'Ali', role_id: CASHIER_ROLE, active: 0n, pin_hash: await pinRecord('1357'), created_at: BigInt(T0) }),
    );
  }
  folder.putSegment(STORE, A, 1, events);
  const db = await memoryDb();
  const store = new Store(db);
  store.createForDrive();
  await store.sync(folder);
  let now = Date.now();
  const editing = new Editing(store, () => now);
  return { folder, db, store, editing, tick: (ms) => { now += ms; } };
}

const opts = { currency: MYR, deviceName: 'LekasPOS Manager (test)' };
const log = (db) => db.selectArrays('SELECT action, staff_id, entity_id, detail FROM audit_log ORDER BY hlc, id').map((r) => r.map(String).join('|'));

test('while no one has a PIN, changes are the owner\'s, with every permission', async () => {
  const { folder, db, editing } = await shop({ pins: false });
  const a = editing.access();
  assert.equal(a.canEdit, true);
  assert.equal(a.loginRequired, false);
  assert.deepEqual([a.who.id, a.who.name, a.who.signedIn], ['1', 'Owner', false]);
  await editing.run(folder, 'saveCategory', { shown: null, name: 'Snek', color: 3 }, opts);
  await editing.run(folder, 'setStock', { productId: String(P1), qty: 4000n }, opts);
  assert.equal(String(db.selectValue("SELECT staff_id FROM stock_count WHERE qty = 4000")), '1');
  assert.equal(Number(db.selectValue("SELECT COUNT(*) FROM category WHERE name = 'Snek'")), 1);
});

test('with PINs: nothing until someone signs in; their role decides; the sign-in goes on record with the first change', async () => {
  const { folder, db, editing } = await shop({ pins: true });
  const a = editing.access();
  assert.equal(a.loginRequired, true);
  assert.equal(a.who, null);
  assert.deepEqual(a.staff.map((s) => s.name), ['Owner', 'Siti'], 'only active staff with a PIN');
  await assert.rejects(editing.run(folder, 'saveCategory', { shown: null, name: 'X', color: 0 }, opts), { code: 'PIN_NEEDED' });
  assert.deepEqual(await editing.signIn(String(ALI), '1357'), { notAllowed: true }, 'not active');
  assert.deepEqual(await editing.signIn(String(SITI), '2468'), { ok: true });
  assert.equal(editing.access().who.name, 'Siti');
  // A cashier: customers yes, products and stock no, a credit limit no.
  await assert.rejects(editing.run(folder, 'saveCategory', { shown: null, name: 'X', color: 0 }, opts), { code: 'NOT_ALLOWED' });
  await assert.rejects(editing.run(folder, 'setStock', { productId: String(P1), qty: 1000n }, opts), { code: 'NOT_ALLOWED' });
  const shown = { id: CUS, name: 'Ah Kow', phone: null, email: null, address: null, tin: null, note: null, creditLimit: 0n };
  await assert.rejects(editing.run(folder, 'saveCustomer', { shown, edited: { ...shown, creditLimit: 10000n } }, opts), { code: 'NOT_ALLOWED' });
  assert.equal(Number(db.selectValue('SELECT COUNT(*) FROM audit_log')), 0, 'nothing sent yet');
  await editing.run(folder, 'saveCustomer', { shown, edited: { ...shown, phone: '012-3456789' } }, opts);
  assert.equal(db.selectValue('SELECT phone FROM customer WHERE id = ?', [CUS]), '0123456789');
  assert.deepEqual(log(db), [`${AuditAction.SIGN_IN}|${SITI}|${SITI}|null`]);
  assert.equal(String(db.selectValue('SELECT staff_id FROM audit_log')), String(SITI));
  // The owner signs in instead: everything
  editing.signOut();
  assert.equal(editing.access().who, null);
  assert.equal((await editing.signIn('1', '9081')).ok, true);
  await editing.run(folder, 'saveCustomer', { shown: { ...shown, phone: '0123456789' }, edited: { ...shown, phone: '0123456789', creditLimit: 10000n } }, opts);
  assert.equal(db.selectValue('SELECT detail FROM audit_log WHERE action = ?', [AuditAction.CREDIT_LIMIT_CHANGE]), 'Ah Kow: none → 100.00');
});

test('wrong PINs: tries left, then waits; each from the 5th is on record; the clock set back does not stretch the wait', async () => {
  const { folder, db, editing, tick } = await shop({ pins: true });
  for (let i = 1; i <= 4; i++) assert.deepEqual(await editing.signIn(String(SITI), '0000'), { wrong: 5 - i, waitMs: 0 });
  assert.deepEqual(await editing.signIn(String(SITI), '0000'), { wrong: 0, waitMs: 30_000 });
  const w = await editing.signIn(String(SITI), '2468');
  assert.ok(w.wait > 0 && w.wait <= 30_000, 'the right PIN waits too');
  tick(31_000);
  assert.deepEqual(await editing.signIn(String(SITI), '0000'), { wrong: 0, waitMs: 60_000 });
  tick(-3_600_000); // the computer's clock set back an hour
  const back = await editing.signIn(String(SITI), '2468');
  assert.ok(back.wait > 0 && back.wait <= 60_000, `waits at most the full wait: ${back.wait}`);
  // On record (the till's PIN_LOCKOUT), sent on their own when nothing else is changed
  await editing.flush(folder, opts);
  assert.deepEqual(log(db), [`${AuditAction.PIN_LOCKOUT}|null|${SITI}|5 wrong PINs`, `${AuditAction.PIN_LOCKOUT}|null|${SITI}|6 wrong PINs`]);
  tick(61_000);
  assert.equal((await editing.signIn(String(SITI), '2468')).ok, true);
  assert.equal(editing.fails(String(SITI)).n, 0, 'the count starts again');
});

test('a sign-in ends after a while without a change, or when the person may no longer sign in', async () => {
  const { folder, editing, tick } = await shop({ pins: true });
  assert.equal((await editing.signIn('1', '9081')).ok, true);
  tick(IDLE_MS - 1000);
  await editing.run(folder, 'saveCategory', { shown: null, name: 'Snek', color: 0 }, opts); // a change keeps it going
  tick(IDLE_MS - 1000);
  assert.equal(editing.access().who.name, 'Owner');
  tick(2000);
  assert.equal(editing.access().who, null);
  // Siti signed in; a till then makes her inactive: her next change is refused
  assert.equal((await editing.signIn(String(SITI), '2468')).ok, true);
  folder.putSegment(STORE, A, 2, [lww(3, SITI, hlcOf(Date.now()), A, { active: 0n })]);
  const shown = { id: CUS, name: 'Ah Kow', phone: null, email: null, address: null, tin: null, note: null, creditLimit: 0n };
  await editing.store.sync(folder);
  await assert.rejects(editing.run(folder, 'saveCustomer', { shown, edited: { ...shown, note: 'x' } }, opts), { code: 'PIN_NEEDED' });
});

test('a change that fails to go out keeps the sign-in entry for the next one; opening stock needs the stock permission', async () => {
  const { folder, db, editing } = await shop({ pins: true });
  assert.equal((await editing.signIn('1', '9081')).ok, true);
  folder.failNext = 'before';
  await assert.rejects(editing.run(folder, 'saveCategory', { shown: null, name: 'Snek', color: 0 }, opts));
  await editing.run(folder, 'saveCategory', { shown: null, name: 'Snek', color: 0 }, opts);
  assert.deepEqual(log(db), [`${AuditAction.SIGN_IN}|1|1|null`]);
  // A role with products but not stock: a new product without opening stock only
  db.exec({ sql: 'UPDATE role SET perms = ? WHERE id = ?', bind: [Perm.MANAGE_PRODUCTS | Perm.CUSTOMERS, CASHIER_ROLE] });
  editing.signOut();
  assert.equal((await editing.signIn(String(SITI), '2468')).ok, true);
  const edited = { name: 'Kopi', sku: null, categoryId: null, unit: 'pcs', sellMode: 0, price: 250n, cost: 0n, taxRateId: null, trackStock: true, lowStock: 0n, active: true };
  await assert.rejects(editing.run(folder, 'saveProduct', { shown: null, edited, opening: 5000n }, opts), { code: 'NOT_ALLOWED' });
  await editing.run(folder, 'saveProduct', { shown: null, edited, opening: 0n }, opts);
  assert.equal(Number(db.selectValue("SELECT COUNT(*) FROM product WHERE name = 'Kopi'")), 1);
});

// Who changes the shop from the web page, and what they may change (D-083): the shop's own staff, PINs and roles,
// as on a till. While no one has a PIN, changes are the owner's (StaffSession.staffId, D-037); once someone has
// one, a staff member signs in here with theirs (PinHash, the till's waits after wrong PINs) and their role decides
// (Perm). A sign-in is held in memory only and ends after IDLE_MS without a change. Each change is one Writer action
// sent as one sync file (Store.commit). Runs in the worker and in the Node tests.

import { Perm, permEffective, permHas, pinVerify, PinLockout, STAFF_OWNER, SysRole, AuditAction, Entity, reason } from './rules.js';
import { EditError } from './writer.js';

/** A sign-in ends after this long without a change (the till locks itself after a while too). */
export const IDLE_MS = 30 * 60_000;

/** The changes the page makes: what each needs and how its Writer action is called. */
const ACTIONS = {
  saveProduct: {
    perm: Perm.MANAGE_PRODUCTS,
    // Stock is set by whoever may receive, change and count it (ProductEditActivity: the opening stock).
    extra: (a) => (BigInt(a.opening ?? 0) > 0n ? Perm.MANAGE_STOCK : 0n),
    run: (w, a) => w.saveProduct(a),
  },
  deleteProduct: { perm: Perm.MANAGE_PRODUCTS, run: (w, a) => w.deleteProduct(BigInt(a.id)) },
  saveCategory: { perm: Perm.MANAGE_PRODUCTS, run: (w, a) => w.saveCategory(a.shown, a.name, a.color) },
  deleteCategory: { perm: Perm.MANAGE_PRODUCTS, run: (w, a) => w.deleteCategory(BigInt(a.id)) },
  reorderCategories: { perm: Perm.MANAGE_PRODUCTS, run: (w, a) => w.reorderCategories(a.ids.map((x) => BigInt(x))) },
  setStock: { perm: Perm.MANAGE_STOCK, run: (w, a) => w.setStock(BigInt(a.productId), BigInt(a.qty)) },
  adjustStock: {
    perm: Perm.MANAGE_STOCK,
    run: (w, a) => {
      const r = reason(a.reason);
      if (!r) throw new EditError('REASON', 'reason');
      return w.adjustStock(BigInt(a.productId), r, BigInt(a.qty), !!a.removing, a.note);
    },
  },
  receive: { perm: Perm.MANAGE_STOCK, run: (w, a) => w.receive(a) },
  saveSupplier: { perm: Perm.MANAGE_STOCK, run: (w, a) => w.saveSupplier(a.shown, a.edited) },
  deleteSupplier: { perm: Perm.MANAGE_STOCK, run: (w, a) => w.deleteSupplier(BigInt(a.id)) },
  saveCustomer: {
    perm: Perm.CUSTOMERS,
    // A new or changed credit limit needs CREDIT_LIMIT (CustomerService.save).
    extra: (a) => (BigInt(a.edited.creditLimit ?? 0) !== BigInt(a.shown?.creditLimit ?? 0) ? Perm.CREDIT_LIMIT : 0n),
    run: (w, a) => w.saveCustomer(a.shown, a.edited),
  },
  deleteCustomer: { perm: Perm.CUSTOMERS, run: (w, a) => w.deleteCustomer(BigInt(a.id)) },
};

export class Editing {
  /** [store]: the page's Store; [clock]: Date.now in the tests' hands. */
  constructor(store, clock = () => Date.now()) {
    this.store = store;
    this.clock = clock;
    this.session = null; // {id, at}
    this.pending = []; // activity-log entries that go with the next change: {action, staffId, entityId, detail, at}
  }

  get db() {
    return this.store.db;
  }

  /** PIN sign-in is on as soon as one active staff member has a PIN (StaffDao.loginRequired). */
  loginRequired() {
    return !!this.db.selectValue('SELECT 1 FROM staff WHERE deleted = 0 AND active = 1 AND pin_hash IS NOT NULL LIMIT 1');
  }

  /** A staff member with their role's permissions: {id, name, role, pin, perms, owner, canSignIn}, or null. */
  staff(id) {
    const r = this.db.selectArray('SELECT s.id, s.name, s.pin_hash, s.active, s.deleted, r.name, r.sys_role, r.perms ' +
      'FROM staff s LEFT JOIN role r ON r.id = s.role_id WHERE s.id = ?', [BigInt(id)]);
    if (!r) return null;
    const perms = permEffective(r[6] ?? 0, r[7] ?? 0);
    return {
      id: BigInt(r[0]), name: r[1], pin: r[2] ?? null, role: r[5] ?? '', perms, owner: Number(r[6] ?? 0) === SysRole.OWNER,
      canSignIn: r[2] != null && Number(r[3]) !== 0 && Number(r[4]) === 0,
    };
  }

  /** Who may sign in here: active staff with a PIN, by name. */
  signInList() {
    return this.db.selectArrays('SELECT s.id, s.name, r.name FROM staff s LEFT JOIN role r ON r.id = s.role_id ' +
      'WHERE s.deleted = 0 AND s.active = 1 AND s.pin_hash IS NOT NULL ORDER BY s.name COLLATE NOCASE, s.id')
      .map(([id, name, role]) => ({ id: String(id), name, role: role ?? '' }));
  }

  /**
   * Who makes a change now: the signed-in staff member (their role as it is now: a role changed on a till applies at
   * once), the owner while no one has a PIN, or null. A sign-in idle too long, or of someone who can no longer sign
   * in, ends here.
   */
  current() {
    if (!this.loginRequired()) {
      const owner = this.staff(STAFF_OWNER);
      return { id: STAFF_OWNER, name: owner?.name ?? 'Owner', perms: Perm.ALL, owner: true, signedIn: false };
    }
    const s = this.session;
    if (!s) return null;
    const who = this.staff(s.id);
    if (!who?.canSignIn || this.clock() - s.at > IDLE_MS) {
      this.session = null;
      return null;
    }
    return { id: who.id, name: who.name, perms: who.perms, owner: who.owner, signedIn: true };
  }

  allowed(perm) {
    const who = this.current();
    return !!who && permHas(who.perms, perm);
  }

  /** For the page: whether it can change the shop, who would, and who may sign in. Permissions as text (BigInt). */
  access() {
    const canEdit = this.store.meta('web.source') === 'drive' && this.store.check() === 'ok';
    const who = canEdit ? this.current() : null;
    return {
      canEdit,
      loginRequired: canEdit && this.loginRequired(),
      who: who ? { id: String(who.id), name: who.name, perms: String(who.perms), owner: who.owner, signedIn: who.signedIn } : null,
      staff: canEdit ? this.signInList() : [],
    };
  }

  fails(staffId) {
    const n = Number(this.store.meta(`web.pin.fails.${staffId}`) ?? 0);
    const last = Number(this.store.meta(`web.pin.last.${staffId}`) ?? 0);
    return { n, last };
  }

  /**
   * Checks [pin] of [staffId] and signs them in (StaffSession.check / signIn): {ok: true}, {wrong: triesLeft, wait},
   * {wait: ms} while wrong PINs make them wait, or {notAllowed: true}. Wrong PINs are counted in this browser; from the
   * 5th on, each is on record in the activity log with the next change (the till's PIN_LOCKOUT).
   */
  async signIn(staffId, pin) {
    const now = this.clock();
    const f = this.fails(staffId);
    if (f.n > 0 && now < f.last) {
      // The clock was set back after the last wrong PIN: the wait counts from now (StaffSession.check).
      this.store.setMeta(`web.pin.last.${staffId}`, now);
      f.last = now;
    }
    const wait = PinLockout.remainingMs(f.n, f.last, now);
    if (wait > 0) return { wait };
    const who = this.staff(staffId);
    if (!who?.canSignIn) return { notAllowed: true };
    if (await pinVerify(String(pin ?? ''), who.pin)) {
      if (f.n !== 0) this.store.setMeta(`web.pin.fails.${staffId}`, null);
      this.store.setMeta(`web.pin.last.${staffId}`, null);
      this.session = { id: who.id, at: now };
      this.pending.push({ action: AuditAction.SIGN_IN, staffId: who.id, entityId: who.id, detail: null, at: now });
      return { ok: true };
    }
    const n = f.n + 1;
    this.store.setMeta(`web.pin.fails.${staffId}`, n);
    this.store.setMeta(`web.pin.last.${staffId}`, now);
    if (n >= PinLockout.FREE_TRIES) {
      // Whose PIN, by name, as on a till (StaffSession: "5 wrong PINs for Siti").
      this.pending.push({ action: AuditAction.PIN_LOCKOUT, staffId: this.session?.id ?? null, entityId: who.id, detail: `${n} wrong PINs for ${who.name}`.slice(0, 300), at: now });
    }
    return { wrong: PinLockout.triesLeft(n), waitMs: PinLockout.waitMs(n) };
  }

  signOut() {
    this.session = null;
  }

  /** Forgets everything about the person (another shop, or the shop forgotten on this computer). */
  reset() {
    this.session = null;
    this.pending = [];
  }

  /**
   * Makes change [action] with [args] as the person signed in (or the owner while there are no PINs): checked against
   * their role, then one sync file (Store.commit). Activity-log entries waiting (a sign-in) go in the same file, before
   * the change. [opts]: {currency, deviceName, progress}. Returns {result, events, seq}.
   */
  async run(provider, action, args, opts = {}) {
    const def = ACTIONS[action];
    if (!def) throw new EditError('UNKNOWN');
    const who = this.current();
    if (!who) throw new EditError('PIN_NEEDED');
    const need = def.perm | (def.extra ? def.extra(args) : 0n);
    if (!permHas(who.perms, need)) throw new EditError('NOT_ALLOWED');
    const waiting = this.pending;
    this.pending = [];
    try {
      const r = await this.store.commit(provider, (w) => {
        for (const a of waiting) w.audit(a.action, Entity.STAFF, a.entityId, null, a.detail, { staffId: a.staffId, at: a.at });
        return def.run(w, args);
      }, { ...opts, staffId: who.id });
      if (this.session) this.session.at = this.clock();
      return r;
    } catch (e) {
      this.pending = waiting.concat(this.pending); // offline: on record with the next change that goes through
      throw e;
    }
  }

  /** Sends the activity-log entries waiting (wrong PINs) on their own, when nothing else is changed. */
  async flush(provider, opts = {}) {
    if (this.pending.length === 0) return null;
    const waiting = this.pending;
    this.pending = [];
    try {
      return await this.store.commit(provider, (w) => {
        for (const a of waiting) w.audit(a.action, Entity.STAFF, a.entityId, null, a.detail, { staffId: a.staffId, at: a.at });
      }, { ...opts, staffId: STAFF_OWNER });
    } catch (e) {
      this.pending = waiting.concat(this.pending);
      throw e;
    }
  }
}

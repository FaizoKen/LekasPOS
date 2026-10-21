// The app's own rules that a change made on the web page must follow, ported from :core so a product, a
// barcode, an amount or a stock change typed here is stored exactly as a till stores it: search keys
// (SearchText), barcode forms (Gtin), amounts and quantities (MoneyFormat), stock reasons (AdjustReason),
// costs (CostMath, Rounding), PINs (PinHash, PinLockout), permissions (Perm) and the clock (Hlc). Each is
// tested with the app's own test cases (scripts/manager/rules.test.mjs). Amounts are BigInt minor units.

// ----------------------------------------------------------------------------------- codes (core/model/Codes.kt)

export const Entity = Object.freeze({
  SETTING: 1, ROLE: 2, STAFF: 3, TAX_RATE: 4, CATEGORY: 5, PRODUCT: 6, BARCODE: 7, SUPPLIER: 8, CUSTOMER: 9,
  PAYMENT_METHOD: 10, SHIFT: 11, COUNT_SESSION: 12, PROMOTION: 13, PRODUCT_LOOK: 14, PROMOTION_PRICE: 15,
  SALE: 20, SALE_VOID: 21, STOCK_MOVE: 22, STOCK_COUNT: 23, PURCHASE: 24, CASH_MOVE: 25, CREDIT: 26, AUDIT: 27,
  PRODUCT_IMAGE: 28,
});

export const EventOp = Object.freeze({ LWW: 1, INSERT: 2 });

export const SellMode = Object.freeze({ UNIT: 0, WEIGHT: 1, OPEN_PRICE: 2 });

export const BarcodeKind = Object.freeze({ BARCODE: 0, SCALE_PLU: 1 });

export const MovementKind = Object.freeze({
  RECEIVE: 1, ADJUST: 2, WASTE: 3, RETURN_TO_SUPPLIER: 4, TRANSFER_IN: 5, TRANSFER_OUT: 6, OPENING: 7,
});

export const SysRole = Object.freeze({ NONE: 0, OWNER: 1, MANAGER: 2, CASHIER: 3 });

export const AuditAction = Object.freeze({
  PRODUCT_PRICE_CHANGE: 8, PRODUCT_DELETE: 9, SIGN_IN: 12, PIN_LOCKOUT: 13, CREDIT_LIMIT_CHANGE: 26, STOCK_WRITE_OFF: 32,
});

/** Tile colours (TileColor): 1–12, 0 = none; the shades of ui/common/TileColors.kt. */
export const TILE_COLORS = Object.freeze([
  [1, '#C62828'], [2, '#AD1457'], [3, '#6A1B9A'], [4, '#283593'], [5, '#1565C0'], [6, '#00695C'],
  [7, '#2E7D32'], [8, '#4D7C0F'], [9, '#A14A00'], [10, '#C2410C'], [11, '#6D4C41'], [12, '#455A64'],
]);

/** The seed staff row "Owner": who acts while no one has a PIN (Seed.Ids.STAFF_OWNER, D-037). */
export const STAFF_OWNER = 1n;

// ----------------------------------------------------------------------------------- permissions (Perm)

const bit = (n) => 1n << BigInt(n);
export const Perm = Object.freeze({
  VOID: bit(0), MANAGE_PRODUCTS: bit(6), SETTINGS: bit(7), MANAGE_STOCK: bit(9), MANAGE_STAFF: bit(10),
  CUSTOMERS: bit(14), CREDIT_LIMIT: bit(16), VOID_LAST: bit(18), ALL: -1n,
});

/** What a role may do: the owner role everything, others their bits with what they include (Perm.effective). */
export function permEffective(sysRole, perms) {
  if (Number(sysRole) === SysRole.OWNER) return Perm.ALL;
  const p = BigInt(perms ?? 0);
  return (p & Perm.VOID) === Perm.VOID ? p | Perm.VOID_LAST : p;
}

export const permHas = (perms, perm) => (BigInt(perms) & perm) === perm;

// ----------------------------------------------------------------------------------- search keys (SearchText.key)

const isCjk = (cp) => (cp >= 0x3040 && cp <= 0x30ff) || (cp >= 0x3400 && cp <= 0x4dbf) || (cp >= 0x4e00 && cp <= 0x9fff) ||
  (cp >= 0xac00 && cp <= 0xd7af) || (cp >= 0xf900 && cp <= 0xfaff) || (cp >= 0x20000 && cp <= 0x2ffff);
const LETTER_OR_DIGIT = /^[\p{L}\p{Nd}]$/u; // Character.isLetterOrDigit
const MARK = /^\p{M}$/u;

/**
 * The `*_key` column of a name: Latin accents removed, lowercased, letters, digits and marks kept, anything else a
 * separator, CJK one token per character. Stored with every product, category, supplier and customer.
 */
export function searchKey(input) {
  if (!input) return '';
  let out = '';
  let pendingSpace = false;
  for (const ch of input.normalize('NFD')) {
    const cp = ch.codePointAt(0);
    if (cp >= 0x300 && cp <= 0x36f) continue; // Latin combining diacritics
    if (isCjk(cp)) {
      if (out) out += ' ';
      out += ch;
      pendingSpace = true;
    } else if (LETTER_OR_DIGIT.test(ch) || MARK.test(ch)) {
      if (pendingSpace && out) out += ' ';
      pendingSpace = false;
      out += ch.toLowerCase();
    } else {
      pendingSpace = true;
    }
  }
  return out;
}

// ----------------------------------------------------------------------------------- barcodes (Gtin)

const DIGITS = /^[0-9]+$/;

export function gtinCheckDigit(digits) {
  let sum = 0;
  let weight3 = true;
  for (let i = digits.length - 1; i >= 0; i--) {
    const d = digits.charCodeAt(i) - 48;
    if (d < 0 || d > 9) throw new Error(`not a digit: ${digits[i]}`);
    sum += weight3 ? d * 3 : d;
    weight3 = !weight3;
  }
  return (10 - (sum % 10)) % 10;
}

export function gtinIsValid(code) {
  if (![8, 12, 13, 14].includes(code.length) || !DIGITS.test(code)) return false;
  return gtinCheckDigit(code.slice(0, -1)) === code.charCodeAt(code.length - 1) - 48;
}

const ND = /^\p{Nd}$/u;

/** The value of a decimal digit of any script (Character.digit(c, 10)); Unicode keeps each script's 0–9 in a run. */
function digitValue(ch) {
  if (!ND.test(ch)) return -1;
  let cp = ch.codePointAt(0);
  let run = 0;
  while (ND.test(String.fromCodePoint(cp - 1))) {
    cp--;
    run++;
  }
  return run % 10;
}

/** Other scripts' digits (Arabic-Indic, Bengali, full-width …) as 0–9 (Gtin.asciiDigits). */
export function asciiDigits(text) {
  if (!/[^\x00-\x7f]/.test(text)) return text;
  let out = '';
  for (const ch of text) {
    const d = ch.codePointAt(0) >= 0x80 ? digitValue(ch) : -1;
    out += d >= 0 ? String(d) : ch;
  }
  return out;
}

export function upcEToUpcA(code) {
  if (code.length !== 8 || !DIGITS.test(code) || (code[0] !== '0' && code[0] !== '1')) return null;
  const d = code.slice(1, 7);
  let mid;
  switch (d[5]) {
    case '0': case '1': case '2': mid = d.slice(0, 2) + d[5] + '0000' + d.slice(2, 5); break;
    case '3': mid = d.slice(0, 3) + '00000' + d.slice(3, 5); break;
    case '4': mid = d.slice(0, 4) + '00000' + d[4]; break;
    default: mid = d.slice(0, 5) + '0000' + d[5];
  }
  const upcA = code[0] + mid + code[7];
  return gtinIsValid(upcA) ? upcA : null;
}

export function upcAToUpcE(code) {
  if (code.length !== 12 || !DIGITS.test(code) || (code[0] !== '0' && code[0] !== '1') || !gtinIsValid(code)) return null;
  const m = code.slice(1, 6);
  const p = code.slice(6, 11);
  let six;
  if (['000', '100', '200'].includes(m.slice(2)) && p.startsWith('00')) six = m.slice(0, 2) + p.slice(2) + m[2];
  else if (m.endsWith('00') && p.startsWith('000')) six = m.slice(0, 3) + p.slice(3) + '3';
  else if (m.endsWith('0') && p.startsWith('0000')) six = m.slice(0, 4) + p[4] + '4';
  else if (p.startsWith('0000') && p[4] >= '5' && p[4] <= '9') six = m + p[4];
  else return null;
  const upcE = code[0] + six + code[11];
  return upcEToUpcA(upcE) === code ? upcE : null;
}

/** Codes a scan of [raw] may be stored as (Gtin.lookupVariants): UPC-A/EAN-13/GTIN-14 and UPC-E forms. */
export function gtinLookupVariants(raw) {
  const code = asciiDigits(raw.trim());
  if (!code) return [];
  if (!DIGITS.test(code)) return [code];
  let forms;
  switch (code.length) {
    case 7: case 11: forms = gtinIsValid(`0${code}`) ? [code, `0${code}`] : [code]; break;
    case 12: forms = [code, `0${code}`]; break;
    case 13: forms = code[0] === '0' ? [code, code.slice(1)] : [code, `0${code}`]; break;
    case 14: forms = code[0] === '0' ? [code, code.slice(1)] : [code]; break;
    default: forms = [code];
  }
  let upcE = null;
  if (code.length === 8) { const a = upcEToUpcA(code); upcE = a ? [a, `0${a}`] : null; }
  if (code.length === 12) { const e = upcAToUpcE(code); upcE = e ? [e] : null; }
  if (code.length === 13 && code[0] === '0') { const e = upcAToUpcE(code.slice(1)); upcE = e ? [e] : null; }
  return upcE ? [...new Set([...forms, ...upcE])] : forms;
}

function restoreLeadingZero(code) {
  if ((code.length === 7 || code.length === 11) && DIGITS.test(code) && gtinIsValid(`0${code}`)) return `0${code}`;
  return code;
}

/** The form a barcode is stored in (Gtin.canonical): trimmed, EAN/UPC digits without their spaces, GTIN-14 as EAN-13. */
export function gtinCanonical(raw) {
  const code = asciiDigits(raw.trim());
  if (!code) return code;
  const digits = /[ -]/.test(code) ? code.replace(/[ -]/g, '') : code;
  if (digits !== code && !(DIGITS.test(digits) && gtinIsValid(digits))) return code;
  if (digits.length === 14 && digits[0] === '0' && gtinIsValid(digits)) return digits.slice(1);
  return restoreLeadingZero(digits);
}

// ----------------------------------------------------------------------------------- amounts (MoneyFormat)

const LONG_MAX = (1n << 63n) - 1n;

function parseUnsigned(s, decimals, decimalSep, groupSep) {
  if (!s) return null;
  const dot = s.indexOf(decimalSep);
  if (dot !== s.lastIndexOf(decimalSep)) return null;
  const intPart = dot >= 0 ? s.slice(0, dot) : s;
  const fracPart = dot >= 0 ? s.slice(dot + 1) : '';
  if (!intPart && !fracPart) return null;
  if (fracPart.length > decimals) return null;
  if (fracPart && !DIGITS.test(fracPart)) return null;
  let intDigits = intPart;
  if (groupSep != null && intPart.includes(groupSep)) {
    const groups = intPart.split(groupSep);
    if (!groups[0] || groups[0].length > 3) return null;
    for (let i = 1; i < groups.length; i++) if (groups[i].length !== 3) return null;
    intDigits = groups.join('');
  }
  if (intDigits && !DIGITS.test(intDigits)) return null;
  const v = BigInt(intDigits || '0') * 10n ** BigInt(decimals) + BigInt((fracPart || '').padEnd(decimals, '0') || '0');
  return v > LONG_MAX ? null : v;
}

/** User text such as "12.5", "RM 1,234.50", "-3" in minor units, or null (MoneyFormat.parse). */
export function parseMoney(text, currency) {
  let s = String(text ?? '').trim();
  if (!s) return null;
  let negative = false;
  if (s.startsWith('-')) { negative = true; s = s.slice(1).trim(); }
  const sym = currency.symbol ?? '';
  if (sym) {
    if (s.toLowerCase().startsWith(sym.toLowerCase())) s = s.slice(sym.length).trim();
    else if (s.toLowerCase().endsWith(sym.toLowerCase())) s = s.slice(0, s.length - sym.length).trim();
  }
  if (s.startsWith('-') && !negative) { negative = true; s = s.slice(1).trim(); }
  const v = parseUnsigned(s, currency.decimals, '.', ',');
  return v == null ? null : negative ? -v : v;
}

/** "3", "0.253", "1.5" in milli-units; more than 3 decimals → null (MoneyFormat.parseQty). */
export function parseQty(text) {
  let s = String(text ?? '').trim();
  let negative = false;
  if (s.startsWith('-')) { negative = true; s = s.slice(1); }
  const v = parseUnsigned(s, 3, '.', null);
  return v == null ? null : negative ? -v : v;
}

/** Phone numbers as digits and a leading + (CustomerDao.normalizePhone). */
export function normalizePhone(text) {
  if (text == null) return null;
  const t = String(text).trim();
  const digits = t.replace(/[^0-9]/g, '');
  if (!digits) return null;
  return t.startsWith('+') ? `+${digits}` : digits;
}

// ----------------------------------------------------------------------------------- rounding and costs

/** n / d rounded half away from zero (Rounding.roundHalfUp). */
export function roundHalfUp(n, d) {
  n = BigInt(n);
  d = BigInt(d);
  if (d === 0n) throw new Error('division by zero');
  const q = n / d;
  const r = n % d;
  if (r === 0n) return q;
  const absR = r < 0n ? -r : r;
  const absD = d < 0n ? -d : d;
  if (absR >= absD - absR) return (n < 0n) === (d < 0n) ? q + 1n : q - 1n;
  return q;
}

export const mulDivHalfUp = (a, b, d) => roundHalfUp(BigInt(a) * BigInt(b), d);

/** Value of [qty] milli-units at [unitCost] each (CostMath.lineTotal). */
export const lineTotal = (qty, unitCost) => mulDivHalfUp(unitCost, qty, 1000n);

/** Cost per unit implied by paying [total] for [qty] milli-units (CostMath.unitCost). */
export function unitCost(total, qty) {
  if (BigInt(qty) <= 0n) throw new Error('qty must be > 0');
  return mulDivHalfUp(total, 1000n, qty);
}

/** The average cost after a delivery (CostMath.movingAverage, D-033). */
export function movingAverage(onHand, onHandCost, addQty, addTotal) {
  onHand = BigInt(onHand); onHandCost = BigInt(onHandCost); addQty = BigInt(addQty); addTotal = BigInt(addTotal);
  if (addQty <= 0n) throw new Error('received qty must be > 0');
  if (addTotal < 0n || onHandCost < 0n) throw new Error('costs must be >= 0');
  if (onHand <= 0n || onHandCost === 0n) return unitCost(addTotal, addQty);
  return roundHalfUp(onHand * onHandCost + addTotal * 1000n, onHand + addQty);
}

// ----------------------------------------------------------------------------------- stock reasons (AdjustReason)

const OUT = -1;
const IN = 1;
const EITHER = 0;

/** Why stock was changed by hand: stored in `stock_movement.reason` as the code, ": " and a note. Never rename. */
export const REASONS = Object.freeze([
  { code: 'damaged', kind: MovementKind.WASTE, direction: OUT },
  { code: 'expired', kind: MovementKind.WASTE, direction: OUT },
  { code: 'lost', kind: MovementKind.ADJUST, direction: OUT },
  { code: 'theft', kind: MovementKind.ADJUST, direction: OUT },
  { code: 'own_use', kind: MovementKind.ADJUST, direction: OUT },
  { code: 'returned', kind: MovementKind.RETURN_TO_SUPPLIER, direction: OUT },
  { code: 'found', kind: MovementKind.ADJUST, direction: IN },
  { code: 'correction', kind: MovementKind.ADJUST, direction: EITHER },
  { code: 'other', kind: MovementKind.ADJUST, direction: EITHER },
]);

export const reason = (code) => REASONS.find((r) => r.code === code) ?? null;

/** The reasons offered under Remove ([removing]) or Add (AdjustReason.choices). */
export const reasonChoices = (removing) => REASONS.filter((r) => r.direction !== (removing ? IN : OUT));

/** Signed change for a positive quantity entered (AdjustReason.delta). */
export function reasonDelta(r, qty, removing) {
  qty = BigInt(qty);
  if (qty <= 0n) throw new Error('qty must be > 0');
  const out = r.direction === OUT ? true : r.direction === IN ? false : removing;
  return out ? -qty : qty;
}

/** "damaged: box crushed" (AdjustReason.encode). */
export function reasonEncode(r, note) {
  const n = note?.trim().replace(/\n/g, ' ');
  return n ? `${r.code}: ${n}` : r.code;
}

// ----------------------------------------------------------------------------------- PINs (PinHash, PinLockout)

export const validPin = (pin) => /^[0-9]{4,6}$/.test(pin);

function unhex(s) {
  if (s.length % 2 !== 0 || !/^[0-9a-fA-F]*$/.test(s)) return null;
  const out = new Uint8Array(s.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(s.slice(i * 2, i * 2 + 2), 16);
  return out;
}

/** True when [pin] matches the stored record `p2:iterations:salt:hash` (PBKDF2-HMAC-SHA256, D-037). */
export async function pinVerify(pin, stored) {
  const parts = stored?.split(':');
  if (!parts || parts.length !== 4 || parts[0] !== 'p2') return false;
  if (!/^[0-9]+$/.test(parts[1])) return false;
  const iterations = Number(parts[1]);
  if (iterations < 1 || iterations > 10_000_000) return false;
  const salt = unhex(parts[2]);
  const expected = unhex(parts[3]);
  if (!salt || !expected || expected.length === 0 || !pin) return false;
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(pin), 'PBKDF2', false, ['deriveBits']);
  const actual = new Uint8Array(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, key, expected.length * 8));
  let diff = 0;
  for (let i = 0; i < expected.length; i++) diff |= expected[i] ^ actual[i];
  return diff === 0;
}

export const PinLockout = Object.freeze({
  FREE_TRIES: 5,
  waitMs(failures) {
    if (failures < 5) return 0;
    return Math.min(30_000 * 2 ** Math.min(failures - 5, 10), 15 * 60_000);
  },
  remainingMs(failures, lastFailureAt, now) {
    const wait = this.waitMs(failures);
    if (wait === 0) return 0;
    if (now < lastFailureAt) return wait;
    return Math.max(0, lastFailureAt + wait - now);
  },
  triesLeft: (failures) => Math.max(0, 5 - failures),
});

// ----------------------------------------------------------------------------------- the clock (Hlc, Lww)

export const MAX_FUTURE_MS = 24 * 3_600_000;
const MAX_PHYSICAL_MS = 4_102_444_800_000n;

export const hlcPack = (ms, counter = 0) => (BigInt(ms) << 16n) | BigInt(counter & 0xffff);
export const hlcPhysical = (hlc) => BigInt(hlc) >> 16n;

/** A hybrid logical clock (Hlc): every value greater than the last; follows what it has seen, not a clock far ahead. */
export class Clock {
  constructor(last = 0n, wall = () => Date.now()) {
    this.last = BigInt(last);
    this.wall = wall;
  }

  now() {
    const physical = BigInt(this.wall());
    if (physical > hlcPhysical(this.last)) this.last = hlcPack(physical, 0);
    else if (this.last !== LONG_MAX) this.last += 1n;
    return this.last;
  }

  observe(remote) {
    remote = BigInt(remote);
    const own = BigInt(this.wall()) > hlcPhysical(this.last) ? BigInt(this.wall()) : hlcPhysical(this.last);
    if (hlcPhysical(remote) > own + BigInt(MAX_FUTURE_MS)) return false;
    if (hlcPhysical(remote) > MAX_PHYSICAL_MS) return false;
    if (remote > this.last) this.last = remote;
    return true;
  }
}

/** The HLC of a local edit: [now], but above [held], the newest version the edited fields have (Lww.stampAbove). */
export function stampAbove(now, held) {
  now = BigInt(now);
  held = BigInt(held);
  if (now > held) return now;
  if (held === LONG_MAX) return held;
  return held + 1n;
}

// ----------------------------------------------------------------------------------- ids (core/id)

export const SEQ_BITS = 41n;
export const idOf = (dev, seq) => (BigInt(dev) << SEQ_BITS) | BigInt(seq);
export const deviceOf = (id) => Number(BigInt(id) >> SEQ_BITS);
/** Till numbers are 22-bit (sync.md §2). */
export const DEVICE_BITS = 22;

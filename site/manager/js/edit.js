// Changing the shop on the manager web page (D-083): signing in with the shop's PINs, the product form, Change stock,
// Receive stock, categories, suppliers and customers — the till's forms, rules and words (the app's strings). A save
// goes to the worker (editing.js), which sends it to Google Drive as one sync file of this page's own and only then
// shows it here; the tills take it on their next sync. Forms open over the page and stay open while it updates.

import { t } from './i18n.js';
import { h, put, append, toast } from './ui.js';
import { format, qty as qtyText } from './money.js';
import {
  parseMoney, parseQty, gtinCanonical, TILE_COLORS, BarcodeKind, SellMode, Perm, permHas, reasonChoices, lineTotal, unitCost,
} from './rules.js';

let ctx = null;

/**
 * [c]: {rpc(method, args), currency(), access(), setAccess(a), token() → Promise (call it straight from a tap: Google's
 * window may open), device(), saved(status, message, keepOpen), errorText(e)}.
 */
export function initEdit(c) {
  ctx = c;
}

const access = () => ctx.access();
export const canEdit = () => !!access()?.canEdit;

function may(perm) {
  const who = access()?.who;
  return !!who && permHas(BigInt(who.perms), perm);
}

/** Runs [then] when someone who may do [perm] is making changes; otherwise asks them to sign in first. */
export async function need(perm, then) {
  let a = access();
  if (!a?.canEdit) return;
  if (a.who?.signedIn) {
    // The sign-in may have ended meanwhile (30 minutes without a change): asked before the form opens.
    a = await ctx.rpc('access');
    ctx.setAccess(a);
  }
  if (!a.who) {
    signIn(() => need(perm, then));
    return;
  }
  if (!permHas(BigInt(a.who.perms), perm)) {
    notAllowed(perm, then);
    return;
  }
  then();
}

const COLOR_NAMES = ['color_none', 'color_red', 'color_pink', 'color_purple', 'color_indigo', 'color_blue', 'color_teal', 'color_green',
  'color_lime', 'color_amber', 'color_orange', 'color_brown', 'color_grey'];
const colorHex = (n) => TILE_COLORS.find(([k]) => k === Number(n))?.[1] ?? null;
const REASON_KEYS = { damaged: 'reason_damaged', expired: 'reason_expired', lost: 'reason_lost', theft: 'reason_theft', own_use: 'reason_own_use', returned: 'reason_returned', found: 'reason_found', correction: 'reason_correction', other: 'reason_other' };

// ------------------------------------------------------------------------------------------- the form layer

let editor = null; // {el, body, dirty(), busy}

export const editorOpen = () => !!editor;

function openEditor(title, ...content) {
  closeEditor(true);
  const body = h('div', { class: 'sheet-body' }, content);
  const el = h('div', { class: 'modal editor', role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
    h('div', { class: 'sheet' },
      h('div', { class: 'sheet-head' }, h('h2', { text: title }),
        h('button', { class: 'btn ghost', type: 'button', 'aria-label': t('close'), onclick: () => closeEditor() }, '✕')),
      body));
  let changed = false;
  el.addEventListener('input', () => { changed = true; });
  el.addEventListener('change', () => { changed = true; });
  editor = { el, body, dirty: () => changed, busy: false, clean: () => { changed = false; } };
  document.body.appendChild(el);
  document.body.classList.add('modal-open');
  return editor;
}

/** Closes the form; one with changes not saved asks first (unless [force]). Returns false while it stays. */
export function closeEditor(force = false) {
  if (!editor) return true;
  if (!force && editor.busy) return false;
  if (!force && editor.dirty()) {
    askDiscard();
    return false;
  }
  editor.el.remove();
  editor = null;
  if (!document.querySelector('.modal')) document.body.classList.remove('modal-open');
  return true;
}

function askDiscard() {
  const ed = editor;
  ed.body.querySelector('.discard')?.remove();
  const bar = h('div', { class: 'banner discard', role: 'alert' }, h('span', { text: t('discard_changes') }),
    h('span', { class: 'confirm' },
      h('button', { class: 'btn danger small', type: 'button', onclick: () => closeEditor(true) }, t('inv_discard')),
      h('button', { class: 'btn small', type: 'button', onclick: () => bar.remove() }, t('keep_editing'))));
  ed.body.prepend(bar);
  bar.scrollIntoView({ block: 'nearest' });
}

/** A labelled input with room for its error under it. */
function field(label, control, opts = {}) {
  const err = h('span', { class: 'field-error', role: 'alert' });
  const el = h('label', { class: `field${opts.wide ? ' wide' : ''}` }, h('span', { class: 'field-label', text: label }), control,
    opts.hint ? h('small', { class: 'muted', text: opts.hint }) : null, err);
  el.error = (msg) => {
    err.textContent = msg ?? '';
    el.classList.toggle('bad', !!msg);
    if (msg) control.focus();
  };
  return el;
}

const input = (value, attrs = {}) => h('input', { class: 'input', type: 'text', value: value ?? '', autocomplete: 'off', ...attrs });
const money = (attrs = {}) => ({ inputmode: 'decimal', ...attrs });

function select(options, value) {
  const el = h('select', { class: 'input' }, options.map((o) => h('option', { value: o.value, text: o.label })));
  el.value = String(value ?? '');
  return el;
}

function checkbox(label, checked) {
  const box = h('input', { type: 'checkbox' });
  box.checked = !!checked;
  const el = h('label', { class: 'check' }, box, h('span', { text: label }));
  el.box = box;
  return el;
}

const section = (title, info) => [h('h3', { class: 'section', text: title }), info ? h('p', { class: 'small muted', text: info }) : null];
const actions = (...buttons) => h('div', { class: 'form-actions' }, buttons);
const button = (label, onclick, kind = '') => h('button', { class: `btn ${kind}`.trim(), type: 'button', onclick }, label);

/** An inline question in the form: [text] with a button that does [yes]. */
function ask(ed, text, yesLabel, yes, danger = false) {
  ed.body.querySelector('.ask')?.remove();
  const bar = h('div', { class: `banner ask${danger ? ' error' : ''}`, role: 'alert' }, h('span', { text }),
    h('span', { class: 'confirm' },
      h('button', { class: `btn small ${danger ? 'danger' : 'primary'}`, type: 'button', onclick: () => { bar.remove(); yes(); } }, yesLabel),
      h('button', { class: 'btn small', type: 'button', onclick: () => bar.remove() }, t('cancel'))));
  ed.body.prepend(bar);
  bar.scrollIntoView({ block: 'nearest' });
}

function problem(ed, text) {
  ed.body.querySelector('.problem')?.remove();
  if (!text) return;
  const bar = h('div', { class: 'banner error problem', role: 'alert' }, h('span', { text }));
  ed.body.prepend(bar);
  bar.scrollIntoView({ block: 'nearest' });
}

/** What went wrong with a save, in the shop's words. */
function editError(e) {
  switch (e?.code) {
    case 'GONE': return t('err_GONE');
    case 'NOT_ALLOWED': return t('not_allowed');
    case 'PIN_NEEDED': return t('err_PIN_NEEDED');
    case 'HAS_BALANCE': return t('error_has_balance');
    case 'QTY': return t('product_error_qty');
    case 'EMPTY': case 'LINE': return t('inv_receive_empty');
    case 'PICTURE': return t('look_unreadable');
    case 'OFFLINE': return t('err_SAVE_OFFLINE');
    case 'DRIVE_FULL': return t('err_DRIVE_FULL');
    default: return ctx.errorText(e);
  }
}

/**
 * Sends change [action]: the token asked for at the tap ([tokenP]), the change made by the worker, the page shown again.
 * [keepOpen]: the form stays (a list of categories). Returns the worker's answer, or null when it failed (said in the form).
 */
async function send(ed, tokenP, action, args, message, keepOpen = false) {
  ed.busy = true;
  problem(ed, null);
  const buttons = [...ed.body.querySelectorAll('button')];
  for (const b of buttons) b.disabled = true;
  const saving = h('p', { class: 'progress saving', text: t('saving') });
  ed.body.querySelector('.form-actions')?.after(saving);
  try {
    const token = await tokenP;
    const r = await ctx.rpc('edit', { token, action, args, device: ctx.device() });
    ctx.setAccess(r.access);
    ed.busy = false;
    if (!keepOpen) closeEditor(true);
    else ed.clean();
    ctx.saved(r.status, message, keepOpen);
    return r;
  } catch (e) {
    problem(ed, editError(e));
    if (e.code === 'PIN_NEEDED') {
      ctx.setAccess(await ctx.rpc('access'));
      pinInForm(ed);
    }
    return null;
  } finally {
    ed.busy = false;
    saving.remove();
    for (const b of buttons) b.disabled = false;
  }
}

// ------------------------------------------------------------------------------------------- signing in

/** "m:ss" for a wait (StaffUi.waitText). */
function waitText(ms) {
  const s = Math.ceil(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

/** Choose who you are and type your PIN, as on the till; [then] runs once signed in. */
export function signIn(then) {
  const a = access();
  if (!a?.canEdit) return;
  const list = h('div', { class: 'people' });
  const pinBox = h('div');
  const ed = openEditor(t('lock_title'), h('p', { text: t('sign_in_help') }), h('p', { class: 'field-label', text: t('lock_who') }), list, pinBox);
  if (a.staff.length === 0) put(list, h('p', { class: 'muted', text: t('pin_not_allowed') }));
  const choose = (s) => {
    for (const b of list.querySelectorAll('button')) b.classList.toggle('active', b.dataset.id === s.id);
    const pin = input('', { type: 'password', inputmode: 'numeric', maxlength: 6, autocomplete: 'off', 'aria-label': t('pin_enter') });
    const f = field(t('pin_enter'), pin);
    const go = async () => {
      const tokenP = Promise.resolve(ctx.tokenIfAny());
      const r = await ctx.rpc('signIn', { staffId: s.id, pin: pin.value, token: await tokenP, device: ctx.device() });
      ctx.setAccess(r.access);
      pin.value = '';
      if (r.ok) {
        ed.clean();
        closeEditor(true);
        toast(t('signed_in_as', s.name));
        then?.();
        return;
      }
      if (r.notAllowed) f.error(t('pin_not_allowed'));
      else if (r.wait) f.error(t('pin_wait', waitText(r.wait)));
      else if (r.waitMs > 0) f.error(t('pin_wrong_wait', waitText(r.waitMs)));
      else f.error(t('pin_wrong', r.wrong));
    };
    pin.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
    put(pinBox, h('h3', { class: 'section', text: s.name }), f, actions(button(t('lock_title'), go, 'primary'), button(t('cancel'), () => closeEditor(true))));
    pin.focus();
  };
  append(list, a.staff.map((s) => h('button', { class: 'btn person-btn', type: 'button', 'data-id': s.id, onclick: () => choose(s) },
    h('strong', { text: s.name }), s.role ? h('small', { class: 'muted', text: s.role }) : null)));
  if (a.staff.length === 1) choose(a.staff[0]);
}

export async function signOut() {
  ctx.setAccess(await ctx.rpc('signOut'));
}

/**
 * Signing in again inside a form whose save was refused because the sign-in had ended: the form stays as typed, and
 * Save is tapped again once signed in.
 */
function pinInForm(ed) {
  const bar = ed.body.querySelector('.problem');
  const a = access();
  if (!bar || !a?.staff.length) return;
  const who = select(a.staff.map((s) => ({ value: s.id, label: s.name })), a.staff[0].id);
  const pin = input('', { type: 'password', inputmode: 'numeric', maxlength: 6, 'aria-label': t('pin_enter'), placeholder: t('pin_enter') });
  const message = h('span', { class: 'field-error', role: 'alert' });
  const go = async () => {
    const r = await ctx.rpc('signIn', { staffId: who.value, pin: pin.value, token: ctx.tokenIfAny(), device: ctx.device() });
    ctx.setAccess(r.access);
    pin.value = '';
    if (r.ok) {
      bar.remove();
      toast(t('signed_in_as', a.staff.find((s) => s.id === who.value)?.name ?? ''));
      return;
    }
    message.textContent = r.notAllowed ? t('pin_not_allowed') : r.wait ? t('pin_wait', waitText(r.wait))
      : r.waitMs > 0 ? t('pin_wrong_wait', waitText(r.waitMs)) : t('pin_wrong', r.wrong);
  };
  pin.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
  bar.append(h('div', { class: 'inline-sign-in' }, who, pin, button(t('lock_title'), go, 'primary small'), message));
  pin.focus();
}

function notAllowed(perm, then) {
  const a = access();
  const ed = openEditor(t('not_allowed'), h('p', { text: t('not_allowed_who', a.who.name) }));
  append(ed.body, actions(
    a.loginRequired ? button(t('sign_in_other'), async () => { await signOut(); closeEditor(true); signIn(() => need(perm, then)); }, 'primary') : null,
    button(t('close'), () => closeEditor(true))));
}

// ------------------------------------------------------------------------------------------- pictures

/** A picture file as the till stores it (Pictures.fromFile): its middle square, 240 px, JPEG, base64. */
async function pictureOf(file) {
  if (!file || file.size > 40 * 1024 * 1024) throw Object.assign(new Error('picture'), { code: 'PICTURE' });
  let bmp;
  try {
    bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch {
    throw Object.assign(new Error('picture'), { code: 'PICTURE' });
  }
  const SIDE = 240;
  const canvas = document.createElement('canvas');
  canvas.width = SIDE;
  canvas.height = SIDE;
  const g = canvas.getContext('2d');
  g.fillStyle = '#ffffff'; // a transparent picture on white, as in a JPEG on the till
  g.fillRect(0, 0, SIDE, SIDE);
  g.imageSmoothingQuality = 'high';
  const side = Math.min(bmp.width, bmp.height);
  g.drawImage(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side, 0, 0, SIDE, SIDE);
  bmp.close?.();
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.8));
  if (!blob) throw Object.assign(new Error('picture'), { code: 'PICTURE' });
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let bin = '';
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}

// ------------------------------------------------------------------------------------------- products

/** The product form (ProductEditActivity): [id] of the product, or null for a new one. */
export function editProduct(id) {
  need(Perm.MANAGE_PRODUCTS, async () => {
    const ed = openEditor(t(id ? 'product_edit' : 'product_new'), h('p', { class: 'muted', text: t('loading') }));
    try {
      productForm(ed, await ctx.rpc('productForEdit', { id }));
    } catch (e) {
      put(ed.body, h('p', { class: 'error', text: editError(e) }));
    }
  });
}

function productForm(ed, data) {
  const c = ctx.currency();
  const p = data.product;
  const name = input(p?.name, { maxlength: 200 });
  const price = input(p ? format(p.price, c, false) : '', money());
  const mode = select([['sell_mode_unit', SellMode.UNIT], ['sell_mode_weight', SellMode.WEIGHT], ['sell_mode_open', SellMode.OPEN_PRICE]]
    .map(([k, v]) => ({ value: v, label: t(k) })), p?.sellMode ?? SellMode.UNIT);
  const unit = input(p?.unit ?? 'pcs', { maxlength: 20 });
  mode.addEventListener('change', () => {
    // Unit symbols are data (printed on receipts): pcs and kg follow the way it is sold.
    if (Number(mode.value) === SellMode.WEIGHT && unit.value.trim() === 'pcs') unit.value = 'kg';
    if (Number(mode.value) !== SellMode.WEIGHT && unit.value.trim() === 'kg') unit.value = 'pcs';
  });
  const fName = field(t('product_name'), name, { wide: true });
  const fPrice = field(t('product_price'), price);

  // Stock (D-080): under the price.
  const track = checkbox(t('product_track_stock'), p?.trackStock ?? true);
  const untracked = h('p', { class: 'small muted', text: t('product_untracked') });
  const opening = !p && may(Perm.MANAGE_STOCK) ? input('', money()) : null;
  const fOpening = opening ? field(t('product_opening_stock'), opening, { hint: t('product_opening_hint') }) : null;
  const low = input(p && p.lowStock > 0n ? qtyText(p.lowStock) : '', money());
  const fLow = field(t('product_low_stock'), low);
  const tracked = h('div', { class: 'form-grid' }, fOpening,
    p ? h('div', { class: 'field' }, h('span', { class: 'field-label', text: t('product_stock_label') }), h('strong', { class: 'stock-now', text: `${qtyText(data.stock)} ${p.unit}` })) : null,
    fLow);
  const showStock = () => {
    tracked.hidden = !track.box.checked;
    untracked.hidden = track.box.checked;
  };
  track.box.addEventListener('change', showStock);
  showStock();

  // On the selling screen (D-066): a colour and a picture.
  let imageId = data.look.imageId;
  let picture = null;
  let shownPicture = data.picture;
  const color = select(COLOR_NAMES.map((k, n) => ({ value: n, label: t(k) })), data.look.color);
  const preview = h('div', { class: 'look-preview', 'aria-label': t('look_preview'), role: 'img' });
  const pictureError = h('p', { class: 'field-error', role: 'alert' });
  const removePicture = button(t('look_remove_picture'), () => {
    picture = null;
    imageId = null;
    shownPicture = null;
    showLook();
    ed.el.dispatchEvent(new Event('change'));
  });
  const showLook = () => {
    const src = picture ?? shownPicture;
    preview.style.background = colorHex(color.value) ?? '';
    put(preview, src ? h('img', { src: `data:image/jpeg;base64,${src}`, alt: '' }) : h('span', { text: name.value.trim().slice(0, 2) || '?' }));
    removePicture.hidden = !src;
  };
  color.addEventListener('change', showLook);
  name.addEventListener('input', showLook);
  const file = h('input', { type: 'file', accept: 'image/*', class: 'hidden-input' });
  file.addEventListener('change', async () => {
    pictureError.textContent = '';
    try {
      picture = await pictureOf(file.files?.[0]);
      showLook();
    } catch {
      pictureError.textContent = t('look_unreadable');
    }
    file.value = '';
  });
  showLook();

  // Barcodes: each says what it is (the owner, 2026-10-13); packs carry their pieces and price; the scale PLU apart.
  const codes = data.codes.filter((x) => x.kind === BarcodeKind.BARCODE).map((x) => ({ ...x }));
  const shownPlu = data.codes.find((x) => x.kind === BarcodeKind.SCALE_PLU) ?? null;
  const codeList = h('div', { class: 'codes' });
  const renderCodes = () => {
    put(codeList, codes.length === 0 ? h('p', { class: 'small muted', text: t('product_no_barcodes') }) : codes.map((x) => h('div', { class: 'code-row' },
      h('span', { text: x.packQty === 1000n ? x.code : t('product_pack_row', x.code, qtyText(x.packQty), x.packPrice != null ? format(x.packPrice, c) : t('product_pack_auto_price')) }),
      h('button', { class: 'btn ghost small', type: 'button', 'aria-label': `${t('remove')} ${x.code}`, onclick: () => { codes.splice(codes.indexOf(x), 1); renderCodes(); ed.el.dispatchEvent(new Event('change')); } }, '✕'))));
  };
  renderCodes();
  const newCode = input('', { inputmode: 'numeric', 'aria-label': t('product_barcode_hint'), placeholder: t('product_barcode_hint') });
  const fNewCode = field(t('product_barcode_add'), newCode);
  const addCode = () => {
    const code = gtinCanonical(newCode.value);
    if (!code) return fNewCode.error(t('product_error_barcode'));
    fNewCode.error(null);
    if (!codes.some((x) => x.code === code)) codes.push({ id: null, code, kind: BarcodeKind.BARCODE, packQty: 1000n, packPrice: null });
    newCode.value = '';
    renderCodes();
  };
  newCode.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); addCode(); } });
  const packCode = input('', { inputmode: 'numeric' });
  const packQty = input('', { inputmode: 'numeric' });
  const packPrice = input('', money());
  const fPackCode = field(t('product_barcode_hint'), packCode);
  const fPackQty = field(t('product_pack_qty'), packQty);
  const fPackPrice = field(t('product_pack_price'), packPrice);
  const packForm = h('div', { class: 'pack-form', hidden: true }, h('div', { class: 'form-grid' }, fPackCode, fPackQty, fPackPrice),
    actions(button(t('ok'), () => {
      const code = gtinCanonical(packCode.value);
      const pieces = /^[0-9]+$/.test(packQty.value.trim()) ? Number(packQty.value.trim()) : null;
      const priceText = packPrice.value.trim();
      const pp = priceText ? parseMoney(priceText, c) : null;
      for (const f of [fPackCode, fPackQty, fPackPrice]) f.error(null);
      if (!code) return fPackCode.error(t('product_error_barcode'));
      if (pieces == null || pieces < 2 || pieces > 100_000) return fPackQty.error(t('product_error_pack_qty'));
      if (priceText && (pp == null || pp < 0n)) return fPackPrice.error(t('product_error_price'));
      for (let i = codes.length - 1; i >= 0; i--) if (codes[i].code === code) codes.splice(i, 1);
      codes.push({ id: null, code, kind: BarcodeKind.BARCODE, packQty: BigInt(pieces) * 1000n, packPrice: pp });
      packCode.value = '';
      packQty.value = '';
      packPrice.value = '';
      packForm.hidden = true;
      renderCodes();
    }), button(t('cancel'), () => { packForm.hidden = true; })));
  const plu = input(shownPlu?.code ?? '', { inputmode: 'numeric' });
  const fPlu = field(t('product_plu'), plu);

  // More details
  const category = select([{ value: '', label: t('product_no_category') }, ...data.categories.map((x) => ({ value: x.id, label: x.name }))], p?.categoryId ?? '');
  const tax = select([{ value: '', label: t('product_no_tax') }, ...data.taxes.map((x) => ({ value: x.id, label: `${x.name} ${percentText(x.rateBp)}` }))], p?.taxRateId ?? '');
  const cost = input(p ? format(p.cost, c, false) : '', money());
  const fCost = field(t('product_cost'), cost);
  const sku = input(p?.sku, { placeholder: t('product_sku_hint'), maxlength: 60 });
  const active = checkbox(t('product_active'), p?.active ?? true);

  const save = async (confirmed = false) => {
    const n = name.value.trim();
    const m = Number(mode.value);
    const pr = price.value.trim() === '' && m === SellMode.OPEN_PRICE ? 0n : parseMoney(price.value, c);
    const co = cost.value.trim() === '' ? 0n : parseMoney(cost.value, c);
    const lo = low.value.trim() === '' ? 0n : parseQty(low.value);
    const pluText = plu.value.trim().replace(/^0+/, '');
    const openingText = track.box.checked && opening ? opening.value.trim() : '';
    const op = openingText === '' ? 0n : parseQty(openingText);
    for (const f of [fName, fPrice, fCost, fLow, fPlu, fOpening]) f?.error(null);
    if (!n) return fName.error(t('product_error_name'));
    if (pr == null || pr < 0n) return fPrice.error(t('product_error_price'));
    if (co == null || co < 0n) return fCost.error(t('product_error_price'));
    if (lo == null || lo < 0n) return fLow.error(t('product_error_qty'));
    if (plu.value.trim() && (!pluText || !/^[0-9]+$/.test(pluText))) return fPlu.error(t('product_error_plu'));
    if (op == null || op < 0n) return fOpening?.error(t('product_error_qty'));
    const edited = {
      name: n, sku: sku.value.trim() || null, categoryId: category.value ? BigInt(category.value) : null,
      unit: unit.value.trim() || (m === SellMode.WEIGHT ? 'kg' : 'pcs'), sellMode: m, price: pr, cost: co,
      taxRateId: tax.value ? BigInt(tax.value) : null, trackStock: track.box.checked, lowStock: lo, active: active.box.checked,
    };
    const wanted = codes.map((x) => ({ ...x }));
    if (pluText) wanted.push({ id: shownPlu?.code === pluText ? shownPlu.id : null, code: pluText, kind: BarcodeKind.SCALE_PLU, packQty: 1000n, packPrice: null });
    const tokenP = ctx.token();
    tokenP.catch(() => {});
    if (!confirmed) {
      const fresh = wanted.filter((x) => x.id == null && x.kind === BarcodeKind.BARCODE).map((x) => x.code);
      if (fresh.length) {
        const owners = await ctx.rpc('codeOwners', { codes: fresh, except: p ? String(p.id) : null });
        const dup = fresh.find((x) => owners[x]);
        if (dup) {
          ask(ed, `${t('product_duplicate_title')}: ${t('product_error_barcode_dup', dup, owners[dup][0].name)}`, t('save'), () => save(true));
          return;
        }
      }
    }
    await send(ed, tokenP, 'saveProduct', {
      shown: p, edited, shownCodes: data.codes, codes: wanted,
      look: { shown: data.look, edited: { color: Number(color.value), imageId }, picture },
      opening: op,
    }, t('product_saved'));
  };
  const del = () => ask(ed, t('product_delete_confirm', p.name), t('delete'), () => {
    send(ed, ctx.token(), 'deleteProduct', { id: String(p.id) }, t('product_deleted'));
  }, true);

  put(ed.body,
    h('div', { class: 'form-grid' }, fName, fPrice, field(t('product_sell_mode'), mode), field(t('product_unit'), unit)),
    section(t('product_stock_section'), t('product_stock_info')), track, untracked, tracked,
    section(t('look_section'), t('look_help')),
    h('div', { class: 'look' }, preview, h('div', { class: 'look-controls' }, field(t('look_color'), color),
      h('div', { class: 'button-row' }, button(t('look_choose_picture'), () => file.click()), removePicture), pictureError, file)),
    section(t('product_barcodes'), t('product_barcodes_info')), codeList,
    h('div', { class: 'add-code' }, fNewCode, button(t('product_barcode_add'), addCode)),
    button(t('product_pack_add'), () => { packForm.hidden = false; packCode.focus(); }), packForm,
    h('div', { class: 'form-grid' }, fPlu),
    section(t('product_more')),
    h('div', { class: 'form-grid' }, field(t('product_category'), category), field(t('product_tax'), tax), fCost,
      field(t('product_sku'), sku, { hint: t('product_sku_info') })),
    active,
    actions(button(t('save'), () => save(), 'primary'), button(t('cancel'), () => closeEditor()),
      p ? button(t('delete'), del, 'danger push') : null));
  ed.clean();
  name.focus();
}

const percentText = (bp) => `${(bp / 100).toFixed(2).replace(/\.?0+$/, '')}%`;

// ------------------------------------------------------------------------------------------- stock

/** Choose a product, then change its stock (the Stock tab's "Change stock"). */
export function pickAndChangeStock() {
  need(Perm.MANAGE_STOCK, () => {
    const ed = openEditor(t('inv_adjust'));
    productPicker(ed.body, (p) => changeStockNow(p.id));
  });
}

/** A search box listing products (name, SKU or barcode); [pick] gets the chosen row. */
function productPicker(box, pick, opts = {}) {
  const search = input('', { type: 'search', placeholder: t('inv_pick_hint'), 'aria-label': t('inv_pick_hint') });
  const results = h('div', { class: 'pick-list' });
  let asked = 0;
  const load = async () => {
    const mine = ++asked;
    const q = search.value.trim();
    const rows = await ctx.rpc('productList', { search: q, limit: 20 });
    if (mine !== asked) return;
    put(results, rows.length === 0 ? h('p', { class: 'muted small', text: t('nothing_found') }) : rows.map((x) => h('button', {
      class: 'pick', type: 'button', onclick: () => { if (opts.clear) { search.value = ''; put(results); } pick(x); },
    }, h('span', { text: x.name }), h('small', { class: 'muted', text: x.track_stock ? t('inv_stock_now', `${qtyText(x.stock)} ${x.unit}`) : (x.barcodes || '') }))));
  };
  let timer = 0;
  search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(load, 150); });
  search.addEventListener('keydown', async (e) => {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    // A scanner types the code and Enter: one product with that barcode is taken at once.
    const rows = await ctx.rpc('productList', { search: search.value.trim(), limit: 2 });
    if (rows.length === 1) { if (opts.clear) { search.value = ''; put(results); } pick(rows[0]); }
  });
  append(box, h('div', { class: 'picker' }, search, results));
  if (!opts.clear) load();
  search.focus();
  return search;
}

/** "Change stock" of one product (InventoryUi.changeDialog, D-080): count, remove or add. */
export function changeStock(productId) {
  need(Perm.MANAGE_STOCK, () => changeStockNow(productId));
}

async function changeStockNow(productId) {
  const ed = openEditor(t('inv_adjust'), h('p', { class: 'muted', text: t('loading') }));
  let p;
  try {
    p = await ctx.rpc('product', { id: productId });
    if (!p) throw Object.assign(new Error('gone'), { code: 'GONE' });
  } catch (e) {
    put(ed.body, h('p', { class: 'error', text: editError(e) }));
    return;
  }
  ed.el.querySelector('h2').textContent = `${t('inv_adjust')}: ${p.name}`;
  if (!p.track_stock) {
    put(ed.body, h('p', { text: t('product_untracked') }), actions(button(t('close'), () => closeEditor(true))));
    return;
  }
  const shown = (v) => `${qtyText(v)} ${p.unit}`;
  let mode = 'count';
  const modes = h('div', { class: 'chips', role: 'tablist' });
  const help = h('p', { class: 'small' });
  const reason = select([], '');
  const fReason = field(t('reason_hint'), reason);
  const amount = input('', money());
  const fAmount = field(t('qty'), amount);
  const status = h('p', { class: 'small muted stock-status' });
  const note = input('', { placeholder: t('inv_note_hint'), maxlength: 200 });
  const fNote = field(t('inv_note_hint'), note);
  const appHas = t('inv_app_has', shown(p.stock));
  const showStatus = () => {
    const v = parseQty(amount.value);
    if (mode !== 'count' || v == null || amount.value.trim() === '') { status.textContent = appHas; return; }
    const change = v - BigInt(p.stock);
    status.textContent = change < 0n ? t('inv_diff_less', shown(-change)) : change > 0n ? t('inv_diff_more', shown(change)) : t('inv_diff_same');
  };
  amount.addEventListener('input', showStatus);
  const choose = (m) => {
    mode = m;
    for (const b of modes.children) b.classList.toggle('active', b.dataset.mode === m);
    help.textContent = t(m === 'count' ? 'inv_count_help' : m === 'remove' ? 'inv_remove_help' : 'inv_add_help');
    fReason.hidden = m === 'count';
    fNote.hidden = m === 'count';
    if (m === 'count') {
      amount.value = BigInt(p.stock) > 0n ? qtyText(p.stock) : '';
    } else {
      put(reason, reasonChoices(m === 'remove').map((r) => h('option', { value: r.code, text: t(REASON_KEYS[r.code]) })));
      amount.value = '';
    }
    fAmount.error(null);
    showStatus();
    amount.focus();
  };
  append(modes, [['count', 'inv_count_stock'], ['remove', 'inv_remove_stock'], ['add', 'inv_add_stock']].map(([m, k]) =>
    h('button', { class: 'chip', type: 'button', 'data-mode': m, onclick: () => choose(m) }, t(k))));
  const save = () => {
    const v = amount.value.trim() === '' ? null : parseQty(amount.value);
    // 0 is a count ("none left"), never an amount to remove or add.
    if (v == null || v < 0n || (v === 0n && mode !== 'count')) return fAmount.error(t('product_error_qty'));
    const tokenP = ctx.token();
    if (mode === 'count') {
      send(ed, tokenP, 'setStock', { productId: p.id, qty: v }, t('inv_stock_set', p.name, shown(v)));
    } else {
      send(ed, tokenP, 'adjustStock', { productId: p.id, reason: reason.value, qty: v, removing: mode === 'remove', note: note.value.trim() || null },
        t('inv_adjusted', p.name));
    }
  };
  amount.addEventListener('keydown', (e) => { if (e.key === 'Enter') save(); });
  put(ed.body, modes, help, fReason, h('div', { class: 'form-grid' }, fAmount), status, fNote,
    actions(button(t('save'), save, 'primary'), button(t('cancel'), () => closeEditor())));
  choose('count');
  ed.clean();
}

/** A delivery from a supplier (ReceiveActivity, ReceiveDraft): stock in, costs to the moving average. */
export function receiveStock() {
  need(Perm.MANAGE_STOCK, async () => {
    const ed = openEditor(t('inv_receive'), h('p', { class: 'muted', text: t('loading') }));
    let lists;
    try {
      lists = await ctx.rpc('editLists');
    } catch (e) {
      put(ed.body, h('p', { class: 'error', text: editError(e) }));
      return;
    }
    receiveForm(ed, lists);
  });
}

function receiveForm(ed, lists) {
  const c = ctx.currency();
  const NEW = 'new';
  const supplier = select([{ value: '', label: t('inv_no_supplier') }, ...lists.suppliers.map((x) => ({ value: x.id, label: x.name })),
    { value: NEW, label: `${t('inv_new_supplier')}…` }], '');
  const newName = input('', { maxlength: 200 });
  const fNewName = field(t('inv_supplier_name'), newName);
  fNewName.hidden = true;
  supplier.addEventListener('change', () => { fNewName.hidden = supplier.value !== NEW; if (!fNewName.hidden) newName.focus(); });
  const ref = input('', { maxlength: 60 });
  const note = input('', { maxlength: 200 });
  const lines = [];
  const body = h('tbody');
  const total = h('p', { class: 'period' });
  const empty = h('p', { class: 'muted small', text: t('inv_receive_empty') });
  const showTotal = () => {
    const sum = lines.reduce((a, l) => a + l.total, 0n);
    total.textContent = t('inv_receive_total', format(sum, c), lines.length);
    empty.hidden = lines.length > 0;
  };
  const lineRow = (l) => {
    const q = input(qtyText(l.qty), money({ 'aria-label': t('qty'), class: 'input num' }));
    const u = input(format(l.unitCost, c, false), money({ 'aria-label': t('inv_unit_cost'), class: 'input num' }));
    const tot = input(format(l.total, c, false), money({ 'aria-label': t('inv_line_total'), class: 'input num' }));
    const bad = (el, yes) => el.classList.toggle('bad-input', yes);
    q.addEventListener('change', () => {
      const v = parseQty(q.value);
      bad(q, v == null || v <= 0n);
      if (v == null || v <= 0n) return;
      l.qty = v;
      l.total = lineTotal(v, l.unitCost);
      tot.value = format(l.total, c, false);
      showTotal();
    });
    u.addEventListener('change', () => {
      const v = parseMoney(u.value, c);
      bad(u, v == null || v < 0n);
      if (v == null || v < 0n) return;
      l.unitCost = v;
      l.total = lineTotal(l.qty, v);
      tot.value = format(l.total, c, false);
      showTotal();
    });
    tot.addEventListener('change', () => {
      // The invoice's line amount: kept exactly; the unit cost follows from it (ReceiveDraft.setTotal).
      const v = parseMoney(tot.value, c);
      bad(tot, v == null || v < 0n);
      if (v == null || v < 0n) return;
      l.total = v;
      l.unitCost = unitCost(v, l.qty);
      u.value = format(l.unitCost, c, false);
      showTotal();
    });
    return h('tr', null, h('td', null, h('div', { text: l.name }), h('small', { class: 'muted', text: l.unit })), h('td', null, q), h('td', null, u), h('td', null, tot),
      h('td', null, h('button', { class: 'btn ghost small', type: 'button', 'aria-label': `${t('remove')} ${l.name}`, onclick: () => {
        lines.splice(lines.indexOf(l), 1);
        renderLines();
      } }, '✕')));
  };
  const renderLines = () => {
    put(body, lines.map(lineRow));
    showTotal();
  };
  const add = (p) => {
    // The same product again adds to its line (ReceiveDraft.add).
    const existing = lines.find((l) => l.productId === p.id);
    if (existing) {
      existing.qty += 1000n;
      existing.total = lineTotal(existing.qty, existing.unitCost);
    } else {
      const cost = BigInt(p.cost ?? 0);
      lines.push({ productId: p.id, name: p.name, unit: p.unit, qty: 1000n, unitCost: cost, total: lineTotal(1000n, cost) });
    }
    renderLines();
    ed.el.dispatchEvent(new Event('change'));
  };
  const save = async () => {
    if (lines.length === 0) return problem(ed, t('inv_receive_empty'));
    if (ed.body.querySelector('.bad-input')) return problem(ed, t('product_error_qty'));
    if (supplier.value === NEW && !newName.value.trim()) return fNewName.error(t('product_error_name'));
    const tokenP = ctx.token();
    let supplierId = supplier.value && supplier.value !== NEW ? supplier.value : null;
    if (supplier.value === NEW) {
      const r = await send(ed, tokenP, 'saveSupplier', { shown: null, edited: { name: newName.value.trim() } }, t('saved'), true);
      if (!r) return;
      supplierId = r.result;
      put(supplier, [{ value: '', label: t('inv_no_supplier') }, { value: supplierId, label: newName.value.trim() }].map((o) => h('option', { value: o.value, text: o.label })));
      supplier.value = supplierId;
      fNewName.hidden = true;
    }
    await send(ed, tokenP, 'receive', {
      supplierId, refNo: ref.value, note: note.value,
      lines: lines.map((l) => ({ productId: l.productId, qty: l.qty, unitCost: l.unitCost, total: l.total })),
    }, t('inv_received'));
  };
  put(ed.body,
    h('div', { class: 'form-grid' }, field(t('inv_supplier'), supplier), fNewName, field(t('inv_ref_hint'), ref), field(t('inv_note_hint'), note)),
    section(t('inv_purchase_lines')),
    h('div', { class: 'table-wrap' }, h('table', { class: 'lines' },
      h('thead', null, h('tr', null, h('th', { text: t('product') }), h('th', { class: 'num', text: t('qty') }), h('th', { class: 'num', text: t('inv_unit_cost') }),
        h('th', { class: 'num', text: t('inv_line_total') }), h('th'))), body)),
    empty);
  const pickBox = h('div', { class: 'add-line' }, h('p', { class: 'field-label', text: t('inv_add_item') }));
  ed.body.append(pickBox);
  productPicker(pickBox, (p) => add(p), { clear: true });
  ed.body.append(total, actions(button(t('inv_receive_save'), save, 'primary'), button(t('cancel'), () => closeEditor())));
  showTotal();
  ed.clean();
}

// ------------------------------------------------------------------------------------------- categories

/** The categories (CategoriesActivity): add, rename and colour, delete. */
export function editCategories() {
  need(Perm.MANAGE_PRODUCTS, async () => {
    const ed = openEditor(t('categories_title'), h('p', { class: 'muted', text: t('loading') }));
    const show = async () => {
      let lists;
      try {
        lists = await ctx.rpc('editLists');
      } catch (e) {
        put(ed.body, h('p', { class: 'error', text: editError(e) }));
        return;
      }
      const list = h('div', { class: 'cat-list' });
      const formBox = h('div');
      const form = (cat) => {
        const name = input(cat?.name ?? '', { maxlength: 100 });
        const color = select(COLOR_NAMES.map((k, n) => ({ value: n, label: t(k) })), cat?.color ?? 0);
        const fName = field(t('category_name'), name);
        const save = () => {
          const n = name.value.trim();
          if (!n) return fName.error(t('product_error_name'));
          send(ed, ctx.token(), 'saveCategory', { shown: cat, name: n, color: Number(color.value) }, t('saved'), true).then((r) => { if (r) show(); });
        };
        name.addEventListener('keydown', (e) => { if (e.key === 'Enter') save(); });
        put(formBox, h('h3', { class: 'section', text: t(cat ? 'category_edit' : 'category_add') }),
          h('div', { class: 'form-grid' }, fName, field(t('look_color'), color)),
          actions(button(t('save'), save, 'primary'), button(t('cancel'), () => put(formBox))));
        name.focus();
      };
      put(list, lists.categories.length === 0 ? h('p', { class: 'muted', text: t('categories_empty') }) : lists.categories.map((cat) => h('div', { class: 'cat-row' },
        h('span', { class: 'swatch', style: `background:${colorHex(cat.color) ?? 'transparent'}` }), h('span', { class: 'grow', text: cat.name }),
        button(t('category_edit'), () => form(cat), 'small'),
        button(t('delete'), () => ask(ed, t('category_delete_confirm', cat.name), t('delete'), () => {
          send(ed, ctx.token(), 'deleteCategory', { id: cat.id }, t('saved'), true).then((r) => { if (r) show(); });
        }, true), 'small'))));
      put(ed.body, list, actions(button(t('category_add'), () => form(null), 'primary'), button(t('close'), () => closeEditor(true))), formBox);
      ed.clean();
    };
    show();
  });
}

// ------------------------------------------------------------------------------------------- suppliers

/** Add ([s] = null) or edit a supplier (SuppliersActivity.editSupplier). */
export function editSupplier(s) {
  need(Perm.MANAGE_STOCK, () => {
    const name = input(s?.name, { maxlength: 200 });
    const contact = input(s?.contact, { maxlength: 200 });
    const phone = input(s?.phone, { type: 'tel', maxlength: 40 });
    const email = input(s?.email, { type: 'email', maxlength: 200 });
    const address = h('textarea', { class: 'input', rows: 2, maxlength: 500 });
    address.value = s?.address ?? '';
    const note = input(s?.note, { maxlength: 500 });
    const fName = field(t('inv_supplier_name'), name, { wide: true });
    const ed = openEditor(t(s ? 'inv_edit_supplier' : 'inv_new_supplier'));
    const value = (el) => el.value.trim() || null;
    const save = () => {
      const n = name.value.trim();
      if (!n) return fName.error(t('product_error_name'));
      send(ed, ctx.token(), 'saveSupplier', {
        shown: s, edited: { name: n, contact: value(contact), phone: value(phone), email: value(email), address: value(address), note: value(note) },
      }, t('saved'));
    };
    put(ed.body, h('div', { class: 'form-grid' }, fName, field(t('inv_supplier_contact'), contact), field(t('store_phone'), phone),
      field(t('store_email'), email), field(t('store_address'), address, { wide: true }), field(t('inv_note_hint'), note, { wide: true })),
    actions(button(t('save'), save, 'primary'), button(t('cancel'), () => closeEditor()),
      s ? button(t('delete'), () => ask(ed, t('inv_supplier_delete_confirm', s.name), t('delete'), () => send(ed, ctx.token(), 'deleteSupplier', { id: s.id }, t('saved')), true), 'danger push') : null));
    ed.clean();
    name.focus();
  });
}

// ------------------------------------------------------------------------------------------- customers

/** Add ([id] = null) or edit a customer (CustomerActivities.showCustomerForm); the limit only for who may set it. */
export function editCustomer(id) {
  need(Perm.CUSTOMERS, async () => {
    const ed = openEditor(t(id ? 'customer_edit' : 'customer_add'), h('p', { class: 'muted', text: t('loading') }));
    let cus = null;
    try {
      if (id) cus = await ctx.rpc('customerForEdit', { id });
    } catch (e) {
      put(ed.body, h('p', { class: 'error', text: editError(e) }));
      return;
    }
    const c = ctx.currency();
    const name = input(cus?.name, { maxlength: 200 });
    const phone = input(cus?.phone, { type: 'tel', maxlength: 40 });
    const email = input(cus?.email, { type: 'email', maxlength: 200 });
    const address = h('textarea', { class: 'input', rows: 2, maxlength: 500 });
    address.value = cus?.address ?? '';
    const tin = input(cus?.tin, { maxlength: 40 });
    const mayLimit = may(Perm.CREDIT_LIMIT);
    const limit = mayLimit ? input(cus && cus.creditLimit > 0n ? format(cus.creditLimit, c, false) : '', money()) : null;
    const note = input(cus?.note, { maxlength: 500 });
    const fName = field(t('customer_name'), name, { wide: true });
    const fLimit = limit ? field(t('customer_limit'), limit, { hint: t('customer_limit_hint') }) : null;
    const value = (el) => el.value.trim() || null;
    const save = () => {
      const n = name.value.trim();
      fName.error(null);
      fLimit?.error(null);
      if (!n) return fName.error(t('product_error_name'));
      const limitText = limit?.value.trim() ?? null;
      const lim = limit == null ? (cus?.creditLimit ?? 0n) : limitText === '' ? 0n : parseMoney(limitText, c);
      if (lim == null || lim < 0n) return fLimit?.error(t('customer_limit_error'));
      send(ed, ctx.token(), 'saveCustomer', {
        shown: cus, edited: { name: n, phone: value(phone), email: value(email), address: value(address), tin: value(tin), note: value(note), creditLimit: lim },
      }, t('saved'));
    };
    put(ed.body, h('div', { class: 'form-grid' }, fName, field(t('store_phone'), phone), field(t('store_email'), email),
      field(t('customer_tin'), tin), field(t('store_address'), address, { wide: true }), fLimit, field(t('inv_note_hint'), note, { wide: true })),
    actions(button(t('save'), save, 'primary'), button(t('cancel'), () => closeEditor()),
      cus ? button(t('delete'), () => ask(ed, t('customer_delete_confirm', cus.name), t('delete'), () => send(ed, ctx.token(), 'deleteCustomer', { id: String(cus.id) }, t('saved')), true), 'danger push') : null));
    ed.clean();
    name.focus();
  });
}

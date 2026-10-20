// The manager web page (D-082): shows one shop — its reports, sales, products, stock, shifts and activity
// log — from a copy kept in this browser (worker.js), filled from the shop's Google Drive backup or a
// backup file. Read only. Every text from the shop is set as text, never as HTML.

import { t, setLanguage, language } from './i18n.js';
import * as auth from './auth.js';
import * as D from './days.js';
import { format, qty as qtyText, currencyOf, MYR, plain } from './money.js';

// ------------------------------------------------------------------------------------------- DOM

function h(tag, props, ...children) {
  const el = document.createElement(tag);
  if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = v;
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else el.setAttribute(k, v === true ? '' : v);
    }
  }
  append(el, children);
  return el;
}

/** Replaces [el]'s content with [children] (nested lists flattened, empty values left out). */
function put(el, ...children) {
  el.replaceChildren();
  return append(el, children);
}

function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c == null || c === false) continue;
    el.appendChild(typeof c === 'string' || typeof c === 'number' ? document.createTextNode(String(c)) : c);
  }
  return el;
}

const svgNs = 'http://www.w3.org/2000/svg';
function s(tag, attrs, ...children) {
  const el = document.createElementNS(svgNs, tag);
  for (const [k, v] of Object.entries(attrs ?? {})) el.setAttribute(k, v);
  for (const c of children) if (c) el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
  return el;
}

// ------------------------------------------------------------------------------------------- worker

let worker = null;
let nextCall = 1;
const calls = new Map();

function startWorker() {
  worker = new Worker(new URL('./worker.js', import.meta.url), { type: 'module' });
  worker.onmessage = (ev) => {
    const m = ev.data;
    if (m.type === 'progress') {
      S.progress = m;
      renderStatusLine();
      return;
    }
    const c = calls.get(m.id);
    if (!c) return;
    calls.delete(m.id);
    if (m.error) c.reject(Object.assign(new Error(m.error.message), { code: m.error.code }));
    else c.resolve(m.result);
  };
  worker.onerror = (e) => {
    for (const c of calls.values()) c.reject(Object.assign(new Error(e.message || 'worker failed'), { code: 'ERROR' }));
    calls.clear();
  };
}

function rpc(method, args) {
  return new Promise((resolve, reject) => {
    const id = nextCall++;
    calls.set(id, { resolve, reject });
    worker.postMessage({ id, method, args });
  });
}

// ------------------------------------------------------------------------------------------- state

const S = {
  status: null,
  currency: MYR,
  busy: false,
  progress: null,
  error: null,
  staff: {},
  report: { preset: 'TODAY' },
};
let gen = 0; // each render: answers for an older one are dropped

const money = (v) => format(v ?? 0, S.currency);
const signed = (v) => (v > 0 ? `+${money(v)}` : money(v));
const pct = (bp) => (bp == null ? '–' : `${plain(bp, 2)}%`);
const tillName = (dev) => S.status?.cards?.find((c) => c.dev === Number(dev))?.name || t('till_number', Number(dev));
const periodText = (p) => (p.to - p.from === 1 ? D.dateText(p.from) : t('report_range', D.dateText(p.from), D.dateText(p.to - 1)));

function errorText(e) {
  const code = e?.code ?? 'ERROR';
  switch (code) {
    case 'NO_STORE': return t('err_NO_STORE', t('menu_settings'), t('sync_title'), t('sync_turn_on'));
    case 'DRIVE': return t('err_DRIVE', e.message);
    case 'CORRUPT': return t('err_CORRUPT', e.message);
    case 'ERROR': return t('err_ERROR', e.message);
    default: {
      const key = `err_${code}`;
      const text = t(key);
      return text === key ? t('err_ERROR', e.message || code) : text;
    }
  }
}

// ------------------------------------------------------------------------------------------- boot

function supported() {
  try {
    return typeof Worker === 'function' && typeof DecompressionStream === 'function' && typeof BigInt === 'function' &&
      !!globalThis.crypto?.subtle;
  } catch {
    return false;
  }
}

async function boot() {
  let lang = null;
  try { lang = localStorage.getItem('lekas.manager.lang'); } catch { /* storage blocked */ }
  setLanguage(lang ?? (navigator.language?.toLowerCase().startsWith('ms') ? 'ms' : 'en'));
  const root = document.getElementById('app');
  if (!supported()) {
    root.replaceChildren(h('main', { class: 'welcome' }, h('h1', { text: t('app_title') }), h('p', { class: 'error', text: t('unsupported') })));
    return;
  }
  auth.prepare().catch(() => {});
  startWorker();
  try {
    S.status = await rpc('status');
  } catch (e) {
    S.error = e;
    S.status = { source: null };
  }
  applyStatus();
  window.addEventListener('hashchange', render);
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeModal(); });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') autoUpdate();
  });
  setInterval(autoUpdate, 5 * 60_000);
  render();
}

function applyStatus() {
  S.currency = S.status?.settings ? currencyOf(S.status.settings) : MYR;
  document.title = S.status?.settings?.['store.name'] ? `${S.status.settings['store.name']} · ${t('app_title')}` : t('app_title');
}

/** While a token is still good: read what is new without asking (every 5 minutes, and when the tab comes back). */
function autoUpdate() {
  if (S.busy || S.status?.source !== 'drive' || !auth.current()) return;
  if (S.status.syncedAt && Date.now() - S.status.syncedAt < 2 * 60_000) return;
  update(false, true);
}

async function update(consent = false, quiet = false) {
  if (S.busy) return;
  let token = auth.current();
  try {
    if (!token) token = await auth.signIn(S.status?.account, consent);
    S.busy = true;
    S.error = null;
    S.progress = { phase: 'connect' };
    renderStatusLine();
    await rpc('sync', { token });
    S.status = await rpc('status');
    applyStatus();
  } catch (e) {
    if (!quiet || e.code !== 'OFFLINE') S.error = e;
  } finally {
    S.busy = false;
    S.progress = null;
    render();
  }
}

async function openBackup(file) {
  if (!file || S.busy) return;
  S.busy = true;
  S.error = null;
  S.progress = { phase: 'backup' };
  render();
  try {
    S.status = await rpc('openBackup', { file });
    applyStatus();
    location.hash = '#home';
  } catch (e) {
    S.error = e;
  } finally {
    S.busy = false;
    S.progress = null;
    render();
  }
}

function pickBackup() {
  const input = h('input', { type: 'file', accept: '.lekasbak,application/zip,application/octet-stream', class: 'hidden-input' });
  input.addEventListener('change', () => openBackup(input.files?.[0]));
  document.body.appendChild(input);
  input.click();
  setTimeout(() => input.remove(), 60_000);
}

async function forget() {
  S.status = await rpc('forget');
  auth.forget();
  S.error = null;
  applyStatus();
  location.hash = '';
  render();
}

// ------------------------------------------------------------------------------------------- shell

const NAV = [
  ['home', 'nav_home'], ['reports', 'menu_reports'], ['sales', 'menu_sales'], ['products', 'products_title'],
  ['stock', 'inv_title'], ['shifts', 'menu_shift'], ['activity', 'settings_audit'],
];

function route() {
  const [view, ...rest] = location.hash.slice(1).split('/');
  return { view: NAV.some(([v]) => v === view) ? view : 'home', id: rest.join('/') || null };
}

function langButton() {
  return h('button', {
    class: 'btn ghost lang', type: 'button', lang: t('language_code'), title: t('language'),
    onclick: () => {
      const next = language() === 'ms' ? 'en' : 'ms';
      try { localStorage.setItem('lekas.manager.lang', next); } catch { /* storage blocked */ }
      setLanguage(next);
      applyStatus();
      render();
    },
  }, t('language'));
}

function render() {
  gen++;
  closeModal(true); // a view opens its own sheet again when the address names an item
  const root = document.getElementById('app');
  if (!S.status?.source) {
    root.replaceChildren(welcome());
    return;
  }
  const r = route();
  const view = h('main', { id: 'view', class: 'view' });
  root.replaceChildren(...[header(), nav(r.view), banner(), view, footer()].filter(Boolean));
  const views = { home, reports, sales, products, stock, shifts, activity };
  views[r.view](view, r.id);
}

function header() {
  const name = S.status.settings?.['store.name'] || t('shop');
  const st = S.status;
  return h('header', { class: 'top' },
    h('a', { class: 'brand', href: '#home' }, h('img', { src: '../icon.svg', alt: '', width: 32, height: 32 }),
      h('span', { class: 'brand-text' }, h('strong', { text: name }), h('small', { text: t('app_title') }))),
    h('div', { class: 'top-actions' },
      h('span', { class: 'status', id: 'status-line' }, statusText()),
      st.source === 'drive'
        ? h('button', { class: 'btn primary', type: 'button', disabled: S.busy, onclick: () => update() }, S.busy ? t('updating') : t('update'))
        : h('button', { class: 'btn', type: 'button', disabled: S.busy, onclick: pickBackup }, t('open_backup')),
      langButton()));
}

function statusText() {
  const st = S.status;
  if (S.busy && S.progress) {
    if (S.progress.phase === 'receive' && S.progress.total > 0) return t('progress_receive', S.progress.done, S.progress.total);
    if (S.progress.phase === 'backup') return t('progress_backup');
    return t('progress_connect');
  }
  if (st.source === 'backup') {
    const b = st.backup ?? {};
    return t('source_backup', b.name || t('shop'), b.createdAt ? D.dateTimeText(b.createdAt) : '?');
  }
  return st.syncedAt ? t('updated_at', D.dateTimeText(st.syncedAt)) : t('updated_never');
}

function renderStatusLine() {
  const el = document.getElementById('status-line');
  if (el) el.textContent = statusText();
}

function nav(current) {
  return h('nav', { class: 'tabs', 'aria-label': t('app_title') },
    NAV.map(([v, key]) => h('a', { href: `#${v}`, class: v === current ? 'active' : null, 'aria-current': v === current ? 'page' : null }, t(key))));
}

function banner() {
  const items = [];
  if (S.error) {
    const code = S.error.code;
    items.push(h('div', { class: 'banner error', role: 'alert' }, h('span', { text: errorText(S.error) }),
      (code === 'SIGN_IN' || code === 'NO_ACCESS' || code === 'POPUP')
        ? h('button', { class: 'btn', type: 'button', onclick: () => update(code === 'NO_ACCESS') }, t('sign_in_again')) : null));
  }
  if (!S.status.persistent) items.push(h('div', { class: 'banner', text: t('not_kept') }));
  if (S.status.deferred > 0) items.push(h('div', { class: 'banner', text: t('deferred', S.status.deferred) }));
  return items.length ? h('div', { class: 'banners' }, items) : null;
}

function footer() {
  const st = S.status;
  return h('footer', { class: 'foot' },
    h('p', null,
      st.source === 'drive' ? t('source_drive', st.account || '–') : t('source_backup_note'), ' · ',
      st.source === 'drive' ? h('button', { class: 'linkish', type: 'button', onclick: pickBackup }, t('open_backup')) : null,
      st.source === 'drive' ? ' · ' : null,
      forgetButton()),
    h('p', null,
      h('a', { href: language() === 'ms' ? '../panduan.html#manager' : '../guide.html#manager' }, t('user_guide')), ' · ',
      h('a', { href: language() === 'ms' ? '../privasi.html' : '../privacy.html' }, t('privacy')), ' · ',
      h('a', { href: 'https://github.com/FaizoKen/LekasPOS' }, 'LekasPOS')));
}

function forgetButton() {
  const wrap = h('span', { class: 'confirm' });
  const ask = h('button', { class: 'linkish danger', type: 'button', onclick: () => {
    put(wrap, h('span', { text: `${t('forget_confirm')} ` }),
      h('button', { class: 'btn danger small', type: 'button', onclick: forget }, t('forget')),
      h('button', { class: 'btn small', type: 'button', onclick: () => put(wrap, ask) }, t('close')));
  } }, t('forget'));
  wrap.appendChild(ask);
  return wrap;
}

// ------------------------------------------------------------------------------------------- welcome

function welcome() {
  const ready = auth.configured();
  const guide = language() === 'ms' ? '../panduan.html#manager' : '../guide.html#manager';
  return h('main', { class: 'welcome' },
    h('div', { class: 'welcome-top' }, h('a', { class: 'brand', href: '../' }, h('img', { src: '../icon.svg', alt: '', width: 40, height: 40 }), h('strong', { text: 'LekasPOS' })), langButton()),
    h('h1', { text: t('app_title') }),
    h('p', { class: 'lead', text: t('tagline') }),
    S.error ? h('div', { class: 'banner error', role: 'alert' }, h('span', { text: errorText(S.error) }),
      ['SIGN_IN', 'NO_ACCESS', 'POPUP'].includes(S.error.code) && ready
        ? h('button', { class: 'btn', type: 'button', onclick: () => update(S.error.code === 'NO_ACCESS') }, t('sign_in_again')) : null) : null,
    S.busy ? h('p', { class: 'progress', id: 'status-line', text: S.progress?.phase === 'backup' ? t('progress_backup') : t('progress_connect') }) : null,
    h('div', { class: 'choices' },
      h('section', { class: 'choice' },
        h('h2', { text: t('connect_drive') }),
        h('p', { text: t('connect_help', t('menu_settings'), t('sync_title'), t('sync_turn_on')) }),
        ready
          ? h('button', { class: 'btn primary big', type: 'button', disabled: S.busy, onclick: () => update() }, t('connect_drive'))
          : h('p', { class: 'muted', text: t('not_configured') })),
      h('section', { class: 'choice' },
        h('h2', { text: t('open_backup') }),
        h('p', { text: t('open_backup_help', t('menu_settings'), t('backup_title'), t('backup_save_file')) }),
        h('button', { class: 'btn big', type: 'button', disabled: S.busy, onclick: pickBackup }, t('open_backup')))),
    h('section', { class: 'how' }, h('h2', { text: t('how_it_works') }),
      h('ul', null, h('li', { text: t('how_1') }), h('li', { text: t('how_2') }), h('li', { text: t('how_3') }))),
    h('p', { class: 'muted' }, h('a', { href: guide }, t('user_guide')), ' · ',
      h('a', { href: language() === 'ms' ? '../privasi.html' : '../privacy.html' }, t('privacy'))));
}

// ------------------------------------------------------------------------------------------- shared pieces

function card(title, ...children) {
  return h('section', { class: 'card' }, title ? h('h2', { text: title }) : null, ...children);
}

/** A label / value row like the app's report rows. */
function row(label, value, opts = {}) {
  return h('div', { class: `row${opts.bold ? ' bold' : ''}${opts.warn ? ' warn' : ''}` },
    h('span', { class: 'label', text: label }), h('span', { class: 'value', text: value ?? '' }));
}

function loadingEl() {
  return h('p', { class: 'muted', text: t('loading') });
}

/** Runs [load] for the current render and puts what [show] makes into [box]; an older render's answer is dropped. */
function fill(box, load, show) {
  const mine = gen;
  put(box, loadingEl());
  load().then((data) => {
    if (mine !== gen) return;
    put(box, show(data));
  }).catch((e) => {
    if (mine !== gen) return;
    put(box, h('p', { class: 'error', text: errorText(e) }));
  });
}

/** Bars of [values] ({label, value, title}), for a quick look at a trend; the labels are text under them. */
function bars(values) {
  const n = Math.max(1, values.length);
  const max = Math.max(1, ...values.map((v) => v.value));
  // One unit of width per bar, stretched to the card: the bars scale, the labels stay text-sized.
  const svg = s('svg', { viewBox: `0 0 ${n * 10} 100`, preserveAspectRatio: 'none', class: 'bars', role: 'img', 'aria-label': values.map((v) => v.title).join('; ') });
  values.forEach((v, i) => {
    const bh = Math.max(0, (v.value / max) * 100);
    const rect = s('rect', { x: i * 10 + 1, y: 100 - bh, width: 8, height: Math.max(bh, v.value > 0 ? 0.5 : 0), class: v.today ? 'bar today' : 'bar' });
    rect.appendChild(s('title', {}, v.title));
    svg.appendChild(rect);
  });
  const every = Math.ceil(n / 12);
  const labels = h('div', { class: 'bar-labels', style: `grid-template-columns: repeat(${n}, 1fr)` },
    values.map((v, i) => h('span', { text: i % every === 0 ? v.label : '' })));
  return h('div', { class: 'chart' }, svg, labels);
}

function table(headers, rows, opts = {}) {
  return h('div', { class: 'table-wrap' },
    h('table', { class: opts.class ?? null },
      h('thead', null, h('tr', null, headers.map((x) => h('th', { class: x.num ? 'num' : null, text: x.label ?? x })))),
      h('tbody', null, rows)));
}

function td(text, opts = {}) {
  return h('td', { class: opts.num ? 'num' : opts.class ?? null }, text);
}

function tag(text, kind) {
  return h('span', { class: `tag ${kind ?? ''}`, text });
}

function linkRow(href, cells, extra) {
  const tr = h('tr', { class: `click${extra ? ` ${extra}` : ''}`, tabindex: 0, onclick: () => { location.hash = href; },
    onkeydown: (e) => { if (e.key === 'Enter') location.hash = href; } }, cells);
  return tr;
}

/** A list that loads a page at a time: [page](after) → rows; [tr](row) → <tr>; [key](row) → the next `after`. */
function pagedTable(box, headers, page, tr, key, empty) {
  const body = h('tbody');
  const more = h('button', { class: 'btn', type: 'button' }, t('show_more'));
  const mine = gen;
  let after = null;
  const load = async () => {
    more.disabled = true;
    try {
      const rows = await page(after);
      if (mine !== gen) return;
      if (!after && rows.length === 0) {
        put(box, h('p', { class: 'muted', text: empty }));
        return;
      }
      append(body, rows.map(tr));
      after = rows.length ? key(rows[rows.length - 1]) : after;
      more.hidden = rows.length < 50;
    } catch (e) {
      if (mine === gen) put(box, h('p', { class: 'error', text: errorText(e) }));
    } finally {
      more.disabled = false;
    }
  };
  more.addEventListener('click', load);
  put(box, h('div', { class: 'table-wrap' }, h('table', null,
    h('thead', null, h('tr', null, headers.map((x) => h('th', { class: x.num ? 'num' : null, text: x.label ?? x })))), body)),
  h('p', { class: 'more' }, more));
  load();
}

// ------------------------------------------------------------------------------------------- modal

let modal = null;

function openModal(title, content, back) {
  closeModal(true);
  const close = () => { location.hash = back; };
  modal = h('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true', onclick: (e) => { if (e.target === modal) close(); } },
    h('div', { class: 'sheet' },
      h('div', { class: 'sheet-head' }, h('h2', { text: title }), h('button', { class: 'btn ghost', type: 'button', 'aria-label': t('close'), onclick: close }, '✕')),
      h('div', { class: 'sheet-body' }, content)));
  modal.dataset.back = back;
  document.body.appendChild(modal);
  document.body.classList.add('modal-open');
}

function closeModal(silent) {
  if (!modal) return;
  const back = modal.dataset.back;
  modal.remove();
  modal = null;
  document.body.classList.remove('modal-open');
  if (!silent && back && location.hash !== back) location.hash = back;
}

// ------------------------------------------------------------------------------------------- home

function home(view) {
  const today = D.today();
  const box = h('div', { class: 'home' });
  view.append(box);
  fill(box, () => rpc('overview', { today }), (o) => {
    const tile = (label, tot, preset, compareTot, compareLabel) => {
      const sales = tot.total;
      const change = compareTot ? D.changeBp(sales, compareTot.total) : null;
      return h('a', { class: 'tile', href: `#reports/${preset}` },
        h('span', { class: 'tile-label', text: label }),
        h('strong', { class: 'tile-value', text: money(sales) }),
        h('span', { class: 'tile-sub', text: `${t('report_sales_count', tot.saleCount)} · ${t('home_profit')} ${money(tot.grossProfit)}` }),
        change != null ? h('span', { class: `tile-change ${change >= 0 ? 'up' : 'down'}`, text: `${change > 0 ? '+' : ''}${pct(change)} ${t('home_vs', compareLabel)}` }) : null);
    };
    const tiles = h('div', { class: 'tiles' },
      tile(t('report_today'), o.today, 'TODAY', o.yesterday, t('report_yesterday').toLowerCase()),
      tile(t('report_yesterday'), o.yesterday, 'YESTERDAY'),
      tile(t('report_this_week'), o.week, 'THIS_WEEK'),
      tile(t('report_this_month'), o.month, 'THIS_MONTH', o.lastMonthSoFar, periodText(o.lastMonthSoFar.period)));
    const chart = card(t('home_last14'), bars(o.days.map((b) => ({
      label: D.dateText(b.start).slice(0, 5), value: Math.max(0, b.total), today: b.start === today,
      title: `${D.dateText(b.start)}: ${money(b.total)} (${b.sales})`,
    }))));
    // The tills: their cards in Google Drive (name, last seen, app version) and, from a backup too, their sales.
    const shiftsByTill = new Map(o.openShifts.map((x) => [Number(x.device_no), x]));
    const cards = new Map((S.status.cards ?? []).map((c) => [c.dev, c]));
    const sold = new Map(o.tills.map((x) => [x.dev, x]));
    const devs = [...new Set([...cards.keys(), ...sold.keys()])].sort((a, b) => a - b);
    const tills = card(t('home_tills'), devs.length === 0 ? h('p', { class: 'muted', text: t('sync_tills_empty') }) :
      h('ul', { class: 'plain' }, devs.map((dev) => {
        const c = cards.get(dev);
        const sh = shiftsByTill.get(dev);
        const last = sold.get(dev)?.lastSale;
        const parts = c ? t('sync_till_row', c.name || t('till_number', dev), c.lastSeen ? D.dateTimeText(c.lastSeen) : '–', c.app || '?').split('\n') : [t('till_number', dev)];
        const prefix = c?.prefix || sold.get(dev)?.prefix;
        return h('li', null, h('strong', { text: parts[0] }), prefix ? h('span', { class: 'muted', text: ` · ${t('receipts_prefix', prefix)}` }) : null,
          parts[1] ? h('div', { class: 'small muted', text: parts[1] }) : null,
          last ? h('div', { class: 'small muted', text: t('home_last_sale', D.dateTimeText(last)) }) : null,
          sh ? h('div', { class: 'small', text: t('home_till_shift', o.staff[sh.opened_by] ?? '–', D.dateTimeText(sh.opened_at)) }) : null);
      })));
    const look = [];
    if (o.lowStock > 0) look.push(h('li', null, h('a', { href: '#stock/low', text: t('home_low', o.lowStock) })));
    for (const p of o.checksToday) {
      const c = p.checks;
      const notes = [];
      if (c.afterPay.count > 0) notes.push(t('check_after_pay', c.afterPay.count));
      if (c.drawerOpens > 0) notes.push(t('check_drawer'));
      if (c.floatDiffs.amount < 0) notes.push(t('check_float_diff', c.floatDiffs.count));
      if (p.overShort < 0) notes.push(`${t('st_difference')} ${money(p.overShort)}`);
      if (notes.length) look.push(h('li', null, h('a', { href: '#reports/TODAY/staff' }, h('strong', { text: p.name ?? '–' }), `: ${notes.join(' · ')}`)));
    }
    const lookCard = card(t('home_look'), look.length ? h('ul', { class: 'plain' }, look) : h('p', { class: 'muted', text: t('home_nothing') }),
      S.status.counts?.lastSoldAt ? h('p', { class: 'small muted', text: t('home_last_sale', D.dateTimeText(S.status.counts.lastSoldAt)) }) : null);
    return [tiles, h('div', { class: 'grid2' }, chart, h('div', { class: 'stack' }, lookCard, tills))];
  });
}

// ------------------------------------------------------------------------------------------- reports

function reportPeriod() {
  const r = S.report;
  const today = D.today();
  if (r.preset) {
    const p = D.presetPeriod(r.preset, today);
    return { p, compare: D.presetComparison(r.preset, p) };
  }
  const p = new D.Period(r.from, r.to);
  return { p, compare: p.days > 7 ? p.sameDaysBefore() : p.previous() };
}

function reports(view, id) {
  const [preset, extra] = (id ?? '').split('/');
  if (preset && D.PRESETS.includes(preset)) S.report = { preset };
  const { p, compare } = reportPeriod();
  const chips = h('div', { class: 'chips' }, D.PRESETS.map((x) =>
    h('a', { class: `chip${S.report.preset === x ? ' active' : ''}`, href: `#reports/${x}` }, t(`report_${x.toLowerCase()}`))));
  const today = D.today();
  const from = h('input', { type: 'date', value: D.isoDate(p.from), max: D.isoDate(today), 'aria-label': t('report_from') });
  const to = h('input', { type: 'date', value: D.isoDate(p.to - 1), max: D.isoDate(today), 'aria-label': t('report_to') });
  const custom = h('form', { class: `custom${S.report.preset ? '' : ' active'}`, onsubmit: (e) => {
    e.preventDefault();
    const a = D.parseIso(from.value);
    const b = D.parseIso(to.value);
    if (a == null || b == null) return;
    S.report = { from: Math.min(a, b), to: Math.max(a, b) + 1 };
    if (location.hash === '#reports') render();
    else location.hash = '#reports';
  } }, h('span', { class: 'chip-label', text: t('report_custom') }), from, '–', to, h('button', { class: 'btn small', type: 'submit' }, t('show')));
  const exports = h('div', { class: 'exports' }, h('span', { class: 'chip-label', text: t('export_title') }),
    [['summary', 'export_summary'], ['daily', 'export_daily'], ['products', 'export_products'], ['receipts', 'export_receipts']].map(([what, key]) =>
      h('button', { class: 'btn small', type: 'button', onclick: async (e) => {
        e.target.disabled = true;
        try {
          const r = await rpc('exportCsv', { what, p, decimals: S.currency.decimals });
          const name = { summary: 'summary', daily: 'daily-sales', products: 'products-sold', receipts: 'receipts' }[what];
          download(`lekaspos-${name}-${D.isoDate(p.from)}_${D.isoDate(p.to - 1)}.csv`, r.text);
        } catch (err) {
          S.error = err;
          render();
        } finally {
          e.target.disabled = false;
        }
      } }, t(key))));
  const body = h('div', { class: 'report' });
  view.append(h('div', { class: 'toolbar' }, chips, custom), h('p', { class: 'period', text: periodText(p) }), body, exports);
  fill(body, () => Promise.all([rpc('report', { p, compare }), rpc('stockValue')]), ([rep, stock]) => reportBody(rep, stock, p, extra));
}

function download(name, text) {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
  const a = h('a', { href: url, download: name });
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

function reportBody(rep, stock, p, extra) {
  const tt = rep.totals;
  const out = [];
  const sum = [
    row(t('report_sales_count', tt.saleCount), money(tt.total + tt.refundTotal), { bold: true }),
    tt.refundCount > 0 ? row(t('report_refunds_count', tt.refundCount), money(-tt.refundTotal)) : null,
    tt.voidCount > 0 ? row(t('report_voids'), String(tt.voidCount)) : null,
    tt.discount !== 0 ? row(t('report_discounts'), money(tt.discount)) : null,
    row(t('report_net_ex_tax'), money(tt.netEx)),
    tt.tax !== 0 ? row(t('report_tax'), money(tt.tax)) : null,
    tt.rounding !== 0 ? row(t('report_rounding'), money(tt.rounding)) : null,
    row(t('report_total'), money(tt.total), { bold: true }),
    row(t('report_cost'), money(tt.cost)),
    row(t('report_profit'), money(tt.grossProfit), { bold: true }),
    rep.marginBp != null ? row(t('report_margin'), pct(rep.marginBp)) : null,
    tt.saleCount > 0 ? row(t('report_average'), money(rep.averageSale)) : null,
  ];
  if (rep.previous.total !== 0) {
    const change = D.changeBp(tt.total, rep.previous.total);
    sum.push(row(t('report_vs_previous', periodText(rep.compare), money(rep.previous.total)), change == null ? '-' : `${change > 0 ? '+' : ''}${pct(change)}`));
  }
  out.push(card(t('report_summary'), sum));

  if (rep.buckets.length && p.days > 1) {
    const g = rep.granularity;
    const label = (b) => (g === 'DAY' ? D.dateText(b.start) : g === 'WEEK' ? t('report_range', D.dateText(b.start), D.dateText(b.end - 1)) : monthName(b.start));
    const short = (b) => (g === 'MONTH' ? monthName(b.start, true) : D.dateText(b.start).slice(0, 5));
    out.push(card(t(g === 'DAY' ? 'report_by_day' : g === 'WEEK' ? 'report_by_week' : 'report_by_month'),
      bars(rep.buckets.map((b) => ({ label: short(b), value: Math.max(0, b.total), title: `${label(b)}: ${money(b.total)} (${b.sales})` }))),
      table([t('day'), { label: t('st_sales'), num: true }, { label: t('report_total'), num: true }, { label: t('report_net_ex_tax'), num: true }, { label: t('report_profit'), num: true }],
        rep.buckets.map((b) => h('tr', null, td(label(b)), td(String(b.sales), { num: true }),
          td(b.sales === 0 && b.total === 0 ? '-' : money(b.total), { num: true }), td(money(b.netEx), { num: true }), td(money(b.grossProfit), { num: true }))))));
  }
  const grid = h('div', { class: 'grid2' });
  if (rep.payments.length) grid.append(card(t('report_payments'), rep.payments.map((x) => row(t('report_count_label', x.name ?? '-', x.count), money(x.amount)))));
  if (rep.staff.length > 1 || rep.staff.some((x) => x.staff_id !== '0')) {
    grid.append(card(t('report_cashiers'), rep.staff.map((x) => row(t('report_count_label', x.name ?? '-', x.saleCount), money(x.total)))));
  }
  if (rep.categories.length) {
    grid.append(card(t('report_categories'), rep.categories.map((x) => row(x.name ?? t('report_no_category'), t('report_net_profit', money(x.netEx), money(x.grossProfit))))));
  }
  if (rep.topProducts.length) {
    const box = h('div');
    const show = (list) => put(box, ...list.map((x) => row(t('report_qty_label', x.name ?? t('report_other_items'), qtyText(x.qty)), money(x.netEx))));
    show(rep.topProducts);
    const all = rep.topProducts.length >= 20
      ? h('button', { class: 'btn small', type: 'button', onclick: async (e) => { e.target.remove(); show(await rpc('products', { p, limit: null })); } }, t('show_all')) : null;
    grid.append(card(t('report_top_products'), box, all));
  }
  grid.append(card(t('report_stock'),
    row(t('report_stock_value', stock.products), money(stock.value), { bold: true }),
    stock.categories.slice(0, 12).map((c) => row(c.name ?? t('report_no_category'), money(c.value)))));
  out.push(grid);

  const slowBox = h('div');
  const slow = card(t('report_slow_movers'), slowBox);
  const loadSlow = () => fill(slowBox, () => rpc('slowMovers', { p }), (r) => [
    h('p', { class: 'small', text: t('report_slow_header', periodText(p), r.count, money(r.value)) }),
    r.items.length === 0 ? h('p', { class: 'muted', text: t('report_slow_empty') }) :
      table([t('product'), { label: t('product_stock_label'), num: true }, { label: t('report_stock'), num: true }],
        r.items.map((x) => linkRow(`#products/${x.id}`, [td(x.name), td(`${qtyText(x.qty)} ${x.unit ?? ''}`, { num: true }), td(money(x.value), { num: true })]))),
  ]);
  slowBox.append(h('button', { class: 'btn', type: 'button', onclick: loadSlow }, t('report_slow_movers')));

  const staffBox = h('div');
  const staffCard = card(t('staff_check_title'), h('p', { class: 'small', text: t('staff_check_intro') }), staffBox);
  const loadStaff = () => fill(staffBox, () => rpc('staffCheck', { p }), (list) => staffCheck(list));
  staffBox.append(h('button', { class: 'btn', type: 'button', onclick: loadStaff }, t('staff_check_title')));
  out.push(h('div', { class: 'grid2' }, slow, staffCard));
  out.push(h('p', { class: 'small muted', text: t('report_note') }));
  if (extra === 'staff') setTimeout(loadStaff, 0);
  return out;
}

function monthName(day, short = false) {
  const ymd = D.toYmd(day);
  const date = new Date(Date.UTC(Math.floor(ymd / 10000), Math.floor(ymd / 100) % 100 - 1, 1));
  return new Intl.DateTimeFormat(language() === 'ms' ? 'ms-MY' : 'en-GB', { month: short ? 'short' : 'long', year: 'numeric', timeZone: 'UTC' }).format(date);
}

function staffCheck(list) {
  if (list.length === 0) return h('p', { class: 'muted', text: t('staff_check_none') });
  const tally = (key, x) => (x.count > 0 ? row(t(key, x.count), money(Math.abs(x.amount))) : null);
  return list.map((s) => {
    const c = s.checks;
    return h('div', { class: 'person' }, h('h3', { text: s.name ?? '-' }),
      row(t('check_sales', s.sales.count), money(s.sales.amount), { bold: true }),
      c.afterPay.count > 0 ? row(t('check_after_pay', c.afterPay.count), money(c.afterPay.amount), { bold: true, warn: true }) : null,
      tally('check_cleared', c.cleared), tally('check_removed', c.removed), tally('check_voids', c.voids), tally('check_refunds', c.refunds),
      c.discounts > 0 ? row(t('check_discounts'), String(c.discounts)) : null,
      c.drawerOpens > 0 ? row(t('check_drawer'), String(c.drawerOpens), { bold: true, warn: true }) : null,
      c.copies > 0 ? row(t('check_copies'), String(c.copies)) : null,
      tally('check_cash_out', c.cashOut), tally('check_write_off', c.writeOffs),
      c.continued > 0 ? row(t('check_continued'), String(c.continued)) : null,
      c.floatDiffs.count > 0 ? row(t('check_float_diff', c.floatDiffs.count), signed(c.floatDiffs.amount), { bold: c.floatDiffs.amount < 0, warn: c.floatDiffs.amount < 0 }) : null,
      s.shifts > 0 ? row(t('check_over_short', s.shifts, s.shortShifts), signed(s.overShort), { bold: s.overShort < 0, warn: s.overShort < 0 }) : null);
  });
}

// ------------------------------------------------------------------------------------------- sales

let salesFilter = { receipt: '', day: '' };

function sales(view, id) {
  const search = h('input', { type: 'search', placeholder: t('sales_search_hint'), value: salesFilter.receipt, 'aria-label': t('sales_search_hint') });
  const day = h('input', { type: 'date', value: salesFilter.day, max: D.isoDate(D.today()), 'aria-label': t('day') });
  const list = h('div');
  const form = h('form', { class: 'toolbar', onsubmit: (e) => { e.preventDefault(); salesFilter = { receipt: search.value.trim(), day: day.value }; render(); } },
    search, day, h('button', { class: 'btn', type: 'submit' }, t('search')),
    (salesFilter.receipt || salesFilter.day) ? h('button', { class: 'btn ghost', type: 'button', onclick: () => { salesFilter = { receipt: '', day: '' }; render(); } }, t('all_days')) : null);
  view.append(form, list);
  const dayNo = D.parseIso(salesFilter.day);
  pagedTable(list, [t('receipt'), t('time'), { label: t('items'), num: true }, { label: t('total'), num: true }, ''],
    (after) => rpc('sales', { after, limit: 50, receipt: salesFilter.receipt || null, day: dayNo }),
    (x) => linkRow(`#sales/${x.id}`, [td(x.receipt_no), td(D.dateTimeText(x.sold_at)), td(String(x.line_count), { num: true }),
      td(money(x.total), { num: true }), td([x.kind === 1 ? tag(t('sale_refund'), 'refund') : null, x.status === 1 ? tag(t('sale_voided'), 'void') : null])],
    x.status === 1 ? 'voided' : null),
    (x) => ({ sold_at: x.sold_at, id: x.id }),
    salesFilter.receipt ? t('sales_none_found') : t('sales_empty'));
  if (id) showSale(id);
}

function showSale(id) {
  const box = h('div');
  openModal(t('receipt'), box, '#sales');
  const mine = gen;
  rpc('sale', { id }).then((x) => {
    if (mine !== gen || !x) return;
    modal?.querySelector('h2')?.replaceChildren(`${t('receipt')} ${x.receipt_no}`);
    const lines = x.lines.map((l) => h('tr', null,
      td([h('div', { text: l.name }),
        l.promo_name ? h('div', { class: 'small muted', text: t('promotion', l.promo_name) }) : null,
        l.discount ? h('div', { class: 'small muted', text: t('line_discount', money(l.discount)) }) : null,
        l.price_overridden ? h('div', { class: 'small muted', text: t('price_changed') }) : null]),
      td(`${qtyText(l.qty)} × ${money(l.unit_price)}`, { num: true }), td(money(l.net), { num: true })));
    put(box, 
      h('div', { class: 'tags' }, x.kind === 1 ? tag(t('sale_refund'), 'refund') : null, x.status === 1 ? tag(t('sale_voided'), 'void') : null),
      h('div', { class: 'facts' },
        row(t('time'), D.dateTimeText(x.sold_at)), row(t('till'), `${tillName(x.till)}`),
        row(t('cashier'), x.staff_name ?? '–'), x.customer_name ? row(t('customer'), x.customer_name) : null),
      x.original ? h('p', null, h('a', { href: `#sales/${x.original.id}`, text: t('refund_of_receipt', x.original.receipt_no) })) : null,
      table([t('product'), { label: t('qty'), num: true }, { label: t('total'), num: true }], lines),
      h('div', { class: 'facts' },
        row(t('subtotal'), money(x.subtotal)), x.discount ? row(t('report_discounts'), money(-x.discount)) : null,
        x.tax ? row(t('report_tax'), money(x.tax)) : null, x.rounding ? row(t('report_rounding'), money(x.rounding)) : null,
        row(t('total'), money(x.total), { bold: true }), row(t('paid'), money(x.paid)), x.change_due ? row(t('change'), money(x.change_due)) : null),
      h('h3', { text: t('report_payments') }), x.payments.map((pm) => row(pm.name ?? '-', money(pm.amount))),
      x.voids.length ? h('div', { class: 'warnbox' }, x.voids.map((v) => h('p', { text: t('voided_by', D.dateTimeText(v.at), v.staff_name ?? '–', v.reason) }))) : null,
      x.refunds.length ? [h('h3', { text: t('sale_refunds') }), x.refunds.map((r) => h('p', null, h('a', { href: `#sales/${r.id}`, text: `${r.receipt_no} · ${money(r.total)}` }), r.status === 1 ? ` (${t('sale_voided')})` : null))] : null);
  }).catch((e) => put(box, h('p', { class: 'error', text: errorText(e) })));
}

// ------------------------------------------------------------------------------------------- products

let productFilter = { search: '', filter: null };

function sellMode(m) {
  return t(m === 1 ? 'sell_mode_weight' : m === 2 ? 'sell_mode_open' : 'sell_mode_unit');
}

function productRows(box, filter) {
  pagedTable(box, [t('product'), t('product_category'), { label: t('product_price'), num: true }, { label: t('product_cost'), num: true }, { label: t('product_stock_label'), num: true }],
    (after) => rpc('productList', { ...filter, after, limit: 50 }),
    (x) => {
      const low = x.track_stock && x.low_stock > 0 && x.stock <= x.low_stock;
      return linkRow(`#products/${x.id}`, [
        td([h('div', null, x.name, x.active ? null : [' ', tag(t('hidden'), 'muted')]), (x.sku || x.barcodes) ? h('div', { class: 'small muted', text: [x.sku, x.barcodes].filter(Boolean).join(' · ') }) : null]),
        td(x.category ?? '–'), td(money(x.price), { num: true }), td(money(x.cost), { num: true }),
        td(x.track_stock ? `${qtyText(x.stock)} ${x.unit}` : '–', { num: true, class: low ? 'num low' : null })], low ? 'low-row' : null);
    },
    (x) => ({ name_key: x.name_key, id: x.id }),
    t('nothing_found'));
}

function products(view, id) {
  const search = h('input', { type: 'search', placeholder: t('products_search_hint'), value: productFilter.search, 'aria-label': t('products_search_hint') });
  const chips = h('div', { class: 'chips' }, [[null, 'all'], ['low', 'inv_low_stock'], ['hidden', 'hidden']].map(([f, key]) =>
    h('button', { class: `chip${productFilter.filter === f ? ' active' : ''}`, type: 'button', onclick: () => { productFilter.filter = f; render(); } }, t(key))));
  const list = h('div');
  view.append(h('form', { class: 'toolbar', onsubmit: (e) => { e.preventDefault(); productFilter.search = search.value; render(); } },
    search, h('button', { class: 'btn', type: 'submit' }, t('search')), chips), list);
  productRows(list, productFilter);
  if (id) showProduct(id, '#products');
}

function showProduct(id, back) {
  const box = h('div');
  openModal(t('product'), box, back);
  const mine = gen;
  rpc('product', { id }).then((x) => {
    if (mine !== gen || !x) return;
    modal?.querySelector('h2')?.replaceChildren(x.name);
    const hist = x.history.map((e) => {
      let what;
      if (e.type === 'sale') what = t(e.status === 1 ? 'history_voided' : e.kind === 1 ? 'history_refund' : 'history_sale', e.receipt_no);
      else if (e.type === 'count') what = t('history_count', qtyText(e.counted), e.expected == null ? '?' : qtyText(e.expected));
      else what = moveText(e.kind, e.reason);
      const q = e.type === 'count' ? (e.expected == null ? null : e.counted - e.expected) : e.qty;
      return h('tr', { class: e.status === 1 ? 'voided' : null }, td(D.dateTimeText(e.at)), td(what),
        td(q == null ? '' : `${q > 0 ? '+' : ''}${qtyText(q)}`, { num: true }));
    });
    put(box, 
      h('div', { class: 'facts' },
        row(t('product_price'), `${money(x.price)} / ${x.unit}`, { bold: true }), row(t('product_cost'), money(x.cost)),
        row(t('product_category'), x.category ?? t('product_no_category')), row(t('sold_by'), sellMode(x.sell_mode)),
        x.sku ? row(t('product_sku'), x.sku) : null, row(t('product_barcodes'), x.barcode_rows.map((b) => b.code).join(', ') || '–'),
        x.tax ? row(t('tax_rate'), `${x.tax.name} (${plain(x.tax.rate_bp, 2)}%)`) : null,
        x.track_stock ? row(t('product_stock_label'), `${qtyText(x.stock)} ${x.unit}`, { bold: true, warn: x.low_stock > 0 && x.stock <= x.low_stock }) : row(t('product_track_stock'), t('product_untracked')),
        x.track_stock && x.low_stock > 0 ? row(t('product_low_stock'), qtyText(x.low_stock)) : null,
        x.active ? null : row(t('hidden'), '✓')),
      h('h3', { text: t('stock_history') }),
      hist.length ? table([t('time'), t('what'), { label: t('qty'), num: true }], hist) : h('p', { class: 'muted', text: t('inv_movements_empty') }));
  }).catch((e) => put(box, h('p', { class: 'error', text: errorText(e) })));
}

const REASONS = { damaged: 'reason_damaged', expired: 'reason_expired', lost: 'reason_lost', theft: 'reason_theft', own_use: 'reason_own_use', returned: 'reason_returned', found: 'reason_found', correction: 'reason_correction', other: 'reason_other' };

/** "Written off · Damaged: box crushed" (InventoryUi.movementText). */
function moveText(kind, reason) {
  const k = { 1: 'move_receive', 3: 'move_waste', 4: 'move_return', 5: 'move_transfer_in', 6: 'move_transfer_out', 7: 'move_opening' }[kind] ?? 'move_adjust';
  let label = t(k);
  let note = null;
  if (reason) {
    const colon = reason.indexOf(': ');
    const code = colon >= 0 ? reason.slice(0, colon) : reason;
    if (REASONS[code]) {
      if (kind !== 4) label += ` · ${t(REASONS[code])}`;
      note = colon >= 0 ? reason.slice(colon + 2) : null;
    } else {
      note = reason;
    }
  }
  return note ? `${label}: ${note}` : label;
}

// ------------------------------------------------------------------------------------------- stock

const STOCK_TABS = [['low', 'inv_low_stock'], ['changes', 'inv_movements'], ['deliveries', 'inv_purchases'], ['value', 'stock_value_tab']];

function stock(view, id) {
  const [tab, sub] = (id ?? 'low').split('/');
  const current = STOCK_TABS.some(([k]) => k === tab) ? tab : 'low';
  view.append(h('div', { class: 'chips' }, STOCK_TABS.map(([k, key]) => h('a', { class: `chip${k === current ? ' active' : ''}`, href: `#stock/${k}` }, t(key)))));
  const box = h('div');
  view.append(box);
  if (current === 'low') {
    productRows(box, { filter: 'low' });
    box.prepend(h('p', { class: 'small muted', text: t('inv_low_empty') }));
  } else if (current === 'changes') {
    loadStaff().then(() => pagedTable(box, [t('time'), t('product'), t('what'), { label: t('qty'), num: true }, t('who')],
      (after) => rpc('movements', { after, limit: 50 }),
      (m) => linkRow(`#stock/changes/${m.product_id}`, [td(D.dateTimeText(m.at)), td(m.name ?? '–'),
        td(m.kind === 0 ? [t('move_counted', qtyText(m.counted)), m.reason ? `: ${m.reason}` : ''].join('') : moveText(m.kind, m.reason)),
        td(`${m.qty > 0 ? '+' : ''}${qtyText(m.qty)} ${m.unit ?? ''}`, { num: true }), td(S.staff[m.staff_id] ?? '–')]),
      (m) => ({ hlc: m.hlc, id: m.id }), t('inv_movements_empty')));
    if (sub) showProduct(sub, '#stock/changes');
  } else if (current === 'deliveries') {
    pagedTable(box, [t('time'), t('supplier'), t('receipt'), { label: t('items'), num: true }, { label: t('total'), num: true }],
      (after) => rpc('purchases', { after, limit: 50 }),
      (x) => linkRow(`#stock/deliveries/${x.id}`, [td(D.dateTimeText(x.at)), td(x.supplier ?? '–'), td(x.ref_no ?? ''), td(String(x.lines), { num: true }), td(money(x.total), { num: true })]),
      (x) => ({ at: x.at, id: x.id }), t('inv_purchases_empty'));
    if (sub) showPurchase(sub);
  } else {
    fill(box, () => rpc('stockValue'), (v) => card(t('report_stock'),
      row(t('report_stock_value', v.products), money(v.value), { bold: true }),
      v.categories.map((c) => row(`${c.name ?? t('report_no_category')} (${c.products})`, money(c.value)))));
  }
}

function showPurchase(id) {
  const box = h('div');
  openModal(t('inv_purchases'), box, '#stock/deliveries');
  const mine = gen;
  rpc('purchase', { id }).then((x) => {
    if (mine !== gen || !x) return;
    modal?.querySelector('h2')?.replaceChildren(t('delivery', x.ref_no ?? ''));
    put(box, 
      h('div', { class: 'facts' }, row(t('time'), D.dateTimeText(x.at)), row(t('supplier'), x.supplier ?? '–'), x.note ? row(t('st_note'), x.note) : null),
      table([t('product'), { label: t('qty'), num: true }, { label: t('unit_cost'), num: true }, { label: t('total'), num: true }],
        x.lines.map((l) => h('tr', null, td(l.name ?? '–'), td(`${qtyText(l.qty)} ${l.unit ?? ''}`, { num: true }), td(money(l.unit_cost), { num: true }), td(money(l.total), { num: true })))),
      row(t('inv_purchase_total', money(x.total)), '', { bold: true }));
  }).catch((e) => put(box, h('p', { class: 'error', text: errorText(e) })));
}

// ------------------------------------------------------------------------------------------- shifts

async function loadStaff() {
  if (Object.keys(S.staff).length) return;
  try { S.staff = await rpc('staffNames'); } catch { /* names only */ }
}

function shifts(view, id) {
  const box = h('div');
  view.append(h('h2', { class: 'view-title', text: t('shift_history') }), box);
  loadStaff().then(() => {
    pagedTable(box, [t('st_till'), t('shift_opened_by'), t('st_opened'), t('st_closed'), { label: t('st_expected'), num: true }, { label: t('st_counted'), num: true }, { label: t('st_difference'), num: true }],
      (after) => rpc('shifts', { after, limit: 50 }),
      (x) => {
        const diff = x.counted_cash != null && x.expected_cash != null ? x.counted_cash - x.expected_cash : null;
        return linkRow(`#shifts/${x.id}`, [td(tillName(x.device_no)), td(S.staff[x.opened_by] ?? '–'), td(D.dateTimeText(x.opened_at)),
          td(x.closed_at ? D.dateTimeText(x.closed_at) : tag(t('shift_open_tag'), 'open')),
          td(x.expected_cash != null ? money(x.expected_cash) : '', { num: true }), td(x.counted_cash != null ? money(x.counted_cash) : '', { num: true }),
          td(diff == null ? '' : signed(diff), { num: true, class: diff < 0 ? 'num low' : null })]);
      },
      (x) => ({ opened_at: x.opened_at, id: x.id }), t('shift_history_empty'));
  });
  if (id) showShift(id);
}

function showShift(id) {
  const box = h('div');
  openModal(t('shift_report'), box, '#shifts');
  const mine = gen;
  rpc('shiftReport', { id }).then((r) => {
    if (mine !== gen || !r) return;
    const c = r.cash;
    const sections = [
      h('div', { class: 'facts' }, row(t('st_till'), tillName(r.device_no)), row(t('st_opened'), D.dateTimeText(r.opened_at)),
        r.openedByName ? row(t('st_opened_by'), r.openedByName) : null,
        row(t('st_closed'), r.closed_at ? D.dateTimeText(r.closed_at) : t('st_still_open')),
        r.closedByName ? row(t('st_closed_by'), r.closedByName) : null),
      h('h3', { text: t('st_sales_section') }),
      row(`${t('st_sales')} (${r.sales})`, money(r.salesTotal), { bold: true }),
      r.refunds > 0 ? row(`${t('st_refunds')} (${r.refunds})`, money(r.refundsTotal)) : null,
      r.voids > 0 ? row(`${t('st_voids')} (${r.voids})`, money(r.voidsTotal)) : null,
      r.discounts !== 0 ? row(t('st_discounts'), money(r.discounts)) : null,
      r.tax !== 0 ? row(t('st_tax'), money(r.tax)) : null,
      r.methods.length ? [h('h3', { text: t('st_payments_section') }), r.methods.map((m) => row(`${m.name} (${m.count})`, money(m.amount)))] : null,
      h('h3', { text: t('st_cash_section') }),
      row(t('st_opening_float'), money(c.openingFloat)), row(t('st_cash_sales'), money(c.cashSales)),
      c.cashRefunds !== 0 ? row(t('st_cash_refunds'), money(c.cashRefunds)) : null,
      c.voided !== 0 ? row(t('st_voided'), money(-c.voided)) : null,
      c.cashIn !== 0 ? row(t('st_cash_in'), money(c.cashIn)) : null,
      c.cashOut !== 0 ? row(t('st_cash_out'), money(-c.cashOut)) : null,
      c.drops !== 0 ? row(t('st_drops'), money(-c.drops)) : null,
      c.creditRepayments !== 0 ? row(t('st_credit_repayments'), money(c.creditRepayments)) : null,
      row(t('st_expected'), money(c.expected), { bold: true }),
      r.counted_cash != null ? [row(t('st_counted'), money(r.counted_cash), { bold: true }),
        row(t('st_difference'), signed(r.counted_cash - c.expected), { bold: true, warn: r.counted_cash - c.expected < 0 })] : null,
      (r.creditCharged !== 0 || r.creditRepaid.length) ? [h('h3', { text: t('st_credit_section') }), row(t('st_credit_charged'), money(r.creditCharged)),
        r.creditRepaid.map((m) => row(`${t('st_credit_repaid')}: ${m.name} (${m.count})`, money(m.amount)))] : null,
      shiftChecks(r.checks),
      r.moveList.length ? [h('h3', { text: t('shift_moves') }), r.moveList.map((m) =>
        row(`${D.timeText(m.at)} · ${t(m.kind === 1 ? 'shift_cash_in' : m.kind === 2 ? 'shift_cash_out' : 'shift_drop')}${m.reason ? `: ${m.reason}` : ''}`, money(m.amount)))] : null,
      r.note?.trim() ? [h('h3', { text: t('st_note') }), h('p', { text: r.note.trim() })] : null,
    ];
    put(box, sections);
  }).catch((e) => put(box, h('p', { class: 'error', text: errorText(e) })));
}

/** The shift report's checks (ShiftReportLayout.checkRows). */
function shiftChecks(c) {
  const rows = [];
  const tally = (label, x) => { if (x.count > 0) rows.push(row(`${label} (${x.count})`, money(Math.abs(x.amount)))); };
  tally(t('st_cleared'), c.cleared);
  tally(t('st_removed'), c.removed);
  if (c.afterPay.count > 0) rows.push(row(`${t('st_after_pay')} (${c.afterPay.count})`, money(c.afterPay.amount), { bold: true, warn: true }));
  if (c.drawerOpens > 0) rows.push(row(t('st_drawer_opens'), String(c.drawerOpens), { bold: true, warn: true }));
  if (c.copies > 0) rows.push(row(t('st_copies'), String(c.copies)));
  if (c.continued > 0) rows.push(row(t('st_continued'), String(c.continued)));
  if (c.floatDiffs.count > 0) rows.push(row(`${t('st_float_diff')} (${c.floatDiffs.count})`, signed(c.floatDiffs.amount)));
  return rows.length ? [h('h3', { text: t('st_checks_section') }), rows] : null;
}

// ------------------------------------------------------------------------------------------- activity log

const ACTIONS = [
  [1, 'audit_sale_void'], [2, 'audit_refund'], [3, 'audit_price_override'], [4, 'audit_line_discount'], [5, 'audit_bill_discount'],
  [10, 'audit_bill_cancel'], [6, 'audit_drawer'], [7, 'audit_reprint'], [8, 'audit_product_price'], [9, 'audit_product_delete'],
  [11, 'audit_approval'], [12, 'audit_sign_in'], [13, 'audit_pin_lockout'], [14, 'audit_staff'], [15, 'audit_role'],
  [16, 'audit_shift_open'], [17, 'audit_shift_close'], [18, 'shift_cash_in'], [19, 'shift_cash_out'], [20, 'shift_drop'],
  [21, 'credit_adjust_title'], [22, 'audit_over_limit'], [23, 'audit_pin_reset'], [26, 'audit_credit_limit'],
  [28, 'audit_credit_payment'], [24, 'audit_product_import'], [25, 'audit_promotion'], [27, 'audit_settings'],
  [29, 'audit_line_remove'], [30, 'audit_line_remove_after_pay'], [31, 'audit_bill_cancel_after_pay'], [32, 'audit_write_off'],
  [33, 'audit_shift_continued'], [34, 'audit_float_difference'],
];
const MONEY_ACTIONS = new Set([1, 2, 3, 8, 10, 16, 17, 18, 19, 20, 21, 22, 26, 28, 29, 30, 31, 32, 34]);
const WARN_ACTIONS = new Set([6, 30, 31, 34]);
const PERMS = [
  [3, 'perm_discount'], [2, 'perm_price'], [8, 'perm_cancel_bill'], [0, 'perm_void'], [18, 'perm_void_last'], [1, 'perm_refund'],
  [5, 'perm_reprint'], [4, 'perm_drawer'], [11, 'perm_cash_move'], [12, 'perm_shift_report'], [14, 'perm_customers'],
  [15, 'perm_credit_sale'], [16, 'perm_credit_limit'], [6, 'perm_products'], [9, 'perm_stock'], [17, 'perm_reports'],
  [13, 'perm_audit'], [7, 'perm_settings'], [10, 'perm_staff'],
];
let auditFilter = 0;

function actionName(a) {
  return t(ACTIONS.find(([n]) => n === a)?.[1] ?? 'audit_other');
}

function permNames(bits) {
  const b = BigInt(bits ?? 0);
  return PERMS.filter(([bit]) => (b & (1n << BigInt(bit))) !== 0n).map(([, key]) => t(key)).join(', ');
}

function activity(view) {
  const select = h('select', { 'aria-label': t('audit_filter'), onchange: () => { auditFilter = Number(select.value); render(); } },
    h('option', { value: 0, selected: auditFilter === 0 }, t('audit_all')),
    ACTIONS.map(([n, key]) => h('option', { value: n, selected: auditFilter === n }, t(key))));
  const box = h('div');
  view.append(h('div', { class: 'toolbar' }, h('label', null, `${t('audit_filter')}: `, select)), box);
  loadStaff().then(() => pagedTable(box, [t('time'), t('what'), t('who'), { label: t('amount'), num: true }],
    (after) => rpc('audit', { action: auditFilter, after, limit: 50 }),
    (a) => {
      const who = [S.staff[a.staff_id], a.approved_by && S.staff[a.approved_by] ? t('audit_approved_by', S.staff[a.approved_by]) : null].filter(Boolean).join(' · ');
      const detail = a.action === 11 ? permNames(a.amount) : a.detail;
      return h('tr', { class: WARN_ACTIONS.has(a.action) ? 'warn-row' : null }, td(D.dateTimeText(a.at)),
        td([h('div', { text: actionName(a.action) }), detail ? h('div', { class: 'small muted', text: detail }) : null]), td(who || '–'),
        td(a.amount && MONEY_ACTIONS.has(a.action) ? money(a.amount) : '', { num: true }));
    },
    (a) => ({ at: a.at, id: a.id }), t('audit_empty')));
}

boot();

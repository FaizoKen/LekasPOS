// The manager web page's building blocks: elements made from text (every text from the shop is set as text, never
// as HTML), cards, rows, tables and a short message at the bottom of the screen. Used by app.js and edit.js.

export function h(tag, props, ...children) {
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
export function put(el, ...children) {
  el.replaceChildren();
  return append(el, children);
}

/** Adds [children] (elements, text, or lists of them) to [el]. */
export function append(el, ...children) {
  for (const c of children.flat(Infinity)) {
    if (c == null || c === false) continue;
    el.appendChild(typeof c === 'string' || typeof c === 'number' ? document.createTextNode(String(c)) : c);
  }
  return el;
}

const svgNs = 'http://www.w3.org/2000/svg';
export function s(tag, attrs, ...children) {
  const el = document.createElementNS(svgNs, tag);
  for (const [k, v] of Object.entries(attrs ?? {})) el.setAttribute(k, v);
  for (const c of children) if (c) el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
  return el;
}

export function card(title, ...children) {
  return h('section', { class: 'card' }, title ? h('h2', { text: title }) : null, ...children);
}

/** A label / value row like the app's report rows. */
export function row(label, value, opts = {}) {
  return h('div', { class: `row${opts.bold ? ' bold' : ''}${opts.warn ? ' warn' : ''}` },
    h('span', { class: 'label', text: label }), h('span', { class: 'value', text: value ?? '' }));
}

export function table(headers, rows, opts = {}) {
  return h('div', { class: 'table-wrap' },
    h('table', { class: opts.class ?? null },
      h('thead', null, h('tr', null, headers.map((x) => h('th', { class: x.num ? 'num' : null, text: x.label ?? x })))),
      h('tbody', null, rows)));
}

export function td(text, opts = {}) {
  return h('td', { class: opts.num ? 'num' : opts.class ?? null }, text);
}

export function tag(text, kind) {
  return h('span', { class: `tag ${kind ?? ''}`, text });
}

let toastEl = null;
let toastTimer = 0;

/** A short message at the bottom of the screen ("Product saved"), gone after a few seconds. */
export function toast(text) {
  if (!toastEl) {
    toastEl = h('div', { class: 'toast', role: 'status', 'aria-live': 'polite' });
    document.body.appendChild(toastEl);
  }
  toastEl.textContent = text;
  toastEl.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastEl.classList.remove('show'), 3500);
}

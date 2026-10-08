// Checks the website before it is published (pages.yml) and after any edit of the user guide:
// every in-page link has its target, every picture exists, tags are balanced, ids are unique, and the
// English guide (guide.html) and the Malay one (panduan.html) have exactly the same sections.
// Usage (repo root): node scripts/check-site.mjs
import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const site = join(dirname(fileURLToPath(import.meta.url)), '..', 'site');
const problems = [];
const TAGS = ['main', 'nav', 'div', 'p', 'ul', 'ol', 'li', 'dl', 'dt', 'dd', 'table', 'tr', 'th', 'td', 'figure',
  'figcaption', 'h1', 'h2', 'h3', 'h4', 'b', 'i', 'strong', 'code', 'a'];

function ids(html) {
  return [...html.matchAll(/\sid="([^"]+)"/g)].map(m => m[1]);
}

for (const file of readdirSync(site).filter(f => f.endsWith('.html'))) {
  const html = readFileSync(join(site, file), 'utf8');
  for (const t of TAGS) {
    const open = (html.match(new RegExp(`<${t}[\\s>]`, 'g')) || []).length;
    const close = (html.match(new RegExp(`</${t}>`, 'g')) || []).length;
    if (open !== close) problems.push(`${file}: <${t}> opened ${open} times, closed ${close} times`);
  }
  const all = ids(html);
  const seen = new Set();
  for (const id of all) {
    if (seen.has(id)) problems.push(`${file}: id "${id}" is used twice`);
    seen.add(id);
  }
  for (const [, target] of html.matchAll(/href="#([^"]+)"/g)) {
    if (!seen.has(target)) problems.push(`${file}: link to #${target}, but no element has that id`);
  }
  for (const [, src] of html.matchAll(/src="([^"]+)"/g)) {
    if (!/^https?:/.test(src) && !existsSync(join(site, src))) problems.push(`${file}: picture ${src} is missing`);
  }
  for (const [, href] of html.matchAll(/href="([^"#:]+\.html)(?:#[^"]*)?"/g)) {
    if (!existsSync(join(site, href))) problems.push(`${file}: link to ${href}, which does not exist`);
  }
}

const en = join(site, 'guide.html');
const ms = join(site, 'panduan.html');
if (existsSync(en) && existsSync(ms)) {
  const a = ids(readFileSync(en, 'utf8')).join(' ');
  const b = ids(readFileSync(ms, 'utf8')).join(' ');
  if (a !== b) {
    const sa = new Set(a.split(' ')), sb = new Set(b.split(' '));
    const onlyEn = [...sa].filter(x => !sb.has(x)), onlyMs = [...sb].filter(x => !sa.has(x));
    problems.push('guide.html and panduan.html differ in their sections' +
      (onlyEn.length ? `; only in English: ${onlyEn.join(', ')}` : '') +
      (onlyMs.length ? `; only in Malay: ${onlyMs.join(', ')}` : '') +
      (!onlyEn.length && !onlyMs.length ? ' (same ids, different order)' : ''));
  }
}

if (problems.length) {
  console.error(problems.join('\n'));
  process.exit(1);
}
console.log('site/: all pages OK');

// The manager web page's browser parts that run without a browser: the Google Drive reader (against a fake
// fetch), its words (the app's own, and both languages complete), and its files (check-site covers the HTML).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DriveFolder } from '../../site/manager/js/drive.js';
import { APP_STRINGS } from '../../site/manager/js/app-strings.js';
import { render as renderStrings } from './build-strings.mjs';

function fakeFetch(handler) {
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url: new URL(url), auth: init?.headers?.Authorization });
    return handler(new URL(url), calls.length);
  };
  return calls;
}

const reply = (status, body) => new Response(typeof body === 'string' ? body : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

test('Drive: the app folder is listed page by page, with the exact prefix', async () => {
  const calls = fakeFetch((url) => {
    if (!url.searchParams.get('pageToken')) {
      return reply(200, { nextPageToken: 'p2', files: [
        { id: '1', name: 'seg-s-7-00000001.ndjson.gz', size: '120', appProperties: { sha256: 'ab', count: '3' }, createdTime: '2026-10-20T01:02:03.000Z' },
        { id: 'x', name: 'segment-notes.txt', size: '1', createdTime: '2026-10-20T01:02:03.000Z' },
      ] });
    }
    return reply(200, { files: [{ id: '2', name: 'seg-s-7-00000002.ndjson.gz', size: '99', createdTime: '2026-10-20T02:00:00.000Z' }] });
  });
  const files = await new DriveFolder('tok').list('seg', Date.UTC(2026, 9, 20));
  assert.deepEqual(files.map((f) => f.name), ['seg-s-7-00000001.ndjson.gz', 'segment-notes.txt', 'seg-s-7-00000002.ndjson.gz'].filter((n) => n.startsWith('seg')));
  assert.equal(files[0].props.sha256, 'ab');
  assert.equal(files[0].created, Date.UTC(2026, 9, 20, 1, 2, 3));
  assert.equal(calls.length, 2);
  assert.equal(calls[0].auth, 'Bearer tok');
  assert.equal(calls[0].url.searchParams.get('spaces'), 'appDataFolder');
  assert.equal(calls[0].url.searchParams.get('q'), "name contains 'seg' and trashed = false and createdTime > '2026-10-20T00:00:00.000Z'");
  assert.equal(calls[1].url.searchParams.get('pageToken'), 'p2');
});

test('Drive: downloads, a busy Drive is waited for, sign-in and access problems are told apart', async () => {
  fakeFetch((url, n) => (n === 1 ? reply(503, { error: { errors: [{ reason: 'backendError' }] } }) : new Response(new Uint8Array([1, 2, 3]))));
  assert.deepEqual([...await new DriveFolder('t').get({ id: 'f1' })], [1, 2, 3]);
  fakeFetch(() => reply(401, { error: { status: 'UNAUTHENTICATED' } }));
  await assert.rejects(new DriveFolder('t').list('seg'), (e) => e.code === 'SIGN_IN');
  fakeFetch(() => reply(403, { error: { errors: [{ reason: 'insufficientPermissions' }] } }));
  await assert.rejects(new DriveFolder('t').list('seg'), (e) => e.code === 'NO_ACCESS');
  fakeFetch(() => reply(404, { error: { errors: [{ reason: 'notFound' }] } }));
  await assert.rejects(new DriveFolder('t').get({ id: 'gone' }), (e) => e.code === 'DRIVE');
  fakeFetch(() => { throw new TypeError('Failed to fetch'); });
  await assert.rejects(new DriveFolder('t').list('dev'), (e) => e.code === 'OFFLINE');
});

test('the app\'s words are the app\'s (run node scripts/manager/build-strings.mjs after changing them)', () => {
  // A Windows checkout may have CRLF line ends.
  assert.equal(readFileSync(new URL('../../site/manager/js/app-strings.js', import.meta.url), 'utf8').replace(/\r\n/g, '\n'), renderStrings());
  assert.equal(APP_STRINGS.en.report_total, 'Total (after refunds)');
  assert.equal(APP_STRINGS.ms.menu_sales, 'Jualan & bayaran balik');
});

test('the web page\'s own words exist in English and Malay, with the same placeholders', async () => {
  const src = readFileSync(new URL('../../site/manager/js/i18n.js', import.meta.url), 'utf8');
  // i18n.js touches `document` only in setLanguage: import it as text-free module data.
  globalThis.document = { documentElement: {} };
  const { WEB_STRINGS, t, setLanguage } = await import('../../site/manager/js/i18n.js');
  const en = Object.keys(WEB_STRINGS.en).sort();
  const ms = Object.keys(WEB_STRINGS.ms).sort();
  assert.deepEqual(ms, en);
  const marks = (s) => (s.match(/%\d\$[sd]/g) ?? []).sort().join();
  for (const k of en) assert.equal(marks(WEB_STRINGS.ms[k]), marks(WEB_STRINGS.en[k]), `placeholders of ${k}`);
  for (const k of Object.keys(APP_STRINGS.en)) assert.equal(marks(APP_STRINGS.ms[k]), marks(APP_STRINGS.en[k]), `placeholders of ${k}`);
  // Every key the page asks for exists.
  const used = new Set();
  for (const f of ['app.js', 'i18n.js']) {
    const code = readFileSync(new URL(`../../site/manager/js/${f}`, import.meta.url), 'utf8');
    for (const m of code.matchAll(/\bt\('([a-z0-9_]+)'/g)) used.add(m[1]);
  }
  for (const k of used) assert.ok(k in WEB_STRINGS.en || k in APP_STRINGS.en, `missing string ${k}`);
  setLanguage('ms');
  assert.equal(t('report_sales_count', 3), 'Jualan (3)');
  assert.equal(t('home_till_shift', 'Ali', '20/10/2026 08:00'), 'Syif dibuka: Ali sejak 20/10/2026 08:00');
  setLanguage('en');
  assert.equal(t('report_range', 'a', 'b'), 'a – b');
  assert.ok(src.length > 0);
});

// node --test (run by .github/workflows/relay.yml before every deploy)
import test from 'node:test';
import assert from 'node:assert/strict';
import worker, { clean, file, readStats, details, fence, md } from './worker.js';

const report = (over = {}) => ({
  v: 1,
  kind: 'crash',
  fp: '0123456789abcdef',
  install: 'a1b2c3d4e5f60718',
  app: '1.5.0',
  build: 90,
  signing: 'release',
  android: '5.0.2',
  sdk: 21,
  device: 'samsung SM-T295',
  ram_mb: 2048,
  free_mb: 1200,
  db_mb: 35,
  lang: 'ms',
  at: Date.UTC(2026, 9, 3, 10, 22),
  count: 1,
  thread: 'main',
  title: 'IllegalStateException in CartSession.commit',
  message: 'boom',
  trace: 'java.lang.IllegalStateException: boom\n\tat com.lekaspos.domain.sell.CartSession.commit(SourceFile:12)',
  log: '',
  ...over,
});

/** A pretend GitHub repository: issues and labels in memory, every call recorded. */
function fakeGitHub() {
  const issues = [];
  const calls = [];
  const gh = async (method, path, body) => {
    calls.push(`${method} ${path.split('?')[0]}`);
    if (method === 'GET') {
      const label = decodeURIComponent(path.split('labels=')[1]);
      return issues.filter((i) => i.labels.includes(label)).slice(0, 1).map((i) => ({ ...i, labels: i.labels.map((name) => ({ name })) }));
    }
    if (method === 'POST' && path === '/labels') return null;
    if (method === 'POST' && path === '/issues') {
      const i = { number: issues.length + 1, state: 'open', comments: [], ...body };
      issues.push(i);
      return i;
    }
    const n = Number(path.split('/')[2]);
    const i = issues.find((x) => x.number === n);
    if (method === 'PATCH') Object.assign(i, body);
    else i.comments.push(body.body);
    return i;
  };
  return { gh, issues, calls };
}

test('a report that is not from the app is refused', () => {
  assert.throws(() => clean({ v: 2 }), /format/);
  assert.throws(() => clean(report({ kind: 'spam' })), /kind/);
  assert.throws(() => clean(report({ fp: 'zz' })), /fingerprint/);
  assert.throws(() => clean(report({ app: '<b>1</b>' })), /version/);
  assert.throws(() => clean(report({ build: -1 })), /build/);
});

test('long fields are cut and control characters dropped', () => {
  const r = clean(report({ trace: 'x'.repeat(30000), title: 'a\u0007b' }));
  assert.equal(r.trace.length, 20001);
  assert.equal(r.title, 'ab');
});

test('the same bug from two tills is one issue with the counts', async () => {
  const g = fakeGitHub();
  await file(g.gh, clean(report()));
  await file(g.gh, clean(report({ install: 'ffffffffffffffff', count: 3 })));
  assert.equal(g.issues.length, 1);
  const s = readStats(g.issues[0].body);
  assert.equal(s.count, 4);
  assert.equal(s.tills.length, 2);
  assert.deepEqual(s.builds, { '1.5.0 (90)': 4 });
  assert.equal(g.issues[0].title, '[crash] IllegalStateException in CartSession.commit');
  assert.deepEqual(g.issues[0].labels, ['crash', 'fp:0123456789abcdef']);
  assert.equal(g.issues[0].comments.length, 0); // same build: counted only
});

test('a new build of a known bug adds a comment with its trace', async () => {
  const g = fakeGitHub();
  await file(g.gh, clean(report()));
  await file(g.gh, clean(report({ app: '1.5.1', build: 95 })));
  assert.equal(g.issues[0].comments.length, 1);
  assert.match(g.issues[0].comments[0], /First report from 1\.5\.1 \(build 95\)/);
});

test('a closed bug is reopened only by a build newer than any seen before', async () => {
  const g = fakeGitHub();
  await file(g.gh, clean(report({ build: 90 })));
  g.issues[0].state = 'closed';
  await file(g.gh, clean(report({ build: 88, app: '1.4.9' }))); // an old till not yet updated
  assert.equal(g.issues[0].state, 'closed');
  await file(g.gh, clean(report({ build: 95, app: '1.5.1' })));
  assert.equal(g.issues[0].state, 'open');
  assert.ok(g.issues[0].labels.includes('regression'));
  assert.match(g.issues[0].comments.at(-1), /Back after it was closed/);
});

test('a bug closed as not the app\'s fault stays closed', async () => {
  const g = fakeGitHub();
  await file(g.gh, clean(report({ build: 90 })));
  g.issues[0].state = 'closed';
  g.issues[0].labels.push('wontfix');
  await file(g.gh, clean(report({ build: 95, app: '1.5.1' })));
  assert.equal(g.issues[0].state, 'closed');
  assert.equal(readStats(g.issues[0].body).count, 2);
});

test('a report sent by hand is always its own issue, with the note', async () => {
  const g = fakeGitHub();
  const r = clean(report({ kind: 'manual', note: 'Printer stopped after @someone changed paper', contact: '012-345' }));
  await file(g.gh, r);
  await file(g.gh, r);
  assert.equal(g.issues.length, 2);
  assert.match(g.issues[0].title, /^\[manual\] Printer stopped/);
  assert.match(g.issues[0].body, /@​someone/); // no @mention
});

test('nothing in a report can break out of its code block or add markup', () => {
  assert.equal(fence('a\n~~~\nb'), '~~~text\na\n~ ~ ~\nb\n~~~');
  assert.equal(md('<img src=x> [a](b) `c`'), '\\<img src=x\\> \\[a\\](b) \\`c\\`');
  const d = details(clean(report({ device: '<script>' })));
  assert.ok(!d.includes('<script>'));
});

test('the relay answers the app', async () => {
  const env = { REPORT_KEY: 'k', REPORTS_REPO: 'o/r', REPORTS_TOKEN: 't' };
  const post = (body, headers = {}) =>
    worker.fetch(
      new Request('https://relay.example/v1/report', {
        method: 'POST',
        headers: { 'x-lekas-key': 'k', 'content-type': 'application/json', ...headers },
        body,
      }),
      env,
    );
  assert.equal((await post(JSON.stringify(report()), { 'x-lekas-key': 'wrong' })).status, 401);
  assert.equal((await post('not json')).status, 400);
  assert.equal((await post('x'.repeat(70000))).status, 413);
  const ok = await post(JSON.stringify(report()), { 'x-lekas-test': '1' });
  assert.equal(ok.status, 204);
  assert.equal(ok.headers.get('x-lekas-relay'), 'ok');
  assert.equal((await worker.fetch(new Request('https://relay.example/'), env)).status, 404);
});

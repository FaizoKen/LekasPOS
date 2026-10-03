// node --test (run by .github/workflows/relay.yml before every deploy)
import test from 'node:test';
import assert from 'node:assert/strict';
import worker, { add, clean, file, readStats, statsBlock, details, fence, md } from './worker.js';

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
      const label = /labels=([^&]*)/.exec(path);
      const perPage = Number(/per_page=(\d+)/.exec(path)[1]);
      return issues
        .filter((i) => !label || i.labels.includes(decodeURIComponent(label[1])))
        .sort((a, b) => b.number - a.number) // newest first, as GitHub lists them
        .slice(0, perPage)
        .map((i) => ({ ...i, labels: i.labels.map((name) => ({ name })) }));
    }
    if (method === 'POST' && path === '/labels') return null;
    if (method === 'POST' && path === '/issues') {
      const i = { number: issues.length + 1, state: 'open', created_at: new Date().toISOString(), comments: [], ...body };
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

test('two issues filed at the same moment become one at the next report', async () => {
  const g = fakeGitHub();
  // Two tills at once: GitHub did not list the first issue yet when the second report came.
  await file(g.gh, clean(report()));
  const first = g.issues[0];
  first.labels = first.labels.filter((l) => !l.startsWith('fp:'));
  await file(g.gh, clean(report({ install: 'ffffffffffffffff' })));
  first.labels.push('fp:0123456789abcdef');
  assert.equal(g.issues.length, 2);
  await file(g.gh, clean(report({ install: 'eeeeeeeeeeeeeeee' })));
  assert.equal(g.issues.length, 2);
  const s = readStats(g.issues[0].body);
  assert.equal(s.count, 3);
  assert.equal(s.tills.length, 3);
  assert.equal(g.issues[1].state, 'closed');
  assert.ok(g.issues[1].labels.includes('duplicate'));
  assert.ok(!g.issues[1].labels.includes('fp:0123456789abcdef')); // found no more
  assert.match(g.issues[1].comments[0], /The same bug as #1/);
  await file(g.gh, clean(report()));
  assert.equal(readStats(g.issues[0].body).count, 4);
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

test('a build above the ceiling is refused', () => {
  assert.throws(() => clean(report({ build: 10001 })), /build/); // the default: MAX_BUILD not set
  assert.equal(clean(report({ build: 10000 })).build, 10000);
  assert.equal(clean(report({ build: 50000 }), 50000).build, 50000);
});

test('a made-up build counted before builds were capped cannot keep a closed bug closed', async () => {
  const g = fakeGitHub();
  await file(g.gh, clean(report({ build: 90 })));
  // Before the ceiling, a forged build 9,999,999 became the bug's newest build for good.
  const forged = add(readStats(g.issues[0].body), { ...clean(report()), app: '9.9.9', build: 9999999 });
  g.issues[0].body = g.issues[0].body.replace(/<!-- lekas-stats[\s\S]*?<!-- \/lekas-stats -->/, statsBlock(forged));
  g.issues[0].state = 'closed';
  await file(g.gh, clean(report({ app: '1.5.1', build: 95 })));
  assert.equal(g.issues[0].state, 'open');
  assert.ok(g.issues[0].labels.includes('regression'));
  const s = readStats(g.issues[0].body);
  assert.equal(s.maxBuild, 95);
  assert.deepEqual(Object.keys(s.builds).sort(), ['1.5.0 (90)', '1.5.1 (95)']);
});

test('an issue keeps its newest 30 builds and comments only on a build not seen before', async () => {
  const g = fakeGitHub();
  const on = (build) => clean(report({ app: `1.6.${build}`, build }));
  await file(g.gh, on(100));
  for (let b = 101; b <= 130; b++) await file(g.gh, on(b));
  const issue = g.issues[0];
  assert.equal(issue.comments.length, 30);
  let s = readStats(issue.body);
  assert.equal(Object.keys(s.builds).length, 30);
  assert.ok(!('1.6.100 (100)' in s.builds)); // the oldest made room
  await file(g.gh, on(100)); // older than every build kept
  await file(g.gh, on(99));
  await file(g.gh, on(130)); // seen
  assert.equal(issue.comments.length, 30);
  await file(g.gh, on(131));
  assert.equal(issue.comments.length, 31);
  s = readStats(issue.body);
  assert.ok(!('1.6.101 (101)' in s.builds));
  assert.equal(s.builds['1.6.131 (131)'], 1);
  assert.equal(s.count, 35);
});

test('a till past the first 100 is counted once, however often it reports', () => {
  const till = (n) => n.toString(16).padStart(8, '0') + '00000000';
  let s = null;
  for (let n = 0; n < 100; n++) s = add(s, clean(report({ install: till(n) })));
  for (let k = 0; k < 5; k++) s = add(s, clean(report({ install: till(1000) })));
  s = add(s, clean(report({ install: till(1001) })));
  s = add(s, clean(report({ install: till(5) }))); // kept by its id: counted already
  assert.equal(s.tills.length, 100);
  assert.match(statsBlock(s), /\*\*Tills:\*\* 102 /);
  assert.equal(readStats(statsBlock(s)).tillBits, s.tillBits); // kept in the issue
});

test('a bug closed as not planned stays closed', async () => {
  const g = fakeGitHub();
  await file(g.gh, clean(report({ build: 90 })));
  Object.assign(g.issues[0], { state: 'closed', state_reason: 'not_planned' });
  await file(g.gh, clean(report({ build: 95, app: '1.5.1' })));
  assert.equal(g.issues[0].state, 'closed');
  assert.ok(!g.issues[0].labels.includes('regression'));
  assert.equal(readStats(g.issues[0].body).count, 2);
});

test('no mentions, issue references or bidi overrides from a report, in its text or a title', async () => {
  const sneaky = '&#64;dev &#35;12 &commat;x #3 o/r#4 GH-5 gh-6 @y a‮b⁦c⁩';
  const live = /@\w|&#|&[a-z]|#\d|GH-\d|[‪-‮⁦-⁩]/i;
  assert.doesNotMatch(md(sneaky), live);
  const g = fakeGitHub();
  await file(g.gh, clean(report({ kind: 'manual', note: sneaky, contact: sneaky })));
  await file(g.gh, clean(report({ title: sneaky, device: sneaky })));
  for (const i of g.issues) {
    assert.doesNotMatch(i.title, live);
    assert.doesNotMatch(i.body.replace(/~~~text[\s\S]*?~~~/g, ''), live); // outside its code blocks
  }
  assert.match(g.issues[0].title, /^\[manual\] &/);
  assert.equal(clean(report({ trace: 'a‮b⁧c' })).trace, 'abc');
});

test('at most so many new issues an hour; a known bug is still counted', async () => {
  const g = fakeGitHub();
  const two = { newPerHour: 2 };
  await file(g.gh, clean(report({ fp: '1111111111111111' })), two);
  await file(g.gh, clean(report({ kind: 'manual' })), two);
  await assert.rejects(file(g.gh, clean(report({ fp: '2222222222222222' })), two), (e) => e.status === 429);
  await assert.rejects(file(g.gh, clean(report({ kind: 'manual' })), two), (e) => e.status === 429);
  await file(g.gh, clean(report({ fp: '1111111111111111', install: 'ffffffffffffffff' })), two);
  assert.equal(g.issues.length, 2);
  assert.equal(readStats(g.issues[0].body).count, 2);
  g.issues[0].created_at = new Date(Date.now() - 3601 * 1000).toISOString(); // two in the last hour no more
  await file(g.gh, clean(report({ fp: '2222222222222222' })), two);
  assert.equal(g.issues.length, 3);
});

test('a body without its length is read no further than the limit', { timeout: 5000 }, async () => {
  let pulled = 0;
  const endless = new ReadableStream({
    pull(c) {
      pulled++;
      c.enqueue(new Uint8Array(16 * 1024));
    },
  });
  const request = new Request('https://relay.example/v1/report', {
    method: 'POST',
    headers: { 'x-lekas-key': 'k' },
    body: endless,
    duplex: 'half',
  });
  assert.equal(request.headers.get('content-length'), null);
  assert.equal((await worker.fetch(request, { REPORT_KEY: 'k' })).status, 413);
  assert.ok(pulled <= 8, `read ${pulled} chunks`);
});

test('the relay refuses a build above MAX_BUILD and asks to wait past MAX_NEW_ISSUES', async () => {
  const g = fakeGitHub();
  const real = globalThis.fetch;
  // GitHub's API, answered by the pretend repository.
  globalThis.fetch = async (url, init) => {
    const out = await g.gh(init.method, String(url).replace('https://api.github.com/repos/o/r', ''), init.body && JSON.parse(init.body));
    return out == null ? new Response(null, { status: 204 }) : Response.json(out);
  };
  try {
    const env = { REPORT_KEY: 'k', REPORTS_REPO: 'o/r', REPORTS_TOKEN: 't', MAX_NEW_ISSUES: '1' };
    const post = (over, e = env, headers = {}) =>
      worker.fetch(
        new Request('https://relay.example/v1/report', {
          method: 'POST',
          headers: { 'x-lekas-key': 'k', 'content-type': 'application/json', ...headers },
          body: JSON.stringify(report(over)),
        }),
        e,
      );
    assert.equal((await post({ build: 10001 })).status, 400); // the default ceiling: the app drops it
    assert.equal((await post({ build: 10001 }, { ...env, MAX_BUILD: '20000' }, { 'x-lekas-test': '1' })).status, 204);
    assert.equal((await post({})).status, 204);
    assert.equal((await post({ fp: 'fedcba9876543210' })).status, 429); // a second new bug this hour: later
    assert.equal((await post({ kind: 'manual' })).status, 429);
    assert.equal((await post({ install: 'ffffffffffffffff' })).status, 204); // the known bug: counted
    assert.equal(g.issues.length, 1);
    assert.equal(readStats(g.issues[0].body).count, 2);
  } finally {
    globalThis.fetch = real;
  }
});

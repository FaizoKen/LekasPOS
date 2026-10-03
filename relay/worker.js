// LekasPOS error-report relay (docs/DECISIONS.md D-057).
//
// The app sends an error report here only when the shop allowed it (or pressed "Send a report").
// The relay checks it and files it in the private reports repository on GitHub: one issue per bug
// (its fingerprint is a label), with counts, tills and builds kept in the issue; a closed issue
// is reopened when a build newer than any seen before it was closed hits it again. The relay
// holds the only GitHub token and stores nothing itself.
//
// The key is in every APK, so anyone can send a report: builds above MAX_BUILD are refused, at
// most MAX_NEW_ISSUES issues are opened in an hour, and nothing a report says can mention anyone,
// reference an issue or add markup. Both settings are optional [vars] in wrangler.toml.

export const KINDS = ['crash', 'anr', 'native', 'killed', 'error', 'check', 'manual'];
export const MAX_BODY = 64 * 1024;
/** The highest build taken when MAX_BUILD is not set (builds are CI run numbers: ~105 in 2026-10). */
export const DEFAULT_MAX_BUILD = 10000;
/** New issues (new bugs and reports sent by hand) in an hour when MAX_NEW_ISSUES is not set. */
export const DEFAULT_NEW_PER_HOUR = 30;
const STATS = /<!-- lekas-stats ([\s\S]*?) -->[\s\S]*?<!-- \/lekas-stats -->/;
const MAX_TILLS = 100; // till ids kept in an issue
const TILL_BITS = 1024; // tills past those: one bit each, picked by the id, so each is counted once
const MAX_BUILDS = 30; // the newest builds kept in an issue
const STAY_CLOSED = ['wontfix', 'not-a-bug'];
const HOUR = 3600 * 1000;
// Bidi overrides and isolates: they make text read other than it is.
const BIDI = /[‪-‮⁦-⁩]/g;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== '/v1/report') return text(404, 'not found');
    if (request.method !== 'POST') return text(405, 'POST only');
    if (request.headers.get('x-lekas-key') !== env.REPORT_KEY) return text(401, 'unknown sender');
    if (Number(request.headers.get('content-length') || 0) > MAX_BODY) return text(413, 'too large');
    if (env.LIMITER) {
      const { success } = await env.LIMITER.limit({ key: request.headers.get('cf-connecting-ip') || 'unknown' });
      if (!success) return text(429, 'slow down');
    }
    const raw = await readBody(request, MAX_BODY);
    if (!raw) return text(413, 'too large');
    const opts = settings(env);
    let r;
    try {
      r = clean(JSON.parse(new TextDecoder().decode(raw)), opts.maxBuild);
    } catch (e) {
      return text(400, String((e && e.message) || e));
    }
    // The app's connection test (instrumented tests on Android 5): checked, never filed.
    if (request.headers.get('x-lekas-test') === '1') return new Response(null, { status: 204, headers: { 'x-lekas-relay': 'ok' } });
    try {
      await file(github(env), r, opts);
      return new Response(null, { status: 204 });
    } catch (e) {
      if (e && e.status === 429) return text(429, 'too many new issues this hour'); // the app tries again later
      console.log('filing failed', (e && e.stack) || e);
      return text(502, 'could not file the report');
    }
  },
};

/** The relay's settings: [vars] MAX_BUILD and MAX_NEW_ISSUES in wrangler.toml, else the defaults. */
export function settings(env) {
  const num = (v) => (typeof v === 'string' ? Number(v) : v);
  return {
    maxBuild: int(num(env.MAX_BUILD), 1, 2100000000) || DEFAULT_MAX_BUILD,
    newPerHour: int(num(env.MAX_NEW_ISSUES), 1, 100) || DEFAULT_NEW_PER_HOUR,
  };
}

/** The request's body, or null when it is over [max] bytes: read no further than that, length given or not. */
async function readBody(request, max) {
  if (!request.body) return new Uint8Array(0);
  const reader = request.body.getReader();
  const chunks = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > max) {
      await reader.cancel().catch(() => {});
      return null;
    }
    chunks.push(value);
  }
  const all = new Uint8Array(size);
  let at = 0;
  for (const c of chunks) {
    all.set(c, at);
    at += c.byteLength;
  }
  return all;
}

/** The report, checked and cut to size; throws on anything that is not a LekasPOS report. */
export function clean(j, maxBuild = DEFAULT_MAX_BUILD) {
  if (!j || typeof j !== 'object' || j.v !== 1) throw new Error('unknown format');
  if (!KINDS.includes(j.kind)) throw new Error('unknown kind');
  if (!/^[0-9a-f]{16}$/.test(j.fp || '')) throw new Error('bad fingerprint');
  if (!/^[0-9a-f]{16}$/.test(j.install || '')) throw new Error('bad install id');
  if (!/^\d{1,3}\.\d{1,3}\.\d{1,3}[\w.-]{0,24}$/.test(j.app || '')) throw new Error('bad version');
  const build = int(j.build, 1, maxBuild);
  if (build === 0) throw new Error('bad build');
  return {
    kind: j.kind,
    fp: j.fp,
    install: j.install,
    app: j.app,
    build,
    signing: str(j.signing, 16),
    android: str(j.android, 24),
    sdk: int(j.sdk, 1, 100),
    device: str(j.device, 80),
    ramMb: int(j.ram_mb, 1, 1e7),
    freeMb: int(j.free_mb, 0, 1e8),
    dbMb: int(j.db_mb, 0, 1e8),
    lang: str(j.lang, 8),
    at: int(j.at, 1, 1e13) || Date.now(),
    count: int(j.count, 1, 1e6) || 1,
    thread: str(j.thread, 60),
    title: str(j.title, 120) || j.kind,
    message: str(j.message, 500),
    trace: str(j.trace, 20000),
    log: str(j.log, 10000),
    note: str(j.note, 1000),
    contact: str(j.contact, 200),
  };
}

/**
 * Files [r]: a new issue, or the bug's issue updated (and reopened when a newer build hits it).
 * Throws an error with status 429 when [newPerHour] issues were already opened in the last hour.
 */
export async function file(gh, r, { maxBuild = DEFAULT_MAX_BUILD, newPerHour = DEFAULT_NEW_PER_HOUR, now = Date.now() } = {}) {
  if (r.kind === 'manual') {
    await roomForNew(gh, newPerHour, now);
    const title = '[manual] ' + (r.note ? oneLine(r.note, 70) : 'A report from a shop');
    await gh('POST', '/issues', { title, body: details(r), labels: ['manual'] });
    return;
  }
  const label = 'fp:' + r.fp;
  const found = await gh('GET', '/issues?state=all&per_page=10&labels=' + encodeURIComponent(label));
  // The oldest is the bug's issue. GitHub lists a new issue a moment late, so two tills reporting the
  // same bug at once can file two: the extra ones are counted into it and closed.
  const [issue, ...extra] = (found || []).sort((a, b) => a.number - b.number);
  if (issue && extra.length > 0) issue.body = await absorb(gh, issue, extra, label, maxBuild);
  if (!issue) {
    await roomForNew(gh, newPerHour, now);
    const stats = add(null, r);
    await gh('POST', '/labels', { name: label, color: 'ededed' }, [422]); // already there: fine
    await gh('POST', '/issues', {
      title: `[${r.kind}] ${oneLine(r.title, 110)}`,
      body: statsBlock(stats) + '\n\n' + details(r),
      labels: [r.kind, label],
    });
    return;
  }
  const old = readStats(issue.body, maxBuild);
  const stats = add(old, r);
  const key = buildKey(r);
  // Kept now and not before: a build already seen, or older than every build kept, is not new.
  const newBuild = !old || (!(key in old.builds) && key in stats.builds);
  const labels = labelsOf(issue);
  // Closed as not planned, or as not the app's fault (a full phone, say): stays closed, only counted.
  const stayClosed = issue.state_reason === 'not_planned' || labels.some((l) => STAY_CLOSED.includes(l));
  const regression = issue.state === 'closed' && old && r.build > old.maxBuild && !stayClosed;
  const body = old ? issue.body.replace(STATS, statsBlock(stats)) : statsBlock(stats) + '\n\n' + (issue.body || '');
  const patch = { body };
  if (regression) {
    patch.state = 'open';
    patch.labels = [...new Set(labels.concat('regression'))];
  }
  await gh('PATCH', '/issues/' + issue.number, patch);
  if (regression) {
    await gh('POST', `/issues/${issue.number}/comments`, {
      body: `**Back after it was closed**, on ${r.app} (build ${r.build}) — newer than every build seen before. Reopened.\n\n` + details(r),
    });
  } else if (newBuild) {
    await gh('POST', `/issues/${issue.number}/comments`, { body: `First report from ${r.app} (build ${r.build}).\n\n` + details(r) });
  }
}

/**
 * Throws (status 429: the app tries again later) when [max] issues were opened in the last hour, so
 * made-up bugs cannot flood the repository. GitHub lists the newest issues first.
 */
async function roomForNew(gh, max, now) {
  const recent = (await gh('GET', `/issues?state=all&sort=created&direction=desc&per_page=${max}`)) || [];
  if (recent.length >= max && Date.parse(recent[max - 1].created_at) > now - HOUR) {
    const e = new Error(`${max} new issues in the last hour`);
    e.status = 429;
    throw e;
  }
}

/** Closes [extra] issues of the same bug as [issue], their counts moved into it; [issue]'s new body. */
async function absorb(gh, issue, extra, label, maxBuild) {
  let stats = readStats(issue.body, maxBuild);
  for (const d of extra) {
    const s = readStats(d.body, maxBuild);
    if (s) stats = stats ? merge(stats, s) : s;
    await gh('PATCH', '/issues/' + d.number, {
      state: 'closed',
      state_reason: 'not_planned',
      labels: labelsOf(d).filter((l) => l !== label).concat('duplicate'),
    });
    await gh('POST', `/issues/${d.number}/comments`, { body: `The same bug as #${issue.number} (filed at the same moment); counted there.` });
  }
  if (!stats) return issue.body;
  const body = STATS.test(issue.body) ? issue.body.replace(STATS, statsBlock(stats)) : statsBlock(stats) + '\n\n' + (issue.body || '');
  await gh('PATCH', '/issues/' + issue.number, { body });
  return body;
}

/** Two issues' stats as one. */
export function merge(a, b) {
  const s = { ...a, builds: { ...a.builds }, tills: [...a.tills] };
  s.count += b.count;
  if (b.moreTills) s.moreTills = (s.moreTills || 0) + b.moreTills;
  for (const t of b.tills) countTill(s, t);
  if (b.tillBits) s.tillBits = orBits(s.tillBits, b.tillBits);
  for (const [k, n] of Object.entries(b.builds)) countBuild(s.builds, k, n);
  s.maxBuild = Math.max(s.maxBuild, b.maxBuild);
  s.first = Math.min(s.first, b.first);
  s.last = Math.max(s.last, b.last);
  return s;
}

const labelsOf = (issue) => issue.labels.map((l) => (typeof l === 'string' ? l : l.name));

/** [old] stats (or none) with report [r] counted. */
export function add(old, r) {
  const s = old
    ? { ...old, builds: { ...old.builds }, tills: [...old.tills] }
    : { count: 0, tills: [], builds: {}, maxBuild: 0, first: r.at, last: r.at };
  s.count += r.count;
  countTill(s, r.install.slice(0, 8));
  countBuild(s.builds, buildKey(r), r.count);
  s.maxBuild = Math.max(s.maxBuild, r.build);
  s.first = Math.min(s.first, r.at);
  s.last = Math.max(s.last, r.at);
  return s;
}

/**
 * Counts till [t] once in [s]: its id is kept (the first MAX_TILLS tills); past those, the bit its
 * id picks is set in tillBits, and the set bits count the tills not kept (two can share a bit, so
 * that count can be a little low: ~5% at 100 more tills).
 */
function countTill(s, t) {
  if (s.tills.includes(t)) return;
  if (s.tills.length < MAX_TILLS) s.tills.push(t);
  else s.tillBits = setBit(s.tillBits, parseInt(t, 16) % TILL_BITS);
}

/** Counts [n] reports of build [key] in [builds], which keeps the MAX_BUILDS newest (highest) builds. */
function countBuild(builds, key, n) {
  if (key in builds) {
    builds[key] += n;
    return;
  }
  const kept = Object.keys(builds).sort((x, y) => buildOf(x) - buildOf(y));
  while (kept.length >= MAX_BUILDS) {
    if (buildOf(key) <= buildOf(kept[0])) return; // older than every build kept: not kept
    delete builds[kept.shift()];
  }
  builds[key] = n;
}

/** The stats in an issue's body (null when there are none), builds above [maxBuild] forgotten. */
export function readStats(body, maxBuild = DEFAULT_MAX_BUILD) {
  const m = STATS.exec(body || '');
  if (!m) return null;
  let s;
  try {
    s = JSON.parse(m[1]);
  } catch (e) {
    return null;
  }
  if (!s || typeof s.count !== 'number' || !s.builds || typeof s.builds !== 'object' || !Array.isArray(s.tills)) return null;
  // A made-up build sent before builds were capped must not stay the bug's newest build: no real
  // build would be newer, so the closed bug would never be reopened again.
  for (const k of Object.keys(s.builds)) if (buildOf(k) > maxBuild) delete s.builds[k];
  if (!Number.isInteger(s.maxBuild) || s.maxBuild > maxBuild) s.maxBuild = Math.max(0, ...Object.keys(s.builds).map(buildOf));
  return s;
}

export function statsBlock(s) {
  const builds = Object.entries(s.builds)
    .sort((a, b) => b[1] - a[1])
    .map(([k, n]) => `${k} ×${n}`)
    .join(', ');
  // moreTills: only in issues filed before tillBits (it counted reports, not tills).
  const tills = s.tills.length + (s.moreTills || 0) + bitCount(s.tillBits);
  return [
    `<!-- lekas-stats ${JSON.stringify(s)} -->`,
    `**Reports:** ${s.count} · **Tills:** ${tills} · **First:** ${day(s.first)} · **Last:** ${day(s.last)}`,
    `**Builds:** ${builds}`,
    '<!-- /lekas-stats -->',
  ].join('\n');
}

/** One report, as markdown: the facts on two lines, then the trace and the log before it. */
export function details(r) {
  const head = [
    `**${r.kind}** on LekasPOS ${md(r.app)} (build ${r.build}${r.signing && r.signing !== 'release' ? ', ' + md(r.signing) : ''})` +
      ` · Android ${md(r.android)} (API ${r.sdk}) · ${md(r.device)}`,
    [
      r.ramMb && `RAM ${r.ramMb} MB`,
      r.freeMb && `free storage ${r.freeMb} MB`,
      r.dbMb && `database ${r.dbMb} MB`,
      r.lang && `language ${md(r.lang)}`,
      `till \`${r.install.slice(0, 8)}\``,
      r.count > 1 && `${r.count}× on this till`,
      new Date(r.at).toISOString().replace('T', ' ').slice(0, 16) + ' UTC',
      r.thread && `thread ${md(r.thread)}`,
    ]
      .filter(Boolean)
      .join(' · '),
  ];
  const parts = [head.join('\n')];
  if (r.note) parts.push('**What the shop says:**\n' + quote(r.note));
  if (r.contact) parts.push('**Contact:** ' + md(r.contact));
  if (r.trace) parts.push(fence(r.trace));
  else if (r.message) parts.push(fence(r.message));
  if (r.log) parts.push(`<details><summary>The error log before it</summary>\n\n${fence(r.log)}\n</details>`);
  return parts.join('\n\n');
}

function github(env) {
  const base = 'https://api.github.com/repos/' + env.REPORTS_REPO;
  return async (method, path, body, okStatuses = []) => {
    const res = await fetch(base + path, {
      method,
      headers: {
        authorization: 'Bearer ' + env.REPORTS_TOKEN,
        accept: 'application/vnd.github+json',
        'x-github-api-version': '2022-11-28',
        'user-agent': 'lekaspos-relay',
        ...(body ? { 'content-type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (okStatuses.includes(res.status)) return null;
    if (!res.ok) throw new Error(`GitHub ${method} ${path}: ${res.status} ${(await res.text()).slice(0, 300)}`);
    return res.status === 204 ? null : res.json();
  };
}

const buildKey = (r) => `${r.app} (${r.build})`;
/** The build number in a build key ("1.5.0 (90)" is 90). */
const buildOf = (key) => Number((/\((\d+)\)$/.exec(key) || [])[1]) || 0;
const day = (ms) => new Date(ms).toISOString().slice(0, 10);

/** tillBits: TILL_BITS bits as hex (absent while every till is kept by its id). */
function bitsOf(h) {
  return typeof h === 'string' && h.length === TILL_BITS / 4 && /^[0-9a-f]+$/.test(h) ? h : '0'.repeat(TILL_BITS / 4);
}

function setBit(h, i) {
  const b = bitsOf(h);
  const c = i >> 2;
  return b.slice(0, c) + (parseInt(b[c], 16) | (1 << (i & 3))).toString(16) + b.slice(c + 1);
}

function orBits(a, b) {
  const x = bitsOf(a);
  const y = bitsOf(b);
  let out = '';
  for (let i = 0; i < x.length; i++) out += (parseInt(x[i], 16) | parseInt(y[i], 16)).toString(16);
  return out;
}

function bitCount(h) {
  if (!h) return 0;
  let n = 0;
  for (const c of bitsOf(h)) for (let v = parseInt(c, 16); v; v &= v - 1) n++;
  return n;
}

function int(v, min, max) {
  return Number.isInteger(v) && v >= min && v <= max ? v : 0;
}

function str(v, max) {
  if (typeof v !== 'string') return '';
  // No control characters but newlines and tabs, no bidi overrides or isolates.
  const s = v.replace(/[\u0000-\u0008\u000b-\u001f\u007f‪-‮⁦-⁩]/g, '');
  return s.length > max ? s.slice(0, max) + '…' : s;
}

/** An issue title (plain text, not markdown): one line, made [inert] as [md] makes text. */
function oneLine(s, max) {
  const t = s.replace(BIDI, '').replace(/\s+/g, ' ').trim();
  return inert(t.length > max ? t.slice(0, max) + '…' : t);
}

/** Text shown outside a code block: no markup, links, @mentions or issue references from a report. */
export function md(s) {
  return inert(
    String(s)
      .replace(/[\\`*_[\]<>|#!~&]/g, (c) => '\\' + c)
      .replace(/\r?\n/g, ' '),
  );
}

/**
 * [s] with nothing GitHub would make a mention or an issue reference: a zero-width space after every
 * @, after a # or GH- before a number, and after an & that could start a character reference
 * (&#64; is @, &#35; is #); no bidi overrides or isolates.
 */
function inert(s) {
  return s
    .replace(BIDI, '')
    .replace(/@/g, '@​')
    .replace(/&(?=[#A-Za-z])/g, '&​')
    .replace(/#(?=\d)/g, '#​')
    .replace(/\b(GH)-(?=\d)/gi, '$1​-');
}

function quote(s) {
  return s
    .split(/\r?\n/)
    .map((l) => '> ' + md(l))
    .join('\n');
}

/** A code block a report cannot break out of. */
export function fence(s) {
  return '~~~text\n' + s.replace(/~~~/g, '~ ~ ~') + '\n~~~';
}

function text(status, message) {
  return new Response(message + '\n', { status, headers: { 'content-type': 'text/plain; charset=utf-8' } });
}

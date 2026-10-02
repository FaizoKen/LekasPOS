// LekasPOS error-report relay (docs/DECISIONS.md D-057).
//
// The app sends an error report here only when the shop allowed it (or pressed "Send a report").
// The relay checks it and files it in the private reports repository on GitHub: one issue per bug
// (its fingerprint is a label), with counts, tills and builds kept in the issue; a closed issue
// is reopened when a build newer than any seen before it was closed hits it again. The relay
// holds the only GitHub token and stores nothing itself.

export const KINDS = ['crash', 'anr', 'native', 'killed', 'error', 'check', 'manual'];
export const MAX_BODY = 64 * 1024;
const STATS = /<!-- lekas-stats ([\s\S]*?) -->[\s\S]*?<!-- \/lekas-stats -->/;
const MAX_TILLS = 100;
const MAX_BUILDS = 30;
const STAY_CLOSED = ['wontfix', 'not-a-bug'];

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
    const raw = await request.arrayBuffer();
    if (raw.byteLength > MAX_BODY) return text(413, 'too large');
    let r;
    try {
      r = clean(JSON.parse(new TextDecoder().decode(raw)));
    } catch (e) {
      return text(400, String((e && e.message) || e));
    }
    // The app's connection test (instrumented tests on Android 5): checked, never filed.
    if (request.headers.get('x-lekas-test') === '1') return new Response(null, { status: 204, headers: { 'x-lekas-relay': 'ok' } });
    try {
      await file(github(env), r);
      return new Response(null, { status: 204 });
    } catch (e) {
      console.log('filing failed', (e && e.stack) || e);
      return text(502, 'could not file the report');
    }
  },
};

/** The report, checked and cut to size; throws on anything that is not a LekasPOS report. */
export function clean(j) {
  if (!j || typeof j !== 'object' || j.v !== 1) throw new Error('unknown format');
  if (!KINDS.includes(j.kind)) throw new Error('unknown kind');
  if (!/^[0-9a-f]{16}$/.test(j.fp || '')) throw new Error('bad fingerprint');
  if (!/^[0-9a-f]{16}$/.test(j.install || '')) throw new Error('bad install id');
  if (!/^\d{1,3}\.\d{1,3}\.\d{1,3}[\w.-]{0,24}$/.test(j.app || '')) throw new Error('bad version');
  const build = int(j.build, 1, 1e7);
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

/** Files [r]: a new issue, or the bug's issue updated (and reopened when a newer build hits it). */
export async function file(gh, r) {
  if (r.kind === 'manual') {
    const title = '[manual] ' + (r.note ? oneLine(r.note, 70) : 'A report from a shop');
    await gh('POST', '/issues', { title, body: details(r), labels: ['manual'] });
    return;
  }
  const label = 'fp:' + r.fp;
  const found = await gh('GET', '/issues?state=all&per_page=10&labels=' + encodeURIComponent(label));
  // The oldest is the bug's issue. GitHub lists a new issue a moment late, so two tills reporting the
  // same bug at once can file two: the extra ones are counted into it and closed.
  const [issue, ...extra] = (found || []).sort((a, b) => a.number - b.number);
  if (issue && extra.length > 0) issue.body = await absorb(gh, issue, extra, label);
  if (!issue) {
    const stats = add(null, r);
    await gh('POST', '/labels', { name: label, color: 'ededed' }, [422]); // already there: fine
    await gh('POST', '/issues', {
      title: `[${r.kind}] ${oneLine(r.title, 110)}`,
      body: statsBlock(stats) + '\n\n' + details(r),
      labels: [r.kind, label],
    });
    return;
  }
  const old = readStats(issue.body);
  const stats = add(old, r);
  const newBuild = !old || !(buildKey(r) in old.builds);
  const labels = labelsOf(issue);
  // Closed as not the app's fault (a full phone, say): stays closed, only counted.
  const regression = issue.state === 'closed' && old && r.build > old.maxBuild && !labels.some((l) => STAY_CLOSED.includes(l));
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

/** Closes [extra] issues of the same bug as [issue], their counts moved into it; [issue]'s new body. */
async function absorb(gh, issue, extra, label) {
  let stats = readStats(issue.body);
  for (const d of extra) {
    const s = readStats(d.body);
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
  s.moreTills = (s.moreTills || 0) + (b.moreTills || 0);
  for (const t of b.tills) {
    if (s.tills.includes(t)) continue;
    if (s.tills.length < MAX_TILLS) s.tills.push(t);
    else s.moreTills += 1;
  }
  for (const [k, n] of Object.entries(b.builds)) {
    if (k in s.builds || Object.keys(s.builds).length < MAX_BUILDS) s.builds[k] = (s.builds[k] || 0) + n;
  }
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
    : { count: 0, tills: [], moreTills: 0, builds: {}, maxBuild: 0, first: r.at, last: r.at };
  s.count += r.count;
  const till = r.install.slice(0, 8);
  if (!s.tills.includes(till)) {
    if (s.tills.length < MAX_TILLS) s.tills.push(till);
    else s.moreTills = (s.moreTills || 0) + 1;
  }
  const key = buildKey(r);
  if (key in s.builds || Object.keys(s.builds).length < MAX_BUILDS) s.builds[key] = (s.builds[key] || 0) + r.count;
  s.maxBuild = Math.max(s.maxBuild, r.build);
  s.first = Math.min(s.first, r.at);
  s.last = Math.max(s.last, r.at);
  return s;
}

export function readStats(body) {
  const m = STATS.exec(body || '');
  if (!m) return null;
  try {
    const s = JSON.parse(m[1]);
    return s && typeof s.count === 'number' && s.builds && Array.isArray(s.tills) ? s : null;
  } catch (e) {
    return null;
  }
}

export function statsBlock(s) {
  const builds = Object.entries(s.builds)
    .sort((a, b) => b[1] - a[1])
    .map(([k, n]) => `${k} ×${n}`)
    .join(', ');
  const tills = s.tills.length + (s.moreTills || 0);
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
const day = (ms) => new Date(ms).toISOString().slice(0, 10);

function int(v, min, max) {
  return Number.isInteger(v) && v >= min && v <= max ? v : 0;
}

function str(v, max) {
  if (typeof v !== 'string') return '';
  // No control characters but newlines and tabs.
  const s = v.replace(/[\u0000-\u0008\u000b-\u001f\u007f]/g, '');
  return s.length > max ? s.slice(0, max) + '…' : s;
}

/** An issue title (plain text, not markdown). */
function oneLine(s, max) {
  const t = s.replace(/\s+/g, ' ').trim();
  return t.length > max ? t.slice(0, max) + '…' : t;
}

/** Text shown outside a code block: no markup, links or @mentions from a report. */
export function md(s) {
  return String(s)
    .replace(/[\\`*_[\]<>|#!~]/g, (c) => '\\' + c)
    .replace(/@/g, '@​')
    .replace(/\r?\n/g, ' ');
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

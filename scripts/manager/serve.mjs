// Serves site/ on http://localhost:8000 for trying the manager web page (http://localhost:8000/manager/).
// localhost is a secure context, so the worker, OPFS storage and Google sign-in behave as on GitHub Pages.
// Usage: node scripts/manager/serve.mjs [port] [--fake-drive <folder>]
//
// --fake-drive: a stand-in for Google Drive's app folder at /fake-drive, holding the files of <folder> (as
// `demo-shop.mjs --fixture <folder>` or ManagerFixtureTest leave them: each file, its properties in `name.props`).
// Open http://localhost:8000/manager/?fake-drive and "Open my shop with Google Drive" reads them without a Google
// account; changes made on the page are written there as new files, as Google Drive would keep them (D-083).
import { createServer } from 'node:http';
import { readFile, stat, readdir, writeFile, unlink } from 'node:fs/promises';
import { join, extname, normalize, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../site/', import.meta.url));
const args = process.argv.slice(2);
const fakeAt = args.indexOf('--fake-drive');
const fakeDir = fakeAt >= 0 ? args[fakeAt + 1] : null;
if (fakeAt >= 0 && !fakeDir) throw new Error('--fake-drive needs a folder');
const port = Number(args.find((a, i) => /^[0-9]+$/.test(a) && i !== fakeAt + 1) ?? 8000);
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.wasm': 'application/wasm',
  '.json': 'application/json', '.txt': 'text/plain; charset=utf-8', '.md': 'text/plain; charset=utf-8',
};

const json = (res, status, body) => {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
};

function body(req) {
  return new Promise((resolve, reject) => {
    const parts = [];
    req.on('data', (c) => parts.push(c));
    req.on('end', () => resolve(Buffer.concat(parts)));
    req.on('error', reject);
  });
}

// ------------------------------------------------------------------------------------------- the stand-in for Drive

/** A file's name is its id here (a folder cannot hold two files of one name; Drive can, the page copes with both). */
const safe = (name) => basename(name) === name && !name.startsWith('.') && !name.endsWith('.props') && name.length > 0;

async function props(name) {
  try {
    const out = {};
    for (const line of (await readFile(join(fakeDir, `${name}.props`), 'utf8')).split('\n')) {
      const at = line.indexOf('=');
      if (at > 0) out[line.slice(0, at)] = line.slice(at + 1);
    }
    return out;
  } catch {
    return {};
  }
}

async function describe(name) {
  const st = await stat(join(fakeDir, name));
  return { id: name, name, size: String(st.size), appProperties: await props(name), createdTime: new Date(st.mtimeMs).toISOString() };
}

/** The few `q` forms the page sends (drive.js): name contains / name = / createdTime > / trashed = false. */
function matcher(q) {
  const tests = [];
  for (const m of q.matchAll(/name contains '((?:[^'\\]|\\.)*)'/g)) tests.push((f) => f.name.includes(m[1].replace(/\\(.)/g, '$1')));
  for (const m of q.matchAll(/name = '((?:[^'\\]|\\.)*)'/g)) tests.push((f) => f.name === m[1].replace(/\\(.)/g, '$1'));
  for (const m of q.matchAll(/createdTime > '([^']+)'/g)) tests.push((f) => Date.parse(f.createdTime) > Date.parse(m[1]));
  return (f) => tests.every((t) => t(f));
}

/** A multipart/related upload: its JSON metadata and its bytes (drive.js multipart). */
function multipart(buf, type) {
  const boundary = /boundary=([^;]+)/.exec(type)?.[1];
  if (!boundary) throw new Error('no boundary');
  const sep = Buffer.from(`--${boundary}`);
  const parts = [];
  let at = buf.indexOf(sep);
  while (at >= 0) {
    const next = buf.indexOf(sep, at + sep.length);
    if (next < 0) break;
    const part = buf.subarray(at + sep.length + 2, next - 2); // after CRLF, before CRLF
    const head = part.indexOf('\r\n\r\n');
    parts.push(part.subarray(head + 4));
    at = next;
  }
  return { meta: JSON.parse(parts[0].toString('utf8')), bytes: parts[1] };
}

async function fakeDrive(req, res, url) {
  const path = url.pathname.replace(/^\/fake-drive/, '');
  if (req.headers.authorization !== 'Bearer fake') return json(res, 401, { error: { status: 'UNAUTHENTICATED' } });
  if (req.method === 'GET' && path === '/drive/v3/about') return json(res, 200, { user: { emailAddress: 'shop@example.com' } });
  if (req.method === 'GET' && path === '/drive/v3/files') {
    const keep = matcher(url.searchParams.get('q') ?? '');
    const files = [];
    for (const name of (await readdir(fakeDir)).filter(safe).sort()) {
      const f = await describe(name);
      if (keep(f)) files.push(f);
    }
    return json(res, 200, { files });
  }
  const file = /^\/(?:upload\/)?drive\/v3\/files\/([^/]+)$/.exec(path);
  if (file) {
    const name = decodeURIComponent(file[1]);
    if (!safe(name)) return json(res, 404, { error: { errors: [{ reason: 'notFound' }] } });
    const there = await stat(join(fakeDir, name)).catch(() => null);
    if (!there) return json(res, 404, { error: { errors: [{ reason: 'notFound' }] } });
    if (req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'application/octet-stream' });
      return res.end(await readFile(join(fakeDir, name)));
    }
    if (req.method === 'PATCH') {
      await writeFile(join(fakeDir, name), await body(req));
      return json(res, 200, { id: name });
    }
    if (req.method === 'DELETE') {
      await unlink(join(fakeDir, name));
      await unlink(join(fakeDir, `${name}.props`)).catch(() => {});
      res.writeHead(204);
      return res.end();
    }
  }
  if (req.method === 'POST' && path === '/upload/drive/v3/files') {
    const { meta, bytes } = multipart(await body(req), req.headers['content-type'] ?? '');
    if (!safe(meta.name)) return json(res, 400, { error: { errors: [{ reason: 'invalid' }] } });
    await writeFile(join(fakeDir, meta.name), bytes);
    const p = meta.appProperties ?? {};
    if (Object.keys(p).length) await writeFile(join(fakeDir, `${meta.name}.props`), Object.entries(p).map(([k, v]) => `${k}=${v}`).join('\n'));
    console.log(`fake Drive: ${meta.name} (${bytes.length} bytes)`);
    return json(res, 200, { id: meta.name });
  }
  return json(res, 404, { error: { errors: [{ reason: 'notFound' }] } });
}

createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://x');
    if (fakeDir && url.pathname.startsWith('/fake-drive/')) return await fakeDrive(req, res, url);
    let path = normalize(decodeURIComponent(url.pathname)).replace(/^([/\\])+/, '');
    if (path.includes('..')) throw new Error('bad path');
    let file = join(root, path);
    if ((await stat(file).catch(() => null))?.isDirectory()) file = join(file, 'index.html');
    const data = await readFile(file);
    res.writeHead(200, { 'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(data);
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('not found');
  }
}).listen(port, () => console.log(`http://localhost:${port}/manager/${fakeDir ? `?fake-drive (Google Drive: ${fakeDir})` : ''}`));

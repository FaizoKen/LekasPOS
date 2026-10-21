// The shop's sync folder in Google Drive (the hidden app folder, `drive.appdata` — the same folder and permission as
// the tills, sync/drive/DriveProvider.kt). Lists and downloads the tills' files; since D-083 it also writes the web
// page's own files the way DriveProvider.put does — new files with their checksum, its device card replaced — and
// never touches a file of a till. Runs in the worker with an access token from the page.

import { SyncError } from './store.js';

const GOOGLE = 'https://www.googleapis.com';
const BUSY = new Set(['userRateLimitExceeded', 'rateLimitExceeded', 'backendError', 'internalError']);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** A Drive query string literal (DriveProvider.quote). */
const quote = (s) => s.replace(/\\/g, '\\\\').replace(/'/g, "\\'");

export class DriveFolder {
  /** [base]: Google's address; a local test server's on localhost (scripts/manager/serve.mjs --fake-drive). */
  constructor(token, base = GOOGLE) {
    this.token = token;
    this.api = `${base}/drive/v3`;
    this.upload = `${base}/upload/drive/v3`;
  }

  /**
   * One request: [as] 'json', 'bytes' or 'none'. A read is tried again after a dropped connection; a write is not
   * (it may have arrived: [create] looks first). Busy answers are waited for either way.
   */
  async request(url, as = 'json', init = {}) {
    const write = (init.method ?? 'GET') !== 'GET';
    for (let attempt = 0; ; attempt++) {
      let res;
      try {
        res = await fetch(url, { ...init, headers: { ...init.headers, Authorization: `Bearer ${this.token}` } });
      } catch (e) {
        if (!write && attempt < 2) { await sleep(1000 * (attempt + 1)); continue; }
        throw new SyncError('OFFLINE', e.message);
      }
      if (res.ok) {
        if (as === 'bytes') return new Uint8Array(await res.arrayBuffer());
        if (as === 'none') return null;
        return res.json();
      }
      let reason = '';
      try {
        const body = await res.json();
        reason = body?.error?.errors?.[0]?.reason ?? body?.error?.status ?? '';
      } catch {
        // not JSON
      }
      if (res.status === 401) throw new SyncError('SIGN_IN', reason);
      if (res.status === 403 && (reason === 'insufficientPermissions' || reason === 'PERMISSION_DENIED')) throw new SyncError('NO_ACCESS', reason);
      if (res.status === 403 && reason === 'storageQuotaExceeded') throw new SyncError('DRIVE_FULL', reason);
      if ((res.status === 429 || res.status >= 500 || (res.status === 403 && BUSY.has(reason))) && attempt < 4) {
        await sleep(1000 * 2 ** attempt + Math.random() * 500);
        continue;
      }
      if (res.status === 403 || res.status === 429) throw new SyncError('BUSY', reason);
      throw Object.assign(new SyncError('DRIVE', `${res.status} ${reason}`), { status: res.status });
    }
  }

  /** Files of a query [q], page by page. */
  async query(q) {
    const out = [];
    let page = null;
    do {
      const url = `${this.api}/files?spaces=appDataFolder&pageSize=1000&prettyPrint=false` +
        `&fields=${encodeURIComponent('nextPageToken,files(id,name,size,appProperties,createdTime)')}` +
        `&q=${encodeURIComponent(q)}` + (page ? `&pageToken=${encodeURIComponent(page)}` : '');
      const r = await this.request(url);
      for (const f of r.files ?? []) {
        out.push({ name: f.name ?? '', id: f.id, size: Number(f.size ?? 0), props: f.appProperties ?? {}, created: Date.parse(f.createdTime ?? '') || 0 });
      }
      page = r.nextPageToken ?? null;
    } while (page);
    return out;
  }

  /** Files whose name starts with [word] (Drive's `name contains` matches word prefixes), created after [since]. */
  async list(word, since) {
    let q = `name contains '${quote(word)}' and trashed = false`;
    if (since) q += ` and createdTime > '${new Date(since).toISOString()}'`;
    return (await this.query(q)).filter((f) => f.name.startsWith(word));
  }

  /** The files named exactly [name], oldest first. */
  async find(name) {
    return (await this.query(`name = '${quote(name)}' and trashed = false`)).filter((f) => f.name === name).sort((a, b) => a.created - b.created);
  }

  get(f) {
    return this.request(`${this.api}/files/${encodeURIComponent(f.id)}?alt=media`, 'bytes');
  }

  /** The signed-in account's e-mail address (shown so the owner sees which shop this is). */
  async account() {
    const r = await this.request(`${this.api}/about?fields=${encodeURIComponent('user(emailAddress)')}`);
    return r?.user?.emailAddress ?? null;
  }

  async multipart(name, bytes, props) {
    const meta = { name, parents: ['appDataFolder'] };
    if (props && Object.keys(props).length) meta.appProperties = props;
    const boundary = `lekas${Date.now()}${Math.random().toString(16).slice(2)}`;
    const body = new Blob([
      `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n`, JSON.stringify(meta),
      `\r\n--${boundary}\r\nContent-Type: application/octet-stream\r\n\r\n`, bytes, `\r\n--${boundary}--\r\n`,
    ]);
    const r = await this.request(`${this.upload}/files?uploadType=multipart&fields=id`, 'json',
      { method: 'POST', headers: { 'Content-Type': `multipart/related; boundary=${boundary}` }, body });
    return r.id;
  }

  /**
   * Creates file [name] (never a second copy: DriveProvider.put with replace = false). A create whose answer was
   * lost may have arrived, so the name is looked up before it is sent once more. Returns its id.
   */
  async create(name, bytes, props = {}) {
    try {
      return await this.multipart(name, bytes, props);
    } catch (e) {
      if (!(e instanceof SyncError) || !['OFFLINE', 'BUSY', 'DRIVE'].includes(e.code) || (e.status != null && e.status < 500)) throw e;
      const there = (await this.find(name))[0];
      if (there) return there.id;
      return this.multipart(name, bytes, props);
    }
  }

  /** New content for this page's own file [id] (its device card). */
  async update(id, bytes) {
    await this.request(`${this.upload}/files/${encodeURIComponent(id)}?uploadType=media`, 'none',
      { method: 'PATCH', headers: { 'Content-Type': 'application/octet-stream' }, body: new Blob([bytes]) });
  }

  /** Removes this page's own file [id] (a second copy of its card); one already gone is fine. */
  async remove(id) {
    try {
      await this.request(`${this.api}/files/${encodeURIComponent(id)}`, 'none', { method: 'DELETE' });
    } catch (e) {
      if (e.status !== 404) throw e;
    }
  }
}

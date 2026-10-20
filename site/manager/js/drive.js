// Reads the shop's sync folder in Google Drive (the hidden app folder, `drive.appdata` — the same folder
// and permission as the tills, sync/drive/DriveProvider.kt). Only lists and downloads: the web page never
// creates, changes or deletes a file there. Runs in the worker with an access token from the page.

import { SyncError } from './store.js';

const API = 'https://www.googleapis.com/drive/v3';
const BUSY = new Set(['userRateLimitExceeded', 'rateLimitExceeded', 'backendError', 'internalError']);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export class DriveFolder {
  constructor(token) {
    this.token = token;
  }

  async request(url, bytes) {
    for (let attempt = 0; ; attempt++) {
      let res;
      try {
        res = await fetch(url, { headers: { Authorization: `Bearer ${this.token}` } });
      } catch (e) {
        if (attempt < 2) { await sleep(1000 * (attempt + 1)); continue; }
        throw new SyncError('OFFLINE', e.message);
      }
      if (res.ok) return bytes ? new Uint8Array(await res.arrayBuffer()) : res.json();
      let reason = '';
      try {
        const body = await res.json();
        reason = body?.error?.errors?.[0]?.reason ?? body?.error?.status ?? '';
      } catch {
        // not JSON
      }
      if (res.status === 401) throw new SyncError('SIGN_IN', reason);
      if (res.status === 403 && (reason === 'insufficientPermissions' || reason === 'PERMISSION_DENIED')) throw new SyncError('NO_ACCESS', reason);
      if ((res.status === 429 || res.status >= 500 || (res.status === 403 && BUSY.has(reason))) && attempt < 4) {
        await sleep(1000 * 2 ** attempt + Math.random() * 500);
        continue;
      }
      if (res.status === 403 || res.status === 429) throw new SyncError('BUSY', reason);
      throw new SyncError('DRIVE', `${res.status} ${reason}`);
    }
  }

  /** Files whose name starts with [word] (Drive's `name contains` matches word prefixes), created after [since]. */
  async list(word, since) {
    let q = `name contains '${word}' and trashed = false`;
    if (since) q += ` and createdTime > '${new Date(since).toISOString()}'`;
    const out = [];
    let page = null;
    do {
      const url = `${API}/files?spaces=appDataFolder&pageSize=1000&prettyPrint=false` +
        `&fields=${encodeURIComponent('nextPageToken,files(id,name,size,appProperties,createdTime)')}` +
        `&q=${encodeURIComponent(q)}` + (page ? `&pageToken=${encodeURIComponent(page)}` : '');
      const r = await this.request(url, false);
      for (const f of r.files ?? []) {
        if (!f.name?.startsWith(word)) continue;
        out.push({ name: f.name, id: f.id, size: Number(f.size ?? 0), props: f.appProperties ?? {}, created: Date.parse(f.createdTime ?? '') || 0 });
      }
      page = r.nextPageToken ?? null;
    } while (page);
    return out;
  }

  get(f) {
    return this.request(`${API}/files/${encodeURIComponent(f.id)}?alt=media`, true);
  }

  /** The signed-in account's e-mail address (shown so the owner sees which shop this is). */
  async account() {
    const r = await this.request(`${API}/about?fields=${encodeURIComponent('user(emailAddress)')}`, false);
    return r?.user?.emailAddress ?? null;
  }
}

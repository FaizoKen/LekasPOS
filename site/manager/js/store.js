// The web page's copy of one shop: an SQLite database with the app's own tables (schema.js), filled from
// the tills' sync files (a sync round, below) or taken whole from a backup file (backup.js). Since D-083 the
// page can also change the shop like a till: it takes a till number of its own on its first change and writes
// each save as one sync file of its own (commit, below) — never a till's file. Runs in the worker (worker.js)
// and in the Node tests, so nothing here touches the browser's windows or storage.

import { STATEMENTS, APP_SCHEMA } from './schema.js';
import { Importer, Touched } from './importer.js';
import { applyDerived } from './derived.js';
import { parse } from './json64.js';
import {
  readSegment, readPack, parseSegmentName, parseFileName, planFiles, reach, parseStoreName, parseCard, sha256, CorruptFile, writeSegment, jsonText,
} from './segment.js';
import { Clock, DEVICE_BITS } from './rules.js';
import { Writer } from './writer.js';

/** Bump to make every browser read its shop again from the start (a change in what is stored). */
export const WEB_FORMAT = 1;

/** Indexes the web page adds for its own reading (the tills keep `sale.day` totals incrementally). */
const WEB_INDEXES = ['CREATE INDEX IF NOT EXISTS web_sale_day ON sale(day)'];

/** Oldest backup schema the reports can read (v8, 1.5.2: the category totals, D-058). */
export const MIN_BACKUP_SCHEMA = 8;

/** Events and files per database transaction while reading the shop's files. */
const BATCH_EVENTS = 20000;
const BATCH_FILES = 40;
/** Files downloaded at the same time. */
const PARALLEL = 4;
/** A short listing starts this long before the newest file seen (Drive's clock, as SyncEngine). */
const LIST_OVERLAP_MS = 15 * 60_000;
/** The whole folder is listed at least this often. */
const FULL_LIST_MS = 24 * 3_600_000;

export class SyncError extends Error {
  constructor(code, message) {
    super(message || code);
    this.code = code;
  }
}

// SQLite primary result codes that mean the storage, not the event, failed: never set an event aside for them.
const FATAL_CODES = new Set([7, 8, 10, 11, 13, 14, 26]); // NOMEM, READONLY, IOERR, CORRUPT, FULL, CANTOPEN, NOTADB

function isFatal(e) {
  return e && typeof e.resultCode === 'number' && FATAL_CODES.has(e.resultCode & 0xff);
}

export class Store {
  /** [db]: an open sqlite3 oo1 DB (empty for a new shop). */
  constructor(db) {
    this.db = db;
  }

  /** 'ok' when the database is ours and current; 'empty' for a new file; 'reset' when it must start over. */
  check() {
    const hasMeta = this.db.selectValue("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'meta'");
    if (!hasMeta) return 'empty';
    if (this.meta('web.format') !== String(WEB_FORMAT)) return 'reset';
    if (this.meta('web.source') === 'drive' && this.meta('web.schema') !== String(APP_SCHEMA)) return 'reset';
    return 'ok';
  }

  /** Creates the app's tables in an empty database, for a shop read from Google Drive. */
  createForDrive() {
    this.db.transaction(() => {
      for (const s of STATEMENTS) this.db.exec(s);
      for (const s of WEB_INDEXES) this.db.exec(s);
      this.setMeta('web.format', String(WEB_FORMAT));
      this.setMeta('web.schema', String(APP_SCHEMA));
      this.setMeta('web.source', 'drive');
    });
  }

  /** Marks a database taken from a backup file as ours ([header]: its backup.json). */
  adoptBackup(header) {
    const schema = Number(this.db.selectValue("SELECT value FROM meta WHERE key = 'web.schema'") ??
      this.db.selectValue('PRAGMA user_version') ?? 0);
    if (schema < MIN_BACKUP_SCHEMA) throw new SyncError('OLD_BACKUP', `backup schema ${schema}`);
    this.db.transaction(() => {
      for (const s of WEB_INDEXES) this.db.exec(s);
      this.setMeta('web.format', String(WEB_FORMAT));
      this.setMeta('web.schema', String(schema));
      this.setMeta('web.source', 'backup');
      this.setMeta('web.backup', JSON.stringify(header ?? {}));
    });
  }

  meta(key) {
    return this.db.selectValue('SELECT value FROM meta WHERE key = ?', [key]) ?? null;
  }

  setMeta(key, value) {
    if (value == null) this.db.exec({ sql: 'DELETE FROM meta WHERE key = ?', bind: [key] });
    else this.db.exec({ sql: 'INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)', bind: [key, String(value)] });
  }

  cursors() {
    const m = new Map();
    for (const [dev, seq] of this.db.selectArrays('SELECT dev, seq FROM sync_cursor')) m.set(Number(dev), Number(seq));
    return m;
  }

  setCursor(dev, seq, lastHlc) {
    this.db.exec({
      sql: 'INSERT OR REPLACE INTO sync_cursor(dev, seq, last_hlc, updated_at) VALUES(?, ?, ?, ?)',
      bind: [dev, seq, lastHlc ?? 0n, Date.now()],
    });
  }

  /** Applies [segments] ({dev, seq, events}) and brings the derived tables up to date, in one transaction. */
  applySegments(segments) {
    const touched = new Touched();
    let deferred = 0;
    this.db.transaction(() => {
      const imp = new Importer(this.db, touched);
      for (const seg of segments) {
        let max = 0n;
        for (const e of seg.events) {
          if (e.hlc > max) max = e.hlc;
          touched.events++;
          if (!imp.knows(e.entity)) {
            imp.defer(e); // from a newer app: applied once this page knows it
            deferred++;
            continue;
          }
          this.db.exec('SAVEPOINT ev');
          try {
            imp.apply(e);
            this.db.exec('RELEASE ev');
          } catch (x) {
            this.db.exec('ROLLBACK TO ev');
            this.db.exec('RELEASE ev');
            if (isFatal(x)) throw x;
            imp.defer(e); // an event this page cannot apply waits aside, like on a till (D-061)
            deferred++;
          }
        }
        this.setCursor(seg.dev, seg.seq, max);
      }
      applyDerived(this.db, touched);
    });
    return { touched, deferred };
  }

  /** Tries the events set aside again (after this page was updated); returns how many went in. */
  retryDeferred() {
    const rows = this.db.selectArrays('SELECT seq, entity, op, row_id, hlc, payload FROM sync_deferred ORDER BY seq LIMIT 2000');
    if (rows.length === 0) return 0;
    let applied = 0;
    const touched = new Touched();
    this.db.transaction(() => {
      const imp = new Importer(this.db, touched);
      for (const [seq, entity, op, rowId, hlc, payload] of rows) {
        if (!imp.knows(Number(entity))) continue;
        this.db.exec('SAVEPOINT ev');
        try {
          imp.apply({ entity: Number(entity), op: Number(op), rowId, hlc: BigInt(hlc), payload: parse(payload) });
          this.db.exec({ sql: 'DELETE FROM sync_deferred WHERE seq = ?', bind: [seq] });
          this.db.exec('RELEASE ev');
          applied++;
        } catch (x) {
          this.db.exec('ROLLBACK TO ev');
          this.db.exec('RELEASE ev');
          if (isFatal(x)) throw x;
        }
      }
      applyDerived(this.db, touched);
    });
    return applied;
  }

  /**
   * One round: reads every new file of the shop's tills from [provider] and applies it. [provider]:
   * {list(word, sinceMs?) → [{name, id, size, props, created}], get(file) → Uint8Array}. [progress]
   * gets {phase, done, total}. Returns what happened.
   *
   * A file that left the folder while the round read it (a till packed it, D-085) ends that till's reading;
   * the round then runs once more on the whole folder, where its pack is (SyncEngine.import). It failed the
   * whole round with Google Drive's "404" before.
   */
  async sync(provider, progress = () => {}, now = Date.now()) {
    const first = await this.round(provider, progress, now, false);
    if (!first.gone) return first.result;
    const again = (await this.round(provider, progress, now, true)).result;
    return {
      ...again,
      files: first.result.files + again.files,
      events: first.result.events + again.events,
      deferred: first.result.deferred + again.deferred,
      entities: [...new Set([...first.result.entities, ...again.entities])],
    };
  }

  /** [sync]'s round; [forceFull]: list the whole folder. Returns {result, gone}: [gone] when a listed file had left the folder. */
  async round(provider, progress, now, forceFull) {
    progress({ phase: 'connect', done: 0, total: 0 });
    const stores = [...new Set((await provider.list('store')).map((f) => parseStoreName(f.name)).filter(Boolean))].sort();
    if (stores.length === 0) throw new SyncError('NO_STORE');
    const store = stores[0]; // the one every till settles on (SyncEngine.stores)
    const known = this.meta('web.store');
    if (known && known !== store) throw new SyncError('STORE_MOVED');

    // The tills' cards: names, versions, last seen; a till that published everything again (fullFrom).
    const cardFiles = (await provider.list('dev')).filter((f) => f.name.startsWith(`dev-${store}-`));
    const cards = [];
    for (const f of cardFiles) {
      try {
        const card = parseCard(new TextDecoder().decode(await provider.get(f)));
        if (card) cards.push(card);
      } catch (e) {
        if (e instanceof SyncError) throw e; // signed out, offline
      }
    }
    cards.sort((a, b) => a.dev - b.dev);

    let cursors = this.cursors();
    const fullAt = Number(this.meta('web.full_list_at') ?? 0);
    const since = Number(this.meta('web.list_since') ?? 0);
    let full = forceFull || !known || since === 0 || now - fullAt > FULL_LIST_MS;
    let listing = await this.listSegments(provider, store, full ? null : since);
    if (!full && hasGap(listing.files, cursors)) {
      full = true; // a file is missing from the short listing: list the whole folder
      listing = await this.listSegments(provider, store, null);
    }
    if (full) {
      // A till numbered its files from 1 again (it moved the store here, or the folder was emptied):
      // read it again from its first file. Reading a file twice changes nothing.
      for (const [dev, seq] of cursors) {
        if (seq > (listing.highest.get(dev) ?? 0)) cursors.delete(dev);
      }
      // A till whose first files are gone published everything again from file fullFrom on.
      for (const c of cards) {
        const cursor = cursors.get(c.dev) ?? 0;
        if (c.fullFrom <= 1 || cursor >= c.fullFrom - 1) continue;
        if (!listing.files.some((f) => f.dev === c.dev && f.from <= cursor + 1 && f.to >= cursor + 1)) cursors.set(c.dev, c.fullFrom - 1);
      }
    }

    // Which files to read: for each till, the ones after its cursor, in order, up to the first gap — a pack where
    // its segments are gone, or where it holds many of them (D-085). [after]: what the till had read before the file.
    const byDev = new Map();
    for (const f of listing.files) {
      if (!byDev.has(f.dev)) byDev.set(f.dev, []);
      byDev.get(f.dev).push(f);
    }
    const plan = [];
    for (const [dev, files] of [...byDev].sort((a, b) => a[0] - b[0])) {
      let after = cursors.get(dev) ?? 0;
      for (const f of planFiles(after, files)) {
        plan.push({ ...f, after });
        after = f.to;
      }
    }

    if (this.db.selectValue('SELECT 1 FROM sync_deferred LIMIT 1')) this.retryDeferred();

    // Download ahead, apply in order, a batch of files per transaction.
    let done = 0;
    let read = 0; // files applied ([done] also counts those passed by for a till whose file was gone)
    let events = 0;
    let deferred = 0;
    const entities = new Set();
    progress({ phase: 'receive', done, total: plan.length });
    const pending = [];
    // Tills whose next file left the folder while this round read it: the rest of their files wait for the next round.
    const gone = new Set();
    const fetchAt = (i) => {
      const f = plan[i];
      const p = gone.has(f.dev) ? Promise.resolve(null) : this.download(provider, f, store);
      p.catch(() => {}); // seen when its turn comes; a failure ahead of it must not go unheard
      return { f, p };
    };
    let next = 0;
    while (next < plan.length && pending.length < PARALLEL) pending.push(fetchAt(next++));
    let batch = [];
    let batchEvents = 0;
    const flush = () => {
      if (batch.length === 0) return;
      const r = this.applySegments(batch);
      events += r.touched.events;
      deferred += r.deferred;
      for (const e of r.touched.entities) entities.add(e);
      batch = [];
      batchEvents = 0;
      progress({ phase: 'receive', done, total: plan.length });
    };
    const add = (seg) => {
      batch.push(seg);
      batchEvents += seg.events.length;
      if (batchEvents >= BATCH_EVENTS || batch.length >= BATCH_FILES) flush();
    };
    while (pending.length > 0) {
      const { f, p } = pending.shift();
      let item;
      try {
        item = await p;
      } catch (e) {
        if (e?.status !== 404) throw e;
        gone.add(f.dev);
      }
      if (next < plan.length) pending.push(fetchAt(next++));
      if (gone.has(f.dev)) {
        done++;
        continue; // this till's files after the one gone are read from its pack, on the whole listing
      }
      if (item.pack) {
        // Unpacked only now, a segment at a time (a pack is up to a few thousand of them).
        const segments = await readPack(item.bytes, item, item.after);
        for (const { header, events: evs } of segments) add({ dev: item.dev, seq: header.seq, events: evs });
      } else {
        add(item);
      }
      done++;
      read++;
    }
    flush();

    this.db.transaction(() => {
      this.setMeta('web.store', store);
      this.setMeta('web.cards', JSON.stringify(cards));
      this.setMeta('web.synced_at', now);
      if (listing.newest > 0) this.setMeta('web.list_since', Math.max(0, listing.newest - LIST_OVERLAP_MS));
      if (gone.size > 0) this.setMeta('web.full_list_at', 0); // the next round lists the whole folder
      else if (full) this.setMeta('web.full_list_at', now);
    });
    progress({ phase: 'done', done, total: plan.length });
    return { result: { store, files: read, events, deferred, entities: [...entities], tills: cards.length }, gone: gone.size > 0 };
  }

  // ------------------------------------------------------------------------------- changing the shop (D-083)

  /** The web page as a till of the shop: {dev, uuid, name, cardId}, or null before its first change. */
  identity() {
    const dev = this.meta('web.dev');
    if (!dev) return null;
    return { dev: Number(dev), uuid: this.meta('web.uuid'), name: this.meta('web.device_name') || 'Lekas Manager', cardId: this.meta('web.card_id') };
  }

  /**
   * The page's till number, taken on its first change: random 22 bits no card and no file of the folder uses (sync.md
   * §2), then its card is published so the tills know it before any of its files.
   */
  async ensureIdentity(provider, name) {
    const known = this.identity();
    if (known) return known;
    const cards = JSON.parse(this.meta('web.cards') ?? '[]');
    const taken = new Set([0, ...cards.map((c) => c.dev), ...this.cursors().keys()]);
    const range = 2 ** DEVICE_BITS - 1;
    let dev;
    do {
      dev = 1 + (crypto.getRandomValues(new Uint32Array(1))[0] % range);
    } while (taken.has(dev));
    this.db.transaction(() => {
      this.setMeta('web.dev', dev);
      this.setMeta('web.uuid', crypto.randomUUID());
      this.setMeta('web.device_name', name || 'Lekas Manager');
      this.setMeta('web.next_seq', 1);
      this.setMeta('web.seg_seq', 0);
    });
    await this.putCard(provider);
    return this.identity();
  }

  /** The page's device card `dev-{store}-{dev}.json` (SyncEngine.putCard): app "web", no receipt prefix. */
  async putCard(provider, now = Date.now()) {
    const me = this.identity();
    const store = this.meta('web.store');
    const cursors = {};
    for (const [d, s] of this.cursors()) if (d !== me.dev) cursors[String(d)] = BigInt(s);
    const bytes = new TextEncoder().encode(jsonText({
      dev: BigInt(me.dev), uuid: me.uuid, name: me.name, app: 'web', lastSeen: BigInt(now),
      lastSeq: BigInt(this.meta('web.seg_seq') ?? 0), prefix: null, cursors, packs: 1n, // it reads packs (D-085)
    }));
    if (me.cardId) {
      try {
        await provider.update(me.cardId, bytes);
        return;
      } catch (e) {
        if (e.status !== 404) throw e; // removed meanwhile: made again below
      }
    }
    const name = `dev-${store}-${me.dev}.json`;
    const copies = await provider.find(name);
    let id;
    if (copies.length) {
      for (const extra of copies.slice(1)) await provider.remove(extra.id); // a create whose answer was lost
      id = copies[0].id;
      await provider.update(id, bytes);
    } else {
      id = await provider.create(name, bytes);
    }
    this.setMeta('web.card_id', id);
  }

  /**
   * One change of the shop: the copy is brought up to date, [build] (a Writer → its result) makes the change's events
   * from it, they go to the folder as this page's next sync file, and the copy then reads that file back and applies
   * it like any till's. Nothing is applied when the upload fails. [opts]: {staffId, currency, deviceName, progress}.
   * Returns {result, events, seq}.
   */
  async commit(provider, build, opts = {}) {
    await this.sync(provider, opts.progress);
    const store = this.meta('web.store');
    const me = await this.ensureIdentity(provider, opts.deviceName);
    const clock = new Clock(BigInt(this.meta('web.hlc') ?? 0));
    const seen = this.db.selectValue('SELECT MAX(last_hlc) FROM sync_cursor');
    if (seen) clock.observe(BigInt(seen));
    let seq = BigInt(this.meta('web.next_seq') ?? 1);
    const writer = new Writer(this.db, { dev: me.dev, clock, nextSeq: () => seq++, staffId: opts.staffId, currency: opts.currency, now: opts.now });
    const result = build(writer);
    if (writer.events.length === 0) return { result, events: 0, seq: null };
    // Ids and the clock are kept before anything leaves: an upload that failed never hands them out twice.
    this.db.transaction(() => {
      this.setMeta('web.next_seq', seq);
      this.setMeta('web.hlc', clock.last);
    });
    // After this page's highest file in the folder: one sent before its answer was lost is read, never overwritten.
    let next = Number(this.meta('web.seg_seq') ?? 0) + 1;
    for (const f of await provider.list(`seg-${store}-${me.dev}-`)) {
      const s = parseSegmentName(f.name);
      if (s && s.store === store && s.dev === me.dev && s.seq >= next) next = s.seq + 1;
    }
    const seg = await writeSegment({ store, dev: me.dev, seq: next, events: writer.events });
    await provider.create(seg.name, seg.bytes, { sha256: seg.sha256, count: String(seg.count) });
    this.setMeta('web.seg_seq', next);
    const { events } = await readSegment(seg.bytes, { store, dev: me.dev, seq: next });
    this.applySegments([{ dev: me.dev, seq: next, events }]);
    try {
      await this.putCard(provider);
    } catch {
      // The card only says when this page was last here: the next change sends it again.
    }
    return { result, events: events.length, seq: next };
  }

  /** The shop's segments and packs in the folder (created after [since] when given); a pack counts with its last number. */
  async listSegments(provider, store, since) {
    const files = [];
    const highest = new Map();
    let newest = 0;
    for (const f of await provider.list('seg', since ?? undefined)) {
      if (f.created > newest) newest = f.created;
      const s = parseFileName(f.name);
      if (!s || s.store !== store) continue;
      files.push({ ...s, file: f });
      if (s.to > (highest.get(s.dev) ?? 0)) highest.set(s.dev, s.to);
    }
    return { files, highest, newest };
  }

  /**
   * Downloads one file; checked against its checksum (once more before calling it damaged). A segment is read
   * at once; a pack is left as bytes, read when its turn comes (several are downloaded ahead).
   */
  async download(provider, f, store) {
    const what = f.pack ? `pack ${f.dev}/${f.from}-${f.to}` : `segment ${f.dev}/${f.from}`;
    const expected = f.file.props?.sha256;
    let bytes = await provider.get(f.file);
    if (expected && (await sha256(bytes)) !== expected) {
      bytes = await provider.get(f.file);
      if ((await sha256(bytes)) !== expected) throw new CorruptFile(`${what} does not match its checksum`);
    }
    if (f.pack) return { pack: true, store, dev: f.dev, from: f.from, to: f.to, after: f.after, bytes };
    const { events } = await readSegment(bytes, { store, dev: f.dev, seq: f.from });
    return { dev: f.dev, seq: f.from, events };
  }
}

/** True when a till has files after its cursor that do not follow on from it. */
function hasGap(files, cursors) {
  const byDev = new Map();
  for (const f of files) {
    if (!byDev.has(f.dev)) byDev.set(f.dev, []);
    byDev.get(f.dev).push(f);
  }
  for (const [dev, tillFiles] of byDev) {
    const cursor = cursors.get(dev) ?? 0;
    const furthest = tillFiles.reduce((m, f) => Math.max(m, f.to), 0);
    if (furthest > cursor && reach(cursor, tillFiles) < furthest) return true;
  }
  return false;
}

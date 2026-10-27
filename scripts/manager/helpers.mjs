// Test helpers for the manager web page (node --test scripts/manager/): the page's own SQLite build,
// sync files written like a till's (SegmentCodec), and a folder provider like the app's FolderProvider.
import { readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { gzipSync, gunzipSync, deflateRawSync } from 'node:zlib';
import { stringify } from '../../site/manager/js/json64.js';

const vendor = new URL('../../site/manager/vendor/sqlite/', import.meta.url);
let loaded = null;

/** The web page's SQLite (the same files the browser loads), in memory. */
export async function sqlite() {
  if (!loaded) {
    const { default: init } = await import(new URL('sqlite3.mjs', vendor));
    const quiet = console.warn;
    console.warn = () => {}; // "Ignoring inability to install the OPFS VFS": Node has none
    try {
      loaded = await init({ wasmBinary: readFileSync(new URL('sqlite3.wasm', vendor)), print: () => {}, printErr: () => {} });
    } finally {
      console.warn = quiet;
    }
  }
  return loaded;
}

export async function memoryDb() {
  const s = await sqlite();
  return new s.oo1.DB(':memory:');
}

/** A database image ([bytes]) opened in memory. */
export async function dbFromBytes(bytes) {
  const s = await sqlite();
  const db = new s.oo1.DB(':memory:');
  const p = s.wasm.allocFromTypedArray(bytes);
  const rc = s.capi.sqlite3_deserialize(db.pointer, 'main', p, bytes.length, bytes.length,
    s.capi.SQLITE_DESERIALIZE_FREEONCLOSE | s.capi.SQLITE_DESERIALIZE_RESIZEABLE);
  db.checkRc(rc);
  return db;
}

export const SEQ_BITS = 41n;
export const idOf = (dev, seq) => (BigInt(dev) << SEQ_BITS) | BigInt(seq);
/** An HLC of [ms] with counter [c] (Hlc.pack). */
export const hlcOf = (ms, c = 0) => (BigInt(ms) << 16n) | BigInt(c);

/** A segment file as a till writes it: gzip NDJSON, header first. [events]: {e, o, r, h, p}. */
export function segment(store, dev, seq, events) {
  let first = 0n;
  let last = 0n;
  for (const e of events) {
    if (first === 0n || e.h < first) first = e.h;
    if (e.h > last) last = e.h;
  }
  const head = stringify({ v: 1n, store, dev: BigInt(dev), seq: BigInt(seq), count: BigInt(events.length), firstHlc: first, lastHlc: last });
  const lines = [head, ...events.map((e) => `{"e":${e.e},"o":${e.o ?? 2},"r":${e.r ?? 'null'},"h":${e.h},"p":${stringify(e.p)}}`)];
  return new Uint8Array(gzipSync(Buffer.from(lines.join('\n') + '\n', 'utf8')));
}

/**
 * A pack as a till writes it (SegmentCodec.writePack, D-085): a pack header, then each segment's own lines.
 * [segments]: [{seq, events}], numbers following on.
 */
export function pack(store, dev, segments) {
  const total = segments.reduce((n, s) => n + s.events.length, 0);
  const from = segments[0].seq;
  const to = segments[segments.length - 1].seq;
  const head = stringify({ v: 1n, pack: 1n, store, dev: BigInt(dev), from: BigInt(from), to: BigInt(to), count: BigInt(total) });
  const body = segments.map((s) => gunzipSync(segment(store, dev, s.seq, s.events)).toString('utf8')).join('');
  return new Uint8Array(gzipSync(Buffer.from(`${head}\n${body}`, 'utf8')));
}

export const sha256hex = (bytes) => createHash('sha256').update(bytes).digest('hex');

/** A sync folder in memory, read the way DriveProvider lists and downloads. */
export class MemoryFolder {
  constructor() {
    this.files = [];
    this.clock = 1_700_000_000_000;
    this.gets = 0;
  }

  put(name, bytes, props = {}) {
    this.files = this.files.filter((f) => f.name !== name);
    this.files.push({ name, id: `id-${name}`, size: bytes.length, props, created: this.clock++, bytes });
  }

  putJson(name, value) {
    this.put(name, new TextEncoder().encode(stringify(value)));
  }

  putSegment(store, dev, seq, events) {
    const bytes = segment(store, dev, seq, events);
    this.put(`seg-${store}-${dev}-${String(seq).padStart(8, '0')}.ndjson.gz`, bytes, { sha256: sha256hex(bytes), count: String(events.length) });
  }

  /** Till [dev]'s segments [segments] ({seq, events}) as one pack, and those segments gone from the folder (D-085). */
  putPack(store, dev, segments) {
    const bytes = pack(store, dev, segments);
    const pad = (n) => String(n).padStart(8, '0');
    const [from, to] = [segments[0].seq, segments[segments.length - 1].seq];
    this.put(`seg-${store}-${dev}-${pad(from)}-${pad(to)}.pack.gz`, bytes,
      { sha256: sha256hex(bytes), count: String(segments.reduce((n, s) => n + s.events.length, 0)) });
    const gone = new Set(segments.map((s) => `seg-${store}-${dev}-${pad(s.seq)}.ndjson.gz`));
    this.files = this.files.filter((f) => !gone.has(f.name));
  }

  async list(word, since) {
    return this.files.filter((f) => f.name.startsWith(word) && (since == null || f.created > since))
      .map(({ bytes, ...f }) => f);
  }

  async get(f) {
    this.gets++;
    this.beforeGet?.(f); // tests: a till changes the folder while it is read
    const file = this.files.find((x) => x.id === f.id);
    if (!file) throw Object.assign(new Error(`no file ${f.name}`), { status: 404 }); // as DriveFolder: removed meanwhile
    return file.bytes;
  }

  // Writes, as DriveFolder makes them (the web page's own files, D-083). [failNext]: the next create fails after
  // storing the file (an answer lost on the way back), or before (offline) with 'before'.
  async create(name, bytes, props = {}) {
    const lost = this.failNext;
    this.failNext = null;
    if (lost === 'before') throw Object.assign(new Error('offline'), { code: 'OFFLINE' });
    const id = `id-${name}-${this.clock}`;
    this.files.push({ name, id, size: bytes.length, props, created: this.clock++, bytes: new Uint8Array(bytes) });
    if (lost === 'after') throw Object.assign(new Error('answer lost'), { code: 'OFFLINE' });
    return id;
  }

  async find(name) {
    return this.files.filter((f) => f.name === name).map(({ bytes, ...f }) => f);
  }

  async update(id, bytes) {
    const f = this.files.find((x) => x.id === id);
    if (!f) throw Object.assign(new Error('not found'), { code: 'DRIVE', status: 404 });
    f.bytes = new Uint8Array(bytes);
    f.size = bytes.length;
  }

  async remove(id) {
    this.files = this.files.filter((f) => f.id !== id);
  }

  /** The text of the file [name] (the newest copy). */
  text(name) {
    const f = this.files.filter((x) => x.name === name).pop();
    return f ? new TextDecoder().decode(f.bytes) : null;
  }
}

function crc32(bytes) {
  let c = 0xffffffff;
  for (const b of bytes) {
    c ^= b;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  }
  return (c ^ 0xffffffff) >>> 0;
}

/** A sync folder on disk as FolderProvider leaves it (props in `name.props` side files), read like Google Drive. */
export class DiskFolder {
  constructor(dir, files) {
    this.dir = dir;
    this.files = files; // [{name, bytes, props}]
  }

  async list(word, since) {
    return this.files.filter((f) => f.name.startsWith(word))
      .map((f, i) => ({ name: f.name, id: f.name, size: f.bytes.length, props: f.props, created: 1_000 + i }))
      .filter((f) => since == null || f.created > since);
  }

  async get(f) {
    return this.files.find((x) => x.name === f.id).bytes;
  }

  // The web page's own files (D-083), written as FolderProvider keeps them after the fixture's renaming: the file and
  // its props in `name.props` (key=value lines). [written]: the names this folder wrote.
  async create(name, bytes, props = {}) {
    if (this.files.some((f) => f.name === name)) return name;
    this.files.push({ name, bytes: new Uint8Array(bytes), props });
    (this.written ??= new Set()).add(name);
    if (this.dir) {
      writeFileSync(join(this.dir, name), bytes);
      const lines = Object.entries(props).map(([k, v]) => `${k}=${v}\n`).join('');
      if (lines) writeFileSync(join(this.dir, `${name}.props`), lines);
    }
    return name;
  }

  async find(name) {
    return (await this.list(name)).filter((f) => f.name === name);
  }

  async update(id, bytes) {
    const f = this.files.find((x) => x.name === id);
    if (!f) throw Object.assign(new Error('not found'), { code: 'DRIVE', status: 404 });
    f.bytes = new Uint8Array(bytes);
    (this.written ??= new Set()).add(id);
    if (this.dir) writeFileSync(join(this.dir, id), bytes);
  }

  async remove(id) {
    this.files = this.files.filter((f) => f.name !== id);
  }
}

/** A FolderProvider folder on disk (props in `name.props`), read like Google Drive, written like it too. */
export function readDiskFolder(dir) {
  const files = readdirSync(dir)
    .filter((n) => !n.endsWith('.props') && !n.endsWith('.lekasbak') && !n.endsWith('.json.expected'))
    .map((name) => {
      const p = join(dir, `${name}.props`);
      const props = {};
      if (existsSync(p)) {
        for (const line of readFileSync(p, 'utf8').split('\n')) {
          const at = line.indexOf('=');
          if (at > 0) props[line.slice(0, at)] = line.slice(at + 1).trim();
        }
      }
      return { name, bytes: new Uint8Array(readFileSync(join(dir, name))), props };
    });
  return new DiskFolder(dir, files);
}

/** A ZIP of [entries] ({name: Uint8Array}) as java.util.zip writes it (deflated). */
export function zip(entries) {
  const parts = [];
  const dir = [];
  let offset = 0;
  for (const [name, data] of Object.entries(entries)) {
    const nameBytes = Buffer.from(name, 'utf8');
    const comp = deflateRawSync(data);
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0, 6);
    local.writeUInt16LE(8, 8);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(comp.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    parts.push(local, nameBytes, comp);
    const c = Buffer.alloc(46);
    c.writeUInt32LE(0x02014b50, 0);
    c.writeUInt16LE(20, 4);
    c.writeUInt16LE(20, 6);
    c.writeUInt16LE(8, 10);
    c.writeUInt32LE(crc, 16);
    c.writeUInt32LE(comp.length, 20);
    c.writeUInt32LE(data.length, 24);
    c.writeUInt16LE(nameBytes.length, 28);
    c.writeUInt32LE(offset, 42);
    dir.push(c, nameBytes);
    offset += 30 + nameBytes.length + comp.length;
  }
  const dirBuf = Buffer.concat(dir);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(Object.keys(entries).length, 8);
  end.writeUInt16LE(Object.keys(entries).length, 10);
  end.writeUInt32LE(dirBuf.length, 12);
  end.writeUInt32LE(offset, 16);
  return new Uint8Array(Buffer.concat([...parts, dirBuf, end]));
}

// A `.lekasbak` backup file (data/backup/BackupFiles.kt, D-044): a ZIP with `backup.json` (what it is)
// and the shop's SQLite database `lekaspos.db`, plus `lekaspos.db-wal` when the copy had changes the
// app could not fold in yet. Read entirely in the browser: the file is never uploaded anywhere.

export class BadBackup extends Error {
  constructor(kind, message) {
    super(message || kind);
    this.kind = kind; // NOT_A_BACKUP, DAMAGED, TOO_NEW
  }
}

const u16 = (b, o) => b[o] | (b[o + 1] << 8);
const u32 = (b, o) => (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0;

let CRC_TABLE = null;
function crc32(bytes) {
  if (!CRC_TABLE) {
    CRC_TABLE = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      CRC_TABLE[n] = c >>> 0;
    }
  }
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** The entries of ZIP [blob] (a File or Blob): name → {method, compSize, size, crc, offset}. */
async function entries(blob) {
  const tailSize = Math.min(blob.size, 65557);
  const tail = new Uint8Array(await blob.slice(blob.size - tailSize).arrayBuffer());
  let eocd = -1;
  for (let i = tail.length - 22; i >= 0; i--) {
    if (u32(tail, i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new BadBackup('NOT_A_BACKUP', 'not a ZIP file');
  const count = u16(tail, eocd + 10);
  const dirSize = u32(tail, eocd + 12);
  const dirOffset = u32(tail, eocd + 16);
  if (dirOffset === 0xffffffff || count === 0xffff) throw new BadBackup('DAMAGED', 'ZIP64 files are not supported');
  const dir = new Uint8Array(await blob.slice(dirOffset, dirOffset + dirSize).arrayBuffer());
  const out = new Map();
  let p = 0;
  for (let i = 0; i < count; i++) {
    if (u32(dir, p) !== 0x02014b50) throw new BadBackup('DAMAGED', 'bad ZIP directory');
    const nameLen = u16(dir, p + 28);
    const extraLen = u16(dir, p + 30);
    const commentLen = u16(dir, p + 32);
    const name = new TextDecoder().decode(dir.subarray(p + 46, p + 46 + nameLen));
    out.set(name, {
      method: u16(dir, p + 10), crc: u32(dir, p + 16), compSize: u32(dir, p + 20), size: u32(dir, p + 24),
      local: u32(dir, p + 42),
    });
    p += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}

async function read(blob, e) {
  const head = new Uint8Array(await blob.slice(e.local, e.local + 30).arrayBuffer());
  if (u32(head, 0) !== 0x04034b50) throw new BadBackup('DAMAGED', 'bad ZIP entry');
  const start = e.local + 30 + u16(head, 26) + u16(head, 28);
  const raw = blob.slice(start, start + e.compSize);
  let bytes;
  if (e.method === 0) {
    bytes = new Uint8Array(await raw.arrayBuffer());
  } else if (e.method === 8) {
    const out = new Uint8Array(e.size);
    let at = 0;
    const reader = raw.stream().pipeThrough(new DecompressionStream('deflate-raw')).getReader();
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        if (at + value.length > out.length) throw new BadBackup('DAMAGED', 'a ZIP entry is longer than it says');
        out.set(value, at);
        at += value.length;
      }
    } catch (x) {
      if (x instanceof BadBackup) throw x;
      throw new BadBackup('DAMAGED', `a ZIP entry cannot be unpacked (${x.message})`);
    }
    if (at !== out.length) throw new BadBackup('DAMAGED', 'a ZIP entry is shorter than it says');
    bytes = out;
  } else {
    throw new BadBackup('DAMAGED', `unknown ZIP compression ${e.method}`);
  }
  if (crc32(bytes) !== e.crc) throw new BadBackup('DAMAGED', 'a ZIP entry does not match its checksum');
  return bytes;
}

/**
 * Reads backup [blob]: {header (backup.json), db (Uint8Array, the database with the WAL's committed
 * changes folded in, opened without WAL)}.
 */
export async function readBackup(blob) {
  const list = await entries(blob);
  const head = list.get('backup.json');
  const dbEntry = list.get('lekaspos.db');
  if (!head || !dbEntry) throw new BadBackup('NOT_A_BACKUP', 'not a LekasPOS backup');
  let header;
  try {
    header = JSON.parse(new TextDecoder().decode(await read(blob, head)));
  } catch (e) {
    if (e instanceof BadBackup) throw e;
    throw new BadBackup('NOT_A_BACKUP', 'unreadable backup.json');
  }
  if (!(header.format >= 1)) throw new BadBackup('NOT_A_BACKUP', 'unknown backup format');
  let db = await read(blob, dbEntry);
  const walEntry = list.get('lekaspos.db-wal');
  if (walEntry && walEntry.size > 0) db = applyWal(db, await read(blob, walEntry));
  if (db.length < 100 || new TextDecoder().decode(db.subarray(0, 15)) !== 'SQLite format 3') {
    throw new BadBackup('DAMAGED', 'the database in the backup is damaged');
  }
  db[18] = 1; // opened as a plain (rollback journal) database: the web page's storage has no WAL
  db[19] = 1;
  return { header, db };
}

/** [db] with the committed frames of write-ahead log [wal] written into it (the SQLite WAL format). */
export function applyWal(db, wal) {
  if (wal.length < 32) return db;
  const be32 = (o) => ((wal[o] << 24) | (wal[o + 1] << 16) | (wal[o + 2] << 8) | wal[o + 3]) >>> 0;
  const magic = be32(0);
  if (magic !== 0x377f0682 && magic !== 0x377f0683) return db;
  const bigEndian = (magic & 1) === 1;
  const pageSize = be32(8) === 1 ? 65536 : be32(8);
  const salt1 = be32(16);
  const salt2 = be32(20);
  const word = bigEndian ? be32 : (o) => u32(wal, o);
  let s0 = 0;
  let s1 = 0;
  const sum = (from, to) => {
    for (let i = from; i < to; i += 8) {
      s0 = (s0 + word(i) + s1) >>> 0;
      s1 = (s1 + word(i + 4) + s0) >>> 0;
    }
  };
  sum(0, 24);
  if (s0 !== be32(24) || s1 !== be32(28)) return db; // the log's header is damaged: nothing in it counts
  const frames = []; // [page, offset of its data]
  let committed = [];
  let dbPages = 0;
  for (let o = 32; o + 24 + pageSize <= wal.length; o += 24 + pageSize) {
    if (be32(o + 8) !== salt1 || be32(o + 12) !== salt2) break; // left over from an earlier log
    sum(o, o + 8);
    sum(o + 24, o + 24 + pageSize);
    if (s0 !== be32(o + 16) || s1 !== be32(o + 20)) break; // torn write: stops here
    frames.push([be32(o), o + 24]);
    const commit = be32(o + 4);
    if (commit > 0) {
      committed = frames.slice();
      dbPages = commit;
    }
  }
  if (committed.length === 0) return db;
  const out = new Uint8Array(dbPages * pageSize);
  out.set(db.subarray(0, Math.min(db.length, out.length)));
  for (const [page, at] of committed) {
    if (page >= 1 && page <= dbPages) out.set(wal.subarray(at, at + pageSize), (page - 1) * pageSize);
  }
  return out;
}

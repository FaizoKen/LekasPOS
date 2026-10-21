// A till's sync file (references/sync.md §5): `seg-{store}-{till}-{seq}.ndjson.gz`, gzip NDJSON — a
// header line {v, store, dev, seq, count, firstHlc, lastHlc}, then one event per line
// {"e": entity, "o": op, "r": rowId, "h": hlc, "p": payload}. Read like SegmentCodec.read, written like
// SegmentCodec.write (the web page's own changes, D-083).

import { parse } from './json64.js';

export class CorruptFile extends Error {
  get code() {
    return 'CORRUPT';
  }
}

/** (store, till, seq) of a segment name, or null (SyncNames.parseSegment). */
export function parseSegmentName(name) {
  const ext = '.ndjson.gz';
  if (!name.startsWith('seg-') || !name.endsWith(ext)) return null;
  const body = name.slice(4, name.length - ext.length);
  const lastDash = body.lastIndexOf('-');
  if (lastDash <= 0) return null;
  const devDash = body.lastIndexOf('-', lastDash - 1);
  if (devDash <= 0) return null;
  const store = body.slice(0, devDash);
  const devText = body.slice(devDash + 1, lastDash);
  const seqText = body.slice(lastDash + 1);
  if (!/^\d+$/.test(devText) || !/^\d+$/.test(seqText)) return null;
  const dev = Number(devText);
  const seq = Number(seqText);
  if (seq < 1 || !Number.isSafeInteger(dev) || !Number.isSafeInteger(seq)) return null;
  return { store, dev, seq };
}

/** The store id of `store-{id}.json`, or null. */
export function parseStoreName(name) {
  if (!name.startsWith('store-') || !name.endsWith('.json')) return null;
  const id = name.slice(6, name.length - 5);
  return id || null;
}

export async function sha256(bytes) {
  const d = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  let s = '';
  for (const b of d) s += b.toString(16).padStart(2, '0');
  return s;
}

export async function gunzip(bytes) {
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
  return new Response(stream).text();
}

export async function gzip(bytes) {
  const stream = new Blob([bytes]).stream().pipeThrough(new CompressionStream('gzip'));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

/** The name of segment [seq] of till [dev] (SyncNames.segment: the number padded to 8 digits). */
export const segmentName = (store, dev, seq) => `seg-${store}-${dev}-${String(seq).padStart(8, '0')}.ndjson.gz`;

/**
 * JSON text as the tills' JsonWriter writes it: integers as plain digits (BigInt, or a safe Number), keys in their
 * order, true/false as 1/0 (SQLite has no booleans: Outbox.writeRow), text escaped by JSON.stringify.
 */
export function jsonText(v) {
  if (v == null) return 'null';
  switch (typeof v) {
    case 'bigint': return v.toString();
    case 'number':
      if (!Number.isSafeInteger(v)) throw new Error(`not an integer: ${v}`);
      return String(v);
    case 'boolean': return v ? '1' : '0';
    case 'string': return JSON.stringify(v);
    default:
      if (Array.isArray(v)) return `[${v.map(jsonText).join(',')}]`;
      return `{${Object.entries(v).map(([k, x]) => `${JSON.stringify(k)}:${jsonText(x)}`).join(',')}}`;
  }
}

/**
 * A segment of [events] ({entity, op, rowId, hlc, payload}) as SegmentCodec.write makes it: the gzip bytes and their
 * SHA-256, which goes with the file (Drive appProperties `sha256` and `count`).
 */
export async function writeSegment({ store, dev, seq, events }) {
  if (events.length === 0) throw new Error('an empty segment');
  let first = events[0].hlc;
  let last = events[0].hlc;
  for (const e of events) {
    if (e.hlc < first) first = e.hlc;
    if (e.hlc > last) last = e.hlc;
  }
  const head = jsonText({ v: 1n, store, dev: BigInt(dev), seq: BigInt(seq), count: BigInt(events.length), firstHlc: first, lastHlc: last });
  let text = `${head}\n`;
  for (const e of events) {
    text += `{"e":${e.entity},"o":${e.op},"r":${e.rowId == null ? 'null' : BigInt(e.rowId)},"h":${BigInt(e.hlc)},"p":${jsonText(e.payload)}}\n`;
  }
  const bytes = await gzip(new TextEncoder().encode(text));
  return { name: segmentName(store, dev, seq), bytes, sha256: await sha256(bytes), count: events.length };
}

/**
 * The events of segment [bytes] (gzip), checked: the header must name [expect] {store, dev, seq} and the
 * number of events must be the header's count.
 */
export async function readSegment(bytes, expect) {
  let text;
  try {
    text = await gunzip(bytes);
  } catch (e) {
    throw new CorruptFile(`segment ${expect.dev}/${expect.seq}: not a gzip file (${e.message})`);
  }
  const lines = text.split('\n');
  let first = 0;
  const header = parseLine(lines[first] ?? '', 'header');
  const need = (k) => {
    const v = header[k];
    if (typeof v !== 'bigint') throw new CorruptFile(`header without ${k}`);
    return v;
  };
  if (typeof header.store !== 'string') throw new CorruptFile('header without store');
  const h = {
    v: Number(need('v')), store: header.store, dev: Number(need('dev')), seq: Number(need('seq')),
    count: Number(need('count')), firstHlc: need('firstHlc'), lastHlc: need('lastHlc'),
  };
  if (h.store !== expect.store || h.dev !== expect.dev || h.seq !== expect.seq) {
    throw new CorruptFile(`segment ${expect.dev}/${expect.seq} has a wrong header`);
  }
  const events = [];
  for (let i = first + 1; i < lines.length; i++) {
    const line = lines[i].endsWith('\r') ? lines[i].slice(0, -1) : lines[i];
    if (!line) continue;
    const m = parseLine(line, 'event');
    if (typeof m.e !== 'bigint') throw new CorruptFile('event without entity');
    if (typeof m.h !== 'bigint') throw new CorruptFile('event without hlc');
    if (!m.p || typeof m.p !== 'object' || Array.isArray(m.p)) throw new CorruptFile('event without payload');
    events.push({
      entity: Number(m.e),
      op: typeof m.o === 'bigint' ? Number(m.o) : 0,
      rowId: typeof m.r === 'bigint' ? m.r : null,
      hlc: m.h,
      payload: m.p,
    });
  }
  if (events.length !== h.count) {
    throw new CorruptFile(`segment ${h.dev}/${h.seq}: ${events.length} events, header says ${h.count}`);
  }
  return { header: h, events };
}

function parseLine(line, what) {
  let v;
  try {
    v = parse(line);
  } catch (e) {
    throw new CorruptFile(`bad ${what}: ${e.message}`);
  }
  if (!v || typeof v !== 'object' || Array.isArray(v)) throw new CorruptFile(`bad ${what}`);
  return v;
}

/** A till's card `dev-{store}-{till}.json` (SyncEngine.parseCard), or null. */
export function parseCard(text) {
  let m;
  try {
    m = parse(text);
  } catch {
    return null;
  }
  if (!m || typeof m !== 'object' || typeof m.dev !== 'bigint') return null;
  const num = (v) => (typeof v === 'bigint' ? Number(v) : 0);
  return {
    dev: Number(m.dev),
    uuid: typeof m.uuid === 'string' ? m.uuid : '',
    name: typeof m.name === 'string' ? m.name : '',
    app: typeof m.app === 'string' ? m.app : '',
    lastSeen: num(m.lastSeen),
    lastSeq: num(m.lastSeq),
    prefix: typeof m.prefix === 'string' ? m.prefix : null,
    fullFrom: num(m.fullFrom),
  };
}

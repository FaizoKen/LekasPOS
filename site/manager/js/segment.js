// A till's sync file (references/sync.md §5): `seg-{store}-{till}-{seq}.ndjson.gz`, gzip NDJSON — a
// header line {v, store, dev, seq, count, firstHlc, lastHlc}, then one event per line
// {"e": entity, "o": op, "r": rowId, "h": hlc, "p": payload}. Read like SegmentCodec.read.

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

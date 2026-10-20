// A backup file opens in the browser: the ZIP is read and checked, the write-ahead log's committed
// changes are folded in (a real SQLite log, scripts/manager/fixtures/mkwal.py), and the schema
// snapshot the web page uses is the app's current one.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { readBackup, applyWal, BadBackup } from '../../site/manager/js/backup.js';
import { Store, MIN_BACKUP_SCHEMA } from '../../site/manager/js/store.js';
import { STATEMENTS, APP_SCHEMA } from '../../site/manager/js/schema.js';
import { render, appVersion } from './build-schema.mjs';
import { dbFromBytes, memoryDb, sqlite, zip } from './helpers.mjs';

const fixture = (name) => new Uint8Array(readFileSync(new URL(`fixtures/${name}`, import.meta.url)));

test('the committed frames of a write-ahead log are folded into the database', async () => {
  const main = fixture('wal-main.db');
  const wal = fixture('wal-log.db-wal');
  const plain = (bytes) => Object.assign(bytes.slice(), { 18: 1, 19: 1 }); // opened without WAL, as readBackup does
  const before = await dbFromBytes(plain(main));
  assert.equal(before.selectValue('SELECT COUNT(*) FROM t'), 0);
  before.close();
  const db = await dbFromBytes(plain(applyWal(main, wal)));
  assert.equal(db.selectValue('PRAGMA integrity_check'), 'ok');
  assert.equal(db.selectValue('SELECT COUNT(*) FROM t'), 120);
  assert.equal(db.selectValue("SELECT COUNT(*) FROM t WHERE v LIKE 'uncommitted%'"), 0);
  // A torn log: a frame with a wrong checksum ends it there.
  const torn = wal.slice();
  torn[torn.length - 10] ^= 0xff;
  const partial = await dbFromBytes(plain(applyWal(main, torn)));
  assert.equal(partial.selectValue('SELECT COUNT(*) FROM t'), 120);
  assert.equal(applyWal(main, new Uint8Array(10)), main);
});

async function shopDatabase(schema = APP_SCHEMA) {
  const db = await memoryDb();
  for (const s of STATEMENTS) db.exec(s);
  db.exec(`PRAGMA user_version = ${schema}`);
  db.exec("INSERT INTO setting(key, value, updated_at, ver_hlc, ver_dev) VALUES('store.name', 'Kedai', 0, 0, 0)");
  const s = await sqlite();
  const bytes = s.capi.sqlite3_js_db_export(db.pointer);
  db.close();
  return bytes;
}

test('a .lekasbak backup opens as the shop', async () => {
  const header = { format: 1, createdAt: 1, reason: 'manual', store: 's', device: 'd', deviceNo: 7, schema: APP_SCHEMA, app: '1.20.1', name: 'Kedai', sales: 0, products: 0 };
  const file = zip({ 'backup.json': new TextEncoder().encode(JSON.stringify(header)), 'lekaspos.db': await shopDatabase() });
  const r = await readBackup(new Blob([file]));
  assert.equal(r.header.name, 'Kedai');
  assert.equal(r.db[18], 1);
  const db = await dbFromBytes(r.db);
  const store = new Store(db);
  assert.equal(store.check(), 'reset'); // not ours until adopted
  store.adoptBackup(r.header);
  assert.equal(store.check(), 'ok');
  assert.equal(store.meta('web.source'), 'backup');
  assert.equal(db.selectValue("SELECT value FROM setting WHERE key = 'store.name'"), 'Kedai');

  const old = zip({ 'backup.json': new TextEncoder().encode(JSON.stringify(header)), 'lekaspos.db': await shopDatabase(MIN_BACKUP_SCHEMA - 1) });
  const oldDb = await dbFromBytes((await readBackup(new Blob([old]))).db);
  assert.throws(() => new Store(oldDb).adoptBackup(header), (e) => e.code === 'OLD_BACKUP');
});

test('files that are no backup, or damaged, are told apart', async () => {
  await assert.rejects(readBackup(new Blob([new Uint8Array(100)])), (e) => e instanceof BadBackup && e.kind === 'NOT_A_BACKUP');
  const notOurs = zip({ 'photo.jpg': new Uint8Array([1, 2, 3]) });
  await assert.rejects(readBackup(new Blob([notOurs])), (e) => e.kind === 'NOT_A_BACKUP');
  const good = zip({ 'backup.json': new TextEncoder().encode('{"format":1}'), 'lekaspos.db': await shopDatabase() });
  const bad = good.slice();
  bad[200] ^= 0xff; // inside the database's compressed data
  await assert.rejects(readBackup(new Blob([bad])), (e) => e instanceof BadBackup && e.kind === 'DAMAGED');
});

test('schema.js is the app\'s current schema (run node scripts/manager/build-schema.mjs)', () => {
  assert.equal(APP_SCHEMA, appVersion());
  // A Windows checkout may have CRLF line ends.
  assert.equal(readFileSync(new URL('../../site/manager/js/schema.js', import.meta.url), 'utf8').replace(/\r\n/g, '\n'), render());
});

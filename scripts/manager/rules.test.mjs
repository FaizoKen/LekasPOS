// The app's rules as the web page uses them for its changes (site/manager/js/rules.js, D-083), checked with the
// app's own test cases (core/src/test: SearchTextTest, BarcodeTest, MoneyFormatTest, InventoryMathTest,
// StaffRulesTest, HlcTest), so a product typed on the web page is stored exactly as on a till.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pbkdf2Sync, randomBytes } from 'node:crypto';
import * as X from '../../site/manager/js/rules.js';

test('search keys: accents, case, punctuation, CJK and other scripts (SearchTextTest)', () => {
  assert.equal(X.searchKey('Café Crème'), 'cafe creme');
  assert.equal(X.searchKey('NESTLÉ'), 'nestle');
  assert.equal(X.searchKey('Coca-Cola 1.5L'), 'coca cola 1 5l');
  assert.equal(X.searchKey('  MILO   Activ-Go  '), 'milo activ go');
  assert.equal(X.searchKey('F&N (100 Plus)'), 'f n 100 plus');
  assert.equal(X.searchKey('--- ...'), '');
  assert.equal(X.searchKey('李锦记 蚝油'), '李 锦 记 蚝 油');
  assert.equal(X.searchKey('Nestlé牛奶'), 'nestle 牛 奶');
  assert.equal(X.searchKey('牛奶1kg'), '牛 奶 1kg');
  assert.equal(X.searchKey('தமிழ்'), 'தமிழ்');
  assert.equal(X.searchKey('ไข่ไก่'), 'ไข่ไก่');
  assert.equal(X.searchKey('Ábc'), 'abc');
  assert.equal(X.searchKey(''), '');
});

test('barcodes: the stored form and the forms a scan finds (BarcodeTest)', () => {
  assert.equal(X.gtinCanonical(' 9 556001 234568 '), '9556001234568');
  assert.equal(X.gtinCanonical('955-6001-234568'), '9556001234568');
  assert.equal(X.gtinCanonical('09556001234568'), '9556001234568');
  assert.equal(X.gtinCanonical('ABC123\n'), 'ABC123');
  assert.equal(X.gtinCanonical('1234565'), '01234565');
  assert.equal(X.gtinCanonical('ABC-123'), 'ABC-123');
  assert.equal(X.gtinCanonical('12 34'), '12 34');
  assert.equal(X.gtinCanonical('036000291452'), '036000291452');
  assert.equal(X.gtinCanonical('৯৫৫৬০০১০০০০১৩'), '9556001000013');
  assert.equal(X.asciiDigits('٩٥٥٦٠٠١٠٠٠٠١٣'), '9556001000013');
  assert.equal(X.asciiDigits('MILO-１'), 'MILO-1');
  assert.equal(X.asciiDigits('Kopi'), 'Kopi');
  assert.deepEqual(X.gtinLookupVariants('1234565'), ['1234565', '01234565']);
  assert.deepEqual(X.gtinLookupVariants('36000291452'), ['36000291452', '036000291452']);
  assert.deepEqual(X.gtinLookupVariants('1234567'), ['1234567']);
  assert.deepEqual(X.gtinLookupVariants('036000291452'), ['036000291452', '0036000291452']);
  assert.deepEqual(X.gtinLookupVariants(' 0036000291452 '), ['0036000291452', '036000291452']);
  assert.deepEqual(X.gtinLookupVariants('9556001000013'), ['9556001000013', '09556001000013']);
  assert.deepEqual(X.gtinLookupVariants('ABC-1'), ['ABC-1']);
  assert.deepEqual(X.gtinLookupVariants('  '), []);
  assert.deepEqual(X.gtinLookupVariants('04252614'), ['04252614', '042100005264', '0042100005264']);
  assert.deepEqual(X.gtinLookupVariants('042100005264'), ['042100005264', '0042100005264', '04252614']);
  assert.deepEqual(X.gtinLookupVariants('0042100005264'), ['0042100005264', '042100005264', '04252614']);
  assert.ok(X.gtinIsValid('9556001234568'));
  assert.ok(!X.gtinIsValid('9556001234569'));
});

test('amounts and quantities typed by hand (MoneyFormatTest)', () => {
  const myr = { symbol: 'RM', decimals: 2 };
  const ok = [['12.5', 1250n], ['RM 1,234.50', 123450n], ['rm12', 1200n], ['12.50 RM', 1250n], ['-3', -300n], ['RM-3', -300n],
    ['.5', 50n], ['12.', 1200n], ['0', 0n]];
  for (const [text, v] of ok) assert.equal(X.parseMoney(text, myr), v, text);
  for (const text of ['12.505', '1,23.00', '12,34', 'abc', '', '1.2.3', '99999999999999999999', '1e3']) assert.equal(X.parseMoney(text, myr), null, text);
  assert.equal(X.parseQty('0.253'), 253n);
  assert.equal(X.parseQty('1.5'), 1500n);
  assert.equal(X.parseQty('3'), 3000n);
  assert.equal(X.parseQty('1.2345'), null);
  assert.equal(X.parseQty('x'), null);
  assert.equal(X.normalizePhone(' +60 12-345 6789 '), '+60123456789');
  assert.equal(X.normalizePhone('012-345 6789'), '0123456789');
  assert.equal(X.normalizePhone('  '), null);
});

test('costs: line totals, unit costs and the moving average (InventoryMathTest)', () => {
  assert.equal(X.lineTotal(24_000n, 45n), 1_080n);
  assert.equal(X.lineTotal(253n, 1_290n), 326n);
  assert.equal(X.unitCost(1_080n, 24_000n), 45n);
  assert.equal(X.unitCost(1_000n, 3_000n), 333n);
  assert.throws(() => X.unitCost(100n, 0n));
  assert.equal(X.movingAverage(10_000n, 100n, 30_000n, 3_600n), 115n);
  assert.equal(X.movingAverage(0n, 100n, 30_000n, 3_600n), 120n);
  assert.equal(X.movingAverage(-5_000n, 100n, 30_000n, 3_600n), 120n);
  assert.equal(X.movingAverage(1_000n, 99n, 2_000n, 200n), 100n);
  assert.equal(X.movingAverage(500n, 1_600n, 1_500n, 1_350n), 1_075n);
  assert.equal(X.movingAverage(100_000n, 0n, 100_000n, 15_000n), 150n);
  assert.equal(X.movingAverage(100_000n, 100n, 100_000n, 0n), 50n);
  assert.equal(X.roundHalfUp(-5n, 2n), -3n);
  assert.equal(X.roundHalfUp(5n, 2n), 3n);
  assert.equal(X.roundHalfUp(4n, 3n), 1n);
});

test('stock reasons: the codes, their direction and how they are stored (AdjustReason)', () => {
  assert.deepEqual(X.reasonChoices(true).map((r) => r.code), ['damaged', 'expired', 'lost', 'theft', 'own_use', 'returned', 'correction', 'other']);
  assert.deepEqual(X.reasonChoices(false).map((r) => r.code), ['found', 'correction', 'other']);
  assert.equal(X.reasonDelta(X.reason('damaged'), 2000n, false), -2000n);
  assert.equal(X.reasonDelta(X.reason('found'), 2000n, true), 2000n);
  assert.equal(X.reasonDelta(X.reason('correction'), 2000n, true), -2000n);
  assert.equal(X.reasonDelta(X.reason('correction'), 2000n, false), 2000n);
  assert.equal(X.reasonEncode(X.reason('damaged'), ' box\ncrushed '), 'damaged: box crushed');
  assert.equal(X.reasonEncode(X.reason('lost'), '  '), 'lost');
  assert.equal(X.reason('expired').kind, X.MovementKind.WASTE);
  assert.equal(X.reason('returned').kind, X.MovementKind.RETURN_TO_SUPPLIER);
});

test('PINs: the till\'s salted PBKDF2 record checks the same PIN, and the lockout waits (StaffRulesTest)', async () => {
  const salt = randomBytes(16);
  const record = (pin, n = 50) => `p2:${n}:${salt.toString('hex')}:${pbkdf2Sync(pin, salt, n, 32, 'sha256').toString('hex')}`;
  const a = record('1234');
  assert.ok(await X.pinVerify('1234', a));
  assert.ok(await X.pinVerify('0007', record('0007', 4000)));
  assert.ok(!(await X.pinVerify('1235', a)));
  assert.ok(!(await X.pinVerify('', a)));
  assert.ok(!(await X.pinVerify('1234', null)));
  assert.ok(!(await X.pinVerify('1234', 'p2:50:zz:00')));
  assert.ok(!(await X.pinVerify('1234', 'p1:50:00:00')));
  assert.ok(!(await X.pinVerify('1234', 'p2:x:00:00')));
  assert.ok(!(await X.pinVerify('1234', a.slice(0, a.lastIndexOf(':')))));
  assert.ok(X.validPin('0000') && X.validPin('012345') && !X.validPin('123') && !X.validPin('1234567') && !X.validPin('12a4'));
  assert.equal(X.PinLockout.waitMs(4), 0);
  assert.equal(X.PinLockout.waitMs(5), 30_000);
  assert.equal(X.PinLockout.waitMs(6), 60_000);
  assert.equal(X.PinLockout.waitMs(20), 15 * 60_000);
  assert.equal(X.PinLockout.remainingMs(5, 1_000, 11_000), 20_000);
  assert.equal(X.PinLockout.remainingMs(5, 50_000, 1_000), 30_000); // the clock was set back
  assert.equal(X.PinLockout.triesLeft(3), 2);
});

test('permissions: the owner role has everything, a void includes the last sale (Perm)', () => {
  assert.equal(X.permEffective(1, 0n), -1n);
  assert.ok(X.permHas(X.permEffective(1, 0n), X.Perm.MANAGE_STAFF));
  assert.ok(X.permHas(X.permEffective(2, X.Perm.VOID), X.Perm.VOID_LAST));
  assert.ok(!X.permHas(X.permEffective(3, 49186n), X.Perm.MANAGE_PRODUCTS));
  assert.ok(X.permHas(X.permEffective(2, 129919n), X.Perm.MANAGE_PRODUCTS));
  assert.ok(X.permHas(X.permEffective(2, 129919n), X.Perm.MANAGE_STOCK));
});

test('the clock only goes forward and does not follow a clock far ahead (HlcTest)', () => {
  let wall = 1_000_000;
  const c = new X.Clock(0n, () => wall);
  const a = c.now();
  const b = c.now();
  assert.equal(a, X.hlcPack(1_000_000, 0));
  assert.equal(b, a + 1n);
  wall = 999_000; // set back
  assert.ok(c.now() > b);
  assert.ok(!c.observe(X.hlcPack(1_000_000 + 25 * 3_600_000, 0)));
  assert.ok(c.observe(X.hlcPack(1_000_000 + 3_600_000, 5)));
  assert.equal(c.now(), X.hlcPack(1_000_000 + 3_600_000, 6));
  assert.equal(X.stampAbove(10n, 5n), 10n);
  assert.equal(X.stampAbove(5n, 10n), 11n);
  assert.equal(X.idOf(3_000_001, 7), (3_000_001n << 41n) | 7n);
  assert.equal(X.deviceOf(X.idOf(3_000_001, 7)), 3_000_001);
});

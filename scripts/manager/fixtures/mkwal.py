# Makes a small SQLite database with a write-ahead log holding committed changes, for the WAL test
# (scripts/manager/backup.test.mjs). The log ends with frames of a transaction that never committed.
import sqlite3, shutil, os, sys
d = sys.argv[1]
for f in ('w.db', 'w.db-wal', 'w.db-shm'):
    p = os.path.join(d, f)
    if os.path.exists(p): os.remove(p)
db = sqlite3.connect(os.path.join(d, 'w.db'), isolation_level=None)
db.execute('PRAGMA page_size=1024')
db.execute('PRAGMA journal_mode=WAL')
db.execute('PRAGMA wal_autocheckpoint=0')
db.execute('CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)')
db.execute('PRAGMA wal_checkpoint(TRUNCATE)')  # the empty table is in the main file
n = 0
for batch in range(3):
    db.execute('BEGIN')
    for i in range(40):
        n += 1
        db.execute('INSERT INTO t(v) VALUES(?)', ('row %d %s' % (n, 'x' * 20),))
    db.execute('COMMIT')
db.execute('BEGIN')
for i in range(40):
    db.execute("INSERT INTO t(v) VALUES('uncommitted')")
db.execute('PRAGMA cache_spill=1')
db.execute('PRAGMA cache_size=1')  # spill the open transaction's pages into the log
for i in range(40):
    db.execute("INSERT INTO t(v) VALUES('uncommitted %s')" % ('y' * 200))
shutil.copy(os.path.join(d, 'w.db'), os.path.join(d, 'wal-main.db'))
shutil.copy(os.path.join(d, 'w.db-wal'), os.path.join(d, 'wal-log.db-wal'))
db.execute('ROLLBACK')
db.close()
for f in ('w.db', 'w.db-wal', 'w.db-shm'):
    p = os.path.join(d, f)
    if os.path.exists(p): os.remove(p)
print(n, os.path.getsize(os.path.join(d, 'wal-main.db')), os.path.getsize(os.path.join(d, 'wal-log.db-wal')))

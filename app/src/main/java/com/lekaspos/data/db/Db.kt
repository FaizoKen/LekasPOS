package com.lekaspos.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.os.Looper
import com.lekaspos.core.id.IdAllocator
import com.lekaspos.core.time.Hlc
import com.lekaspos.data.backup.BackupFiles
import com.lekaspos.data.backup.Restore
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The only entry point to SQLite (references/architecture.md §3):
 *  - every write runs on the single `db-writer` thread inside one transaction ([write]);
 *  - reads run on a small pool ([read]) concurrently with the writer thanks to WAL;
 *  - nothing here may be called on the main thread.
 */
class Db private constructor(
    val sqlite: SQLiteDatabase,
    private val helper: DbOpenHelper,
    val name: String,
    val deviceNo: Int,
    val storeUuid: String,
    val ids: IdAllocator,
    val hlc: Hlc,
) : Closeable {

    private val writerThread = AtomicReference<Thread>()
    private val writerExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "db-writer").also { writerThread.set(it) }
    }
    val writerDispatcher: CoroutineDispatcher = writerExecutor.asCoroutineDispatcher()

    @OptIn(ExperimentalCoroutinesApi::class)
    val readDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(3)

    /** Written only while sync is enabled (D-016). */
    @Volatile
    var syncEnabled: Boolean = Meta.get(sqlite, Meta.SYNC_ENABLED) == "1"

    /**
     * Called on the writer thread right after a transaction that queued sync events commits, so
     * the change can be sent soon (auto sync, D-053). Must return at once (post the work).
     */
    @Volatile
    var onOutboxCommit: (() -> Unit)? = null

    /** A sync event was queued in the open transaction (writer thread only). */
    private var outboxTouched = false

    private val statements = HashMap<String, SQLiteStatement>()
    private var persistedHlc: Long = hlc.current()

    val file: File get() = File(sqlite.path)

    suspend fun <T> read(block: (SQLiteDatabase) -> T): T = withContext(readDispatcher) { block(sqlite) }

    /** Runs [block] in one write transaction on the writer thread. */
    suspend fun <T> write(reserveIds: Long = DEFAULT_RESERVE, block: (Tx) -> T): T =
        withContext(writerDispatcher) { transaction(reserveIds, block) }

    /** Blocking read on the calling (background) thread. */
    fun <T> readBlocking(block: (SQLiteDatabase) -> T): T {
        assertNotMainThread()
        return block(sqlite)
    }

    /** Blocking write: hands the block to the writer thread and waits. For workers and tests. */
    fun <T> writeBlocking(reserveIds: Long = DEFAULT_RESERVE, block: (Tx) -> T): T {
        assertNotMainThread()
        if (Thread.currentThread() === writerThread.get()) return transaction(reserveIds, block)
        return try {
            writerExecutor.submit<T> { transaction(reserveIds, block) }.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Runs [block] on the writer thread OUTSIDE any transaction, so nothing else can write while
     * it runs (backups: checkpoint and copy the file, D-044). Keep it short: sales wait meanwhile.
     */
    fun <T> onWriterThread(block: (SQLiteDatabase) -> T): T {
        assertNotMainThread()
        if (Thread.currentThread() === writerThread.get()) {
            check(!sqlite.inTransaction()) { "not inside a transaction" }
            return block(sqlite)
        }
        return try {
            writerExecutor.submit<T> { block(sqlite) }.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun <T> transaction(reserveIds: Long, block: (Tx) -> T): T {
        if (sqlite.inTransaction()) return block(Tx(this)) // nested write: join the outer one
        // Reserve IDs *before* BEGIN: the reservation must be committed on its own (D-006).
        ids.ensure(reserveIds)
        outboxTouched = false
        sqlite.beginTransactionNonExclusive()
        var newHlc = persistedHlc
        val result = try {
            val r = block(Tx(this))
            newHlc = hlc.current()
            if (newHlc != persistedHlc) Meta.put(sqlite, Meta.HLC_LAST, newHlc.toString())
            sqlite.setTransactionSuccessful()
            r
        } finally {
            sqlite.endTransaction()
        }
        persistedHlc = newHlc // reached only when the commit succeeded
        if (outboxTouched) {
            outboxTouched = false
            try {
                onOutboxCommit?.invoke()
            } catch (e: Exception) {
                com.lekaspos.util.Log.w("Auto sync notice failed", e) // never fails the committed write
            }
        }
        return result
    }

    internal fun statement(sql: String): SQLiteStatement {
        check(Thread.currentThread() === writerThread.get()) { "cached statements belong to the writer thread" }
        return statements.getOrPut(sql) { sqlite.compileStatement(sql) }
    }

    override fun close() {
        writerExecutor.submit {
            for (s in statements.values) s.close()
            statements.clear()
        }.get()
        writerExecutor.shutdown()
        helper.close()
    }

    /** Write-transaction scope handed to DAOs. Only valid inside [write]/[writeBlocking]. */
    class Tx internal constructor(private val owner: Db) {
        val db: SQLiteDatabase get() = owner.sqlite
        val deviceNo: Int get() = owner.deviceNo
        val syncEnabled: Boolean get() = owner.syncEnabled

        fun nextId(): Long = owner.ids.nextId()
        fun hlcNow(): Long = owner.hlc.now()

        /** A sync event was queued in this transaction: [onOutboxCommit] runs after the commit. */
        fun outboxQueued() {
            owner.outboxTouched = true
        }

        /** A compiled statement cached for the life of the DB (hot paths). */
        fun stmt(sql: String): SQLiteStatement = owner.statement(sql)

        fun exec(sql: String, vararg bind: Any?) {
            val s = stmt(sql)
            s.bindAll(*bind)
            s.execute()
        }

        /** UPDATE/DELETE; returns the number of changed rows. */
        fun update(sql: String, vararg bind: Any?): Int {
            val s = stmt(sql)
            s.bindAll(*bind)
            return s.executeUpdateDelete()
        }

        /** INSERT; returns the rowid (-1 if ignored). */
        fun insert(sql: String, vararg bind: Any?): Long {
            val s = stmt(sql)
            s.bindAll(*bind)
            return s.executeInsert()
        }

        /** "UPDATE …; if nothing changed, INSERT …" — the API 21 replacement for UPSERT. */
        fun updateOrInsert(updateSql: String, updateArgs: Array<Any?>, insertSql: String, insertArgs: Array<Any?>) {
            if (update(updateSql, *updateArgs) == 0) insert(insertSql, *insertArgs)
        }
    }

    companion object {
        const val DEFAULT_RESERVE = 1000L

        /** Opens (creating/migrating if needed) the database [name]. Blocking; never on main. */
        fun open(context: Context, name: String = Schema.FILE_NAME, seedNames: SeedNames = SeedNames()): Db {
            assertNotMainThread()
            // A restore staged before the restart goes in first; otherwise an upgrade is backed up (D-044).
            val restored = Restore.applyIfStaged(context, name)
            if (restored == null) backupBeforeUpgrade(context, name)
            val helper = DbOpenHelper(context.applicationContext, name, seedNames)
            val sqlite = helper.writableDatabase
            if (restored != null) Restore.afterOpen(sqlite, restored)
            sqlite.setMaxSqlCacheSize(SQLiteDatabase.MAX_SQL_CACHE_SIZE)
            val deviceNo = Meta.getLong(sqlite, Meta.DEVICE_NO)?.toInt()
                ?: throw IllegalStateException("database has no device identity")
            val storeUuid = Meta.get(sqlite, Meta.STORE_UUID) ?: ""
            Seed.ensureOwner(sqlite, seedNames, System.currentTimeMillis())
            val hlc = Hlc(System::currentTimeMillis, Meta.getLong(sqlite, Meta.HLC_LAST) ?: 0L)
            val ids = IdAllocator(deviceNo, MetaReservations(sqlite))
            return Db(sqlite, helper, name, deviceNo, storeUuid, ids, hlc)
        }

        /** Keeps a backup of a database about to be migrated to a newer schema (kept: the last 3). */
        private fun backupBeforeUpgrade(context: Context, name: String) {
            val file = context.getDatabasePath(name)
            if (!file.exists()) return
            try {
                val version = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
                if (version <= 0 || version >= Schema.VERSION) return
                val dir = Restore.backupDir(context).apply { mkdirs() }
                java.io.FileOutputStream(File(dir, "upgrade-v$version-${System.currentTimeMillis()}${BackupFiles.EXT}")).use {
                    BackupFiles.writeClosed(file, it, com.lekaspos.BuildConfig.VERSION_NAME, "upgrade")
                }
                dir.listFiles { f -> f.name.startsWith("upgrade-") }?.sortedByDescending { it.name }?.drop(3)?.forEach { it.delete() }
            } catch (e: Exception) {
                com.lekaspos.util.Log.e("Backup before upgrade failed", e) // never block opening the store
            }
        }

        fun assertNotMainThread() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "database access on the main thread" }
        }
    }

    /** Persists ID reservations in `meta`, always in its own (autocommit) transaction. */
    private class MetaReservations(private val db: SQLiteDatabase) : IdAllocator.ReservationStore {
        override fun loadReserved(): Long = Meta.getLong(db, Meta.ID_RESERVED) ?: 0L

        override fun saveReserved(value: Long) {
            check(!db.inTransaction()) { "ID reservation must be committed outside other transactions" }
            Meta.put(db, Meta.ID_RESERVED, value.toString())
        }
    }
}

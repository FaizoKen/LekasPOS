package com.lekaspos.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.os.Looper
import com.lekaspos.core.id.IdAllocator
import com.lekaspos.core.time.Hlc
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

    private fun <T> transaction(reserveIds: Long, block: (Tx) -> T): T {
        if (sqlite.inTransaction()) return block(Tx(this)) // nested write: join the outer one
        // Reserve IDs *before* BEGIN: the reservation must be committed on its own (D-006).
        ids.ensure(reserveIds)
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
            val helper = DbOpenHelper(context.applicationContext, name, seedNames)
            val sqlite = helper.writableDatabase
            sqlite.setMaxSqlCacheSize(SQLiteDatabase.MAX_SQL_CACHE_SIZE)
            val deviceNo = Meta.getLong(sqlite, Meta.DEVICE_NO)?.toInt()
                ?: throw IllegalStateException("database has no device identity")
            val storeUuid = Meta.get(sqlite, Meta.STORE_UUID) ?: ""
            Seed.ensureOwner(sqlite, seedNames, System.currentTimeMillis())
            val hlc = Hlc(System::currentTimeMillis, Meta.getLong(sqlite, Meta.HLC_LAST) ?: 0L)
            val ids = IdAllocator(deviceNo, MetaReservations(sqlite))
            return Db(sqlite, helper, name, deviceNo, storeUuid, ids, hlc)
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

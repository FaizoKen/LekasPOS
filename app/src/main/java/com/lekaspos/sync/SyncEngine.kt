package com.lekaspos.sync

import android.app.Application
import com.lekaspos.BuildConfig
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.Entity
import com.lekaspos.core.sync.Cursors
import com.lekaspos.core.sync.SyncNames
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.Meta
import com.lekaspos.data.sale.ReceiptNumbers
import com.lekaspos.data.sync.Backfill
import com.lekaspos.data.sync.Importer
import com.lekaspos.data.sync.Outbox
import com.lekaspos.data.sync.SegmentCodec
import com.lekaspos.data.sync.SegmentRow
import com.lekaspos.data.sync.SyncDao
import com.lekaspos.data.sync.SyncEvent
import com.lekaspos.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Log shipping between the tills of one store (references/sync.md, D-045). Each till seals its
 * outbox into numbered, immutable segment files and uploads them; it downloads the other tills'
 * segments and applies each, in order, in one transaction together with its cursor — so an
 * interrupted sync simply resumes, and nothing is applied twice. Selling never waits on this.
 */
class SyncEngine(private val graph: AppGraph, private val app: Application) {

    data class Status(
        val enabled: Boolean = false,
        val running: Boolean = false,
        val lastSuccessAt: Long? = null,
        val lastError: String? = null,
        /** Events not yet uploaded. */
        val pending: Long = 0L,
        /** Other tills seen in the store. */
        val devices: Int = 0,
        val phase: String? = null,
        /** Records published so far while preparing (first sync). */
        val done: Long = 0L,
        /** Google (or another provider) needs the user to sign in again before syncing continues. */
        val needsSignIn: Boolean = false,
        val account: String? = null,
        val deviceName: String? = null,
    )

    data class Report(val sealed: Int, val uploaded: Int, val applied: Int, val events: Int, val devices: Int)

    /** Why sync cannot run; shown to the user. */
    class Problem(val reason: Reason, message: String) : Exception(message) {
        enum class Reason { NOT_ENABLED, DEVICE_CLASH, CORRUPT }
    }

    /** A till's card in the sync folder: who it is and how far it has read everyone else. */
    data class DeviceCard(
        val dev: Int,
        val uuid: String,
        val name: String,
        val app: String,
        val lastSeen: Long,
        val lastSeq: Long,
        val prefix: String?,
        val cursors: Map<Int, Long>,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val mutex = Mutex()
    private val outDir get() = File(app.filesDir, "sync/out").apply { mkdirs() }
    private val inDir get() = File(app.cacheDir, "sync-in").apply { mkdirs() }

    suspend fun refreshStatus() {
        val db = graph.db()
        val s = db.read { r ->
            _status.value.copy(
                enabled = db.syncEnabled, pending = SyncDao.unsentEvents(r), lastSuccessAt = Meta.getLong(r, LAST_OK),
                lastError = Meta.get(r, LAST_ERROR), account = Meta.get(r, ACCOUNT), deviceName = Meta.get(r, DEVICE_NAME),
            )
        }
        _status.value = s.copy(needsSignIn = s.enabled && s.lastError == ERROR_SIGN_IN)
    }

    /**
     * Makes this till part of the store kept in [provider] (creating it when there is none),
     * publishes the data it already has, then syncs. Safe to call again.
     */
    suspend fun enable(
        provider: SyncProvider,
        deviceName: String,
        account: String? = null,
        progress: (String, Long) -> Unit = { _, _ -> },
    ): Report = mutex.withLock {
        val db = graph.db()
        val (localStore, uuid) = db.read { Meta.get(it, Meta.STORE_UUID).orEmpty() to Meta.get(it, Meta.DEVICE_UUID).orEmpty() }
        val stores = provider.list(SyncNames.STORE_PREFIX).mapNotNull { SyncNames.parseStore(it.name) }.sorted()
        val store = when {
            localStore in stores -> localStore
            stores.isEmpty() -> {
                putJson(provider, SyncNames.store(localStore), replace = false) { w ->
                    w.beginObject()
                    w.name("store").value(localStore)
                    w.name("createdAt").value(System.currentTimeMillis())
                    w.name("createdBy").value(db.deviceNo.toLong())
                    w.endObject()
                }
                localStore
            }
            else -> stores.first() // one store per account in practice; deterministic otherwise
        }
        val cards = cards(provider, store)
        if (cards.any { it.dev == db.deviceNo && it.uuid != uuid }) {
            throw Problem(Problem.Reason.DEVICE_CLASH, "another till uses device number ${db.deviceNo}")
        }
        db.write(reserveIds = 0L) { tx ->
            if (store != localStore) Meta.put(tx.db, Meta.STORE_UUID, store)
            val taken = cards.filter { it.dev != tx.deviceNo }.mapNotNull { it.prefix }.toSet()
            if (ReceiptNumbers.prefix(tx) in taken) Meta.put(tx.db, Meta.RECEIPT_PREFIX, freePrefix(taken))
            Meta.put(tx.db, Meta.SYNC_ENABLED, "1")
            Meta.put(tx.db, DEVICE_NAME, deviceName)
            Meta.put(tx.db, PROVIDER, provider.id)
            Meta.put(tx.db, ACCOUNT, account)
        }
        db.syncEnabled = true // before the backfill: nothing written meanwhile can be missed
        syncLocked(provider, progress)
    }

    /** Stops syncing (the data stays). Enabling again publishes everything again. */
    suspend fun disable() = mutex.withLock {
        val db = graph.db()
        db.syncEnabled = false
        db.write(reserveIds = 0L) { tx ->
            Meta.put(tx.db, Meta.SYNC_ENABLED, "0")
            Meta.put(tx.db, BACKFILLED, null)
            Meta.put(tx.db, PROVIDER, null)
            Meta.put(tx.db, LAST_ERROR, null)
            tx.update("DELETE FROM outbox")
        }
        refreshStatus()
    }

    /** The provider this till was set up with, or null when sync is off. */
    suspend fun provider(): SyncProvider? {
        val db = graph.db()
        if (!db.syncEnabled) return null
        val (id, account) = db.read { Meta.get(it, PROVIDER) to Meta.get(it, ACCOUNT) }
        return SyncProviders.forId(app, id, account)
    }

    /** One round: seal, upload, import, publish the device card. */
    suspend fun sync(provider: SyncProvider): Report = mutex.withLock { syncLocked(provider) }

    private suspend fun syncLocked(provider: SyncProvider, progress: (String, Long) -> Unit = { _, _ -> }): Report {
        val db = graph.db()
        if (!db.syncEnabled) throw Problem(Problem.Reason.NOT_ENABLED, "sync is off")
        _status.value = _status.value.copy(running = true, phase = PHASE_SYNC)
        try {
            val store = db.read { Meta.get(it, Meta.STORE_UUID).orEmpty() }
            if (db.read { Meta.get(it, BACKFILLED) } != store) {
                // Publishes what this till already had; an interrupted run is simply repeated (imports are idempotent).
                _status.value = _status.value.copy(phase = PHASE_PREPARE, done = 0L)
                var before = 0L
                var table = ""
                Backfill.run(db) { t, n ->
                    if (t != table) {
                        before = _status.value.done
                        table = t
                    }
                    _status.value = _status.value.copy(done = before + n)
                    progress(t, n)
                }
                db.write(reserveIds = 0L) { tx -> Meta.put(tx.db, BACKFILLED, store) }
                _status.value = _status.value.copy(phase = PHASE_SYNC)
            }
            val sealed = seal(db, store)
            val uploaded = upload(db, provider, store)
            val changed = HashSet<Int>()
            val (applied, events, devices) = import(db, provider, store, changed)
            putCard(db, provider, store)
            cleanLocal(db)
            if (Entity.SETTING in changed) graph.settings.reload()
            if (Entity.ROLE in changed || Entity.STAFF in changed) graph.staff.reload()
            if (Entity.PROMOTION in changed) graph.promotions.load()
            val now = System.currentTimeMillis()
            db.write(reserveIds = 0L) { tx ->
                Meta.put(tx.db, LAST_OK, now.toString())
                Meta.put(tx.db, LAST_ERROR, null)
            }
            _status.value = _status.value.copy(running = false, phase = null, devices = devices, lastSuccessAt = now, lastError = null, needsSignIn = false)
            refreshStatus()
            return Report(sealed, uploaded, applied, events, devices)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _status.value = _status.value.copy(running = false, phase = null)
            throw e
        } catch (e: Exception) {
            Log.w("Sync failed", e)
            val msg = errorCode(e)
            runCatching { db.write(reserveIds = 0L) { tx -> Meta.put(tx.db, LAST_ERROR, msg) } }
            _status.value = _status.value.copy(running = false, phase = null, lastError = msg, needsSignIn = msg == ERROR_SIGN_IN)
            throw e
        }
    }

    // ------------------------------------------------------------------ steps

    private fun localSegment(seq: Long) = File(outDir, "seg-$seq${SyncNames.SEGMENT_EXT}")

    /**
     * Outbox → numbered segment files, [MAX_EVENTS] events each. The file is written and synced
     * outside the write transaction (sales keep committing); a short transaction then records it
     * and drops those outbox rows. Outbox seqs only grow, so the rows read are exactly those up
     * to the last one; a file left by a crash before the transaction is simply written again.
     */
    private suspend fun seal(db: Db, store: String): Int {
        var n = 0
        while (true) {
            val (rows, seq) = db.read { SyncDao.outboxBatch(it, MAX_EVENTS) to SyncDao.nextSegmentSeq(it) }
            if (rows.isEmpty()) return n
            val first = rows.minOf { it.hlc }
            val last = rows.maxOf { it.hlc }
            val (size, sha) = withContext(Dispatchers.IO) {
                SegmentCodec.write(localSegment(seq), SegmentCodec.Header(1, store, db.deviceNo, seq, rows.size, first, last), rows)
            }
            db.write(reserveIds = 0L) { tx ->
                SyncDao.insertSegment(tx, SegmentRow(seq, rows.size, first, last, size, sha, System.currentTimeMillis(), null))
                SyncDao.deleteOutboxUpTo(tx, rows.last().seq)
            }
            n++
        }
    }

    private suspend fun upload(db: Db, provider: SyncProvider, store: String): Int {
        var n = 0
        for (s in db.read { SyncDao.unsent(it) }) {
            val file = localSegment(s.seq)
            if (!file.exists()) throw Problem(Problem.Reason.CORRUPT, "segment ${s.seq} is missing on this phone")
            provider.put(SyncNames.segment(store, db.deviceNo, s.seq), file, mapOf("sha256" to s.sha256, "count" to s.count.toString()))
            db.write(reserveIds = 0L) { tx -> SyncDao.markUploaded(tx, s.seq, System.currentTimeMillis()) }
            n++
        }
        return n
    }

    /** Applies the other tills' new segments, each in order and in one transaction with its cursor. */
    private suspend fun import(db: Db, provider: SyncProvider, store: String, changed: MutableSet<Int>): Triple<Int, Int, Int> {
        applyDeferred(db, changed)
        val remote = provider.list(SyncNames.segmentPrefix(store))
            .mapNotNull { f -> SyncNames.parseSegment(f.name)?.takeIf { it.store == store && it.dev != db.deviceNo }?.let { it to f } }
        val byDev = remote.groupBy { it.first.dev }
        val cursors = db.read { SyncDao.cursors(it) }
        var applied = 0
        var events = 0
        for ((dev, files) in byDev) {
            val bySeq = files.associate { it.first.seq to it.second }
            for (seq in Cursors.next(cursors[dev] ?: 0L, bySeq.keys)) {
                val rf = bySeq.getValue(seq)
                val tmp = File(inDir, "seg-$dev-$seq.tmp")
                try {
                    provider.get(rf, tmp)
                    val expected = rf.props["sha256"]
                    if (expected != null && SegmentCodec.sha256(tmp) != expected) {
                        throw Problem(Problem.Reason.CORRUPT, "segment $dev/$seq does not match its checksum")
                    }
                    events += applySegment(db, tmp, store, dev, seq, changed)
                    applied++
                } finally {
                    tmp.delete()
                }
            }
        }
        return Triple(applied, events, byDev.keys.size)
    }

    /** Events kept by an older version of this app (D-047) that this version can apply now. */
    private suspend fun applyDeferred(db: Db, changed: MutableSet<Int>) {
        val waiting = db.read { SyncDao.deferred(it) }
        if (waiting.isEmpty()) return
        db.write(reserveIds = 0L) { tx ->
            val importer = Importer(tx.db)
            for ((seq, e) in waiting) {
                if (!importer.knows(e.entity)) continue
                if (importer.apply(tx, e)) changed.add(e.entity)
                SyncDao.dropDeferred(tx, seq)
            }
        }
    }

    /**
     * Applies one downloaded segment in short transactions (at most [IMPORT_CHUNK] events or about
     * [IMPORT_TX_MS] ms each), so a sale never waits for a whole segment; the cursor moves only
     * after the last one. A crash in between re-applies the segment next time, which changes
     * nothing (imports are idempotent).
     */
    private suspend fun applySegment(db: Db, file: File, store: String, dev: Int, seq: Long, changed: MutableSet<Int>): Int =
        withContext(Dispatchers.IO) {
            val batch = ArrayList<SyncEvent>(IMPORT_CHUNK)
            var max = 0L
            var count = 0
            fun flush() {
                var i = 0
                while (i < batch.size) {
                    db.writeBlocking(reserveIds = 0L) { tx ->
                        val importer = Importer(tx.db)
                        val start = System.nanoTime()
                        while (i < batch.size) {
                            val e = batch[i++]
                            if (!importer.knows(e.entity)) {
                                SyncDao.defer(tx, e) // from a newer version: applied after this till is updated
                            } else if (importer.apply(tx, e)) {
                                changed.add(e.entity)
                            }
                            if (System.nanoTime() - start >= IMPORT_TX_MS * 1_000_000L) break
                        }
                    }
                }
                batch.clear()
                if (!db.hlc.observe(max)) Log.w("Till $dev has a clock far in the future")
            }
            SegmentCodec.read(file, { h ->
                if (h.store != store || h.dev != dev || h.seq != seq) throw Problem(Problem.Reason.CORRUPT, "segment $dev/$seq has a wrong header")
            }) { e ->
                batch.add(e)
                if (e.hlc > max) max = e.hlc
                count++
                if (batch.size >= IMPORT_CHUNK) flush()
            }
            flush()
            db.writeBlocking(reserveIds = 0L) { tx -> SyncDao.setCursor(tx, dev, seq, max, System.currentTimeMillis()) }
            count
        }

    private suspend fun putCard(db: Db, provider: SyncProvider, store: String) {
        val (uuid, name, prefix) = db.read { r -> Triple(Meta.get(r, Meta.DEVICE_UUID).orEmpty(), Meta.get(r, DEVICE_NAME).orEmpty(), Meta.get(r, Meta.RECEIPT_PREFIX)) }
        val lastSeq = db.read { SyncDao.lastUploaded(it) }
        val cursors = db.read { SyncDao.cursors(it) }
        putJson(provider, SyncNames.device(store, db.deviceNo), replace = true) { w ->
            w.beginObject()
            w.name("dev").value(db.deviceNo.toLong())
            w.name("uuid").value(uuid)
            w.name("name").value(name)
            w.name("app").value(BuildConfig.VERSION_NAME)
            w.name("lastSeen").value(System.currentTimeMillis())
            w.name("lastSeq").value(lastSeq)
            w.name("prefix").value(prefix)
            w.name("cursors").beginObject()
            for ((d, s) in cursors) w.name(d.toString()).value(s)
            w.endObject()
            w.endObject()
        }
    }

    /** Uploaded segments are kept on the phone for [KEEP_LOCAL_MS], then deleted. */
    private suspend fun cleanLocal(db: Db) {
        val cutoff = System.currentTimeMillis() - KEEP_LOCAL_MS
        for (s in db.read { SyncDao.segments(it) }) {
            val at = s.uploadedAt ?: continue
            if (at < cutoff) localSegment(s.seq).delete()
        }
    }

    /** Every till's card in [store]. */
    suspend fun cards(provider: SyncProvider, store: String): List<DeviceCard> {
        val out = ArrayList<DeviceCard>()
        for (f in provider.list(SyncNames.devicePrefix(store))) {
            val tmp = File(inDir, "card.tmp")
            try {
                provider.get(f, tmp)
                parseCard(tmp.readText())?.let { out.add(it) }
            } catch (e: Exception) {
                Log.w("Unreadable device card ${f.name}", e)
            } finally {
                tmp.delete()
            }
        }
        return out
    }

    private suspend fun putJson(provider: SyncProvider, name: String, replace: Boolean, build: (android.util.JsonWriter) -> Unit) {
        val tmp = File(inDir, "put-${System.nanoTime()}.json")
        try {
            tmp.writeText(Outbox.json(build))
            provider.put(name, tmp, replace = replace)
        } finally {
            tmp.delete()
        }
    }

    companion object {
        const val MAX_EVENTS = 2_000
        const val IMPORT_CHUNK = 200
        const val IMPORT_TX_MS = 100L
        const val KEEP_LOCAL_MS = 14L * 24L * 60L * 60L * 1000L
        const val DEVICE_NAME = "sync.device_name"
        const val PROVIDER = "sync.provider"
        const val BACKFILLED = Meta.SYNC_BACKFILLED
        const val LAST_OK = Meta.SYNC_LAST_OK
        const val LAST_ERROR = Meta.SYNC_LAST_ERROR
        const val ACCOUNT = "sync.account"

        /** Stored as the last error when the provider needs the user to sign in. */
        const val ERROR_SIGN_IN = "sign-in"
        const val ERROR_OFFLINE = "offline"
        const val ERROR_CORRUPT = "corrupt"

        /** Known failures are stored as codes the screens translate; anything else as its message. */
        fun errorCode(e: Exception): String = when (e) {
            is AuthNeeded -> ERROR_SIGN_IN
            is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException,
            is java.net.SocketTimeoutException -> ERROR_OFFLINE
            is Problem -> if (e.reason == Problem.Reason.CORRUPT) ERROR_CORRUPT else e.message ?: e.reason.name
            else -> e.message ?: e.javaClass.simpleName
        }
        const val PHASE_PREPARE = "prepare"
        const val PHASE_SYNC = "sync"

        @Suppress("UNCHECKED_CAST")
        fun parseCard(json: String): DeviceCard? {
            val m = SegmentCodec.parse(json) as? Map<String, Any?> ?: return null
            val dev = (m["dev"] as? Long)?.toInt() ?: return null
            val cursors = (m["cursors"] as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) -> k.toIntOrNull()?.let { d -> (v as? Long)?.let { d to it } } }.toMap()
            return DeviceCard(
                dev, m["uuid"] as? String ?: "", m["name"] as? String ?: "", m["app"] as? String ?: "",
                m["lastSeen"] as? Long ?: 0L, m["lastSeq"] as? Long ?: 0L, m["prefix"] as? String, cursors,
            )
        }

        /** A two-letter receipt prefix no other till uses. */
        fun freePrefix(taken: Set<String>): String {
            for (a in 'A'..'Z') for (b in 'A'..'Z') {
                val p = "$a$b-"
                if (p !in taken) return p
            }
            return "ZZ-"
        }
    }
}

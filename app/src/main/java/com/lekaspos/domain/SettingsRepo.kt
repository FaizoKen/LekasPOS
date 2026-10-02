package com.lekaspos.domain

import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.AuditAction
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.db.Meta
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.data.settings.DeviceSettings
import com.lekaspos.data.settings.SettingsDao
import com.lekaspos.data.settings.StoreSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Store settings (synced, LWW per key) and this device's hardware settings, loaded once and
 * then served from memory as StateFlows. Saving writes the DB first, then publishes.
 */
class SettingsRepo(private val graph: AppGraph, private val defaultLanguage: String) {

    private val _store = MutableStateFlow(StoreSettings(receiptLanguage = defaultLanguage))
    val store: StateFlow<StoreSettings> = _store

    private val _device = MutableStateFlow(DeviceSettings())
    val device: StateFlow<DeviceSettings> = _device

    private val loadLock = Mutex()

    @Volatile
    var loaded: Boolean = false
        private set

    suspend fun load() = loadLock.withLock {
        if (loaded) return@withLock
        val db = graph.db()
        val (store, device) = db.read { r -> SettingsDao.all(r) to DeviceSettings.load(r) }
        _store.value = StoreSettings.from(store, defaultLanguage)
        _device.value = device
        loaded = true
    }

    /** Re-reads store settings after another till changed them (sync import). */
    suspend fun reload() {
        val db = graph.db()
        val store = db.read { r -> SettingsDao.all(r) }
        _store.value = StoreSettings.from(store, defaultLanguage)
    }

    /**
     * Saves the settings the user changed from [before] (what their screen showed) to [after]; the
     * audit log says who changed which (prices include tax, rounding …). Only those keys are
     * written: comparing with the database wrote every key a fresh install had never stored (its
     * defaults, with new versions, which won over the store's real header, BRN and tax switch on
     * every till once this till joined), and every key another till changed while the screen was
     * open (2026-10 review).
     */
    suspend fun saveStore(before: StoreSettings, after: StoreSettings) {
        val db = graph.db()
        val staffId = graph.staff.staffId
        val old = before.toMap()
        val wanted = after.toMap().filter { (k, v) -> old[k] != v }
        if (wanted.isNotEmpty()) {
            db.write(reserveIds = 1) { tx ->
                val now = System.currentTimeMillis()
                val current = SettingsDao.all(tx.db)
                val changed = wanted.keys.filter { current[it] != wanted[it] }.sorted()
                SettingsDao.putChanged(tx, current, wanted, now)
                if (changed.isNotEmpty()) AuditDao.log(tx, AuditAction.SETTINGS_CHANGE, staffId, now, detail = changed.joinToString(", ").take(MAX_DETAIL))
            }
        }
        // Keys this screen did not change keep what the database has now (another till's edit).
        reload()
    }

    /** Saves the changes from the settings in memory now to [after] (one-switch changes, tests). */
    suspend fun saveStore(after: StoreSettings) = saveStore(_store.value, after)

    /** Records a settings change made outside [saveStore] ([what]: e.g. the receipt logo, a tax rate). */
    suspend fun recordChange(what: String, approvedBy: Long? = null) {
        val staffId = graph.staff.staffId
        graph.db().write(reserveIds = 1) { tx ->
            AuditDao.log(tx, AuditAction.SETTINGS_CHANGE, staffId, System.currentTimeMillis(), detail = what.take(MAX_DETAIL), approvedBy = approvedBy)
        }
    }

    /**
     * Saves this till's own settings. What is in use follows the commit even when the screen that
     * saved is closing (a new printer was stored but not used until the app restarted, 2026-10
     * review); [then] runs in the same uninterruptible step (e.g. reconnecting the printer).
     */
    suspend fun saveDevice(d: DeviceSettings, then: () -> Unit = {}) = withContext(kotlinx.coroutines.NonCancellable) {
        val db = graph.db()
        db.write(reserveIds = 0) { tx -> DeviceSettings.save(tx, d) }
        _device.value = d
        then()
    }

    /**
     * First-run setup (Phase 8) is shown once, on a fresh install: no shop name, products or sales
     * yet. Upgraded or restored databases, and tills that joined a store through sync, skip it.
     */
    suspend fun needsSetup(): Boolean {
        load()
        if (_store.value.name.isNotBlank()) return false
        // The shop's data was damaged and set aside: the empty store is not a new shop. The selling
        // screen's "Data problem" leads to restoring a backup, not the welcome screen (2026-10 review).
        if (com.lekaspos.data.db.KeepDamagedDatabase.problem != null) return false
        return graph.db().read { r ->
            Meta.get(r, SETUP_DONE) != "1" && !ProductDao.any(r) && !SaleDao.any(r)
        }
    }

    suspend fun markSetupDone() {
        graph.db().write(reserveIds = 0) { tx -> Meta.put(tx.db, SETUP_DONE, "1") }
    }

    private companion object {
        const val SETUP_DONE = "dev.setup_done"
        const val MAX_DETAIL = 300
    }
}

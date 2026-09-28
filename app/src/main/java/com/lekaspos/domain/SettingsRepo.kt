package com.lekaspos.domain

import com.lekaspos.app.AppGraph
import com.lekaspos.data.settings.DeviceSettings
import com.lekaspos.data.settings.SettingsDao
import com.lekaspos.data.settings.StoreSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    suspend fun saveStore(s: StoreSettings) {
        val db = graph.db()
        db.write(reserveIds = 0) { tx -> SettingsDao.putChanged(tx, SettingsDao.all(tx.db), s.toMap(), System.currentTimeMillis()) }
        _store.value = s
    }

    suspend fun saveDevice(d: DeviceSettings) {
        val db = graph.db()
        db.write(reserveIds = 0) { tx -> DeviceSettings.save(tx, d) }
        _device.value = d
    }
}

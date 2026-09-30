package com.lekaspos.data.db

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.id.Ids
import java.security.SecureRandom
import java.util.UUID

/** Device-local key/value store (`meta` table, LOCAL sync class). */
object Meta {
    const val DEVICE_UUID = "device_uuid"
    const val DEVICE_NO = "device_no"
    const val STORE_UUID = "store_uuid"
    const val CREATED_AT = "created_at"
    const val ID_RESERVED = "id_reserved"
    const val HLC_LAST = "hlc_last"
    const val SYNC_ENABLED = "sync_enabled"
    const val RECEIPT_PREFIX = "receipt_prefix"
    const val SYNC_BACKFILLED = "sync.backfilled"
    const val SYNC_LAST_OK = "sync.last_ok"
    const val SYNC_LAST_ERROR = "sync.last_error"

    // Data safety on this phone (D-048), LOCAL.
    const val BACKUP_FOLDER = "dev.backup_folder"
    const val BACKUP_FOLDER_NAME = "dev.backup_folder_name"
    const val BACKUP_FOLDER_OK = "dev.backup_folder_ok"
    const val BACKUP_FOLDER_ERROR = "dev.backup_folder_error"
    const val BACKUP_EXPORT_OK = "dev.backup_export_ok"
    const val DB_PROBLEM = "dev.db_problem"

    fun docSeqKey(kind: Int) = "doc_seq_$kind"

    fun get(db: SQLiteDatabase, key: String): String? =
        db.stringOrNull("SELECT value FROM meta WHERE key = ?", key)

    fun getLong(db: SQLiteDatabase, key: String): Long? = get(db, key)?.toLongOrNull()

    fun put(db: SQLiteDatabase, key: String, value: String?) {
        db.execSQL("INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)", arrayOf<Any?>(key, value))
    }

    /** Increments a counter and returns the new value; call inside the caller's transaction. */
    fun increment(db: SQLiteDatabase, key: String): Long {
        val next = (getLong(db, key) ?: 0L) + 1L
        put(db, key, next.toString())
        return next
    }

    /** First-launch identity: random device number, device and store UUIDs. */
    fun createIdentity(db: SQLiteDatabase, now: Long, random: SecureRandom = SecureRandom()) {
        put(db, DEVICE_UUID, UUID.randomUUID().toString())
        put(db, DEVICE_NO, Ids.randomDeviceNo(random).toString())
        put(db, STORE_UUID, UUID.randomUUID().toString())
        put(db, CREATED_AT, now.toString())
        put(db, ID_RESERVED, "0")
        put(db, HLC_LAST, "0")
        put(db, SYNC_ENABLED, "0")
    }
}

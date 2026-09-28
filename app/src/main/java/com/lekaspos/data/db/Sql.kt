package com.lekaspos.data.db

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDoneException
import android.database.sqlite.SQLiteStatement

/*
 * Small helpers so DAO code stays short and every cursor is closed.
 * rawQuery only binds strings on API 21; SQLite converts them for INTEGER-affinity columns.
 */

fun args(vararg values: Any?): Array<String?> = Array(values.size) { values[it]?.toString() }

inline fun <T> SQLiteDatabase.queryList(sql: String, args: Array<String?>? = null, map: (Cursor) -> T): List<T> {
    rawQuery(sql, args).use { c ->
        val out = ArrayList<T>(c.count.coerceAtLeast(0))
        while (c.moveToNext()) out.add(map(c))
        return out
    }
}

inline fun <T> SQLiteDatabase.queryOne(sql: String, args: Array<String?>? = null, map: (Cursor) -> T): T? {
    rawQuery(sql, args).use { c -> return if (c.moveToFirst()) map(c) else null }
}

/** Single Long (first column of first row) without a CursorWindow; null if no row or NULL. */
fun SQLiteDatabase.longOrNull(sql: String, vararg bind: Any?): Long? {
    val st = compileStatement(sql)
    try {
        st.bindAll(*bind)
        return try {
            st.simpleQueryForLong()
        } catch (e: SQLiteDoneException) {
            null
        }
    } finally {
        st.close()
    }
}

fun SQLiteDatabase.long(sql: String, vararg bind: Any?): Long = longOrNull(sql, *bind) ?: 0L

fun SQLiteDatabase.stringOrNull(sql: String, vararg bind: Any?): String? {
    val st = compileStatement(sql)
    try {
        st.bindAll(*bind)
        return try {
            st.simpleQueryForString()
        } catch (e: SQLiteDoneException) {
            null
        }
    } finally {
        st.close()
    }
}

/** Runs a PRAGMA (or any statement that may return a row) and returns the first value as text. */
fun SQLiteDatabase.pragma(sql: String): String? = rawQuery(sql, null).use { c ->
    if (c.moveToFirst() && c.columnCount > 0 && !c.isNull(0)) c.getString(0) else null
}

/** Binds positional arguments (1-based) by Kotlin type. */
fun SQLiteStatement.bindAll(vararg values: Any?) {
    clearBindings()
    for (i in values.indices) {
        val index = i + 1
        when (val v = values[i]) {
            null -> bindNull(index)
            is Long -> bindLong(index, v)
            is Int -> bindLong(index, v.toLong())
            is Boolean -> bindLong(index, if (v) 1L else 0L)
            is String -> bindString(index, v)
            is ByteArray -> bindBlob(index, v)
            is Double -> bindDouble(index, v)
            else -> throw IllegalArgumentException("unsupported bind type ${v.javaClass}")
        }
    }
}

fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

fun Cursor.bool(index: Int): Boolean = getLong(index) != 0L

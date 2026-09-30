package com.lekaspos.testing

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.app.AppGraph
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** A real AppGraph (cart, checkout, refunds…) over a throw-away database. */
object TestGraph {

    fun create(name: String = "test-${UUID.randomUUID()}.db"): AppGraph {
        SQLiteDatabase.deleteDatabase(TestDb.context.getDatabasePath(name))
        return reopen(name)
    }

    /**
     * Another graph over the same file, like the app after a process restart. The signed-in staff
     * member is loaded as every screen does first: until then nothing is allowed (see [unloaded]).
     */
    fun reopen(name: String): AppGraph = unloaded(name).also { g -> runBlocking { g.staff.load() } }

    /** A graph as Android leaves it right after restoring a screen into a new process: nothing loaded yet. */
    fun unloaded(name: String): AppGraph = AppGraph(TestDb.context.applicationContext as Application, name)

    /** Closes the graph's database without deleting it. */
    fun close(graph: AppGraph) {
        val db = runBlocking { graph.db() }
        graph.appScope.cancel()
        db.close()
    }

    fun destroy(graph: AppGraph) {
        val db = runBlocking { graph.db() }
        graph.appScope.cancel()
        TestDb.delete(db)
    }
}

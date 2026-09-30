package com.lekaspos.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** The welcome screen appears once, and only on a fresh install (Phase 8). */
@RunWith(AndroidJUnit4::class)
class FirstRunSetupTest {

    private fun <T> withGraph(block: suspend (AppGraph) -> T): T {
        val g = TestGraph.create()
        try {
            return runBlocking { block(g) }
        } finally {
            TestGraph.destroy(g)
        }
    }

    @Test
    fun aFreshInstallNeedsSetupOnce() = withGraph { g ->
        assertTrue(g.settings.needsSetup())
        g.settings.markSetupDone()
        assertFalse(g.settings.needsSetup())
    }

    @Test
    fun existingDataMeansNoSetup() {
        withGraph { g ->
            TestDb.product(g.db(), "Milo", 1_890L)
            assertFalse(g.settings.needsSetup()) // e.g. an upgrade from an older version
        }
        withGraph { g ->
            g.settings.load()
            g.settings.saveStore(g.settings.store.value.copy(name = "Kedai Ali")) // e.g. joined through sync
            assertFalse(g.settings.needsSetup())
        }
    }
}

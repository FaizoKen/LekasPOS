package com.lekaspos.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 2026-10 review: failures of release builds left no trace a shop could send. */
@RunWith(AndroidJUnit4::class)
class ErrorLogTest {

    @Test
    fun aLoggedFailureIsKeptInTheErrorLog() {
        val marker = "error-log-test-${System.nanoTime()}"
        Log.e(marker, IllegalStateException("boom"))
        val deadline = System.currentTimeMillis() + 5_000L
        var found = false
        while (!found && System.currentTimeMillis() < deadline) {
            found = ErrorLog.files().any { f -> f.readText().let { it.contains(marker) && it.contains("IllegalStateException: boom") } }
            if (!found) Thread.sleep(50L)
        }
        assertTrue(found, "the entry reached files/logs")
    }
}

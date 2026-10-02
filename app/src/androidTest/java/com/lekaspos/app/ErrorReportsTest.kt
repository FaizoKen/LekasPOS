package com.lekaspos.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lekaspos.BuildConfig
import com.lekaspos.core.diag.ErrorReport
import com.lekaspos.util.Log
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Error reports to the developer (D-057): kept on the phone, cleaned, counted; the relay reachable. */
@RunWith(AndroidJUnit4::class)
class ErrorReportsTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Nothing waiting from earlier tests (their logged errors are reports too); never sent from here. */
    @Before
    fun noReportsWaiting() = ErrorReports.setConsent(ctx, false)

    /** Letters only: numbers in a message are taken out of its title. */
    private fun marker() = "reports test " + (1..10).map { ('a'..'z').random() }.joinToString("")

    private fun waiting(marker: String) = ErrorReports.waiting(ctx).filter { it.title.startsWith(marker) }

    private fun waitFor(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!check()) {
            assertTrue(System.currentTimeMillis() < deadline, what)
            Thread.sleep(50L)
        }
    }

    @Test
    fun aLoggedErrorWaitsAsAReportWithoutPersonalDetails() {
        val m = marker()
        Log.e("$m for siti@example.com", IllegalStateException("bad \"Kopi O\" for 0123456789"))
        waitFor("the report was kept") { waiting(m).isNotEmpty() }
        val r = waiting(m).single()
        assertEquals(ErrorReport.ERROR, r.kind)
        assertEquals("$m for <email>: IllegalStateException", r.title)
        assertEquals(16, r.fingerprint.length)
        assertTrue(r.trace.contains("java.lang.IllegalStateException"))
        assertTrue(r.trace.contains("com.lekaspos.app.ErrorReportsTest"))
        for (text in listOf(r.title, r.message, r.trace, r.log)) {
            assertFalse(text.contains("siti@"), text)
            assertFalse(text.contains("Kopi O"), text)
            assertFalse(text.contains("0123456789"), text)
        }
        assertEquals(BuildConfig.VERSION_NAME, r.app)
        assertEquals(BuildConfig.VERSION_CODE, r.build)
        assertTrue(r.device.isNotBlank())
    }

    @Test
    fun theSameErrorAgainIsCountedInOneReport() {
        val m = marker()
        Log.e("$m at line 1")
        Log.e("$m at line 2")
        waitFor("both were counted") { waiting(m).any { it.count == 2 } }
        assertEquals(1, waiting(m).size)
    }

    @Test
    fun reportsTurnedOffAreDeleted() {
        val m = marker()
        Log.e(m)
        waitFor("the report was kept") { waiting(m).isNotEmpty() }
        ErrorReports.setConsent(ctx, false)
        assertTrue(waiting(m).isEmpty())
        assertEquals(ErrorReports.OFF, ErrorReports.consent(ctx))
    }

    /**
     * The relay is reached over HTTPS from this Android version (Android 5's TLS and certificates
     * included); it answers 204 to a test report once deployed, which files nothing.
     */
    @Test
    fun theRelayCanBeReachedFromThisAndroid() {
        val code = ErrorReports.relayStatus(ctx)
        android.util.Log.i(Log.TAG, "Error-report relay answered $code")
        // No name yet (the Cloudflare subdomain is set up once) or no network: nothing to learn here.
        assumeTrue("${ErrorReports.URL} does not resolve", code != ErrorReports.NO_HOST)
        assertTrue(code > 0, "no HTTPS connection to ${ErrorReports.URL}")
    }
}

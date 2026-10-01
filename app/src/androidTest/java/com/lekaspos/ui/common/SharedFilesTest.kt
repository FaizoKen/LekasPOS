package com.lekaspos.ui.common

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Files shared to other apps (2026-10 review): sharing one never deletes another that the receiving
 * app may still be reading; only files older than a quarter of an hour go.
 */
@RunWith(AndroidJUnit4::class)
class SharedFilesTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun file(path: String, ageMs: Long): File {
        val f = File(File(ctx.cacheDir, "shared"), path)
        f.parentFile?.mkdirs()
        f.writeText("x")
        assertTrue(f.setLastModified(System.currentTimeMillis() - ageMs))
        return f
    }

    @Test
    fun keepsRecentFilesOfEveryKind() {
        val csv = file("csv/products.csv", 60_000L)
        val oldReceipt = file("receipt/receipt-A.png", 60L * 60_000L)
        val loose = file("receipt-old-version.png", 60L * 60_000L)
        val f = CsvFiles.sharedFile(ctx, CsvFiles.KIND_RECEIPT, "receipt-B.pdf")
        assertEquals(File(File(ctx.cacheDir, "shared"), "receipt/receipt-B.pdf"), f)
        assertFalse(f.exists())
        assertTrue(csv.exists(), "a CSV shared a minute ago was deleted")
        assertFalse(oldReceipt.exists())
        assertFalse(loose.exists())
        csv.delete()
    }

    @Test
    fun replacesTheFileItself() {
        val same = file("csv/sales.csv", 1_000L)
        val f = CsvFiles.shareFile(ctx, "sales.csv")
        assertEquals(same, f)
        assertFalse(f.exists())
    }
}

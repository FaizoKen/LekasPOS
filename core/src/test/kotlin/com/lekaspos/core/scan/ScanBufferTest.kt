package com.lekaspos.core.scan

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ScanBufferTest {

    private fun feed(b: ScanBuffer, text: String, start: Long, gapMs: Long): Long {
        var t = start
        for (c in text) {
            b.onChar(c, t)
            t += gapMs
        }
        return t - gapMs
    }

    @Test
    fun fastBurstWithEnterIsAScan() {
        val b = ScanBuffer()
        feed(b, "9556001234567", 1000L, 8L)
        assertEquals(ScanBuffer.Result.Scan("9556001234567"), b.onTerminator())
        assertTrue(b.isEmpty)
        assertNull(b.onTerminator())
    }

    @Test
    fun slowTypingIsText() {
        val b = ScanBuffer()
        feed(b, "milo", 1000L, 180L)
        assertEquals(ScanBuffer.Result.Typed("milo", submit = true), b.onTerminator())
        feed(b, "m", 5000L, 0L)
        assertEquals(ScanBuffer.Result.Typed("m", submit = true), b.onTerminator())
    }

    @Test
    fun scannerWithoutTerminatorIsCaughtWhenIdle() {
        val b = ScanBuffer()
        val last = feed(b, "12345678", 1000L, 15L)
        assertFalse(b.isIdle(last + 100L))
        assertTrue(b.isIdle(last + ScanBuffer.IDLE_MS))
        assertEquals(ScanBuffer.Result.Scan("12345678"), b.onIdle())
    }

    @Test
    fun shortFastInputWithoutTerminatorIsText() {
        val b = ScanBuffer()
        feed(b, "abc", 1000L, 10L)
        assertEquals(ScanBuffer.Result.Typed("abc", submit = false), b.onIdle())
        assertNull(b.onIdle())
    }

    @Test
    fun runawayInputStartsOver() {
        val b = ScanBuffer()
        feed(b, "x".repeat(ScanBuffer.MAX_LENGTH), 0L, 1L)
        b.onChar('y', 500L)
        assertEquals(ScanBuffer.Result.Typed("y", submit = true), b.onTerminator())
    }
}

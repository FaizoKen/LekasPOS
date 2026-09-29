package com.lekaspos.core.staff

import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.SysRole
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class StaffRulesTest {

    @Test
    fun pbkdf2MatchesTheJdk() {
        val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        for ((pin, iterations, length) in listOf(Triple("1234", 1, 32), Triple("000000", 2, 32), Triple("98765", 1000, 32), Triple("4321", 3, 70))) {
            val salt = ByteArray(16) { (it * 7 + iterations).toByte() }
            val expected = jdk.generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, length * 8)).encoded
            val actual = PinHash.pbkdf2(pin.toByteArray(), salt, iterations, length)
            assertTrue(expected.contentEquals(actual), "PBKDF2 $pin/$iterations/$length")
        }
    }

    @Test
    fun pinsAreSaltedAndVerified() {
        val random = SecureRandom()
        val a = PinHash.create("1234", random, iterations = 50)
        val b = PinHash.create("1234", random, iterations = 50)
        assertNotEquals(a, b) // different salts
        assertTrue(a.startsWith("p2:50:"))
        assertTrue(PinHash.verify("1234", a))
        assertTrue(PinHash.verify("1234", b))
        assertFalse(PinHash.verify("1235", a))
        assertFalse(PinHash.verify("", a))
        assertFalse(PinHash.verify("1234", null))
        assertFalse(PinHash.verify("1234", "p2:50:zz:00"))
        assertFalse(PinHash.verify("1234", "p1:50:00:00"))
        assertFalse(PinHash.verify("1234", "p2:x:00:00"))
        assertFalse(PinHash.verify("1234", a.substringBeforeLast(':')))
        // The default cost is recorded in the hash, so it can be raised later.
        assertTrue(PinHash.create("0000").startsWith("p2:${PinHash.ITERATIONS}:"))
    }

    @Test
    fun pinFormat() {
        assertTrue(PinHash.validPin("0000"))
        assertTrue(PinHash.validPin("012345"))
        assertFalse(PinHash.validPin("123"))
        assertFalse(PinHash.validPin("1234567"))
        assertFalse(PinHash.validPin("12a4"))
        assertFalse(PinHash.validPin("12 34"))
    }

    @Test
    fun lockoutDoublesAfterFiveWrongPins() {
        assertEquals(0L, PinLockout.waitMs(0))
        assertEquals(0L, PinLockout.waitMs(4))
        assertEquals(30_000L, PinLockout.waitMs(5))
        assertEquals(60_000L, PinLockout.waitMs(6))
        assertEquals(120_000L, PinLockout.waitMs(7))
        assertEquals(480_000L, PinLockout.waitMs(9))
        assertEquals(PinLockout.MAX_WAIT_MS, PinLockout.waitMs(10))
        assertEquals(PinLockout.MAX_WAIT_MS, PinLockout.waitMs(500))
        assertEquals(3, PinLockout.triesLeft(2))
        assertEquals(0, PinLockout.triesLeft(7))

        val t = 1_000_000L
        assertEquals(0L, PinLockout.remainingMs(4, t, t))
        assertEquals(30_000L, PinLockout.remainingMs(5, t, t))
        assertEquals(10_000L, PinLockout.remainingMs(5, t, t + 20_000L))
        assertEquals(0L, PinLockout.remainingMs(5, t, t + 31_000L))
        // Clock set back before the last failure: the full wait counts from now.
        assertEquals(30_000L, PinLockout.remainingMs(5, t, t - 3_600_000L))
    }

    @Test
    fun recoveryCodes() {
        val code = RecoveryCode.generate()
        assertEquals(14, code.length)
        assertTrue(Regex("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}").matches(code))
        assertFalse(code.any { it in "01OIL" })
        assertEquals("ABCDEFGHJKMN", RecoveryCode.normalize(" abcd efgh-jkmn "))
        assertEquals(RecoveryCode.normalize(code), RecoveryCode.normalize(code.lowercase().replace("-", " ")))
        val stored = PinHash.create(RecoveryCode.normalize(code), iterations = 20)
        assertTrue(PinHash.verify(RecoveryCode.normalize(code.lowercase()), stored))
    }

    @Test
    fun rolePermissions() {
        assertEquals(Perm.ALL, Perm.effective(SysRole.OWNER, 0L))
        assertEquals(Perm.DEFAULT_CASHIER, Perm.effective(SysRole.CASHIER, Perm.DEFAULT_CASHIER))
        assertEquals(5L, Perm.effective(SysRole.NONE, 5L))
        assertTrue(Perm.has(Perm.DEFAULT_MANAGER, Perm.VOID or Perm.REFUND))
        assertFalse(Perm.has(Perm.DEFAULT_MANAGER, Perm.SETTINGS))
        assertFalse(Perm.has(Perm.DEFAULT_MANAGER, Perm.MANAGE_STAFF))
        assertFalse(Perm.has(Perm.DEFAULT_CASHIER, Perm.VOID))
        assertTrue(Perm.has(Perm.DEFAULT_CASHIER, Perm.REPRINT))
        // Every bit appears once in the editor list, and the list covers every bit in use.
        assertEquals(Perm.LIST.size, Perm.LIST.toSet().size)
        var all = 0L
        for (p in Perm.LIST) {
            assertEquals(1, java.lang.Long.bitCount(p))
            all = all or p
        }
        assertEquals(all, all or Perm.DEFAULT_MANAGER or Perm.DEFAULT_CASHIER)
        assertEquals((1L shl 17) - 1L, all)
    }
}

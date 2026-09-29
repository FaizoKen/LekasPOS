package com.lekaspos.core.staff

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Staff PINs are stored only as salted PBKDF2-HMAC-SHA256 hashes (D-037). The whole record —
 * scheme, iterations, salt and hash — is ONE string, so a PIN change is a single LWW field and
 * two devices can never merge one PIN's salt with another PIN's hash.
 *
 * A 4–6 digit PIN cannot resist an offline guessing attack whatever the hash; the hash keeps
 * PINs out of plain sight (database copies, sync files), and [PinLockout] throttles guessing
 * at the till. PBKDF2 is built on `Mac` because `PBKDF2WithHmacSHA256` needs Android 8.
 */
object PinHash {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 6

    /** Tuned to ~50 ms on a slow Android 5 phone; stored per hash, so it can change later. */
    const val ITERATIONS = 4000
    private const val SCHEME = "p2"
    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 32

    fun validPin(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    /** A new stored record for [secret] (a PIN or a normalized recovery code). */
    fun create(secret: String, random: SecureRandom = SecureRandom(), iterations: Int = ITERATIONS): String {
        val salt = ByteArray(SALT_BYTES)
        random.nextBytes(salt)
        return encode(iterations, salt, pbkdf2(secret.toByteArray(Charsets.UTF_8), salt, iterations, HASH_BYTES))
    }

    /** True when [secret] matches [stored]; false for any malformed record. */
    fun verify(secret: String, stored: String?): Boolean {
        val parts = stored?.split(':') ?: return false
        if (parts.size != 4 || parts[0] != SCHEME) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
        val salt = unhex(parts[2]) ?: return false
        val expected = unhex(parts[3]) ?: return false
        if (expected.isEmpty() || secret.isEmpty()) return false
        val actual = pbkdf2(secret.toByteArray(Charsets.UTF_8), salt, iterations, expected.size)
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].toInt() xor actual[i].toInt())
        return diff == 0
    }

    internal fun encode(iterations: Int, salt: ByteArray, hash: ByteArray): String = "$SCHEME:$iterations:${hex(salt)}:${hex(hash)}"

    /** RFC 8018 PBKDF2 with HMAC-SHA256. */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        require(iterations >= 1 && length >= 1) { "bad PBKDF2 parameters" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val h = mac.macLength
        val out = ByteArray(length)
        val u = ByteArray(h)
        val t = ByteArray(h)
        var block = 1
        var offset = 0
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            mac.doFinal(u, 0)
            System.arraycopy(u, 0, t, 0, h)
            for (i in 1 until iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (j in 0 until h) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(h, length - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            block++
        }
        return out
    }

    private const val HEX = "0123456789abcdef"

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            sb.append(HEX[(x.toInt() shr 4) and 0xF])
            sb.append(HEX[x.toInt() and 0xF])
        }
        return sb.toString()
    }

    private fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = HEX.indexOf(s[2 * i])
            val lo = HEX.indexOf(s[2 * i + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

/**
 * Wrong-PIN throttling for one till (D-037): [FREE_TRIES] wrong PINs in a row are free, then
 * every further wrong PIN makes the till wait 30 s, doubling up to 15 minutes. A correct PIN
 * resets the count. Pure: the app keeps the count and the last failure time.
 */
object PinLockout {
    const val FREE_TRIES = 5
    const val FIRST_WAIT_MS = 30_000L
    const val MAX_WAIT_MS = 15 * 60_000L

    /** Wait imposed after the [failures]-th wrong PIN in a row. */
    fun waitMs(failures: Int): Long {
        if (failures < FREE_TRIES) return 0L
        val doublings = (failures - FREE_TRIES).coerceAtMost(10)
        return (FIRST_WAIT_MS shl doublings).coerceAtMost(MAX_WAIT_MS)
    }

    /**
     * Milliseconds left before the next try (0 = may try now). If the clock was set back
     * before the last failure, the full wait counts from now.
     */
    fun remainingMs(failures: Int, lastFailureAt: Long, now: Long): Long {
        val wait = waitMs(failures)
        if (wait == 0L) return 0L
        if (now < lastFailureAt) return wait
        return (lastFailureAt + wait - now).coerceAtLeast(0L)
    }

    /** Wrong PINs still allowed before the first wait. */
    fun triesLeft(failures: Int): Int = (FREE_TRIES - failures).coerceAtLeast(0)
}

/**
 * The owner's recovery code (D-037): shown once when the owner first sets a PIN, stored only
 * as a hash, and the only way back in when the owner forgets the PIN (there is no server).
 * 12 characters from an alphabet without look-alikes (no 0/O, 1/I/L), shown as XXXX-XXXX-XXXX.
 */
object RecoveryCode {
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val LENGTH = 12

    fun generate(random: SecureRandom = SecureRandom()): String {
        val sb = StringBuilder(LENGTH + 2)
        for (i in 0 until LENGTH) {
            if (i > 0 && i % 4 == 0) sb.append('-')
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
        return sb.toString()
    }

    /** Upper-cases and drops separators, so "abcd efgh-jkmn" matches "ABCD-EFGH-JKMN". */
    fun normalize(input: String): String = input.uppercase().filter { it.isLetterOrDigit() }
}

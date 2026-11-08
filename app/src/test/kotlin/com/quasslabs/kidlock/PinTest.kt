package com.quasslabs.kidlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinTest {

    @Test
    fun hashIsSaltedSha256Hex() {
        assertTrue(Pin.hash("abc", "1234").matches(Regex("[0-9a-f]{64}")))
        // Known vector: sha256("abc") from FIPS 180-2. Salt and PIN are concatenated.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Pin.hash("ab", "c"),
        )
    }

    @Test
    fun sameSaltAndPinGiveSameHash() {
        assertEquals(Pin.hash("salt", "2468"), Pin.hash("salt", "2468"))
    }

    @Test
    fun differentSaltGivesDifferentHash() {
        assertNotEquals(Pin.hash("salt-a", "2468"), Pin.hash("salt-b", "2468"))
    }

    @Test
    fun matchesAcceptsRightPinAndRejectsWrongOnes() {
        val stored = Pin.hash("salt", "2468")
        assertTrue(Pin.matches("salt", stored, "2468"))
        assertFalse(Pin.matches("salt", stored, "2469"))
        assertFalse(Pin.matches("salt", stored, ""))
        assertFalse(Pin.matches("other", stored, "2468"))
    }

    @Test
    fun firstFourMissesDoNotLock() {
        for (fails in 0..4) assertEquals("fails=$fails", 0L, Pin.lockoutMs(fails))
    }

    @Test
    fun fifthMissLocksForThirtySecondsThenDoubles() {
        assertEquals(30_000L, Pin.lockoutMs(5))
        assertEquals(60_000L, Pin.lockoutMs(6))
        assertEquals(120_000L, Pin.lockoutMs(7))
        assertEquals(240_000L, Pin.lockoutMs(8))
        assertEquals(30_000L * 64, Pin.lockoutMs(11))
    }

    @Test
    fun lockoutIsCappedAtOneHour() {
        assertEquals(3_600_000L, Pin.lockoutMs(12)) // 30 s * 128 would be 64 minutes
        assertEquals(3_600_000L, Pin.lockoutMs(13))
        assertEquals(3_600_000L, Pin.lockoutMs(1_000))
        assertEquals(3_600_000L, Pin.lockoutMs(Int.MAX_VALUE))
    }
}

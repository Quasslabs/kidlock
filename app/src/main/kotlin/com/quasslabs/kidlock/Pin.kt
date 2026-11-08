package com.quasslabs.kidlock

import java.security.MessageDigest

/** PIN hashing and lockout math, kept free of Android types so it can be unit tested. */
object Pin {
    /** Wrong PINs allowed before entry starts locking. */
    const val FREE_TRIES = 5
    const val BASE_LOCKOUT_MS = 30_000L
    const val MAX_LOCKOUT_MS = 60 * 60_000L

    /** Salted SHA-256 of [pin], as lowercase hex. */
    fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).toHex()

    /** Constant-time comparison of a stored hash with the hash of [pin]. */
    fun matches(salt: String, storedHash: String, pin: String): Boolean =
        MessageDigest.isEqual(storedHash.toByteArray(), hash(salt, pin).toByteArray())

    /**
     * How long entry locks after [fails] wrong PINs in a row: nothing for the first
     * [FREE_TRIES] - 1 misses, then 30 seconds, doubling with each further miss, capped at an hour.
     */
    fun lockoutMs(fails: Int): Long {
        if (fails < FREE_TRIES) return 0
        // Shift is capped so it cannot overflow; 30 s shl 7 already passes the one-hour cap.
        return (BASE_LOCKOUT_MS shl (fails - FREE_TRIES).coerceAtMost(7)).coerceAtMost(MAX_LOCKOUT_MS)
    }

    fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

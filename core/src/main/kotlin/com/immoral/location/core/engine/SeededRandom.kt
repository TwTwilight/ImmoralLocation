package com.immoral.location.core.engine

import com.immoral.location.core.model.GeoPoint
import kotlin.math.round

/**
 * Tiny deterministic PRNG (SplitMix64) used by every synthesiser in this package.
 *
 * Randomness must be reproducible: the hook layer inside `system_server` and the UI app must
 * show *identical* satellites, cells and access points for the same position. A plain
 * `kotlin.random.Random` would work per-process but could not be seeded from coordinates in a
 * stable, cross-platform way, so the generator is implemented here with fixed 64-bit wrapping
 * arithmetic.
 */
internal class SeededRandom(seed: Long) {
    private var state: Long = seed

    /** Next 64-bit value of the stream. */
    fun nextLong(): Long {
        state += GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    /** Next value in `0 until bound` (uniform enough for simulation purposes). */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return ((nextLong() ushr 1) % bound).toInt()
    }

    /** Next value in `from..to` (inclusive). */
    fun nextInt(from: Int, to: Int): Int {
        require(to >= from) { "to must be >= from" }
        return from + nextInt(to - from + 1)
    }

    /** Next double in `[0.0, 1.0)`. */
    fun nextDouble(): Double = (nextLong() ushr 11) * DOUBLE_UNIT

    /** Next double in `min..max`. */
    fun nextDouble(min: Double, max: Double): Double = min + nextDouble() * (max - min)

    /** Picks one element of [values]. */
    fun <T> pick(values: List<T>): T = values[nextInt(values.size)]

    private companion object {
        const val GAMMA = -0x61c8864680b583ebL
        const val MIX_1 = -0x40a7b892e31b1a47L
        const val MIX_2 = -0x6b2fb644ecceee15L
        const val DOUBLE_UNIT = 1.0 / (1L shl 53)
    }
}

/**
 * Builds a stable seed from a position rounded to 4 decimals (≈11 m), so micro-movements do
 * not make satellites flicker while real movement does change them.
 */
internal fun seedFor(position: GeoPoint, salt: Long): Long {
    val latQuantized = round(position.lat * 10_000.0).toLong()
    val lonQuantized = round(position.lon * 10_000.0).toLong()
    var z = latQuantized * -0x61c8864680b583ebL + lonQuantized
    z = z xor salt
    z = (z xor (z ushr 33)) * 0xff51afd7ed558ccdUL.toLong()
    z = (z xor (z ushr 33)) * 0xc4ceb9fe1a85ec53UL.toLong()
    return z xor (z ushr 33)
}

/** Salt for satellite synthesis. */
internal const val SALT_SATELLITES: Long = 0x5a7e_11a7_0001L

/** Salt for NMEA synthesis. */
internal const val SALT_NMEA: Long = 0x5a7e_ab3e_0002L

/** Salt for cellular synthesis. */
internal const val SALT_CELLS: Long = 0x5a7e_ce11_0003L

/** Salt for Wi-Fi synthesis. */
internal const val SALT_WIFI: Long = 0x5a7e_c0de_0004L

/** Salt for the GPS jitter animation. */
internal const val SALT_JITTER: Long = 0x5a7e_170e_0005L

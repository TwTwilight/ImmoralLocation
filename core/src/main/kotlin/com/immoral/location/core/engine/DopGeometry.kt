package com.immoral.location.core.engine

import com.immoral.location.core.model.Satellite
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dilution of precision (HDOP/VDOP/PDOP) computed from a simulated constellation's geometry.
 *
 * ### Why this exists
 * A real receiver does not report an arbitrary accuracy number: the error budget is
 * `UERE × DOP`, i.e. the ranging error multiplied by how badly the visible geometry is spread
 * across they sky. A spoof that reports "9 satellites, C/N0 45 dB-Hz, accuracy 9 m" is internally
 * inconsistent in a way an app can check for free: the accuracy must follow from the satellites
 * that are claimed to have produced the fix, not be an independent random number.
 *
 * So one geometry implementation serves both consumers:
 *  * `NmeaSynth` fills `$GPGSA` (and `$GPGGA`'s HDOP) from [of],
 *  * `AccuracySynth` turns the same HDOP into the `Location` accuracy that apps read.
 *
 * ### Model
 * For each satellite the line-of-sight unit vector in the local east/north/up frame is
 * `u = (cos el · sin az, cos el · cos az, sin el)`. The linearised observation matrix rows (with the
 * receiver clock bias as the fourth unknown) are `(-sin az, cos az, 0, 1)` weighted by elevation,
 * and `Q = (HᵗWH)⁻¹` gives
 * `HDOP = √(Q₁₁ + Q₂₂)`, `VDOP = √Q₃₃`, `PDOP = √(Q₁₁ + Q₂₂ + Q₃₃)`.
 *
 * ### Elevation filter
 * Satellites below [MIN_ELEVATION_DEG] are excluded, exactly like a receiver's elevation mask;
 * everything above it enters the solution with equal weight. That is the textbook *geometric*
 * DOP (the quantity `$GPGSA` and `Location.getAccuracy()` are expressed in), not a weighted-DOP
 * variant that would report numbers no real receiver emits.
 *
 * ### Deliberate limits
 * This is the geometry of the *reported* constellation, not an ephemeris: it inherits whatever
 * plausibility [SatelliteSynth] produced. It ignores time-correlated errors, ionospheric delay and
 * multipath, so the numbers are plausible rather than metrologically exact. When the geometry is
 * unusable (fewer than four satellites, or a degenerate constellation) [of] returns
 * [UNUSABLE_DOP] instead of pretending.
 */
object DopGeometry {

    /** DOP reported when the constellation cannot support a fix at all. */
    const val UNUSABLE_DOP: Double = 99.9

    /** Lowest elevation, in degrees, that takes part in the DOP computation. */
    const val MIN_ELEVATION_DEG: Double = 5.0

    /** One DOP triple. */
    data class Dop(
        val hdop: Double,
        val vdop: Double,
        val pdop: Double,
    ) {
        /** `true` when the geometry actually supported a solution. */
        val usable: Boolean get() = pdop < UNUSABLE_DOP
    }

    /** The unusable-geometry triple. */
    val NONE: Dop = Dop(UNUSABLE_DOP, UNUSABLE_DOP, UNUSABLE_DOP)

    /**
     * DOP for [satellites].
     *
     * Satellites flagged `usedInFix` are preferred; when none is flagged the whole list is used,
     * because callers (the GNSS status probe, tests, the preview) sometimes hold an unfiltered list.
     */
    fun of(satellites: List<Satellite>): Dop {
        val flagged = satellites.filter { it.usedInFix }
        val inFix = if (flagged.isNotEmpty()) flagged else satellites
        val visible = inFix.filter { it.elevationDeg >= MIN_ELEVATION_DEG }
        if (visible.size < 4) return NONE
        val normal = Array(4) { DoubleArray(4) }
        var rows = 0
        for (satellite in visible) {
            val elevation = Math.toRadians(satellite.elevationDeg.toDouble())
            val azimuth = Math.toRadians(satellite.azimuthDeg.toDouble())
            val sinEl = sin(elevation)
            if (sinEl <= 1e-3) continue
            val weight = 1.0
            // Row of the linearised observation matrix: the line-of-sight unit vector in the local
            // east/north/up frame, with the receiver clock bias as the fourth unknown.
            val horizontal = cos(elevation)
            val row = doubleArrayOf(
                -horizontal * sin(azimuth),
                -horizontal * cos(azimuth),
                -sinEl,
                1.0,
            )
            for (i in 0 until 4) {
                for (j in 0 until 4) {
                    normal[i][j] += weight * row[i] * row[j]
                }
            }
            rows++
        }
        if (rows < 4) return NONE
        val inverse = invert(normal) ?: return NONE
        val horizontal = inverse[0][0] + inverse[1][1]
        val vertical = inverse[2][2]
        if (!horizontal.isFinite() || !vertical.isFinite() || horizontal <= 0.0 || vertical <= 0.0) {
            return NONE
        }
        return Dop(
            hdop = sanitizeDop(sqrt(horizontal)),
            vdop = sanitizeDop(sqrt(vertical)),
            pdop = sanitizeDop(sqrt(horizontal + vertical)),
        )
    }

    private fun sanitizeDop(value: Double): Double =
        if (!value.isFinite() || value <= 0.0 || value > UNUSABLE_DOP) UNUSABLE_DOP else value

    /**
     * Gauss-Jordan inverse of a 4×4 matrix.
     *
     * @return the inverse, or `null` when the matrix is (numerically) singular — a degenerate
     *   constellation of satellites that all sit in one direction cannot localise you.
     */
    private fun invert(matrix: Array<DoubleArray>): Array<DoubleArray>? {
        val n = matrix.size
        val work = Array(n) { row -> DoubleArray(2 * n) { col -> if (col < n) matrix[row][col] else if (col - n == row) 1.0 else 0.0 } }
        for (column in 0 until n) {
            var pivot = column
            for (row in column + 1 until n) {
                if (kotlin.math.abs(work[row][column]) > kotlin.math.abs(work[pivot][column])) pivot = row
            }
            if (kotlin.math.abs(work[pivot][column]) < 1e-12) return null
            if (pivot != column) {
                val swap = work[pivot]
                work[pivot] = work[column]
                work[column] = swap
            }
            val divisor = work[column][column]
            for (col in 0 until 2 * n) work[column][col] /= divisor
            for (row in 0 until n) {
                if (row == column) continue
                val factor = work[row][column]
                if (factor == 0.0) continue
                for (col in 0 until 2 * n) work[row][col] -= factor * work[column][col]
            }
        }
        return Array(n) { row -> DoubleArray(n) { col -> work[row][col + n] } }
    }
}

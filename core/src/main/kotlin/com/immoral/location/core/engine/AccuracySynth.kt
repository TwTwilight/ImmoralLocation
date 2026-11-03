package com.immoral.location.core.engine

import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.Satellite
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/**
 * The reported horizontal accuracy of a simulated fix, derived from the simulated sky.
 *
 * ### Why the accuracy is not a constant
 * `Location.getAccuracy()` is one of the few fields an app can cross-check against everything else
 * the platform tells it:
 *
 * * a **constant** accuracy (the same 6 m at every position, at every second) is not what a real
 *   receiver produces — it wanders by ±20 % between epochs as the geometry and the ionosphere move;
 * * an accuracy **unrelated to the reported satellites** is worse: 16 satellites at C/N0 45 dB-Hz
 *   geometrically cannot coexist with "accuracy 25 m", and 4 satellites near the horizon cannot
 *   produce "accuracy 2 m".
 *
 * So the number is computed the way a receiver computes it: `accuracy = UERE × HDOP`, with the
 * user-equivalent range error falling as the average C/N0 rises, the dilution coming from
 * [DopGeometry] over the same satellites the GNSS status reports, and a slow, deterministic
 * wander on top so two consecutive fixes are close but not identical.
 *
 * ### Determinism and smoothness
 * Pure in `(position, nowMs)`: the hook inside `system_server` and the UI preview must agree. The
 * wander is two sinusoids (23 s and 71 s, about ±12 % and ±7 %) whose phases come from a coarse
 * position grid, so it is continuous in time (a 1 s step moves the accuracy by well under
 * [MAX_STEP_METERS]) and changes only when the simulated position actually moves.
 */
object AccuracySynth {

    /** Best accuracy ever reported, in meters. */
    const val MIN_ACCURACY_M: Double = 2.0

    /** Worst accuracy ever reported, in meters. */
    const val MAX_ACCURACY_M: Double = 60.0

    /** Largest change between two consecutive 1 s fixes, in meters (`MAX_ACCURACY_M` × 19 %). */
    const val MAX_STEP_METERS: Double = 0.9

    /** Default average C/N0 when no constellation is simulated, in dB-Hz. */
    const val DEFAULT_CN0_DB_HZ: Double = 34.0

    /** Default HDOP when no constellation is simulated (open sky, decent geometry). */
    const val DEFAULT_HDOP: Double = 1.3

    /**
     * HDOP assumed when a constellation *is* reported but its geometry cannot localise anybody.
     *
     * This is deliberately pessimistic: an app that sees "16 satellites, all in one direction" and
     * a 3 m accuracy has found an impossible combination, so the unusable case must look bad.
     */
    const val UNUSABLE_HDOP: Double = 5.0

    /** C/N0 assumed for the error budget when the list carries no usable signal values. */
    private const val CN0_FLOOR_DB_HZ: Double = 15.0

    /**
     * Horizontal accuracy for a fix coming from [provider].
     *
     * @param position Simulated position; only used to derive the wander phase.
     * @param satellites The simulated constellation (may be empty when signal simulation is off).
     * @param nowMs Current wall clock in epoch milliseconds.
     * @param jitterMeters Amplitude of the configured position jitter, which widens the error.
     */
    fun accuracyMeters(
        position: GeoPoint,
        satellites: List<Satellite>,
        nowMs: Long,
        jitterMeters: Double = 0.0,
    ): Double {
        val usable = satellites.filter { it.elevationDeg >= DopGeometry.MIN_ELEVATION_DEG }
        val inFix = usable.filter { it.usedInFix }.ifEmpty { usable }
        val meanCn0 = if (inFix.isEmpty()) {
            DEFAULT_CN0_DB_HZ
        } else {
            inFix.map { it.cn0DbHz.toDouble() }.average().coerceAtLeast(CN0_FLOOR_DB_HZ)
        }
        val meanElevation = if (inFix.isEmpty()) {
            35.0
        } else {
            inFix.map { it.elevationDeg.toDouble() }.average()
        }
        val dop = DopGeometry.of(satellites)
        val hdop = when {
            // No constellation is reported at all (signal simulation off): assume open sky.
            usable.isEmpty() -> DEFAULT_HDOP
            // A reported constellation that cannot localise anybody must read as a bad fix.
            !dop.usable -> UNUSABLE_HDOP
            else -> dop.hdop.coerceIn(0.7, UNUSABLE_HDOP)
        }
        // UERE: a strong, well-elevated constellation puts a phone within a few meters, and a weak
        // one is roughly twice as bad — the range real consumer receivers report.
        val uere = 2.2 + 1_800.0 / (meanCn0 * meanCn0)
        // Low satellites mean longer paths through the atmosphere and more multipath.
        val multipath = 1.0 + ((35.0 - meanElevation).coerceIn(0.0, 25.0) / 40.0)
        val wander = wanderFactor(position, nowMs)
        val jitterRadius = if (jitterMeters.isFinite()) jitterMeters.coerceIn(0.0, 100.0) else 0.0
        // The configured position jitter *is* additional horizontal error, so it has to appear in
        // the reported accuracy: a fix that wanders ±20 m while claiming "accuracy 4 m" is a
        // contradiction (and the reference implementations do couple the two). Combined in
        // quadrature, because the geometry error and the simulated error are independent.
        val raw = hypot(hdop * uere * multipath * wander, jitterRadius * 0.9)
        return round(raw.coerceIn(MIN_ACCURACY_M, MAX_ACCURACY_M) * 10.0) / 10.0
    }

    /**
     * Accuracy a `network` provider reports when the GNSS fix has [gpsAccuracyMeters].
     *
     * Network fixes are bounded by cell/Wi-Fi geometry, so they never beat GNSS and never fall
     * below the ~15 m floor of a single-cell fix.
     */
    fun networkAccuracyMeters(gpsAccuracyMeters: Double): Double =
        round((gpsAccuracyMeters * 2.5 + 8.0).coerceIn(15.0, 120.0) * 10.0) / 10.0

    /**
     * Accuracy a `fused` provider reports when the GNSS fix has [gpsAccuracyMeters].
     *
     * The fused provider is always at least as good as GNSS (it may add Wi-Fi/cell corrections)
     * but never better than the ~4 m the best consumer phones reach indoors.
     */
    fun fusedAccuracyMeters(gpsAccuracyMeters: Double): Double =
        round((gpsAccuracyMeters * 1.3 + 3.0).coerceIn(4.0, 50.0) * 10.0) / 10.0

    /**
     * Slow, deterministic multiplier around `1.0` (roughly `0.81..1.19`).
     *
     * Two incommensurate periods keep the number from repeating on any short cadence, and the phase
     * comes from a ~1 km grid so a walking simulation does not make the accuracy jump.
     */
    fun wanderFactor(position: GeoPoint, nowMs: Long): Double {
        val seed = gridSeed(position)
        val phaseA = (seed % 1_000L) / 1_000.0 * 2.0 * Math.PI
        val phaseB = ((seed / 1_000L) % 1_000L) / 1_000.0 * 2.0 * Math.PI
        val seconds = nowMs / 1_000.0
        return 1.0 +
            0.12 * sin(2.0 * Math.PI * seconds / 23.0 + phaseA) +
            0.07 * sin(2.0 * Math.PI * seconds / 71.0 + phaseB)
    }

    /** Deterministic phase seed from a ~1 km position grid. */
    private fun gridSeed(position: GeoPoint): Long {
        val lat = floor(position.lat * 100.0).toLong()
        val lon = floor(position.lon * 100.0).toLong()
        var z = lat * -0x61c8864680b583ebL + lon
        z = (z xor (z ushr 33)) * -0x40a7b892e31b1a47L
        return abs(z xor (z ushr 31))
    }
}

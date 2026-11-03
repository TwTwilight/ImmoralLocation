package com.immoral.location.core.route

import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import kotlinx.serialization.Serializable

/**
 * An ordered list of waypoints the simulated position can walk along.
 *
 * Sampling is arclength based: [sample] maps a normalized progress in `0..1` onto the
 * polyline using real great-circle distances, so a route with short and long segments
 * advances at a constant ground speed.
 *
 * ### Longitudinal profile and traffic stops
 * The plan describes *where* the vehicle goes and *how fast* it cruises ([speedMps]); the engine
 * turns that into a drive with acceleration, comfortable braking and (optionally) standstill
 * phases. Two things live here because playback and scheduling must agree on the same physics:
 *
 *  * [ACCELERATION_MPS2] / [DECELERATION_MPS2], the vehicle dynamics both sides assume;
 *  * [trafficStops], the deterministic stop schedule (points along the polyline where the vehicle
 *    comes to rest, plus how long it waits).
 *
 * There is **no road graph and no intersection data**: a stop is synthetic. It is placed by
 * arclength, not snapped to a junction, so on real OSRM/高德 geometry a stop can fall mid-block.
 * It exists to make the *signal* of the drive (moving, standing, moving again — with a position
 * that is genuinely identical from one fix to the next while standing) look like a car in a city
 * rather than a cursor gliding at a constant 50 km/h.
 *
 * @property points Waypoints in travel order. Fewer than two points makes the plan degenerate.
 * @property repeat Whether playback restarts from the first point after the last one.
 * @property speedMps Cruising ground speed used while playing this plan.
 */
@Serializable
data class RoutePlan(
    val points: List<GeoPoint>,
    val repeat: Boolean = false,
    val speedMps: Double = 2.0,
) {
    /** `true` when there are at least two distinct points to travel between. */
    val isUsable: Boolean
        get() = points.size >= 2

    /** Cumulative arclength at each point; index `i` holds the distance to point `i`. */
    private val cumulativeMeters: DoubleArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val result = DoubleArray(points.size)
        for (i in 1 until points.size) {
            result[i] = result[i - 1] + GeoMath.distanceMeters(points[i - 1], points[i])
        }
        result
    }

    /**
     * Total polyline length in meters.
     *
     * @return `0.0` for plans with fewer than two points.
     */
    fun totalDistanceMeters(): Double =
        if (points.size < 2) 0.0 else cumulativeMeters[points.lastIndex]

    /**
     * Position and bearing at [progress].
     *
     * @param progress Normalized progress; values outside `0..1` are clamped, so `0` is the
     *   first point and `1` is the last one.
     * @return the interpolated [GeoPoint] and the bearing of the segment it lies on, in degrees
     *   `[0,360)`.
     */
    fun sample(progress: Double): Pair<GeoPoint, Double> {
        if (points.isEmpty()) return GeoPoint.ZERO to 0.0
        if (points.size == 1) return points[0] to 0.0
        val total = totalDistanceMeters()
        val clamped = if (progress.isFinite()) progress.coerceIn(0.0, 1.0) else 0.0
        if (total <= 0.0) return points[0] to GeoMath.bearingDegrees(points[0], points.last())
        val target = total * clamped
        var index = 1
        while (index < points.size - 1 && cumulativeMeters[index] < target) index++
        val segmentStart = cumulativeMeters[index - 1]
        val segmentLength = cumulativeMeters[index] - segmentStart
        val a = points[index - 1]
        val b = points[index]
        val bearing = GeoMath.bearingDegrees(a, b)
        if (segmentLength <= 0.0) return a to bearing
        val fraction = ((target - segmentStart) / segmentLength).coerceIn(0.0, 1.0)
        return GeoMath.interpolate(a, b, fraction) to bearing
    }

    /**
     * Distance travelled when [progress] of the route has been completed.
     *
     * @param progress Normalized progress, clamped into `0..1`.
     */
    fun distanceAt(progress: Double): Double {
        val clamped = if (progress.isFinite()) progress.coerceIn(0.0, 1.0) else 0.0
        return totalDistanceMeters() * clamped
    }

    /** Plan without consecutive duplicates, so degenerate input cannot stall playback. */
    fun normalized(): RoutePlan {
        if (points.size < 2) return this
        val cleaned = ArrayList<GeoPoint>(points.size)
        for (point in points) {
            val previous = cleaned.lastOrNull()
            if (previous == null || previous.lat != point.lat || previous.lon != point.lon) {
                cleaned.add(point)
            }
        }
        return copy(points = cleaned)
    }

    /**
     * The traffic/idle stops this plan plays, in travel order (empty for most plans).
     *
     * ### What it is
     * A list of arclength positions where the simulated vehicle brakes to a full stop, waits
     * [RouteStop.waitSeconds], and pulls away again. Together with the engine's ramps this turns
     * a constant-speed glide into a drive with a plausible speed signal.
     *
     * ### Determinism
     * The list is a pure function of `(geometry, speedMps, stop index)`: it is seeded from a digest
     * of the polyline (vertex count, length, both endpoints and the middle vertex), never from the
     * clock, a tick count or an unseeded `Random`. The UI process, the `system_server` hook and a
     * restarted app therefore schedule byte-identical stops for the same plan, and the same tick
     * sequence always replays the same drive.
     *
     * ### Gating
     * Empty unless [speedMps] is a *vehicle* speed in [MIN_TRAFFIC_STOP_SPEED_MPS]..
     * [MAX_TRAFFIC_STOP_SPEED_MPS] (8..40 m/s, i.e. 29..144 km/h). A 1.4 m/s walk standing at a
     * red light is not a thing this model wants to claim, and a 230 m/s "flight" that stops every
     * few kilometres would be an obvious fake. The caller additionally has to enable
     * [com.immoral.location.core.model.SimulationConfig.routeTrafficStops]; this property always
     * describes what the schedule *would* be.
     *
     * ### Numbers, and why they are sparse
     * Stops are one [TRAFFIC_STOP_STRIDE_SECONDS] of plan-speed driving apart (±[TRAFFIC_STOP_SPREAD]
     * deterministic spread) — about 3.3 km at 50 km/h. That is deliberately rarer than real
     * signalised arterials, because a stop costs a full standstill (15..60 s) *and* the ramp
     * penalty of braking and pulling away again (≈0.37 s per m/s of plan speed, ~5 s at 50 km/h).
     * At one stop every four minutes of driving the stopped share stays near 15–20 %, which keeps
     * the door-to-door average speed within the ±25 % tolerance the on-device acceptance harness
     * allows. Denser stops would make a 50 km/h route average 30 km/h and read as congestion on a
     * road the user chose to drive quickly.
     */
    val trafficStops: List<RouteStop> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildTrafficStops()
    }

    private fun buildTrafficStops(): List<RouteStop> {
        if (points.size < 2) return emptyList()
        val cruise = speedMps
        if (!cruise.isFinite()) return emptyList()
        if (cruise < MIN_TRAFFIC_STOP_SPEED_MPS || cruise > MAX_TRAFFIC_STOP_SPEED_MPS) {
            return emptyList()
        }
        val total = totalDistanceMeters()
        if (total <= 0.0) return emptyList()
        // A stop needs room: the vehicle must be able to accelerate away from the previous stop
        // (or the start) and brake into this one, and after the last stop it must still be able to
        // pull away and come to rest exactly on the final point. Below this run-out a stop would
        // degenerate into "never reaches cruise speed", so it is simply not scheduled.
        val runOut = cruise * cruise / (2.0 * ACCELERATION_MPS2) +
            cruise * cruise / (2.0 * DECELERATION_MPS2) +
            STOP_EDGE_MARGIN_METERS
        val stride = (cruise * TRAFFIC_STOP_STRIDE_SECONDS)
            .coerceIn(MIN_TRAFFIC_STOP_STRIDE_METERS, MAX_TRAFFIC_STOP_STRIDE_METERS)
        val seed = geometryDigest()
        val result = ArrayList<RouteStop>()
        var distance = 0.0
        var index = 0
        var attempt = 0
        while (attempt < MAX_TRAFFIC_STOP_ATTEMPTS && result.size < MAX_TRAFFIC_STOPS_PER_LAP) {
            val spread = 1.0 - TRAFFIC_STOP_SPREAD +
                2.0 * TRAFFIC_STOP_SPREAD * unitFrom(seed, attempt, SALT_STOP_GAP)
            distance += stride * spread
            attempt++
            if (distance + runOut > total) break
            if (distance < runOut) continue
            val wait = MIN_TRAFFIC_STOP_WAIT_SECONDS +
                (MAX_TRAFFIC_STOP_WAIT_SECONDS - MIN_TRAFFIC_STOP_WAIT_SECONDS) *
                unitFrom(seed, index, SALT_STOP_WAIT)
            result.add(RouteStop(index = index, distanceMeters = distance, waitSeconds = wait))
            index++
        }
        return result
    }

    /**
     * Stable digest of the geometry, used as the seed of the stop schedule.
     *
     * Deliberately cheap and pure: vertex count, length in centimetres and three vertices at
     * 1e-6° (≈0.1 m) resolution. Two different drives almost never digest to the same value, and
     * the same drive always digests to the same one, which is all the schedule needs.
     */
    private fun geometryDigest(): Long {
        var hash = DIGEST_SEED
        hash = mix64(hash xor points.size.toLong())
        hash = mix64(hash xor Math.round(totalDistanceMeters() * 100.0))
        for (index in intArrayOf(0, points.size / 2, points.lastIndex)) {
            val point = points[index]
            hash = mix64(hash xor Math.round(point.lat * 1_000_000.0))
            hash = mix64(hash xor Math.round(point.lon * 1_000_000.0))
        }
        return hash
    }

    companion object {
        /** A plan with no points. */
        val EMPTY: RoutePlan = RoutePlan(points = emptyList())

        /**
         * Longitudinal acceleration the route player uses, in m/s².
         *
         * 3.0 m/s² is the "0–100 km/h in ~9.3 s" of an ordinary compact car — brisk enough to be
         * believable, gentle enough that the speed read-out does not jump. Hard-coded (not
         * configurable) so playback, the stop scheduler and the tests share one number.
         */
        const val ACCELERATION_MPS2: Double = 3.0

        /**
         * Longitudinal deceleration the route player uses, in m/s².
         *
         * 2.5 m/s² ≈ 0.25 g, the comfortable-braking figure every vehicle-dynamics reference uses
         * for a planned stop (emergency braking is ~8 m/s², but nobody drives a route like that).
         */
        const val DECELERATION_MPS2: Double = 2.5

        /** Lowest plan speed that schedules traffic stops, in m/s (29 km/h). */
        const val MIN_TRAFFIC_STOP_SPEED_MPS: Double = 8.0

        /** Highest plan speed that schedules traffic stops, in m/s (144 km/h). */
        const val MAX_TRAFFIC_STOP_SPEED_MPS: Double = 40.0

        /** Shortest standstill at a stop, in seconds (a light that is already green by the time you stop). */
        const val MIN_TRAFFIC_STOP_WAIT_SECONDS: Double = 15.0

        /** Longest standstill at a stop, in seconds (a red light against a slow queue). */
        const val MAX_TRAFFIC_STOP_WAIT_SECONDS: Double = 60.0

        /**
         * Spacing between stops, expressed as seconds of plan-speed driving.
         *
         * 240 s ≈ 3.3 km at 50 km/h, i.e. roughly one stop per four minutes of driving. See
         * [RoutePlan.trafficStops] for why the schedule is this sparse; the short answer is that a
         * denser schedule cannot keep the average speed within 25 % of the plan speed.
         */
        const val TRAFFIC_STOP_STRIDE_SECONDS: Double = 240.0

        /** Relative spread of the spacing, so stops are not at a suspiciously constant interval. */
        const val TRAFFIC_STOP_SPREAD: Double = 0.2

        /** Lower bound of the stride, in meters (never schedule stops every few hundred meters). */
        const val MIN_TRAFFIC_STOP_STRIDE_METERS: Double = 800.0

        /** Upper bound of the stride, in meters (a long autobahn leg still has occasional stops). */
        const val MAX_TRAFFIC_STOP_STRIDE_METERS: Double = 12_000.0

        /** Extra distance kept between a stop and either end of the route, in meters. */
        const val STOP_EDGE_MARGIN_METERS: Double = 20.0

        /** Hard cap so a pathological plan cannot allocate an unbounded schedule. */
        const val MAX_TRAFFIC_STOPS_PER_LAP: Int = 128

        /** Hard cap on the spacing loop (guards the `distance < runOut` skip). */
        private const val MAX_TRAFFIC_STOP_ATTEMPTS: Int = 256

        private const val DIGEST_SEED: Long = 0x52_4f_55_54_45_00_00_01L
        private const val SALT_STOP_GAP: Long = 0x1DB7_10C4_8E1F_2A93L
        private const val SALT_STOP_WAIT: Long = 0x2F1E_3D4C_5B6A_7988L
        private const val GOLDEN_GAMMA: Long = -0x61C8_8646_80B5_83EBL

        /** SplitMix64 finalizer: mixes one 64-bit word into an avalanche. */
        private fun mix64(value: Long): Long {
            var z = value
            z = (z xor (z ushr 30)) * -0x40a7_b892_e31b_1a47L
            z = (z xor (z ushr 27)) * -0x6b2f_b644_ecce_ee15L
            return z xor (z ushr 31)
        }

        /** Deterministic value in `[0,1)` for `(seed, index, salt)`. */
        private fun unitFrom(seed: Long, index: Int, salt: Long): Double {
            val mixed = mix64(seed xor salt xor (index.toLong() * GOLDEN_GAMMA))
            return (mixed ushr 11) * (1.0 / (1L shl 53).toDouble())
        }
    }
}

/**
 * One synthetic traffic/idle stop on a [RoutePlan].
 *
 * @property index Position in the schedule (`0` is the first stop of a lap); part of the
 *   determinism contract, and used to derive [waitSeconds].
 * @property distanceMeters Distance along the polyline at which the vehicle comes to rest.
 * @property waitSeconds How long it stays absolutely stationary, in seconds.
 */
data class RouteStop(
    val index: Int,
    val distanceMeters: Double,
    val waitSeconds: Double,
)

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
 * @property points Waypoints in travel order. Fewer than two points makes the plan degenerate.
 * @property repeat Whether playback restarts from the first point after the last one.
 * @property speedMps Ground speed used while playing this plan.
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

    companion object {
        /** A plan with no points. */
        val EMPTY: RoutePlan = RoutePlan(points = emptyList())
    }
}

package com.immoral.location.core.route

import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import kotlinx.serialization.Serializable

/**
 * Travel mode a route is planned for.
 *
 * [wireName] is the segment used in the public OSRM route URL
 * (`https://router.project-osrm.org/route/v1/{wireName}/...`); the AMap SDK exposes a different
 * entry point per mode, so the caller switches on this enum instead.
 */
@Serializable
enum class RouteProfile(val id: String, val wireName: String, val defaultSpeedMps: Double) {
    /** 步行 — footpaths and pedestrian shortcuts. */
    WALK("walk", "walking", 1.4),

    /** 骑行 — cycleways where they exist. */
    BIKE("bike", "cycling", 5.5),

    /** 驾车 — one-way rules apply and ferries may be used. */
    DRIVE("drive", "driving", 13.9),
    ;

    companion object {
        /** Parses a persisted / user-supplied id, falling back to [WALK]. */
        fun fromId(id: String?): RouteProfile =
            entries.firstOrNull { it.id == id } ?: WALK
    }
}

/** Which backend produced the geometry of a [RouteResult]. */
@Serializable
enum class RouteSource {
    /** 高德 `RouteSearch` SDK (road-level geometry, China only, needs a valid SDK key). */
    AMAP,

    /** Public OSRM router (`router.project-osrm.org`), road-level geometry, no key. */
    OSRM,

    /**
     * Straight-line fallback: every leg is a direct line between its two stops.
     *
     * Used when no network backend answered. The simulation still works — it simply follows
     * lines instead of roads — and the UI shows a non-blocking notice.
     */
    STRAIGHT_LINE,
}

/**
 * One planning request: `origin → waypoint 1 … waypoint n → destination`.
 *
 * All points are **WGS-84**; conversion to the GCJ-02 "火星坐标" a Chinese map provider expects
 * happens inside the network clients, never here, so the engine and the map renderer keep one
 * convention.
 *
 * @property origin Where the traveller starts (usually the current simulated position).
 * @property destination Where the route ends.
 * @property waypoints Intermediate stops, in travel order; empty for a simple A→B route.
 * @property profile Travel mode the geometry should be planned for.
 */
@Serializable
data class RouteRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val waypoints: List<GeoPoint> = emptyList(),
    val profile: RouteProfile = RouteProfile.WALK,
) {
    /** Valid intermediate stops only; invalid (NaN / out-of-range) entries are dropped. */
    val validWaypoints: List<GeoPoint>
        get() = waypoints.filter { it.isValidLatLon() }

    /**
     * All stops in travel order: origin, intermediate waypoints, destination.
     *
     * Consecutive duplicates are removed (a "waypoint" placed exactly on the origin produces a
     * zero-length leg, which some routers reject and which can stall playback).
     */
    fun stops(): List<GeoPoint> {
        val result = ArrayList<GeoPoint>(validWaypoints.size + 2)
        fun add(point: GeoPoint) {
            if (!point.isValidLatLon()) return
            val previous = result.lastOrNull()
            if (previous != null &&
                previous.lat == point.lat &&
                previous.lon == point.lon
            ) {
                return
            }
            result.add(point)
        }
        add(origin)
        validWaypoints.forEach(::add)
        add(destination)
        return result
    }

    /** Number of legs [stops] produce; `0` when there is nothing to travel. */
    fun legCount(): Int = (stops().size - 1).coerceAtLeast(0)

    /** `true` when there are at least two distinct stops, i.e. the request can be planned. */
    val isUsable: Boolean
        get() = legCount() > 0

    /**
     * Returns a sanitized copy: invalid points dropped, consecutive duplicates removed,
     * destination kept even when it equals the last waypoint.
     */
    fun normalized(): RouteRequest {
        val clean = stops()
        return when (clean.size) {
            0 -> this
            1 -> copy(origin = clean[0], destination = clean[0], waypoints = emptyList())
            else -> copy(
                origin = clean.first(),
                destination = clean.last(),
                waypoints = clean.subList(1, clean.size - 1).toList(),
            )
        }
    }

    companion object {
        /** A request that cannot be planned: both ends are the same invalid point. */
        val EMPTY: RouteRequest = RouteRequest(GeoPoint.ZERO, GeoPoint.ZERO)
    }
}

/**
 * One leg of a planned route: the geometry and the totals between two consecutive stops.
 *
 * @property from First stop of the leg (inclusive).
 * @property to Last stop of the leg (inclusive).
 * @property points Road geometry of the leg, including both [from] and [to].
 * @property distanceMeters Leg length in meters, as reported by the routing backend.
 * @property durationSeconds Leg duration in seconds, as reported by the routing backend.
 */
@Serializable
data class RouteLeg(
    val from: GeoPoint,
    val to: GeoPoint,
    val points: List<GeoPoint> = emptyList(),
    val distanceMeters: Double = 0.0,
    val durationSeconds: Double = 0.0,
)

/**
 * Planned geometry plus everything the UI needs to describe it.
 *
 * The [polyline] is always **densified** (see [RouteGeometry.DEFAULT_MAX_SEGMENT_METERS]): no
 * segment is longer than that, so playback looks like continuous travel instead of teleporting
 * between sparse geometry vertices.
 *
 * @property polyline Full route geometry, in travel order, WGS-84, densified.
 * @property legs Per-leg geometry and totals, one per [RouteRequest.stops] pair.
 * @property distanceMeters Total route length in meters (sum of the leg distances).
 * @property durationSeconds Total duration in seconds (sum of the leg durations).
 * @property source Which backend answered.
 * @property degraded `true` when [source] is [RouteSource.STRAIGHT_LINE] — the route follows no
 *   roads because no backend could be reached.
 * @property message Optional non-blocking explanation for the user (Chinese), e.g. why the
 *   straight-line fallback was used.
 * @property connectorCount How many polyline vertices were forced onto the exact requested stop
 *   coordinates because the router snapped them somewhere else (see [RouteGeometry.assemble]).
 *   `0` means the backend geometry already touched every stop.
 */
@Serializable
data class RouteResult(
    val polyline: List<GeoPoint> = emptyList(),
    val legs: List<RouteLeg> = emptyList(),
    val distanceMeters: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val source: RouteSource = RouteSource.STRAIGHT_LINE,
    val degraded: Boolean = false,
    val message: String? = null,
    val profile: RouteProfile = RouteProfile.WALK,
    val connectorCount: Int = 0,
) {
    /** `true` when the geometry can be drawn / played (at least two distinct points). */
    val isUsable: Boolean
        get() = polyline.size >= 2 && distanceMeters > 0.0

    /** How many waypoints sit between the ends of this route. */
    val waypointCount: Int
        get() = (legs.size - 1).coerceAtLeast(0)

    private val plan: RoutePlan by lazy(LazyThreadSafetyMode.PUBLICATION) {
        RoutePlan(points = polyline, speedMps = profile.defaultSpeedMps)
    }

    /** Total polyline length measured on the geometry itself (meters). */
    fun measuredDistanceMeters(): Double = plan.totalDistanceMeters()

    /**
     * Position and heading at [progress].
     *
     * @param progress Normalized progress; clamped into `0..1`.
     * @return interpolated [GeoPoint] and the bearing of the segment, in degrees `[0,360)`.
     */
    fun sample(progress: Double): Pair<GeoPoint, Double> = plan.sample(progress)

    /** Distance already travelled at [progress] (meters), clamped into `0..1`. */
    fun travelledMeters(progress: Double): Double = plan.distanceAt(progress)

    /** Distance still to travel at [progress] (meters), clamped into `0..1`. */
    fun remainingMeters(progress: Double): Double =
        (measuredDistanceMeters() - travelledMeters(progress)).coerceAtLeast(0.0)

    /**
     * Converts the geometry into a playback plan.
     *
     * @param repeat Whether playback loops.
     * @param speedMps Ground speed; `<= 0` (or non-finite) falls back to the profile default.
     * @param densifyTo Densification step in meters, applied defensively (the polyline is already
     *   densified when it comes from [RouteGeometry]); `<= 0` disables it.
     */
    fun toPlan(
        repeat: Boolean = false,
        speedMps: Double = profile.defaultSpeedMps,
        densifyTo: Double = RouteGeometry.DEFAULT_MAX_SEGMENT_METERS,
    ): RoutePlan {
        val effectiveSpeed =
            if (speedMps.isFinite() && speedMps > 0.0) speedMps else profile.defaultSpeedMps
        return RoutePlan(
            points = RouteGeometry.densify(polyline, densifyTo),
            repeat = repeat,
            speedMps = effectiveSpeed,
        )
    }

    companion object {
        /** A result with no geometry. */
        val EMPTY: RouteResult = RouteResult()
    }
}

/**
 * Pure geometry helpers shared by every routing backend.
 *
 * Kept in `:core` (no Android imports) so the densification / splitting rules are unit-tested on
 * the JVM and every backend produces geometry of the same quality.
 */
object RouteGeometry {

    /**
     * Maximum distance between two consecutive polyline vertices.
     *
     * 10 m is fine enough that a player advancing at walking speed still moves smoothly, while a
     * 20 km drive stays around 2 000 points — small enough to persist, draw and ship over the
     * bridge without trouble.
     */
    const val DEFAULT_MAX_SEGMENT_METERS: Double = 10.0

    /** Safety cap so a pathological request cannot allocate an unbounded polyline. */
    const val MAX_POINTS: Int = 20_000

    /**
     * A stop farther than this from its nearest vertex is considered "snapped away" and gets a
     * connector vertex pinned on it. Half a meter is below any GPS/road tolerance, so points that
     * the backend already matched exactly are left untouched.
     */
    const val CONNECTOR_TOLERANCE_METERS: Double = 0.5

    /**
     * Removes consecutive duplicate points.
     *
     * Zero-length segments carry no bearing, so they would stall playback and break every
     * "distance along the polyline" calculation.
     */
    fun dedupe(points: List<GeoPoint>): List<GeoPoint> {
        if (points.size < 2) return points
        val result = ArrayList<GeoPoint>(points.size)
        for (point in points) {
            if (!point.isValidLatLon()) continue
            val previous = result.lastOrNull()
            if (previous != null && previous.lat == point.lat && previous.lon == point.lon) continue
            result.add(point)
        }
        return result
    }

    /**
     * Inserts intermediate points so no segment is longer than [maxSegmentMeters].
     *
     * Unlike [RouteIO.densify] this also drops invalid points and consecutive duplicates, and it
     * stops once [MAX_POINTS] would be exceeded (returning what it has so far) instead of
     * allocating a huge list.
     *
     * @param points Input polyline; may be unsorted but must be in travel order.
     * @param maxSegmentMeters Upper bound for a single segment. Non-finite or `<= 0` disables
     *   interpolation and only [dedupe] is applied.
     * @return the densified polyline, first and last point preserved.
     */
    fun densify(
        points: List<GeoPoint>,
        maxSegmentMeters: Double = DEFAULT_MAX_SEGMENT_METERS,
    ): List<GeoPoint> {
        val clean = dedupe(points)
        if (clean.size < 2) return clean
        if (!maxSegmentMeters.isFinite() || maxSegmentMeters <= 0.0) return clean
        val result = ArrayList<GeoPoint>(minOf(clean.size * 2, 256))
        result.add(clean[0])
        for (index in 1 until clean.size) {
            val from = clean[index - 1]
            val to = clean[index]
            val distance = GeoMath.distanceMeters(from, to)
            if (distance <= maxSegmentMeters) {
                result.add(to)
                continue
            }
            val steps = kotlin.math.ceil(distance / maxSegmentMeters).toInt().coerceAtLeast(1)
            for (step in 1 until steps) {
                if (result.size >= MAX_POINTS) return result
                result.add(GeoMath.interpolate(from, to, step.toDouble() / steps))
            }
            result.add(to)
            if (result.size >= MAX_POINTS) return result
        }
        return result
    }

    /**
     * Joins per-leg geometry into one polyline, dropping the duplicated vertex between legs.
     *
     * @param legs Legs in travel order. Empty legs are skipped.
     */
    fun join(legs: List<RouteLeg>): List<GeoPoint> {
        val result = ArrayList<GeoPoint>()
        for (leg in legs) {
            val points = if (leg.points.isNotEmpty()) leg.points else listOf(leg.from, leg.to)
            for (point in points) {
                val previous = result.lastOrNull()
                if (previous != null &&
                    previous.lat == point.lat &&
                    previous.lon == point.lon
                ) {
                    continue
                }
                result.add(point)
            }
        }
        return result
    }

    /**
     * Straight-line geometry between every consecutive pair of [request] stops.
     *
     * This is the graceful-degradation result: same API shape as a real answer so the UI,
     * the map and the player need no special case, only a non-blocking notice.
     *
     * @param message User-facing explanation (already localized) for the notice.
     */
    fun straightLine(request: RouteRequest, message: String? = null): RouteResult {
        val stops = request.stops()
        if (stops.size < 2) {
            return RouteResult(
                polyline = stops,
                legs = emptyList(),
                distanceMeters = 0.0,
                durationSeconds = 0.0,
                source = RouteSource.STRAIGHT_LINE,
                degraded = true,
                message = message,
                profile = request.profile,
            )
        }
        val speed = request.profile.defaultSpeedMps
        val legs = ArrayList<RouteLeg>(stops.size - 1)
        for (index in 1 until stops.size) {
            val from = stops[index - 1]
            val to = stops[index]
            val distance = GeoMath.distanceMeters(from, to)
            legs.add(
                RouteLeg(
                    from = from,
                    to = to,
                    points = densify(listOf(from, to)),
                    distanceMeters = distance,
                    durationSeconds = if (speed > 0.0) distance / speed else 0.0,
                ),
            )
        }
        return assemble(
            request = request,
            legs = legs,
            source = RouteSource.STRAIGHT_LINE,
            degraded = true,
            message = message,
        )
    }

    /**
     * Forces the polyline to pass exactly through every requested stop.
     *
     * Routers answer with **road-snapped** coordinates: ask for a point inside 北海公园 and the
     * geometry starts at the nearest drivable road, which can be hundreds of meters away. Since the
     * engine plays the polyline verbatim, that would make the simulation stop outside the park
     * instead of at the place the user picked.
     *
     * The fix keeps the road geometry but pins the vertices at the stop boundaries:
     *  * the first vertex becomes the requested origin,
     *  * the last vertex becomes the requested destination,
     *  * every intermediate waypoint is spliced in at the vertex geometrically closest to it.
     *
     * Only vertices are replaced (never inserted), so the polyline keeps its topology and segment
     * count; the connector segment between the road and the picked point is explicit, exactly like
     * every navigation app draws a short "walk to your destination" stub.
     *
     * @return the adjusted polyline plus how many vertices were moved.
     */
    fun connectStops(polyline: List<GeoPoint>, stops: List<GeoPoint>): Pair<List<GeoPoint>, Int> {
        if (polyline.size < 2 || stops.isEmpty()) return polyline to 0
        val adjusted = polyline.toMutableList()
        var moved = 0
        for ((index, stop) in stops.withIndex()) {
            if (!stop.isValidLatLon()) continue
            val target = when (index) {
                0 -> 0
                stops.lastIndex -> adjusted.lastIndex
                else -> nearestIndex(adjusted, stop)
            }
            val current = adjusted[target]
            if (current.lat == stop.lat && current.lon == stop.lon) continue
            if (GeoMath.distanceMeters(current, stop) <= CONNECTOR_TOLERANCE_METERS) continue
            adjusted[target] = stop
            moved++
        }
        return adjusted to moved
    }

    /** Index of the vertex closest to [point]; `-1` when [polyline] is empty. */
    private fun nearestIndex(polyline: List<GeoPoint>, point: GeoPoint): Int {
        if (polyline.isEmpty()) return -1
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for ((index, candidate) in polyline.withIndex()) {
            val distance = GeoMath.distanceMeters(candidate, point)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
            if (bestDistance == 0.0) break
        }
        return best
    }

    /**
     * Builds a [RouteResult] from finished legs: joins the geometry, densifies it and sums the
     * totals.
     *
     * Totals are taken from the legs (what the backend reported) but never allowed to fall below
     * the length measured on the actual geometry, so a backend that under-reports cannot make the
     * progress bar finish early.
     *
     * @param polyline Backend-supplied full geometry (OSRM answers with one LineString for the
     *   whole route). `null` means "join the leg geometry", which is what a per-leg backend
     *   (高德) produces.
     */
    fun assemble(
        request: RouteRequest,
        legs: List<RouteLeg>,
        source: RouteSource,
        degraded: Boolean = source == RouteSource.STRAIGHT_LINE,
        message: String? = null,
        polyline: List<GeoPoint>? = null,
    ): RouteResult {
        val full = polyline?.takeIf { it.size >= 2 } ?: join(legs)
        // Routers answer with road-snapped coordinates. The engine plays the polyline verbatim, so
        // the stops are pinned onto it first — otherwise the simulation would stop at the road
        // outside the destination instead of at the place the user picked.
        val (connected, connectors) = connectStops(full, request.stops())
        val densified = densify(connected)
        val reportedDistance = legs.sumOf { it.distanceMeters }
        val measured = if (densified.size >= 2) {
            var total = 0.0
            for (index in 1 until densified.size) {
                total += GeoMath.distanceMeters(densified[index - 1], densified[index])
            }
            total
        } else {
            0.0
        }
        val distance = maxOf(reportedDistance, measured)
        var duration = legs.sumOf { it.durationSeconds }
        if (!duration.isFinite() || duration <= 0.0) {
            val speed = request.profile.defaultSpeedMps
            duration = if (speed > 0.0) distance / speed else 0.0
        }
        return RouteResult(
            polyline = densified,
            legs = legs,
            distanceMeters = if (distance.isFinite()) distance else 0.0,
            durationSeconds = duration,
            source = source,
            degraded = degraded,
            message = message,
            profile = request.profile,
            connectorCount = connectors,
        )
    }
}

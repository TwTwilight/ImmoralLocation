package com.immoral.location.core.route

import com.immoral.location.core.assertNear
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Geometry / model tests for route planning.
 *
 * Everything here is pure math on WGS-84 points — no network, no Android, no clock — so the
 * suite runs on the JVM in milliseconds and pins the contract the app's routing client relies on.
 */
class RouteGeometryTest {

    // Reference coordinates (WGS-84). 王府井 → 故宫 → 北海公园 is ~2.6 km, well inside one city.
    private val wangfujing = GeoPoint(39.908_692, 116.397_532, 50.0)
    private val gugong = GeoPoint(39.916_345, 116.397_150, 46.0)
    private val beihai = GeoPoint(39.925_400, 116.383_100, 45.0)

    // ------------------------------------------------------------------ ordering

    @Test
    fun `stops keep travel order origin waypoints destination`() {
        val request = RouteRequest(
            origin = wangfujing,
            destination = beihai,
            waypoints = listOf(gugong),
            profile = RouteProfile.WALK,
        )

        val stops = request.stops()

        assertEquals(3, stops.size)
        assertEquals(wangfujing, stops[0])
        assertEquals(gugong, stops[1])
        assertEquals(beihai, stops[2])
        assertEquals(2, request.legCount())
    }

    @Test
    fun `waypoints keep their declared order instead of being sorted by distance`() {
        // Deliberately visit the far point first: a router that sorts by proximity would swap them.
        val near = GeoPoint(39.910_000, 116.398_000, 50.0)
        val far = GeoPoint(39.960_000, 116.450_000, 50.0)
        val request = RouteRequest(
            origin = wangfujing,
            destination = beihai,
            waypoints = listOf(far, near),
        )

        val stops = request.stops()

        assertEquals(listOf(wangfujing, far, near, beihai), stops)
    }

    @Test
    fun `consecutive duplicates are dropped but non-adjacent repeats are kept`() {
        val request = RouteRequest(
            origin = wangfujing,
            destination = wangfujing,
            // duplicate of the origin, then the real stops, then a genuine return to the start
            waypoints = listOf(wangfujing, gugong, gugong, wangfujing),
        )

        val stops = request.stops()

        assertEquals(listOf(wangfujing, gugong, wangfujing), stops)
    }

    @Test
    fun `invalid waypoints are dropped without disturbing the order`() {
        val request = RouteRequest(
            origin = wangfujing,
            destination = beihai,
            waypoints = listOf(GeoPoint(Double.NaN, 116.0), gugong, GeoPoint(200.0, 400.0)),
        )

        assertEquals(listOf(wangfujing, gugong, beihai), request.stops())
        assertEquals(1, request.validWaypoints.size)
    }

    // ------------------------------------------------------- distance and duration

    @Test
    fun `legs and totals add up`() {
        val request = RouteRequest(wangfujing, beihai, listOf(gugong), RouteProfile.WALK)
        val legs = listOf(
            RouteLeg(wangfujing, gugong, listOf(wangfujing, gugong), 880.0, 640.0),
            RouteLeg(gugong, beihai, listOf(gugong, beihai), 1_720.0, 1_260.0),
        )

        val result = RouteGeometry.assemble(request, legs, RouteSource.OSRM)

        assertEquals(2_600.0, result.distanceMeters)
        assertEquals(1_900.0, result.durationSeconds)
        assertEquals(1, result.waypointCount)
        assertFalse(result.degraded)
        assertTrue(result.isUsable)
        // The joined geometry never repeats the shared vertex between the two legs.
        assertEquals(wangfujing, result.polyline.first())
        assertEquals(beihai, result.polyline.last())
        assertEquals(1, result.polyline.count { it.lat == gugong.lat && it.lon == gugong.lon })
    }

    @Test
    fun `missing backend duration is reconstructed from the profile speed`() {
        val request = RouteRequest(wangfujing, beihai, emptyList(), RouteProfile.DRIVE)
        val distance = GeoMath.distanceMeters(wangfujing, beihai)
        val legs = listOf(RouteLeg(wangfujing, beihai, listOf(wangfujing, beihai), distance, 0.0))

        val result = RouteGeometry.assemble(request, legs, RouteSource.OSRM)

        assertNear(distance / RouteProfile.DRIVE.defaultSpeedMps, result.durationSeconds, 1e-6)
    }

    @Test
    fun `reported totals never undercut the measured geometry`() {
        // A backend that reports 1 m for a 2 km polyline must not make the progress bar finish early.
        val request = RouteRequest(wangfujing, beihai, emptyList(), RouteProfile.WALK)
        val legs = listOf(RouteLeg(wangfujing, beihai, listOf(wangfujing, beihai), 1.0, 1.0))

        val result = RouteGeometry.assemble(request, legs, RouteSource.AMAP)

        assertTrue(
            result.distanceMeters >= GeoMath.distanceMeters(wangfujing, beihai),
            "distance ${result.distanceMeters} must cover the polyline",
        )
    }

    @Test
    fun `waypoint count counts intermediate stops only`() {
        assertEquals(0, RouteResult.EMPTY.waypointCount)
        val request = RouteRequest(wangfujing, beihai, listOf(gugong, beihai), RouteProfile.WALK)
        val stops = request.stops()
        val legs = (1 until stops.size).map { index ->
            RouteLeg(stops[index - 1], stops[index], listOf(stops[index - 1], stops[index]), 10.0, 10.0)
        }

        val result = RouteGeometry.assemble(request, legs, RouteSource.OSRM)

        assertEquals(stops.size - 1, result.legs.size)
        assertEquals(stops.size - 2, result.waypointCount)
    }

    // -------------------------------------------------------------- densification

    @Test
    fun `densify inserts at least one point per ten meters`() {
        val points = RouteGeometry.densify(listOf(wangfujing, beihai))

        assertTrue(points.first() == wangfujing && points.last() == beihai, "endpoints preserved")
        assertTrue(points.size > 100, "expected > 100 vertices for 2.6 km, got ${points.size}")
        var longest = 0.0
        for (index in 1 until points.size) {
            val step = GeoMath.distanceMeters(points[index - 1], points[index])
            if (step > longest) longest = step
        }
        assertTrue(
            longest <= RouteGeometry.DEFAULT_MAX_SEGMENT_METERS + 1e-6,
            "longest segment $longest m exceeds the 10 m budget",
        )
    }

    @Test
    fun `densify is a no-op for short segments and keeps input untouched`() {
        val input = listOf(wangfujing, GeoPoint(wangfujing.lat + 0.000_01, wangfujing.lon, 50.0))
        val points = RouteGeometry.densify(input)

        assertEquals(input, points)
    }

    @Test
    fun `densify drops duplicates and invalid points`() {
        val points = RouteGeometry.densify(
            listOf(wangfujing, wangfujing, GeoPoint(Double.NaN, 0.0), gugong, gugong),
            0.0,
        )

        assertEquals(2, points.size)
        assertEquals(wangfujing, points.first())
        assertEquals(gugong, points.last())

        // With interpolation enabled the same input must still produce one clean, monotone line.
        val interpolated = RouteGeometry.densify(
            listOf(wangfujing, wangfujing, GeoPoint(Double.NaN, 0.0), gugong, gugong),
        )
        assertEquals(wangfujing, interpolated.first())
        assertEquals(gugong, interpolated.last())
        assertTrue(interpolated.size > 2)
        assertEquals(
            interpolated.size,
            interpolated.distinctBy { it.lat to it.lon }.size,
            "densified output must not contain duplicate vertices",
        )
    }

    @Test
    fun `densify with a non-positive step only deduplicates`() {
        val points = RouteGeometry.densify(listOf(wangfujing, beihai), 0.0)

        assertEquals(2, points.size)
    }

    @Test
    fun `densify never exceeds the point cap`() {
        // Cape Town to Beijing: one segment of ~12 000 km would need millions of vertices.
        val capeTown = GeoPoint(-33.924_9, 18.424_1, 0.0)
        val points = RouteGeometry.densify(listOf(capeTown, beihai))

        assertTrue(
            points.size <= RouteGeometry.MAX_POINTS,
            "point cap violated: ${points.size}",
        )
        assertTrue(points.size >= 2)
    }

    // ------------------------------------------------------------------ degenerate

    @Test
    fun `same origin and destination yields an unusable but non-throwing result`() {
        val request = RouteRequest(wangfujing, wangfujing, emptyList(), RouteProfile.WALK)

        assertFalse(request.isUsable)
        assertEquals(0, request.legCount())
        val result = RouteGeometry.straightLine(request, "同一点")

        assertEquals(1, result.polyline.size)
        assertEquals(0.0, result.distanceMeters)
        assertEquals(0.0, result.durationSeconds)
        assertFalse(result.isUsable)
    }

    @Test
    fun `single point request is stable`() {
        val request = RouteRequest(GeoPoint.ZERO, GeoPoint.ZERO)

        assertTrue(request.stops().size <= 1, "nothing to travel between")
        assertEquals(0, request.legCount())
        assertFalse(request.isUsable)
        val result = RouteGeometry.straightLine(request)
        assertEquals(1, result.polyline.size)
        assertEquals(0.0, result.distanceMeters)
    }

    @Test
    fun `sampling a degenerate result does not throw`() {
        val result = RouteGeometry.straightLine(RouteRequest(wangfujing, wangfujing))
        val (point, bearing) = result.sample(0.5)

        assertEquals(wangfujing, point)
        assertEquals(0.0, bearing)
        assertEquals(0.0, result.remainingMeters(0.5))
    }

    @Test
    fun `antimeridian crossing keeps distances short and longitudes wrapped`() {
        val west = GeoPoint(-16.5, 179.95, 0.0)
        val east = GeoPoint(-16.5, -179.95, 0.0)
        val request = RouteRequest(west, east, emptyList(), RouteProfile.WALK)

        val result = RouteGeometry.straightLine(request, null)

        // The short way across the date line is ~11 km; going the other way it would be ~39 000 km.
        assertTrue(result.distanceMeters < 15_000.0, "distance ${result.distanceMeters} m")
        assertTrue(result.distanceMeters > 10_000.0, "distance ${result.distanceMeters} m")
        for (point in result.polyline) {
            assertTrue(point.lon in -180.0..180.0, "longitude out of range: ${point.lon}")
        }
        val middle = result.sample(0.5).first
        assertTrue(abs(middle.lon) > 179.9, "midpoint should sit near the date line, was ${middle.lon}")
    }

    @Test
    fun `results survive a round trip through kotlinx serialization`() {
        val request = RouteRequest(wangfujing, beihai, listOf(gugong), RouteProfile.BIKE)
        val result = RouteGeometry.straightLine(request, "no network")

        val encoded = kotlinx.serialization.json.Json.encodeToString(
            RouteResult.serializer(),
            result,
        )
        val decoded = kotlinx.serialization.json.Json.decodeFromString(
            RouteResult.serializer(),
            encoded,
        )

        assertEquals(result.polyline.size, decoded.polyline.size)
        assertEquals(result.source, decoded.source)
        assertEquals(result.profile, decoded.profile)
        assertEquals(result.distanceMeters, decoded.distanceMeters)
    }

    // ------------------------------------------------------------- degradation

    @Test
    fun `straight line fallback is flagged and still playable`() {
        val request = RouteRequest(wangfujing, beihai, listOf(gugong), RouteProfile.WALK)

        val result = RouteGeometry.straightLine(request, "未取得道路路径，使用直线连接")

        assertEquals(RouteSource.STRAIGHT_LINE, result.source)
        assertTrue(result.degraded)
        assertEquals("未取得道路路径，使用直线连接", result.message)
        assertEquals(2, result.legs.size)
        assertTrue(result.isUsable)
        assertTrue(result.polyline.size > 100, "fallback must be densified too")

        val plan = result.toPlan(speedMps = 2.0)
        assertTrue(plan.isUsable)
        assertNear(2.0, plan.speedMps, 1e-9)
        // Every densified vertex counts exactly once towards the arclength.
        assertNear(
            GeoMath.distanceMeters(wangfujing, gugong) + GeoMath.distanceMeters(gugong, beihai),
            plan.totalDistanceMeters(),
            1.0,
        )
    }

    // --------------------------------------------------------------- progress

    @Test
    fun `progress sampling walks the polyline at constant speed`() {
        val request = RouteRequest(wangfujing, beihai, emptyList(), RouteProfile.WALK)
        val result = RouteGeometry.straightLine(request)

        val total = result.measuredDistanceMeters()
        assertNear(total, result.distanceMeters, 1.0)

        val (start, _) = result.sample(0.0)
        val (end, _) = result.sample(1.0)
        assertEquals(wangfujing, start)
        assertNear(0.0, GeoMath.distanceMeters(end, beihai), 1.0)

        // A quarter of the way in, a quarter of the distance has been travelled.
        val quarter = result.sample(0.25).first
        assertNear(
            total * 0.25,
            GeoMath.distanceMeters(wangfujing, quarter),
            2.0,
        )
        assertNear(total * 0.75, result.remainingMeters(0.25), 1.5)
        assertNear(0.0, result.remainingMeters(1.0), 1e-6)
        assertNear(total, result.remainingMeters(-1.0), 1e-6, "out-of-range progress is clamped")
    }

    @Test
    fun `sample reports the bearing of the current segment`() {
        val north = GeoPoint(39.900_000, 116.400_000, 50.0)
        val south = GeoPoint(39.890_000, 116.400_000, 50.0)
        val result = RouteGeometry.straightLine(RouteRequest(north, south))

        val (_, bearing) = result.sample(0.5)

        assertNear(180.0, bearing, 0.5)
    }

    @Test
    fun `plan speed falls back to the profile default when unusable`() {
        val result = RouteGeometry.straightLine(
            RouteRequest(wangfujing, beihai, emptyList(), RouteProfile.BIKE),
        )

        assertEquals(
            RouteProfile.BIKE.defaultSpeedMps,
            result.toPlan(speedMps = -5.0).speedMps,
        )
        assertEquals(
            RouteProfile.WALK.defaultSpeedMps,
            RouteResult.EMPTY.toPlan(speedMps = Double.NaN).speedMps,
        )
    }

    @Test
    fun `profile ids round trip and unknown ids fall back to walk`() {
        for (profile in RouteProfile.entries) {
            assertEquals(profile, RouteProfile.fromId(profile.id))
        }
        assertEquals(RouteProfile.WALK, RouteProfile.fromId(null))
        assertEquals(RouteProfile.WALK, RouteProfile.fromId("teleport"))
        assertEquals("driving", RouteProfile.DRIVE.wireName)
    }

    @Test
    fun `normalized request keeps the endpoints and drops noise`() {
        val normalized = RouteRequest(
            origin = wangfujing,
            destination = wangfujing,
            waypoints = listOf(gugong, gugong),
        ).normalized()

        assertEquals(wangfujing, normalized.origin)
        assertEquals(wangfujing, normalized.destination)
        assertEquals(listOf(gugong), normalized.waypoints)
    }

    @Test
    fun `empty result helpers are total`() {
        val empty = RouteResult.EMPTY

        assertFalse(empty.isUsable)
        assertEquals(0, empty.waypointCount)
        assertEquals(0.0, empty.measuredDistanceMeters())
        assertEquals(0.0, empty.distanceMeters)
        assertNotNull(empty.sample(0.3).first)
        assertEquals(0.0, empty.remainingMeters(0.3))
    }

    // -------------------------------------------------- snapped stops / connectors

    @Test
    fun `snapped stops are pinned onto the geometry`() {
        // The router answered with road-snapped coordinates: origin 40 m off, destination 220 m off
        // (typical for a park interior), waypoint 60 m off.
        val request = RouteRequest(
            origin = wangfujing,
            destination = beihai,
            waypoints = listOf(gugong),
            profile = RouteProfile.DRIVE,
        )
        val snappedOrigin = GeoMath.destination(wangfujing, 90.0, 40.0)
        val snappedWaypoint = GeoMath.destination(gugong, 0.0, 60.0)
        val snappedDestination = GeoMath.destination(beihai, 180.0, 220.0)
        val legs = listOf(
            RouteLeg(
                from = snappedOrigin,
                to = snappedWaypoint,
                points = listOf(
                    snappedOrigin,
                    GeoMath.destination(snappedOrigin, 0.0, 300.0),
                    snappedWaypoint,
                ),
                distanceMeters = 900.0,
                durationSeconds = 90.0,
            ),
            RouteLeg(
                from = snappedWaypoint,
                to = snappedDestination,
                points = listOf(
                    snappedWaypoint,
                    GeoMath.destination(snappedWaypoint, 0.0, 300.0),
                    snappedDestination,
                ),
                distanceMeters = 1_100.0,
                durationSeconds = 110.0,
            ),
        )

        val result = RouteGeometry.assemble(request, legs, RouteSource.OSRM)

        assertEquals(wangfujing.lat, result.polyline.first().lat, 1e-9)
        assertEquals(wangfujing.lon, result.polyline.first().lon, 1e-9)
        assertEquals(beihai.lat, result.polyline.last().lat, 1e-9)
        assertEquals(beihai.lon, result.polyline.last().lon, 1e-9)
        assertTrue(
            result.polyline.any { it.lat == gugong.lat && it.lon == gugong.lon },
            "the waypoint must be on the polyline",
        )
        assertEquals(3, result.connectorCount)
        // The road geometry between the stops is untouched, and still densified.
        assertTrue(result.polyline.size > 100)
        assertEquals(RouteSource.OSRM, result.source)
    }

    @Test
    fun `stops already on the geometry produce no connectors`() {
        val request = RouteRequest(wangfujing, beihai, emptyList(), RouteProfile.WALK)
        val legs = listOf(
            RouteLeg(
                from = wangfujing,
                to = beihai,
                points = listOf(wangfujing, GeoMath.destination(wangfujing, 0.0, 200.0), beihai),
                distanceMeters = 2_000.0,
                durationSeconds = 1_400.0,
            ),
        )

        val result = RouteGeometry.assemble(request, legs, RouteSource.OSRM)

        assertEquals(0, result.connectorCount)
        assertEquals(wangfujing, result.polyline.first())
        assertEquals(beihai, result.polyline.last())
    }

    @Test
    fun `connectStops is a no-op for degenerate input`() {
        val single = listOf(wangfujing)
        assertEquals(single to 0, RouteGeometry.connectStops(single, listOf(wangfujing, beihai)))

        val line = listOf(wangfujing, beihai)
        val (unchanged, moved) = RouteGeometry.connectStops(line, emptyList())
        assertEquals(line, unchanged)
        assertEquals(0, moved)
    }
}

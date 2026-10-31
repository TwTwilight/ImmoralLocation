package com.immoral.location.net

import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.route.RouteGeometry
import com.immoral.location.core.route.RouteProfile
import com.immoral.location.core.route.RouteRequest
import com.immoral.location.core.route.RouteSource
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline tests for the OSRM backend and the backend ordering in [RouteClient].
 *
 * The JSON fixture in `src/test/resources/osrm_beijing_driving.json` is a **real** response of
 * `https://router.project-osrm.org/route/v1/driving/...` for 王府井 → 故宫 → 北海公园 (captured once
 * by hand); the tests replay it through a fake [HttpFetcher], so they prove the client parses a
 * production answer into road-following geometry without ever touching the network.
 */
class RouteClientTest {

    // Real 北京 coordinates (WGS-84). The waypoint makes the road distance clearly longer than the
    // straight line, which is what "follows roads" means in a testable sense.
    private val wangfujing = GeoPoint(39.908_692, 116.397_532, 50.0)
    private val gugong = GeoPoint(39.916_345, 116.397_150, 46.0)
    private val beihai = GeoPoint(39.925_400, 116.383_100, 45.0)

    private val fixture: String by lazy {
        val stream = javaClass.getResourceAsStream("/osrm_beijing_driving.json")
        assertNotNull("missing test fixture osrm_beijing_driving.json", stream)
        stream!!.bufferedReader().use { it.readText() }
    }

    private class FakeFetcher(
        private val body: String?,
        private val onRequest: (String) -> Unit = {},
    ) : HttpFetcher {
        var lastUrl: String? = null
        override fun get(url: String, timeoutMs: Int): String? {
            lastUrl = url
            onRequest(url)
            return body
        }
    }

    private fun client(fetcher: HttpFetcher) =
        RouteClient(amap = null, osrm = OsrmRouteService(fetcher))

    private fun assertNear(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(
            "expected $expected ± $tolerance but was $actual",
            abs(expected - actual) <= tolerance,
        )
    }

    // ------------------------------------------------------------------ URL shape

    @Test
    fun urlCarriesEveryWaypointInOrderWithLonLat() {
        val service = OsrmRouteService(FakeFetcher(null))
        val request = RouteRequest(
            origin = wangfujing,
            destination = beihai,
            waypoints = listOf(gugong),
            profile = RouteProfile.DRIVE,
        )

        val url = service.buildUrl(request.stops(), request.profile)

        assertTrue(url, url.contains("/route/v1/driving/"))
        assertTrue(
            "waypoint order or lon/lat order is wrong: $url",
            url.contains("116.397532,39.908692;116.397150,39.916345;116.383100,39.925400"),
        )
        assertTrue(url, url.contains("overview=full"))
        assertTrue(url, url.contains("geometries=geojson"))
    }

    @Test
    fun walkAndBikeMapToOsrmProfileNames() {
        val service = OsrmRouteService(FakeFetcher(null))
        val stops = listOf(wangfujing, beihai)

        assertTrue(service.buildUrl(stops, RouteProfile.WALK).contains("/route/v1/walking/"))
        assertTrue(service.buildUrl(stops, RouteProfile.BIKE).contains("/route/v1/cycling/"))
    }

    // ------------------------------------------------------- real response parsing

    @Test
    fun realResponseBecomesRoadFollowingDensifiedGeometry() {
        val request = RouteRequest(wangfujing, beihai, listOf(gugong), RouteProfile.DRIVE)

        val outcome = client(FakeFetcher(fixture)).plan(request)
        val result = outcome.result

        assertEquals(RouteSource.OSRM, result.source)
        assertFalse(outcome.degraded)
        assertTrue("unexpected failures: ${outcome.failures}", outcome.failures.isEmpty())
        assertEquals(RouteProfile.DRIVE, result.profile)

        // Totals come from the backend (the real answer is 2922.4 m over 2 legs) but never from
        // less than the geometry actually played: pinning the park-interior destination adds a
        // ~190 m connector stub, which the simulation really walks, so the total is the larger of
        // the two.
        assertEquals(2, result.legs.size)
        assertTrue("distance ${result.distanceMeters}", result.distanceMeters > 2_800.0)
        assertTrue("distance ${result.distanceMeters}", result.distanceMeters < 3_400.0)
        assertTrue("duration ${result.durationSeconds}", result.durationSeconds > 250.0)
        assertNear(
            result.measuredDistanceMeters(),
            result.distanceMeters,
            1.0,
        )

        // Densified to the 10 m budget.
        assertTrue(
            "expected densification, got ${result.polyline.size}",
            result.polyline.size > 51,
        )
        var longest = 0.0
        for (index in 1 until result.polyline.size) {
            val step = GeoMath.distanceMeters(result.polyline[index - 1], result.polyline[index])
            if (step > longest) longest = step
        }
        assertTrue("longest segment $longest m", longest <= RouteGeometry.DEFAULT_MAX_SEGMENT_METERS + 1e-6)

        // The polyline is a road route, not a straight line: it detours well past the direct legs.
        val direct =
            GeoMath.distanceMeters(wangfujing, gugong) + GeoMath.distanceMeters(gugong, beihai)
        assertTrue(
            "measured ${result.measuredDistanceMeters()} m vs direct $direct m — not road level",
            result.measuredDistanceMeters() > direct * 1.05,
        )

        // It starts and ends EXACTLY on the requested stops. OSRM snaps 北海公园 to the nearest
        // drivable road (>150 m away in this fixture), so the assembler must pin connector
        // vertices — the simulation has to arrive at the place the user picked, not at the road.
        assertEquals(wangfujing.lat, result.polyline.first().lat, 1e-9)
        assertEquals(wangfujing.lon, result.polyline.first().lon, 1e-9)
        assertEquals(beihai.lat, result.polyline.last().lat, 1e-9)
        assertEquals(beihai.lon, result.polyline.last().lon, 1e-9)
        assertTrue(
            "the destination was snapped by the router, so a connector must exist " +
                "(connectorCount=${result.connectorCount})",
            result.connectorCount >= 1,
        )
        // The waypoint is pinned too, so the route really visits it.
        assertTrue(
            "the intermediate waypoint is missing from the geometry",
            result.polyline.any { it.lat == gugong.lat && it.lon == gugong.lon },
        )

        // Playback follows the same geometry.
        val plan = result.toPlan(speedMps = 8.0)
        assertNear(result.measuredDistanceMeters(), plan.totalDistanceMeters(), 1.0)
        val halfway = result.sample(0.5).first
        assertTrue(GeoMath.distanceMeters(wangfujing, halfway) > 100.0)
    }

    @Test
    fun legsKeepTheTravelOrderOfTheStops() {
        val request = RouteRequest(wangfujing, beihai, listOf(gugong), RouteProfile.DRIVE)

        val result = client(FakeFetcher(fixture)).plan(request).result

        assertEquals(2, result.legs.size)
        assertEquals(wangfujing, result.legs[0].from)
        assertEquals(gugong, result.legs[0].to)
        assertEquals(gugong, result.legs[1].from)
        assertEquals(beihai, result.legs[1].to)
        assertTrue(result.legs.all { it.distanceMeters > 0.0 })
        // The legs carry the backend's road distances (2922.4 m in total); the played geometry is
        // never shorter than that because connector stubs are appended to reach the picked stops.
        val legDistance = result.legs.sumOf { it.distanceMeters }
        assertNear(2_922.4, legDistance, 5.0)
        assertTrue(
            "total ${result.distanceMeters} must cover the legs ($legDistance)",
            result.distanceMeters >= legDistance,
        )
        assertNear(result.measuredDistanceMeters(), result.distanceMeters, 1.0)
    }

    @Test
    fun walkProfileRecomputesTheCarProfileDuration() {
        // `router.project-osrm.org` serves the CAR profile for every mode: the fixture's 321.7 s
        // for 2922.4 m is 9.1 m/s, which would claim this 3 km walk takes five minutes.
        val request = RouteRequest(wangfujing, beihai, listOf(gugong), RouteProfile.WALK)

        val result = client(FakeFetcher(fixture)).plan(request).result

        val roadDistance = result.legs.sumOf { it.distanceMeters }
        assertNear(roadDistance / RouteProfile.WALK.defaultSpeedMps, result.durationSeconds, 2.0)
        assertTrue(
            "a walking ETA must be walking-paced, was ${result.durationSeconds} s",
            result.durationSeconds > 1_800.0,
        )
        // …and it must not be the car answer (321.7 s) dressed up as a walk.
        assertTrue(result.durationSeconds > 321.7 * 4)
    }

    @Test
    fun bikeProfileRecomputesAndDriveProfileKeepsTheServerDuration() {
        val bike = client(FakeFetcher(fixture))
            .plan(RouteRequest(wangfujing, beihai, listOf(gugong), RouteProfile.BIKE))
            .result
        val roadDistance = bike.legs.sumOf { it.distanceMeters }
        assertNear(roadDistance / RouteProfile.BIKE.defaultSpeedMps, bike.durationSeconds, 2.0)

        // Driving is the profile the public server actually runs, so its duration is trusted.
        val drive = client(FakeFetcher(fixture))
            .plan(RouteRequest(wangfujing, beihai, listOf(gugong), RouteProfile.DRIVE))
            .result
        assertNear(321.7, drive.durationSeconds, 2.0)
    }

    // -------------------------------------------------------------- degradation

    @Test
    fun networkFailureDegradesToADensifiedStraightLine() {
        val outcome = client(FakeFetcher(null)).plan(RouteRequest(wangfujing, beihai))

        assertEquals(RouteSource.STRAIGHT_LINE, outcome.result.source)
        assertTrue(outcome.degraded)
        assertTrue("${outcome.failures}", outcome.failures.contains(RouteFailure.OsrmFailed))
        assertTrue(outcome.result.isUsable)
        assertTrue(outcome.result.polyline.size > 100)
        assertTrue(outcome.result.distanceMeters > 0.0)
    }

    @Test
    fun osrmErrorCodeIsReportedAsFailure() {
        val body = """{"code":"NoRoute","message":"Impossible route between points"}"""

        val outcome = client(FakeFetcher(body)).plan(RouteRequest(wangfujing, beihai))

        assertEquals(RouteSource.STRAIGHT_LINE, outcome.result.source)
        assertTrue("${outcome.failures}", outcome.failures.contains(RouteFailure.OsrmFailed))
        // The technical reason is preserved for the log; the UI decides whether to show it.
        assertTrue(
            "${outcome.failures}",
            outcome.failures.filterIsInstance<RouteFailure.Other>()
                .any { it.reason.contains("NoRoute") },
        )
    }

    @Test
    fun garbageResponseDoesNotThrowAndDegrades() {
        val outcome = client(FakeFetcher("<html>502 Bad Gateway</html>"))
            .plan(RouteRequest(wangfujing, beihai))

        assertEquals(RouteSource.STRAIGHT_LINE, outcome.result.source)
        assertTrue(outcome.result.isUsable)
    }

    @Test
    fun sameStartAndEndIsReportedAsNoRouteWithoutAnyRequest() {
        var requested = false
        val fetcher = FakeFetcher(fixture) { requested = true }

        val outcome = client(fetcher).plan(RouteRequest(wangfujing, wangfujing))

        assertFalse("a degenerate request must not hit the network", requested)
        assertEquals(listOf(RouteFailure.NoRoute), outcome.failures)
        assertEquals(RouteSource.STRAIGHT_LINE, outcome.result.source)
        assertFalse(outcome.result.isUsable)
    }

    @Test
    fun aClientWithoutAnyBackendStillReturnsPlayableGeometry() {
        val outcome = RouteClient(amap = null, osrm = null).plan(RouteRequest(wangfujing, beihai))

        assertTrue(outcome.result.isUsable)
        assertEquals(RouteSource.STRAIGHT_LINE, outcome.result.source)
        assertTrue(outcome.degraded)
    }

    @Test
    fun antimeridianRequestStaysFinite() {
        val west = GeoPoint(-16.5, 179.95)
        val east = GeoPoint(-16.5, -179.95)

        val outcome = client(FakeFetcher(null))
            .plan(RouteRequest(west, east, emptyList(), RouteProfile.WALK))

        assertTrue(
            "${outcome.result.distanceMeters}",
            outcome.result.distanceMeters in 10_000.0..15_000.0,
        )
        for (point in outcome.result.polyline) {
            assertTrue(abs(point.lon) <= 180.0)
        }
    }
}

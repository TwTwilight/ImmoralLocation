package com.immoral.location.core.route

import com.immoral.location.core.Reference
import com.immoral.location.core.assertNear
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RoutePlan sampling and GPX import/export. */
class RoutePlanTest {
    private val twoPoints = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001))

    @Test
    fun totalDistanceIsThePolylineLength() {
        val plan = RoutePlan(twoPoints)
        assertNear(GeoMath.distanceMeters(twoPoints[0], twoPoints[1]), plan.totalDistanceMeters(), 1e-9)
    }

    @Test
    fun totalDistanceSumsAllSegments() {
        val points = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.001),
            GeoPoint(0.001, 0.001),
        )
        val expected = GeoMath.distanceMeters(points[0], points[1]) +
            GeoMath.distanceMeters(points[1], points[2])
        assertNear(expected, RoutePlan(points).totalDistanceMeters(), 1e-9)
    }

    @Test
    fun degeneratePlansHaveZeroLength() {
        assertEquals(0.0, RoutePlan(emptyList()).totalDistanceMeters())
        assertEquals(0.0, RoutePlan(listOf(GeoPoint(1.0, 1.0))).totalDistanceMeters())
    }

    @Test
    fun sampleHitsBothEndpoints() {
        val plan = RoutePlan(twoPoints)
        assertEquals(twoPoints[0], plan.sample(0.0).first)
        assertEquals(twoPoints[1], plan.sample(1.0).first)
    }

    @Test
    fun sampleAtHalfwayIsTheGeometricMiddle() {
        val plan = RoutePlan(twoPoints)
        val (point, bearing) = plan.sample(0.5)
        assertNear(0.0005, point.lon, 1e-7)
        assertNear(0.0, point.lat, 1e-9)
        assertNear(90.0, bearing, 1e-6)
    }

    @Test
    fun sampleIsArclengthBasedNotIndexBased() {
        // A long first segment must dominate the sampling: 0.5 is still inside it.
        val points = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.003), // 3x longer
            GeoPoint(0.001, 0.003),
        )
        val plan = RoutePlan(points)
        val (point, _) = plan.sample(0.5)
        assertNear(0.002, point.lon, 1e-6, "half of the route is inside the first segment")
        assertNear(0.0, point.lat, 1e-6)
    }

    @Test
    fun sampleClampsOutOfRangeProgress() {
        val plan = RoutePlan(twoPoints)
        assertEquals(twoPoints[0], plan.sample(-1.0).first)
        assertEquals(twoPoints[1], plan.sample(2.0).first)
        assertEquals(twoPoints[0], plan.sample(Double.NaN).first)
    }

    @Test
    fun sampleOfDegeneratePlansIsSafe() {
        assertEquals(GeoPoint.ZERO, RoutePlan(emptyList()).sample(0.5).first)
        val single = GeoPoint(3.0, 4.0)
        assertEquals(single, RoutePlan(listOf(single)).sample(0.5).first)
    }

    @Test
    fun bearingFollowsTheSegment() {
        val north = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.001, 0.0))
        assertNear(0.0, RoutePlan(north).sample(0.5).second, 1e-6)
        val south = listOf(GeoPoint(0.001, 0.0), GeoPoint(0.0, 0.0))
        assertNear(180.0, RoutePlan(south).sample(0.5).second, 1e-6)
    }

    @Test
    fun distanceAtScalesWithProgress() {
        val plan = RoutePlan(twoPoints)
        assertEquals(0.0, plan.distanceAt(0.0))
        assertNear(plan.totalDistanceMeters(), plan.distanceAt(1.0), 1e-9)
        assertNear(plan.totalDistanceMeters() / 2.0, plan.distanceAt(0.5), 1e-9)
    }

    @Test
    fun normalizedRemovesConsecutiveDuplicates() {
        val duplicated = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.001),
            GeoPoint(0.0, 0.001),
        )
        assertEquals(2, RoutePlan(duplicated).normalized().points.size)
    }

    @Test
    fun isUsableNeedsTwoPoints() {
        assertTrue(RoutePlan(twoPoints).isUsable)
        assertTrue(!RoutePlan(listOf(GeoPoint(1.0, 1.0))).isUsable)
        assertTrue(!RoutePlan.EMPTY.isUsable)
    }
}

/** GPX parsing, serialization and densification. */
class RouteIOTest {
    private val gpx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="unit-test" xmlns="http://www.topografix.com/GPX/1/1">
          <wpt lat="39.9000" lon="116.4000"><ele>44.0</ele></wpt>
          <trk>
            <name>morning run</name>
            <trkseg>
              <trkpt lat="39.9042" lon="116.4074"><ele>43.5</ele></trkpt>
              <trkpt lat="39.9052" lon="116.4084"/>
            </trkseg>
          </trk>
          <rte>
            <rtept lat="31.2304" lon="121.4737"></rtept>
          </rte>
        </gpx>
    """.trimIndent()

    @Test
    fun parsesTrackRouteAndWaypointElementsInOrder() {
        val points = RouteIO.parseGpx(gpx)
        assertEquals(4, points.size)
        assertEquals(39.9000, points[0].lat, 1e-9)
        assertEquals(39.9042, points[1].lat, 1e-9)
        assertEquals(39.9052, points[2].lat, 1e-9)
        assertEquals(31.2304, points[3].lat, 1e-9)
    }

    @Test
    fun parsesElevationAndFallsBackToTheDefault() {
        val points = RouteIO.parseGpx(gpx)
        assertEquals(44.0, points[0].alt, 1e-9)
        assertEquals(43.5, points[1].alt, 1e-9)
        assertEquals(50.0, points[2].alt, 1e-9, "missing <ele> uses the default altitude")
    }

    @Test
    fun parsesNamespacedTagsAndSingleQuotedAttributes() {
        val xml = """
            <gpx:gpx xmlns:gpx="http://www.topografix.com/GPX/1/1">
              <gpx:trkpt lat='12.5' lon='-34.25'><gpx:ele>7.5</gpx:ele></gpx:trkpt>
            </gpx:gpx>
        """.trimIndent()
        val points = RouteIO.parseGpx(xml)
        assertEquals(1, points.size)
        assertEquals(12.5, points[0].lat, 1e-9)
        assertEquals(-34.25, points[0].lon, 1e-9)
        assertEquals(7.5, points[0].alt, 1e-9)
    }

    @Test
    fun ignoresGarbageAndInvalidCoordinates() {
        assertTrue(RouteIO.parseGpx("").isEmpty())
        assertTrue(RouteIO.parseGpx("definitely not xml").isEmpty())
        assertTrue(RouteIO.parseGpx("<gpx><trkpt lat=\"abc\" lon=\"1\"/></gpx>").isEmpty())
        assertTrue(RouteIO.parseGpx("<gpx><trkpt lat=\"91\" lon=\"1\"/></gpx>").isEmpty())
        assertTrue(RouteIO.parseGpx("<gpx><trkpt lon=\"1\"/></gpx>").isEmpty())
    }

    @Test
    fun emptyTrackSegmentsAreTolerated() {
        val xml = "<gpx><trk><trkseg></trkseg></trk></gpx>"
        assertTrue(RouteIO.parseGpx(xml).isEmpty())
    }

    @Test
    fun serializationRoundTripsThroughTheParser() {
        val points = listOf(
            Reference.BEIJING,
            Reference.SHANGHAI,
            Reference.GUANGZHOU,
        )
        val xml = RouteIO.toGpx(points, "unit test route")
        assertTrue(xml.contains("<name>unit test route</name>"))
        assertTrue(xml.contains("<trkpt lat=\"39.904200\""), "expected formatted latitude")
        val parsed = RouteIO.parseGpx(xml)
        assertEquals(points.size, parsed.size)
        for (index in points.indices) {
            assertNear(
                0.0,
                GeoMath.distanceMeters(points[index], parsed[index]),
                0.2,
                "point $index drifted",
            )
        }
    }

    @Test
    fun nameIsXmlEscaped() {
        val xml = RouteIO.toGpx(listOf(Reference.BEIJING), "a & b <c>")
        assertTrue(xml.contains("a &amp; b &lt;c&gt;"))
    }

    @Test
    fun densifySplitsLongSegments() {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001)) // ~111 m
        val densified = RouteIO.densify(points, maxSegmentMeters = 25.0)
        // ceil(111.19 / 25) == 5 steps => 4 inserted points plus both endpoints.
        assertEquals(6, densified.size)
        for (index in 1 until densified.size) {
            val segment = GeoMath.distanceMeters(densified[index - 1], densified[index])
            assertTrue(segment <= 25.0 + 1e-6, "segment $index is ${segment} m")
        }
        assertEquals(points.first(), densified.first())
        assertEquals(points.last(), densified.last())
    }

    @Test
    fun densifyKeepsShortSegmentsUntouched() {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0001)) // ~11 m
        assertEquals(points, RouteIO.densify(points, maxSegmentMeters = 25.0))
    }

    @Test
    fun densifyIsANoOpForDegenerateThresholds() {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001))
        assertEquals(points, RouteIO.densify(points, maxSegmentMeters = 0.0))
        assertEquals(points, RouteIO.densify(points, maxSegmentMeters = -5.0))
        assertEquals(points, RouteIO.densify(points, maxSegmentMeters = Double.NaN))
    }

    @Test
    fun densifyKeepsPointOrderAcrossMultipleSegments() {
        val points = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.001),
            GeoPoint(0.001, 0.001),
        )
        val densified = RouteIO.densify(points, maxSegmentMeters = 30.0)
        assertTrue(densified.size > points.size)
        var total = 0.0
        for (index in 1 until densified.size) {
            total += GeoMath.distanceMeters(densified[index - 1], densified[index])
        }
        assertNear(RoutePlan(points).totalDistanceMeters(), total, 0.5)
    }
}

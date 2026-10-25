package com.immoral.location.core.geo

import com.immoral.location.core.EPS
import com.immoral.location.core.Reference
import com.immoral.location.core.assertNear
import com.immoral.location.core.assertSamePoint
import com.immoral.location.core.model.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Geodesy accuracy tests against independently known reference values. */
class GeoMathTest {
    /** One degree of latitude on the mean-radius sphere is about 111.19 km. */
    @Test
    fun oneDegreeOfLatitudeIsAbout1112Kilometers() {
        val distance = GeoMath.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0))
        assertNear(111_194.93, distance, 111_194.93 * 0.005, "1° latitude")
    }

    /** One degree of longitude at the equator equals one degree of latitude on a sphere. */
    @Test
    fun oneDegreeOfLongitudeAtEquatorMatchesLatitude() {
        val alongLat = GeoMath.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0))
        val alongLon = GeoMath.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
        assertNear(alongLat, alongLon, 1e-6)
    }

    /** Longitude degrees shrink with the cosine of the latitude. */
    @Test
    fun longitudeDegreeShrinksWithLatitude() {
        val distance = GeoMath.distanceMeters(GeoPoint(60.0, 0.0), GeoPoint(60.0, 1.0))
        assertNear(111_194.93 * 0.5, distance, 500.0, "1° longitude at 60°N")
    }

    /** Beijing -> Shanghai is about 1067 km in reality; the sphere model must stay within 0.5 %. */
    @Test
    fun beijingToShanghaiDistanceIsPlausible() {
        val distance = GeoMath.distanceMeters(Reference.BEIJING, Reference.SHANGHAI)
        assertTrue(distance in 1_058_000.0..1_075_000.0, "Beijing-Shanghai was $distance m")
    }

    /** Beijing -> Shanghai heads south-east, roughly 153°. */
    @Test
    fun beijingToShanghaiBearingIsSouthEast() {
        val bearing = GeoMath.bearingDegrees(Reference.BEIJING, Reference.SHANGHAI)
        assertTrue(bearing in 145.0..162.0, "bearing was $bearing")
    }

    /** The haversine result agrees with an independent law-of-cosines computation. */
    @Test
    fun haversineAgreesWithLawOfCosines() {
        val haversine = GeoMath.distanceMeters(Reference.NEW_YORK, Reference.BEIJING)
        val lawOfCosines = GeoMath.distanceMetersLawOfCosines(Reference.NEW_YORK, Reference.BEIJING)
        assertNear(lawOfCosines, haversine, 1.0, "NYC-Beijing")
    }

    /** Distance is symmetric and zero for identical points. */
    @Test
    fun distanceIsSymmetricAndZeroForIdenticalPoints() {
        assertEquals(0.0, GeoMath.distanceMeters(Reference.BEIJING, Reference.BEIJING))
        val forward = GeoMath.distanceMeters(Reference.BEIJING, Reference.SHANGHAI)
        val backward = GeoMath.distanceMeters(Reference.SHANGHAI, Reference.BEIJING)
        assertNear(forward, backward, 1e-6)
    }

    /** Distance is always non-negative, even for antipodal-ish input. */
    @Test
    fun distanceIsNeverNegative() {
        assertTrue(GeoMath.distanceMeters(GeoPoint(89.9, 179.9), GeoPoint(-89.9, -179.9)) > 0.0)
        assertTrue(GeoMath.distanceMeters(GeoPoint.ZERO, GeoPoint(0.0, 0.0)) == 0.0)
    }

    /** Cardinal bearings come out exactly at 0/90/180/270. */
    @Test
    fun cardinalBearingsAreExact() {
        val origin = GeoPoint(0.0, 0.0)
        assertEquals(0.0, GeoMath.bearingDegrees(origin, GeoPoint(1.0, 0.0)), 1e-6, "north")
        assertEquals(90.0, GeoMath.bearingDegrees(origin, GeoPoint(0.0, 1.0)), 1e-6, "east")
        assertEquals(180.0, GeoMath.bearingDegrees(origin, GeoPoint(-1.0, 0.0)), 1e-6, "south")
        assertEquals(270.0, GeoMath.bearingDegrees(origin, GeoPoint(0.0, -1.0)), 1e-6, "west")
    }

    /** The bearing of a zero-length hop is defined as due north instead of NaN. */
    @Test
    fun bearingOfIdenticalPointsIsZero() {
        assertEquals(0.0, GeoMath.bearingDegrees(Reference.BEIJING, Reference.BEIJING))
    }

    /** Bearings are always normalized into `[0, 360)`. */
    @Test
    fun bearingIsAlwaysNormalized() {
        val bearing = GeoMath.bearingDegrees(GeoPoint(-10.0, -10.0), GeoPoint(-11.0, -9.0))
        assertTrue(bearing >= 0.0 && bearing < 360.0, "bearing out of range: $bearing")
    }

    /** Travelling east by ~1 km moves the longitude by the expected amount. */
    @Test
    fun destinationEastOneKilometer() {
        val moved = GeoMath.destination(GeoPoint(0.0, 0.0), 90.0, 1_000.0)
        assertNear(0.0, moved.lat, 1e-9, "latitude must stay on the equator")
        assertNear(0.008993, moved.lon, 1e-5, "longitude shift")
    }

    /** Travelling north by ~1.11 km moves the latitude by exactly one degree. */
    @Test
    fun destinationNorthOneDegree() {
        val moved = GeoMath.destination(GeoPoint(0.0, 0.0), 0.0, 111_194.93)
        assertNear(1.0, moved.lat, 1e-4)
        assertNear(0.0, moved.lon, 1e-6)
    }

    /** A destination followed by a distance measurement round-trips. */
    @Test
    fun destinationAndDistanceRoundTrip() {
        for (bearing in 0..350 step 10) {
            val moved = GeoMath.destination(Reference.BEIJING, bearing.toDouble(), 500.0)
            assertNear(
                500.0,
                GeoMath.distanceMeters(Reference.BEIJING, moved),
                0.01,
                "bearing $bearing",
            )
            assertNear(
                bearing.toDouble(),
                GeoMath.bearingDegrees(Reference.BEIJING, moved),
                0.05,
                "bearing $bearing",
            )
        }
    }

    /** Moving a negative distance goes the other way. */
    @Test
    fun destinationWithNegativeDistanceMovesBackwards() {
        val forward = GeoMath.destination(GeoPoint(0.0, 0.0), 90.0, 100.0)
        val backward = GeoMath.destination(GeoPoint(0.0, 0.0), 90.0, -100.0)
        assertNear(200.0, GeoMath.distanceMeters(forward, backward), 0.001)
    }

    /** A zero-length destination returns the origin unchanged. */
    @Test
    fun destinationWithZeroDistanceIsIdentity() {
        val moved = GeoMath.destination(Reference.BEIJING, 123.0, 0.0)
        assertEquals(Reference.BEIJING, moved)
    }

    /** Altitude is carried over by destination. */
    @Test
    fun destinationPreservesAltitude() {
        val moved = GeoMath.destination(Reference.BEIJING, 45.0, 10.0)
        assertEquals(Reference.BEIJING.alt, moved.alt)
    }

    /** Longitude wraps over the antimeridian instead of leaving the valid range. */
    @Test
    fun destinationCrossesAntimeridianCleanly() {
        val moved = GeoMath.destination(GeoPoint(0.0, 179.99), 90.0, 10_000.0)
        assertTrue(moved.lon in -180.0..180.0, "longitude out of range: ${moved.lon}")
        assertTrue(moved.lon < 0.0, "expected wrap to the western hemisphere, got ${moved.lon}")
    }

    /** normalizeBearing maps into `[0,360)` for all quadrants. */
    @Test
    fun normalizeBearingWrapsIntoRange() {
        assertEquals(0.0, GeoMath.normalizeBearing(0.0))
        assertEquals(270.0, GeoMath.normalizeBearing(-90.0))
        assertEquals(0.0, GeoMath.normalizeBearing(360.0))
        assertEquals(0.5, GeoMath.normalizeBearing(720.5))
        assertEquals(359.0, GeoMath.normalizeBearing(-1.0))
        assertEquals(180.0, GeoMath.normalizeBearing(180.0))
    }

    /** Non-finite bearings collapse to zero instead of NaN. */
    @Test
    fun normalizeBearingHandlesNonFinite() {
        assertEquals(0.0, GeoMath.normalizeBearing(Double.NaN))
        assertEquals(0.0, GeoMath.normalizeBearing(Double.POSITIVE_INFINITY))
    }

    /** Shortest angular delta picks the small arc, in both directions. */
    @Test
    fun shortestAngleDeltaPicksTheSmallArc() {
        assertEquals(20.0, GeoMath.shortestAngleDelta(350.0, 10.0), EPS)
        assertEquals(-20.0, GeoMath.shortestAngleDelta(10.0, 350.0), EPS)
        assertEquals(0.0, GeoMath.shortestAngleDelta(45.0, 45.0), EPS)
        assertEquals(180.0, GeoMath.shortestAngleDelta(0.0, 180.0), EPS)
        assertEquals(-90.0, GeoMath.shortestAngleDelta(90.0, 0.0), EPS)
    }

    /** The delta is always within `[-180,180]`. */
    @Test
    fun shortestAngleDeltaIsBounded() {
        for (from in 0..350 step 17) {
            for (to in 0..350 step 23) {
                val delta = GeoMath.shortestAngleDelta(from.toDouble(), to.toDouble())
                assertTrue(delta >= -180.0 && delta <= 180.0, "$from -> $to gave $delta")
            }
        }
    }

    /** Interpolation at 0, 0.5 and 1 hits the endpoints and the midpoint. */
    @Test
    fun interpolateAtRatios() {
        val a = GeoPoint(0.0, 0.0, 10.0)
        val b = GeoPoint(10.0, 20.0, 30.0)
        assertSamePoint(a, GeoMath.interpolate(a, b, 0.0))
        assertSamePoint(b, GeoMath.interpolate(a, b, 1.0))
        val middle = GeoMath.interpolate(a, b, 0.5)
        assertNear(5.0, middle.lat, 1e-9)
        assertNear(10.0, middle.lon, 1e-9)
        assertNear(20.0, middle.alt, 1e-9)
    }

    /** Interpolation across the antimeridian takes the short path. */
    @Test
    fun interpolateAcrossAntimeridian() {
        val middle = GeoMath.interpolate(GeoPoint(0.0, 179.0), GeoPoint(0.0, -179.0), 0.5)
        assertNear(180.0, abs(middle.lon), 1e-6, "expected ±180, got ${middle.lon}")
    }

    /** Extrapolation beyond the segment is allowed for callers that need it. */
    @Test
    fun interpolateSupportsExtrapolation() {
        val extrapolated = GeoMath.interpolate(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0), 2.0)
        assertNear(2.0, extrapolated.lon, 1e-9)
    }

    /** Compass points map bearings onto eight sectors. */
    @Test
    fun compassPointsMapBearings() {
        assertEquals("N", GeoMath.compassPoint(0.0))
        assertEquals("NE", GeoMath.compassPoint(45.0))
        assertEquals("E", GeoMath.compassPoint(90.0))
        assertEquals("SE", GeoMath.compassPoint(135.0))
        assertEquals("S", GeoMath.compassPoint(180.0))
        assertEquals("SW", GeoMath.compassPoint(225.0))
        assertEquals("W", GeoMath.compassPoint(270.0))
        assertEquals("NW", GeoMath.compassPoint(315.0))
        assertEquals("N", GeoMath.compassPoint(359.0))
    }
}

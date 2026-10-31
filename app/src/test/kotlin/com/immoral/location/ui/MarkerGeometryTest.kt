package com.immoral.location.ui

import com.immoral.location.ui.map.MarkerGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the numbers that are easy to break while "just tweaking the drawing", and that the user
 * has already complained about once: which way `+y` points on the pad, how far the tick ring
 * rotates when 跟随手机朝向 is on, and where a pin is anchored.
 */
class MarkerGeometryTest {

    private val EPS = 1e-4

    // ------------------------------------------------------------ tick ring

    @Test
    fun ringIsUprightWhenNotFollowing() {
        assertEquals(0.0, MarkerGeometry.ringRotationDeg(followHeading = false, headingDeg = 123.0).toDouble(), EPS)
    }

    @Test
    fun ringRotatesAgainstTheHeading() {
        assertEquals(-90.0, MarkerGeometry.ringRotationDeg(followHeading = true, headingDeg = 90.0).toDouble(), EPS)
        assertEquals(90.0, MarkerGeometry.ringRotationDeg(followHeading = true, headingDeg = -90.0).toDouble(), EPS)
    }

    @Test
    fun ringWrapsHeadingsIntoZeroToThreeSixty() {
        // 350° must rotate by +10°, not by -350° (which would be the same picture but a different
        // animation path, and would break the "shortest rotation" expectation on screen).
        assertEquals(10.0, MarkerGeometry.ringRotationDeg(followHeading = true, headingDeg = 350.0).toDouble(), EPS)
        assertEquals(0.0, MarkerGeometry.ringRotationDeg(followHeading = true, headingDeg = 360.0).toDouble(), EPS)
        assertEquals(-1.0, MarkerGeometry.ringRotationDeg(followHeading = true, headingDeg = 361.0).toDouble(), EPS)
    }

    @Test
    fun unknownHeadingNeverProducesNaN() {
        assertEquals(0.0, MarkerGeometry.ringRotationDeg(followHeading = true, headingDeg = null).toDouble(), EPS)
        assertEquals(0.0, MarkerGeometry.ringRotationDeg(followHeading = true, headingDeg = Double.NaN).toDouble(), EPS)
        assertEquals(0.0, MarkerGeometry.ringRotationDeg(followHeading = true, headingDeg = Double.POSITIVE_INFINITY).toDouble(), EPS)
    }

    // ------------------------------------------------------------- joystick

    @Test
    fun pushingUpMeansNorth() {
        val travel = 100f
        val (x, y) = MarkerGeometry.normalizedVector(dx = 0f, dy = -travel / 2f, travel = travel)
        assertEquals(0.0, x.toDouble(), EPS)
        assertEquals(0.5, y.toDouble(), EPS)
        assertTrue("+y must be up on screen, which the engine reads as north", y > 0f)
    }

    @Test
    fun pushingRightMeansEast() {
        val (x, y) = MarkerGeometry.normalizedVector(dx = 50f, dy = 0f, travel = 100f)
        assertEquals(0.5, x.toDouble(), EPS)
        assertEquals(0.0, y.toDouble(), EPS)
    }

    @Test
    fun pushingDownMeansSouth() {
        val (x, y) = MarkerGeometry.normalizedVector(dx = 0f, dy = 50f, travel = 100f)
        assertEquals(0.0, x.toDouble(), EPS)
        assertEquals(-0.5, y.toDouble(), EPS)
    }

    @Test
    fun deadZoneStopsThePosition() {
        val travel = 100f
        assertEquals(0f to 0f, MarkerGeometry.normalizedVector(dx = 5f, dy = -5f, travel = travel))
    }

    @Test
    fun vectorIsClampedToOne() {
        val (x, y) = MarkerGeometry.normalizedVector(dx = 500f, dy = -500f, travel = 100f)
        assertEquals(1.0, x.toDouble(), EPS)
        assertEquals(1.0, y.toDouble(), EPS)
    }

    @Test
    fun degenerateTravelAndNonFiniteInputAreSafe() {
        assertEquals(0f to 0f, MarkerGeometry.normalizedVector(dx = 10f, dy = 10f, travel = 0f))
        assertEquals(0f to 0f, MarkerGeometry.normalizedVector(dx = Float.NaN, dy = 1f, travel = 100f))
    }

    // -------------------------------------------------------------- markers

    @Test
    fun dotsAreCentredAndPinsAreAnchoredAtTheTip() {
        assertEquals(MarkerGeometry.CENTER to MarkerGeometry.CENTER, MarkerGeometry.centeredAnchor())
        assertEquals(MarkerGeometry.CENTER to MarkerGeometry.BOTTOM, MarkerGeometry.pinAnchor())
        // A pin anchored anywhere else sits off the tapped point by half its own height.
        assertEquals(1.0, MarkerGeometry.pinAnchor().second.toDouble(), EPS)
    }

    @Test
    fun waypointsAreNumberedFromOne() {
        assertEquals("1", MarkerGeometry.badgeText(MarkerGeometry.BadgeKind.WAYPOINT, 0))
        assertEquals("3", MarkerGeometry.badgeText(MarkerGeometry.BadgeKind.WAYPOINT, 2))
        assertEquals("起", MarkerGeometry.badgeText(MarkerGeometry.BadgeKind.START, -1))
        assertEquals("终", MarkerGeometry.badgeText(MarkerGeometry.BadgeKind.END, -1))
    }
}

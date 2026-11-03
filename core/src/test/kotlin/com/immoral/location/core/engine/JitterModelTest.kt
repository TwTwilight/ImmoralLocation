package com.immoral.location.core.engine

import com.immoral.location.core.Reference
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulationConfig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The simulated receiver error (GPS jitter).
 *
 * The property that matters is *self-consistency*: a fix may be a few meters off, but the error
 * must wander slowly, so an app that differentiates consecutive coordinates does not compute a
 * ground speed that contradicts `Location.getSpeed()`. A per-tick uniform offset inside the radius
 * (the old model) failed exactly that check: the position jumped the full radius twice a second.
 */
class JitterModelTest {

    private fun engine(point: GeoPoint = Reference.BEIJING, jitter: Double = 0.0, altitude: Double = 0.0) =
        SimulationEngine(
            point,
            SimulationConfig(gpsJitterMeters = jitter, altitudeJitterMeters = altitude),
        )

    private fun SimulationEngine.runTicks(count: Int, deltaMs: Long = 1_000L) {
        running = true
        repeat(count) { tick(deltaMs) }
    }

    @Test
    fun withoutJitterThePositionIsExact() {
        val origin = Reference.BEIJING
        val engine = engine(origin, jitter = 0.0, altitude = 0.0)
        engine.runTicks(30)
        assertEquals(origin.lat, engine.position.lat, 0.0)
        assertEquals(origin.lon, engine.position.lon, 0.0)
        assertEquals(origin.alt, engine.position.alt, 0.0)
    }

    @Test
    fun theErrorStaysInsideTheConfiguredRadius() {
        val origin = Reference.SHANGHAI
        val radius = 6.0
        val engine = engine(origin, jitter = radius)
        engine.runTicks(900)
        var maxOffset = 0.0
        repeat(600) {
            engine.tick(1_000L)
            maxOffset = maxOf(maxOffset, GeoMath.distanceMeters(origin, engine.position))
        }
        assertTrue(maxOffset <= radius + 0.01, "offset $maxOffset exceeded the configured radius $radius")
        assertTrue(maxOffset > 0.2, "the error never appeared at all (max $maxOffset m)")
    }

    @Test
    fun consecutiveFixesMoveFarLessThanTheRadius() {
        val origin = Reference.BEIJING
        val radius = 10.0
        val engine = engine(origin, jitter = radius)
        engine.runTicks(120)
        var maxStep = 0.0
        var previous = engine.position
        repeat(600) {
            engine.tick(1_000L)
            val current = engine.position
            maxStep = maxOf(maxStep, GeoMath.distanceMeters(previous, current))
            previous = current
        }
        // A receiver at rest reports the same point plus noise; the step between two consecutive
        // fixes is a fraction of the error envelope, never the envelope itself.
        assertTrue(maxStep < radius * 0.5, "consecutive fixes jumped $maxStep m (radius $radius m)")
    }

    @Test
    fun aParkedPositionStillWanders() {
        val origin = Reference.BEIJING
        val engine = engine(origin, jitter = 4.0)
        engine.runTicks(60)
        val samples = (0 until 120).map {
            engine.tick(1_000L)
            engine.position
        }
        val distinct = samples.map { it.lat to it.lon }.toSet().size
        assertTrue(distinct > 20, "a stationary fix must still wander, got $distinct distinct points")
        // ...but it must not drift away: the wander is stationary around the true position.
        val centroidLat = samples.map { it.lat }.average()
        val centroidLon = samples.map { it.lon }.average()
        val drift = GeoMath.distanceMeters(origin, GeoPoint(centroidLat, centroidLon, origin.alt))
        assertTrue(drift < 2.0, "the error drifted $drift m away from the true position")
    }

    @Test
    fun walkingSpeedSurvivesTheNoise() {
        val origin = Reference.BEIJING
        val engine = engine(origin, jitter = 6.0)
        engine.setJoystick(0f, 1f) // full speed, north
        engine.runTicks(30)
        val start = engine.position
        var previous = start
        var steps = 0
        var maxStepDistance = 0.0
        repeat(60) {
            engine.tick(1_000L)
            val current = engine.position
            maxStepDistance = maxOf(maxStepDistance, GeoMath.distanceMeters(previous, current))
            previous = current
            steps++
        }
        val travelled = GeoMath.distanceMeters(start, previous)
        val expected = SimulationConfig().effectiveSpeedMps() * steps
        assertTrue(
            abs(travelled - expected) < expected * 0.15,
            "60 s at the configured speed should cover about $expected m, covered $travelled m",
        )
        // Per-second displacement must stay within the modelled step envelope (three sigma of the
        // per-step diffusion for a 6 m radius is ~1.9 m) plus the real movement, instead of the
        // whole 6 m error envelope — that is what keeps "speed from coordinates" believable.
        assertTrue(
            maxStepDistance < 3.5,
            "a single second of walking moved $maxStepDistance m, which contradicts the reported speed",
        )
    }

    @Test
    fun theNoiseIsDeterministicForTheSameTickHistory() {
        val origin = Reference.NEW_YORK
        val first = engine(origin, jitter = 7.0).apply { runTicks(200) }.position
        val second = engine(origin, jitter = 7.0).apply { runTicks(200) }.position
        assertEquals(first.lat, second.lat, 0.0)
        assertEquals(first.lon, second.lon, 0.0)
    }

    @Test
    fun disablingJitterRestoresAnExactPosition() {
        val origin = Reference.BEIJING
        val engine = engine(origin, jitter = 8.0)
        engine.runTicks(120)
        assertTrue(GeoMath.distanceMeters(origin, engine.position) > 0.1)
        engine.config = engine.config.copy(gpsJitterMeters = 0.0)
        engine.tick(1_000L)
        assertEquals(origin.lat, engine.position.lat, 0.0)
        assertEquals(origin.lon, engine.position.lon, 0.0)
    }

    @Test
    fun altitudeJitterMovesOnlyTheAltitude() {
        val origin = Reference.SHANGHAI
        val engine = engine(origin, jitter = 0.0, altitude = 12.0)
        engine.runTicks(180)
        val position = engine.position
        assertEquals(origin.lat, position.lat, 0.0)
        assertEquals(origin.lon, position.lon, 0.0)
        assertTrue(
            abs(position.alt - origin.alt) <= 12.0 + 0.001,
            "altitude offset ${position.alt - origin.alt} m exceeded 12 m",
        )
        assertTrue(abs(position.alt - origin.alt) > 0.05, "altitude jitter never appeared")
    }
}

package com.immoral.location.core.engine

import com.immoral.location.core.Reference
import com.immoral.location.core.assertNear
import com.immoral.location.core.assertSamePoint
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.MoveMode
import com.immoral.location.core.model.RouteState
import com.immoral.location.core.model.SpeedLevel
import com.immoral.location.core.model.SimulationConfig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Movement model: static, joystick, heading, speed levels, ticking and jitter. */
class SimulationEngineTest {
    private fun engineAtBeijing(
        config: SimulationConfig = SimulationConfig(),
        point: GeoPoint = Reference.BEIJING,
    ): SimulationEngine = SimulationEngine(point, config).apply {
        clock = { 1_700_000_000_000L }
    }

    /** Ticks [count] times for [stepMs] each. */
    private fun SimulationEngine.run(count: Int, stepMs: Long = 1_000L) {
        repeat(count) { tick(stepMs) }
    }

    @Test
    fun staticModeNeverMoves() {
        val engine = engineAtBeijing(SimulationConfig(mode = MoveMode.STATIC))
        engine.running = true
        engine.run(10)
        assertSamePoint(Reference.BEIJING, engine.position, 1e-6)
        assertEquals(0.0, engine.state.speedMps)
    }

    @Test
    fun stoppedEngineNeverMoves() {
        val engine = engineAtBeijing(
            SimulationConfig(mode = MoveMode.JOYSTICK, followHeading = false),
        )
        engine.setJoystick(0f, 1f)
        engine.run(10)
        assertSamePoint(Reference.BEIJING, engine.position, 1e-6)
        assertTrue(engine.state.speedMps == 0.0)
    }

    @Test
    fun joystickForwardMovesNorth() {
        val engine = engineAtBeijing(
            SimulationConfig(
                mode = MoveMode.JOYSTICK,
                speed = SpeedLevel.WALK,
                followHeading = false,
                fixedBearingDeg = 0.0,
            ),
        )
        engine.running = true
        engine.setJoystick(0f, 1f)
        engine.run(10)
        val moved = GeoMath.distanceMeters(Reference.BEIJING, engine.position)
        assertNear(14.0, moved, 0.05, "walking 10 s at 1.4 m/s")
        assertTrue(engine.position.lat > Reference.BEIJING.lat, "must move north")
        assertNear(Reference.BEIJING.lon, engine.position.lon, 1e-9)
    }

    @Test
    fun joystickRightMovesEast() {
        val engine = engineAtBeijing(
            SimulationConfig(mode = MoveMode.JOYSTICK, followHeading = false),
        )
        engine.running = true
        engine.setJoystick(1f, 0f)
        engine.run(10)
        assertTrue(engine.position.lon > Reference.BEIJING.lon, "must move east")
        assertNear(Reference.BEIJING.lat, engine.position.lat, 1e-6)
        assertNear(90.0, engine.state.bearingDeg, 1e-6)
    }

    @Test
    fun joystickBackwardMovesSouth() {
        val engine = engineAtBeijing(
            SimulationConfig(mode = MoveMode.JOYSTICK, followHeading = false),
        )
        engine.running = true
        engine.setJoystick(0f, -1f)
        engine.run(10)
        assertTrue(engine.position.lat < Reference.BEIJING.lat, "must move south")
        assertNear(180.0, engine.state.bearingDeg, 1e-6)
    }

    @Test
    fun joystickMagnitudeScalesTheSpeed() {
        val full = engineAtBeijing(
            SimulationConfig(mode = MoveMode.JOYSTICK, followHeading = false),
        ).apply {
            running = true
            setJoystick(0.6f, 0.8f) // magnitude exactly 1.0
            run(10)
        }
        val half = engineAtBeijing(
            SimulationConfig(mode = MoveMode.JOYSTICK, followHeading = false),
        ).apply {
            running = true
            setJoystick(0.3f, 0.4f) // magnitude exactly 0.5
            run(10)
        }
        val fullDistance = GeoMath.distanceMeters(Reference.BEIJING, full.position)
        val halfDistance = GeoMath.distanceMeters(Reference.BEIJING, half.position)
        assertNear(14.0, fullDistance, 0.05)
        assertNear(7.0, halfDistance, 0.05)
        assertNear(1.4, full.state.speedMps, 1e-6)
        assertNear(0.7, half.state.speedMps, 1e-6)
    }

    @Test
    fun diagonalJoystickFollowsTheResultantDirection() {
        val engine = engineAtBeijing(
            SimulationConfig(mode = MoveMode.JOYSTICK, followHeading = false),
        )
        engine.running = true
        engine.setJoystick(0.70710677f, 0.70710677f)
        engine.run(10)
        assertNear(45.0, engine.state.bearingDeg, 0.01)
    }

    @Test
    fun followHeadingRotatesTheJoystickVector() {
        // Device pointing east, stick pushed "forward" => the simulation moves east.
        val engine = engineAtBeijing(
            SimulationConfig(mode = MoveMode.JOYSTICK, followHeading = true),
        )
        engine.running = true
        engine.setHeading(90.0)
        assertEquals(90.0, engine.state.headingDeg)
        engine.setJoystick(0f, 1f)
        engine.run(10)
        assertNear(90.0, engine.state.bearingDeg, 0.01)
        assertTrue(engine.position.lon > Reference.BEIJING.lon, "must move east")
    }

    @Test
    fun fixedBearingIgnoresTheDeviceHeading() {
        val engine = engineAtBeijing(
            SimulationConfig(
                mode = MoveMode.JOYSTICK,
                followHeading = false,
                fixedBearingDeg = 180.0,
            ),
        )
        engine.running = true
        engine.setHeading(90.0)
        engine.setJoystick(0f, 1f)
        engine.run(10)
        assertNear(180.0, engine.state.bearingDeg, 0.01)
        assertTrue(engine.position.lat < Reference.BEIJING.lat, "must move south")
    }

    @Test
    fun headingIsNormalized() {
        val engine = engineAtBeijing()
        engine.setHeading(-90.0)
        assertEquals(270.0, engine.state.headingDeg)
        engine.setHeading(720.0)
        assertEquals(0.0, engine.state.headingDeg)
    }

    @Test
    fun bearingTurnsSmoothlyInsteadOfSnapping() {
        val engine = engineAtBeijing(
            SimulationConfig(mode = MoveMode.JOYSTICK, followHeading = false),
        )
        engine.running = true
        engine.setJoystick(0f, 1f)
        engine.tick(1_000L) // settle on north
        assertNear(0.0, engine.state.bearingDeg, 1e-9)

        engine.setJoystick(1f, 0f) // now ask for east
        engine.tick(100L)
        val after100ms = engine.state.bearingDeg
        assertTrue(after100ms > 0.0 && after100ms < 90.0, "bearing jumped to $after100ms")
        engine.tick(1_000L)
        assertNear(90.0, engine.state.bearingDeg, 1e-6)
    }

    @Test
    fun releaseJoystickStopsTheMovement() {
        val engine = engineAtBeijing(
            SimulationConfig(mode = MoveMode.JOYSTICK, followHeading = false),
        )
        engine.running = true
        engine.setJoystick(0f, 1f)
        engine.run(5)
        val stoppedAt = engine.position
        engine.releaseJoystick()
        engine.run(5)
        assertSamePoint(stoppedAt, engine.position, 1e-9)
        assertEquals(0.0, engine.state.speedMps)
    }

    @Test
    fun joystickSwitchesStaticModeToJoystickMode() {
        val engine = engineAtBeijing(SimulationConfig(mode = MoveMode.STATIC))
        engine.setJoystick(0f, 1f)
        assertEquals(MoveMode.JOYSTICK, engine.state.mode)
        assertEquals(MoveMode.JOYSTICK, engine.config.mode)
    }

    @Test
    fun everySpeedLevelMovesAtItsDocumentedSpeed() {
        val expected = mapOf(
            SpeedLevel.WALK to 1.4,
            SpeedLevel.RUN to 3.3,
            SpeedLevel.BIKE to 5.5,
            SpeedLevel.DRIVE to 13.9,
            SpeedLevel.PLANE to 230.0,
        )
        for ((level, metersPerSecond) in expected) {
            val engine = engineAtBeijing(
                SimulationConfig(mode = MoveMode.JOYSTICK, speed = level, followHeading = false),
            )
            engine.running = true
            engine.setJoystick(0f, 1f)
            engine.tick(1_000L)
            val moved = GeoMath.distanceMeters(Reference.BEIJING, engine.position)
            assertNear(metersPerSecond, moved, metersPerSecond * 0.001, "$level distance")
            assertNear(metersPerSecond, engine.state.speedMps, 1e-9, "$level speed")
        }
    }

    @Test
    fun customSpeedLevelUsesTheConfiguredValue() {
        val engine = engineAtBeijing(
            SimulationConfig(
                mode = MoveMode.JOYSTICK,
                speed = SpeedLevel.CUSTOM,
                customSpeedMps = 42.0,
                followHeading = false,
            ),
        )
        engine.running = true
        engine.setJoystick(0f, 1f)
        engine.tick(1_000L)
        assertNear(42.0, GeoMath.distanceMeters(Reference.BEIJING, engine.position), 0.05)
    }

    @Test
    fun hugeTicksAreClampedToTheMaximumDelta() {
        val engine = engineAtBeijing(
            SimulationConfig(mode = MoveMode.JOYSTICK, followHeading = false),
        )
        engine.running = true
        engine.setJoystick(0f, 1f)
        engine.tick(60_000L)
        val moved = GeoMath.distanceMeters(Reference.BEIJING, engine.position)
        assertNear(1.4 * SimulationEngine.MAX_TICK_DELTA_MS / 1_000.0, moved, 0.02)
    }

    @Test
    fun nonPositiveTicksDoNotMove() {
        val engine = engineAtBeijing(
            SimulationConfig(mode = MoveMode.JOYSTICK, followHeading = false),
        )
        engine.running = true
        engine.setJoystick(0f, 1f)
        engine.tick(0L)
        engine.tick(-5_000L)
        assertSamePoint(Reference.BEIJING, engine.position, 1e-9)
    }

    @Test
    fun tickRefreshesTheTimestampFromTheClock() {
        val engine = SimulationEngine()
        engine.clock = { 123_456L }
        assertEquals(123_456L, engine.tick(1_000L).updatedAtMs)
    }

    @Test
    fun setPositionTeleportsAndCountsImpacts() {
        val engine = engineAtBeijing()
        engine.setPosition(Reference.SHANGHAI)
        assertSamePoint(Reference.SHANGHAI, engine.position)
        assertEquals(1L, engine.impactCounter)
        engine.setPosition(Reference.GUANGZHOU, emitImpact = false)
        assertEquals(1L, engine.impactCounter)
        assertSamePoint(Reference.GUANGZHOU, engine.position)
    }

    @Test
    fun setPositionIgnoresInvalidCoordinates() {
        val engine = engineAtBeijing()
        engine.setPosition(GeoPoint(200.0, 0.0))
        engine.setPosition(GeoPoint(Double.NaN, Double.NaN))
        assertSamePoint(Reference.BEIJING, engine.position, 1e-9)
        assertEquals(0L, engine.impactCounter)
    }

    @Test
    fun setPositionStopsTheCurrentMovement() {
        val engine = engineAtBeijing(
            SimulationConfig(mode = MoveMode.JOYSTICK, followHeading = false),
        )
        engine.running = true
        engine.setJoystick(0f, 1f)
        engine.run(5)
        engine.setPosition(Reference.NEW_YORK)
        assertEquals(0.0, engine.state.speedMps)
        assertSamePoint(Reference.NEW_YORK, engine.position)
    }

    @Test
    fun configSetterSanitizesTheValue() {
        val engine = engineAtBeijing()
        engine.config = SimulationConfig(updateIntervalMs = 1L, stepCadenceBpm = 9_999)
        assertEquals(SimulationConfig.MIN_INTERVAL_MS, engine.config.updateIntervalMs)
        assertEquals(SimulationConfig.MAX_CADENCE_BPM, engine.config.stepCadenceBpm)
    }

    @Test
    fun initialConfigIsSanitizedToo() {
        val engine = SimulationEngine(Reference.BEIJING, SimulationConfig(gpsJitterMeters = -1.0))
        assertEquals(0.0, engine.config.gpsJitterMeters)
    }

    @Test
    fun stateCarriesModeVersionAndPackages() {
        val engine = engineAtBeijing(
            SimulationConfig(
                mode = MoveMode.JOYSTICK,
                independentMode = true,
                independentPackages = setOf("com.example.second", "com.example.first"),
            ),
        )
        val state = engine.state
        assertEquals(MoveMode.JOYSTICK, state.mode)
        assertEquals(
            listOf("com.example.first", "com.example.second"),
            state.activePackages,
        )
        assertTrue(state.engineVersion.isNotBlank(), "engine version must be reported")
        assertEquals(RouteState.IDLE, state.routeState)
    }

    @Test
    fun stateHidesPackagesWhenIndependentModeIsOff() {
        val engine = engineAtBeijing(
            SimulationConfig(
                independentMode = false,
                independentPackages = setOf("com.example.app"),
            ),
        )
        assertTrue(engine.state.activePackages.isEmpty())
    }

    @Test
    fun jitterIsDisabledByDefault() {
        val engine = engineAtBeijing()
        assertSamePoint(Reference.BEIJING, engine.position, 1e-12)
        assertSamePoint(Reference.BEIJING, engine.state.point, 1e-12)
    }

    @Test
    fun gpsJitterStaysWithinTheConfiguredRadius() {
        val engine = engineAtBeijing(SimulationConfig(gpsJitterMeters = 20.0))
        // The error is a random walk that only advances while the simulation runs.
        engine.running = true
        repeat(60) {
            engine.tick(1_000L)
            val distance = GeoMath.distanceMeters(Reference.BEIJING, engine.position)
            assertTrue(distance <= 20.0 + 1e-6, "jitter of $distance m exceeds 20 m")
        }
    }

    @Test
    fun jitterIsStableWithinATick() {
        val engine = engineAtBeijing(SimulationConfig(gpsJitterMeters = 15.0))
        engine.running = true
        engine.tick(1_000L)
        val first = engine.position
        val second = engine.position
        assertEquals(first, second, "jitter must not change between reads")
        assertEquals(engine.state.point, first)
    }

    @Test
    fun altitudeJitterMovesTheAltitudeOnly() {
        val engine = engineAtBeijing(
            SimulationConfig(gpsJitterMeters = 0.0, altitudeJitterMeters = 5.0),
        )
        // The error is a random walk that starts at zero, so it needs a few epochs to develop.
        engine.running = true
        repeat(5) { engine.tick(1_000L) }
        assertSamePoint(Reference.BEIJING, engine.position, 1e-6)
        assertTrue(
            abs(engine.position.alt - Reference.BEIJING.alt) <= 5.0 + 1e-9,
            "altitude jitter out of range: ${engine.position.alt}",
        )
        assertNotEquals(Reference.BEIJING.alt, engine.position.alt)
    }

    @Test
    fun movementIsDeterministicAcrossEngines() {
        fun runOnce(): GeoPoint {
            val engine = engineAtBeijing(
                SimulationConfig(mode = MoveMode.JOYSTICK, speed = SpeedLevel.RUN),
            )
            engine.running = true
            engine.setHeading(37.0)
            engine.setJoystick(0.6f, 0.8f)
            engine.run(10)
            return engine.position
        }
        assertEquals(runOnce(), runOnce())
    }

    /**
     * Before the first tick the engine has no authoritative epoch, so it must *pin* one.
     *
     * Otherwise two reads of the same pre-tick engine answered from two different wall-clock
     * instants: C/N0 drifts a few hundredths of a dB per second, which is invisible until it lands
     * on a rounding boundary — and then the same satellite is 39 dB-Hz in `GnssStatus` and 40 in
     * `$GGGSV`. CI (Linux) caught it while the local run passed; the fix is the pin, and this test
     * reproduces it deterministically by advancing the injected clock between reads.
     */
    @Test
    fun theSkyIsPinnedUntilTheFirstTick() {
        var now = 1_700_000_000_000L
        val engine = SimulationEngine(
            Reference.BEIJING,
            SimulationConfig(gpsSignalSimulation = true),
        ).apply { clock = { now } }
        val first = engine.satellites()
        now += 7_000L
        assertEquals(first, engine.satellites(), "two pre-tick reads must describe the same sky")
        val firstNmea = engine.nmea(now)
        now += 31_000L
        assertEquals(firstNmea, engine.nmea(now - 31_000L), "NMEA of one instant must be reproducible")
        // A tick makes the tick timestamp authoritative, so the sky is allowed to move again.
        engine.running = true
        engine.tick(1_000L)
        assertNotEquals(first, engine.satellites())
    }
}

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
import kotlin.test.assertTrue

/**
 * Regression tests for the on-device accuracy defects reported by the Lead.
 *
 * ### Case 1 — joystick overshoot
 * Device: `speed=RUN (3.3 m/s)` with `setJoystick(0, 1)` advanced ≈ 7.1 m/s and reported `vel=7.5`.
 * The engine must apply `effectiveSpeedMps() * magnitude` exactly once per `tick`, and report
 * exactly that value in [com.immoral.location.core.model.EngineState.speedMps].
 *
 * ### Case 2 — route too slow / drift while paused
 * Device: `RoutePlan(speedMps = 6.0)` advanced 3.46 m in 10.19 s while a paused route still
 * drifted 1.74 m. Root cause in `:core`: assigning [SimulationEngine.config] unconditionally
 * re-seeded the loaded plan with `config.routeSpeedMps`, so every UI `SetConfig` push (which
 * carries the app default of 2.0) silently retuned the running route. Covered by
 * [routeSpeedSurvivesRepeatedConfigPushes].
 *
 * ### Case 3 — route speed vs speed level
 * Route playback must use the plan speed, never [SimulationConfig.speed], and
 * `setJoystick` must never take the engine out of [MoveMode.ROUTE].
 */
class MovementAccuracyTest {
    private fun engine(
        config: SimulationConfig,
        point: GeoPoint = GeoPoint(0.0, 0.0),
    ): SimulationEngine = SimulationEngine(point, config).apply { clock = { 0L } }

    private fun joystickEngine(
        level: SpeedLevel,
        point: GeoPoint = GeoPoint(0.0, 0.0),
    ): SimulationEngine = engine(
        SimulationConfig(
            mode = MoveMode.JOYSTICK,
            speed = level,
            followHeading = false,
        ),
        point,
    ).apply {
        running = true
    }

    /** A dead-straight 2.2 km route along the equator, so path length == displacement. */
    private val straightRoute = listOf(
        GeoPoint(0.0, 0.0),
        GeoPoint(0.0, 0.010),
        GeoPoint(0.0, 0.020),
    )

    private fun SimulationEngine.run(count: Int, stepMs: Long = 1_000L) {
        repeat(count) { tick(stepMs) }
    }

    // ------------------------------------------------------------ case 1: joystick

    @Test
    fun joystickRunOneSecondMovesExactlyTheConfiguredSpeed() {
        val engine = joystickEngine(SpeedLevel.RUN)
        engine.setJoystick(0f, 1f)
        val state = engine.tick(1_000L)
        val moved = GeoMath.distanceMeters(GeoPoint(0.0, 0.0), engine.position)
        assertNear(3.3, moved, 0.033, "RUN must move 3.3 m in one 1 s tick")
        assertNear(3.3, state.speedMps, 1e-9, "reported speed must be 3.3 m/s, not 7.5")
    }

    @Test
    fun joystickRunTenSecondsMovesThirtyThreeMeters() {
        // On device this window advanced ~48 m (+7.1 m/s) instead of 33 m (3.3 m/s).
        val engine = joystickEngine(SpeedLevel.RUN)
        engine.setJoystick(0f, 1f)
        engine.run(10)
        val moved = GeoMath.distanceMeters(GeoPoint(0.0, 0.0), engine.position)
        assertNear(33.0, moved, 0.33, "10 s at RUN must be 33 m")
        assertNear(3.3, engine.state.speedMps, 1e-9)
    }

    @Test
    fun repeatedSetJoystickCallsDoNotAccumulateSpeedOrDistance() {
        // The app re-sends the joystick vector on every UI frame; that must be idempotent.
        val engine = joystickEngine(SpeedLevel.RUN)
        repeat(60) { engine.setJoystick(0f, 1f) }
        engine.tick(1_000L)
        val moved = GeoMath.distanceMeters(GeoPoint(0.0, 0.0), engine.position)
        assertNear(3.3, moved, 0.033, "repeated setJoystick must not stack distance")
        assertNear(3.3, engine.state.speedMps, 1e-9)
    }

    @Test
    fun reportedSpeedIsAlwaysEffectiveSpeedTimesMagnitude() {
        val full = joystickEngine(SpeedLevel.RUN).apply {
            setJoystick(0f, 1f)
            tick(1_000L)
        }
        assertNear(3.3, full.state.speedMps, 1e-9, "full stick at RUN")

        val half = joystickEngine(SpeedLevel.RUN).apply {
            setJoystick(0.3f, 0.4f) // magnitude 0.5
            tick(1_000L)
        }
        assertNear(1.65, half.state.speedMps, 1e-6, "half stick at RUN")

        val sideways = joystickEngine(SpeedLevel.RUN).apply {
            setJoystick(1f, 0f)
            tick(1_000L)
        }
        assertNear(3.3, sideways.state.speedMps, 1e-9, "right stick at RUN")

        val walk = joystickEngine(SpeedLevel.WALK).apply {
            setJoystick(0f, 1f)
            tick(1_000L)
        }
        assertNear(1.4, walk.state.speedMps, 1e-9, "full stick at WALK")
    }

    @Test
    fun speedLevelsThatLookedCorrectOnDeviceStillMatch() {
        // These two were measured as correct on device (14.0 and 230.7); keep them pinned.
        val drive = joystickEngine(SpeedLevel.DRIVE).apply {
            setJoystick(0f, 1f)
            tick(1_000L)
        }
        assertNear(13.9, GeoMath.distanceMeters(GeoPoint(0.0, 0.0), drive.position), 0.14)
        assertNear(13.9, drive.state.speedMps, 1e-9)

        val plane = joystickEngine(SpeedLevel.PLANE).apply {
            setJoystick(0f, 1f)
            tick(1_000L)
        }
        assertNear(230.0, GeoMath.distanceMeters(GeoPoint(0.0, 0.0), plane.position), 2.3)
        assertNear(230.0, plane.state.speedMps, 1e-9)
    }

    @Test
    fun subTicksComposeExactlyIntoTheFullSecond() {
        val coarse = joystickEngine(SpeedLevel.RUN).apply {
            setJoystick(0f, 1f)
            tick(1_000L)
        }
        val fine = joystickEngine(SpeedLevel.RUN).apply {
            setJoystick(0f, 1f)
            repeat(10) { tick(100L) }
        }
        val coarseDistance = GeoMath.distanceMeters(GeoPoint(0.0, 0.0), coarse.position)
        val fineDistance = GeoMath.distanceMeters(GeoPoint(0.0, 0.0), fine.position)
        assertNear(coarseDistance, fineDistance, 1e-6, "10 x 100 ms must equal 1 x 1000 ms")
    }

    @Test
    fun deltasAreAdditiveSoADuplicatedTickDoublesTheDistance() {
        // Documents the contract boundary: the engine applies the requested delta and has no
        // notion of "one second", so a ticker that fires twice per interval doubles the speed.
        // This is why hook-framework must make ticker start idempotent - it is not an engine bug.
        val single = joystickEngine(SpeedLevel.RUN).apply {
            setJoystick(0f, 1f)
            tick(1_000L)
        }
        val doubled = joystickEngine(SpeedLevel.RUN).apply {
            setJoystick(0f, 1f)
            tick(1_000L)
            tick(1_000L)
        }
        val singleDistance = GeoMath.distanceMeters(GeoPoint(0.0, 0.0), single.position)
        val doubledDistance = GeoMath.distanceMeters(GeoPoint(0.0, 0.0), doubled.position)
        assertNear(singleDistance * 2.0, doubledDistance, 1e-6)
        assertNear(3.3, doubled.state.speedMps, 1e-9, "speed is per tick, not cumulative")
    }

    @Test
    fun tickDiagnosticsExposeTheAppliedCadence() {
        val engine = joystickEngine(SpeedLevel.RUN)
        engine.setJoystick(0f, 1f)
        engine.run(7, 250L)
        assertEquals(7L, engine.appliedTickCount)
        assertEquals(250L, engine.lastTickDeltaMs)
    }

    // -------------------------------------------------------------- case 2: route

    @Test
    fun routeAdvancesSixMetersPerSecond() {
        val engine = engine(SimulationConfig(routeSpeedMps = 6.0))
        engine.startRoute(straightRoute, speedMps = 6.0)
        engine.run(10)
        val moved = GeoMath.distanceMeters(straightRoute[0], engine.position)
        assertNear(60.0, moved, 0.6, "10 s on a 6 m/s route must be 60 m")
        assertNear(6.0, engine.state.speedMps, 1e-9, "reported route speed")
        val total = GeoMath.distanceMeters(straightRoute[0], straightRoute[1]) +
            GeoMath.distanceMeters(straightRoute[1], straightRoute[2])
        assertNear(60.0 / total, engine.state.routeProgress, 0.002, "progress after 60 m")
    }

    @Test
    fun routeProgressIsMonotonicWhilePlaying() {
        val engine = engine(SimulationConfig(routeSpeedMps = 6.0))
        engine.startRoute(straightRoute, speedMps = 6.0)
        var previous = engine.state.routeProgress
        repeat(30) {
            engine.tick(1_000L)
            val current = engine.state.routeProgress
            assertTrue(current >= previous, "progress went backwards: $previous -> $current")
            previous = current
        }
        assertTrue(previous > 0.0)
    }

    @Test
    fun pausedRouteDoesNotMoveAtAll() {
        val engine = engine(SimulationConfig(routeSpeedMps = 6.0))
        engine.startRoute(straightRoute, speedMps = 6.0)
        engine.run(5)
        val pausedPoint = engine.position
        val pausedProgress = engine.state.routeProgress
        engine.pauseRoute()
        engine.run(15)
        assertSamePoint(pausedPoint, engine.position, 1e-9)
        assertEquals(pausedProgress, engine.state.routeProgress)
        assertEquals(0.0, engine.state.speedMps)
        assertEquals(RouteState.PAUSED, engine.state.routeState)
    }

    @Test
    fun playRouteResumesFromThePausedProgress() {
        val engine = engine(SimulationConfig(routeSpeedMps = 6.0))
        engine.startRoute(straightRoute, speedMps = 6.0)
        engine.run(5)
        val pausedProgress = engine.state.routeProgress
        val pausedPoint = engine.position
        engine.pauseRoute()
        engine.run(5)
        engine.playRoute()
        engine.run(5)
        assertTrue(engine.state.routeProgress > pausedProgress)
        // 5 s before the pause plus 5 s after it => 60 m from the route start.
        assertNear(60.0, GeoMath.distanceMeters(straightRoute[0], engine.position), 0.6)
        assertNear(6.0, engine.state.speedMps, 1e-9)
        val expectedAdvance = GeoMath.distanceMeters(pausedPoint, engine.position)
        assertNear(30.0, expectedAdvance, 0.3, "resumed movement is 5 s at 6 m/s")
    }

    @Test
    fun routeSpeedSurvivesRepeatedConfigPushes() {
        // Regression: the app re-publishes its whole config (routeSpeedMps default 2.0) while a
        // route started at 6.0 m/s is playing. That must not retune the running route.
        val engine = engine(SimulationConfig(routeSpeedMps = 2.0))
        engine.startRoute(straightRoute, speedMps = 6.0)
        repeat(10) {
            engine.config = engine.config
            engine.tick(1_000L)
        }
        assertNear(60.0, GeoMath.distanceMeters(straightRoute[0], engine.position), 0.6)
        assertNear(6.0, engine.state.speedMps, 1e-9, "the plan speed must still be 6.0")
        assertEquals(6.0, engine.activeRouteSpeedMps)
    }

    @Test
    fun thePlanSpeedIsAlsoKeptWhenTheConfigCarriesAStaleDefault() {
        // Same as above but the pushed config really differs in unrelated fields.
        val engine = engine(SimulationConfig(routeSpeedMps = 2.0))
        engine.startRoute(straightRoute, speedMps = 6.0)
        engine.config = engine.config.copy(
            mode = MoveMode.ROUTE,
            updateIntervalMs = 500L,
            gpsJitterMeters = 2.0,
            stepSimulation = true,
        )
        engine.tick(1_000L)
        assertNear(6.0, engine.state.speedMps, 1e-9)
        assertNear(6.0, engine.activeRouteSpeedMps, 1e-9)
    }

    @Test
    fun anExplicitRouteSpeedChangeInConfigStillApplies() {
        val engine = engine(SimulationConfig(routeSpeedMps = 2.0))
        engine.startRoute(straightRoute)
        assertEquals(2.0, engine.activeRouteSpeedMps)
        engine.config = engine.config.copy(routeSpeedMps = 8.0)
        assertEquals(8.0, engine.activeRouteSpeedMps)
        engine.tick(1_000L)
        assertNear(8.0, engine.state.speedMps, 1e-9)
        assertNear(8.0, GeoMath.distanceMeters(straightRoute[0], engine.position), 0.08)
    }

    @Test
    fun pausedRouteIgnoresJoystickInput() {
        val engine = engine(SimulationConfig(routeSpeedMps = 6.0))
        engine.startRoute(straightRoute, speedMps = 6.0)
        engine.run(3)
        engine.pauseRoute()
        val pausedPoint = engine.position
        engine.setJoystick(1f, 0f)
        engine.run(10)
        assertSamePoint(pausedPoint, engine.position, 1e-9)
        assertEquals(MoveMode.ROUTE, engine.state.mode)
        assertEquals(0.0, engine.state.speedMps)
    }

    @Test
    fun joystickDoesNotHijackARunningRoute() {
        val engine = engine(SimulationConfig(routeSpeedMps = 6.0))
        engine.startRoute(straightRoute, speedMps = 6.0)
        engine.run(5)
        engine.setJoystick(-1f, -1f) // pull the stick back and left
        assertEquals(MoveMode.ROUTE, engine.state.mode, "route mode must survive setJoystick")
        engine.tick(1_000L)
        assertNear(6.0, engine.state.speedMps, 1e-9, "movement must stay route driven")
        assertNear(6.0, GeoMath.distanceMeters(straightRoute[0], engine.position) - 30.0, 0.3)
    }

    @Test
    fun unloadingARouteDoesNotStartMovingFromAStaleStick() {
        val engine = engine(SimulationConfig(routeSpeedMps = 6.0))
        engine.startRoute(straightRoute, speedMps = 6.0)
        engine.setJoystick(0f, 1f)
        engine.stopRoute()
        val stoppedAt = engine.position
        engine.run(10)
        assertSamePoint(stoppedAt, engine.position, 1e-9)
        assertEquals(0.0, engine.state.speedMps)
    }

    // ------------------------------------------- case 3: route speed vs speed level

    @Test
    fun routeUsesTheRouteSpeedNotTheSpeedLevel() {
        for (level in listOf(SpeedLevel.WALK, SpeedLevel.RUN, SpeedLevel.PLANE)) {
            val engine = engine(
                SimulationConfig(mode = MoveMode.ROUTE, speed = level, routeSpeedMps = 6.0),
            )
            engine.startRoute(straightRoute, speedMps = 6.0)
            engine.tick(1_000L)
            assertNear(6.0, engine.state.speedMps, 1e-9, "speed level $level must not matter")
            assertNear(
                6.0,
                GeoMath.distanceMeters(straightRoute[0], engine.position),
                0.06,
                "distance after 1 s at route speed 6.0 (level $level)",
            )
        }
    }

    @Test
    fun omittedRouteSpeedFallsBackToTheConfiguredRouteSpeed() {
        val engine = engine(SimulationConfig(routeSpeedMps = 4.5))
        engine.startRoute(straightRoute)
        assertEquals(4.5, engine.activeRouteSpeedMps)
        engine.tick(1_000L)
        assertNear(4.5, engine.state.speedMps, 1e-9)
    }

    @Test
    fun routeReportsZeroSpeedWhenPausedOrFinished() {
        val engine = engine(SimulationConfig(routeSpeedMps = 6.0))
        engine.startRoute(straightRoute, speedMps = 6.0)
        engine.pauseRoute()
        assertEquals(0.0, engine.state.speedMps)
        engine.playRoute()
        engine.run(500) // 500 s * 6 m/s = 3 km > 2.2 km route
        assertEquals(RouteState.FINISHED, engine.state.routeState)
        assertEquals(0.0, engine.state.speedMps)
        assertEquals(1.0, engine.state.routeProgress)
    }

    // ------------------------------------------------- paused/static jitter drift

    @Test
    fun staticJitterDoesNotWanderWhileNothingMoves() {
        val engine = engine(
            SimulationConfig(mode = MoveMode.STATIC, gpsJitterMeters = 20.0),
            Reference.BEIJING,
        )
        engine.running = true
        engine.tick(1_000L)
        val first = engine.position
        assertTrue(GeoMath.distanceMeters(Reference.BEIJING, first) in 0.0..20.0)
        repeat(20) { engine.tick(1_000L) }
        assertSamePoint(first, engine.position, 1e-9)
    }

    @Test
    fun pausedRouteWithJitterDoesNotDrift() {
        val engine = engine(
            SimulationConfig(routeSpeedMps = 6.0, gpsJitterMeters = 20.0),
            Reference.BEIJING,
        )
        engine.startRoute(
            listOf(Reference.BEIJING, Reference.SHANGHAI),
            speedMps = 6.0,
        )
        engine.run(3)
        engine.pauseRoute()
        engine.tick(1_000L)
        val first = engine.position
        repeat(10) { engine.tick(1_000L) }
        assertSamePoint(first, engine.position, 1e-9)
    }

    @Test
    fun jitterStillVariesWhileTheEngineMoves() {
        val start = GeoPoint(0.0, 0.0)
        val engine = engine(
            SimulationConfig(
                mode = MoveMode.JOYSTICK,
                speed = SpeedLevel.RUN,
                followHeading = false,
                gpsJitterMeters = 20.0,
            ),
            start,
        )
        engine.running = true
        engine.setJoystick(0f, 1f)

        engine.tick(1_000L)
        val baseAfterFirst = GeoMath.destination(start, 0.0, 3.3)
        val firstOffset = GeoMath.distanceMeters(baseAfterFirst, engine.position)
        val firstBearing = GeoMath.bearingDegrees(baseAfterFirst, engine.position)

        engine.tick(1_000L)
        val baseAfterSecond = GeoMath.destination(start, 0.0, 6.6)
        val secondOffset = GeoMath.distanceMeters(baseAfterSecond, engine.position)
        val secondBearing = GeoMath.bearingDegrees(baseAfterSecond, engine.position)

        assertTrue(firstOffset in 0.0..20.0, "first jitter $firstOffset m")
        assertTrue(secondOffset in 0.0..20.0, "second jitter $secondOffset m")
        assertTrue(
            abs(GeoMath.shortestAngleDelta(firstBearing, secondBearing)) > 0.5,
            "jitter must re-randomize while moving ($firstBearing vs $secondBearing)",
        )
    }
}

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Route playback and step-counter simulation. */
class RoutePlaybackTest {
    /** Roughly 111.19 m long heading due east from the origin. */
    private val straightEast = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001))

    private fun engine(
        config: SimulationConfig = SimulationConfig(),
        point: GeoPoint = GeoPoint(0.0, 0.0),
    ): SimulationEngine = SimulationEngine(point, config).apply {
        clock = { 1_700_000_000_000L }
    }

    private fun SimulationEngine.run(count: Int, stepMs: Long = 1_000L) {
        repeat(count) { tick(stepMs) }
    }

    @Test
    fun startRouteSwitchesToRouteModeAndPlays() {
        val engine = engine()
        engine.startRoute(straightEast, speedMps = 2.0)
        assertEquals(MoveMode.ROUTE, engine.state.mode)
        assertEquals(RouteState.PLAYING, engine.state.routeState)
        assertTrue(engine.running, "starting a route implies spoofing is active")
        assertEquals(2.0, engine.config.routeSpeedMps)
        assertEquals(2, engine.routePoints.size)
    }

    @Test
    fun routeAdvancesBySpeedTimesTime() {
        val engine = engine()
        engine.startRoute(straightEast, speedMps = 5.0)
        engine.run(10) // 10 s * 5 m/s = 50 m
        val progress = engine.state.routeProgress
        assertNear(50.0 / 111.19, progress, 0.005, "progress after 50 m")
        assertNear(5.0, engine.state.speedMps, 1e-9)
        assertTrue(engine.position.lon > 0.0, "must travel east")
        assertNear(90.0, engine.state.bearingDeg, 1e-6)
    }

    @Test
    fun routeProgressReachesOneAtTheEnd() {
        val engine = engine()
        engine.startRoute(straightEast, speedMps = 5.0)
        engine.run(50) // 250 m > 111.19 m
        assertEquals(1.0, engine.state.routeProgress)
        assertEquals(RouteState.FINISHED, engine.state.routeState)
        assertSamePoint(straightEast.last(), engine.position, 0.01)
    }

    @Test
    fun finishedRouteStopsMoving() {
        val engine = engine()
        engine.startRoute(straightEast, speedMps = 5.0)
        engine.run(50)
        val atEnd = engine.position
        engine.run(10)
        assertSamePoint(atEnd, engine.position, 1e-9)
        assertEquals(0.0, engine.state.speedMps)
    }

    @Test
    fun pausedRouteDoesNotAdvance() {
        val engine = engine()
        engine.startRoute(straightEast, speedMps = 5.0)
        engine.run(5)
        val progress = engine.state.routeProgress
        engine.pauseRoute()
        assertEquals(RouteState.PAUSED, engine.state.routeState)
        engine.run(10)
        assertNear(progress, engine.state.routeProgress, 1e-12)
        assertEquals(0.0, engine.state.speedMps)
    }

    @Test
    fun playRouteResumesFromThePausedProgress() {
        val engine = engine()
        engine.startRoute(straightEast, speedMps = 5.0)
        engine.run(5)
        val paused = engine.state.routeProgress
        engine.pauseRoute()
        engine.run(5)
        engine.playRoute()
        assertEquals(RouteState.PLAYING, engine.state.routeState)
        engine.run(5)
        assertTrue(engine.state.routeProgress > paused, "must continue from $paused")
    }

    @Test
    fun repeatingRouteWrapsBackToTheStart() {
        val engine = engine()
        engine.startRoute(straightEast, repeat = true, speedMps = 5.0)
        engine.run(50) // 250 m travelled over a ~111.19 m route
        val total = GeoMath.distanceMeters(straightEast[0], straightEast[1])
        val expectedProgress = (250.0 % total) / total
        assertEquals(RouteState.PLAYING, engine.state.routeState)
        assertNear(expectedProgress, engine.state.routeProgress, 0.005, "wrapped progress")
        assertTrue(engine.state.speedMps > 0.0, "a repeating route keeps moving")
    }

    @Test
    fun stopRouteUnloadsThePlayer() {
        val engine = engine()
        engine.startRoute(straightEast, speedMps = 5.0)
        engine.run(3)
        engine.stopRoute()
        assertEquals(RouteState.IDLE, engine.state.routeState)
        assertEquals(MoveMode.STATIC, engine.state.mode)
        assertTrue(engine.routePoints.isEmpty())
        assertEquals(0.0, engine.state.routeProgress)
        engine.run(5)
        assertEquals(0.0, engine.state.speedMps)
    }

    @Test
    fun routeNeedsAtLeastTwoDistinctPoints() {
        val engine = engine()
        engine.startRoute(listOf(GeoPoint(1.0, 1.0)))
        assertEquals(RouteState.IDLE, engine.state.routeState)
        assertTrue(engine.routePoints.isEmpty())
        engine.startRoute(listOf(GeoPoint(1.0, 1.0), GeoPoint(1.0, 1.0)))
        assertEquals(RouteState.IDLE, engine.state.routeState)
    }

    @Test
    fun startRouteUsesTheConfiguredSpeedWhenTheArgumentIsInvalid() {
        val engine = engine(SimulationConfig(routeSpeedMps = 4.0))
        engine.startRoute(straightEast, speedMps = -3.0)
        assertEquals(4.0, engine.config.routeSpeedMps)
        engine.run(5)
        assertNear(4.0, engine.state.speedMps, 1e-9)
    }

    @Test
    fun settingConfigReseedsTheLoadedRoutePlayer() {
        val engine = engine()
        engine.startRoute(straightEast, speedMps = 2.0)
        engine.config = engine.config.copy(routeSpeedMps = 8.0, routeRepeat = true)
        engine.run(1)
        assertNear(8.0, engine.state.speedMps, 1e-9)
        assertTrue(engine.config.routeRepeat)
    }

    @Test
    fun routeStartPlacesTheEngineOnTheFirstPoint() {
        val engine = engine(point = Reference.BEIJING)
        engine.startRoute(listOf(Reference.SHANGHAI, Reference.GUANGZHOU), speedMps = 2.0)
        assertSamePoint(Reference.SHANGHAI, engine.position, 1e-6)
        assertNear(
            GeoMath.bearingDegrees(Reference.SHANGHAI, Reference.GUANGZHOU),
            engine.state.bearingDeg,
            0.01,
        )
    }

    @Test
    fun routeIsSampledAlongSegmentsInOrder() {
        val route = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.001),
            GeoPoint(0.001, 0.001),
        )
        val engine = engine()
        engine.startRoute(route, speedMps = 2.0)
        engine.run(120) // 240 m, longer than the ~222 m route
        assertEquals(RouteState.FINISHED, engine.state.routeState)
        assertSamePoint(route.last(), engine.position, 0.01)
    }

    @Test
    fun routeBearingFollowsTheCurrentSegment() {
        val route = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.001), // east
            GeoPoint(0.001, 0.001), // then north
        )
        val engine = engine()
        engine.startRoute(route, speedMps = 5.0)
        engine.run(10)
        assertNear(90.0, engine.state.bearingDeg, 0.5, "first segment is east")
        engine.run(15) // now past the corner
        assertNear(0.0, engine.state.bearingDeg, 0.5, "second segment is north")
    }
}

/** Step-counter simulation (cadence driven, `TYPE_STEP_DETECTOR` style). */
class StepSimulationTest {
    private fun walkingEngine(
        cadence: Int = 120,
        speed: SpeedLevel = SpeedLevel.WALK,
        stepSimulation: Boolean = true,
    ): SimulationEngine {
        val engine = SimulationEngine(
            Reference.BEIJING,
            SimulationConfig(
                mode = MoveMode.JOYSTICK,
                speed = speed,
                followHeading = false,
                stepSimulation = stepSimulation,
                stepCadenceBpm = cadence,
            ),
        )
        engine.clock = { 0L }
        engine.running = true
        engine.setJoystick(0f, 1f)
        return engine
    }

    @Test
    fun stepsFollowTheCadenceAtTwoPerSecond() {
        val engine = walkingEngine(cadence = 120)
        repeat(10) { engine.tick(1_000L) }
        assertEquals(20L, engine.state.steps)
    }

    @Test
    fun cadenceIsConfigurable() {
        val engine = walkingEngine(cadence = 60)
        repeat(10) { engine.tick(1_000L) }
        assertEquals(10L, engine.state.steps)
    }

    @Test
    fun partialStepsAreCarriedOverBetweenTicks() {
        val engine = walkingEngine(cadence = 120) // 0.5 steps per 250 ms
        engine.tick(250L)
        assertEquals(0L, engine.state.steps)
        engine.tick(250L)
        assertEquals(1L, engine.state.steps)
        engine.tick(250L)
        engine.tick(250L)
        assertEquals(2L, engine.state.steps)
    }

    @Test
    fun stepsAdvanceWhileStandingStill() {
        // 步频模拟 is cadence-driven: the toggle alone is enough, no movement required. On the
        // device the acceptance check runs the engine in STATIC mode, and a counter that only
        // ticks while the position moves reads delta=0 there (this used to be the case).
        val engine = SimulationEngine(
            Reference.BEIJING,
            SimulationConfig(
                mode = MoveMode.STATIC,
                stepSimulation = true,
                stepCadenceBpm = 120,
            ),
        )
        engine.clock = { 0L }
        engine.running = true
        repeat(10) { engine.tick(1_000L) }
        assertEquals(20L, engine.state.steps, "120 bpm for 10 s is 20 steps, STATIC or not")
        assertEquals(0.0, engine.state.speedMps, "the position does not move in STATIC mode")
    }

    @Test
    fun stepsFollowTheCadenceAtWalkingSpeedToo() {
        // Below STEP_MIN_SPEED_MPS the old implementation produced nothing; the cadence, not the
        // speed, is what the counter follows now.
        val engine = SimulationEngine(
            Reference.BEIJING,
            SimulationConfig(
                mode = MoveMode.JOYSTICK,
                speed = SpeedLevel.CUSTOM,
                customSpeedMps = 0.4,
                followHeading = false,
                stepSimulation = true,
                stepCadenceBpm = 90,
            ),
        )
        engine.clock = { 0L }
        engine.running = true
        engine.setJoystick(0f, 1f)
        repeat(10) { engine.tick(1_000L) }
        // 90 bpm for 10 s = 15 steps (1.5 per second, whole steps only).
        assertEquals(15L, engine.state.steps)
    }

    @Test
    fun twelveSecondWindowMatchesTheAcceptanceCadence() {
        // The device check reads the counter over a 12 s window at 120 bpm and expects ~24 steps,
        // delivered as 500 ms ticks (what the engine ticker uses at the default interval).
        val engine = walkingEngine(cadence = 120)
        repeat(24) { engine.tick(500L) }
        assertEquals(24L, engine.state.steps)
    }

    @Test
    fun noStepsWhenStepSimulationIsDisabled() {
        val engine = walkingEngine(stepSimulation = false)
        repeat(10) { engine.tick(1_000L) }
        assertEquals(0L, engine.state.steps)
    }

    @Test
    fun noStepsWhileTheEngineIsStopped() {
        val engine = walkingEngine()
        repeat(5) { engine.tick(1_000L) }
        val steps = engine.state.steps
        engine.running = false
        repeat(5) { engine.tick(1_000L) }
        assertEquals(steps, engine.state.steps, "a stopped engine must not keep counting")
    }

    @Test
    fun stepsKeepAdvancingAfterTheJoystickIsReleased() {
        // Releasing the stick stops the position, not the counter: the toggle is still on and the
        // engine is still running, which is exactly the state a user leaves the panel in.
        val engine = walkingEngine()
        repeat(5) { engine.tick(1_000L) }
        val steps = engine.state.steps
        engine.releaseJoystick()
        repeat(5) { engine.tick(1_000L) }
        assertTrue(engine.state.steps > steps, "cadence keeps running while the engine is running")
    }

    @Test
    fun resetStepsOverridesTheCounter() {
        val engine = walkingEngine()
        repeat(5) { engine.tick(1_000L) }
        engine.resetSteps(1_000L)
        assertEquals(1_000L, engine.state.steps)
        engine.tick(1_000L)
        assertEquals(1_002L, engine.state.steps)
    }

    @Test
    fun resetStepsRejectsNegativeValues() {
        val engine = walkingEngine()
        engine.resetSteps(-50L)
        assertEquals(0L, engine.state.steps)
    }

    @Test
    fun stepDeltaPredictsTheWindowWithoutMutating() {
        val engine = walkingEngine(cadence = 120)
        engine.tick(1_000L)
        val before = engine.state.steps
        assertEquals(2, engine.stepDelta(1_000L))
        assertEquals(1, engine.stepDelta(500L))
        assertEquals(0, engine.stepDelta(0L))
        assertEquals(before, engine.state.steps, "stepDelta must not mutate the counter")
    }

    @Test
    fun stepDeltaIsZeroWhenTheFeatureIsOffOrTheEngineIsIdle() {
        val disabled = walkingEngine(stepSimulation = false)
        disabled.tick(1_000L)
        assertEquals(0, disabled.stepDelta(1_000L))
        val idle = SimulationEngine(Reference.BEIJING, SimulationConfig(stepSimulation = true))
        idle.tick(1_000L)
        assertEquals(0, idle.stepDelta(1_000L))
        val active = walkingEngine()
        active.tick(1_000L)
        assertNotEquals(0, active.stepDelta(1_000L))
    }
}

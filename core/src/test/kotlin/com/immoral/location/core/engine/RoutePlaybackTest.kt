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
import com.immoral.location.core.route.RouteGeometry
import com.immoral.location.core.route.RoutePlan
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Route playback: longitudinal model, traffic stops and step-counter simulation.
 *
 * The playback model is a *vehicle*: it accelerates at [RoutePlan.ACCELERATION_MPS2], brakes at
 * [RoutePlan.DECELERATION_MPS2], optionally stands still at a scheduled stop, and its reported
 * speed is always the distance it actually covered divided by the tick length. Every expected
 * number below is therefore derived from that model (`v·t − v²/2a` while ramping), never from a
 * hand-tuned constant.
 */
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

    /** Total arclength of the loaded route, in meters (the engine's own notion of distance). */
    private fun SimulationEngine.routeLength(): Double =
        RoutePlan(routePoints).totalDistanceMeters()

    /**
     * A dead-straight densified route of [meters] along the equator, i.e. exactly what the app
     * plays (the UI always hands the engine geometry densified to ≤ 10 m segments).
     */
    private fun straightRouteOf(meters: Double, start: GeoPoint = GeoPoint(0.0, 0.0)): List<GeoPoint> =
        RouteGeometry.densify(
            listOf(start, GeoPoint(start.lat, start.lon + meters / METERS_PER_DEGREE)),
            RouteGeometry.DEFAULT_MAX_SEGMENT_METERS,
        )

    // --------------------------------------------------------------- existing intents

    @Test
    fun startRouteSwitchesToRouteModeAndPlays() {
        val engine = engine()
        engine.startRoute(straightEast, speedMps = 2.0)
        assertEquals(MoveMode.ROUTE, engine.state.mode)
        assertEquals(RouteState.PLAYING, engine.state.routeState)
        assertTrue(engine.running, "starting a route implies spoofing is active")
        assertEquals(2.0, engine.config.routeSpeedMps)
        assertEquals(2, engine.routePoints.size)
        assertEquals(0.0, engine.state.speedMps, "a route starts from rest, not at cruise speed")
    }

    @Test
    fun routeAdvancesBySpeedTimesTime() {
        val engine = engine()
        engine.startRoute(straightEast, speedMps = 5.0)
        engine.run(10)
        val total = engine.routeLength()
        // 10 s at 5 m/s, minus the distance the ramp start costs: v²/2a = 25/6 m.
        val expectedMeters = 5.0 * 10.0 - 5.0 * 5.0 / (2.0 * RoutePlan.ACCELERATION_MPS2)
        assertNear(expectedMeters / total, engine.state.routeProgress, 0.005, "progress after 10 s")
        assertNear(5.0, engine.state.speedMps, 1e-9, "cruising speed once the ramp is over")
        assertTrue(engine.position.lon > 0.0, "must travel east")
        assertNear(90.0, engine.state.bearingDeg, 1e-6)
    }

    @Test
    fun routeProgressReachesOneAtTheEnd() {
        val engine = engine()
        engine.startRoute(straightEast, speedMps = 5.0)
        engine.run(50) // comfortably more than the ~111 m route, even with the ramps
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
        // The engine's own distance is the sum of `speed * dt` (that identity is what the
        // speed/displacement invariant below pins down), so it is a valid way to know how far the
        // vehicle actually went through the loop seam.
        var travelled = 0.0
        repeat(50) { travelled += engine.tick(1_000L).speedMps }
        val total = engine.routeLength()
        val expectedProgress = (travelled % total) / total
        assertEquals(RouteState.PLAYING, engine.state.routeState)
        assertNear(expectedProgress, engine.state.routeProgress, 0.005, "wrapped progress")
        assertNear(5.0, engine.state.speedMps, 1e-9, "a repeating route keeps moving at cruise speed")
        assertTrue(travelled > total, "the test must actually cross the seam")
    }

    @Test
    fun repeatingRouteKeepsTheLoopSeamFree() {
        // A circuit has no stop at the seam: the position wraps while the vehicle is at cruise
        // speed, so there is no fabricated braking event at the end of every lap.
        val engine = engine()
        engine.startRoute(straightEast, repeat = true, speedMps = 5.0)
        engine.run(10)
        var sawSeam = false
        var previous = engine.state.routeProgress
        repeat(40) {
            val snapshot = engine.tick(1_000L)
            if (snapshot.routeProgress < previous) sawSeam = true
            assertTrue(snapshot.speedMps >= 1.0, "the loop seam must not slow the vehicle down")
            previous = snapshot.routeProgress
        }
        assertTrue(sawSeam, "50 s at 5 m/s on a 111 m route must cross the seam")
    }

    @Test
    fun aFastPlanOnAShortLoopKeepsWrapping() {
        // One integration sub-step at 230 m/s covers 11.5 m, i.e. more than a whole lap of this
        // 5 m loop: the wrap must be a modulo, not a single subtraction that would leave the
        // progress clamped at 1.0 and "finish" a repeating route.
        val engine = engine()
        engine.startRoute(straightRouteOf(5.0), repeat = true, speedMps = 230.0)
        repeat(20) {
            val snapshot = engine.tick(1_000L)
            assertEquals(RouteState.PLAYING, snapshot.routeState, "a loop must never finish")
            assertTrue(snapshot.routeProgress in 0.0..1.0, "progress ${snapshot.routeProgress}")
            assertTrue(snapshot.speedMps > 0.0)
        }
        assertTrue(engine.state.routeProgress < 1.0)
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
        engine.run(5) // 4 m/s needs 1.33 s of ramp, so 5 s is cruise
        assertNear(4.0, engine.state.speedMps, 1e-9)
    }

    @Test
    fun settingConfigReseedsTheLoadedRoutePlayer() {
        val engine = engine()
        engine.startRoute(straightEast, speedMps = 2.0)
        engine.config = engine.config.copy(routeSpeedMps = 8.0, routeRepeat = true)
        assertEquals(8.0, engine.activeRouteSpeedMps, "the new plan speed is in force")
        engine.run(5) // 8 m/s needs 2.67 s of ramp
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
        engine.run(10) // ~46 m of the 111 m first leg
        assertNear(90.0, engine.state.bearingDeg, 0.5, "first segment is east")
        engine.run(15) // 25 s in: ~121 m, past the corner at 111 m
        assertNear(0.0, engine.state.bearingDeg, 0.5, "second segment is north")
    }

    // ------------------------------------------------- speed == displacement (invariant)

    /**
     * Runs [ticks] ticks and returns the worst disagreement, in m/s, between the reported speed and
     * the rate at which the position actually moved.
     *
     * The invariant is the whole point of the longitudinal model: a consumer that differentiates
     * two consecutive fixes must arrive at exactly `Location.getSpeed()`. Two independent measures
     * are checked per tick — progress × total length (the engine's own arclength) and the
     * great-circle distance between the two coordinates — so a bug in either the player or the
     * sampler cannot hide behind the other.
     */
    private fun SimulationEngine.worstSpeedDisagreement(
        ticks: Int,
        stepMs: Long = 1_000L,
    ): Double {
        val total = routeLength()
        val dt = stepMs / 1_000.0
        var worst = 0.0
        var previousProgress = state.routeProgress
        var previousPoint = position
        repeat(ticks) {
            val snapshot = tick(stepMs)
            val alongRoute = (snapshot.routeProgress - previousProgress) * total
            val chord = GeoMath.distanceMeters(previousPoint, snapshot.point)
            worst = max(worst, abs(alongRoute / dt - snapshot.speedMps))
            // The coordinates themselves must tell the same story (1 mm slack for the 10 m
            // densification steps of a straight line).
            assertTrue(
                abs(chord - alongRoute) <= 0.001 + 1e-6 * alongRoute,
                "position moved $chord m but the route advanced $alongRoute m",
            )
            assertTrue(
                snapshot.routeProgress >= previousProgress,
                "progress went backwards: $previousProgress -> ${snapshot.routeProgress}",
            )
            previousProgress = snapshot.routeProgress
            previousPoint = snapshot.point
        }
        return worst
    }

    @Test
    fun everyTickReportsExactlyTheDistanceItMoved() {
        val cases = listOf(
            "WALK" to SpeedLevel.WALK.defaultMetersPerSecond,
            "BIKE" to SpeedLevel.BIKE.defaultMetersPerSecond,
            "DRIVE" to SpeedLevel.DRIVE.defaultMetersPerSecond,
            "PLANE" to SpeedLevel.PLANE.defaultMetersPerSecond,
        )
        for ((label, speed) in cases) {
            val route = straightRouteOf(maxOf(400.0, speed * 120.0 + 2_000.0))
            val engine = engine()
            engine.startRoute(route, speedMps = speed)
            val worst = engine.worstSpeedDisagreement(ticks = 60)
            println("$label @ ${speed} m/s: max |speed - displacement/dt| = $worst m/s")
            assertTrue(worst <= 1e-9, "$label: speed disagreed with the displacement by $worst m/s")
        }
    }

    @Test
    fun speedAgreementHoldsAcrossRampsAndTrafficStops() {
        // Same invariant, but through every phase the model has: ramp up, cruise, brake into a
        // stop, stand still, pull away, brake into the end of the route.
        val route = straightRouteOf(6_000.0)
        val engine = engine(SimulationConfig(routeTrafficStops = true))
        engine.startRoute(route, speedMps = 13.9)
        val worst = engine.worstSpeedDisagreement(ticks = 600) // 10 minutes: stops + a finish
        assertEquals(RouteState.FINISHED, engine.state.routeState, "the 6 km route must be over")
        println("DRIVE + traffic stops: max |speed - displacement/dt| = $worst m/s over 600 ticks")
        assertTrue(worst <= 1e-9, "speed disagreed with the displacement by $worst m/s")
    }

    @Test
    fun standingStillReportsExactlyZeroAndDoesNotMove() {
        val route = straightRouteOf(6_000.0)
        val engine = engine(SimulationConfig(routeTrafficStops = true))
        engine.startRoute(route, speedMps = 13.9)
        var stationaryTicks = 0
        var checked = 0
        var previous = engine.position
        repeat(480) {
            val snapshot = engine.tick(1_000L)
            if (snapshot.speedMps == 0.0) {
                stationaryTicks++
                assertSamePoint(previous, snapshot.point, 1e-9)
                checked++
            }
            previous = snapshot.point
        }
        assertTrue(stationaryTicks > 0, "a stop must produce stationary ticks")
        assertEquals(stationaryTicks, checked)
    }

    // ------------------------------------------------------------------- ramp sanity

    @Test
    fun speedNeverChangesFasterThanTheAccelerationLimit() {
        for (stops in listOf(false, true)) {
            for (speed in listOf(1.4, 5.5, 13.9, 230.0)) {
                val route = straightRouteOf(maxOf(900.0, speed * 200.0 + 3_000.0))
                val engine = engine(SimulationConfig(routeTrafficStops = stops))
                engine.startRoute(route, speedMps = speed)
                val limit = RoutePlan.ACCELERATION_MPS2 * 1.0
                var previous = engine.state.speedMps
                var worst = 0.0
                repeat(220) {
                    val snapshot = engine.tick(1_000L)
                    worst = max(worst, abs(snapshot.speedMps - previous))
                    previous = snapshot.speedMps
                }
                // `ACCELERATION_MPS2 * dt` is the exact bound for the interval average of a
                // 3 m/s² profile; the last centimetres of a braking curve are integrated in 50 ms
                // sub-steps, so allow a hair of discretisation on top.
                println("ramp check: stops=$stops speed=$speed max |Δv| per 1 s tick = $worst m/s")
                assertTrue(
                    worst <= limit + 0.1,
                    "stops=$stops speed=$speed: speed jumped $worst m/s in one 1 s tick",
                )
            }
        }
    }

    @Test
    fun aFreshRouteStartsWithAShortRamp() {
        // Walking must be invisible: 1.4 m/s is reached in 0.47 s, so the very first 1 s tick
        // already covers 1.07 m and the second tick reports the cruise speed.
        val walk = engine()
        walk.startRoute(straightRouteOf(200.0), speedMps = 1.4)
        val firstTick = walk.tick(1_000L)
        assertTrue(firstTick.speedMps > 1.0, "a walking ramp must not cost a whole tick")
        walk.tick(1_000L)
        assertNear(1.4, walk.state.speedMps, 1e-9, "one second is plenty to reach walking speed")

        // Driving must be visible: 13.9 m/s takes 4.63 s at 3 m/s², i.e. five 1 s ticks before a
        // whole tick is spent at cruise speed.
        val drive = engine()
        drive.startRoute(straightRouteOf(4_000.0), speedMps = 13.9)
        val rampTicks = generateSequence { drive.tick(1_000L) }
            .take(30)
            .takeWhile { it.speedMps < 13.9 - 1e-9 }
            .count()
        assertEquals(
            kotlin.math.ceil(13.9 / RoutePlan.ACCELERATION_MPS2).toInt(),
            rampTicks,
            "the cruise speed must be reached on the 5th tick of a 1 s cadence",
        )
    }

    @Test
    fun routeEndBrakesInsteadOfStoppingInstantly() {
        val route = straightRouteOf(400.0)
        val engine = engine()
        engine.startRoute(route, speedMps = 13.9)
        var brakingTicks = 0
        var previous = 0.0
        repeat(120) {
            val snapshot = engine.tick(1_000L)
            if (snapshot.routeState != RouteState.PLAYING) return@repeat
            if (snapshot.speedMps < previous - 1e-9) brakingTicks++
            previous = snapshot.speedMps
        }
        assertEquals(RouteState.FINISHED, engine.state.routeState)
        assertSamePoint(route.last(), engine.position, 1e-6)
        // Braking from 13.9 m/s at 2.5 m/s² lasts v/a = 5.56 s, so at least four 1 s ticks must
        // show a falling speed before the route can be over.
        assertTrue(
            brakingTicks >= kotlin.math.floor(13.9 / RoutePlan.DECELERATION_MPS2).toInt() - 1,
            "expected several braking ticks, saw $brakingTicks",
        )
    }

    // ------------------------------------------------------------------ traffic stops

    @Test
    fun trafficStopsAreOnlyScheduledForVehicleSpeeds() {
        // Long enough for at least one stride (240 s of plan-speed driving) to fit, whatever the
        // speed: a 144 km/h route needs ~12 km before the first stop can be scheduled.
        fun stops(speed: Double) = RoutePlan(
            points = straightRouteOf(maxOf(8_000.0, speed * 400.0)),
            speedMps = speed,
        ).trafficStops

        assertTrue(stops(1.4).isEmpty(), "a walk never stands at a red light")
        assertTrue(stops(5.5).isEmpty(), "a bike is below the 8 m/s vehicle band")
        assertTrue(stops(7.9).isEmpty(), "just below the band")
        assertTrue(stops(8.0).isNotEmpty(), "just inside the band")
        assertTrue(stops(13.9).isNotEmpty(), "city driving")
        assertTrue(stops(40.0).isNotEmpty(), "just inside the band")
        assertTrue(stops(40.1).isEmpty(), "just above the band")
        assertTrue(stops(230.0).isEmpty(), "a flight that stops is not a flight")
    }

    @Test
    fun trafficStopsAreDeterministicAndPlausiblyTimed() {
        val route = straightRouteOf(20_000.0)
        val first = RoutePlan(points = route, speedMps = 13.9).trafficStops
        val second = RoutePlan(points = route, speedMps = 13.9).trafficStops
        assertEquals(first, second, "the schedule must be a pure function of the plan")
        assertTrue(first.isNotEmpty())
        var previous = 0.0
        for (stop in first) {
            assertTrue(stop.distanceMeters > previous, "stops must be in travel order")
            previous = stop.distanceMeters
            assertTrue(
                stop.waitSeconds in RoutePlan.MIN_TRAFFIC_STOP_WAIT_SECONDS..
                    RoutePlan.MAX_TRAFFIC_STOP_WAIT_SECONDS,
                "wait ${stop.waitSeconds}s is outside the documented 15..60 s",
            )
        }
        // A different geometry must produce a different schedule (otherwise the seed is constant).
        val other = RoutePlan(
            points = straightRouteOf(20_000.0, start = GeoPoint(0.0, 0.5)),
            speedMps = 13.9,
        ).trafficStops
        assertNotEquals(
            first.map { it.distanceMeters },
            other.map { it.distanceMeters },
            "the schedule must depend on the geometry",
        )
    }

    @Test
    fun aDriveWithStopsContainsSeveralFullStandstills() {
        val route = straightRouteOf(20_000.0)
        val schedule = RoutePlan(points = route, speedMps = 13.9).trafficStops
        val engine = engine(SimulationConfig(routeTrafficStops = true))
        engine.startRoute(route, speedMps = 13.9)

        val stops = ArrayList<StopEvent>()
        var current: StopEvent? = null
        while (engine.state.routeState == RouteState.PLAYING && stops.size < 200) {
            val snapshot = engine.tick(1_000L)
            if (snapshot.routeState != RouteState.PLAYING) break
            if (snapshot.speedMps == 0.0) {
                val event = current ?: StopEvent(snapshot.point, snapshot.routeProgress).also {
                    current = it
                    stops.add(it)
                }
                assertSamePoint(event.point, snapshot.point, 1e-9)
                event.seconds++
            } else {
                current = null
            }
        }
        assertEquals(RouteState.FINISHED, engine.state.routeState, "the 20 km route must be over")
        assertTrue(stops.size >= 2, "expected at least two full stops, saw ${stops.size}")
        println(
            "traffic stops observed: count=${stops.size} durations=" +
                stops.joinToString { "${it.seconds}s" } +
                " scheduled=" + schedule.size,
        )
        for (event in stops) {
            assertTrue(
                event.seconds >= RoutePlan.MIN_TRAFFIC_STOP_WAIT_SECONDS - 1 &&
                    event.seconds <= RoutePlan.MAX_TRAFFIC_STOP_WAIT_SECONDS + 1,
                "a standstill lasted ${event.seconds}s, outside 15..60 s",
            )
        }
        // Each standstill must happen at one of the scheduled points (within the 1 s tick
        // resolution of the arrival).
        val total = engine.routeLength()
        for (event in stops) {
            val meters = event.progress * total
            assertTrue(
                schedule.any { abs(it.distanceMeters - meters) <= 20.0 },
                "standstill at $meters m is not near any scheduled stop " +
                    schedule.map { it.distanceMeters },
            )
        }
        assertEquals(schedule.size, stops.size, "every scheduled stop is actually served")
    }

    @Test
    fun stopsOffKeepsTheUninterruptedDrive() {
        val route = straightRouteOf(20_000.0)
        val engine = engine(SimulationConfig(routeTrafficStops = false))
        engine.startRoute(route, speedMps = 13.9)
        var reachedCruise = false
        repeat(1_200) { // 20 minutes of driving, still short of the 20 km at 13.9 m/s
            val snapshot = engine.tick(1_000L)
            if (snapshot.speedMps >= 13.9 - 1e-9) {
                reachedCruise = true
            } else {
                assertTrue(
                    !reachedCruise,
                    "speed fell to ${snapshot.speedMps} m/s although no stop is scheduled",
                )
            }
        }
        assertTrue(reachedCruise, "the vehicle must reach the plan speed")
        assertEquals(RouteState.PLAYING, engine.state.routeState)
    }

    @Test
    fun averageSpeedWithStopsStaysWithinTheHarnessTolerance() {
        val cruise = SpeedLevel.DRIVE.defaultMetersPerSecond
        val route = straightRouteOf(20_000.0)
        val engine = engine(SimulationConfig(routeTrafficStops = true))
        engine.startRoute(route, speedMps = cruise)
        var meters = 0.0
        repeat(600) { meters += engine.tick(1_000L).speedMps } // ten minutes of 1 s ticks
        val average = meters / 600.0
        val ratio = average / cruise
        println(
            "10 min DRIVE with stops: average=${"%.3f".format(average)} m/s " +
                "ratio=${"%.3f".format(ratio)} travelled=${"%.0f".format(meters)} m",
        )
        assertTrue(
            ratio >= 0.75,
            "average speed $average m/s is below 75 % of the plan speed ($ratio)",
        )
        assertTrue(ratio <= 1.0, "average speed $average m/s cannot exceed the plan speed")
        assertEquals(RouteState.PLAYING, engine.state.routeState, "the window must stay mid-route")
    }

    @Test
    fun togglingStopsMidRouteKeepsTheProgressAndTheSpeed() {
        val route = straightRouteOf(20_000.0)
        val engine = engine(SimulationConfig(routeTrafficStops = false))
        engine.startRoute(route, speedMps = 13.9)
        engine.run(60)
        val progress = engine.state.routeProgress
        engine.config = engine.config.copy(routeTrafficStops = true)
        assertEquals(13.9, engine.activeRouteSpeedMps, "the plan speed must not be retuned")
        engine.tick(1_000L)
        assertTrue(engine.state.routeProgress > progress, "playback continues where it was")
        assertNear(13.9, engine.state.speedMps, 1e-9, "enabling stops does not slow the cruise")
    }

    // --------------------------------------------------------------------- determinism

    @Test
    fun samePlanAndSameTicksGiveTheSameTrajectory() {
        val route = straightRouteOf(12_000.0)
        val config = SimulationConfig(routeTrafficStops = true)
        val first = engine(config)
        val second = engine(config)
        first.startRoute(route, speedMps = 13.9)
        second.startRoute(route, speedMps = 13.9)
        repeat(900) {
            val a = first.tick(1_000L)
            val b = second.tick(1_000L)
            assertEquals(a.speedMps, b.speedMps, "speed diverged at tick $it")
            assertEquals(a.routeProgress, b.routeProgress, "progress diverged at tick $it")
            assertEquals(a.point, b.point, "position diverged at tick $it")
            assertEquals(a.routeState, b.routeState, "state diverged at tick $it")
        }
        assertTrue(first.state.routeProgress > 0.0)
    }

    @Test
    fun subtickDeltasIntegrateTheSameDrive() {
        // The engine has no notion of "a second": ten 100 ms ticks must integrate the same
        // longitudinal profile as one 1 s tick, to within the 50 ms sub-step discretisation.
        val route = straightRouteOf(2_000.0)
        val coarse = engine()
        val fine = engine()
        coarse.startRoute(route, speedMps = 6.0)
        fine.startRoute(route, speedMps = 6.0)
        coarse.run(40, 1_000L)
        fine.run(400, 100L)
        assertNear(coarse.state.routeProgress, fine.state.routeProgress, 0.0005, "same distance")
        assertNear(coarse.state.speedMps, fine.state.speedMps, 1e-6, "same speed")
    }

    /** One full standstill observed from the outside: where, and for how many ticks. */
    private class StopEvent(val point: GeoPoint, val progress: Double) {
        var seconds: Int = 0
    }

    private companion object {
        /** Meters per degree of longitude on the equator of the haversine model. */
        val METERS_PER_DEGREE: Double = 2.0 * Math.PI * GeoMath.EARTH_RADIUS_M / 360.0
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

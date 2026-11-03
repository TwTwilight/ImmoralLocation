package com.immoral.location.core.engine

import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulatedCell
import com.immoral.location.core.model.SimulationConfig
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The synthetic cellular network must *follow the simulated position*.
 *
 * This is the invariant behind acceptance check `6-cells`: an app that reads
 * `TelephonyManager.getAllCellInfo` before and after the position moved by ~500 m has to see a
 * different serving cell, and after ~5 km a different tracking area. Before this test existed the
 * suite only compared Beijing with Shanghai, so a set that stayed constant across a realistic
 * movement (the actual on-device defect) could not be caught here.
 *
 * A phone also expects *stability*: the same position must always produce byte-identical cells,
 * otherwise the identifiers flicker between two reads one tick apart.
 */
class CellSynthTest {

    /** Shanghai, the point the device matrix uses for `6-cells` (POINT_A). */
    private val shanghai = GeoPoint(31.230416, 121.473701)

    private fun cellsAt(point: GeoPoint): List<SimulatedCell> =
        SimulationEngine(point, SimulationConfig(cellSimulation = true)).cells()

    private fun servingAt(point: GeoPoint): SimulatedCell =
        cellsAt(point).first { it.registered }

    /** ~500 m north of [shanghai] — the movement `6-cells` performs between samples A and C. */
    private val fiveHundredMetersNorth: GeoPoint =
        GeoMath.destination(shanghai, 0.0, 500.0)

    /** ~5 km north — a different tracking area, so the TAC has to change too. */
    private val fiveKilometresNorth: GeoPoint =
        GeoMath.destination(shanghai, 0.0, 5_000.0)

    @Test
    fun theTestPointsReallyAreHalfAKilometreAndFiveKilometresApart() {
        val half = GeoMath.distanceMeters(shanghai, fiveHundredMetersNorth)
        val five = GeoMath.distanceMeters(shanghai, fiveKilometresNorth)
        assertTrue(half in 450.0..550.0, "500 m point is really $half m away")
        assertTrue(five in 4_800.0..5_200.0, "5 km point is really $five m away")
    }

    @Test
    fun servingCellChangesAfterFiveHundredMeters() {
        val before = servingAt(shanghai)
        val after = servingAt(fiveHundredMetersNorth)
        assertNotEquals(
            before.cid to before.lac,
            after.cid to after.lac,
            "the serving cell (cid/tac) did not change over ~500 m: $before -> $after",
        )
        // A handover still stays on the same operator.
        assertEquals(before.mcc, after.mcc)
        assertEquals(CellSynth.RAT_LTE, after.type)
    }

    @Test
    fun trackingAreaChangesAfterFiveKilometres() {
        val before = servingAt(shanghai)
        val after = servingAt(fiveKilometresNorth)
        assertNotEquals(
            before.lac,
            after.lac,
            "the TAC did not change over ~5 km: ${before.lac} -> ${after.lac}",
        )
        assertNotEquals(before.cid, after.cid, "the cid did not change over ~5 km")
    }

    @Test
    fun theWholeCellSetMovesWithThePosition() {
        assertNotEquals(
            cellsAt(shanghai),
            cellsAt(fiveHundredMetersNorth),
            "moving 500 m produced an identical cell list",
        )
    }

    @Test
    fun theSamePositionIsByteIdentical() {
        assertEquals(cellsAt(shanghai), cellsAt(shanghai))
        // Determinism is a product invariant: the UI preview and the hook must agree, so it may not
        // depend on the engine instance, on ticking, or on anything but the position.
        val first = SimulationEngine(shanghai, SimulationConfig(cellSimulation = true))
        val second = SimulationEngine(shanghai, SimulationConfig(cellSimulation = true))
        second.running = true
        repeat(5) { second.tick(1_000L) }
        assertEquals(first.cells(), second.cells())
    }

    @Test
    fun movingBackRestoresTheSameCells() {
        // Two-way stability: a position must map to exactly one cell set, so a route that returns to
        // its origin reports the original serving cell again instead of drifting.
        val original = cellsAt(shanghai)
        val engine = SimulationEngine(shanghai, SimulationConfig(cellSimulation = true))
        engine.setPosition(fiveHundredMetersNorth)
        assertNotEquals(original, engine.cells())
        engine.setPosition(shanghai)
        assertEquals(original, engine.cells())
    }

    @Test
    fun neighbouringPositionsStayOnAPlausibleNetwork() {
        // Sanity over a walk of 1 km in 50 m steps: every step must produce a valid Chinese cell.
        for (step in 1..20) {
            val point = GeoMath.destination(shanghai, 90.0, step * 50.0)
            val cells = cellsAt(point)
            val serving = cells.first { it.registered }
            assertEquals(CellSynth.MCC_CHINA, serving.mcc)
            assertTrue(serving.cid > 0L, "cid must be positive at $point")
            assertTrue(serving.lac in 0..65_535, "tac out of range at $point: ${serving.lac}")
        }
    }

    // ------------------------------------------------------------------ signal continuity
    //
    // The defect these guard against: the level used to be a per-grid-cell constant picked by hashing
    // the eNodeB, so a signal-strength plot showed a 220 m plateau and a step at every grid border. A
    // real report instead moves by a couple of dB per 20 m step, is continuous across a handover, and
    // is a function of the distance to the antenna rather than of the cell identity.

    /** A straight walk of [steps] × [stepM] metres from [from] along [bearingDeg]. */
    private fun walk(from: GeoPoint, bearingDeg: Double, steps: Int, stepM: Double): List<GeoPoint> =
        (0..steps).map { GeoMath.destination(from, bearingDeg, it * stepM) }

    @Test
    fun twentyMetreStepsMoveTheSignalByAFewDecibels() {
        val points = walk(shanghai, 12.0, 50, 20.0)
        val exact = points.map { CellSynth.servingRssiExact(it) }
        val reported = points.map { servingAt(it).dbm }

        var worstExact = 0.0
        var worstReported = 0
        var changed = 0
        for (step in 1 until points.size) {
            val delta = abs(exact[step] - exact[step - 1])
            worstExact = maxOf(worstExact, delta)
            assertTrue(delta > 0.0, "step $step left the level untouched at ${points[step]}")
            // The raw law is steeper where the walk passes close to a mast; the *report* saturates
            // there, which is exactly what a handset under a site does.
            assertTrue(delta <= 10.0, "step $step moved the raw level by $delta dB")
            val reportedDelta = abs(reported[step] - reported[step - 1])
            worstReported = maxOf(worstReported, reportedDelta)
            assertTrue(
                reportedDelta <= 6,
                "step $step moved the reported level by $reportedDelta dB, which is a jump, not a walk",
            )
            if (reportedDelta != 0) changed++
        }
        // A whole-dBm report cannot change on every step — it is quantised, and a sideways step at the
        // edge of a cell genuinely shifts the distance by little — but a level that only takes a
        // handful of values over a kilometre is the plateau defect again.
        assertTrue(
            changed >= (points.size - 1) * 2 / 3,
            "only $changed of ${points.size - 1} steps changed the reported level",
        )
        assertTrue(
            reported.toSet().size >= 10,
            "1 km of walking only produced these levels: ${reported.toSet()} " +
                "(worst raw step $worstExact dB, worst reported step $worstReported dB)",
        )
    }

    @Test
    fun theLevelFollowsTheDistanceToTheServingAntenna() {
        // Sweep enough positions that distances vary widely, then check the reported level against the
        // log-distance law: level_i - level_j must equal 10 n log10(d_j / d_i) up to the ±SHADOW_DB
        // slow-fading term. This is the property that makes the value a *measurement*, not a label.
        val samples = (walk(shanghai, 40.0, 30, 20.0) + walk(fiveHundredMetersNorth, 200.0, 30, 20.0))
            .map { Triple(CellSynth.antennaDistancesM(it).first(), CellSynth.servingRssiExact(it), it) }
            .filter { it.first > CellSynth.MIN_DISTANCE_M + 1.0 }
        for (a in samples) {
            for (b in samples) {
                val law = 10.0 * CellSynth.PATH_LOSS_EXPONENT * log10(b.first / a.first)
                val seen = a.second - b.second
                assertTrue(
                    abs(law - seen) <= 2.0 * CellSynth.SHADOW_DB + 1e-6,
                    "at ${a.third} vs ${b.third}: ${a.first} m vs ${b.first} m predicts $law dB, " +
                        "reported $seen dB",
                )
            }
        }
    }

    @Test
    fun theNearerAntennaIsAlwaysTheStrongerCell() {
        for (point in walk(shanghai, 33.0, 40, 25.0)) {
            val cells = cellsAt(point)
            val distances = CellSynth.antennaDistancesM(point)
            assertEquals(cells.size, distances.size, "distance list does not match the cell list")
            for (i in 1 until cells.size) {
                for (j in 1 until cells.size) {
                    if (distances[i] < distances[j]) {
                        assertTrue(
                            cells[i].dbm >= cells[j].dbm,
                            "at $point neighbour $i (${distances[i]} m, ${cells[i].dbm} dBm) is " +
                                "weaker than neighbour $j (${distances[j]} m, ${cells[j].dbm} dBm)",
                        )
                    }
                }
            }
            assertTrue(
                cells.drop(1).all { it.dbm <= cells.first().dbm },
                "at $point the serving cell ${cells.first().dbm} is not the strongest: " +
                    cells.map { it.dbm },
            )
        }
    }

    @Test
    fun aHandoverDoesNotStepTheSignal() {
        var crossings = 0
        var worst = 0
        val points = walk(shanghai, 8.0, 60, 20.0)
        for (step in 1 until points.size) {
            val before = servingAt(points[step - 1])
            val after = servingAt(points[step])
            if (before.cid == after.cid) continue
            crossings++
            worst = maxOf(worst, abs(before.dbm - after.dbm))
        }
        assertTrue(crossings >= 3, "1.2 km of walking only handed over $crossings times")
        assertTrue(worst <= 6, "a handover jumped the level by $worst dB")
    }

    @Test
    fun theLevelDoesNotStayConstantInsideOneGridCell() {
        // 30 m around one position stays inside a ~220 m eNodeB cell for most starting points, so this
        // is the "the level must not be a per-cell constant" check.
        val around = walk(shanghai, 0.0, 4, 15.0).drop(1) +
            walk(shanghai, 90.0, 4, 15.0).drop(1) +
            walk(shanghai, 180.0, 4, 15.0).drop(1)
        val centre = servingAt(shanghai)
        val sameCell = (listOf(shanghai) + around).map { servingAt(it) }.filter { it.cid == centre.cid }
        assertTrue(sameCell.size >= 5, "the sample left the grid cell: ${sameCell.size} of 13")
        assertTrue(
            sameCell.map { it.dbm }.toSet().size >= 2,
            "one grid cell reports a single level across ${sameCell.size} positions: " +
                sameCell.map { it.dbm },
        )
    }

    @Test
    fun everyReportedLevelStaysInsideTheClampWhileWalking() {
        for (point in walk(shanghai, 27.0, 80, 25.0)) {
            for (cell in cellsAt(point)) {
                assertTrue(
                    cell.dbm in CellSynth.MIN_DBM..CellSynth.MAX_DBM,
                    "$point reported ${cell.dbm} dBm outside the documented clamp",
                )
                assertEquals(CellSynth.levelFor(cell.dbm), cell.level, "$point: level/dbm mismatch")
            }
        }
    }
}

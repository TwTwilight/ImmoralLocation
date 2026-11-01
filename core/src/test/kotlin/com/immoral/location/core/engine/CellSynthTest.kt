package com.immoral.location.core.engine

import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulatedCell
import com.immoral.location.core.model.SimulationConfig
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
}

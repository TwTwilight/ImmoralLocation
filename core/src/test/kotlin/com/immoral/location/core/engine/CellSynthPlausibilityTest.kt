package com.immoral.location.core.engine

import com.immoral.location.core.Reference
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulatedCell
import com.immoral.location.core.model.SimulationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The synthetic cells must look like cells a real network could serve.
 *
 * "Plausible" here is deliberately concrete and testable without a device or a cell database:
 *
 *  * the PLMN is a **real** MCC/MNC pair (and 460 for a Chinese position),
 *  * the TAC is a 16-bit value inside that operator's band,
 *  * the ECI is a valid 3GPP identity (`eNodeB id << 8 | sector`, sector `1..255`),
 *  * the PCI is `0..503` and distinct across the reported set,
 *  * the serving cell is registered and the strongest of the set,
 *  * and the whole thing stays byte-identical for the same position while changing on movement —
 *    different ECI at handover scale (~hundreds of metres), different TAC across tracking areas
 *    (kilometres).
 *
 * The last group is what stops the identifiers from looking "freshly random each tick", which is the
 * realism defect this file guards against.
 */
class CellSynthPlausibilityTest {

    private fun cellsAt(point: GeoPoint): List<SimulatedCell> =
        SimulationEngine(point, SimulationConfig(cellSimulation = true)).cells()

    private fun servingAt(point: GeoPoint): SimulatedCell =
        cellsAt(point).first { it.registered }

    /** A handful of positions: three in China, three abroad, and one in the southern hemisphere. */
    private val positions = listOf(
        Reference.BEIJING,
        Reference.SHANGHAI,
        Reference.GUANGZHOU,
        Reference.NEW_YORK,
        GeoPoint(51.5074, -0.1278), // London
        GeoPoint(35.6762, 139.6503), // Tokyo
        GeoPoint(-33.8688, 151.2093), // Sydney (no dedicated table entry)
    )

    // ------------------------------------------------------------------ PLMN

    @Test
    fun everyReportedPlmnIsARealOperatorPair() {
        for (position in positions) {
            for (cell in cellsAt(position)) {
                assertTrue(
                    CellSynth.isKnownPlmn(cell.mcc, cell.mnc),
                    "$position produced an unknown PLMN ${cell.mcc}/${cell.mnc} (${cell.type})",
                )
            }
        }
    }

    @Test
    fun theMncAgreesWithTheMccOfItsCountry() {
        // China: only the MNCs assigned to Mobile/Unicom/Telecom.
        val chinaMncs = setOf("00", "01", "11")
        for (position in listOf(Reference.BEIJING, Reference.SHANGHAI, Reference.GUANGZHOU)) {
            val serving = servingAt(position)
            assertEquals(CellSynth.MCC_CHINA, serving.mcc, "$position is not on a Chinese MCC")
            assertTrue(serving.mnc in chinaMncs, "$position produced MNC ${serving.mnc} for MCC 460")
        }
        // Abroad: a real MCC/MNC pair, but never the Chinese MCC.
        val abroad = listOf(Reference.NEW_YORK, GeoPoint(51.5074, -0.1278), GeoPoint(35.6762, 139.6503))
        for (position in abroad) {
            val serving = servingAt(position)
            assertNotEquals(CellSynth.MCC_CHINA, serving.mcc, "$position should not report MCC 460")
            assertTrue(CellSynth.isKnownPlmn(serving.mcc, serving.mnc), "$position -> ${serving.mcc}/${serving.mnc}")
        }
    }

    @Test
    fun everyCellOfOnePositionSharesTheSameOperator() {
        // One handset has one serving network; a neighbour from another operator would be a giveaway.
        for (position in positions) {
            val cells = cellsAt(position)
            val first = cells.first()
            assertTrue(
                cells.all { it.mcc == first.mcc && it.mnc == first.mnc },
                "$position mixes operators: " + cells.map { "${it.mcc}/${it.mnc}" },
            )
        }
    }

    // ------------------------------------------------------------------ TAC

    @Test
    fun theTacIsInsideTheOperatorsBand() {
        for (position in positions) {
            val operator = CellSynth.operatorFor(position)
            for (cell in cellsAt(position)) {
                assertTrue(
                    cell.lac in operator.tacFrom..operator.tacTo,
                    "$position (${operator.plmn}) produced TAC ${cell.lac} outside " +
                        "${operator.tacFrom}..${operator.tacTo}",
                )
                assertTrue(cell.lac in 1..CellSynth.MAX_TAC, "TAC ${cell.lac} is not a 16-bit value")
            }
        }
    }

    // ------------------------------------------------------------------ ECI / PCI

    @Test
    fun theCidIsAValidEutranCellIdentity() {
        for (position in positions) {
            for (cell in cellsAt(position)) {
                assertTrue(cell.cid in 1..CellSynth.MAX_ECI, "cid ${cell.cid} outside 1..${CellSynth.MAX_ECI}")
                val sector = (cell.cid % 256L).toInt()
                val enodeb = cell.cid shr 8
                assertTrue(
                    sector in CellSynth.MIN_SECTOR..CellSynth.MAX_SECTOR,
                    "sector $sector of cid ${cell.cid} is out of range",
                )
                assertTrue(
                    enodeb in 1..CellSynth.MAX_ENODEB_ID,
                    "eNodeB id $enodeb of cid ${cell.cid} is out of range",
                )
            }
        }
    }

    @Test
    fun physicalCellIdentitiesAreDistinctAndInRange() {
        for (position in positions) {
            val pcis = cellsAt(position).map { it.pci }
            assertTrue(pcis.all { it in 0..CellSynth.MAX_PCI }, "$position: pci out of range: $pcis")
            assertEquals(pcis.size, pcis.toSet().size, "$position repeats a PCI: $pcis")
        }
    }

    // ------------------------------------------------------------------ signal

    @Test
    fun theServingCellIsRegisteredAndTheStrongest() {
        for (position in positions) {
            val cells = cellsAt(position)
            val serving = cells.first()
            assertTrue(serving.registered, "$position: the first cell must be the registered one")
            assertTrue(cells.drop(1).none { it.registered }, "$position: a neighbour is marked registered")
            assertTrue(
                cells.drop(1).all { it.dbm <= serving.dbm },
                "$position: a neighbour (${cells.drop(1).maxOf { it.dbm }}) is stronger than serving (${serving.dbm})",
            )
            for (cell in cells) {
                assertTrue(
                    cell.dbm in CellSynth.MIN_DBM..CellSynth.MAX_DBM,
                    "$position: dbm ${cell.dbm} outside ${CellSynth.MIN_DBM}..${CellSynth.MAX_DBM}",
                )
                assertEquals(CellSynth.levelFor(cell.dbm), cell.level, "$position: level does not match dbm")
                assertEquals(CellSynth.RAT_LTE, cell.type)
            }
        }
    }

    @Test
    fun neighbourCountStaysInRange() {
        for (position in positions) {
            val cells = cellsAt(position)
            assertTrue(
                cells.size in (1 + CellSynth.MIN_NEIGHBOURS)..(1 + CellSynth.MAX_NEIGHBOURS),
                "$position: ${cells.size} cells",
            )
        }
    }

    // ------------------------------------------------------------------ behaviour over a movement

    @Test
    fun theSamePositionIsByteIdentical() {
        for (position in positions) {
            assertEquals(cellsAt(position), cellsAt(position), "$position is not reproducible")
        }
    }

    @Test
    fun aMicroMovementDoesNotFlickerTheIdentifiers() {
        // 10 m: well inside one eNodeB grid cell and one tracking area, so no identifier may change.
        // This is the property that stops the set from looking random between two reads one tick
        // apart. The *level* is a distance measurement and is allowed to move by a dB or two — that
        // is what a real handset reports over ten metres — but not to jump.
        val point = Reference.SHANGHAI
        val near = GeoMath.destination(point, 0.0, 10.0)
        val before = servingAt(point)
        val after = servingAt(near)
        assertEquals(before.type, after.type, "the RAT changed over 10 m")
        assertEquals(before.mcc to before.mnc, after.mcc to after.mnc, "the PLMN changed over 10 m")
        assertEquals(before.lac, after.lac, "the TAC changed over 10 m")
        assertEquals(before.cid, after.cid, "the ECI changed over 10 m")
        assertEquals(before.pci, after.pci, "the PCI changed over 10 m")
        assertEquals(before.registered, after.registered, "registration changed over 10 m")
        assertTrue(
            kotlin.math.abs(before.dbm - after.dbm) <= 3,
            "a 10 m move moved the level from ${before.dbm} to ${after.dbm} dBm",
        )
    }

    @Test
    fun aHandoverChangesTheCidButKeepsTheTrackingArea() {
        // ~300 m: across at least one eNodeB boundary (grid ≈220 m) but still inside one TAC (~2.2 km).
        val point = Reference.SHANGHAI
        val moved = GeoMath.destination(point, 0.0, 300.0)
        val before = servingAt(point)
        val after = servingAt(moved)
        assertEquals(before.lac, after.lac, "the TAC changed inside a 300 m move (should be a handover)")
        assertNotEquals(before.cid, after.cid, "the ECI did not change across a handover")
        assertEquals(before.mcc, after.mcc, "the operator changed inside one region")
        assertEquals(before.mnc, after.mnc, "the operator changed inside one region")
    }

    @Test
    fun crossingTrackingAreasChangesTheTac() {
        val point = Reference.SHANGHAI
        val far = GeoMath.destination(point, 0.0, 5_000.0)
        val before = servingAt(point)
        val after = servingAt(far)
        assertNotEquals(before.lac, after.lac, "5 km did not leave the tracking area")
        assertNotEquals(before.cid, after.cid, "5 km did not change the ECI")
    }

    @Test
    fun walkingAcrossOneRegionKeepsThePairCoherent() {
        // 3 km east in 100 m steps: the PLMN is fixed, the TAC only ever takes few values, and every
        // step must stay a valid identity (no "half-updated" tuple at a grid boundary).
        val start = Reference.SHANGHAI
        val tacs = HashSet<Int>()
        val seen = ArrayList<SimulatedCell>()
        for (step in 0..30) {
            val point = GeoMath.destination(start, 90.0, step * 100.0)
            val serving = servingAt(point)
            assertTrue(CellSynth.isKnownPlmn(serving.mcc, serving.mnc), "step $step: bad PLMN")
            assertTrue(serving.cid in 1..CellSynth.MAX_ECI, "step $step: bad ECI ${serving.cid}")
            assertTrue(serving.pci in 0..CellSynth.MAX_PCI, "step $step: bad PCI ${serving.pci}")
            tacs.add(serving.lac)
            seen.add(serving)
        }
        assertTrue(tacs.size <= 3, "3 km of walking crossed ${tacs.size} tracking areas")
        assertTrue(seen.map { it.cid }.toSet().size > 1, "3 km of walking never handed over")
    }
}

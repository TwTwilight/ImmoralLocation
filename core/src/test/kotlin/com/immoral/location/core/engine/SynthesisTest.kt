package com.immoral.location.core.engine

import com.immoral.location.core.Reference
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Deterministic synthesis of satellites, cells and Wi-Fi access points. */
class SynthesisTest {
    private fun satellitesAt(point: GeoPoint = Reference.BEIJING) =
        SimulationEngine(point, SimulationConfig(gpsSignalSimulation = true)).satellites()

    @Test
    fun satelliteCountIsWithinTheConfiguredRange() {
        val satellites = satellitesAt()
        assertTrue(
            satellites.size in SatelliteSynth.MIN_SATELLITE_COUNT..SatelliteSynth.MAX_SATELLITE_COUNT,
            "count was ${satellites.size}",
        )
    }

    @Test
    fun satelliteConstellationsAreRealOnes() {
        val allowed = setOf(
            SatelliteSynth.CONSTELLATION_GPS,
            SatelliteSynth.CONSTELLATION_GLONASS,
            SatelliteSynth.CONSTELLATION_GALILEO,
            SatelliteSynth.CONSTELLATION_BEIDOU,
        )
        assertTrue(satellitesAt().all { it.constellation in allowed })
    }

    @Test
    fun satelliteSvidsAreUniqueAndInsideTheirConstellationRange() {
        for (position in listOf(Reference.BEIJING, Reference.SHANGHAI, Reference.NEW_YORK)) {
            val satellites = satellitesAt(position)
            val keys = satellites.map { it.constellation to it.svid }
            assertEquals(keys.size, keys.toSet().size, "duplicate satellites at $position")
            for (satellite in satellites) {
                val (from, to) = SatelliteSynth.svidRange(satellite.constellation)
                assertTrue(
                    satellite.svid in from..to,
                    "svid ${satellite.svid} outside $from..$to for ${satellite.constellation}",
                )
            }
        }
    }

    @Test
    fun satelliteSignalAndGeometryArePlausible() {
        for (satellite in satellitesAt()) {
            assertTrue(
                satellite.cn0DbHz >= SatelliteSynth.MIN_CN0_DB_HZ - 1e-3 &&
                    satellite.cn0DbHz <= SatelliteSynth.MAX_CN0_DB_HZ + 1e-3,
                "cn0 out of range: ${satellite.cn0DbHz}",
            )
            assertTrue(
                satellite.elevationDeg >= SatelliteSynth.MIN_ELEVATION_DEG - 1e-3 &&
                    satellite.elevationDeg <= SatelliteSynth.MAX_ELEVATION_DEG + 1e-3,
                "elevation out of range: ${satellite.elevationDeg}",
            )
            assertTrue(
                satellite.azimuthDeg >= 0f && satellite.azimuthDeg < 360f,
                "azimuth out of range: ${satellite.azimuthDeg}",
            )
        }
    }

    @Test
    fun usedInFixSatellitesAreTheStrongestOnes() {
        val satellites = satellitesAt()
        val used = satellites.filter { it.usedInFix }
        assertTrue(
            used.size in SatelliteSynth.MIN_USED_IN_FIX..SatelliteSynth.MAX_USED_IN_FIX,
            "used-in-fix count was ${used.size}",
        )
        val weakestUsed = used.minOf { it.cn0DbHz }
        val unused = satellites.filterNot { it.usedInFix }
        assertTrue(
            unused.all { it.cn0DbHz <= weakestUsed + 1e-3 },
            "an unused satellite is stronger than a used one",
        )
    }

    @Test
    fun satellitesAreDeterministicForTheSamePosition() {
        assertEquals(satellitesAt(), satellitesAt())
    }

    @Test
    fun satellitesDoNotDependOnTheEngineInstanceOrTicking() {
        val first = SimulationEngine(Reference.BEIJING)
        val second = SimulationEngine(Reference.BEIJING)
        second.running = true
        repeat(5) { second.tick(1_000L) }
        assertEquals(first.satellites(), second.satellites())
    }

    @Test
    fun satellitesChangeWithThePosition() {
        assertNotEquals(satellitesAt(Reference.BEIJING), satellitesAt(Reference.SHANGHAI))
    }

    @Test
    fun satelliteCountIsStableAcrossCities() {
        for (position in listOf(
            Reference.BEIJING,
            Reference.SHANGHAI,
            Reference.GUANGZHOU,
            Reference.NEW_YORK,
            GeoPoint(-33.8688, 151.2093),
        )) {
            val count = satellitesAt(position).size
            assertTrue(
                count in SatelliteSynth.MIN_SATELLITE_COUNT..SatelliteSynth.MAX_SATELLITE_COUNT,
                "$position produced $count satellites",
            )
        }
    }

    @Test
    fun constellationNamesAreHumanReadable() {
        assertEquals("GPS", SatelliteSynth.constellationName(SatelliteSynth.CONSTELLATION_GPS))
        assertEquals("GLONASS", SatelliteSynth.constellationName(SatelliteSynth.CONSTELLATION_GLONASS))
        assertEquals("GALILEO", SatelliteSynth.constellationName(SatelliteSynth.CONSTELLATION_GALILEO))
        assertEquals("BEIDOU", SatelliteSynth.constellationName(SatelliteSynth.CONSTELLATION_BEIDOU))
        assertEquals("UNKNOWN", SatelliteSynth.constellationName(42))
    }

    // ---------------------------------------------------------------- cells

    private fun cellsAt(point: GeoPoint = Reference.BEIJING) =
        SimulationEngine(point, SimulationConfig(cellSimulation = true)).cells()

    @Test
    fun cellsContainOneServingCellAndNeighbours() {
        val cells = cellsAt()
        assertTrue(
            cells.size in (1 + CellSynth.MIN_NEIGHBOURS)..(1 + CellSynth.MAX_NEIGHBOURS),
            "cell count was ${cells.size}",
        )
        assertTrue(cells.first().registered, "the serving cell must be registered")
        assertTrue(cells.drop(1).none { it.registered }, "neighbours must not be registered")
    }

    @Test
    fun cellsUseChineseOperatorIdentifiers() {
        for (cell in cellsAt()) {
            assertEquals(CellSynth.MCC_CHINA, cell.mcc)
            assertTrue(cell.mnc in listOf("00", "01", "11"), "unexpected mnc ${cell.mnc}")
            assertTrue(cell.lac in 0..65_535, "tac out of range: ${cell.lac}")
            assertTrue(cell.pci in 0..503, "pci out of range: ${cell.pci}")
            assertTrue(cell.cid > 0L, "cid must be positive")
            assertEquals(CellSynth.RAT_LTE, cell.type)
        }
    }

    @Test
    fun cellSignalStrengthIsPlausible() {
        for (cell in cellsAt()) {
            assertTrue(
                cell.dbm in CellSynth.MIN_DBM..CellSynth.MAX_DBM,
                "dbm out of range: ${cell.dbm}",
            )
            assertTrue(cell.level in 1..4, "level out of range: ${cell.level}")
            assertEquals(CellSynth.levelFor(cell.dbm), cell.level)
        }
    }

    @Test
    fun cellsAreDeterministicAndPositionSensitive() {
        assertEquals(cellsAt(), cellsAt())
        assertNotEquals(cellsAt(Reference.BEIJING), cellsAt(Reference.SHANGHAI))
    }

    @Test
    fun cellLevelMappingCoversTheBarScale() {
        assertEquals(4, CellSynth.levelFor(-75))
        assertEquals(3, CellSynth.levelFor(-90))
        assertEquals(2, CellSynth.levelFor(-100))
        assertEquals(1, CellSynth.levelFor(-110))
    }

    // ---------------------------------------------------------------- wifi

    private fun apsAt(point: GeoPoint = Reference.BEIJING) =
        SimulationEngine(point, SimulationConfig(wifiSimulation = true)).accessPoints()

    @Test
    fun accessPointCountIsWithinRange() {
        val aps = apsAt()
        assertTrue(
            aps.size in WifiSynth.MIN_APS..WifiSynth.MAX_APS,
            "ap count was ${aps.size}",
        )
    }

    @Test
    fun accessPointBssidsAreLocallyAdministeredAndUnique() {
        val aps = apsAt()
        for (ap in aps) {
            assertTrue(
                WifiSynth.isLocallyAdministered(ap.bssid),
                "bssid ${ap.bssid} is not locally administered",
            )
            assertEquals(6, ap.bssid.split(':').size)
        }
        assertEquals(aps.size, aps.map { it.bssid }.toSet().size, "duplicate BSSIDs")
    }

    @Test
    fun accessPointSsidsAreNonBlankAndUnique() {
        val aps = apsAt()
        assertTrue(aps.all { it.ssid.isNotBlank() })
        assertEquals(aps.size, aps.map { it.ssid }.toSet().size, "duplicate SSIDs")
    }

    @Test
    fun accessPointSignalAndFrequenciesArePlausible() {
        for (ap in apsAt()) {
            assertTrue(
                ap.level in WifiSynth.WEAKEST_DBM..WifiSynth.STRONGEST_DBM,
                "rssi out of range: ${ap.level}",
            )
            val is24 = ap.frequencyMhz in 2_412..2_472
            val is5 = ap.frequencyMhz in 5_180..5_825
            assertTrue(is24 || is5, "unexpected frequency ${ap.frequencyMhz}")
        }
    }

    @Test
    fun accessPointsAreSortedByStrength() {
        val levels = apsAt().map { it.level }
        assertEquals(levels.sortedDescending(), levels)
    }

    @Test
    fun accessPointsAreDeterministicAndPositionSensitive() {
        assertEquals(apsAt(), apsAt())
        assertNotEquals(apsAt(Reference.BEIJING), apsAt(Reference.SHANGHAI))
    }

    @Test
    fun locallyAdministeredCheckRejectsForeignAddresses() {
        assertTrue(WifiSynth.isLocallyAdministered("02:11:22:33:44:55"))
        assertTrue(WifiSynth.isLocallyAdministered("0a:11:22:33:44:55"))
        assertTrue(!WifiSynth.isLocallyAdministered("00:11:22:33:44:55"), "global address")
        assertTrue(!WifiSynth.isLocallyAdministered("03:11:22:33:44:55"), "multicast address")
        assertTrue(!WifiSynth.isLocallyAdministered("nonsense"))
    }
}

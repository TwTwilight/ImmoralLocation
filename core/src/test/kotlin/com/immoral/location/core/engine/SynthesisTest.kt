package com.immoral.location.core.engine

import com.immoral.location.core.Reference
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.Satellite
import com.immoral.location.core.model.SimulationConfig
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Deterministic synthesis of satellites, cells and Wi-Fi access points. */
class SynthesisTest {
    private fun satellitesAt(point: GeoPoint = Reference.BEIJING) =
        SimulationEngine(point, SimulationConfig(gpsSignalSimulation = true)).satellites()

    private fun skyAt(
        point: GeoPoint = Reference.BEIJING,
        nowMs: Long = SatelliteSynth.ANCHOR_EPOCH_MS,
    ): List<Satellite> = SatelliteSynth.synthesize(point, nowMs)

    private fun List<Satellite>.keys(): Set<Pair<Int, Int>> =
        map { it.constellation to it.svid }.toSet()

    /** Share of the larger of the two constellations that both skys have in common. */
    private fun skyOverlap(a: List<Satellite>, b: List<Satellite>): Double {
        val first = a.keys()
        val second = b.keys()
        val denominator = maxOf(first.size, second.size)
        if (denominator == 0) return 1.0
        return first.intersect(second).size.toDouble() / denominator
    }

    /** Pearson correlation coefficient of two equally sized samples. */
    private fun correlation(xs: List<Float>, ys: List<Float>): Double {
        val meanX = xs.sumOf { it.toDouble() } / xs.size
        val meanY = ys.sumOf { it.toDouble() } / ys.size
        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        for (index in xs.indices) {
            val dx = xs[index] - meanX
            val dy = ys[index] - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        return covariance / (sqrt(varianceX) * sqrt(varianceY))
    }

    @Test
    fun satelliteCountIsWithinTheConfiguredRange() {
        for (point in SAMPLE_POSITIONS) {
            val count = skyAt(point).size
            assertTrue(
                count in SatelliteSynth.MIN_SATELLITE_COUNT..SatelliteSynth.MAX_SATELLITE_COUNT,
                "$point produced $count satellites",
            )
        }
    }

    @Test
    fun satelliteConstellationsAreRealOnes() {
        val allowed = setOf(
            SatelliteSynth.CONSTELLATION_GPS,
            SatelliteSynth.CONSTELLATION_GLONASS,
            SatelliteSynth.CONSTELLATION_GALILEO,
            SatelliteSynth.CONSTELLATION_BEIDOU,
            SatelliteSynth.CONSTELLATION_QZSS,
        )
        assertTrue(satellitesAt().all { it.constellation in allowed })
    }

    @Test
    fun everyConstellationContributesToTheSky() {
        // A multi-GNSS phone in East Asia sees all five systems; that is what makes the
        // multi-talker NMEA stream honest rather than decorative.
        val constellations = skyAt(Reference.BEIJING).map { it.constellation }.toSet()
        assertEquals(
            setOf(
                SatelliteSynth.CONSTELLATION_GPS,
                SatelliteSynth.CONSTELLATION_GLONASS,
                SatelliteSynth.CONSTELLATION_GALILEO,
                SatelliteSynth.CONSTELLATION_BEIDOU,
                SatelliteSynth.CONSTELLATION_QZSS,
            ),
            constellations,
        )
    }

    @Test
    fun qzssIsOnlyVisibleInsideItsAsiaPacificCoverage() {
        assertTrue(
            skyAt(Reference.BEIJING).any { it.constellation == SatelliteSynth.CONSTELLATION_QZSS },
            "Beijing must see the Japanese augmentation system",
        )
        assertTrue(
            skyAt(Reference.NEW_YORK).none { it.constellation == SatelliteSynth.CONSTELLATION_QZSS },
            "New York cannot see QZSS",
        )
    }

    @Test
    fun satelliteSvidsAreUniqueAndInsideTheirConstellationRange() {
        for (position in SAMPLE_POSITIONS) {
            val satellites = skyAt(position)
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
        for (position in SAMPLE_POSITIONS) {
            for (satellite in skyAt(position)) {
                assertTrue(
                    satellite.cn0DbHz >= SatelliteSynth.MIN_CN0_DB_HZ - 1e-3 &&
                        satellite.cn0DbHz <= SatelliteSynth.MAX_CN0_DB_HZ + 1e-3,
                    "cn0 out of range at $position: ${satellite.cn0DbHz}",
                )
                assertTrue(
                    satellite.elevationDeg >= SatelliteSynth.MIN_ELEVATION_DEG - 1e-3 &&
                        satellite.elevationDeg <= SatelliteSynth.MAX_ELEVATION_DEG + 1e-3,
                    "elevation out of range at $position: ${satellite.elevationDeg}",
                )
                assertTrue(
                    satellite.azimuthDeg >= 0f && satellite.azimuthDeg < 360f,
                    "azimuth out of range: ${satellite.azimuthDeg}",
                )
            }
        }
    }

    @Test
    fun usedInFixIsStrongHighAndSpreadAroundTheSky() {
        for (position in SAMPLE_POSITIONS) {
            val satellites = skyAt(position)
            val used = satellites.filter { it.usedInFix }
            val unused = satellites.filterNot { it.usedInFix }
            assertTrue(
                used.size in SatelliteSynth.MIN_USED_IN_FIX..SatelliteSynth.MAX_USED_IN_FIX,
                "used-in-fix count at $position was ${used.size}",
            )
            assertTrue(
                unused.size >= 2,
                "a fix that uses every satellite in view cannot prove usedInFix is written",
            )
            assertTrue(
                used.all { it.elevationDeg >= SatelliteSynth.MIN_USED_ELEVATION_DEG },
                "a used satellite sits below the elevation mask at $position",
            )
            // Three constraints guarantee this: a 45° sector can hold at most ceil(target/4)
            // used satellites and at least eight are used.
            val sectors = used.map { SatelliteSynth.sectorOf(it.azimuthDeg) }.toSet()
            assertTrue(
                sectors.size >= 4,
                "the fix only uses ${sectors.size} azimuth sectors at $position",
            )
            // The fix is the strongest set that satisfies the constraints: every satellite left
            // out is either weaker than the weakest used one, below the elevation mask, or sits in
            // an azimuth sector that is already full.
            val weakestUsed = used.minOf { it.cn0DbHz }
            val sectorUsage = used.groupingBy { SatelliteSynth.sectorOf(it.azimuthDeg) }.eachCount()
            val sectorCap = SatelliteSynth.maxPerSectorFor(used.size)
            for (satellite in unused) {
                if (satellite.cn0DbHz <= weakestUsed) continue
                val sector = SatelliteSynth.sectorOf(satellite.azimuthDeg)
                assertTrue(
                    satellite.elevationDeg < SatelliteSynth.MIN_USED_ELEVATION_DEG ||
                        (sectorUsage[sector] ?: 0) >= sectorCap,
                    "a strong satellite at $position was skipped without a reason",
                )
            }
            assertEquals(
                satellites.maxOf { it.cn0DbHz },
                used.maxOf { it.cn0DbHz },
                "the strongest satellite in view must be part of the fix at $position",
            )
        }
    }

    @Test
    fun theUseOfASatelliteIsStableFromSecondToSecond() {
        // Flapping `usedInFix` bits once per epoch is another classic synthetic signature.
        val first = skyAt(Reference.BEIJING, SatelliteSynth.ANCHOR_EPOCH_MS)
        val second = skyAt(Reference.BEIJING, SatelliteSynth.ANCHOR_EPOCH_MS + 1_000L)
        assertEquals(
            first.filter { it.usedInFix }.map { it.constellation to it.svid }.toSet(),
            second.filter { it.usedInFix }.map { it.constellation to it.svid }.toSet(),
        )
    }

    @Test
    fun satellitesAreDeterministicForTheSamePosition() {
        assertEquals(satellitesAt(), satellitesAt())
        assertEquals(skyAt(Reference.BEIJING), skyAt(Reference.BEIJING))
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
        for (position in SAMPLE_POSITIONS) {
            val count = skyAt(position).size
            assertTrue(
                count in SatelliteSynth.MIN_SATELLITE_COUNT..SatelliteSynth.MAX_SATELLITE_COUNT,
                "$position produced $count satellites",
            )
        }
    }

    @Test
    fun theSkyKeepsItsSatellitesForTenMinutesWhileTheSignalWanders() {
        val start = SatelliteSynth.ANCHOR_EPOCH_MS
        val first = skyAt(Reference.BEIJING, start)
        var previous = first
        var maxStep = 0f
        for (tick in 1..600) {
            val current = skyAt(Reference.BEIJING, start + tick * 1_000L)
            assertEquals(
                first.keys(),
                current.keys(),
                "the constellation was re-rolled at second $tick",
            )
            val before = previous.associate { (it.constellation to it.svid) to it.cn0DbHz }
            for (satellite in current) {
                val delta = abs(satellite.cn0DbHz - before.getValue(satellite.constellation to satellite.svid))
                maxStep = maxOf(maxStep, delta)
                assertTrue(
                    delta <= SatelliteSynth.MAX_CN0_STEP_DB_HZ,
                    "C/N0 moved ${delta} dB in one second at tick $tick",
                )
            }
            previous = current
        }
        assertTrue(maxStep > 0.05f, "the C/N0 never moved: $maxStep dB over ten minutes")
    }

    @Test
    fun theGeometryDriftsInsteadOfJumping() {
        val start = SatelliteSynth.ANCHOR_EPOCH_MS
        var previous = skyAt(Reference.BEIJING, start)
        var maxElevationStep = 0.0
        var maxAzimuthStep = 0.0
        var totalMotion = 0.0
        for (tick in 1..600) {
            val current = skyAt(Reference.BEIJING, start + tick * 1_000L)
            val before = previous.associateBy { it.constellation to it.svid }
            for (satellite in current) {
                val old = before.getValue(satellite.constellation to satellite.svid)
                val elevationStep = abs(satellite.elevationDeg - old.elevationDeg).toDouble()
                val azimuthStep = abs(
                    GeoMath.shortestAngleDelta(
                        old.azimuthDeg.toDouble(),
                        satellite.azimuthDeg.toDouble(),
                    ),
                )
                maxElevationStep = maxOf(maxElevationStep, elevationStep)
                maxAzimuthStep = maxOf(maxAzimuthStep, azimuthStep)
                totalMotion += elevationStep + azimuthStep
                assertTrue(
                    elevationStep <= SatelliteSynth.MAX_ELEVATION_RATE_DEG_PER_S,
                    "elevation jumped ${elevationStep}° in one second",
                )
                assertTrue(
                    azimuthStep <= SatelliteSynth.MAX_AZIMUTH_RATE_DEG_PER_S,
                    "azimuth jumped ${azimuthStep}° in one second",
                )
            }
            previous = current
        }
        assertTrue(totalMotion > 1.0, "the geometry is frozen")
        assertTrue(maxElevationStep > 0.0, "elevation never changed")
        assertTrue(maxAzimuthStep > 0.0, "azimuth never changed")
    }

    @Test
    fun satelliteSignalIsCorrelatedWithElevation() {
        val elevations = ArrayList<Float>()
        val signals = ArrayList<Float>()
        for (position in SAMPLE_POSITIONS) {
            for (tick in 0..2) {
                for (satellite in skyAt(position, SatelliteSynth.ANCHOR_EPOCH_MS + tick * 7_000L)) {
                    elevations += satellite.elevationDeg
                    signals += satellite.cn0DbHz
                }
            }
        }
        val correlation = correlation(elevations, signals)
        assertTrue(
            correlation > 0.6,
            "high satellites must be strong ones, correlation was $correlation",
        )
    }

    @Test
    fun oneKilometreOfMovementKeepsTheSameSky() {
        val origin = Reference.BEIJING
        val base = skyAt(origin)
        for (bearing in 0 until 360 step 45) {
            val moved = GeoMath.destination(origin, bearing.toDouble(), 1_000.0)
            val overlap = skyOverlap(base, skyAt(moved))
            assertTrue(
                overlap >= 0.8,
                "walking 1 km at $bearing° changed ${(1 - overlap) * 100}% of the sky",
            )
        }
    }

    @Test
    fun distantCitiesSeeDifferentSkies() {
        val overlap = skyOverlap(skyAt(Reference.BEIJING), skyAt(Reference.NEW_YORK))
        assertTrue(overlap < 0.2, "Beijing and New York share ${overlap * 100}% of their sky")
    }

    @Test
    fun theSkyEvolvesWithTheClock() {
        val start = SatelliteSynth.ANCHOR_EPOCH_MS
        val now = skyAt(Reference.BEIJING, start)
        val later = skyAt(Reference.BEIJING, start + 1_800_000L)
        assertEquals(now.keys(), later.keys(), "half an hour must not change the constellation")
        val before = now.associate { (it.constellation to it.svid) to it.cn0DbHz }
        val moved = later.count { satellite ->
            abs(satellite.cn0DbHz - before.getValue(satellite.constellation to satellite.svid)) > 0.2f
        }
        assertTrue(moved >= 5, "only $moved satellites changed their signal in 30 minutes")
    }

    @Test
    fun constellationNamesAreHumanReadable() {
        assertEquals("GPS", SatelliteSynth.constellationName(SatelliteSynth.CONSTELLATION_GPS))
        assertEquals("GLONASS", SatelliteSynth.constellationName(SatelliteSynth.CONSTELLATION_GLONASS))
        assertEquals("GALILEO", SatelliteSynth.constellationName(SatelliteSynth.CONSTELLATION_GALILEO))
        assertEquals("BEIDOU", SatelliteSynth.constellationName(SatelliteSynth.CONSTELLATION_BEIDOU))
        assertEquals("QZSS", SatelliteSynth.constellationName(SatelliteSynth.CONSTELLATION_QZSS))
        assertEquals("UNKNOWN", SatelliteSynth.constellationName(42))
    }

    private companion object {
        /** Positions the satellite model is exercised with: cities, null island and both poles. */
        val SAMPLE_POSITIONS = listOf(
            Reference.BEIJING,
            Reference.SHANGHAI,
            Reference.GUANGZHOU,
            Reference.NEW_YORK,
            GeoPoint(-33.8688, 151.2093),
            GeoPoint(0.0, 0.0),
            GeoPoint(64.1466, -21.9426),
            GeoPoint(-89.9, 179.9),
            GeoPoint(89.9, -179.9),
        )
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
    fun accessPointBssidsAreGloballyAdministeredAndUnique() {
        // A router's radio address is burned in by its vendor: the U/L bit is clear and the prefix
        // belongs to a real vendor. See WifiSynthPlausibilityTest for the full identity checks.
        val aps = apsAt()
        for (ap in aps) {
            assertTrue(
                WifiSynth.brandOfBssid(ap.bssid) != null,
                "bssid ${ap.bssid} has no vendor OUI",
            )
            val firstOctet = ap.bssid.substringBefore(':').toInt(16)
            assertEquals(0, firstOctet and 0x02, "bssid ${ap.bssid} is locally administered")
            assertEquals(6, ap.bssid.split(':').size)
        }
        assertEquals(aps.size, aps.map { it.bssid }.toSet().size, "duplicate BSSIDs")
    }

    @Test
    fun accessPointSsidsAreRealistic() {
        // Hidden networks report an empty SSID (~1 in 12 of them) and a dual-band router announces
        // one SSID on two BSSIDs, so "non-blank and unique" is not the property to assert — what
        // must hold is that everything visible is a legal name and that most of the scan is visible.
        val aps = apsAt()
        val visible = aps.filter { it.ssid.isNotEmpty() }
        assertTrue(visible.all { it.ssid.isNotBlank() }, "a visible SSID is blank")
        assertTrue(visible.size >= aps.size - aps.size / 4, "too few visible SSIDs")
        assertTrue(
            visible.size - visible.map { it.ssid }.toSet().size <= visible.size * 2 / 3,
            "more duplicate SSIDs than dual-band pairs explain",
        )
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
}

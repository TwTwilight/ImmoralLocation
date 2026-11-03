package com.immoral.location.core.engine

import com.immoral.location.core.Reference
import com.immoral.location.core.assertNear
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.nmeaFields
import com.immoral.location.core.referenceNmeaChecksum
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** NMEA synthesis: sentence structure, talkers, derived numbers, checksums and time formatting. */
class NmeaTest {
    private val epoch = 1_609_459_200_000L // 2021-01-01T00:00:00Z

    private fun engine(point: GeoPoint = Reference.BEIJING): SimulationEngine =
        SimulationEngine(point, SimulationConfig(gpsSignalSimulation = true))

    private fun sentences(point: GeoPoint = Reference.BEIJING, nowMs: Long = epoch): List<String> =
        engine(point).nmea(nowMs)

    /** Field lists of every sentence whose talker + type ends with [suffix] (`GSV`, `GSA`, …). */
    private fun List<String>.ofType(suffix: String): List<List<String>> =
        map { nmeaFields(it) }.filter { it[0].endsWith(suffix) }

    /** SVID that a talker-specific NMEA satellite number refers to, recomputed independently. */
    private fun svidOf(talker: String, prn: Int): Int = when (talker) {
        "GL" -> prn + 64
        "GA" -> prn + 300
        "GB" -> prn + 200
        "GQ" -> prn + 192
        else -> prn
    }

    /** `GnssStatus` constellation constant behind a talker id. */
    private fun constellationOf(talker: String): Int = when (talker) {
        "GL" -> SatelliteSynth.CONSTELLATION_GLONASS
        "GA" -> SatelliteSynth.CONSTELLATION_GALILEO
        "GB" -> SatelliteSynth.CONSTELLATION_BEIDOU
        "GQ" -> SatelliteSynth.CONSTELLATION_QZSS
        else -> SatelliteSynth.CONSTELLATION_GPS
    }

    @Test
    fun nmeaContainsGgaRmcGsaVtgAndGsv() {
        val types = sentences().map { nmeaFields(it)[0] }
        assertEquals("GPGGA", types[0])
        assertEquals("GPRMC", types[1])
        assertTrue(types.any { it == "GPGSA" }, "no GSA in $types")
        assertTrue(types.any { it == "GPVTG" }, "no VTG in $types")
        assertTrue(types.any { it == "GPGSV" }, "no GPS GSV in $types")
        assertTrue(types.size >= 5, "expected at least 5 sentences, got ${types.size}")
    }

    @Test
    fun everySentenceHasAValidChecksum() {
        for (point in listOf(Reference.BEIJING, Reference.NEW_YORK, GeoPoint(0.0, 0.0))) {
            for (sentence in sentences(point)) {
                assertTrue(sentence.startsWith('$'), "missing leading \$: $sentence")
                assertTrue(sentence.contains('*'), "missing checksum marker: $sentence")
                assertEquals(
                    referenceNmeaChecksum(sentence),
                    sentence.substringAfterLast('*'),
                    "bad checksum in $sentence",
                )
                assertTrue(NmeaSynth.isChecksumValid(sentence), "isChecksumValid rejected $sentence")
            }
        }
    }

    @Test
    fun knownNmeaSentenceChecksumMatchesTheStandardExample() {
        // Textbook sentence from the NMEA 0183 documentation; XOR must be 0x47.
        val body = "GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        assertEquals("$" + body + "*47", NmeaSynth.attachChecksum(body))
    }

    @Test
    fun corruptSentencesAreRejected() {
        val sentence = sentences().first()
        val corrupted = sentence.dropLast(2) + "00"
        assertFalse(NmeaSynth.isChecksumValid(corrupted), "corrupt checksum must be rejected")
        assertFalse(NmeaSynth.isChecksumValid("not a sentence"))
        assertFalse(NmeaSynth.isChecksumValid("\$GPGGA,abc"))
    }

    @Test
    fun ggaCarriesThePosition() {
        val gga = nmeaFields(sentences()[0])
        assertEquals("GPGGA", gga[0])
        assertEquals("000000.00", gga[1])
        assertEquals("3954.2520", gga[2], "latitude field for ${Reference.BEIJING.lat}")
        assertEquals("N", gga[3])
        assertEquals("11624.4440", gga[4], "longitude field for ${Reference.BEIJING.lon}")
        assertEquals("E", gga[5])
        assertEquals("1", gga[6], "fix quality")
        assertEquals("43.5", gga[9], "altitude")
        assertEquals("M", gga[10])
        assertEquals("M", gga[12], "geoid separation unit")
    }

    @Test
    fun ggaAltitudeAndGeoidSeparationFollowThePosition() {
        for (point in listOf(Reference.BEIJING, Reference.NEW_YORK, GeoPoint(-33.8688, 151.2093, 512.0))) {
            val gga = nmeaFields(sentences(point)[0])
            assertEquals(
                String.format(java.util.Locale.US, "%.1f", point.alt),
                gga[9],
                "GGA altitude must be the simulated altitude at $point",
            )
            val geoid = gga[11].toDouble()
            assertEquals(
                NmeaSynth.geoidSeparationMeters(point.lat, point.lon),
                geoid,
                0.05,
                "GGA geoid separation at $point",
            )
            assertTrue(geoid >= NmeaSynth.MIN_GEOID_SEPARATION_M)
            assertTrue(geoid <= NmeaSynth.MAX_GEOID_SEPARATION_M)
        }
    }

    @Test
    fun ggaSatelliteCountMatchesTheGnssState() {
        val engine = engine()
        val gga = nmeaFields(engine.nmea(epoch)[0])
        val used = engine.satellites().count { it.usedInFix }
        assertEquals(used.toString().padStart(2, '0'), gga[7])
        assertTrue(
            used in SatelliteSynth.MIN_USED_IN_FIX..SatelliteSynth.MAX_USED_IN_FIX,
            "GGA claims $used satellites in the fix",
        )
    }

    @Test
    fun southernAndWesternHemispheresAreReported() {
        val sydney = GeoPoint(lat = -33.8688, lon = 151.2093)
        val gga = nmeaFields(sentences(sydney)[0])
        assertEquals("S", gga[3])
        assertEquals("E", gga[5])

        val newYork = nmeaFields(sentences(Reference.NEW_YORK)[0])
        assertEquals("N", newYork[3])
        assertEquals("W", newYork[5])
    }

    @Test
    fun rmcReportsStatusSpeedAndDate() {
        val rmc = nmeaFields(sentences()[1])
        assertEquals("GPRMC", rmc[0])
        assertEquals("000000.00", rmc[1])
        assertEquals("A", rmc[2], "status must be valid")
        assertEquals("010121", rmc[9], "date field")
        assertEquals("0.0", rmc[7], "a static engine reports zero knots")
    }

    @Test
    fun rmcConvertsMetersPerSecondToKnots() {
        val engine = movingEngine()
        val rmc = nmeaFields(engine.nmea(epoch)[1])
        val knots = rmc[7].toDouble()
        assertNear(1.4 * NmeaSynth.MPS_TO_KNOTS, knots, 0.05, "knot field")
        assertEquals("0.0", rmc[8], "course over ground")
    }

    @Test
    fun vtgReportsBothKnotsAndKilometresPerHour() {
        val engine = movingEngine()
        val vtg = engine.nmea(epoch).ofType("VTG").single()
        assertEquals("GPVTG", vtg[0])
        assertEquals("T", vtg[2])
        assertEquals("M", vtg[4])
        assertNear(1.4 * NmeaSynth.MPS_TO_KNOTS, vtg[5].toDouble(), 0.05, "knots")
        assertEquals("N", vtg[6])
        assertNear(1.4 * NmeaSynth.MPS_TO_KMH, vtg[7].toDouble(), 0.05, "km/h")
        assertEquals("K", vtg[8])
        assertEquals("A", vtg[9], "mode indicator")
    }

    @Test
    fun gsvCoversEveryConstellationWithItsOwnTalker() {
        val engine = engine()
        val gsv = engine.nmea(epoch).ofType("GSV")
        val talkers = gsv.map { it[0].removeSuffix("GSV") }.toSet()
        val expected = engine.satellites().map { NmeaSynth.talkerFor(it.constellation) }.toSet()
        assertEquals(expected, talkers, "every constellation in the status needs its own GSV talker")
        // Beijing sees all five systems, so the stream must not be GP-only any more.
        assertTrue(talkers.containsAll(setOf("GP", "GL", "GA", "GB", "GQ")), "talkers were $talkers")
    }

    @Test
    fun gsvEntriesAreTheStatusSatellitesWithTalkerSpecificNumbers() {
        val engine = engine()
        val status = engine.satellites()
        val gsv = engine.nmea(epoch).ofType("GSV")
        for (talker in gsv.map { it[0].removeSuffix("GSV") }.toSet()) {
            val messages = gsv.filter { it[0] == talker + "GSV" }
            val constellation = constellationOf(talker)
            val expected = status.filter { it.constellation == constellation }
            assertEquals(
                expected.size.toString().padStart(2, '0'),
                messages[0][3],
                "$talker GSV satellites-in-view",
            )
            assertEquals(messages[0][1].toInt(), messages.size, "$talker GSV message count")
            val reported = ArrayList<Pair<Int, Int>>()
            for ((index, fields) in messages.withIndex()) {
                assertEquals((index + 1).toString(), fields[2], "$talker GSV message number")
                assertEquals(0, (fields.size - 4) % 4, "$talker GSV entries must come in fours")
                var cursor = 4
                while (cursor < fields.size) {
                    val prn = fields[cursor].toInt()
                    val elevation = fields[cursor + 1].toInt()
                    val azimuth = fields[cursor + 2].toInt()
                    val cn0 = fields[cursor + 3].toInt()
                    val satellite = expected.single { it.svid == svidOf(talker, prn) }
                    assertEquals(satellite.elevationDeg.toInt(), elevation, "$talker PRN $prn elevation")
                    assertEquals(satellite.azimuthDeg.toInt(), azimuth, "$talker PRN $prn azimuth")
                    assertEquals(satellite.cn0DbHz.toInt(), cn0, "$talker PRN $prn C/N0")
                    reported += constellation to svidOf(talker, prn)
                    cursor += 4
                }
            }
            assertEquals(
                expected.map { it.constellation to it.svid }.toSet(),
                reported.toSet(),
                "$talker GSV must list exactly the status satellites of its constellation",
            )
        }
    }

    @Test
    fun gsaListsTheUsedPrnsPerTalkerAndAGeometricDop() {
        val engine = engine()
        val status = engine.satellites()
        val sentences = engine.nmea(epoch)
        val gsa = sentences.ofType("GSA")
        assertTrue(gsa.isNotEmpty(), "no GSA sentence")
        val used = status.filter { it.usedInFix }.map { it.constellation to it.svid }.toSet()
        val listed = HashSet<Pair<Int, Int>>()
        for (fields in gsa) {
            val talker = fields[0].removeSuffix("GSA")
            assertEquals("A", fields[1], "$talker GSA mode must be automatic")
            assertEquals("3", fields[2], "$talker GSA fix type must be 3D")
            assertEquals(18, fields.size, "$talker GSA must carry twelve PRN slots and three DOPs")
            for (index in 3..14) {
                if (fields[index].isEmpty()) continue
                listed += constellationOf(talker) to svidOf(talker, fields[index].toInt())
            }
            val pdop = fields[15].toDouble()
            val hdop = fields[16].toDouble()
            val vdop = fields[17].toDouble()
            for (dop in listOf(pdop, hdop, vdop)) {
                assertTrue(dop.isFinite() && dop > 0.0, "$talker GSA reported DOP $dop")
                assertTrue(dop < NmeaSynth.UNAVAILABLE_DOP, "$talker GSA geometry was unusable")
            }
            assertTrue(pdop >= hdop, "PDOP $pdop must not be smaller than HDOP $hdop")
            assertTrue(pdop >= vdop, "PDOP $pdop must not be smaller than VDOP $vdop")
            assertEquals(nmeaFields(sentences[0])[8], fields[16], "GGA HDOP must equal GSA HDOP")
        }
        assertEquals(used, listed, "the union of the GSA PRN lists must be the used-in-fix set")
    }

    @Test
    fun gsvAndRmcAgreeOnTheMotion() {
        val engine = movingEngine()
        val fields = engine.nmea(epoch)
        val rmc = nmeaFields(fields[1])
        val vtg = fields.ofType("VTG").single()
        assertEquals(rmc[7], vtg[5], "RMC and VTG must report the same speed in knots")
        assertEquals(rmc[8], vtg[1], "RMC and VTG must report the same course")
    }

    @Test
    fun timeAndDateFieldsAreFormatted() {
        assertEquals("000000.00", NmeaSynth.timeField(epoch))
        assertEquals("010121", NmeaSynth.dateField(epoch))
        assertEquals("010101.00", NmeaSynth.timeField(epoch + 3_661_000L))
        assertEquals(
            "290224",
            NmeaSynth.dateField(1_709_164_800_000L),
            "2024-02-29 must render as 290224",
        )
    }

    @Test
    fun nmeaIsOneHertzFriendly() {
        val engine = engine()
        val first = nmeaFields(engine.nmea(epoch)[0])
        val second = nmeaFields(engine.nmea(epoch + 1_000L)[0])
        assertEquals("000000.00", first[1])
        assertEquals("000001.00", second[1])
        assertNotEquals(first[1], second[1])
    }

    @Test
    fun differentPositionsProduceDifferentSentences() {
        assertNotEquals(sentences(), sentences(Reference.SHANGHAI))
    }

    @Test
    fun sentencesAreReproducibleForTheSameState() {
        assertEquals(sentences(), sentences())
    }

    @Test
    fun latitudeFormattingHandlesWholeAndFractionalDegrees() {
        assertEquals("3954.2520", NmeaSynth.latitudeField(39.9042))
        assertEquals("01131.0000", NmeaSynth.longitudeField(11.5166666))
        assertEquals("0000.0000", NmeaSynth.latitudeField(0.0))
        assertEquals("9000.0000", NmeaSynth.latitudeField(90.0))
    }

    @Test
    fun geoidSeparationIsPositionDependent() {
        val beijing = NmeaSynth.geoidSeparationMeters(Reference.BEIJING.lat, Reference.BEIJING.lon)
        val newYork = NmeaSynth.geoidSeparationMeters(Reference.NEW_YORK.lat, Reference.NEW_YORK.lon)
        assertTrue(
            abs(beijing - newYork) > 5.0,
            "the geoid separation must vary with position, got $beijing m vs $newYork m",
        )
        assertTrue(
            abs(beijing - 46.9) > 1.0,
            "the model still returns the old hard-coded 46.9 m",
        )

        var minimum = Double.MAX_VALUE
        var maximum = -Double.MAX_VALUE
        var lat = -90
        while (lat <= 90) {
            var lon = -180
            while (lon < 180) {
                val value = NmeaSynth.geoidSeparationMeters(lat.toDouble(), lon.toDouble())
                assertTrue(
                    value >= NmeaSynth.MIN_GEOID_SEPARATION_M &&
                        value <= NmeaSynth.MAX_GEOID_SEPARATION_M,
                    "geoid separation $value m at ($lat, $lon) is outside the plausible range",
                )
                minimum = minOf(minimum, value)
                maximum = maxOf(maximum, value)
                lon += 5
            }
            lat += 5
        }
        assertTrue(
            maximum - minimum > 40.0,
            "the geoid model spans only ${maximum - minimum} m worldwide",
        )
    }

    @Test
    fun talkerAndPrnMappingFollowsTheNmeaConvention() {
        assertEquals("GP", NmeaSynth.talkerFor(SatelliteSynth.CONSTELLATION_GPS))
        assertEquals("GL", NmeaSynth.talkerFor(SatelliteSynth.CONSTELLATION_GLONASS))
        assertEquals("GA", NmeaSynth.talkerFor(SatelliteSynth.CONSTELLATION_GALILEO))
        assertEquals("GB", NmeaSynth.talkerFor(SatelliteSynth.CONSTELLATION_BEIDOU))
        assertEquals("GQ", NmeaSynth.talkerFor(SatelliteSynth.CONSTELLATION_QZSS))
        assertEquals(5, NmeaSynth.prnFor(SatelliteSynth.CONSTELLATION_GPS, 5))
        assertEquals(1, NmeaSynth.prnFor(SatelliteSynth.CONSTELLATION_GLONASS, 65))
        assertEquals(1, NmeaSynth.prnFor(SatelliteSynth.CONSTELLATION_BEIDOU, 201))
        assertEquals(1, NmeaSynth.prnFor(SatelliteSynth.CONSTELLATION_GALILEO, 301))
        assertEquals(1, NmeaSynth.prnFor(SatelliteSynth.CONSTELLATION_QZSS, 193))
    }

    /** Engine that is actually moving at walking speed, for the RMC/VTG fields. */
    private fun movingEngine(): SimulationEngine {
        val engine = SimulationEngine(
            Reference.BEIJING,
            SimulationConfig(
                mode = com.immoral.location.core.model.MoveMode.JOYSTICK,
                followHeading = false,
            ),
        )
        engine.running = true
        engine.setJoystick(0f, 1f)
        engine.tick(1_000L)
        return engine
    }
}

package com.immoral.location.core.engine

import com.immoral.location.core.Reference
import com.immoral.location.core.assertNear
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.nmeaFields
import com.immoral.location.core.referenceNmeaChecksum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** NMEA synthesis: structure, checksums, field values and time formatting. */
class NmeaTest {
    private val epoch = 1_609_459_200_000L // 2021-01-01T00:00:00Z

    private fun engine(point: GeoPoint = Reference.BEIJING): SimulationEngine =
        SimulationEngine(point, SimulationConfig(gpsSignalSimulation = true))

    @Test
    fun nmeaContainsGgaRmcAndGsv() {
        val sentences = engine().nmea(epoch)
        assertEquals("GPGGA", nmeaFields(sentences[0])[0])
        assertEquals("GPRMC", nmeaFields(sentences[1])[0])
        assertTrue(sentences.drop(2).all { nmeaFields(it)[0] == "GPGSV" })
        assertTrue(sentences.size >= 3, "expected at least 3 sentences, got ${sentences.size}")
    }

    @Test
    fun everySentenceHasAValidChecksum() {
        for (sentence in engine().nmea(epoch)) {
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

    @Test
    fun knownNmeaSentenceChecksumMatchesTheStandardExample() {
        // Textbook sentence from the NMEA 0183 documentation; XOR must be 0x47.
        val body = "GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        assertEquals("$" + body + "*47", NmeaSynth.attachChecksum(body))
    }

    @Test
    fun corruptSentencesAreRejected() {
        val sentence = engine().nmea(epoch).first()
        val corrupted = sentence.dropLast(2) + "00"
        assertFalse(NmeaSynth.isChecksumValid(corrupted), "corrupt checksum must be rejected")
        assertFalse(NmeaSynth.isChecksumValid("not a sentence"))
        assertFalse(NmeaSynth.isChecksumValid("\$GPGGA,abc"))
    }

    @Test
    fun ggaCarriesThePosition() {
        val gga = nmeaFields(engine().nmea(epoch)[0])
        assertEquals("GPGGA", gga[0])
        assertEquals("000000.00", gga[1])
        assertEquals("3954.2520", gga[2], "latitude field for ${Reference.BEIJING.lat}")
        assertEquals("N", gga[3])
        assertEquals("11624.4440", gga[4], "longitude field for ${Reference.BEIJING.lon}")
        assertEquals("E", gga[5])
        assertEquals("1", gga[6], "fix quality")
        assertEquals("43.5", gga[9], "altitude")
        assertEquals("M", gga[10])
    }

    @Test
    fun ggaSatelliteCountMatchesTheGnssState() {
        val engine = engine()
        val gga = nmeaFields(engine.nmea(epoch)[0])
        val used = engine.satellites().count { it.usedInFix }
        assertEquals(used.toString().padStart(2, '0'), gga[7])
    }

    @Test
    fun southernAndWesternHemispheresAreReported() {
        val sydney = GeoPoint(lat = -33.8688, lon = 151.2093)
        val gga = nmeaFields(engine(sydney).nmea(epoch)[0])
        assertEquals("S", gga[3])
        assertEquals("E", gga[5])

        val newYork = nmeaFields(engine(Reference.NEW_YORK).nmea(epoch)[0])
        assertEquals("N", newYork[3])
        assertEquals("W", newYork[5])
    }

    @Test
    fun rmcReportsStatusSpeedAndDate() {
        val engine = engine()
        val rmc = nmeaFields(engine.nmea(epoch)[1])
        assertEquals("GPRMC", rmc[0])
        assertEquals("000000.00", rmc[1])
        assertEquals("A", rmc[2], "status must be valid")
        assertEquals("010121", rmc[9], "date field")
        assertEquals("0.0", rmc[7], "a static engine reports zero knots")
    }

    @Test
    fun rmcConvertsMetersPerSecondToKnots() {
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
        val rmc = nmeaFields(engine.nmea(epoch)[1])
        val knots = rmc[7].toDouble()
        assertNear(1.4 * NmeaSynth.MPS_TO_KNOTS, knots, 0.05, "knot field")
        assertEquals("0.0", rmc[8], "course over ground")
    }

    @Test
    fun gsvMessagesAreConsistent() {
        val engine = engine()
        val sentences = engine.nmea(epoch)
        val gsv = sentences.drop(2).map { nmeaFields(it) }
        val gpsInView = engine.satellites()
            .count { it.constellation == SatelliteSynth.CONSTELLATION_GPS }
        val messageCount = gsv[0][1].toInt()
        assertEquals(messageCount, gsv.size, "GSV message count must match the emitted sentences")
        for ((index, fields) in gsv.withIndex()) {
            assertEquals((index + 1).toString(), fields[2], "GSV message number")
            assertEquals(gpsInView.toString().padStart(2, '0'), fields[3], "satellites in view")
        }
    }

    @Test
    fun gsvHasAtMostFourSatellitesPerSentence() {
        val gsv = engine().nmea(epoch).drop(2).map { nmeaFields(it) }
        for (fields in gsv) {
            val satelliteFields = fields.size - 4
            assertTrue(
                satelliteFields >= 0 && satelliteFields / 4 <= 4,
                "too many satellites in one GSV: ${fields.size}",
            )
            assertEquals(0, satelliteFields % 4, "GSV satellite fields must come in fours")
        }
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
        assertNotEquals(engine().nmea(epoch), engine(Reference.SHANGHAI).nmea(epoch))
    }

    @Test
    fun sentencesAreReproducibleForTheSameState() {
        assertEquals(engine().nmea(epoch), engine().nmea(epoch))
    }

    @Test
    fun latitudeFormattingHandlesWholeAndFractionalDegrees() {
        assertEquals("3954.2520", NmeaSynth.latitudeField(39.9042))
        assertEquals("01131.0000", NmeaSynth.longitudeField(11.5166666))
        assertEquals("0000.0000", NmeaSynth.latitudeField(0.0))
        assertEquals("9000.0000", NmeaSynth.latitudeField(90.0))
    }
}

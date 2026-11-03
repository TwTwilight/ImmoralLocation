package com.immoral.location.core.engine

import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.Satellite
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Builds NMEA 0183 sentences from the simulated fix.
 *
 * A phone's `NmeaListener` sees the same stream a real GNSS chip emits, so this produces a
 * multi-constellation one:
 *
 * ```
 * $GPGGA, …                       fix data (quality, sats used, HDOP, altitude, geoid separation)
 * $GPRMC, …                       position, speed in knots, date
 * $GPGSA,A,3,12,15,18,…           satellites used for the fix + PDOP/HDOP/VDOP (one per talker)
 * $GPVTG, …                       course and speed in knots *and* km/h
 * $GPGSV / $GLGSV / $GAGSV / $GBGSV / $GQGSV   satellites in view, four per sentence
 * ```
 *
 * ### Talkers and PRN numbering
 * NMEA names the constellation in the *talker id* and numbers the satellites per constellation,
 * which is the part a naive implementation always gets wrong:
 *
 * | constellation | talker | NMEA satellite number |
 * |---|---|---|
 * | GPS | `GP` | SVID as-is |
 * | GLONASS | `GL` | `svid - 64` (slot 1..32) |
 * | Galileo | `GA` | `svid - 300` |
 * | BeiDou | `GB` | `svid - 200` |
 * | QZSS | `GQ` | `svid - 192` |
 *
 * ### Numbers that are derived, never invented
 * * `$GPGGA`'s **sats used** is exactly the number of satellites flagged `usedInFix`.
 * * Its **altitude** is exactly [GeoPoint.getAlt] and its **HDOP** is the geometric HDOP of the
 *   used subset, computed by [DopGeometry] — the very same number `AccuracySynth` turns into
 *   `Location.getAccuracy()`, so the two channels cannot contradict each other.
 * * Its **geoid separation** comes from [geoidSeparationMeters], a coarse world model; the old
 *   fixed 46.9 m value was constant everywhere on earth, which is an obvious tell.
 * * `$GSV` entries are exactly the satellites the GNSS status reports for that constellation, in
 *   the same order, using the talker-specific numbering above.
 *
 * ### Deliberately not modelled
 * No `$GPZDA`, `$GPDTM`, `$GPGBS` or proprietary sentences; no differential/GPS corrections
 * (fix quality is always `1`), no age-of-differential field, no talker-specific DOP (one combined
 * multi-GNSS solution is reported on every `GSA`, because that is the position the receiver
 * actually solves).
 *
 * All formatting uses [Locale.US], so a device with an Arabic or German locale does not emit
 * `48,07` instead of `48.07`.
 */
internal object NmeaSynth {
    /** Conversion factor from meters/second to knots. */
    const val MPS_TO_KNOTS: Double = 1.9438444924406046

    /** Conversion factor from meters/second to km/h. */
    const val MPS_TO_KMH: Double = 3.6

    /** DOP reported when the simulated geometry cannot support a fix at all. */
    const val UNAVAILABLE_DOP: Double = 99.9

    /** Lowest value the geoid model can produce, in meters (Indian Ocean low). */
    const val MIN_GEOID_SEPARATION_M: Double = -100.0

    /** Highest value the geoid model can produce, in meters (New Guinea high). */
    const val MAX_GEOID_SEPARATION_M: Double = 70.0

    /** Mean of the geoid model, in meters (its degrees-0 term). */
    const val GEOID_MEAN_M: Double = -8.0

    private const val MAX_SATS_PER_GSV = 4

    /** NMEA `GSA` carries at most twelve satellite numbers. */
    private const val MAX_PRN_PER_GSA = 12

    /** Talkers in emission order; GPS first because it is the reference constellation. */
    private val TALKER_ORDER = intArrayOf(
        SatelliteSynth.CONSTELLATION_GPS,
        SatelliteSynth.CONSTELLATION_GLONASS,
        SatelliteSynth.CONSTELLATION_GALILEO,
        SatelliteSynth.CONSTELLATION_BEIDOU,
        SatelliteSynth.CONSTELLATION_QZSS,
    )

    private val DEG_TO_RAD = PI / 180.0

    /**
     * Builds the sentence list for one 1 Hz epoch.
     *
     * @param position Position reported by the engine.
     * @param satellites Every satellite the GNSS status reports (all constellations).
     * @param speedMps Ground speed in m/s, converted to knots and km/h.
     * @param bearingDeg Course over ground in degrees, `[0,360)`.
     * @param nowMs Wall clock in epoch milliseconds; rendered as UTC time/date fields.
     */
    fun build(
        position: GeoPoint,
        satellites: List<Satellite>,
        speedMps: Double,
        bearingDeg: Double,
        nowMs: Long,
    ): List<String> {
        val dop = DopGeometry.of(satellites)
        val hdop = if (dop.usable) dop.hdop else UNAVAILABLE_DOP
        val vdop = if (dop.usable) dop.vdop else UNAVAILABLE_DOP
        val pdop = if (dop.usable) dop.pdop else UNAVAILABLE_DOP
        val usedInFix = satellites.count { it.usedInFix }

        val groups = ArrayList<Pair<Int, List<Satellite>>>(TALKER_ORDER.size)
        for (constellation in TALKER_ORDER) {
            val group = satellites.filter { it.constellation == constellation }
            if (group.isNotEmpty()) groups += constellation to group
        }

        return buildList {
            add(gga(position, usedInFix, hdop, nowMs))
            add(rmc(position, speedMps, bearingDeg, nowMs))
            // One GSA per consortium that contributed to the fix. GPS is always present so the
            // standard `$GPGSA` sentence never disappears.
            var emittedGsa = false
            for ((constellation, group) in groups) {
                val used = group.filter { it.usedInFix }
                val isGps = constellation == SatelliteSynth.CONSTELLATION_GPS
                if (isGps || used.isNotEmpty()) {
                    add(gsa(talkerFor(constellation), used, pdop, hdop, vdop))
                    emittedGsa = true
                }
            }
            if (!emittedGsa) add(gsa("GP", emptyList(), pdop, hdop, vdop))
            add(vtg(speedMps, bearingDeg))
            for ((constellation, group) in groups) {
                addAll(gsv(talkerFor(constellation), constellation, group))
            }
        }
    }

    /**
     * `$--GGA` — global positioning system fix data.
     *
     * @param satellitesUsed Number of satellites used in the fix ([Satellite.usedInFix] count).
     * @param hdop Geometric horizontal dilution of precision from [DopGeometry].
     */
    fun gga(position: GeoPoint, satellitesUsed: Int, hdop: Double, nowMs: Long): String {
        val body = buildString(80) {
            append("GPGGA,")
            append(timeField(nowMs)).append(',')
            append(latitudeField(position.lat)).append(',')
            append(hemisphere(position.lat, 'N', 'S')).append(',')
            append(longitudeField(position.lon)).append(',')
            append(hemisphere(position.lon, 'E', 'W')).append(',')
            append('1').append(',') // fix quality: GPS fix (no DGPS corrections modelled)
            append(String.format(Locale.US, "%02d", satellitesUsed.coerceIn(0, 99))).append(',')
            append(format(dopField(hdop), 1)).append(',')
            append(format(position.alt, 1)).append(",M,")
            append(format(geoidSeparationMeters(position), 1)).append(",M,")
            append(",,") // age of differential data, station id (both empty)
        }
        return attachChecksum(body)
    }

    /** `$--RMC` — recommended minimum specific GPS/transit data. */
    fun rmc(position: GeoPoint, speedMps: Double, bearingDeg: Double, nowMs: Long): String {
        val knots = if (speedMps.isFinite() && speedMps > 0.0) speedMps * MPS_TO_KNOTS else 0.0
        val course = if (bearingDeg.isFinite()) {
            ((bearingDeg % 360.0) + 360.0) % 360.0
        } else {
            0.0
        }
        val body = buildString(80) {
            append("GPRMC,")
            append(timeField(nowMs)).append(',')
            append('A').append(',') // status: valid
            append(latitudeField(position.lat)).append(',')
            append(hemisphere(position.lat, 'N', 'S')).append(',')
            append(longitudeField(position.lon)).append(',')
            append(hemisphere(position.lon, 'E', 'W')).append(',')
            append(format(knots, 1)).append(',')
            append(format(course, 1)).append(',')
            append(dateField(nowMs))
            append(",,,A") // magnetic variation, its hemisphere, mode indicator
        }
        return attachChecksum(body)
    }

    /**
     * `$--GSA` — GNSS DOP and active satellites.
     *
     * @param talker Two-letter talker id of the constellation whose satellites [used] carries.
     * @param used Satellites of *that* constellation flagged `usedInFix`.
     * @param pdop Position, [hdop] horizontal and [vdop] vertical dilution of precision of the
     *   combined fix (see the class comment: the receiver solves one position, so every talker's
     *   `GSA` carries that solution's numbers).
     */
    fun gsa(talker: String, used: List<Satellite>, pdop: Double, hdop: Double, vdop: Double): String {
        val body = buildString(80) {
            append(talker).append("GSA,")
            append('A').append(',') // mode: automatic (2D/3D chosen by the receiver)
            append('3') // fix type: 3D
            val limited = used.take(MAX_PRN_PER_GSA)
            repeat(MAX_PRN_PER_GSA) { index ->
                append(',')
                val satellite = limited.getOrNull(index)
                if (satellite != null) {
                    append(
                        String.format(
                            Locale.US,
                            "%02d",
                            prnFor(satellite.constellation, satellite.svid).coerceIn(0, 99),
                        ),
                    )
                }
            }
            append(',').append(format(dopField(pdop), 1))
            append(',').append(format(dopField(hdop), 1))
            append(',').append(format(dopField(vdop), 1))
        }
        return attachChecksum(body)
    }

    /** `$GPVTG` — course over ground and ground speed, in knots and km/h. */
    fun vtg(speedMps: Double, bearingDeg: Double): String {
        val course = if (bearingDeg.isFinite()) {
            ((bearingDeg % 360.0) + 360.0) % 360.0
        } else {
            0.0
        }
        val safeSpeed = if (speedMps.isFinite() && speedMps > 0.0) speedMps else 0.0
        val body = buildString(64) {
            append("GPVTG,")
            append(format(course, 1)).append(",T,") // true course
            append(",M,") // magnetic course: not modelled
            append(format(safeSpeed * MPS_TO_KNOTS, 1)).append(",N,")
            append(format(safeSpeed * MPS_TO_KMH, 1)).append(",K,")
            append('A') // mode indicator: autonomous
        }
        return attachChecksum(body)
    }

    /**
     * `$--GSV` — satellites in view of one constellation, four satellites per sentence.
     *
     * @param talker Two-letter talker id (`GP`, `GL`, `GA`, `GB`, `GQ`).
     * @param constellation Constellation id, used for the talker-specific satellite numbering.
     * @param satellites In-view satellites of that constellation, in status order.
     * @return one to four sentences; a single empty sentence when nothing is in view.
     */
    fun gsv(talker: String, constellation: Int, satellites: List<Satellite>): List<String> {
        val total = satellites.size
        val messageCount = if (total == 0) 1 else (total + MAX_SATS_PER_GSV - 1) / MAX_SATS_PER_GSV
        if (total == 0) {
            return listOf(attachChecksum(talker + "GSV,1,1,00"))
        }
        return (0 until messageCount).map { messageIndex ->
            val builder = StringBuilder(72)
            builder.append(talker).append("GSV,")
            builder.append(messageCount).append(',')
            builder.append(messageIndex + 1).append(',')
            builder.append(String.format(Locale.US, "%02d", total.coerceIn(0, 99)))
            val from = messageIndex * MAX_SATS_PER_GSV
            val to = minOf(from + MAX_SATS_PER_GSV, total)
            for (index in from until to) {
                val satellite = satellites[index]
                builder.append(',')
                builder.append(
                    String.format(
                        Locale.US,
                        "%02d",
                        prnFor(constellation, satellite.svid).coerceIn(0, 99),
                    ),
                )
                builder.append(',').append(satellite.elevationDeg.toInt().coerceIn(0, 90))
                builder.append(',').append(satellite.azimuthDeg.toInt().coerceIn(0, 359))
                builder.append(',').append(satellite.cn0DbHz.toInt().coerceIn(0, 99))
            }
            attachChecksum(builder.toString())
        }
    }

    /**
     * NMEA talker id for a constellation.
     *
     * @return `GP` (GPS), `GL` (GLONASS), `GA` (Galileo), `GB` (BeiDou), `GQ` (QZSS), `GI`
     *   (NavIC/IRNSS); `GP` for anything unknown, because a NMEA sentence needs *some* talker.
     */
    fun talkerFor(constellation: Int): String = when (constellation) {
        SatelliteSynth.CONSTELLATION_GLONASS -> "GL"
        SatelliteSynth.CONSTELLATION_GALILEO -> "GA"
        SatelliteSynth.CONSTELLATION_BEIDOU -> "GB"
        SatelliteSynth.CONSTELLATION_QZSS -> "GQ"
        SatelliteSynth.CONSTELLATION_IRNSS -> "GI"
        else -> "GP"
    }

    /**
     * NMEA satellite number for a `GnssStatus` SVID.
     *
     * Each constellation numbers its own satellites from 1: GLONASS slots start at SVID 65,
     * Galileo PRNs at 301, BeiDou at 201 and QZSS at 193. Reporting the raw SVID instead is the
     * classic give-away of a synthesized `GSV`.
     */
    fun prnFor(constellation: Int, svid: Int): Int = when (constellation) {
        SatelliteSynth.CONSTELLATION_GLONASS -> svid - 64
        SatelliteSynth.CONSTELLATION_GALILEO -> svid - 300
        SatelliteSynth.CONSTELLATION_BEIDOU -> svid - 200
        SatelliteSynth.CONSTELLATION_QZSS -> svid - 192
        else -> svid
    }

    /**
     * Height of the geoid above the WGS-84 ellipsoid, in meters, at [lat]/[lon].
     *
     * A real receiver reports the geoid separation from an EGM96 grid, and it varies from about
     * -106 m in the Indian Ocean to +85 m near New Guinea; the old implementation hard-coded 46.9 m
     * for the whole planet, which is wrong everywhere except a few places.
     *
     * This is a coarse, deterministic approximation: a two-degree harmonic series (the dominant
     * degrees 1-4 of the undulation) with the sign structure of the real field — negative over the
     * Americas and the Indian Ocean, positive over Europe and the western Pacific. It is smooth,
     * cheap and continuous in position, but it is **not** a geodetic model: local error of tens of
     * meters is expected, and it must not be used for anything but making the sentence look real.
     *
     * @return meters, clamped into [MIN_GEOID_SEPARATION_M]..[MAX_GEOID_SEPARATION_M].
     */
    fun geoidSeparationMeters(lat: Double, lon: Double): Double {
        if (!lat.isFinite() || !lon.isFinite()) return GEOID_MEAN_M
        val phi = lat * DEG_TO_RAD
        val lambda = lon * DEG_TO_RAD
        val cosPhi = cos(phi)
        val value = GEOID_MEAN_M +
            26.0 * cosPhi * cosPhi * cos(2.0 * lambda + 0.4) +
            18.0 * sin(2.0 * phi) * cos(lambda - 0.6) +
            9.0 * sin(4.0 * phi) +
            6.0 * cos(3.0 * lambda) * cosPhi +
            5.0 * sin(phi)
        return value.coerceIn(MIN_GEOID_SEPARATION_M, MAX_GEOID_SEPARATION_M)
    }

    /** [geoidSeparationMeters] for a [GeoPoint]. */
    fun geoidSeparationMeters(position: GeoPoint): Double =
        geoidSeparationMeters(position.lat, position.lon)

    /**
     * Appends the NMEA `*hh` XOR checksum to [body] (the sentence content without `$`).
     *
     * The checksum is the XOR of every character strictly between `$` and `*`.
     */
    fun attachChecksum(body: String): String {
        var checksum = 0
        for (ch in body) {
            checksum = checksum xor ch.code
        }
        return buildString(body.length + 5) {
            append('$')
            append(body)
            append('*')
            append(String.format(Locale.US, "%02X", checksum and 0xFF))
        }
    }

    /**
     * Recomputes the checksum of a full sentence and compares it to the transmitted one.
     *
     * @return `true` when the sentence starts with `$`, contains `*` and the XOR matches.
     */
    fun isChecksumValid(sentence: String): Boolean {
        if (!sentence.startsWith('$')) return false
        val star = sentence.lastIndexOf('*')
        if (star <= 0) return false
        val expected = sentence.substring(star + 1).trim()
        if (expected.length != 2) return false
        val transmitted = expected.toIntOrNull(16) ?: return false
        var checksum = 0
        for (index in 1 until star) {
            checksum = checksum xor sentence[index].code
        }
        return (checksum and 0xFF) == transmitted
    }

    /** `hhmmss.ss` UTC field. */
    fun timeField(nowMs: Long): String {
        val totalSeconds = Math.floorDiv(nowMs, 1_000L)
        val secondOfDay = Math.floorMod(totalSeconds, 86_400L)
        val hours = secondOfDay / 3_600L
        val minutes = (secondOfDay % 3_600L) / 60L
        val seconds = secondOfDay % 60L
        val hundredths = Math.floorMod(nowMs, 1_000L) / 10L
        return String.format(Locale.US, "%02d%02d%02d.%02d", hours, minutes, seconds, hundredths)
    }

    /** `ddmmyy` UTC field. */
    fun dateField(nowMs: Long): String {
        val days = Math.floorDiv(nowMs, 86_400_000L)
        val (year, month, day) = civilFromDays(days)
        return String.format(Locale.US, "%02d%02d%02d", day, month, year % 100)
    }

    /** Formats latitude as `ddmm.mmmm`. */
    fun latitudeField(lat: Double): String = degreesMinutes(lat, 2)

    /** Formats longitude as `dddmm.mmmm`. */
    fun longitudeField(lon: Double): String = degreesMinutes(lon, 3)

    /** DOP as printed: a positive, finite, one-decimal number, never `NaN` or `0.0`. */
    private fun dopField(value: Double): Double =
        if (!value.isFinite() || value <= 0.0 || value > UNAVAILABLE_DOP) UNAVAILABLE_DOP else value

    private fun degreesMinutes(value: Double, degreeDigits: Int): String {
        val safe = if (value.isFinite()) value else 0.0
        var degrees = abs(safe).toInt()
        var minutes = (abs(safe) - degrees) * 60.0
        // Round first, then fix up a possible 60.0000 carry so the field stays valid.
        val rounded = (minutes * 10_000.0).roundToLong() / 10_000.0
        minutes = rounded
        if (minutes >= 60.0) {
            minutes -= 60.0
            degrees += 1
        }
        return String.format(
            Locale.US,
            "%0${degreeDigits}d%07.4f",
            degrees,
            minutes,
        )
    }

    private fun hemisphere(value: Double, positive: Char, negative: Char): Char =
        if (value >= 0.0) positive else negative

    private fun format(value: Double, decimals: Int): String {
        val safe = if (value.isFinite()) value else 0.0
        return String.format(Locale.US, "%.${decimals}f", safe)
    }

    /** Converts days since the Unix epoch to `(year, month, day)` in the proleptic Gregorian calendar. */
    private fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
        val z = daysSinceEpoch + 719_468L
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val dayOfEra = z - era * 146_097L
        val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
        val year = yearOfEra + era * 400L
        val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
        val monthPrime = (5L * dayOfYear + 2L) / 153L
        val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
        val month = if (monthPrime < 10L) monthPrime + 3L else monthPrime - 9L
        val adjustedYear = if (month <= 2L) year + 1L else year
        return Triple(adjustedYear.toInt(), month.toInt(), day.toInt())
    }
}

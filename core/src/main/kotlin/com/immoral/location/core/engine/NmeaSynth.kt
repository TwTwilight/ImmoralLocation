package com.immoral.location.core.engine

import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.Satellite
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Builds NMEA 0183 sentences from the simulated position.
 *
 * Only what a location provider actually echoes back to apps is produced: `$GPGGA` (fix data),
 * `$GPRMC` (recommended minimum) and `$GPGSV` (satellites in view). Every sentence carries a
 * correct `*hh` XOR checksum, because receivers and apps validate it and a bad checksum makes
 * apps drop the fix.
 *
 * All formatting is done with [Locale.US] so a device with an Arabic or German locale does not
 * emit `48,07` instead of `48.07`.
 */
internal object NmeaSynth {
    /** Conversion factor from meters/second to knots. */
    const val MPS_TO_KNOTS: Double = 1.9438444924406046

    /** WGS-84 mean geoid separation reported in `$GPGGA`. */
    const val GEOID_SEPARATION_M: Double = 46.9

    /** Dilution of precision reported in `$GPGGA`. */
    const val HDOP: Double = 0.9

    private const val MAX_SATS_PER_GSV = 4

    /**
     * Builds the sentence list for one 1 Hz epoch.
     *
     * @param position Position reported by the engine.
     * @param satellites Satellites used for `$GPGSV` (GPS constellation only, as per NMEA).
     * @param speedMps Ground speed in m/s, converted to knots for `$GPRMC`.
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
        val gpsSatellites = satellites.filter {
            it.constellation == SatelliteSynth.CONSTELLATION_GPS
        }
        val usedInFix = satellites.count { it.usedInFix }
        return buildList {
            add(gga(position, usedInFix, nowMs))
            add(rmc(position, speedMps, bearingDeg, nowMs))
            addAll(gsv(gpsSatellites))
        }
    }

    /**
     * `$GPGGA` — global positioning system fix data.
     *
     * @param satellitesUsed Number of satellites used in the fix.
     */
    fun gga(position: GeoPoint, satellitesUsed: Int, nowMs: Long): String {
        val body = buildString(72) {
            append("GPGGA,")
            append(timeField(nowMs)).append(',')
            append(latitudeField(position.lat)).append(',')
            append(hemisphere(position.lat, 'N', 'S')).append(',')
            append(longitudeField(position.lon)).append(',')
            append(hemisphere(position.lon, 'E', 'W')).append(',')
            append('1').append(',') // fix quality: GPS fix
            append(String.format(Locale.US, "%02d", satellitesUsed.coerceIn(0, 99))).append(',')
            append(format(HDOP, 1)).append(',')
            append(format(position.alt, 1)).append(",M,")
            append(format(GEOID_SEPARATION_M, 1)).append(",M,")
            append(",,") // age of differential data, station id (both empty)
        }
        return attachChecksum(body)
    }

    /** `$GPRMC` — recommended minimum specific GPS/transit data. */
    fun rmc(position: GeoPoint, speedMps: Double, bearingDeg: Double, nowMs: Long): String {
        val knots = if (speedMps.isFinite() && speedMps > 0.0) speedMps * MPS_TO_KNOTS else 0.0
        val course = if (bearingDeg.isFinite()) {
            ((bearingDeg % 360.0) + 360.0) % 360.0
        } else {
            0.0
        }
        val body = buildString(72) {
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
     * `$GPGSV` — satellites in view, four satellites per sentence.
     *
     * @return one to four sentences; a single empty sentence when nothing is in view.
     */
    fun gsv(gpsSatellites: List<Satellite>): List<String> {
        val total = gpsSatellites.size
        val messageCount = if (total == 0) 1 else (total + MAX_SATS_PER_GSV - 1) / MAX_SATS_PER_GSV
        if (total == 0) {
            return listOf(attachChecksum("GPGSV,1,1,00"))
        }
        return (0 until messageCount).map { messageIndex ->
            val builder = StringBuilder(64)
            builder.append("GPGSV,")
            builder.append(messageCount).append(',')
            builder.append(messageIndex + 1).append(',')
            builder.append(String.format(Locale.US, "%02d", total.coerceIn(0, 99)))
            val from = messageIndex * MAX_SATS_PER_GSV
            val to = minOf(from + MAX_SATS_PER_GSV, total)
            for (index in from until to) {
                val satellite = gpsSatellites[index]
                builder.append(',')
                builder.append(String.format(Locale.US, "%02d", satellite.svid.coerceIn(0, 99)))
                builder.append(',').append((satellite.elevationDeg.toInt()).coerceIn(0, 90))
                builder.append(',').append((satellite.azimuthDeg.toInt()).coerceIn(0, 359))
                builder.append(',').append((satellite.cn0DbHz.toInt()).coerceIn(0, 99))
            }
            attachChecksum(builder.toString())
        }
    }

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

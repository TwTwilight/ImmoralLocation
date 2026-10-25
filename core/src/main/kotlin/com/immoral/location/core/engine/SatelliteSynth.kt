package com.immoral.location.core.engine

import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.Satellite

/**
 * Deterministic GNSS constellation synthesis.
 *
 * The result is a pure function of the position: the same coordinates always produce the same
 * satellites, so the app UI and the hook layer inside `system_server` agree. The values mimic
 * what `GnssStatus` reports in an urban environment: a handful of satellites per constellation,
 * C/N0 spread over 20..45 dBHz and a subset used in the fix.
 */
internal object SatelliteSynth {
    /** `GnssStatus.CONSTELLATION_GPS`, inlined so `:core` stays Android-free. */
    const val CONSTELLATION_GPS: Int = 1

    /** `GnssStatus.CONSTELLATION_SBAS`. */
    const val CONSTELLATION_SBAS: Int = 2

    /** `GnssStatus.CONSTELLATION_GLONASS`. */
    const val CONSTELLATION_GLONASS: Int = 3

    /** `GnssStatus.CONSTELLATION_QZSS`. */
    const val CONSTELLATION_QZSS: Int = 4

    /** `GnssStatus.CONSTELLATION_BEIDOU`. */
    const val CONSTELLATION_BEIDOU: Int = 5

    /** `GnssStatus.CONSTELLATION_GALILEO`. */
    const val CONSTELLATION_GALILEO: Int = 6

    /** `GnssStatus.CONSTELLATION_IRNSS`. */
    const val CONSTELLATION_IRNSS: Int = 7

    /** Lowest simulated C/N0 in dB-Hz. */
    const val MIN_CN0_DB_HZ: Float = 20f

    /** Highest simulated C/N0 in dB-Hz. */
    const val MAX_CN0_DB_HZ: Float = 45f

    /** Lowest simulated elevation in degrees. */
    const val MIN_ELEVATION_DEG: Float = 10f

    /** Highest simulated elevation in degrees. */
    const val MAX_ELEVATION_DEG: Float = 85f

    private val CONSTELLATION_PATTERN = intArrayOf(
        CONSTELLATION_GPS,
        CONSTELLATION_GPS,
        CONSTELLATION_GLONASS,
        CONSTELLATION_GPS,
        CONSTELLATION_GALILEO,
        CONSTELLATION_BEIDOU,
        CONSTELLATION_GPS,
        CONSTELLATION_GALILEO,
        CONSTELLATION_GLONASS,
        CONSTELLATION_BEIDOU,
        CONSTELLATION_GPS,
        CONSTELLATION_GALILEO,
        CONSTELLATION_BEIDOU,
        CONSTELLATION_GLONASS,
    )

    /**
     * Synthesizes 8..14 satellites for [position].
     *
     * @return satellites ordered by descending C/N0; those used in the fix form a prefix.
     */
    fun synthesize(position: GeoPoint): List<Satellite> {
        val random = SeededRandom(seedFor(position, SALT_SATELLITES))
        val count = random.nextInt(MIN_SATELLITE_COUNT, MAX_SATELLITE_COUNT)
        val rotation = random.nextInt(CONSTELLATION_PATTERN.size)
        val usedSvids = HashMap<Int, MutableSet<Int>>()
        val draft = ArrayList<Satellite>(count)
        for (index in 0 until count) {
            val constellation = CONSTELLATION_PATTERN[(rotation + index) % CONSTELLATION_PATTERN.size]
            val svid = uniqueSvid(constellation, random, usedSvids)
            draft.add(
                Satellite(
                    svid = svid,
                    constellation = constellation,
                    cn0DbHz = random.nextDouble(MIN_CN0_DB_HZ.toDouble(), MAX_CN0_DB_HZ.toDouble())
                        .toFloat(),
                    usedInFix = false,
                    elevationDeg = random.nextDouble(
                        MIN_ELEVATION_DEG.toDouble(),
                        MAX_ELEVATION_DEG.toDouble(),
                    ).toFloat().coerceAtMost(MAX_ELEVATION_DEG),
                    azimuthDeg = random.nextDouble(0.0, 360.0).toFloat(),
                ),
            )
        }
        draft.sortByDescending { it.cn0DbHz }
        val usedCount = random.nextInt(MIN_USED_IN_FIX, MAX_USED_IN_FIX)
            .coerceAtMost(draft.size)
        return draft.mapIndexed { index, satellite ->
            if (index < usedCount) satellite.copy(usedInFix = true) else satellite
        }
    }

    private fun uniqueSvid(
        constellation: Int,
        random: SeededRandom,
        used: MutableMap<Int, MutableSet<Int>>,
    ): Int {
        val (from, to) = svidRange(constellation)
        val bucket = used.getOrPut(constellation) { HashSet() }
        var attempt = 0
        while (attempt < 64) {
            val candidate = random.nextInt(from, to)
            if (bucket.add(candidate)) return candidate
            attempt++
        }
        // Ranges are wide enough that this is effectively unreachable; stay deterministic.
        for (candidate in from..to) {
            if (bucket.add(candidate)) return candidate
        }
        return from
    }

    /** SVID range used by `GnssStatus` for the given constellation. */
    fun svidRange(constellation: Int): Pair<Int, Int> = when (constellation) {
        CONSTELLATION_GPS -> 1 to 32
        CONSTELLATION_SBAS -> 33 to 64
        CONSTELLATION_GLONASS -> 65 to 96
        CONSTELLATION_QZSS -> 193 to 200
        CONSTELLATION_BEIDOU -> 201 to 237
        CONSTELLATION_GALILEO -> 301 to 336
        CONSTELLATION_IRNSS -> 401 to 414
        else -> 1 to 32
    }

    /** Human readable constellation name, handy for `GnssStatus` debugging output. */
    fun constellationName(constellation: Int): String = when (constellation) {
        CONSTELLATION_GPS -> "GPS"
        CONSTELLATION_SBAS -> "SBAS"
        CONSTELLATION_GLONASS -> "GLONASS"
        CONSTELLATION_QZSS -> "QZSS"
        CONSTELLATION_BEIDOU -> "BEIDOU"
        CONSTELLATION_GALILEO -> "GALILEO"
        CONSTELLATION_IRNSS -> "IRNSS"
        else -> "UNKNOWN"
    }

    /** Lowest number of simulated satellites. */
    const val MIN_SATELLITE_COUNT: Int = 8

    /** Highest number of simulated satellites. */
    const val MAX_SATELLITE_COUNT: Int = 14

    /** Lowest number of satellites reported as used in the fix. */
    const val MIN_USED_IN_FIX: Int = 7

    /** Highest number of satellites reported as used in the fix. */
    const val MAX_USED_IN_FIX: Int = 9

    /** Signal-bars style level `0..4` derived from a C/N0 value. */
    fun signalLevel(cn0DbHz: Float): Int = when {
        cn0DbHz >= 40f -> 4
        cn0DbHz >= 32f -> 3
        cn0DbHz >= 25f -> 2
        cn0DbHz >= 20f -> 1
        else -> 0
    }
}

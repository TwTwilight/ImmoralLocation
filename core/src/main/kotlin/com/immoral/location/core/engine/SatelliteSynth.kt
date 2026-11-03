package com.immoral.location.core.engine

import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.Satellite
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Deterministic GNSS constellation synthesis — a simulated *sky*, not a random sample.
 *
 * ### What changed and why
 * The first version seeded everything from `seedFor(position, salt)`, i.e. from the coordinates
 * rounded to ~11 m, and had no clock at all. Two consequences made it trivially detectable:
 *
 * * walking 11 m re-rolled the entire constellation — new SVIDs, new elevations, new C/N0 — which
 *   no receiver does (a phone keeps the same satellites for hours), and
 * * standing still produced a byte-identical `GnssStatus` forever, while a real C/N0 wanders by
 *   ±2-3 dB from second to second.
 *
 * The model here is a pure function of `(position, nowMs)`:
 *
 * 1. **Sky anchor.** The *set* of satellites a receiver can track is chosen from a coarse
 *    [ANCHOR_GRID_DEG] cell (~55 km at the equator), per constellation, so ordinary movement
 *    never re-rolls the sky and a teleport does.
 * 2. **Orbit-like motion.** Every satellite gets a slowly evolving elevation/azimuth track
 *    (periods of 40 min to 5.8 h), so the geometry drifts instead of jumping, and the C/N0 follows
 *    the elevation the way a real link budget does.
 * 3. **Slow signal wander.** Two slow sines (40..300 s) plus two fast ripples (8..23 s) ride on
 *    top of the elevation term. Their amplitudes are chosen so a 1 s step can never move the
 *    reported C/N0 by more than [MAX_CN0_STEP_DB_HZ].
 *
 * The visible set is stable for at least ten minutes by construction: modelled elevations live in
 * 15°..83°, so nothing crosses the [MIN_ELEVATION_DEG] horizon mask and no satellite rises or sets
 * inside a session.
 *
 * ### Deliberately *not* modelled
 * This is a plausible sky, not a GNSS simulation. There is **no ephemeris or almanac**, no
 * Keplerian orbit propagation, no ionospheric/tropospheric delay, no multipath, no antenna or
 * body-shadowing pattern, no satellite rise/set, and no DOP/accuracy coupling (that lives in
 * [DopGeometry] and `AccuracySynth`). Crossing an anchor cell replaces the whole sky at once
 * instead of handing satellites over gradually. Everything is deterministic and allocation-light
 * so the hook inside `system_server` and the app UI compute byte-identical values — the
 * acceptance harness compares exactly that.
 *
 * ### Guarantees (all asserted by the `:core` tests)
 * * in-view count in [MIN_SATELLITE_COUNT]..[MAX_SATELLITE_COUNT], used-in-fix in
 *   [MIN_USED_IN_FIX]..[MAX_USED_IN_FIX] with at least two unused satellites left over;
 * * elevation `[MIN_ELEVATION_DEG, MAX_ELEVATION_DEG]`, azimuth `[0, 360)`, C/N0
 *   `[MIN_CN0_DB_HZ, MAX_CN0_DB_HZ]`;
 * * `|Δ elevation| <= 0.15 °/s` and `|Δ azimuth| <= 0.4 °/s` (shortest arc);
 * * `|Δ C/N0| <= 1.5 dB` between consecutive 1 s samples, and a stable SVID set over 600 s;
 * * C/N0 strongly correlated with elevation;
 * * two points 1 km apart keep (almost always) the same sky; distant cities do not.
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

    /** Lowest simulated C/N0 in dB-Hz (a barely usable, heavily attenuated signal). */
    const val MIN_CN0_DB_HZ: Float = 18f

    /** Highest simulated C/N0 in dB-Hz (open-sky zenith, high-end phone). */
    const val MAX_CN0_DB_HZ: Float = 48f

    /** Largest C/N0 change the model allows between two consecutive 1 s samples, in dB. */
    const val MAX_CN0_STEP_DB_HZ: Float = 1.5f

    /** Largest elevation change the model allows per second, in degrees. */
    const val MAX_ELEVATION_RATE_DEG_PER_S: Float = 0.15f

    /** Largest azimuth change the model allows per second, in degrees (shortest arc). */
    const val MAX_AZIMUTH_RATE_DEG_PER_S: Float = 0.4f

    /** Lowest elevation at which a satellite is reported at all, in degrees. */
    const val MIN_ELEVATION_DEG: Float = 5f

    /** Highest simulated elevation in degrees. */
    const val MAX_ELEVATION_DEG: Float = 89f

    /** Lowest simulated number of satellites in view. */
    const val MIN_SATELLITE_COUNT: Int = 14

    /** Highest simulated number of satellites in view. */
    const val MAX_SATELLITE_COUNT: Int = 28

    /** Lowest number of satellites reported as used in the fix. */
    const val MIN_USED_IN_FIX: Int = 8

    /** Highest number of satellites reported as used in the fix. */
    const val MAX_USED_IN_FIX: Int = 16

    /** Lowest elevation, in degrees, at which a satellite may be used in the fix. */
    const val MIN_USED_ELEVATION_DEG: Float = 12f

    /** Number of equal azimuth sectors the fix selection spreads the used satellites over. */
    const val AZIMUTH_SECTORS: Int = 8

    /** Most satellites of a single constellation that are used in the fix (NMEA GSA has 12 slots). */
    const val MAX_USED_PER_CONSTELLATION: Int = 12

    /** Edge length of one sky-anchor cell in degrees; `0.5° ≈ 55 km` on the equator. */
    const val ANCHOR_GRID_DEG: Double = 0.5

    /**
     * Epoch the per-satellite tracks are phased against: 2026-01-01T00:00:00Z.
     *
     * It only shifts the phase of the sinusoidal tracks, so any fixed instant would do; a value in
     * the future keeps `nowMs - ANCHOR_EPOCH_MS` small for a phone whose clock is roughly right.
     */
    const val ANCHOR_EPOCH_MS: Long = 1_767_225_600_000L

    /** Two satellites (at least) are always left out of the fix, as on a real receiver. */
    private const val MIN_UNUSED = 2

    private val TAU = 2.0 * PI

    private val DEG_TO_RAD = PI / 180.0

    /** Constellations modelled everywhere on earth. */
    private val GLOBAL_CONSTELLATIONS = intArrayOf(
        CONSTELLATION_GPS,
        CONSTELLATION_GLONASS,
        CONSTELLATION_GALILEO,
        CONSTELLATION_BEIDOU,
    )

    /**
     * Synthesizes the sky at [position] for the wall clock [nowMs].
     *
     * Pure: the same `(position, nowMs)` always yields the same list, so the UI and the hook agree.
     *
     * @return satellites ordered by descending C/N0; `usedInFix` marks the subset the receiver
     *   reports as used for the fix.
     */
    fun synthesize(position: GeoPoint, nowMs: Long): List<Satellite> {
        val seconds = (nowMs - ANCHOR_EPOCH_MS) / 1_000.0
        val constellations = if (qzssCovers(position)) {
            QZSS_ARRAY
        } else {
            GLOBAL_CONSTELLATIONS
        }
        val counts = allocateCounts(position, constellations)
        val total = counts.sum()

        val latRad = position.lat * DEG_TO_RAD
        val lonRad = position.lon * DEG_TO_RAD
        val draft = ArrayList<Satellite>(total)
        for (index in constellations.indices) {
            val constellation = constellations[index]
            val count = counts[index]
            if (count <= 0) continue
            val random = SeededRandom(orbitSeed(position, constellation))
            val uniqueSvids = HashSet<Int>(count * 2)
            repeat(count) {
                val svid = uniqueSvid(constellation, random, uniqueSvids)
                draft += satelliteAt(constellation, svid, random, latRad, lonRad, seconds)
            }
        }
        draft.sortByDescending { it.cn0DbHz }
        return markUsedInFix(draft, usedTarget(position, total))
    }

    /**
     * One satellite's instantaneous state.
     *
     * The elevation/azimuth/C-N0 are the whole model: a slow sinusoidal track per axis (the period
     * is the simulated orbital geometry), a small position-coupled perturbation so the sky is a
     * continuous function of the coordinates inside an anchor cell, and a signal built from the
     * elevation plus slow and fast deterministic wander.
     */
    private fun satelliteAt(
        constellation: Int,
        svid: Int,
        random: SeededRandom,
        latRad: Double,
        lonRad: Double,
        seconds: Double,
    ): Satellite {
        // Elevation track: 26°..72° base with a 4°..10° swing over 40..100 min. With the ±1.5°
        // parallax the minimum is ≈14.5°, comfortably above the 5° horizon mask: nothing rises or
        // sets inside a session, which is also what keeps the SVID set stable for minutes.
        val elevationBase = 26.0 + random.nextDouble() * 46.0
        val elevationSwing = 4.0 + random.nextDouble() * 6.0
        val elevationPeriod = 2_400.0 + random.nextDouble() * 3_600.0
        val elevationPhase = random.nextDouble() * TAU
        // Azimuth track: a 20°..90° sweep over 2.5..5.8 h plus a slow drift, so a satellite crawls
        // across the sky instead of spinning: <= 0.07 °/s before the drift, <= 0.12 °/s after it.
        val azimuthBase = random.nextDouble() * 360.0
        val azimuthSwing = 20.0 + random.nextDouble() * 70.0
        val azimuthPeriod = 9_000.0 + random.nextDouble() * 12_000.0
        val azimuthPhase = random.nextDouble() * TAU
        val azimuthDrift = (random.nextDouble() - 0.5) * 0.1
        // Signal parameters, all bounded so the 1 s derivative stays far below MAX_CN0_STEP_DB_HZ.
        val offsetDb = (random.nextDouble() - 0.5) * 5.0
        val slowAmpA = 0.8 + random.nextDouble() * 1.0
        val slowPeriodA = 90.0 + random.nextDouble() * 210.0
        val slowPhaseA = random.nextDouble() * TAU
        val slowAmpB = 0.3 + random.nextDouble() * 0.5
        val slowPeriodB = 40.0 + random.nextDouble() * 50.0
        val slowPhaseB = random.nextDouble() * TAU
        val fastAmpA = 0.25 + random.nextDouble() * 0.25
        val fastPeriodA = 8.0 + random.nextDouble() * 5.0
        val fastPhaseA = random.nextDouble() * TAU
        val fastAmpB = 0.10 + random.nextDouble() * 0.20
        val fastPeriodB = 17.0 + random.nextDouble() * 6.0
        val fastPhaseB = random.nextDouble() * TAU

        // Smooth, position-coupled parallax: two points 1 km apart differ by < 0.02° of arc, two
        // points on opposite sides of an anchor cell by a couple of degrees.
        val elevationParallax =
            1.5 * sin(latRad * 9.0 + lonRad * 5.0 + svid * 0.7)
        val azimuthParallax =
            1.5 * cos(latRad * 7.0 - lonRad * 6.0 + svid * 1.3)

        val elevation = (
            elevationBase +
                elevationSwing * sin(TAU * seconds / elevationPeriod + elevationPhase) +
                elevationParallax
            ).coerceIn(MIN_ELEVATION_DEG.toDouble(), MAX_ELEVATION_DEG.toDouble())

        val azimuth = normalizeDegrees(
            azimuthBase +
                azimuthSwing * sin(TAU * seconds / azimuthPeriod + azimuthPhase) +
                azimuthDrift * seconds +
                azimuthParallax,
        )

        val cn0 = (
            22.0 + 0.26 * elevation +
                offsetDb +
                slowAmpA * sin(TAU * seconds / slowPeriodA + slowPhaseA) +
                slowAmpB * sin(TAU * seconds / slowPeriodB + slowPhaseB) +
                fastAmpA * sin(TAU * seconds / fastPeriodA + fastPhaseA) +
                fastAmpB * sin(TAU * seconds / fastPeriodB + fastPhaseB)
            ).coerceIn(MIN_CN0_DB_HZ.toDouble(), MAX_CN0_DB_HZ.toDouble())

        return Satellite(
            svid = svid,
            constellation = constellation,
            cn0DbHz = cn0.toFloat(),
            usedInFix = false,
            elevationDeg = elevation.toFloat(),
            azimuthDeg = azimuth.toFloat(),
        )
    }

    /**
     * Marks the satellites a receiver would actually use for the fix.
     *
     * [draft] must already be sorted by descending C/N0. The strongest satellites win, subject to
     * three constraints a real receiver applies: a 12° elevation mask (low satellites carry more
     * atmospheric error and multipath), a cap of [MAX_USED_PER_CONSTELLATION] per constellation
     * (NMEA `GSA` has twelve PRN slots), and at most `ceil(target / 4)` satellites per 45° azimuth
     * sector so the used set is spread across the sky instead of clustering in one direction.
     *
     * At least [MIN_UNUSED] satellites stay unused, so downstream hooks can verify that the
     * `usedInFix` bits are actually written (a list where every entry is flagged cannot tell a
     * working flag from a stuck one).
     */
    private fun markUsedInFix(draft: List<Satellite>, requestedTarget: Int): List<Satellite> {
        val target = requestedTarget.coerceIn(
            MIN_USED_IN_FIX,
            maxOf(MIN_USED_IN_FIX, minOf(MAX_USED_IN_FIX, draft.size - MIN_UNUSED)),
        )
        val flags = BooleanArray(draft.size)
        val sectorCounts = IntArray(AZIMUTH_SECTORS)
        val constellationCounts = HashMap<Int, Int>(4)
        val maxPerSector = maxPerSectorFor(target)
        var used = 0

        // Seed the fix with the strongest GPS satellite. GPS is the reference constellation of
        // NMEA, so `$GPGSA` must never come out empty; a real receiver uses it whenever it can.
        for (index in draft.indices) {
            val satellite = draft[index]
            if (satellite.constellation != CONSTELLATION_GPS) continue
            if (satellite.elevationDeg < MIN_USED_ELEVATION_DEG) continue
            flags[index] = true
            sectorCounts[sectorOf(satellite.azimuthDeg)]++
            constellationCounts[CONSTELLATION_GPS] = 1
            used++
            break
        }

        for (pass in 0..1) {
            val diversity = pass == 0
            for (index in draft.indices) {
                if (used >= target) break
                if (flags[index]) continue
                val satellite = draft[index]
                if (satellite.elevationDeg < MIN_USED_ELEVATION_DEG) continue
                val sector = sectorOf(satellite.azimuthDeg)
                if (diversity && sectorCounts[sector] >= maxPerSector) continue
                val perConstellation = constellationCounts[satellite.constellation] ?: 0
                if (perConstellation >= MAX_USED_PER_CONSTELLATION) continue
                flags[index] = true
                sectorCounts[sector]++
                constellationCounts[satellite.constellation] = perConstellation + 1
                used++
            }
            // Pass 1 is the fallback for a badly clustered sky: it drops the sector cap rather
            // than under-reporting the fix and breaking the 8..16 contract.
            if (used >= MIN_USED_IN_FIX) break
        }
        return draft.mapIndexed { index, satellite ->
            if (flags[index]) satellite.copy(usedInFix = true) else satellite
        }
    }

    /** 45°-wide azimuth sector index, `0..AZIMUTH_SECTORS-1`. */
    fun sectorOf(azimuthDeg: Float): Int {
        val normalized = normalizeDegrees(azimuthDeg.toDouble())
        return floor(normalized / (360.0 / AZIMUTH_SECTORS)).toInt()
            .coerceIn(0, AZIMUTH_SECTORS - 1)
    }

    /**
     * Splits [MIN_SATELLITE_COUNT]..[MAX_SATELLITE_COUNT] across the constellations in view.
     *
     * Per-anchor quotas (which constellation dominates) and the resulting total come from the
     * anchor seed only, so the counts are stable in time exactly like the SVID set they describe.
     */
    private fun allocateCounts(position: GeoPoint, constellations: IntArray): IntArray {
        val layout = SeededRandom(anchorSeed(position, SALT_SKY_LAYOUT))
        val targetTotal = layout.nextInt(MIN_SATELLITE_COUNT, MAX_SATELLITE_COUNT)
        val rotation = layout.nextInt(constellations.size)
        val counts = IntArray(constellations.size) { minCountFor(constellations[it]) }
        var remaining = targetTotal - counts.sum()
        var cursor = 0
        // Round-robin from a rotated starting point: every constellation grows by one satellite per
        // round until its cap, then the next one takes over. Deterministic and exact.
        while (remaining > 0 && cursor < 4 * MAX_SATELLITE_COUNT) {
            val index = (rotation + cursor) % constellations.size
            if (counts[index] < maxCountFor(constellations[index])) {
                counts[index]++
                remaining--
            }
            cursor++
        }
        return counts
    }

    /** How many satellites of [constellation] the fix claims; derived from the anchor only. */
    private fun usedTarget(position: GeoPoint, inView: Int): Int {
        val layout = SeededRandom(anchorSeed(position, SALT_FIX_LAYOUT))
        val drawn = layout.nextInt(MIN_USED_IN_FIX, MAX_USED_IN_FIX)
        // A receiver does not use everything it can see: roughly a quarter to a third of the sky
        // stays out of the fix even in open view.
        return drawn.coerceAtMost(inView * 7 / 10)
    }

    /**
     * Most used-in-fix satellites a single 45° azimuth sector may hold for a [target]-sized fix.
     *
     * Exposed because it is part of the selection contract: the fix is the strongest satellites
     * *subject to* this spread, which is what keeps the geometric DOP of the used subset sane.
     */
    fun maxPerSectorFor(target: Int): Int = ((target + 3) / 4).coerceAtLeast(2)

    /** Number of satellites of [constellation] that are simulated at all. */
    private fun maxCountFor(constellation: Int): Int = when (constellation) {
        CONSTELLATION_GPS -> 11
        CONSTELLATION_GLONASS -> 8
        CONSTELLATION_GALILEO -> 8
        CONSTELLATION_BEIDOU -> 10
        CONSTELLATION_QZSS -> 3
        else -> 0
    }

    /** Floor a constellation always contributes when it is visible from this sky. */
    private fun minCountFor(constellation: Int): Int = when (constellation) {
        CONSTELLATION_GPS -> 4
        CONSTELLATION_GLONASS -> 2
        CONSTELLATION_GALILEO -> 2
        CONSTELLATION_BEIDOU -> 3
        CONSTELLATION_QZSS -> 1
        else -> 0
    }

    /** QZSS is a Japanese augmentation system: only the Asia-Pacific sees it. */
    private fun qzssCovers(position: GeoPoint): Boolean =
        position.lat >= -50.0 && position.lat <= 60.0 &&
            (position.lon >= 90.0 || position.lon <= -170.0)

    private fun uniqueSvid(
        constellation: Int,
        random: SeededRandom,
        used: MutableSet<Int>,
    ): Int {
        val (from, to) = svidRange(constellation)
        var attempt = 0
        while (attempt < 64) {
            val candidate = random.nextInt(from, to)
            if (used.add(candidate)) return candidate
            attempt++
        }
        // Ranges are wider than the per-constellation caps, so this is effectively unreachable.
        for (candidate in from..to) {
            if (used.add(candidate)) return candidate
        }
        return from
    }

    /** Normalizes degrees into `[0, 360)`. */
    private fun normalizeDegrees(value: Double): Double {
        if (!value.isFinite()) return 0.0
        val wrapped = value % 360.0
        return if (wrapped < 0.0) wrapped + 360.0 else wrapped
    }

    /** Sky-anchor cell seed: coarse in space (a cell is ~55 km wide), exact and stable. */
    private fun anchorSeed(position: GeoPoint, salt: Long): Long {
        val latCell = floor(position.lat / ANCHOR_GRID_DEG).toLong()
        val lonCell = floor(position.lon / ANCHOR_GRID_DEG).toLong()
        var z = latCell * -0x61c8864680b583ebL + lonCell
        z = z xor salt
        z = (z xor (z ushr 33)) * 0xff51afd7ed558ccdUL.toLong()
        z = (z xor (z ushr 33)) * 0xc4ceb9fe1a85ec53UL.toLong()
        return z xor (z ushr 33)
    }

    /** Independent orbit stream per (anchor cell, constellation). */
    private fun orbitSeed(position: GeoPoint, constellation: Int): Long =
        anchorSeed(position, SALT_ORBITS) xor (constellation.toLong() * 0x9e3779b97f4a7c15UL.toLong())

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

    /** Signal-bars style level `0..4` derived from a C/N0 value. */
    fun signalLevel(cn0DbHz: Float): Int = when {
        cn0DbHz >= 40f -> 4
        cn0DbHz >= 32f -> 3
        cn0DbHz >= 25f -> 2
        cn0DbHz >= 20f -> 1
        else -> 0
    }

    private val QZSS_ARRAY = intArrayOf(
        CONSTELLATION_GPS,
        CONSTELLATION_GLONASS,
        CONSTELLATION_GALILEO,
        CONSTELLATION_BEIDOU,
        CONSTELLATION_QZSS,
    )

    /** Salt for the per-anchor sky layout (how many satellites of each constellation). */
    private const val SALT_SKY_LAYOUT: Long = 0x5a7e_5b1a_0007L

    /** Salt for the per-anchor fix layout (how many satellites are used in the fix). */
    private const val SALT_FIX_LAYOUT: Long = 0x5a7e_f1d0_0008L

    /** Salt for the per-anchor orbital tracks. */
    private const val SALT_ORBITS: Long = 0x5a7e_0b17_0009L
}

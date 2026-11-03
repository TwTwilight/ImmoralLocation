package com.immoral.location.core.engine

import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulatedAp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Deterministic Wi-Fi *fingerprint field* synthesis.
 *
 * `WifiManager.getScanResults()` is one of the strongest real-world location signals, so the
 * spoofed scan has to survive the two cheap checks every risk-scoring app performs:
 *
 * 1. **Scan it twice while walking.** Real access points are fixed radios: whether you see one
 *    depends on geometry, so the set only changes gradually as you move and your distance to each
 *    AP changes its RSSI. Drawing a handful of APs from a position hash (the previous approach)
 *    replaced the *entire* neighbourhood every ~11 m — the quantisation step of `seedFor` — and
 *    produced RSSI values that were uniform noise and never changed, which is impossible.
 * 2. **Look at the tuple.** `TP-LINK_1A2B` on a Huawei OUI, a 2.4 GHz radio on 5 GHz channel 149,
 *    a MAC with the locally-administered bit set, or a `scanResult` of four networks in a city are
 *    all "this phone is lying" signals.
 *
 * ## The model
 *
 * A deterministic AP *field* is laid out on anchor cells of [ANCHOR_GRID_DEG] (≈ 50 m):
 *
 * | element | how it is chosen |
 * |---|---|
 * | how many radios a cell holds | 1..6, from a smooth density field (see below) |
 * | where each radio sits | pseudo-random point inside its cell |
 * | vendor / OUI / SSID | one table entry, so the SSID brand can never contradict the OUI |
 * | band, channel | 2.4 GHz ch 1..13, or 5 GHz non-DFS UNII centres |
 * | TX power, obstruction | 14..22 dBm, 6..34 dB of per-AP wall loss (skewed towards 6..14) |
 *
 * A query collects every radio in the 7×7 block of anchor cells around the position (a 5×5 block
 * does not reach [MAX_RANGE_M] in every direction, which would make the set jump whenever the query
 * crossed a cell border), drops everything beyond [MAX_RANGE_M], and reports everything above the
 * receiver's sensitivity — at most [MAX_APS] of them, and always at least the strongest [MIN_APS] —
 * so the count follows the neighbourhood instead of being a fixed number.
 *
 * `RSSI = txPower - (40 dB + 20·log10(d)) - obstruction - 5 GHz penalty + fade`, where the 40 dB is
 * free-space loss at a 1 m reference distance (a bare `20·log10(d)` would make dBm values physically
 * impossible), `obstruction` is the AP's own wall loss raised by the minimum a city imposes over
 * that distance (see [obstructionsDb]), and `fade` is a per-AP sinusoid of 12..60 s and at most
 * ±2.5 dB, i.e. the slow breathing a phone sees from slow fading. Levels are clamped to
 * [WEAKEST_DBM]..[STRONGEST_DBM].
 *
 * The *reported set* is chosen by the **fade-free** level, so it depends only on the position;
 * [nowMs] only scales the fade. That is what makes two scans one second apart report the same
 * BSSIDs with slightly different levels instead of a reshuffled neighbourhood.
 *
 * ## Boundary — what this is not
 *
 * This is **not** a Wi-Fi database: no real network is looked up, and AP positions, SSIDs and
 * BSSIDs are synthesized, so a determined app can still notice that the networks it really hears
 * are missing. The OUI prefixes are real IEEE-registered prefixes of the named vendors (a curated
 * subset, not the full registry), and the SSID of a dual-band router is announced on both bands by
 * two BSSIDs of the same OUI that differ in the last octet, exactly like real firmware does.
 * Some SSIDs carry no brand at all (`CMCC-…`, `ChinaNet-…`), which is why the brand/SSID
 * consistency test only constrains branded ones.
 *
 * **DFS is not modelled**, so 5 GHz DFS channels (5260..5720 MHz) are never emitted: a real DFS
 * radio has to run a 60 s channel availability check and vacate the channel when radar is
 * detected, and pretending otherwise would be a hole, not realism. Every emitted AP is therefore
 * on UNII-1/3 or the 2.4 GHz ISM band.
 */
internal object WifiSynth {
    /** Lowest number of access points a scan reports (sparse block). */
    const val MIN_APS: Int = 12

    /** Highest number of access points a scan reports (dense block / scan cap). */
    const val MAX_APS: Int = 30

    /** Strongest simulated RSSI, in dBm (about 1 m from a router with a clear path). */
    const val STRONGEST_DBM: Int = -35

    /** Weakest simulated RSSI, in dBm (below this a real driver would not report the AP). */
    const val WEAKEST_DBM: Int = -92

    /** Anchor-cell size in degrees: 0.0005° ≈ 55 m north/south, ≈ 43 m east/west at 40° N. */
    const val ANCHOR_GRID_DEG: Double = 0.0005

    /** Access points further than this from the queried position are not reported, in meters. */
    const val MAX_RANGE_M: Double = 120.0

    /** Reference epoch (2023-11-14T22:13:20Z) used by tests and the preview for a fixed instant. */
    const val ANCHOR_EPOCH_MS: Long = 1_700_000_000_000L

    /** Free-space path loss at a 1 m reference distance for 2.4 GHz, in dB. */
    const val REFERENCE_LOSS_DB: Double = 40.0

    /** Extra loss a 5 GHz link has over 2.4 GHz at the same distance, in dB. */
    const val FIVE_GHZ_PENALTY_DB: Double = 6.0

    /** Shortest slow-fade period, in seconds. */
    const val MIN_FADE_PERIOD_S: Double = 12.0

    /** Longest slow-fade period, in seconds. */
    const val MAX_FADE_PERIOD_S: Double = 60.0

    /** Smallest slow-fade amplitude, in dB. */
    const val MIN_FADE_DB: Double = 0.8

    /** Largest slow-fade amplitude, in dB. */
    const val MAX_FADE_DB: Double = 2.5

    /** 2.4 GHz channels 1..13 as centre frequencies (`2407 + 5·channel`). Channel 14 (2484 MHz) is
     * not usable at 20 MHz in China or ETSI, so it is deliberately absent. */
    internal val CHANNELS_24_GHZ: IntArray = IntArray(13) { CHANNEL_1_MHZ + 5 * it }

    /** 5 GHz **non-DFS** UNII centres this synthesiser may use: 36..48 and 149..165. */
    internal val CHANNELS_5_GHZ: IntArray =
        intArrayOf(5_180, 5_200, 5_220, 5_240, 5_745, 5_765, 5_785, 5_805, 5_825)

    /** 5 GHz DFS centres, listed so tests can assert they are never emitted (see the class doc). */
    internal val DFS_5_GHZ_MHZ: IntArray = intArrayOf(
        5_260, 5_280, 5_300, 5_320,
        5_500, 5_520, 5_540, 5_560, 5_580, 5_600, 5_620, 5_640, 5_660, 5_680, 5_700, 5_720,
    )

    // ---------------------------------------------------------------- vendor table

    /**
     * One router vendor: the real IEEE prefixes it is known for, plus the SSID shapes its stock
     * firmware ships with.
     *
     * @property brand canonical vendor name; tests compare it with the brand token in the SSID.
     * @property ouis globally-administered prefixes registered to this vendor (`AA:BB:CC`).
     * @property templates default SSIDs; `%s` is replaced by the router's own MAC suffix.
     * @property carrierTemplates ISP-branded SSIDs a carrier hands out with this vendor's box.
     * @property weight relative frequency in a neighbourhood (Chinese market share, roughly).
     */
    internal class Vendor(
        val brand: String,
        val ouis: List<String>,
        val templates: List<String>,
        val carrierTemplates: List<String> = emptyList(),
        val weight: Int = 1,
    )

    /**
     * The vendors a scan may contain. Every OUI below is a real IEEE MA-L prefix of that vendor,
     * so the first octet is globally administered and unicast by construction.
     *
     * `Mercury` (水星) is TP-Link's domestic sub-brand; it ships under its own
     * `00:5C:C2` prefix, which is why it is listed separately instead of sharing TP-Link's OUIs.
     */
    internal val VENDORS: List<Vendor> = listOf(
        Vendor(
            brand = "TP-Link",
            ouis = listOf("14:CC:20", "1C:3B:F3", "00:1D:0F", "08:57:00", "60:3A:7C"),
            templates = listOf("TP-LINK_%s", "TP-LINK_5G_%s"),
            weight = 18,
        ),
        Vendor(
            brand = "Huawei",
            ouis = listOf("00:E0:FC", "48:62:76"),
            templates = listOf("HUAWEI-%s", "HUAWEI_%s"),
            carrierTemplates = listOf("CMCC-%s", "ChinaNet-%s"),
            weight = 15,
        ),
        Vendor(
            brand = "Xiaomi",
            ouis = listOf("50:4F:3B", "5C:40:71", "A4:C3:BE", "FC:43:45"),
            templates = listOf("Xiaomi_%s", "Redmi_%s", "MIWIFI_%s"),
            weight = 12,
        ),
        Vendor(
            brand = "ZTE",
            ouis = listOf("00:15:EB", "28:DB:02", "3C:76:25"),
            templates = listOf("ZTE-%s", "ZTE_5G_%s"),
            carrierTemplates = listOf("CMCC-%s", "ChinaNet-%s"),
            weight = 10,
        ),
        Vendor(
            brand = "Tenda",
            ouis = listOf("C8:3A:35", "50:2B:73", "D8:32:14"),
            templates = listOf("Tenda_%s", "Tenda_5G_%s"),
            weight = 8,
        ),
        Vendor(
            brand = "Mercury",
            ouis = listOf("00:5C:C2"),
            templates = listOf("MERCURY_%s", "MERCURY_5G_%s"),
            weight = 6,
        ),
        Vendor(
            brand = "H3C",
            ouis = listOf("00:0F:E2", "3C:E5:A6", "0C:DA:41"),
            templates = listOf("H3C_%s", "H3C-5G-%s"),
            weight = 6,
        ),
        Vendor(
            brand = "Ruijie",
            ouis = listOf("00:74:9C", "58:69:6C", "D4:31:27"),
            templates = listOf("Ruijie_%s", "Ruijie-5G-%s"),
            weight = 5,
        ),
        Vendor(
            brand = "D-Link",
            ouis = listOf("00:1B:11", "1C:7E:E5"),
            templates = listOf("dlink-%s", "D-Link_%s"),
            weight = 5,
        ),
        Vendor(
            brand = "Cisco",
            ouis = listOf("00:00:0C", "00:1A:A1"),
            templates = listOf("Cisco_%s"),
            weight = 4,
        ),
        Vendor(
            brand = "Linksys",
            ouis = listOf("00:25:9C", "48:F8:B3"),
            templates = listOf("Linksys%s"),
            weight = 4,
        ),
        Vendor(
            brand = "Aruba",
            ouis = listOf("00:0B:86", "6C:F3:7F"),
            templates = listOf("Aruba-%s"),
            weight = 3,
        ),
        Vendor(
            brand = "Ubiquiti",
            ouis = listOf("24:A4:3C", "78:8A:20"),
            templates = listOf("UniFi_%s"),
            weight = 3,
        ),
    )

    /** Every (OUI, brand) pair of [VENDORS], exposed so tests can validate an emitted BSSID. */
    internal val OUI_TO_BRAND: Map<String, String> =
        VENDORS.flatMap { vendor -> vendor.ouis.map { it.uppercase(Locale.US) to vendor.brand } }
            .toMap()

    /** The vendor that owns [bssid]'s prefix, or `null` when the prefix is not in the table. */
    internal fun brandOfBssid(bssid: String): String? {
        val prefix = bssid.take(8).uppercase(Locale.US)
        return OUI_TO_BRAND[prefix]
    }

    // ---------------------------------------------------------------- synthesis

    /**
     * Deterministic scan result at [position] for the instant [nowMs].
     *
     * @param nowMs wall clock in epoch milliseconds; it only scales the slow temporal fade, so the
     *   set of BSSIDs is identical for every instant while their levels breathe by at most
     *   [MAX_FADE_DB].
     * @return access points ordered by descending signal strength, like a sorted real scan.
     */
    fun synthesize(position: GeoPoint, nowMs: Long): List<SimulatedAp> {
        val placements = placements(position, nowMs)
        val result = ArrayList<SimulatedAp>(placements.size)
        for (placement in placements) {
            result.add(
                SimulatedAp(
                    ssid = placement.ssid,
                    bssid = placement.bssid,
                    level = placement.level,
                    frequencyMhz = placement.frequencyMhz,
                ),
            )
        }
        return result
    }

    /**
     * One reported access point plus the radio facts behind its level.
     *
     * Not part of the injected model — it exists so tests can check the properties the level is
     * derived from (distance, TX power, wall loss, band) instead of snapshotting numbers.
     */
    internal data class Placement(
        val ssid: String,
        val bssid: String,
        val level: Int,
        val frequencyMhz: Int,
        /** Great-circle distance from the queried position to this AP, in meters. */
        val distanceMeters: Double,
        /** Where this AP's radio actually sits; used by the "walk towards it" test. */
        val anchor: GeoPoint,
        val txPowerDbm: Int,
        /**
         * The AP's own obstruction, in dB. [level] uses the larger of this and the city's
         * distance-dependent minimum, see [obstructionsDb].
         */
        val wallLossDb: Int,
        val isFiveGhz: Boolean,
    )

    /**
     * The same scan as [synthesize], with the geometry that produced every level.
     *
     * Pure in `(position, nowMs)` like [synthesize]; exposed `internal` for the test-suite only.
     */
    internal fun placements(position: GeoPoint, nowMs: Long): List<Placement> {
        val cellLat = cellIndexOf(position.lat)
        val cellLon = cellIndexOf(position.lon)

        val candidates = ArrayList<Candidate>(INITIAL_CANDIDATES)
        for (dLat in -SEARCH_RING..SEARCH_RING) {
            for (dLon in -SEARCH_RING..SEARCH_RING) {
                collectCell(cellLat + dLat, cellLon + dLon, candidates)
            }
        }

        // The block's density decides how many radios exist around the phone; what a scan reports is
        // everything the radio can decode, which is a property of the receiver (SCAN_FLOOR_DBM) and
        // therefore stable while walking. Ranking uses the fade-free level, so the set does not
        // depend on the clock either.
        val ranked = ArrayList<Ranked>(candidates.size)
        for (candidate in candidates) {
            val distance = GeoMath.distanceMeters(position, candidate.anchor)
            if (distance > MAX_RANGE_M) continue
            val unfaded = candidate.levelDb(distance, fadeDb = 0.0)
            ranked.add(Ranked(candidate, distance, unfaded))
        }
        ranked.sortWith(compareByDescending<Ranked> { it.unfadedDb }.thenBy { it.candidate.bssid })

        // A scan reports everything above the receiver's sensitivity, at most [MAX_APS]. When the
        // block is so thin that fewer than [MIN_APS] radios reach that floor, the floor is lowered in
        // [FLOOR_STEP_DB] steps until it is filled: a level threshold, unlike "the strongest N",
        // keeps the same APs in the list from one step of the walk to the next.
        val selected = when {
            ranked.size <= MIN_APS -> ranked
            else -> {
                var floor = SCAN_FLOOR_DBM
                var count = countAbove(ranked, floor)
                while (count < MIN_APS && floor > WEAKEST_DBM) {
                    floor -= FLOOR_STEP_DB
                    count = countAbove(ranked, floor)
                }
                if (count > MAX_APS) count = MAX_APS
                if (count < MIN_APS) count = MIN_APS
                ranked.subList(0, count)
            }
        }

        val seen = HashSet<String>(selected.size)
        val result = ArrayList<Placement>(selected.size)
        for (item in selected) {
            if (!seen.add(item.candidate.bssid)) continue
            val level = item.candidate.levelDb(item.distance, fadeFor(item.candidate, nowMs))
            result.add(
                Placement(
                    ssid = item.candidate.ssid,
                    bssid = item.candidate.bssid,
                    level = level.roundToInt().coerceIn(WEAKEST_DBM, STRONGEST_DBM),
                    frequencyMhz = item.candidate.frequencyMhz,
                    distanceMeters = item.distance,
                    anchor = item.candidate.anchor,
                    txPowerDbm = item.candidate.txPowerDbm,
                    wallLossDb = item.candidate.wallLossDb,
                    isFiveGhz = item.candidate.isFiveGhz,
                ),
            )
        }
        result.sortWith(compareByDescending<Placement> { it.level }.thenBy { it.bssid })
        return result
    }

    // ---------------------------------------------------------------- field

    /** One radio of the field, before a query decides whether it is audible. */
    private class Candidate(
        val ssid: String,
        val bssid: String,
        val frequencyMhz: Int,
        val anchor: GeoPoint,
        val txPowerDbm: Int,
        val wallLossDb: Int,
        val isFiveGhz: Boolean,
        val fadeAmplitudeDb: Double,
        val fadePeriodS: Double,
        val fadePhase: Double,
    ) {
        /** Receive level at [distanceM] without the temporal fade, in dBm. */
        fun levelDb(distanceM: Double, fadeDb: Double): Double {
            val clamped = distanceM.coerceAtLeast(1.0)
            val penalty = if (isFiveGhz) FIVE_GHZ_PENALTY_DB else 0.0
            val pathLoss = REFERENCE_LOSS_DB + 20.0 * log10(clamped) + penalty
            return txPowerDbm - pathLoss - obstructionsDb(wallLossDb, clamped) + fadeDb
        }
    }

    /** A candidate that survived the range and audibility filters, with its selection key. */
    private class Ranked(val candidate: Candidate, val distance: Double, val unfadedDb: Double)

    /**
     * Total obstruction between the phone and an AP at [distanceM], in dB.
     *
     * [perApWallLossDb] is the obstruction of that particular radio (a same-room router is 6 dB,
     * one behind three walls 34 dB). It is **not** enough on its own: a 6 dB path over
     * [MAX_RANGE_M] would claim a clear line of sight across a whole city block, which would leave
     * strong fringe APs that pop in and out of the [MAX_RANGE_M] cut as the phone moves. The city
     * therefore adds obstruction with range, so an AP at the modelled radio horizon is always below
     * the receiver's sensitivity and the range cut never truncates something audible.
     */
    private fun obstructionsDb(perApWallLossDb: Int, distanceM: Double): Double {
        val ramp = ((distanceM - NEAR_FIELD_M).coerceAtLeast(0.0) / (MAX_RANGE_M - NEAR_FIELD_M)) *
            RANGE_OBSTRUCTION_DB
        return maxOf(perApWallLossDb.toDouble(), ramp)
    }

    /** Number of leading entries of the descending [ranked] list at or above [floorDb]. */
    private fun countAbove(ranked: List<Ranked>, floorDb: Double): Int {
        var index = 0
        while (index < ranked.size && ranked[index].unfadedDb >= floorDb) index++
        return index
    }

    /** Slow fade of one radio at [nowMs], in dB (|value| <= [MAX_FADE_DB]). */
    private fun fadeFor(candidate: Candidate, nowMs: Long): Double {
        val seconds = nowMs / 1_000.0
        return candidate.fadeAmplitudeDb * sin(TWO_PI * seconds / candidate.fadePeriodS + candidate.fadePhase)
    }

    /** Appends the 1..6 radios anchored inside one cell. */
    private fun collectCell(cellLat: Long, cellLon: Long, out: MutableList<Candidate>) {
        val count = 1 + (densityAt(cellLat, cellLon) * 5.0).roundToInt() // 1..6 radios
        for (slot in 0 until count) {
            val random = SeededRandom(hash(cellLat, cellLon, slot, SALT_AP))
            val vendor = pickVendor(random)
            val oui = vendor.ouis[random.nextInt(vendor.ouis.size)]
            val nicHigh = random.nextInt(256)
            val nicLow = random.nextInt(256)
            val lastOctet = random.nextInt(0, 248)
            val dualBand = random.nextInt(3) == 0
            val hidden = random.nextInt(12) == 0
            val txPower = random.nextInt(14, 22)
            // Obstruction is drawn with a squared bias, not uniformly: most radios you can hear are
            // a room or two away (6..14 dB), and the 25..34 dB cases are the rare one behind a floor
            // slab. A uniform draw put a fifth of the nearby APs below the receiver's sensitivity,
            // which made whole blocks look starved and even reversed the distance/level ordering.
            val wallRoll = random.nextDouble()
            val wallLoss = WALL_LOSS_MIN_DB + (wallRoll * wallRoll * WALL_LOSS_SPREAD_DB).roundToInt()
            val fadeAmplitude = random.nextDouble(MIN_FADE_DB, MAX_FADE_DB)
            val fadePeriod = random.nextDouble(MIN_FADE_PERIOD_S, MAX_FADE_PERIOD_S)
            val fadePhase = random.nextDouble(0.0, TWO_PI)
            val frequency24 = CHANNELS_24_GHZ[random.nextInt(CHANNELS_24_GHZ.size)]
            val frequency5 = CHANNELS_5_GHZ[random.nextInt(CHANNELS_5_GHZ.size)]
            val suffix = hex4(nicHigh, nicLow)
            val ssid = if (hidden) "" else vendor.ssidFor(random, suffix)
            val anchor = GeoPoint(
                lat = (cellLat + 0.05 + random.nextDouble() * 0.9) * ANCHOR_GRID_DEG,
                lon = (cellLon + 0.05 + random.nextDouble() * 0.9) * ANCHOR_GRID_DEG,
                alt = 0.0,
            )
            val singleFiveGhz = !dualBand && random.nextInt(100) < 45

            fun add(frequency: Int, bssidLastOctet: Int, fiveGhz: Boolean) {
                out.add(
                    Candidate(
                        ssid = ssid,
                        bssid = bssidOf(oui, nicHigh, nicLow, bssidLastOctet),
                        frequencyMhz = frequency,
                        anchor = anchor,
                        txPowerDbm = txPower,
                        wallLossDb = wallLoss,
                        isFiveGhz = fiveGhz,
                        fadeAmplitudeDb = fadeAmplitude,
                        fadePeriodS = fadePeriod,
                        fadePhase = fadePhase,
                    ),
                )
            }

            when {
                // A dual-band router announces one SSID on both bands with two BSSIDs of the same
                // OUI that differ only in the last octet — this is what real stock firmware does.
                dualBand -> {
                    add(frequency24, lastOctet, fiveGhz = false)
                    add(frequency5, lastOctet + 1, fiveGhz = true)
                }
                singleFiveGhz -> add(frequency5, lastOctet, fiveGhz = true)
                else -> add(frequency24, lastOctet, fiveGhz = false)
            }
        }
    }

    /**
     * Smooth "how built-up is this block" field, in `[MIN_BLOCK_DENSITY]..1.0`.
     *
     * Two sinusoids of ~1 km and ~3.4 km wavelength keep it continuous across anchor cells: a
     * building-dense street holds more networks than a park two blocks away, and walking does not
     * step off a cliff. The floor of [MIN_BLOCK_DENSITY] matters: a block so empty that fewer than
     * [MIN_APS] networks are audible would make the reported count jump between two thresholds as
     * the phone moves, so even the emptiest park keeps two radios per anchor cell, like a city does.
     */
    internal fun densityAt(cellLat: Long, cellLon: Long): Double {
        val lat = (cellLat + 0.5) * ANCHOR_GRID_DEG
        val lon = (cellLon + 0.5) * ANCHOR_GRID_DEG
        val blobs = sin(lat * BLOCK_WAVE_LAT) * cos(lon * BLOCK_WAVE_LON)
        val drift = sin((lat + lon) * DRIFT_WAVE)
        val raw = (0.5 + 0.28 * blobs + 0.22 * drift).coerceIn(0.0, 1.0)
        return MIN_BLOCK_DENSITY + (1.0 - MIN_BLOCK_DENSITY) * raw
    }

    private fun pickVendor(random: SeededRandom): Vendor {
        var remaining = random.nextInt(TOTAL_VENDOR_WEIGHT)
        for (vendor in VENDORS) {
            remaining -= vendor.weight
            if (remaining < 0) return vendor
        }
        return VENDORS.last()
    }

    /** Default SSID of [vendor]'s stock firmware, with the router's own MAC suffix. */
    private fun Vendor.ssidFor(random: SeededRandom, suffix: String): String {
        val template = if (carrierTemplates.isNotEmpty() && random.nextInt(10) == 0) {
            carrierTemplates[random.nextInt(carrierTemplates.size)]
        } else {
            templates[random.nextInt(templates.size)]
        }
        return template.replace("%s", suffix)
    }

    // ---------------------------------------------------------------- formatting

    /** `oui:aa:bb:cc` in the lower-case form Android reports. */
    private fun bssidOf(oui: String, nicHigh: Int, nicLow: Int, lastOctet: Int): String {
        val prefix = oui.lowercase(Locale.US)
        return "$prefix:${hex2(nicHigh)}:${hex2(nicLow)}:${hex2(lastOctet)}"
    }

    /**
     * Two lower-case hex digits.
     *
     * Hand-rolled instead of `String.format`: a scan builds a few hundred of these and the format
     * parser is an order of magnitude slower than this lookup, which matters because the hook runs
     * this on a 1 Hz tick inside `system_server`.
     */
    private fun hex2(value: Int): String {
        val clamped = value and 0xFF
        val high = HEX_LOWER_DIGITS[clamped ushr 4]
        val low = HEX_LOWER_DIGITS[clamped and 0x0F]
        return "$high$low"
    }

    /** Four upper-case hex digits, the MAC suffix real firmware puts in a default SSID. */
    private fun hex4(high: Int, low: Int): String {
        val combined = ((high and 0xFF) shl 8) or (low and 0xFF)
        return buildString(4) {
            append(HEX_UPPER_DIGITS[(combined ushr 12) and 0x0F])
            append(HEX_UPPER_DIGITS[(combined ushr 8) and 0x0F])
            append(HEX_UPPER_DIGITS[(combined ushr 4) and 0x0F])
            append(HEX_UPPER_DIGITS[combined and 0x0F])
        }
    }

    // ---------------------------------------------------------------- hashing

    private fun cellIndexOf(degrees: Double): Long = floor(degrees / ANCHOR_GRID_DEG).toLong()

    /** Deterministic non-negative hash of one anchor cell + slot, mixed so neighbours differ. */
    private fun hash(cellLat: Long, cellLon: Long, slot: Int, salt: Long): Long {
        var z = cellLat * -0x61c8864680b583ebL + cellLon + slot * 0x100000001b3L + salt
        z = (z xor (z ushr 33)) * 0xff51afd7ed558ccdUL.toLong()
        z = (z xor (z ushr 33)) * 0xc4ceb9fe1a85ec53UL.toLong()
        return (z xor (z ushr 33)) and Long.MAX_VALUE
    }

    private val TOTAL_VENDOR_WEIGHT: Int = VENDORS.sumOf { it.weight }

    private val TWO_PI: Double = 2.0 * PI

    // ---------------------------------------------------------------- constants

    /** Centre frequency of 2.4 GHz channel 1. */
    private const val CHANNEL_1_MHZ: Int = 2_412

    /**
     * Radius of the searched cell block.
     *
     * Three cells (7×7) on purpose: two cells would leave part of the [MAX_RANGE_M] circle
     * uncovered whenever the query sits near a cell border, so a strong low-loss AP on the fringe
     * would appear and disappear as the phone crossed that border. With 7×7 every radio inside
     * [MAX_RANGE_M] is always a candidate, which is what makes the reported set continuous.
     */
    private const val SEARCH_RING: Int = 3

    /** Rough candidate count in a mid-density block, used only as the list's initial capacity. */
    private const val INITIAL_CANDIDATES: Int = 120

    /**
     * Lowest level the simulated radio decodes, in dBm.
     *
     * A real 802.11 station stops decoding around -82..-85 dBm at 20 MHz; below this the AP is not
     * in the scan result at all. It is a property of the receiver, not of the block, which is what
     * makes the reported set walk-stable: an AP joins or leaves the list when it crosses this fixed
     * line, not when it changes rank.
     */
    private const val SCAN_FLOOR_DBM: Double = -84.0

    /** Step by which the floor drops when a thin block cannot fill [MIN_APS], in dB. */
    private const val FLOOR_STEP_DB: Double = 1.0

    /**
     * Inside this radius an AP is assumed to be reachable with the wall loss it was given, in meters.
     *
     * Beyond it the city keeps adding obstruction up to [RANGE_OBSTRUCTION_DB] at [MAX_RANGE_M].
     */
    private const val NEAR_FIELD_M: Double = 15.0

    /** Obstruction the city adds by [MAX_RANGE_M] whatever the AP's own wall loss is, in dB. */
    private const val RANGE_OBSTRUCTION_DB: Double = 26.0

    /** Lowest per-AP obstruction: an AP with clear line of sight (same room), in dB. */
    private const val WALL_LOSS_MIN_DB: Int = 6

    /** Range the per-AP obstruction is drawn from above [WALL_LOSS_MIN_DB], in dB. */
    private const val WALL_LOSS_SPREAD_DB: Double = 28.0

    /** Radians per degree of the ~1 km density blob field. */
    private const val BLOCK_WAVE_LAT: Double = 698.0

    /** Radians per degree of the same field along longitude. */
    private const val BLOCK_WAVE_LON: Double = 740.0

    /** Radians per degree of the ~3.4 km density drift term. */
    private const val DRIFT_WAVE: Double = 205.0

    /** Emptiest a block may be, in the same 0..1 scale as [densityAt]; see that method. */
    private const val MIN_BLOCK_DENSITY: Double = 0.2

    /** Salt for the per-cell AP identities. */
    private const val SALT_AP: Long = SALT_WIFI + 0x10L

    /** Lower-case hex alphabet used by [hex2]. */
    private const val HEX_LOWER_DIGITS = "0123456789abcdef"

    /** Upper-case hex alphabet used by [hex4]. */
    private const val HEX_UPPER_DIGITS = "0123456789ABCDEF"
}

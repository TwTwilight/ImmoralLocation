package com.immoral.location.core.engine

import com.immoral.location.core.Reference
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulatedAp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The synthetic Wi-Fi scan must survive the two checks that expose a fake one.
 *
 * "Plausible" here is concrete and testable without a device or a wardriving database:
 *
 *  * **Spatial coherence.** Real access points are fixed radios. One second later the BSSID set is
 *    identical and every level moved by at most a few dB; 10 m of walking keeps almost all of them;
 *    100 m replaces most of them; another city shares essentially none. This is the defect the
 *    fingerprint field exists to fix — the previous implementation redrew the whole neighbourhood
 *    every ~11 m and never changed an RSSI value.
 *  * **Radio coherence.** Every reported level follows from a distance and a path-loss model, so
 *    walking towards an AP raises its level monotonically and the closest APs are louder than the
 *    farthest ones.
 *  * **Identity coherence.** Every BSSID is a globally-administered unicast MAC from a real vendor
 *    prefix, the prefix agrees with the brand in the SSID, the SSID suffix is the router's own MAC,
 *    channels are real channels of their own band, and no DFS channel is invented.
 *
 * Everything asserted here is a *property* of the model, never a snapshot of one hash output.
 */
class WifiSynthPlausibilityTest {

    private val positions = listOf(
        Reference.BEIJING,
        Reference.SHANGHAI,
        Reference.GUANGZHOU,
        Reference.NEW_YORK,
        GeoPoint(51.5074, -0.1278), // London
        GeoPoint(35.6762, 139.6503), // Tokyo
        GeoPoint(-33.8688, 151.2093), // Sydney
    )

    /** An arbitrary fixed instant: every assertion below is about the position, not the clock. */
    private val t0 = 1_700_000_000_000L

    /** A block whose best AP is weaker than this has no usable network, in dBm. */
    private val USABLE_DBM = -65

    /** A block with nothing weaker than this has no fringe, in dBm. */
    private val DISTANT_DBM = -70

    private fun scan(point: GeoPoint, nowMs: Long = t0): List<SimulatedAp> =
        WifiSynth.synthesize(point, nowMs)

    private fun placements(point: GeoPoint, nowMs: Long = t0) =
        WifiSynth.placements(point, nowMs)

    /** `|A ∩ B| / |A ∪ B|` over BSSIDs. */
    private fun overlap(a: List<SimulatedAp>, b: List<SimulatedAp>): Double {
        val first = a.map { ap -> ap.bssid }.toSet()
        val second = b.map { ap -> ap.bssid }.toSet()
        val union = first union second
        if (union.isEmpty()) return 1.0
        return (first intersect second).size.toDouble() / union.size
    }

    /** The six octets of a MAC, or `null` when the text is not `aa:bb:cc:dd:ee:ff`. */
    private fun octets(bssid: String): IntArray? {
        val parts = bssid.split(':')
        if (parts.size != 6) return null
        val values = IntArray(6)
        for (index in parts.indices) {
            val part = parts[index]
            if (part.length != 2) return null
            values[index] = part.toIntOrNull(16) ?: return null
        }
        return values
    }

    // ------------------------------------------------------------------ shape of a scan

    @Test
    fun everyScanReportsBetweenMinAndMaxAccessPoints() {
        for (point in positions) {
            val count = scan(point).size
            assertTrue(
                count in WifiSynth.MIN_APS..WifiSynth.MAX_APS,
                "$point reported $count APs, outside ${WifiSynth.MIN_APS}..${WifiSynth.MAX_APS}",
            )
        }
    }

    @Test
    fun scanIsSortedFromStrongestToWeakest() {
        for (point in positions) {
            val levels = scan(point).map { ap -> ap.level }
            assertEquals(levels.sortedDescending(), levels, "$point is not sorted by level")
        }
    }

    @Test
    fun accessPointCountFollowsTheDensityOfTheBlock() {
        // The density field is smooth and deterministic, so across seven city blocks the model must
        // produce *some* variety instead of a constant cap — a scan of exactly 30 everywhere would
        // itself look synthetic.
        val counts = positions.map { point -> scan(point).size }
        assertTrue(
            counts.toSet().size >= 2,
            "every sampled block reported the same AP count: $counts",
        )
        assertTrue(
            counts.max() - counts.min() >= 3,
            "block density does not move the AP count: $counts",
        )
    }

    // ------------------------------------------------------------------ BSSID identity

    @Test
    fun everyBssidIsAWellFormedGlobalUnicastAddress() {
        for (point in positions) {
            for (ap in scan(point)) {
                val values = octets(ap.bssid)
                assertTrue(values != null, "$point emitted a malformed BSSID '${ap.bssid}'")
                assertTrue(
                    ap.bssid == ap.bssid.lowercase(),
                    "BSSID '${ap.bssid}' is not in the lower-case form Android reports",
                )
                val first = values!![0]
                assertEquals(0, first and 0x01, "BSSID '${ap.bssid}' is a multicast address")
                // A real router's radio address is burned in by the vendor, so the locally
                // administered bit must be clear — a set-bit BSSID screams "randomized by software".
                assertEquals(0, first and 0x02, "BSSID '${ap.bssid}' is locally administered")
            }
        }
    }

    @Test
    fun bssidsAreUniqueWithinOneScan() {
        for (point in positions) {
            val bssids = scan(point).map { ap -> ap.bssid }
            assertEquals(bssids.size, bssids.toSet().size, "$point repeats a BSSID: $bssids")
        }
    }

    @Test
    fun everyOuiBelongsToAVendorInTheTable() {
        for (point in positions) {
            for (ap in scan(point)) {
                assertTrue(
                    WifiSynth.brandOfBssid(ap.bssid) != null,
                    "$point emitted '${ap.bssid}' with an unregistered prefix",
                )
            }
        }
    }

    @Test
    fun theVendorTableIsMadeOfRealLookingGlobalPrefixes() {
        assertTrue(WifiSynth.VENDORS.size >= 10, "only ${WifiSynth.VENDORS.size} vendors modelled")
        var ouiCount = 0
        for (vendor in WifiSynth.VENDORS) {
            assertTrue(vendor.ouis.isNotEmpty(), "${vendor.brand} has no OUI")
            for (oui in vendor.ouis) {
                ouiCount++
                val values = octets(oui.lowercase() + ":00:00:00")
                assertTrue(values != null, "malformed OUI '$oui' for ${vendor.brand}")
                assertEquals(0, values!![0] and 0x01, "OUI '$oui' is multicast")
                assertEquals(0, values[0] and 0x02, "OUI '$oui' is locally administered")
            }
        }
        assertTrue(ouiCount >= 20, "only $ouiCount OUI prefixes modelled")
    }

    // ------------------------------------------------------------------ brand consistency

    /** Brand tokens a stock SSID can contain, mapped to the vendor that is allowed to own them. */
    private val brandTokens = listOf(
        "TP-LINK" to "TP-Link",
        "TP-Link" to "TP-Link",
        "HUAWEI" to "Huawei",
        "Xiaomi" to "Xiaomi",
        "Redmi" to "Xiaomi",
        "MIWIFI" to "Xiaomi",
        "MERCURY" to "Mercury",
        "H3C" to "H3C",
        "Tenda" to "Tenda",
        "ZTE" to "ZTE",
        "Ruijie" to "Ruijie",
        "dlink" to "D-Link",
        "D-Link" to "D-Link",
        "Cisco" to "Cisco",
        "Linksys" to "Linksys",
        "Aruba" to "Aruba",
        "UniFi" to "Ubiquiti",
    )

    @Test
    fun anSsidBrandNeverContradictsItsOui() {
        var branded = 0
        for (point in positions) {
            for (ap in scan(point)) {
                if (ap.ssid.isEmpty()) continue
                val vendor = WifiSynth.brandOfBssid(ap.bssid)
                val named = brandTokens.filter { (token, _) -> ap.ssid.contains(token, ignoreCase = true) }
                if (named.isEmpty()) continue
                branded++
                for ((token, brand) in named) {
                    assertEquals(
                        brand,
                        vendor,
                        "'${ap.ssid}' (token $token) is on ${ap.bssid} owned by $vendor",
                    )
                }
            }
        }
        assertTrue(branded >= 50, "only $branded branded SSIDs sampled; the check proved little")
    }

    @Test
    fun carrierBrandedSsidsComeFromCarrierBoxes() {
        // CMCC-*/ChinaNet-* are handed out by the ISP together with the box, so they only ever
        // appear on the vendors that supply those boxes.
        val allowed = setOf("Huawei", "ZTE")
        for (point in positions) {
            for (ap in scan(point)) {
                if (!ap.ssid.startsWith("CMCC-") && !ap.ssid.startsWith("ChinaNet-")) continue
                assertTrue(
                    WifiSynth.brandOfBssid(ap.bssid) in allowed,
                    "'${ap.ssid}' on ${ap.bssid} (${WifiSynth.brandOfBssid(ap.bssid)}) is not a carrier box",
                )
            }
        }
    }

    @Test
    fun theSsidSuffixIsTheRoutersOwnMac() {
        // Real firmware names its default SSID after the radio's own MAC. An SSID whose suffix is
        // not part of its BSSID is a giveaway that the two were made up separately.
        for (point in positions) {
            for (ap in scan(point)) {
                if (ap.ssid.isEmpty()) continue
                val suffix = ap.ssid.takeLast(4)
                assertTrue(
                    suffix.all { character -> character.isDigit() || character in 'A'..'F' },
                    "'${ap.ssid}' does not end in a hex MAC suffix",
                )
                assertTrue(
                    ap.bssid.uppercase().replace(":", "").contains(suffix),
                    "suffix '$suffix' of '${ap.ssid}' is not part of ${ap.bssid}",
                )
            }
        }
    }

    @Test
    fun visibleSsidsAreLegal80211Names() {
        for (point in positions) {
            for (ap in scan(point)) {
                if (ap.ssid.isEmpty()) continue
                assertTrue(ap.ssid.isNotBlank(), "'${ap.ssid}' is blank but not empty")
                assertEquals(ap.ssid.trim(), ap.ssid, "'${ap.ssid}' has padding whitespace")
                val bytes = ap.ssid.toByteArray(Charsets.UTF_8).size
                assertTrue(bytes in 1..32, "'${ap.ssid}' is $bytes bytes (802.11 allows 1..32)")
            }
        }
    }

    @Test
    fun aRealisticShareOfAccessPointsHideTheirSsid() {
        var hidden = 0
        var total = 0
        for (step in 0 until 40) {
            // 40 blocks ~4 km apart would be a different city; use a small offset so this stays one
            // neighbourhood sweep but samples many anchor cells.
            val point = GeoMath.destination(Reference.BEIJING, (step * 37).toDouble(), step * 90.0)
            for (ap in scan(point)) {
                total++
                if (ap.ssid.isEmpty()) hidden++
            }
        }
        val ratio = hidden.toDouble() / total
        assertTrue(
            ratio in 0.03..0.18,
            "hidden-SSID ratio $ratio over $total APs is not the expected ~1/12",
        )
    }

    // ------------------------------------------------------------------ dual band

    @Test
    fun aDualBandRouterAnnouncesOneSsidOnTwoBssids() {
        var siblings = 0
        for (point in positions) {
            val aps = scan(point)
            for (group in aps.groupBy { ap -> ap.ssid }.values) {
                if (group.size != 2 || group[0].ssid.isEmpty()) continue
                val first = octets(group[0].bssid)!!
                val second = octets(group[1].bssid)!!
                // Two different routers may legitimately share a name; only check real siblings,
                // i.e. the same radio group of one box.
                val samePrefix = (0 until 5).all { index -> first[index] == second[index] }
                if (!samePrefix) continue
                siblings++
                assertEquals(
                    abs(first[5] - second[5]),
                    1,
                    "sibling BSSIDs differ by more than the last octet: ${group[0].bssid} / ${group[1].bssid}",
                )
                val lowBand = group.minByOrNull { ap -> ap.frequencyMhz }!!
                val highBand = group.maxByOrNull { ap -> ap.frequencyMhz }!!
                assertTrue(
                    lowBand.frequencyMhz in WifiSynth.CHANNELS_24_GHZ.asList(),
                    "the lower band of '${group[0].ssid}' is not 2.4 GHz",
                )
                assertTrue(
                    highBand.frequencyMhz in WifiSynth.CHANNELS_5_GHZ.asList(),
                    "the higher band of '${group[0].ssid}' is not 5 GHz",
                )
                val lowOctet = octets(lowBand.bssid)!![5]
                val highOctet = octets(highBand.bssid)!![5]
                assertEquals(
                    lowOctet + 1,
                    highOctet,
                    "the 5 GHz radio of '${group[0].ssid}' does not use the next MAC",
                )
            }
        }
        assertTrue(siblings >= 10, "only $siblings dual-band sibling pairs sampled")
    }

    // ------------------------------------------------------------------ channels / bands

    @Test
    fun everyChannelIsARealChannelOfItsOwnBand() {
        val band24 = WifiSynth.CHANNELS_24_GHZ.toSet()
        val band5 = WifiSynth.CHANNELS_5_GHZ.toSet()
        for (point in positions) {
            for (placement in placements(point)) {
                val frequency = placement.frequencyMhz
                if (placement.isFiveGhz) {
                    assertTrue(frequency in band5, "$point: $frequency MHz is not a 5 GHz UNII centre")
                } else {
                    assertTrue(frequency in band24, "$point: $frequency MHz is not a 2.4 GHz centre")
                }
            }
        }
    }

    @Test
    fun noDfsChannelIsInventedAndChannel14IsNeverUsed() {
        // This synthesiser does not model DFS (channel availability check, radar vacate), so it must
        // not claim to be on a DFS channel. 2484 MHz (channel 14) is likewise not usable at 20 MHz.
        val dfs = WifiSynth.DFS_5_GHZ_MHZ.toSet()
        for (point in positions) {
            for (ap in scan(point)) {
                assertTrue(ap.frequencyMhz !in dfs, "$point used DFS channel ${ap.frequencyMhz} MHz")
                assertNotEquals(2_484, ap.frequencyMhz, "$point used channel 14")
            }
        }
    }

    @Test
    fun bothBandsAppearInACityScan() {
        val frequencies = positions.flatMap { point -> scan(point) }.map { ap -> ap.frequencyMhz }
        assertTrue(frequencies.any { it in WifiSynth.CHANNELS_24_GHZ.asList() }, "no 2.4 GHz AP")
        assertTrue(frequencies.any { it in WifiSynth.CHANNELS_5_GHZ.asList() }, "no 5 GHz AP")
    }

    // ------------------------------------------------------------------ signal model

    @Test
    fun everyLevelIsInsideTheClampAndTheScanHasRealSpread() {
        for (point in positions) {
            val levels = scan(point).map { ap -> ap.level }
            assertTrue(
                levels.all { level -> level in WifiSynth.WEAKEST_DBM..WifiSynth.STRONGEST_DBM },
                "$point: level outside ${WifiSynth.WEAKEST_DBM}..${WifiSynth.STRONGEST_DBM}: $levels",
            )
            // A block with no usable network, or one where everything is strong, is not a city
            // block: every sampled position must have both a usable AP and a weak tail.
            assertTrue(
                levels.max() >= USABLE_DBM,
                "$point: no usable network nearby (best ${levels.max()} dBm)",
            )
            assertTrue(
                levels.min() <= DISTANT_DBM,
                "$point: no weak tail at all (worst ${levels.min()} dBm)",
            )
        }
    }

    @Test
    fun closerAccessPointsAreLouderThanDistantOnes() {
        // RSSI has to follow geometry: the quartile of APs nearest the phone must be clearly louder
        // than the quartile on the fringe. Wall loss is modelled per AP, so the two groups overlap —
        // but the distance term (about 20 dB across 10..120 m) must dominate.
        for (point in positions) {
            val sorted = placements(point).sortedBy { placement -> placement.distanceMeters }
            val nearest = sorted.take(sorted.size / 4)
            val fringe = sorted.takeLast(sorted.size / 4)
            val nearestMean = nearest.map { it.level }.average()
            val fringeMean = fringe.map { it.level }.average()
            assertTrue(
                nearestMean - fringeMean >= 4.0,
                "$point: nearest quartile averages $nearestMean dBm, fringe $fringeMean dBm",
            )
        }
    }

    @Test
    fun walkingTowardsAnAccessPointRaisesItsLevelMonotonically() {
        // The single most damning check an app can run against a static fake: move closer and a real
        // AP must get louder, never jump. The target is a mid-scan AP (the reported list is sorted,
        // so a middle one is ~40-50 m away and comfortably inside the scan cap): the weakest entry
        // is allowed to fall off the tail when the block's audibility count changes, an AP in the
        // middle of the ranking is not.
        for (point in listOf(Reference.BEIJING, Reference.SHANGHAI, Reference.NEW_YORK)) {
            val reported = placements(point)
            val target = reported[reported.size / 2]
            val bearing = GeoMath.bearingDegrees(point, target.anchor)
            var previousLevel = target.level
            var walked = 0.0
            val stopAt = (target.distanceMeters - 15.0).coerceAtLeast(10.0)
            while (walked < stopAt) {
                walked += 10.0
                val here = GeoMath.destination(point, bearing, walked)
                val placement = placements(here).firstOrNull { it.bssid == target.bssid }
                assertTrue(
                    placement != null,
                    "$point: ${target.bssid} vanished from the scan while walking towards it " +
                        "(${walked.toInt()} m)",
                )
                assertTrue(
                    placement!!.level >= previousLevel,
                    "$point: ${target.bssid} dropped from $previousLevel to ${placement.level} dBm " +
                        "while approaching it (at ${walked.toInt()} m)",
                )
                previousLevel = placement.level
            }
            assertTrue(
                previousLevel - target.level >= 3,
                "$point: ${target.bssid} only gained ${previousLevel - target.level} dB over " +
                    "$walked m of approach",
            )
        }
    }

    @Test
    fun noReportedAccessPointIsBeyondTheModelledRadius() {
        for (point in positions) {
            for (placement in placements(point)) {
                assertTrue(
                    placement.distanceMeters <= WifiSynth.MAX_RANGE_M,
                    "$point reported ${placement.bssid} at ${placement.distanceMeters} m",
                )
                assertTrue(placement.txPowerDbm in 14..22, "TX power ${placement.txPowerDbm} dBm")
                assertTrue(placement.wallLossDb in 6..34, "wall loss ${placement.wallLossDb} dB")
            }
        }
    }

    // ------------------------------------------------------------------ time behaviour

    @Test
    fun theSamePositionAndInstantIsByteIdentical() {
        for (point in positions) {
            assertEquals(scan(point), scan(point), "$point is not reproducible")
            assertEquals(placements(point), placements(point), "$point geometry is not reproducible")
        }
    }

    @Test
    fun aScanOneSecondLaterKeepsTheSameBssidsAndNearlyTheSameLevels() {
        for (point in positions) {
            val before = scan(point, t0)
            val after = scan(point, t0 + 1_000L)
            assertEquals(
                before.map { ap -> ap.bssid }.toSet(),
                after.map { ap -> ap.bssid }.toSet(),
                "$point swapped BSSIDs between two scans one second apart",
            )
            val levels = after.associate { ap -> ap.bssid to ap.level }
            for (ap in before) {
                val delta = abs(ap.level - levels.getValue(ap.bssid))
                assertTrue(delta <= 3, "$point: ${ap.bssid} moved $delta dB in one second")
            }
        }
    }

    @Test
    fun theFadeBreathesButStaysBounded() {
        val instants = listOf(t0, t0 + 7_000L, t0 + 23_000L, t0 + 41_000L)
        for (point in positions) {
            val scans = instants.map { nowMs -> scan(point, nowMs) }
            val first = scans.first().map { ap -> ap.bssid }.toSet()
            for (other in scans) {
                assertEquals(first, other.map { ap -> ap.bssid }.toSet(), "$point changed its BSSID set")
            }
            var breathing = 0
            for (ap in scans.first()) {
                val levels = scans.mapNotNull { scan -> scan.firstOrNull { it.bssid == ap.bssid }?.level }
                if (levels.isEmpty()) continue
                val span = levels.max() - levels.min()
                assertTrue(
                    span <= 6,
                    "$point: ${ap.bssid} swings $span dB over 41 s (fade must stay ~2.5 dB)",
                )
                if (span >= 1) breathing++
            }
            assertTrue(
                breathing >= 3,
                "$point: only $breathing APs breathe at all; the fade is not applied",
            )
        }
    }

    // ------------------------------------------------------------------ movement

    @Test
    fun aTenMeterWalkKeepsAlmostEveryAccessPoint() {
        // Sampled over five cities and eight directions, because a single bearing can be biased by
        // one block's geometry. The reported set keeps ~85 % of its BSSIDs over 10 m; the entries it
        // can lose are the ones sitting exactly at the receiver's sensitivity floor, which is where a
        // real scan flickers too (their level moves ~2 dB over 10 m, so they cross the floor).
        // What must never happen is a wholesale replacement of the neighbourhood.
        val overlaps = ArrayList<Double>()
        for (origin in listOf(
            Reference.BEIJING, Reference.SHANGHAI, Reference.GUANGZHOU,
            Reference.NEW_YORK, GeoPoint(51.5074, -0.1278),
        )) {
            val before = scan(origin)
            for (bearing in 0 until 8) {
                val after = scan(GeoMath.destination(origin, bearing * 45.0, 10.0))
                overlaps.add(overlap(before, after))
            }
        }
        assertTrue(
            overlaps.min() >= 0.6,
            "walking 10 m replaced ${((1 - overlaps.min()) * 100).toInt()}% of the scan",
        )
        assertTrue(
            overlaps.average() >= 0.8,
            "a 10 m walk only keeps ${(overlaps.average() * 100).toInt()}% of the BSSIDs on average",
        )
        val tight = overlaps.count { it >= 0.7 }
        assertTrue(
            tight >= overlaps.size * 8 / 10,
            "only $tight of ${overlaps.size} directions kept 70% of the BSSIDs over 10 m",
        )
    }

    @Test
    fun aHundredMeterWalkReplacesMostButNotAllOfTheScan() {
        val point = Reference.SHANGHAI
        val before = scan(point)
        var partial = 0
        for (bearing in listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)) {
            val after = scan(GeoMath.destination(point, bearing, 100.0))
            val shared = overlap(before, after)
            assertTrue(
                shared <= 0.45,
                "walking 100 m at $bearing° kept ${(shared * 100).toInt()}% of the BSSIDs; the field is too flat",
            )
            if (shared > 0.0) partial++
        }
        assertTrue(
            partial >= 6,
            "100 m replaced the scan completely in ${8 - partial} of 8 directions; a real block " +
                "still shares its fringe",
        )
    }

    @Test
    fun jumpingToAnotherCitySharesEssentiallyNothing() {
        val here = scan(Reference.BEIJING)
        for (other in listOf(Reference.SHANGHAI, Reference.NEW_YORK, Reference.GUANGZHOU)) {
            val there = scan(other)
            assertTrue(overlap(here, there) <= 0.02, "Beijing and $other share BSSIDs")
        }
    }

    @Test
    fun theFieldIsContinuousAlongAWholeBlock() {
        // No cliff: walking 200 m in 10 m steps must never replace most of the scan in one step.
        // Measured worst step over the seven sampled cities is 0.67, so this is a real bound, not a
        // snapshot: it fails the moment the field gains a grid discontinuity.
        val start = Reference.GUANGZHOU
        var previous = scan(start)
        for (step in 1..20) {
            val here = scan(GeoMath.destination(start, 60.0, step * 10.0))
            val shared = overlap(previous, here)
            assertTrue(
                shared >= 0.6,
                "step $step (${step * 10} m) dropped ${((1 - shared) * 100).toInt()}% of the BSSIDs — " +
                    "the field has a discontinuity",
            )
            previous = here
        }
    }

    @Test
    fun everyNeighbourhoodStillLooksLikeSomebodyElses() {
        // Two nearby points must not be identical either: 300 m changes the serving geometry.
        val near = scan(Reference.BEIJING)
        val far = scan(GeoMath.destination(Reference.BEIJING, 0.0, 300.0))
        assertNotEquals(near, far)
    }
}

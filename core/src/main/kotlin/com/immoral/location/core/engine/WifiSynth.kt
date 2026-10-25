package com.immoral.location.core.engine

import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulatedAp
import java.util.Locale

/**
 * Deterministic Wi-Fi access-point synthesis.
 *
 * `WifiManager.getScanResults()` is one of the strongest real-world location signals, so a
 * convincing spoof needs access points that look like a real neighbourhood: locally-administered
 * BSSIDs (the U/L bit is set, exactly like a router-generated one), both 2.4 GHz and 5 GHz
 * channels, SSIDs with the usual ISP prefixes, and a signal spread of -40..-90 dBm.
 */
internal object WifiSynth {
    /** Lowest number of simulated access points. */
    const val MIN_APS: Int = 4

    /** Highest number of simulated access points. */
    const val MAX_APS: Int = 8

    /** Strongest simulated RSSI, in dBm. */
    const val STRONGEST_DBM: Int = -40

    /** Weakest simulated RSSI, in dBm. */
    const val WEAKEST_DBM: Int = -90

    /** 2.4 GHz channels, mapped to their center frequencies in MHz. */
    private val CHANNELS_24_GHZ = intArrayOf(
        2412, 2417, 2422, 2427, 2432, 2437, 2442, 2447, 2452, 2457, 2462, 2467, 2472,
    )

    /** 5 GHz channels, mapped to their center frequencies in MHz. */
    private val CHANNELS_5_GHZ = intArrayOf(
        5180, 5200, 5220, 5240, 5260, 5280, 5300, 5320,
        5500, 5520, 5540, 5560, 5580, 5600, 5620, 5640, 5660, 5680, 5700, 5720, 5745,
    )

    /** SSID templates; `%s` receives a short deterministic suffix. */
    private val SSID_TEMPLATES = listOf(
        "CMCC-%s",
        "ChinaNet-%s",
        "ChinaUnicom-%s",
        "HUAWEI-%s",
        "TP-LINK_%s",
        "Xiaomi_%s",
        "Redmi_%s",
        "MERCURY_%s",
        "H3C_%s",
        "Tenda_%s",
        "%s-5G",
    )

    /** SSIDs without a suffix, dropped in occasionally for variety. */
    private val PLAIN_SSIDS = listOf(
        "DIRECT-3F-HP OfficeJet",
        "Starbucks WiFi",
        "AndroidAP",
        "Guest",
    )

    /**
     * Synthesizes [MIN_APS]..[MAX_APS] access points around [position].
     *
     * @return access points ordered by descending signal strength, like a real scan result.
     */
    fun synthesize(position: GeoPoint): List<SimulatedAp> {
        val random = SeededRandom(seedFor(position, SALT_WIFI))
        val count = random.nextInt(MIN_APS, MAX_APS)
        val usedBssids = HashSet<String>(count)
        val usedSsids = HashSet<String>(count)
        val result = ArrayList<SimulatedAp>(count)
        for (index in 0 until count) {
            // Roughly half of the APs are 5 GHz, which matches a neighbourhood with a couple of
            // modern dual-band routers next to older 2.4 GHz-only ones.
            val isFiveGhz = random.nextInt(0, 2) == 1
            val frequency = if (isFiveGhz) {
                CHANNELS_5_GHZ[random.nextInt(CHANNELS_5_GHZ.size)]
            } else {
                CHANNELS_24_GHZ[random.nextInt(CHANNELS_24_GHZ.size)]
            }
            val bssid = uniqueBssid(random, usedBssids)
            val ssid = uniqueSsid(random, usedSsids, isFiveGhz)
            val dbm = random.nextInt(WEAKEST_DBM, STRONGEST_DBM)
            result.add(
                SimulatedAp(
                    ssid = ssid,
                    bssid = bssid,
                    level = dbm,
                    frequencyMhz = frequency,
                ),
            )
        }
        return result.sortedByDescending { it.level }
    }

    /**
     * Builds a locally-administered, universally-unique-style BSSID.
     *
     * The second-least-significant bit of the first octet (the U/L bit) is set, which is what
     * tells Android and scanners that the address was administratively assigned rather than
     * burned into a card.
     */
    fun locallyAdministeredBssid(random: SeededRandom): String {
        val octets = IntArray(6) { random.nextInt(0, 255) }
        octets[0] = (octets[0] or 0x02) and 0xFE // set U/L, clear multicast
        return octets.joinToString(":") { String.format(Locale.US, "%02x", it) }
    }

    /** `true` when [bssid] is a well-formed, locally-administered (U/L bit set) unicast address. */
    fun isLocallyAdministered(bssid: String): Boolean {
        val parts = bssid.split(':')
        if (parts.size != 6) return false
        val first = parts[0].toIntOrNull(16) ?: return false
        return (first and 0x02) != 0 && (first and 0x01) == 0
    }

    private fun uniqueBssid(random: SeededRandom, used: MutableSet<String>): String {
        repeat(32) {
            val candidate = locallyAdministeredBssid(random)
            if (used.add(candidate)) return candidate
        }
        return locallyAdministeredBssid(random)
    }

    private fun uniqueSsid(random: SeededRandom, used: MutableSet<String>, isFiveGhz: Boolean): String {
        repeat(32) {
            val suffix = String.format(Locale.US, "%04X", random.nextInt(0, 0xFFFF))
            val candidate = if (random.nextInt(0, 6) == 0) {
                random.pick(PLAIN_SSIDS)
            } else {
                random.pick(SSID_TEMPLATES).replace("%s", suffix)
            }
            if (used.add(candidate)) return candidate
        }
        return if (isFiveGhz) "Guest-5G" else "Guest"
    }
}

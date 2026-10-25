package com.immoral.location.core.engine

import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulatedCell

/**
 * Deterministic cellular-network synthesis.
 *
 * A phone in a Chinese city sees one registered LTE cell plus a handful of neighbours. The
 * identifiers are derived from the position, so an app that compares `CellInfo` before and
 * after a movement sees a plausible handover instead of random churn.
 */
internal object CellSynth {
    /** Mobile country code of China. */
    const val MCC_CHINA: String = "460"

    /** Possible mobile network codes. */
    private val MNCS = listOf("00", "01", "11")

    /** Cellular radio access technology reported for the serving cell. */
    const val RAT_LTE: String = "LTE"

    /** Cellular radio access technology reported for a 2G cell. */
    const val RAT_GSM: String = "GSM"

    /** Cellular radio access technology reported for a 5G cell. */
    const val RAT_NR: String = "NR"

    /** Weakest simulated signal, in dBm. */
    const val MIN_DBM: Int = -105

    /** Strongest simulated signal, in dBm. */
    const val MAX_DBM: Int = -75

    /** Lowest number of neighbour cells. */
    const val MIN_NEIGHBOURS: Int = 1

    /** Highest number of neighbour cells. */
    const val MAX_NEIGHBOURS: Int = 3

    /**
     * Synthesizes the serving cell plus [MIN_NEIGHBOURS]..[MAX_NEIGHBOURS] neighbours.
     *
     * @return the registered serving cell first, then unregistered neighbours.
     */
    fun synthesize(position: GeoPoint): List<SimulatedCell> {
        val random = SeededRandom(seedFor(position, SALT_CELLS))
        val mcc = MCC_CHINA
        val mnc = random.pick(MNCS)
        val tac = random.nextInt(10_000, 65_000)
        val serving = SimulatedCell(
            type = RAT_LTE,
            mcc = mcc,
            mnc = mnc,
            lac = tac,
            cid = random.nextLongOfRange(1L, 268_435_455L),
            pci = random.nextInt(0, 503),
            dbm = random.nextInt(-92, MAX_DBM),
            registered = true,
        ).let { it.copy(level = levelFor(it.dbm)) }

        val neighbourCount = random.nextInt(MIN_NEIGHBOURS, MAX_NEIGHBOURS)
        val neighbours = (0 until neighbourCount).map {
            val neighbour = SimulatedCell(
                type = RAT_LTE,
                mcc = mcc,
                mnc = mnc,
                // Neighbours may sit on a different tracking area; that is the common case.
                lac = if (random.nextInt(0, 3) == 0) random.nextInt(10_000, 65_000) else tac,
                cid = random.nextLongOfRange(1L, 268_435_455L),
                pci = random.nextInt(0, 503),
                dbm = random.nextInt(MIN_DBM, -90),
                registered = false,
            )
            neighbour.copy(level = levelFor(neighbour.dbm))
        }
        return listOf(serving) + neighbours
    }

    /** Maps a dBm value onto the `0..4` signal-bar scale used by `SignalStrength`. */
    fun levelFor(dbm: Int): Int = when {
        dbm >= -85 -> 4
        dbm >= -95 -> 3
        dbm >= -105 -> 2
        else -> 1
    }
}

/** Next long in `from..to` (inclusive) from the deterministic stream. */
internal fun SeededRandom.nextLongOfRange(from: Long, to: Long): Long {
    require(to >= from) { "to must be >= from" }
    val span = to - from + 1
    if (span <= 0L) return from
    return from + ((nextLong() ushr 1) % span)
}

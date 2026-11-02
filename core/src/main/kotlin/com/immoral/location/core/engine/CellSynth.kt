package com.immoral.location.core.engine

import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulatedCell
import kotlin.math.floor

/**
 * Deterministic cellular-network synthesis.
 *
 * A phone in a city sees one registered LTE cell plus a handful of neighbours. Two requirements
 * shape everything here:
 *
 * 1. **Reproducibility.** The hook inside `system_server` and the UI preview must agree, and the
 *    acceptance oracle reads these values back, so the whole set is a pure function of the position.
 * 2. **Plausibility.** A risk-scoring app inspects the tuple, not just its presence: an MCC/MNC pair
 *    that is not a real operator, a TAC outside `1..65534`, a PCI above 503, or an ECI whose sector
 *    part is `0` are all "this cannot exist" signals even before any location comparison.
 *
 * The identifiers are therefore **derived from nested position grids** rather than drawn from one
 * random stream per position:
 *
 * | grid | size | drives |
 * |---|---|---|
 * | operator region | 0.5° (≈55 km) | which real PLMN the area belongs to |
 * | tracking area | 0.02° (≈2.2 km) | TAC |
 * | eNodeB | 0.002° (≈220 m) | ECI, and therefore the handover scale |
 *
 * That is what makes the values behave like a radio network: standing still gives byte-identical
 * cells, walking a few hundred metres hands over to a different ECI inside the same TAC, and
 * crossing a few kilometres enters a new tracking area. [MAX_ENODEB_ID] and the ECI layout
 * (`eNodeB id << 8 | sector`) follow 3GPP TS 36.413, and every PLMN in [OPERATORS] is a real,
 * currently assigned pair.
 *
 * **Deliberate limitation** (recorded in `docs/STEALTH.md`): this is a *plausible tuple*, not a
 * lookup of the real neighbouring cells at that position — no public cell database is consulted, so
 * a determined app can still notice that the ECI does not belong to the network it actually sees.
 */
internal object CellSynth {
    /** Mobile country code of China. */
    const val MCC_CHINA: String = "460"

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

    /** Highest valid physical cell identity (3GPP: `0..503`). */
    const val MAX_PCI: Int = 503

    /** Highest valid eNodeB id (20 bits), i.e. the upper part of an ECI. */
    const val MAX_ENODEB_ID: Long = 1_048_575L

    /** Sector part of an ECI is `1..255`; `0` means "no sector" and real cells never report it. */
    const val MIN_SECTOR: Int = 1

    /** Highest sector part of an ECI (8 bits). */
    const val MAX_SECTOR: Int = 255

    /** Highest valid E-UTRAN Cell Identity (`eNodeB id << 8 | sector`), 28 bits. */
    const val MAX_ECI: Long = MAX_ENODEB_ID * 256L + MAX_SECTOR

    /** Highest valid tracking area code (16 bits). */
    const val MAX_TAC: Int = 65_534

    /** Grid sizes; see the table in the class doc. */
    private const val OPERATOR_GRID_DEG = 0.5
    private const val TAC_GRID_DEG = 0.02
    private const val ENODEB_GRID_DEG = 0.002

    /** Salts so the three grids do not correlate with each other. */
    private const val SALT_OPERATOR = 0x5a7e_ce11_0010L
    private const val SALT_TAC = 0x5a7e_ce11_0011L
    private const val SALT_ENODEB = 0x5a7e_ce11_0012L
    private const val SALT_SECTOR = 0x5a7e_ce11_0013L
    private const val SALT_PCI = 0x5a7e_ce11_0014L
    private const val SALT_DBM = 0x5a7e_ce11_0015L
    private const val SALT_COUNT = 0x5a7e_ce11_0016L
    private const val SALT_RING = 0x5a7e_ce11_0017L

    /**
     * A real PLMN plus the identifier ranges this synthesiser uses for it.
     *
     * [tacFrom]/[tacTo] are **shaped for internal consistency**: the TAC a position gets stays
     * inside its operator's band, which is what makes a `(PLMN, TAC)` pair look coherent. They are
     * *not* copied from an operator registry — no public per-operator TAC table exists, and
     * inventing authoritative-looking numbers would be worse than admitting the shaping.
     */
    internal data class Operator(
        val mcc: String,
        val mnc: String,
        val name: String,
        val tacFrom: Int = 1,
        val tacTo: Int = MAX_TAC,
    ) {
        /** `460/00` */
        val plmn: String get() = "$mcc/$mnc"
    }

    /** China Mobile / Unicom / Telecom, with the MNCs that are actually assigned to them. */
    private val CHINA_OPERATORS = listOf(
        Operator(MCC_CHINA, "00", "China Mobile", tacFrom = 20_000, tacTo = 39_999),
        Operator(MCC_CHINA, "01", "China Unicom", tacFrom = 40_000, tacTo = 47_999),
        Operator(MCC_CHINA, "11", "China Telecom", tacFrom = 48_000, tacTo = 55_999),
    )

    /** A small table of real foreign pairs, so a position outside China is still plausible. */
    private val US_OPERATORS = listOf(
        Operator("310", "260", "T-Mobile US", 1_000, 32_767),
        Operator("311", "480", "Verizon", 1_000, 32_767),
        Operator("310", "410", "AT&T", 1_000, 32_767),
    )

    private val UK_OPERATORS = listOf(
        Operator("234", "10", "O2 UK", 1_000, 32_767),
        Operator("234", "15", "Vodafone UK", 1_000, 32_767),
        Operator("234", "30", "EE UK", 1_000, 32_767),
    )

    private val DE_OPERATORS = listOf(
        Operator("262", "01", "Telekom DE", 1_000, 32_767),
        Operator("262", "02", "Vodafone DE", 1_000, 32_767),
    )

    private val JP_OPERATORS = listOf(
        Operator("440", "10", "NTT docomo", 1_000, 32_767),
        Operator("440", "20", "SoftBank", 1_000, 32_767),
        Operator("440", "50", "KDDI", 1_000, 32_767),
    )

    private val AU_OPERATORS = listOf(
        Operator("505", "01", "Telstra", 1_000, 32_767),
        Operator("505", "02", "Optus", 1_000, 32_767),
        Operator("505", "03", "Vodafone AU", 1_000, 32_767),
    )

    /**
     * Every PLMN this synthesiser can emit. Exposed so tests (and the docs) can assert that whatever
     * pair comes out is a real, assigned one instead of hard-coding the list twice.
     */
    internal val OPERATORS: List<Operator> =
        CHINA_OPERATORS + US_OPERATORS + UK_OPERATORS + DE_OPERATORS + JP_OPERATORS + AU_OPERATORS

    /** `true` when `(mcc, mnc)` is one of the real pairs above. */
    internal fun isKnownPlmn(mcc: String, mnc: String): Boolean =
        OPERATORS.any { it.mcc == mcc && it.mnc == mnc }

    /**
     * Synthesizes the serving cell plus [MIN_NEIGHBOURS]..[MAX_NEIGHBOURS] neighbours.
     *
     * Pure function of [position]: the same position always yields a byte-identical list, which both
     * the UI preview and the framework hook rely on.
     *
     * @return the registered serving cell first, then unregistered neighbours.
     */
    fun synthesize(position: GeoPoint): List<SimulatedCell> {
        val operator = operatorFor(position)
        val enodebGrid = gridOf(position, ENODEB_GRID_DEG)
        val serving = cellAt(operator, enodebGrid, serving = true)

        val count = MIN_NEIGHBOURS +
            (hash(enodebGrid, SALT_COUNT) % (MAX_NEIGHBOURS - MIN_NEIGHBOURS + 1).toLong()).toInt()
        // A real neighbour list is the surrounding eNodeBs, so walk the ring around this grid cell
        // starting at a hash-dependent offset: deterministic, distinct, and always one step away.
        val start = (hash(enodebGrid, SALT_RING) % RING.size.toLong()).toInt()
        val neighbours = (0 until count).map { index ->
            val (dLat, dLon) = RING[(start + index) % RING.size]
            cellAt(
                operator,
                Grid(enodebGrid.lat + dLat, enodebGrid.lon + dLon),
                serving = false,
                servingDbm = serving.dbm,
            )
        }
        // Two neighbours on the same PCI look like a decode error, and a neighbour on the serving PCI
        // is impossible: nudge the offender up the PCI space (bounded, still 0..503).
        val used = HashSet<Int>(count + 1)
        used.add(serving.pci)
        val uniqueNeighbours = neighbours.map { neighbour ->
            var pci = neighbour.pci
            var step = 0
            while (pci in used && step < MAX_PCI) {
                pci = (pci + 1) % (MAX_PCI + 1)
                step++
            }
            used.add(pci)
            neighbour.copy(pci = pci)
        }
        return listOf(serving) + uniqueNeighbours
    }

    /** The operator the simulated phone is registered to at [position]. */
    internal fun operatorFor(position: GeoPoint): Operator {
        val candidates = operatorsNear(position)
        val grid = gridOf(position, OPERATOR_GRID_DEG)
        return candidates[(hash(grid, SALT_OPERATOR) % candidates.size.toLong()).toInt()]
    }

    /** Real PLMNs the synthesiser may use around [position]: China first, else a foreign table. */
    private fun operatorsNear(position: GeoPoint): List<Operator> = when {
        // Mainland China, deliberately generous so the edges of the country are covered.
        position.lat in 18.0..54.0 && position.lon in 73.0..135.0 -> CHINA_OPERATORS
        position.lat in 24.0..50.0 && position.lon in -125.0..-66.0 -> US_OPERATORS
        position.lat in 49.0..61.0 && position.lon in -8.0..2.0 -> UK_OPERATORS
        position.lat in 47.0..55.0 && position.lon in 5.0..16.0 -> DE_OPERATORS
        position.lat in 30.0..46.0 && position.lon in 129.0..146.0 -> JP_OPERATORS
        position.lat in -44.0..-10.0 && position.lon in 112.0..154.0 -> AU_OPERATORS
        // Anywhere else: a *real* pair is still better than a made-up one, and the docs say so.
        else -> OPERATORS
    }

    /** One LTE cell for the eNodeB grid cell [enodebGrid], in that eNodeB's own tracking area. */
    private fun cellAt(
        operator: Operator,
        enodebGrid: Grid,
        serving: Boolean,
        servingDbm: Int = MAX_DBM,
    ): SimulatedCell {
        val tac = tacFor(operator, tacGridForEnodebGrid(enodebGrid))
        val enodebId = 1 + hash(enodebGrid, SALT_ENODEB) % MAX_ENODEB_ID
        val sector = MIN_SECTOR +
            (hash(enodebGrid, SALT_SECTOR) % (MAX_SECTOR - MIN_SECTOR + 1).toLong()).toInt()
        val pci = (hash(enodebGrid, SALT_PCI) % (MAX_PCI + 1).toLong()).toInt()
        val dbm = if (serving) {
            // -75..-92 dBm: a camped-on LTE cell in a city.
            MAX_DBM - (hash(enodebGrid, SALT_DBM) % 18L).toInt()
        } else {
            // A neighbouring eNodeB one grid step away is a few dB weaker — never stronger than the
            // cell the phone is registered to, which apps do check.
            val gap = 5 + (hash(enodebGrid, SALT_DBM) % 12L).toInt()
            (servingDbm - gap).coerceIn(MIN_DBM, -86)
        }
        return SimulatedCell(
            type = RAT_LTE,
            mcc = operator.mcc,
            mnc = operator.mnc,
            lac = tac,
            cid = enodebId * 256L + sector,
            pci = pci,
            level = levelFor(dbm),
            dbm = dbm,
            registered = serving,
        )
    }

    /** TAC inside the operator's band, stable for every position inside one tracking area. */
    private fun tacFor(operator: Operator, tacGrid: Grid): Int {
        val span = (operator.tacTo - operator.tacFrom + 1).toLong()
        return operator.tacFrom + (hash(tacGrid, SALT_TAC) % span).toInt()
    }

    /** Maps an eNodeB grid cell onto the tracking area containing its centre. */
    private fun tacGridForEnodebGrid(enodebGrid: Grid): Grid {
        val scale = ENODEB_GRID_DEG / TAC_GRID_DEG
        return Grid(
            lat = floor((enodebGrid.lat + 0.5) * scale).toLong(),
            lon = floor((enodebGrid.lon + 0.5) * scale).toLong(),
        )
    }

    /** Maps a dBm value onto the `0..4` signal-bar scale used by `SignalStrength`. */
    fun levelFor(dbm: Int): Int = when {
        dbm >= -85 -> 4
        dbm >= -95 -> 3
        dbm >= -105 -> 2
        else -> 1
    }

    // ---------------------------------------------------------------- grids

    /** A position grid cell, in grid units. */
    private data class Grid(val lat: Long, val lon: Long)

    private fun gridOf(position: GeoPoint, degrees: Double): Grid = Grid(
        lat = floor(position.lat / degrees).toLong(),
        lon = floor(position.lon / degrees).toLong(),
    )

    /** Deterministic non-negative hash of a grid cell, mixed so neighbouring cells are unrelated. */
    private fun hash(grid: Grid, salt: Long): Long {
        var z = grid.lat * -0x61c8864680b583ebL + grid.lon + salt
        z = (z xor (z ushr 33)) * 0xff51afd7ed558ccdUL.toLong()
        z = (z xor (z ushr 33)) * 0xc4ceb9fe1a85ec53UL.toLong()
        return (z xor (z ushr 33)) and Long.MAX_VALUE
    }

    /**
     * The eight eNodeB grid cells around one cell, in a fixed order.
     *
     * Neighbours are the surrounding eNodeBs — real ones, i.e. one handover step away in both axes —
     * which is also why two neighbours can never be the same cell.
     */
    private val RING: List<Pair<Long, Long>> = listOf(
        0L to 1L,
        1L to 1L,
        1L to 0L,
        1L to -1L,
        0L to -1L,
        -1L to -1L,
        -1L to 0L,
        -1L to 1L,
    )
}

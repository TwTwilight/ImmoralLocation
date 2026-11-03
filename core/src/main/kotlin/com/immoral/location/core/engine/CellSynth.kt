package com.immoral.location.core.engine

import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulatedCell
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt

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
 * ## The radio model behind `dbm`
 *
 * A reported level that is constant inside a 220 m grid cell and jumps at its border is the one
 * thing a signal-strength plot catches immediately, so the level is a *distance* measurement:
 *
 *  * every eNodeB has a **deterministic antenna position** near its own grid-cell centre
 *    ([ANTENNA_OFFSET_FRACTION] of the cell extent, from the per-cell hash — not from the query),
 *  * the serving level is `REFERENCE_DBM - 10 n log10(d / REFERENCE_DISTANCE_M)` with n =
 *    [PATH_LOSS_EXPONENT] (3GPP TS 36.942 urban-macro exponent, 3.0..3.5) and a handset 100 m from
 *    a city macro hearing ≈[REFERENCE_DBM] dBm,
 *  * a smooth ±[SHADOW_DB] dB slow-fading term, correlated over ≈[SHADOW_GRID_DEG]° (≈50 m), stands
 *    in for street clutter and building penetration — that is why two equidistant positions do not
 *    report the same number, exactly like the log-normal shadowing of the propagation literature,
 *  * a neighbour is the *same law with the transmit power cancelled*: it is weaker by
 *    `10 n log10(d_neighbour / d_serving)`, so the serving cell is the strongest by construction and
 *    the neighbour ordering follows the distances.
 *
 * Two consequences are worth stating because tests rely on them. First, the level is continuous
 * across a handover: a point on the border between two cells is, by symmetry, the same distance from
 * both antennas, so the value moves by the antenna jitter only. Second, it saturates inside
 * [MIN_DBM]..[MAX_DBM] — climbing onto the antenna of a cell pins the report at [MAX_DBM], which is
 * what a handset under a mast actually does, and beyond ~[MIN_DBM] the model says "this cell is
 * noise".
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
    private const val SALT_COUNT = 0x5a7e_ce11_0016L
    private const val SALT_RING = 0x5a7e_ce11_0017L
    private const val SALT_ANTENNA_LAT = 0x5a7e_ce11_0018L
    private const val SALT_ANTENNA_LON = 0x5a7e_ce11_0019L
    private const val SALT_SHADOW = 0x5a7e_ce11_001aL

    // ------------------------------------------------------------ radio model
    //
    // Calibrated to what a handset in a dense Chinese or European city reports, not to a full link
    // budget: these cells are only ≈220 m across, so the "mast density" is far higher than a real
    // macro grid and a textbook budget would put every sample tens of dB too strong.

    /** Reference distance of the log-distance law, in metres: a small-urban macro's cell radius. */
    internal const val REFERENCE_DISTANCE_M = 100.0

    /**
     * RSRP at [REFERENCE_DISTANCE_M]: the level a camped-on handset hears in a served city cell.
     *
     * Together with [PATH_LOSS_EXPONENT] this is the law `Fakes.kt` inverts to derive a timing advance
     * from the reported level (`internal` constants do not cross the module boundary), so the two
     * calibrations have to be changed together.
     */
    private const val REFERENCE_DBM = -86.0

    /** Urban-macro path-loss exponent (3GPP TS 36.942 for the 2 GHz band is 3.0..3.5). */
    internal const val PATH_LOSS_EXPONENT = 3.2

    /** Distance floor of the law, so `log10` stays finite when the query sits on the antenna. */
    internal const val MIN_DISTANCE_M = 10.0

    /** A neighbour is never reported at exactly the serving level: that would look like a decode bug. */
    private const val MIN_NEIGHBOUR_GAP_DB = 1.0

    /** Amplitude of the slow-fading (clutter / penetration) term, in dB. */
    internal const val SHADOW_DB = 2.0

    /** Lattice pitch of the slow-fading field: ≈50 m, the correlation length of shadowing. */
    private const val SHADOW_GRID_DEG = 0.0005

    /** How far from its grid-cell centre an antenna sits, as a fraction of the cell extent. */
    private const val ANTENNA_OFFSET_FRACTION = 0.02

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
    fun synthesize(position: GeoPoint): List<SimulatedCell> = build(position, null)

    /**
     * Test-visible: the distance in metres from [position] to the antenna of every cell
     * [synthesize] returns, in the same order.
     *
     * The level of a cell is a function of exactly this distance, so tests can assert the ordering
     * property ("the nearer antenna is the stronger cell") instead of pinning numbers.
     */
    internal fun antennaDistancesM(position: GeoPoint): List<Double> {
        val out = ArrayList<Double>(1 + MAX_NEIGHBOURS)
        build(position, out)
        return out
    }

    /**
     * The exact (unrounded, unclamped) RSRP of the serving cell at [position], in dBm.
     *
     * Test-visible for one reason: the reported value is quantised to whole dBm and saturated at
     * [MAX_DBM]..[MIN_DBM], and a test that wants to show "the signal moves with every step" must
     * look at the physical quantity, not at the saturated report.
     */
    internal fun servingRssiExact(position: GeoPoint): Double =
        rssiExact(position, gridOf(position, ENODEB_GRID_DEG))

    private fun build(position: GeoPoint, distances: MutableList<Double>?): List<SimulatedCell> {
        val operator = operatorFor(position)
        val enodebGrid = gridOf(position, ENODEB_GRID_DEG)
        val servingDistance = distanceToAntennaM(position, enodebGrid)
        val servingExact = rssiExact(position, enodebGrid)
        distances?.add(servingDistance)
        val serving = cellAt(operator, enodebGrid, serving = true, dbm = clampDbm(servingExact))

        val count = MIN_NEIGHBOURS +
            (hash(enodebGrid, SALT_COUNT) % (MAX_NEIGHBOURS - MIN_NEIGHBOURS + 1).toLong()).toInt()
        // A real neighbour list is the surrounding eNodeBs, so walk the ring around this grid cell
        // starting at a hash-dependent offset: deterministic, distinct, and always one step away.
        val start = (hash(enodebGrid, SALT_RING) % RING.size.toLong()).toInt()
        val neighbours = (0 until count).map { index ->
            val (dLat, dLon) = RING[(start + index) % RING.size]
            val grid = Grid(enodebGrid.lat + dLat, enodebGrid.lon + dLon)
            val distance = distanceToAntennaM(position, grid)
            distances?.add(distance)
            // The same path-loss law with the transmit power cancelled out: a neighbour is weaker by
            // exactly the extra path loss to its own antenna, so the serving cell stays the strongest
            // and two neighbours are ordered by their distances.
            val gap = maxOf(MIN_NEIGHBOUR_GAP_DB, pathLossDb(distance) - pathLossDb(servingDistance))
            cellAt(operator, grid, serving = false, dbm = clampDbm(servingExact - gap))
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
        dbm: Int,
    ): SimulatedCell {
        val tac = tacFor(operator, tacGridForEnodebGrid(enodebGrid))
        val enodebId = 1 + hash(enodebGrid, SALT_ENODEB) % MAX_ENODEB_ID
        val sector = MIN_SECTOR +
            (hash(enodebGrid, SALT_SECTOR) % (MAX_SECTOR - MIN_SECTOR + 1).toLong()).toInt()
        val pci = (hash(enodebGrid, SALT_PCI) % (MAX_PCI + 1).toLong()).toInt()
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

    // ------------------------------------------------------------ radio model

    /** Whole dBm inside [MIN_DBM]..[MAX_DBM], which is the range a report is allowed to carry. */
    private fun clampDbm(exact: Double): Int = exact.roundToInt().coerceIn(MIN_DBM, MAX_DBM)

    /** Received level of the cell [grid] as heard at [position], before rounding and clamping. */
    private fun rssiExact(position: GeoPoint, grid: Grid): Double =
        REFERENCE_DBM - pathLossDb(distanceToAntennaM(position, grid)) + shadowDb(position)

    /** Path loss in dB relative to [REFERENCE_DISTANCE_M] (0 dB there, growing with distance). */
    private fun pathLossDb(distanceM: Double): Double =
        10.0 * PATH_LOSS_EXPONENT * log10(maxOf(distanceM, MIN_DISTANCE_M) / REFERENCE_DISTANCE_M)

    /** Distance from [position] to the antenna of [grid], in metres. */
    private fun distanceToAntennaM(position: GeoPoint, grid: Grid): Double =
        GeoMath.distanceMeters(position, antennaOf(grid))

    /**
     * Where the site of [grid] actually stands: near the centre of its own coverage area, by an
     * amount that belongs to the *cell* (hash) and never to the query position.
     *
     * The offset is deliberately small ([ANTENNA_OFFSET_FRACTION] of the extent): it exists so cells
     * differ from each other, not so the level jumps when the serving cell changes. A point on the
     * border of two grid cells is equidistant from both centres, so keeping the sites near those
     * centres keeps the reported level continuous across a handover.
     */
    private fun antennaOf(grid: Grid): GeoPoint {
        val offsetDeg = ENODEB_GRID_DEG * ANTENNA_OFFSET_FRACTION
        return GeoPoint(
            lat = (grid.lat + 0.5) * ENODEB_GRID_DEG + fraction(hash(grid, SALT_ANTENNA_LAT)) * offsetDeg,
            lon = (grid.lon + 0.5) * ENODEB_GRID_DEG + fraction(hash(grid, SALT_ANTENNA_LON)) * offsetDeg,
        )
    }

    /**
     * Slow fading: a smooth ±[SHADOW_DB] dB field with a ≈50 m correlation length.
     *
     * It is the same value for every cell at one position on purpose. What changes between two
     * equidistant spots is the local clutter and the building the phone is in, and that is shared by
     * all links from that spot — which is also why it cancels out of the serving/neighbour gap and
     * cannot break "a neighbour is never stronger than the serving cell". Smoothstep interpolation
     * keeps the field `C1`, so it never introduces a step while walking.
     */
    private fun shadowDb(position: GeoPoint): Double {
        val x = position.lat / SHADOW_GRID_DEG
        val y = position.lon / SHADOW_GRID_DEG
        val x0 = floor(x).toLong()
        val y0 = floor(y).toLong()
        val tx = smoothStep(x - x0)
        val ty = smoothStep(y - y0)
        val v00 = latticeNoise(x0, y0)
        val v10 = latticeNoise(x0 + 1, y0)
        val v01 = latticeNoise(x0, y0 + 1)
        val v11 = latticeNoise(x0 + 1, y0 + 1)
        val near = v00 + (v10 - v00) * tx
        val far = v01 + (v11 - v01) * tx
        return SHADOW_DB * (near + (far - near) * ty)
    }

    private fun latticeNoise(x: Long, y: Long): Double = fraction(hash(Grid(x, y), SALT_SHADOW))

    /** `C1` ramp; a plain linear interpolation would leave a kink on every lattice line. */
    private fun smoothStep(t: Double): Double = t * t * (3.0 - 2.0 * t)

    /** Maps a hash into `[-1, 1]`. */
    private fun fraction(h: Long): Double = ((h % 2_001L).toDouble() - 1_000.0) / 1_000.0

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

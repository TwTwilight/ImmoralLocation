package com.immoral.location.data

import com.immoral.location.core.engine.SimulationEngine
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulationConfig

/** Counts of synthesized environment data, shown in the status panel. */
data class PreviewCounts(
    val satellites: Int = 0,
    val satellitesUsed: Int = 0,
    val cells: Int = 0,
    val accessPoints: Int = 0,
)

/**
 * Display-only engine used to render the numbers the *hook* will inject.
 *
 * `:core`'s synthesis functions are documented as pure functions of `(position, config)`, so a
 * throw-away [SimulationEngine] in the app process produces exactly the same satellites / cells /
 * Wi-Fi APs the framework computes. This instance is never ticked — the authoritative engine lives
 * in `system_server`.
 */
class PreviewEngine {

    private val engine = SimulationEngine()

    fun counts(point: GeoPoint, config: SimulationConfig): PreviewCounts {
        if (!point.isValidLatLon()) return PreviewCounts()
        return try {
            engine.config = config
            engine.setPosition(point, emitImpact = false)
            val satellites = engine.satellites()
            val cells = if (config.cellSimulation) engine.cells() else emptyList()
            val aps = if (config.wifiSimulation) engine.accessPoints() else emptyList()
            PreviewCounts(
                satellites = if (config.gpsSignalSimulation) satellites.size else 0,
                satellitesUsed = if (config.gpsSignalSimulation) satellites.count { it.usedInFix } else 0,
                cells = cells.size,
                accessPoints = aps.size,
            )
        } catch (t: Throwable) {
            PreviewCounts()
        }
    }
}

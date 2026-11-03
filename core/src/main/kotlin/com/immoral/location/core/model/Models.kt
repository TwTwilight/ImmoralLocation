package com.immoral.location.core.model

import kotlinx.serialization.Serializable

/**
 * A geographic position expressed in WGS-84 degrees plus an altitude in meters.
 *
 * The class is deliberately tiny and immutable: every subsystem of ImmoralLocation (`:app`,
 * `:hook`, `:verifier`) exchanges [GeoPoint] values across the bridge, so it must stay
 * trivially serializable with `kotlinx.serialization`.
 *
 * @property lat Latitude in degrees, `-90.0..90.0`.
 * @property lon Longitude in degrees, `-180.0..180.0`.
 * @property alt Altitude (meters above the WGS-84 ellipsoid).
 */
@Serializable
data class GeoPoint(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val alt: Double = 50.0,
) {
    /**
     * `true` when the coordinates are inside the legal latitude/longitude ranges.
     *
     * The null-island (`0,0`) case is *not* rejected here: it is a legal position on the map,
     * merely an unlikely one. Use [isValid] when a "not yet configured" marker must be rejected.
     */
    fun isValidLatLon(): Boolean =
        lat.isFinite() && lon.isFinite() &&
            lat >= -90.0 && lat <= 90.0 &&
            lon >= -180.0 && lon <= 180.0

    /**
     * `true` when [isValidLatLon] holds and the point is not the default "unset" position
     * (latitude and longitude both zero).
     */
    fun isValid(): Boolean = isValidLatLon() && !(lat == 0.0 && lon == 0.0)

    /** `true` when this point is the (unset) null-island marker. */
    fun isZero(): Boolean = lat == 0.0 && lon == 0.0

    companion object {
        /** The "unset" position: latitude/longitude zero, default altitude. */
        val ZERO: GeoPoint = GeoPoint()
    }
}

/**
 * Preset movement speeds.
 *
 * @property defaultMetersPerSecond Speed used when the level is selected without an explicit
 *   custom speed. [CUSTOM] defaults to WALK so an unsanitized config never jumps.
 */
@Serializable
enum class SpeedLevel(val defaultMetersPerSecond: Double) {
    WALK(1.4),
    RUN(3.3),
    BIKE(5.5),
    DRIVE(13.9),
    PLANE(230.0),
    CUSTOM(1.4),
    ;

    companion object {
        /**
         * Case-insensitive lookup that never throws.
         *
         * @return the matching level, or [WALK] when [name] is `null`, blank or unknown.
         */
        fun fromName(name: String?): SpeedLevel {
            if (name == null) return WALK
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return WALK
            return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) } ?: WALK
        }
    }
}

/** How the simulated position is being driven. */
@Serializable
enum class MoveMode {
    /** The position stays where it was set. */
    STATIC,

    /** The floating joystick steers the position relative to the device heading. */
    JOYSTICK,

    /** The position follows a [com.immoral.location.core.route.RoutePlan]. */
    ROUTE,
}

/** Playback state of the route player. */
@Serializable
enum class RouteState {
    /** No route loaded. */
    IDLE,

    /** A route is loaded and advancing. */
    PLAYING,

    /** A route is loaded but not advancing. */
    PAUSED,

    /** The route reached its end and is not set to repeat. */
    FINISHED,
}

/**
 * Complete, serializable simulation configuration shared by the UI, the bridge and the engine.
 *
 * All values are user-editable, therefore [sanitized] is the only thing the engine trusts;
 * it clamps every field into its documented range.
 */
@Serializable
data class SimulationConfig(
    val mode: MoveMode = MoveMode.STATIC,
    val speed: SpeedLevel = SpeedLevel.WALK,
    val customSpeedMps: Double = 1.4,
    /**
     * When `true`, joystick movement is relative to the *device* heading (rotation-vector azimuth)
     * instead of screen/north-up. The default is `false` on purpose: the joystick lives on a
     * north-up map, so "push up" must mean "move north" unless the user explicitly opts into
     * heading-follow from the 朝向 sheet. The old `true` default made a north-up pad move the
     * marker sideways whenever the phone was not pointing north, which reads as "the joystick
     * direction is wrong".
     */
    val followHeading: Boolean = false,
    val fixedBearingDeg: Double = 0.0,
    val updateIntervalMs: Long = 1_000L,
    val gpsJitterMeters: Double = 0.0,
    val altitudeJitterMeters: Double = 0.0,
    val cellSimulation: Boolean = false,
    val wifiSimulation: Boolean = false,
    val gpsSignalSimulation: Boolean = false,
    val stepSimulation: Boolean = false,
    val stepCadenceBpm: Int = 120,
    val independentMode: Boolean = false,
    val independentPackages: Set<String> = emptySet(),
    val routeRepeat: Boolean = false,
    val routeSpeedMps: Double = 2.0,
    /**
     * When `true`, route playback at vehicle speeds inserts synthetic traffic/idle stops: the
     * simulated vehicle brakes to a complete standstill, waits 15..60 s and accelerates again,
     * like a car crossing a signalised city.
     *
     * The schedule itself is a pure function of the plan (see `RoutePlan.trafficStops`), so this
     * flag only switches the behaviour on — it never changes *where* the stops are.
     *
     * Default `false` on purpose:
     *  * every existing acceptance run and saved profile keeps the uninterrupted playback it
     *    documented, and
     *  * a pedestrian plan could not produce a stop even when the flag is on, because the
     *    scheduler only runs for 8..40 m/s (29..144 km/h).
     */
    val routeTrafficStops: Boolean = false,
) {
    /**
     * Speed actually used for movement.
     *
     * [SpeedLevel.CUSTOM] resolves to [customSpeedMps] (already clamped by [sanitized]);
     * every other level resolves to its own preset.
     */
    fun effectiveSpeedMps(): Double = when (speed) {
        SpeedLevel.CUSTOM -> customSpeedMps.coerceIn(MIN_CUSTOM_SPEED_MPS, MAX_CUSTOM_SPEED_MPS)
        else -> speed.defaultMetersPerSecond
    }

    /**
     * Copy of this configuration with every field clamped into its legal range.
     *
     * Ranges: `updateIntervalMs` 100..10000, `stepCadenceBpm` 60..240,
     * `customSpeedMps` 0.1..1000, `gpsJitterMeters`/`altitudeJitterMeters` 0..100,
     * `routeSpeedMps` 0.1..1000, `fixedBearingDeg` normalized into `[0,360)`.
     * Non-finite doubles are replaced by their default. Blank package names are dropped.
     */
    fun sanitized(): SimulationConfig = copy(
        customSpeedMps = if (customSpeedMps.isFinite()) {
            customSpeedMps.coerceIn(MIN_CUSTOM_SPEED_MPS, MAX_CUSTOM_SPEED_MPS)
        } else {
            SpeedLevel.CUSTOM.defaultMetersPerSecond
        },
        updateIntervalMs = updateIntervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
        stepCadenceBpm = stepCadenceBpm.coerceIn(MIN_CADENCE_BPM, MAX_CADENCE_BPM),
        gpsJitterMeters = sanitizeJitter(gpsJitterMeters),
        altitudeJitterMeters = sanitizeJitter(altitudeJitterMeters),
        routeSpeedMps = if (routeSpeedMps.isFinite()) {
            routeSpeedMps.coerceIn(MIN_CUSTOM_SPEED_MPS, MAX_CUSTOM_SPEED_MPS)
        } else {
            DEFAULT_ROUTE_SPEED_MPS
        },
        fixedBearingDeg = normalizeBearingDeg(fixedBearingDeg),
        independentPackages = independentPackages.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
    )

    companion object {
        /** Lowest allowed `updateIntervalMs`. */
        const val MIN_INTERVAL_MS: Long = 100L

        /** Highest allowed `updateIntervalMs`. */
        const val MAX_INTERVAL_MS: Long = 10_000L

        /** Lowest allowed step cadence. */
        const val MIN_CADENCE_BPM: Int = 60

        /** Highest allowed step cadence. */
        const val MAX_CADENCE_BPM: Int = 240

        /** Lowest allowed custom / route speed. */
        const val MIN_CUSTOM_SPEED_MPS: Double = 0.1

        /** Highest allowed custom / route speed. */
        const val MAX_CUSTOM_SPEED_MPS: Double = 1_000.0

        /** Highest allowed jitter amplitude. */
        const val MAX_JITTER_METERS: Double = 100.0

        /** Fallback route speed when the configured one is not a finite number. */
        const val DEFAULT_ROUTE_SPEED_MPS: Double = 2.0

        private fun sanitizeJitter(value: Double): Double =
            if (value.isFinite()) value.coerceIn(0.0, MAX_JITTER_METERS) else 0.0

        private fun normalizeBearingDeg(value: Double): Double {
            if (!value.isFinite()) return 0.0
            val wrapped = value % 360.0
            return if (wrapped < 0.0) wrapped + 360.0 else wrapped
        }
    }
}

/**
 * Immutable snapshot of everything the engine currently simulates.
 *
 * The hook layer answers `GetState` with this object and the UI renders it, so it carries the
 * position, the derived motion values and the "am I actually hooked" version marker.
 *
 * [speedMps] is the single source of truth for the injected ground speed: the hook must call
 * `Location.setSpeed(state.speedMps.toFloat())` instead of estimating a speed from the delta
 * between two consecutive positions. Dead-reckoning the delta double-counts jitter and turns a
 * correct movement model into a wrong `vel` field.
 */
@Serializable
data class EngineState(
    val running: Boolean = false,
    val mode: MoveMode = MoveMode.STATIC,
    val routeState: RouteState = RouteState.IDLE,
    val point: GeoPoint = GeoPoint.ZERO,
    val speedMps: Double = 0.0,
    val bearingDeg: Double = 0.0,
    val steps: Long = 0L,
    val headingDeg: Double = 0.0,
    val routeProgress: Double = 0.0,
    val updatedAtMs: Long = 0L,
    val activePackages: List<String> = emptyList(),
    val engineVersion: String = "",
)

/** A user-saved location shown on the map. */
@Serializable
data class FavoritePlace(
    val id: String,
    val name: String,
    val point: GeoPoint,
    val createdAtMs: Long,
)

/**
 * One GNSS satellite as reported to `GnssStatus.Callback`.
 *
 * @property constellation Numeric `GnssStatus.CONSTELLATION_*` constant. The constants are
 *   inlined as literals so `:core` stays free of Android imports
 *   (GPS=1, SBAS=2, GLONASS=3, QZSS=4, BEIDOU=5, GALILEO=6, IRNSS=7).
 */
@Serializable
data class Satellite(
    val svid: Int,
    val constellation: Int,
    val cn0DbHz: Float,
    val usedInFix: Boolean,
    val elevationDeg: Float,
    val azimuthDeg: Float,
)

/**
 * One simulated cellular cell (serving or neighbour).
 *
 * @property type `"GSM"`, `"LTE"` or `"NR"`.
 * @property lac TAC for LTE/NR, LAC for GSM.
 * @property cid CI / NCI, hence the wider [Long].
 * @property level Signal bars `0..4`.
 */
@Serializable
data class SimulatedCell(
    val type: String = "LTE",
    val mcc: String = "460",
    val mnc: String = "00",
    val lac: Int = 0,
    val cid: Long = 0L,
    val pci: Int = 0,
    val level: Int = 3,
    val dbm: Int = -75,
    val registered: Boolean = true,
)

/** One simulated Wi-Fi access point returned from `WifiManager.getScanResults()`. */
@Serializable
data class SimulatedAp(
    val ssid: String,
    val bssid: String,
    val level: Int = -60,
    val frequencyMhz: Int = 2412,
)

package com.immoral.location.ipc

import android.os.Parcel
import android.os.Parcelable
import com.immoral.location.core.model.EngineState
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.MoveMode
import com.immoral.location.core.model.RouteState
import com.immoral.location.core.model.Satellite
import com.immoral.location.core.model.SimulatedAp
import com.immoral.location.core.model.SimulatedCell
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.model.SpeedLevel
import com.immoral.location.core.route.RoutePlan

/**
 * Wire structs of the binder contract.
 *
 * These are the **only** types that cross the process boundary, and they are hand-written
 * `Parcelable`s on purpose:
 *
 *  * the layout is explicit and positional, so the protocol cannot silently grow a field the
 *    other side does not know about (the previous JSON codec's `ignoreUnknownKeys` is exactly how
 *    a status snapshot with missing fields turned into a UI that reported `IDLE`/`STATIC` forever);
 *  * `:core` stays a pure-JVM module — it cannot reference `android.os.Parcelable` — so the domain
 *    models stay there and are mapped here, at the edge.
 *
 * Evolution rule: **append fields, never reorder or remove**. `IPC_API_VERSION` is bumped
 * whenever this file changes, and the client refuses to talk to a framework with a different
 * version (the framework layer is injected once per boot, so an app update requires a reboot).
 */
data class WirePoint(
    val lat: Double,
    val lon: Double,
    val alt: Double,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeDouble(lat)
        dest.writeDouble(lon)
        dest.writeDouble(alt)
    }

    companion object CREATOR : Parcelable.Creator<WirePoint> {
        override fun createFromParcel(parcel: Parcel): WirePoint =
            WirePoint(parcel.readDouble(), parcel.readDouble(), parcel.readDouble())

        override fun newArray(size: Int): Array<WirePoint?> = arrayOfNulls(size)
    }
}

data class WireConfig(
    val mode: Int,
    val speed: Int,
    val customSpeedMps: Double,
    val followHeading: Boolean,
    val fixedBearingDeg: Double,
    val updateIntervalMs: Long,
    val gpsJitterMeters: Double,
    val altitudeJitterMeters: Double,
    val cellSimulation: Boolean,
    val wifiSimulation: Boolean,
    val gpsSignalSimulation: Boolean,
    val stepSimulation: Boolean,
    val stepCadenceBpm: Int,
    val independentMode: Boolean,
    val independentPackages: List<String>,
    val routeRepeat: Boolean,
    val routeSpeedMps: Double,
    /**
     * Whether a vehicle-speed route simulates traffic standstills.
     *
     * Added last so the parcel layout only grows: both sides of the bridge ship in the same APK,
     * and a config written by an older build simply deserialises to the default (`false`), which is
     * exactly the behaviour before the field existed.
     */
    val routeTrafficStops: Boolean,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(mode)
        dest.writeInt(speed)
        dest.writeDouble(customSpeedMps)
        dest.writeInt(if (followHeading) 1 else 0)
        dest.writeDouble(fixedBearingDeg)
        dest.writeLong(updateIntervalMs)
        dest.writeDouble(gpsJitterMeters)
        dest.writeDouble(altitudeJitterMeters)
        dest.writeInt(if (cellSimulation) 1 else 0)
        dest.writeInt(if (wifiSimulation) 1 else 0)
        dest.writeInt(if (gpsSignalSimulation) 1 else 0)
        dest.writeInt(if (stepSimulation) 1 else 0)
        dest.writeInt(stepCadenceBpm)
        dest.writeInt(if (independentMode) 1 else 0)
        dest.writeStringList(independentPackages)
        dest.writeInt(if (routeRepeat) 1 else 0)
        dest.writeDouble(routeSpeedMps)
        dest.writeInt(if (routeTrafficStops) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<WireConfig> {
        override fun createFromParcel(parcel: Parcel): WireConfig = WireConfig(
            mode = parcel.readInt(),
            speed = parcel.readInt(),
            customSpeedMps = parcel.readDouble(),
            followHeading = parcel.readInt() != 0,
            fixedBearingDeg = parcel.readDouble(),
            updateIntervalMs = parcel.readLong(),
            gpsJitterMeters = parcel.readDouble(),
            altitudeJitterMeters = parcel.readDouble(),
            cellSimulation = parcel.readInt() != 0,
            wifiSimulation = parcel.readInt() != 0,
            gpsSignalSimulation = parcel.readInt() != 0,
            stepSimulation = parcel.readInt() != 0,
            stepCadenceBpm = parcel.readInt(),
            independentMode = parcel.readInt() != 0,
            independentPackages = parcel.createStringArrayList() ?: emptyList(),
            routeRepeat = parcel.readInt() != 0,
            routeSpeedMps = parcel.readDouble(),
            routeTrafficStops = parcel.readInt() != 0,
        )

        override fun newArray(size: Int): Array<WireConfig?> = arrayOfNulls(size)
    }
}

data class WireState(
    val running: Boolean,
    val mode: Int,
    val routeState: Int,
    val point: WirePoint,
    val speedMps: Double,
    val bearingDeg: Double,
    val steps: Long,
    val headingDeg: Double,
    val routeProgress: Double,
    val updatedAtMs: Long,
    val activePackages: List<String>,
    val engineVersion: String,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(if (running) 1 else 0)
        dest.writeInt(mode)
        dest.writeInt(routeState)
        point.writeToParcel(dest, flags)
        dest.writeDouble(speedMps)
        dest.writeDouble(bearingDeg)
        dest.writeLong(steps)
        dest.writeDouble(headingDeg)
        dest.writeDouble(routeProgress)
        dest.writeLong(updatedAtMs)
        dest.writeStringList(activePackages)
        dest.writeString(engineVersion)
    }

    companion object CREATOR : Parcelable.Creator<WireState> {
        override fun createFromParcel(parcel: Parcel): WireState = WireState(
            running = parcel.readInt() != 0,
            mode = parcel.readInt(),
            routeState = parcel.readInt(),
            point = WirePoint.CREATOR.createFromParcel(parcel),
            speedMps = parcel.readDouble(),
            bearingDeg = parcel.readDouble(),
            steps = parcel.readLong(),
            headingDeg = parcel.readDouble(),
            routeProgress = parcel.readDouble(),
            updatedAtMs = parcel.readLong(),
            activePackages = parcel.createStringArrayList() ?: emptyList(),
            engineVersion = parcel.readString() ?: "",
        )

        override fun newArray(size: Int): Array<WireState?> = arrayOfNulls(size)
    }
}

data class WireRoute(
    val points: List<WirePoint>,
    val repeat: Boolean,
    val speedMps: Double,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeTypedList(points)
        dest.writeInt(if (repeat) 1 else 0)
        dest.writeDouble(speedMps)
    }

    companion object CREATOR : Parcelable.Creator<WireRoute> {
        override fun createFromParcel(parcel: Parcel): WireRoute = WireRoute(
            points = parcel.createTypedArrayList(WirePoint.CREATOR) ?: emptyList(),
            repeat = parcel.readInt() != 0,
            speedMps = parcel.readDouble(),
        )

        override fun newArray(size: Int): Array<WireRoute?> = arrayOfNulls(size)
    }
}

data class WireSatellite(
    val svid: Int,
    val constellation: Int,
    val cn0DbHz: Float,
    val usedInFix: Boolean,
    val elevationDeg: Float,
    val azimuthDeg: Float,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(svid)
        dest.writeInt(constellation)
        dest.writeFloat(cn0DbHz)
        dest.writeInt(if (usedInFix) 1 else 0)
        dest.writeFloat(elevationDeg)
        dest.writeFloat(azimuthDeg)
    }

    companion object CREATOR : Parcelable.Creator<WireSatellite> {
        override fun createFromParcel(parcel: Parcel): WireSatellite = WireSatellite(
            svid = parcel.readInt(),
            constellation = parcel.readInt(),
            cn0DbHz = parcel.readFloat(),
            usedInFix = parcel.readInt() != 0,
            elevationDeg = parcel.readFloat(),
            azimuthDeg = parcel.readFloat(),
        )

        override fun newArray(size: Int): Array<WireSatellite?> = arrayOfNulls(size)
    }
}

data class WireCell(
    val type: String,
    val mcc: String,
    val mnc: String,
    val lac: Int,
    val cid: Long,
    val pci: Int,
    val level: Int,
    val dbm: Int,
    val registered: Boolean,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(type)
        dest.writeString(mcc)
        dest.writeString(mnc)
        dest.writeInt(lac)
        dest.writeLong(cid)
        dest.writeInt(pci)
        dest.writeInt(level)
        dest.writeInt(dbm)
        dest.writeInt(if (registered) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<WireCell> {
        override fun createFromParcel(parcel: Parcel): WireCell = WireCell(
            type = parcel.readString() ?: "LTE",
            mcc = parcel.readString() ?: "460",
            mnc = parcel.readString() ?: "00",
            lac = parcel.readInt(),
            cid = parcel.readLong(),
            pci = parcel.readInt(),
            level = parcel.readInt(),
            dbm = parcel.readInt(),
            registered = parcel.readInt() != 0,
        )

        override fun newArray(size: Int): Array<WireCell?> = arrayOfNulls(size)
    }
}

data class WireAp(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val frequencyMhz: Int,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(ssid)
        dest.writeString(bssid)
        dest.writeInt(level)
        dest.writeInt(frequencyMhz)
    }

    companion object CREATOR : Parcelable.Creator<WireAp> {
        override fun createFromParcel(parcel: Parcel): WireAp = WireAp(
            ssid = parcel.readString() ?: "",
            bssid = parcel.readString() ?: "",
            level = parcel.readInt(),
            frequencyMhz = parcel.readInt(),
        )

        override fun newArray(size: Int): Array<WireAp?> = arrayOfNulls(size)
    }
}

/**
 * Synthesized environment data, fetched on demand instead of being attached to every state push.
 *
 * Attaching it would multiply each 1 Hz snapshot by tens of parcelables for a panel the user
 * opens rarely, and would push long routes/NMEA lists towards the 1 MB binder transaction limit.
 */
data class WireDiagnostics(
    val satellites: List<WireSatellite>,
    val cells: List<WireCell>,
    val accessPoints: List<WireAp>,
    val nmeaLines: List<String>,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeTypedList(satellites)
        dest.writeTypedList(cells)
        dest.writeTypedList(accessPoints)
        dest.writeStringList(nmeaLines)
    }

    companion object CREATOR : Parcelable.Creator<WireDiagnostics> {
        override fun createFromParcel(parcel: Parcel): WireDiagnostics = WireDiagnostics(
            satellites = parcel.createTypedArrayList(WireSatellite.CREATOR) ?: emptyList(),
            cells = parcel.createTypedArrayList(WireCell.CREATOR) ?: emptyList(),
            accessPoints = parcel.createTypedArrayList(WireAp.CREATOR) ?: emptyList(),
            nmeaLines = parcel.createStringArrayList() ?: emptyList(),
        )

        override fun newArray(size: Int): Array<WireDiagnostics?> = arrayOfNulls(size)
    }
}

/** Stable integer encoding of `MoveMode`; append-only, never renumbered. */
object WireMode {
    const val STATIC: Int = 0
    const val JOYSTICK: Int = 1
    const val ROUTE: Int = 2

    fun of(mode: MoveMode): Int = when (mode) {
        MoveMode.STATIC -> STATIC
        MoveMode.JOYSTICK -> JOYSTICK
        MoveMode.ROUTE -> ROUTE
    }

    fun toMode(value: Int): MoveMode = when (value) {
        JOYSTICK -> MoveMode.JOYSTICK
        ROUTE -> MoveMode.ROUTE
        else -> MoveMode.STATIC
    }
}

/** Stable integer encoding of `RouteState`; append-only, never renumbered. */
object WireRouteState {
    const val IDLE: Int = 0
    const val PLAYING: Int = 1
    const val PAUSED: Int = 2
    const val FINISHED: Int = 3

    fun of(state: RouteState): Int = when (state) {
        RouteState.IDLE -> IDLE
        RouteState.PLAYING -> PLAYING
        RouteState.PAUSED -> PAUSED
        RouteState.FINISHED -> FINISHED
    }

    fun toState(value: Int): RouteState = when (value) {
        PLAYING -> RouteState.PLAYING
        PAUSED -> RouteState.PAUSED
        FINISHED -> RouteState.FINISHED
        else -> RouteState.IDLE
    }
}

/** Stable integer encoding of `SpeedLevel`; append-only, never renumbered. */
object WireSpeed {
    const val WALK: Int = 0
    const val RUN: Int = 1
    const val BIKE: Int = 2
    const val DRIVE: Int = 3
    const val PLANE: Int = 4
    const val CUSTOM: Int = 5

    fun of(level: SpeedLevel): Int = when (level) {
        SpeedLevel.WALK -> WALK
        SpeedLevel.RUN -> RUN
        SpeedLevel.BIKE -> BIKE
        SpeedLevel.DRIVE -> DRIVE
        SpeedLevel.PLANE -> PLANE
        SpeedLevel.CUSTOM -> CUSTOM
    }

    fun toLevel(value: Int): SpeedLevel = when (value) {
        RUN -> SpeedLevel.RUN
        BIKE -> SpeedLevel.BIKE
        DRIVE -> SpeedLevel.DRIVE
        PLANE -> SpeedLevel.PLANE
        CUSTOM -> SpeedLevel.CUSTOM
        else -> SpeedLevel.WALK
    }
}

/** `onResult` codes. Zero is success; everything else names a rejection reason. */
object WireResult {
    const val OK: Int = 0
    const val INVALID_ARGUMENT: Int = 1
    const val UNAVAILABLE: Int = 2
    const val INTERNAL_ERROR: Int = 3
    const val NOT_RUNNING: Int = 4
    const val TOO_LARGE: Int = 5
}

/** `onEngineRestarted` reasons. */
object WireRestart {
    const val FRAMEWORK_BOOT: Int = 1
    const val ENGINE_RESET: Int = 2
}

// --------------------------------------------------------------------- mappers

fun GeoPoint.toWire(): WirePoint = WirePoint(lat, lon, alt)

fun WirePoint.toCore(): GeoPoint = GeoPoint(lat, lon, alt)

fun SimulationConfig.toWire(): WireConfig = WireConfig(
    mode = WireMode.of(mode),
    speed = WireSpeed.of(speed),
    customSpeedMps = customSpeedMps,
    followHeading = followHeading,
    fixedBearingDeg = fixedBearingDeg,
    updateIntervalMs = updateIntervalMs,
    gpsJitterMeters = gpsJitterMeters,
    altitudeJitterMeters = altitudeJitterMeters,
    cellSimulation = cellSimulation,
    wifiSimulation = wifiSimulation,
    gpsSignalSimulation = gpsSignalSimulation,
    stepSimulation = stepSimulation,
    stepCadenceBpm = stepCadenceBpm,
    independentMode = independentMode,
    independentPackages = independentPackages.toList(),
    routeRepeat = routeRepeat,
    routeSpeedMps = routeSpeedMps,
    routeTrafficStops = routeTrafficStops,
)

fun WireConfig.toCore(): SimulationConfig = SimulationConfig(    mode = WireMode.toMode(mode),
    speed = WireSpeed.toLevel(speed),
    customSpeedMps = customSpeedMps,
    followHeading = followHeading,
    fixedBearingDeg = fixedBearingDeg,
    updateIntervalMs = updateIntervalMs,
    gpsJitterMeters = gpsJitterMeters,
    altitudeJitterMeters = altitudeJitterMeters,
    cellSimulation = cellSimulation,
    wifiSimulation = wifiSimulation,
    gpsSignalSimulation = gpsSignalSimulation,
    stepSimulation = stepSimulation,
    stepCadenceBpm = stepCadenceBpm,
    independentMode = independentMode,
    independentPackages = independentPackages.toSet(),
    routeRepeat = routeRepeat,
    routeSpeedMps = routeSpeedMps,
    routeTrafficStops = routeTrafficStops,
)

fun EngineState.toWire(): WireState = WireState(
    running = running,
    mode = WireMode.of(mode),
    routeState = WireRouteState.of(routeState),
    point = point.toWire(),
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    steps = steps,
    headingDeg = headingDeg,
    routeProgress = routeProgress,
    updatedAtMs = updatedAtMs,
    activePackages = activePackages,
    engineVersion = engineVersion,
)

fun WireState.toCore(): EngineState = EngineState(
    running = running,
    mode = WireMode.toMode(mode),
    routeState = WireRouteState.toState(routeState),
    point = point.toCore(),
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    steps = steps,
    headingDeg = headingDeg,
    routeProgress = routeProgress,
    updatedAtMs = updatedAtMs,
    activePackages = activePackages,
    engineVersion = engineVersion,
)

fun RoutePlan.toWire(): WireRoute = WireRoute(
    points = points.map { it.toWire() },
    repeat = repeat,
    speedMps = speedMps,
)

fun WireRoute.toCore(): RoutePlan = RoutePlan(
    points = points.map { it.toCore() },
    repeat = repeat,
    speedMps = speedMps,
)

fun Satellite.toWire(): WireSatellite = WireSatellite(
    svid = svid,
    constellation = constellation,
    cn0DbHz = cn0DbHz,
    usedInFix = usedInFix,
    elevationDeg = elevationDeg,
    azimuthDeg = azimuthDeg,
)

fun WireSatellite.toCore(): Satellite = Satellite(
    svid = svid,
    constellation = constellation,
    cn0DbHz = cn0DbHz,
    usedInFix = usedInFix,
    elevationDeg = elevationDeg,
    azimuthDeg = azimuthDeg,
)

fun SimulatedCell.toWire(): WireCell = WireCell(
    type = type,
    mcc = mcc,
    mnc = mnc,
    lac = lac,
    cid = cid,
    pci = pci,
    level = level,
    dbm = dbm,
    registered = registered,
)

fun WireCell.toCore(): SimulatedCell = SimulatedCell(
    type = type,
    mcc = mcc,
    mnc = mnc,
    lac = lac,
    cid = cid,
    pci = pci,
    level = level,
    dbm = dbm,
    registered = registered,
)

fun SimulatedAp.toWire(): WireAp = WireAp(
    ssid = ssid,
    bssid = bssid,
    level = level,
    frequencyMhz = frequencyMhz,
)

fun WireAp.toCore(): SimulatedAp = SimulatedAp(
    ssid = ssid,
    bssid = bssid,
    level = level,
    frequencyMhz = frequencyMhz,
)

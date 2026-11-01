# ImmoralLocation — Interface Contracts (FROZEN)

Read this file before writing code. It is the single source of truth shared by `:core`, `:hook`,
`:app` and `:verifier`. **Do not rename or change signatures in this file.** If you need more,
add new declarations; never break existing ones. If something here is impossible, ask the Lead.

Build: `.\gradlew.bat :core:test :app:assembleDebug :verifier:assembleDebug --console=plain`
Toolchain (already working): AGP 9.5.0 (built-in Kotlin, **no** `kotlin-android` plugin),
Gradle 9.8.0, JDK 17, `compileSdk 37`, **`minSdk 21`** (Android 5), Compose BOM 2025.06.01.
AndroidX in use are pinned to the minSdk-21 generation (core-ktx 1.16.0, activity 1.10.1,
lifecycle 2.9.3). Do **not** bump those versions: anything newer requires minSdk 23.
No `androidx.datastore` (minSdk 23) — use `SharedPreferences`.

## Module ownership

| Module | Owner | Notes |
|---|---|---|
| `core/` | `engine-core` | Pure Kotlin/JVM, no Android imports. Unit-tested on the JVM. |
| `hook/` | `hook-framework` | Android library merged into the app APK; runs inside `system_server` / app processes. |
| `app/` | `app-ui` | Compose Material You UI, floating joystick, foreground service, bridge client. |
| `verifier/`, `e2e/`, `.github/` | `qa-e2e` | Acceptance harness, device scripts, CI. |

## 1. `:core` package `com.immoral.location.core.model`

```kotlin
@Serializable
data class GeoPoint(val lat: Double = 0.0, val lon: Double = 0.0, val alt: Double = 50.0) {
    fun isValid(): Boolean                      // lat in -90..90, lon in -180..180, not both 0
    fun isValidLatLon(): Boolean
    companion object { val ZERO: GeoPoint }
}

@Serializable
enum class SpeedLevel(val defaultMetersPerSecond: Double) {
    WALK(1.4), RUN(3.3), BIKE(5.5), DRIVE(13.9), PLANE(230.0), CUSTOM(1.4);
    companion object { fun fromName(name: String?): SpeedLevel }
}

@Serializable
enum class MoveMode { STATIC, JOYSTICK, ROUTE }

@Serializable
enum class RouteState { IDLE, PLAYING, PAUSED, FINISHED }

@Serializable
data class SimulationConfig(
    val mode: MoveMode = MoveMode.STATIC,
    val speed: SpeedLevel = SpeedLevel.WALK,
    val customSpeedMps: Double = 1.4,          // used when speed == CUSTOM (0.1 .. 1000)
    val followHeading: Boolean = true,         // joystick direction follows device heading
    val fixedBearingDeg: Double = 0.0,         // used when followHeading == false
    val updateIntervalMs: Long = 1000L,        // 100 .. 10000
    val gpsJitterMeters: Double = 0.0,
    val altitudeJitterMeters: Double = 0.0,
    val cellSimulation: Boolean = false,
    val wifiSimulation: Boolean = false,
    val gpsSignalSimulation: Boolean = false,  // satellites + NMEA + GnssStatus
    val stepSimulation: Boolean = false,
    val stepCadenceBpm: Int = 120,             // 60 .. 240
    val independentMode: Boolean = false,      // 独立模拟: only listed packages are spoofed
    val independentPackages: Set<String> = emptySet(),
    val routeRepeat: Boolean = false,
    val routeSpeedMps: Double = 2.0,
) {
    fun effectiveSpeedMps(): Double
    fun sanitized(): SimulationConfig           // clamps out-of-range values
}

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
    val routeProgress: Double = 0.0,            // 0..1
    val updatedAtMs: Long = 0L,
    val activePackages: List<String> = emptyList(),
    val engineVersion: String = "",
)

@Serializable
data class FavoritePlace(
    val id: String,
    val name: String,
    val point: GeoPoint,
    val createdAtMs: Long,
)

@Serializable
data class Satellite(
    val svid: Int,
    val constellation: Int,      // GnssStatus.CONSTELLATION_* value
    val cn0DbHz: Float,
    val usedInFix: Boolean,
    val elevationDeg: Float,
    val azimuthDeg: Float,
)

@Serializable
data class SimulatedCell(
    val type: String = "LTE",    // "GSM" | "LTE" | "NR"
    val mcc: String = "460",
    val mnc: String = "00",
    val lac: Int = 0,            // TAC for LTE/NR
    val cid: Long = 0L,          // CI / NCI
    val pci: Int = 0,
    val level: Int = 3,          // 0..4 signal bars
    val dbm: Int = -75,
    val registered: Boolean = true,
)

@Serializable
data class SimulatedAp(
    val ssid: String,
    val bssid: String,
    val level: Int = -60,        // dBm
    val frequencyMhz: Int = 2412,
)
```

## 2. `:core` package `com.immoral.location.core.geo`

```kotlin
object GeoMath {
    const val EARTH_RADIUS_M: Double = 6371008.8
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double          // haversine
    fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double          // 0..360, 0 = north
    fun destination(start: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint
    fun normalizeBearing(deg: Double): Double                     // -> [0,360)
    fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint
    fun shortestAngleDelta(fromDeg: Double, toDeg: Double): Double // [-180,180]
}
```

## 3. `:core` package `com.immoral.location.core.engine`

```kotlin
class SimulationEngine(
    initialPosition: GeoPoint = GeoPoint.ZERO,
    initialConfig: SimulationConfig = SimulationConfig(),
) {
    var config: SimulationConfig                 // setter sanitizes + re-seeds route player
    var running: Boolean
    val position: GeoPoint
    val state: EngineState

    fun setPosition(point: GeoPoint, emitImpact: Boolean = true)
    fun setJoystick(x: Float, y: Float)          // x right +1, y forward +1, each -1..1; strength>0 means moving
    fun releaseJoystick()
    fun setHeading(deg: Double)                  // device heading (rotation vector azimuth)
    fun startRoute(points: List<GeoPoint>, repeat: Boolean = config.routeRepeat, speedMps: Double = config.routeSpeedMps)
    fun playRoute(); fun pauseRoute(); fun stopRoute()
    fun resetSteps(steps: Long = 0L)
    /** Advance the simulation by deltaMs of wall clock. Returns delta-scaled state. */
    fun tick(deltaMs: Long): EngineState
    fun satellites(): List<Satellite>            // deterministic sythesis from position
    fun nmea(nowMs: Long): List<String>          // $GPGGA, $GPRMC, $GPGSV ...
    fun cells(): List<SimulatedCell>             // 基站模拟 near current position
    fun accessPoints(): List<SimulatedAp>        // WIFI 模拟 near current position
    fun stepDelta(deltaMs: Long): Int            // steps produced in this window
}
```

Determinism rules: every synthesis function must be a pure function of (position, config) so the
hook layer and the UI show identical values. No randomness without a seeded value derived from
`(lat, lon)` rounded to 4 decimals.

**Step counter semantics (changed 2026-11-01).** `stepDelta(deltaMs)` and the counter that `tick`
accumulates are **cadence-driven**: steps advance by `stepCadenceBpm / 60 * deltaMs / 1000`
(whole steps only, the fraction carries over) whenever `running == true` and
`config.stepSimulation == true`, in **every** `MoveMode` and at every speed. The old rule
("only above 0.5 m/s") made 步频模拟 inert in `STATIC` mode, which is the mode the device harness
configures — a counter that only climbs while the position moves cannot be told apart from a
broken one. Stopping the engine (`running = false`) or turning the toggle off stops the counter;
moving or not moving the simulated position does not change the rate. Same numbers on both sides
of the bridge, so the UI can show the cadence it will produce before the engine starts.

## 4. `:core` package `com.immoral.location.core.route`

```kotlin
@Serializable
data class RoutePlan(
    val points: List<GeoPoint>,
    val repeat: Boolean = false,
    val speedMps: Double = 2.0,
) {
    fun totalDistanceMeters(): Double
    /** position + bearing at a normalized progress 0..1 */
    fun sample(progress: Double): Pair<GeoPoint, Double>
}

object RouteIO {
    fun parseGpx(xml: String): List<GeoPoint>            // trkpt / rtept / wpt
    fun toGpx(points: List<GeoPoint>, name: String = "ImmoralLocation route"): String
    fun densify(points: List<GeoPoint>, maxSegmentMeters: Double = 25.0): List<GeoPoint>
}
```

## 5. `:core` package `com.immoral.location.core.protocol`

```kotlin
object Bridge {
    const val MODULE_PACKAGE = "com.immoral.location"
    const val PERMISSION_CONTROL = "com.immoral.location.permission.CONTROL"
    const val ACTION_COMMAND = "com.immoral.location.action.COMMAND"   // app -> framework
    const val ACTION_REPLY = "com.immoral.location.action.REPLY"       // framework -> app
    const val EXTRA_PAYLOAD = "com.immoral.location.extra.PAYLOAD"
}

@Serializable
sealed interface Command {
    @Serializable data object Ping : Command
    @Serializable data object GetState : Command
    @Serializable data class SetConfig(val config: SimulationConfig) : Command
    @Serializable data object Start : Command
    @Serializable data object Stop : Command
    @Serializable data class SetPosition(val point: GeoPoint) : Command
    @Serializable data class SetJoystick(val x: Float, val y: Float) : Command
    @Serializable data object ReleaseJoystick : Command
    @Serializable data class SetHeading(val deg: Double) : Command
    @Serializable data class StartRoute(val plan: RoutePlan) : Command
    @Serializable data object PlayRoute : Command
    @Serializable data object PauseRoute : Command
    @Serializable data object StopRoute : Command
    @Serializable data class ResetSteps(val steps: Long = 0L) : Command
}

@Serializable
data class BridgeReply(
    val ok: Boolean = true,
    val error: String? = null,
    val state: EngineState? = null,
    val engineLoaded: Boolean = false,           // framework side answered => module active
    val moduleVersion: String = "",
)

object Codec {
    fun encodeCommand(command: Command): String
    fun decodeCommand(json: String): Command?
    fun encodeReply(reply: BridgeReply): String
    fun decodeReply(json: String): BridgeReply?
}
```

`Codec` must never throw on bad input: return `null` instead. Use `classDiscriminator = "cmd"`.

## 6. `:hook` package layout (`com.immoral.location.hook.*`)

Owned by `hook-framework`. Already present: `XposedEntry` (registered in `assets/xposed_init`).
Required classes (names are contract, used by docs and tests):

```
hook/XposedEntry.kt              // dispatch: framework (system_server) vs client (app process)
hook/BridgeServer.kt             // BroadcastReceiver inside system_server; ACTION_COMMAND -> engine
hook/EngineRuntime.kt            // HandlerThread ticker owning a SimulationEngine
hook/framework/LocationHooks.kt  // Layer A: LocationManagerService / LocationProviderManager
hook/framework/WifiHooks.kt      // Layer A: WifiServiceImpl.getScanResults (system_server)
hook/framework/TelephonyHooks.kt // Layer A: TelephonyRegistry / CellInfo if reachable
hook/client/SensorHooks.kt       // Layer B: TYPE_STEP_COUNTER / TYPE_STEP_DETECTOR
hook/client/LocationClientHooks.kt // Layer B: LocationManager client-side fallback
hook/client/TelephonyClientHooks.kt// Layer B: TelephonyManager.getAllCellInfo/getCellLocation
hook/client/WifiClientHooks.kt   // Layer B: WifiManager.getScanResults fallback
hook/client/GnssClientHooks.kt   // Layer B: GpsStatus/GnssStatus + NMEA
hook/Scope.kt                    // isSpoofedPackage(uid/pkg): independent-mode filtering
hook/Log.kt                      // XposedBridge.log wrapper, tag "[ImmoralLocation]"
```

## 7. `:app` package layout (`com.immoral.location.*`)

Owned by `app-ui`. Already present and referenced by the manifest: `App`,
`ui.MainActivity`, `service.SimulationService`, `bridge.BridgeReplyReceiver`.
Manifest permissions/`xposedmodule` metadata already exist; extend the manifest only through the
Lead unless the change is additive and local to your module.

## 8. Verification contract

`verifier/` prints machine-readable lines to logcat tag `ImmoralVerifier`:

```
VERIFY {"kind":"location","provider":"gps","lat":..,"lon":..,"mock":false,...}
VERIFY {"kind":"cell","mcc":"460","mnc":"00","lac":..,"ci":..}
VERIFY {"kind":"wifi","count":N,"aps":[...]}
VERIFY {"kind":"stepCounter","value":N}
VERIFY {"kind":"gnss","satellites":N,"usedInFix":M}
```

E2E scripts parse these with `adb logcat -s ImmoralVerifier:I`.

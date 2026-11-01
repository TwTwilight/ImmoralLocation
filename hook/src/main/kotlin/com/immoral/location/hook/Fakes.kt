package com.immoral.location.hook

import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.immoral.location.core.model.Satellite
import com.immoral.location.core.model.SimulatedAp
import com.immoral.location.core.model.SimulatedCell
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Factory for the spoofed framework objects: [Location], `LocationResult`, `GnssStatus`,
 * `ScanResult` and `CellInfo`.
 *
 * Everything that is not a stable public API (LocationResult, GnssStatus, hidden Location setters,
 * CellIdentity constructors) is built by reflection with an ordered list of fallbacks, and every
 * failure is logged exactly once. A missing class on an old/new API level degrades that single
 * feature, never the module.
 */
object Fakes {

    // ------------------------------------------------------------------ Location

    /**
     * Builds the spoofed [Location] for [provider] (`gps`/`network`/`fused`/`passive`).
     *
     * The `immoral.*` tags are a **diagnostic state bus, not a product feature**: any app that
     * receives a fix can read `location.extras`, so they name the module and its per-feature
     * switches to anyone who looks. They are therefore written only when a debug property is set —
     * see [Log.extrasEnabled]; otherwise the bundle is not set at all (no empty values, no stub
     * keys), and the Location is indistinguishable from one an OEM put together.
     */
    fun location(provider: String, snap: EngineRuntime.Snap): Location {
        val location = location(
            provider,
            snap.lat,
            snap.lon,
            snap.alt,
            snap.speedMps,
            snap.bearingDeg,
            snap.accuracyFor(provider),
            snap.steps,
        )
        if (Log.extrasEnabled()) {
            try {
                val extras = location.extras ?: Bundle()
                extras.putInt("satellites", snap.satellites.count { it.usedInFix })
                extras.putLong(EXTRA_STEPS, snap.steps)
                extras.putInt(EXTRA_CADENCE, snap.config.stepCadenceBpm)
                extras.putBoolean(EXTRA_STEP_SIM, snap.config.stepSimulation)
                extras.putBoolean(EXTRA_GPS_SIGNAL_SIM, snap.config.gpsSignalSimulation)
                extras.putBoolean(EXTRA_CELL_SIM, snap.config.cellSimulation)
                extras.putBoolean(EXTRA_WIFI_SIM, snap.config.wifiSimulation)
                extras.putBoolean(EXTRA_INDEPENDENT, snap.config.independentMode)
                if (snap.config.independentMode) {
                    extras.putString(
                        EXTRA_INDEPENDENT_PACKAGES,
                        snap.config.independentPackages.sorted().joinToString(","),
                    )
                }
                extras.putLong(EXTRA_TS, snap.updatedAtMs)
                location.extras = extras
            } catch (t: Throwable) {
                Log.once("fakes.extras", "location extras failed: ${t.javaClass.simpleName}")
            }
        }
        return location
    }

    /** Extras keys set by [location]; the client layer's state bus. */
    const val EXTRA_STEPS = "immoral.steps"
    const val EXTRA_CADENCE = "immoral.cadence"
    const val EXTRA_STEP_SIM = "immoral.stepSim"
    const val EXTRA_GPS_SIGNAL_SIM = "immoral.gpsSignalSim"
    const val EXTRA_CELL_SIM = "immoral.cellSim"
    const val EXTRA_WIFI_SIM = "immoral.wifiSim"
    const val EXTRA_INDEPENDENT = "immoral.independent"

    /** Comma separated whitelist for independent mode, carried on the state bus. */
    const val EXTRA_INDEPENDENT_PACKAGES = "immoral.independentPackages"
    const val EXTRA_TS = "immoral.ts"

    fun location(
        provider: String,
        lat: Double,
        lon: Double,
        alt: Double,
        speedMps: Double,
        bearingDeg: Double,
        accuracyM: Double,
        steps: Long = 0L,
    ): Location {
        val location = Location(provider)
        try {
            location.latitude = lat
            location.longitude = lon
            location.altitude = alt
            location.accuracy = accuracyM.toFloat()
            location.speed = speedMps.coerceAtLeast(0.0).toFloat()
            location.bearing = ((bearingDeg % 360.0) + 360.0).mod(360.0).toFloat()
            val nowMs = System.currentTimeMillis()
            location.time = nowMs
            location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            setOptionalNumber(location, "setVerticalAccuracyMeters", accuracyM * 1.5)
            setOptionalNumber(location, "setSpeedAccuracyMetersPerSecond", 0.5 + speedMps * 0.05)
            setOptionalNumber(location, "setBearingAccuracyDegrees", 5.0)
            // Never advertise ourselves as a mock provider — that is the whole point.
            try {
                setIsFromMockProvider.invoke(location, false)
            } catch (_: Throwable) {
            }
            try {
                setMock?.let { Refl.invoke(it, location, false) }
            } catch (_: Throwable) {
            }
            // Same diagnostic-bus gate as [location] above: no keys at all unless the property is set.
            if (Log.extrasEnabled()) {
                val extras = Bundle()
                extras.putInt("satellites", 0)
                extras.putLong(EXTRA_STEPS, steps)
                try {
                    location.extras = extras
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            Log.once("fakes.location", "location build failed: ${t.javaClass.simpleName}")
        }
        rememberFabricated(location)
        return location
    }

    /** Hidden `Location.setMock(boolean)`; absent on some vendors. */
    private val setMock: Method? by lazy { Refl.methodOf(Location::class.java, "setMock", 1) }

    /** `Location.setIsFromMockProvider(boolean)` — reflected because the Kotlin property is val. */
    private val setIsFromMockProvider: Method by lazy {
        Location::class.java.getMethod("setIsFromMockProvider", Boolean::class.javaPrimitiveType)
    }

    private fun setOptionalNumber(location: Location, method: String, value: Double) {
        try {
            val m = Refl.methodOf(Location::class.java, method, 1) ?: return
            Refl.invoke(m, location, value.toFloat())
        } catch (_: Throwable) {
        }
    }

    /** Provider name of the first [Location] inside a framework `LocationResult`. */
    fun providerOfResult(result: Any?): String? =
        locationsOfResult(result).firstOrNull()?.provider

    /** [Location]s inside a framework `LocationResult` (reflection, null-safe). */
    fun locationsOfResult(result: Any?): List<Location> {
        if (result == null) return emptyList()
        val getter = Refl.methodOf(result.javaClass, "getLocations", 0)
        val viaGetter = Refl.invoke(getter, result) as? List<*>
        if (viaGetter != null) {
            return viaGetter.mapNotNull { it as? Location }
        }
        val field = Refl.anyField(result.javaClass, "mLocations", "mResults")
        val viaField = Refl.get(result, field) as? List<*> ?: return emptyList()
        return viaField.mapNotNull { it as? Location }
    }

    // ------------------------------------------------------------------ LocationResult

    private val locationResultClass: Class<*>? by lazy { Refl.findClassAnywhere("android.location.LocationResult") }
    private var locationResultFactory: Method? = null
    private var locationResultCtor: Constructor<*>? = null
    private var locationResultResolved = false

    /** LocationResults built by this module — used to tell our synthetic fixes from real ones. */
    private val fabricatedResults: MutableSet<Any> =
        java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>()))

    /** `true` when [value] is a `LocationResult` this module created. */

    /**
     * Answers whether anything inside [value] is a fabricated Location/LocationResult, including the
     * framework wrappers used when replaying the last fix to a fresh registration
     * (LastLocationResult, ReportedLocationUpdate, ...) - the path that leaked the fake fix to a
     * non-whitelisted app in independent mode.
     */
    fun containsFabricated(value: Any?): Boolean {
        if (value == null) return false
        if (isFabricated(value)) return true
        return try {
            var cursor: Class<*>? = value.javaClass
            var depth = 0
            while (cursor != null && depth < 8) {
                for (field in cursor.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    val type = field.type
                    val interesting = type == Location::class.java ||
                        type.name.endsWith("LocationResult") ||
                        type.name.contains("ReportedLocationUpdate")
                    if (!interesting) continue
                    try {
                        field.isAccessible = true
                    } catch (_: Throwable) {
                        continue
                    }
                    val inner = try {
                        field.get(value)
                    } catch (_: Throwable) {
                        null
                    }
                    if (inner != null && isFabricated(inner)) return true
                }
                cursor = cursor.superclass
                depth++
            }
            false
        } catch (_: Throwable) {
            false
        }
    }
    fun isFabricated(value: Any?): Boolean = value != null && fabricatedResults.contains(value)

    private fun rememberFabricated(value: Any?) {
        if (value == null) return
        if (fabricatedResults.size > 512) fabricatedResults.clear()
        fabricatedResults.add(value)
    }

    /**
     * Builds an `android.location.LocationResult` from [locations].
     *
     * Resolution order: static `create(List)`, static `wrap(List)`, hidden `LocationResult(List)`.
     */
    fun locationResult(locations: List<Location>): Any? {
        val cls = locationResultClass ?: return null
        if (locations.isEmpty()) return null
        if (!locationResultResolved) {
            locationResultResolved = true
            for (name in arrayOf("create", "wrap", "of")) {
                val m = Refl.methodsNamed(cls, name).firstOrNull {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                        it.parameterTypes.size == 1 && it.parameterTypes[0] == List::class.java
                }
                if (m != null) {
                    locationResultFactory = m
                    Log.i("Fakes: LocationResult.$name(List) available")
                    break
                }
            }
            if (locationResultFactory == null) {
                locationResultCtor = Refl.constructorsWhere(cls) {
                    it.parameterTypes.size == 1 && it.parameterTypes[0] == List::class.java
                }.firstOrNull()
                Log.i("Fakes: LocationResult ctor(List) ${if (locationResultCtor != null) "available" else "MISSING"}")
            }
        }
        try {
            locationResultFactory?.let { factory ->
                val built = Refl.invokeStatic(factory, locations)
                rememberFabricated(built)
                return built
            }
            locationResultCtor?.let { ctor ->
                ctor.isAccessible = true
                val built = ctor.newInstance(locations)
                rememberFabricated(built)
                return built
            }
        } catch (t: Throwable) {
            Log.once("fakes.lr", "LocationResult build failed: ${t.javaClass.simpleName}")
        }
        return null
    }

    // ------------------------------------------------------------------ GnssStatus

    private var gnssBuilder: Class<*>? = null
    private var gnssCtor: Constructor<*>? = null
    private var gnssResolved = false

    // ---- construction strategies, chosen by a round-trip probe --------------------------

    private const val STRATEGY_UNKNOWN = -1
    private const val STRATEGY_NONE = -2
    private const val STRATEGY_BUILDER = 0
    private const val STRATEGY_CTOR = 1
    private const val STRATEGY_ALLOCATE = 2

    @Volatile
    private var gnssStrategy = STRATEGY_UNKNOWN

    @Volatile
    private var probed = false

    /**
     * One-shot round-trip probe, executed once per process at install time.
     *
     * On vendor ROMs a synthetic object can be created successfully and still read back as if it were
     * empty (the getters read a field we never wrote). The only trustworthy test is: build it, then
     * read it back through the public getters. Every strategy logs its verdict, and the first one that
     * round-trips is used from then on.
     */
    fun probeOnce() {
        if (probed) return
        probed = true
        try {
            probeGnss()
        } catch (t: Throwable) {
            Log.e("probe: GNSS probe failed", t)
        }
        try {
            probeCellIdentity()
        } catch (t: Throwable) {
            Log.e("probe: CellIdentity probe failed", t)
        }
    }

    private fun probeSatellites(): List<Satellite> = listOf(
        Satellite(svid = 7, constellation = 1, cn0DbHz = 30.5f, usedInFix = true, elevationDeg = 45f, azimuthDeg = 90f),
    )

    private fun gnssSane(status: Any?, satellites: List<Satellite>): Boolean {
        if (status == null || satellites.isEmpty()) return false
        val check = readbackStatus(status) ?: return false
        val usedIndex = satellites.indexOfFirst { it.usedInFix }
        return check.first == satellites.size && check.second > 0f && usedInFixAt(status, usedIndex)
    }

    private fun describeGnss(status: Any?, satellites: List<Satellite>): String {
        if (status == null) return "null"
        val check = readbackStatus(status) ?: return "unreadable"
        val usedIndex = satellites.indexOfFirst { it.usedInFix }
        val svid = Refl.invoke(Refl.methodOf(status.javaClass, "getSvid", 1), status, 0)
        return "count=" + check.first + "/" + satellites.size + " svid=" + svid + "/" + satellites[0].svid +
            " cn0=" + check.second + "/" + satellites[0].cn0DbHz + " used=" + usedInFixAt(status, usedIndex) + "/true"
    }

    private fun probeGnss() {
        val sats = probeSatellites()
        resolveGnssClasses()
        val viaBuilder = buildGnssViaBuilder(sats)
        Log.i("probe GnssStatus Builder: " + describeGnss(viaBuilder, sats))
        if (gnssSane(viaBuilder, sats)) {
            gnssStrategy = STRATEGY_BUILDER
            Log.i("probe GnssStatus -> USING Builder")
            return
        }
        val viaCtor = buildGnssViaCtor(sats)
        Log.i("probe GnssStatus ctor: " + describeGnss(viaCtor, sats))
        if (gnssSane(viaCtor, sats)) {
            gnssStrategy = STRATEGY_CTOR
            Log.i("probe GnssStatus -> USING ctor")
            return
        }
        val viaAllocate = gnssStatusViaAllocate(sats)
        Log.i("probe GnssStatus allocate: " + describeGnss(viaAllocate, sats))
        if (gnssSane(viaAllocate, sats)) {
            gnssStrategy = STRATEGY_ALLOCATE
            Log.i("probe GnssStatus -> USING allocate")
            return
        }
        gnssStrategy = STRATEGY_NONE
        Log.w("probe GnssStatus -> NO strategy round-trips; construction disabled (in-place mutation still applies)")
    }

    private fun resolveGnssClasses() {
        if (gnssResolved) return
        gnssResolved = true
        gnssBuilder = Refl.findClassAnywhere("android.location.GnssStatus\$Builder")
        val gnssCls = Refl.findClassAnywhere("android.location.GnssStatus")
        gnssCtor = Refl.constructorsWhere(gnssCls) { c ->
            val p = c.parameterTypes
            p.size == 7 && p[0] == IntArray::class.java && p[1] == FloatArray::class.java &&
                p[5] == BooleanArray::class.java && p[6] == IntArray::class.java
        }.firstOrNull()
        val shapes = Refl.constructorsWhere(gnssCls) { true }.map { ctor ->
            ctor.parameterTypes.joinToString(",") { Refl.simpleName(it) }
        }
        Log.i("Fakes: GnssStatus builder=" + (gnssBuilder != null) + " ctor=" + (gnssCtor != null) + " shapes=" + shapes)
    }

    private fun buildGnssViaBuilder(satellites: List<Satellite>): Any? {
        val builderClass = gnssBuilder ?: return null
        val builder = newBuilderInstance(builderClass) ?: return null
        val missing = ArrayList<String>(2)
        fun setter(name: String, value: Any) {
            if (!callSetter(builder, name, value)) missing.add(name)
        }
        setter("setSvid", satellites.map { it.svid }.toIntArray())
        setter("setCn0DbHz", satellites.map { it.cn0DbHz }.toFloatArray())
        setter("setElevationDegrees", satellites.map { it.elevationDeg }.toFloatArray())
        setter("setAzimuthDegrees", satellites.map { it.azimuthDeg }.toFloatArray())
        setter("setUsedInFix", satellites.map { it.usedInFix }.toBooleanArray())
        setter("setConstellationType", satellites.map { it.constellation }.toIntArray())
        setter("setCarrierFrequencyHz", satellites.map { 0f }.toFloatArray())
        if (missing.isNotEmpty()) Log.once("fakes.gnss.setters", "GnssStatus.Builder missing setters: " + missing.joinToString())
        return Refl.invoke(Refl.methodOf(builderClass, "build", 0), builder)
    }

    private fun buildGnssViaCtor(satellites: List<Satellite>): Any? {
        val ctor = gnssCtor ?: return null
        return try {
            ctor.isAccessible = true
            ctor.newInstance(
                satellites.map { it.svid }.toIntArray(),
                satellites.map { it.cn0DbHz }.toFloatArray(),
                satellites.map { it.elevationDeg }.toFloatArray(),
                satellites.map { it.azimuthDeg }.toFloatArray(),
                satellites.map { 0f }.toFloatArray(),
                satellites.map { it.usedInFix }.toBooleanArray(),
                satellites.map { it.constellation }.toIntArray(),
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** Builds an `android.location.GnssStatus` with the strategy that round-tripped at install. */
    fun gnssStatus(satellites: List<Satellite>): Any? {
        if (satellites.isEmpty()) return null
        probeOnce()
        val built = when (gnssStrategy) {
            STRATEGY_BUILDER -> buildGnssViaBuilder(satellites)
            STRATEGY_CTOR -> buildGnssViaCtor(satellites)
            STRATEGY_ALLOCATE -> gnssStatusViaAllocate(satellites)
            else -> null
        }
        if (gnssSane(built, satellites)) return built
        Log.once(
            "fakes.gnss.drop",
            "dropping GnssStatus (round-trip probe failed: " + describeGnss(built, satellites) + ")",
        )
        return null
    }

    /** `sun.misc.Unsafe` handle — lets us allocate hidden system classes without a public ctor. */
    private val unsafe: Any? by lazy {
        try {
            val cls = Class.forName("sun.misc.Unsafe")
            val field = cls.getDeclaredField("theUnsafe").apply { isAccessible = true }
            field.get(null)
        } catch (t: Throwable) {
            Log.once("fakes.unsafe", "sun.misc.Unsafe unavailable: ${t.javaClass.simpleName}")
            null
        }
    }

    /** Allocates an instance without calling any constructor (hidden-API friendly). */
    fun allocate(cls: Class<*>?): Any? {
        if (cls == null) return null
        val handle = unsafe ?: return null
        return try {
            val m = handle.javaClass.getMethod("allocateInstance", Class::class.java)
            m.invoke(handle, cls)
        } catch (t: Throwable) {
            Log.once("fakes.allocate." + cls.name, "allocateInstance(${cls.name}) failed: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun newBuilderInstance(builderClass: Class<*>): Any? {
        try {
            val ctor = builderClass.getDeclaredConstructor().also { it.isAccessible = true }
            ctor.newInstance()?.let { return it }
        } catch (t: Throwable) {
            Log.once("fakes.gnss.builderctor", "GnssStatus\$Builder() ctor failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        return allocate(builderClass)
    }
    private fun callSetter(target: Any, name: String, value: Any): Boolean {
        val m = Refl.methodOf(target.javaClass, name, 1) ?: return false
        return Refl.invoke(m, target, value) != null
    }

    /** Reads back (count, cn0[0], usedInFix[0]) from a GnssStatus — our construction sanity check. */
    fun readbackStatus(status: Any?): Triple<Int, Float, Boolean>? {
        if (status == null) return null
        val cls = status.javaClass
        val count = (Refl.invoke(Refl.methodOf(cls, "getSatelliteCount", 0), status) as? Int) ?: return null
        val cn0 = (Refl.invoke(Refl.methodOf(cls, "getCn0DbHz", 1), status, 0) as? Float) ?: 0f
        // AOSP calls it `usedInFix(int)`; some builds also expose a getter-style name.
        val usedMethod = Refl.methodOf(cls, "usedInFix", 1) ?: Refl.methodOf(cls, "getUsedInFix", 1)
        val used = (Refl.invoke(usedMethod, status, 0) as? Boolean) ?: false
        return Triple(count, cn0, used)
    }

    /**
     * Mutates an *existing* (framework-built) GnssStatus in place instead of constructing a new one.
     * This keeps the ROM own representation (and therefore parcelling to apps) intact and is the
     * most compatible way to fake a sky view.
     */
    fun mutateStatus(status: Any?, satellites: List<Satellite>): Boolean {
        if (status == null || satellites.isEmpty()) return false
        val cls = status.javaClass
        val count = (Refl.invoke(Refl.methodOf(cls, "getSatelliteCount", 0), status) as? Int) ?: return false
        if (count <= 0) return false
        val n = kotlin.math.min(count, satellites.size)
        val svidFlags = Refl.get(status, "mSvidWithFlags") as? IntArray ?: return false
        val cn0 = Refl.get(status, "mCn0DbHz") as? FloatArray
        val elev = Refl.get(status, "mElevationDegrees") as? FloatArray
        val azim = Refl.get(status, "mAzimuthDegrees") as? FloatArray
        val usedIndex = satellites.indexOfFirst { it.usedInFix }
        if (usedIndex < 0 || usedIndex >= n) return false
        writeCn0(status, n, satellites)
        // The PRN/flags encoding of mSvidWithFlags differs between builds, and a wrong guess makes
        // usedInFix() report false forever. So: write, read back, and try the next encoding.
        for (variant in 0..2) {
            for (i in 0 until n) {
                val sat = satellites[i]
                var flags = 0x01 or 0x02
                if (sat.usedInFix) flags = flags or 0x04
                if (i < svidFlags.size) {
                    svidFlags[i] = when (variant) {
                        0 -> (sat.svid shl 8) or flags
                        1 -> sat.svid or (if (sat.usedInFix) 0x10000 else 0)
                        else -> sat.svid or flags
                    }
                }
                cn0?.let { if (i < it.size) it[i] = sat.cn0DbHz }
                elev?.let { if (i < it.size) it[i] = sat.elevationDeg }
                azim?.let { if (i < it.size) it[i] = sat.azimuthDeg }
            }
            if (usedInFixAt(status, usedIndex)) {

                val usedCount = (0 until n).count { usedInFixAt(status, it) }
                Log.rate(
                    "gnss.usedreadback",
                    30_000L,
                    "gnss usedInFix readback=" + usedCount + " after variant " + variant +
                        " (expected=" + satellites.count { it.usedInFix } + ")",
                )
                Log.once("fakes.gnss.mutate.variant", "SvStatus mutated with PRN/flags encoding variant " + variant)
                return true
            }
        }
        Log.once("fakes.gnss.mutate.failed", "SvStatus mutation could not make usedInFix readable")
        return false
    }

    /**
     * Writes the CN0 values with the same read-back search used for the PRN/flags encoding: the
     * field the ROM reads from is not necessarily `mCn0DbHz` (`mBasebandCn0DbHz`, `mCn0DbHzs`, ...),
     * and writing the wrong one leaves `getCn0DbHz()` at 0.
     */
    private fun writeCn0(status: Any, n: Int, satellites: List<Satellite>): Boolean {
        val cls = status.javaClass
        val getter = Refl.methodOf(cls, "getCn0DbHz", 1)
            ?: Refl.methodOf(cls, "getCn0DbHzs", 1)
            ?: return false
        val candidates = ArrayList<java.lang.reflect.Field>(4)
        var cursor: Class<*>? = cls
        while (cursor != null && cursor != Any::class.java) {
            for (field in cursor.declaredFields) {
                if (field.type != FloatArray::class.java) continue
                field.isAccessible = true
                val array = Refl.get(status, field) as? FloatArray ?: continue
                if (array.size >= n) candidates.add(field)
            }
            cursor = cursor.superclass
        }
        for ((index, field) in candidates.withIndex()) {
            val array = Refl.get(status, field) as? FloatArray ?: continue
            for (i in 0 until n) array[i] = satellites[i].cn0DbHz
            val readback = (Refl.invoke(getter, status, 0) as? Float) ?: 0f
            if (readback > 0f) {
                Log.once(
                    "fakes.gnss.cn0",
                    "cn0 encoding variant " + index + " field=" + field.name + " readback=" + readback,
                )
                return true
            }
        }
        Log.once(
            "fakes.gnss.cn0.miss",
            "no CN0 array read back (candidates=" + candidates.joinToString { it.name } + ")",
        )
        return false
    }

    /** Reads `usedInFix(i)` / `getUsedInFix(i)` from a GnssStatus. */
    private fun usedInFixAt(status: Any?, index: Int): Boolean {
        if (status == null || index < 0) return false
        val method = Refl.methodOf(status.javaClass, "usedInFix", 1)
            ?: Refl.methodOf(status.javaClass, "getUsedInFix", 1)
            ?: Refl.methodOf(status.javaClass, "getFlags", 1)
            ?: return false
        return (Refl.invoke(method, status, index) as? Boolean) ?: false
    }

    // ------------------------------------------------------------------ ScanResult

    /** Synthesized `ScanResult` list (may be empty when the platform refuses construction). */
    fun scanResults(accessPoints: List<SimulatedAp>): List<Any> {
        if (accessPoints.isEmpty()) return emptyList()
        val cls = Refl.findClassAnywhere("android.net.wifi.ScanResult") ?: return emptyList()
        val out = ArrayList<Any>(accessPoints.size)
        for (ap in accessPoints) {
            val result = buildScanResult(cls, ap) ?: continue
            out.add(result)
        }
        if (out.isEmpty()) Log.once("fakes.scan", "ScanResult construction failed for all APs")
        return out
    }

    private fun buildScanResult(cls: Class<*>, ap: SimulatedAp): Any? {
        val instance = try {
            val ctor = cls.getDeclaredConstructor().also { it.isAccessible = true }
            ctor.newInstance()
        } catch (_: Throwable) {
            allocate(cls)
        } ?: return null
        val nowMs = System.currentTimeMillis()
        // Every field platform code may dereference must be non-null: `WifiCloudSync` in
        // system_server NPEs on a null SSID/capabilities/wifiSsid and takes the whole system down.
        Refl.setField(instance, "SSID", ap.ssid)
        Refl.setField(instance, "BSSID", ap.bssid)
        Refl.setField(instance, "capabilities", CAPABILITIES)
        Refl.setField(instance, "level", ap.level)
        Refl.setField(instance, "frequency", ap.frequencyMhz)
        Refl.setField(instance, "timestamp", nowMs * 1000L)
        Refl.setField(instance, "seen", nowMs)
        Refl.setField(instance, "channelWidth", 0)
        Refl.setField(instance, "centerFreq0", ap.frequencyMhz)
        Refl.setField(instance, "centerFreq1", 0)
        Refl.setField(instance, "wifiStandard", 0)
        wifiSsid(ap.ssid)?.let { Refl.setField(instance, "wifiSsid", it) }
        if (!verifyScanResult(instance, ap)) {
            Log.once("fakes.scanitem.incomplete", "dropping an incompletely populated ScanResult")
            return null
        }
        return instance
    }

    /** Read-back verification: a partially populated ScanResult is never shipped. */
    private fun verifyScanResult(instance: Any, ap: SimulatedAp): Boolean {
        try {
            val ssid = Refl.get(instance, "SSID") as? String ?: return false
            val bssid = Refl.get(instance, "BSSID") as? String ?: return false
            val capabilities = Refl.get(instance, "capabilities") as? String ?: return false
            val level = Refl.get(instance, "level") as? Int ?: return false
            val frequency = Refl.get(instance, "frequency") as? Int ?: return false
            if (ssid != ap.ssid || bssid != ap.bssid) return false
            if (capabilities.isEmpty() || level >= 0 || frequency <= 0) return false
            val ssidField = Refl.fieldOf(instance.javaClass, "wifiSsid")
            if (ssidField != null && Refl.get(instance, ssidField) == null) return false
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    /** Non-null capabilities: platform code parses these strings. */
    private const val CAPABILITIES = "[WPA2-PSK-CCMP][ESS]"

    private fun wifiSsid(ssid: String): Any? {
        val cls = Refl.findClassAnywhere("android.net.wifi.WifiSsid") ?: return null
        // Try every construction shape this platform may have (they differ per API level).
        val attempts: List<() -> Any?> = listOf(
            { Refl.invokeStatic(Refl.methodOf(cls, "createFromAsciiEncoded", 1), ssid) },
            { Refl.invokeStatic(Refl.methodOf(cls, "createFromByteArray", 1), ssid.toByteArray()) },
            { Refl.invokeStatic(Refl.methodOf(cls, "fromBytes", 1), ssid.toByteArray()) },
            { Refl.invokeStatic(Refl.methodOf(cls, "fromUtf8Text", 1), ssid) },
        )
        for ((index, attempt) in attempts.withIndex()) {
            val built = try {
                attempt()
            } catch (_: Throwable) {
                null
            }
            if (built != null) {
                Log.once("fakes.wifissid.ok", "WifiSsid built with construction variant ${index + 1}")
                return built
            }
        }
        Log.once("fakes.wifissid", "WifiSsid could not be constructed; ScanResults will be dropped")
        return null
    }

    // ------------------------------------------------------------------ CellInfo

    private val cellInfoLteClass: Class<*>? by lazy { Refl.findClassAnywhere("android.telephony.CellInfoLte") }
    private val cellIdentityLteClass: Class<*>? by lazy { Refl.findClassAnywhere("android.telephony.CellIdentityLte") }
    private val cellSignalStrengthLteClass: Class<*>? by lazy { Refl.findClassAnywhere("android.telephony.CellSignalStrengthLte") }
    private val cellInfoGsmClass: Class<*>? by lazy { Refl.findClassAnywhere("android.telephony.CellInfoGsm") }
    private val cellIdentityGsmClass: Class<*>? by lazy { Refl.findClassAnywhere("android.telephony.CellIdentityGsm") }
    private val cellSignalStrengthGsmClass: Class<*>? by lazy { Refl.findClassAnywhere("android.telephony.CellSignalStrengthGsm") }
    private val cellInfoNrClass: Class<*>? by lazy { Refl.findClassAnywhere("android.telephony.CellInfoNr") }
    private var cellIdentityResolved = false

    /**
     * Synthesized cell list for `TelephonyManager.getAllCellInfo` / `PhoneInterfaceManager`.
     * Unsupported types are skipped; an empty list is returned when nothing could be built.
     */
    fun cellInfos(cells: List<SimulatedCell>): List<Any> {
        if (cells.isEmpty()) return emptyList()
        val out = ArrayList<Any>(cells.size)
        for (cell in cells) {
            val built = when (cell.type.uppercase()) {
                "LTE" -> lteCell(cell)
                "GSM" -> gsmCell(cell)
                else -> null // NR identities need a much newer constructor; skip rather than lie.
            }
            if (built != null && verifyCellInfo(built)) {
                out.add(built)
            } else if (built != null) {
                Log.once("fakes.cell.incomplete", "dropping an incompletely populated CellInfo")
            }
        }
        if (out.isEmpty()) Log.once("fakes.cell", "cell construction failed for all cells")
        return out
    }

    private fun lteCell(cell: SimulatedCell): Any? {
        val infoCls = cellInfoLteClass ?: return null
        val identityCls = cellIdentityLteClass ?: return null
        val identity = buildIdentity(
            identityCls,
            ints = intArrayOf(
                (cell.mcc.toIntOrNull() ?: 460).coerceIn(1, 999),
                (cell.mnc.toIntOrNull() ?: 0).coerceIn(0, 999),
                cell.cid.coerceIn(1L, 268_435_455L).toInt(),
                cell.pci.coerceIn(0, 503),
                cell.lac.coerceIn(1, 65_535),
            ),
            strings = arrayOf(cell.mcc, cell.mnc, cell.cid.toString(), cell.pci.toString(), cell.lac.toString()),
        ) ?: return null
        val strength = strengthFor(cellSignalStrengthLteClass, cell) ?: return null
        val info = newNoArg(infoCls) ?: return null
        val ok = Refl.setField(info, "mCellIdentityLte", identity) || invokeSetter(info, "setCellIdentity", identity)
        val ok2 = Refl.setField(info, "mCellSignalStrengthLte", strength) || invokeSetter(info, "setCellSignalStrength", strength)
        if (!ok || !ok2) Log.once("fakes.lte", "CellInfoLte field assignment incomplete")
        markRegistered(info, cell.registered)
        return info
    }

    private fun gsmCell(cell: SimulatedCell): Any? {
        val infoCls = cellInfoGsmClass ?: return null
        val identityCls = cellIdentityGsmClass ?: return null
        val identity = buildIdentity(
            identityCls,
            ints = intArrayOf(
                (cell.mcc.toIntOrNull() ?: 460).coerceIn(1, 999),
                (cell.mnc.toIntOrNull() ?: 0).coerceIn(0, 999),
                cell.lac.coerceIn(0, 65_535),
                cell.cid.coerceIn(1L, 65_535L).toInt(),
                0,
            ),
            strings = arrayOf(cell.mcc, cell.mnc, cell.lac.toString(), cell.cid.toString()),
        ) ?: return null
        val strength = strengthFor(cellSignalStrengthGsmClass, cell) ?: return null
        val info = newNoArg(infoCls) ?: return null
        val ok = Refl.setField(info, "mCellIdentityGsm", identity) || invokeSetter(info, "setCellIdentity", identity)
        val ok2 = Refl.setField(info, "mCellSignalStrengthGsm", strength) || invokeSetter(info, "setCellSignalStrength", strength)
        if (!ok || !ok2) Log.once("fakes.gsm", "CellInfoGsm field assignment incomplete")
        markRegistered(info, cell.registered)
        return info
    }

    /**
     * Builds a CellIdentity* object. The constructor shape changed several times
     * (int based, then String based, then combined), so we try the all-int constructors first and
     * fall back to the all-String ones.
     */
    private fun buildIdentity(cls: Class<*>, ints: IntArray, strings: Array<String>): Any? {
        // Use the constructor shape that passed the round-trip probe (see probeCellIdentity).
        if (cellStrategy != STRATEGY_UNKNOWN) {
            if (cellStrategy == STRATEGY_NONE) return null
            val probed = cellIdentityCandidates(cls).getOrNull(cellStrategy) ?: return null
            val args = Array<Any>(probed.parameterTypes.size) { ints[it.coerceAtMost(ints.size - 1)] }
            return Refl.newInstance(probed, *args)
        }
        val candidates = Refl.constructorsWhere(cls) { c ->
            val p = c.parameterTypes
            p.isNotEmpty() && p.all { it == Int::class.javaPrimitiveType || it == String::class.java }
        }
        // Prefer int constructors with the most parameters we can satisfy.
        val intCtor = candidates.filter { c -> c.parameterTypes.all { it == Int::class.javaPrimitiveType } }
            .sortedByDescending { it.parameterTypes.size }
            .firstOrNull { it.parameterTypes.size <= ints.size }
        if (intCtor != null) {
            val args = Array<Any>(intCtor.parameterTypes.size) { ints[it] }
            Refl.newInstance(intCtor, *args)?.let {
                if (!cellIdentityResolved) {
                    cellIdentityResolved = true
                    Log.i("Fakes: CellIdentity via int ctor (${intCtor.parameterTypes.size} args)")
                }
                return it
            }
        }
        val strCtor = candidates.filter { c -> c.parameterTypes.all { it == String::class.java } }
            .sortedByDescending { it.parameterTypes.size }
            .firstOrNull { it.parameterTypes.size <= strings.size }
        if (strCtor != null) {
            val args = Array<Any>(strCtor.parameterTypes.size) { strings[it] }
            Refl.newInstance(strCtor, *args)?.let {
                if (!cellIdentityResolved) {
                    cellIdentityResolved = true
                    Log.i("Fakes: CellIdentity via String ctor (${strCtor.parameterTypes.size} args)")
                }
                return it
            }
        }
        if (!cellIdentityResolved) {
            cellIdentityResolved = true
            Log.i("Fakes: CellIdentity constructors unseen shapes: ${candidates.map { c -> c.parameterTypes.size }}")
        }
        return null
    }

    /**
     * Builds a `CellSignalStrength*` object whose `getLevel()`/`getDbm()` read back our values.
     *
     * A freshly allocated strength object reports `level=0 / dbm=Integer.MAX_VALUE` - the "never
     * initialised" signature - until the exact fields the getters read are written, and on vendor
     * builds those are not necessarily `mLevel`/`mRsrp`. So: write, read back, fall back to the
     * ROM own constructor, and return null (drop the cell) when nothing reads back.
     */
    private fun strengthFor(cls: Class<*>?, cell: SimulatedCell): Any? {
        cls ?: return null
        val instance = newNoArg(cls)
        if (instance != null) {
            Refl.setField(instance, "mLevel", cell.level)
            Refl.setField(instance, "mRsrp", cell.dbm)
            Refl.setField(instance, "mRssi", cell.dbm)
            Refl.setField(instance, "mSignalLevel", cell.level)
            if (strengthSane(instance, cell)) return instance
        }
        val ctors = Refl.constructorsWhere(cls) { c ->
            c.parameterTypes.isNotEmpty() && c.parameterTypes.all { it == Int::class.javaPrimitiveType }
        }.sortedByDescending { it.parameterTypes.size }
        for (ctor in ctors) {
            val args = Array<Any>(ctor.parameterTypes.size) { index ->
                if (index == 0) cell.dbm else cell.level
            }
            val built = Refl.newInstance(ctor, *args)
            if (strengthSane(built, cell)) {
                Log.once("fakes.strength.ctor", "CellSignalStrength via ctor(" + ctor.parameterTypes.size + " ints)")
                return built
            }
        }
        Log.once(
            "fakes.strength.miss",
            "no CellSignalStrength shape reads back (want level=" + cell.level + " dbm=" + cell.dbm + ")",
        )
        return null
    }

    private fun strengthSane(strength: Any?, cell: SimulatedCell): Boolean {
        if (strength == null) return false
        val cls = strength.javaClass
        val level = (Refl.invoke(Refl.methodOf(cls, "getLevel", 0), strength) as? Int) ?: return false
        val dbm = (Refl.invoke(Refl.methodOf(cls, "getDbm", 0), strength) as? Int) ?: 0
        return level == cell.level && dbm == cell.dbm
    }

    private fun newNoArg(cls: Class<*>?): Any? {
        cls ?: return null
        return try {
            val ctor = cls.getDeclaredConstructor().also { it.isAccessible = true }
            ctor.newInstance()
        } catch (_: Throwable) {
            null
        }
    }

    private fun applyStrength(target: Any, cell: SimulatedCell) {
        if (target == null) return
        Refl.setField(target, "mLevel", cell.level)
        Refl.setField(target, "mRsrp", cell.dbm)
        Refl.setField(target, "mRssi", cell.dbm)
    }

    private fun invokeSetter(target: Any, name: String, value: Any?): Boolean {
        val m = Refl.methodOf(target.javaClass, name, 1) ?: return false
        return try {
            Refl.invoke(m, target, value)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun markRegistered(info: Any, registered: Boolean) {
        val field = Refl.anyField(info.javaClass, "mRegistered", "mIsRegistered") ?: return
        try {
            field.set(info, registered)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ misc

    /** Public wrapper for [setFinal]: sets any instance field, including final ones. */
    fun setInstanceField(target: Any?, name: String, value: Any?): Boolean {
        if (target == null) return false
        return setFinal(target, name, value)
    }

    /** Sets a (possibly final) instance field; final fields need their access flag cleared first. */
    private fun setFinal(target: Any, name: String, value: Any?): Boolean {
        val field = Refl.fieldOf(target.javaClass, name) ?: return false
        return try {
            field.isAccessible = true
            field.set(target, value)
            true
        } catch (_: Throwable) {
            try {
                val flags = java.lang.reflect.Field::class.java.getDeclaredField("accessFlags")
                flags.isAccessible = true
                flags.setInt(field, field.modifiers and java.lang.reflect.Modifier.FINAL.inv())
                field.set(target, value)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * Last-resort GnssStatus construction: allocate the object and write its internal arrays.
     * This bypasses both the Builder and the (vendor specific) constructor shapes.
     */
    private fun gnssStatusViaAllocate(satellites: List<Satellite>): Any? {
        val cls = Refl.findClassAnywhere("android.location.GnssStatus") ?: return null
        val instance = allocate(cls) ?: return null
        val n = satellites.size
        val usedIndex = satellites.indexOfFirst { it.usedInFix }
        val cn0 = FloatArray(n) { satellites[it].cn0DbHz }
        val elev = FloatArray(n) { satellites[it].elevationDeg }
        val azim = FloatArray(n) { satellites[it].azimuthDeg }
        val freq = FloatArray(n) { 0f }
        val baseband = FloatArray(n) { satellites[it].cn0DbHz }
        val arraysOk = setFinal(instance, "mCn0DbHz", cn0) &&
            setFinal(instance, "mElevationDegrees", elev) &&
            setFinal(instance, "mAzimuthDegrees", azim)
        setFinal(instance, "mCarrierFrequencyHz", freq)
        setFinal(instance, "mBasebandCn0DbHz", baseband)
        if (!arraysOk) return null
        // CN0: same read-back search (the ROM may read a different float[] field).
        writeCn0(instance, n, satellites)
        // Same encoding search as mutateStatus: write, read back usedInFix, try the next variant.
        for (variant in 0..2) {
            val svidWithFlags = IntArray(n) { i ->
                val sat = satellites[i]
                var flags = 0x03
                if (sat.usedInFix) flags = flags or 0x04
                when (variant) {
                    0 -> (sat.svid shl 8) or flags
                    1 -> sat.svid or (if (sat.usedInFix) 0x10000 else 0)
                    else -> sat.svid or flags
                }
            }
            if (!setFinal(instance, "mSvidWithFlags", svidWithFlags)) return null
            val check = readbackStatus(instance)
            val countOk = check != null && check.first == n && check.second > 0f
            val usedOk = usedIndex < 0 || usedInFixAt(instance, usedIndex)
            if (countOk && usedOk) {
                Log.once("fakes.gnss.alloc.variant", "GnssStatus allocate encoding variant " + variant)
                return instance
            }
        }
        Log.once("fakes.gnss.alloc.failed", "GnssStatus allocate could not produce a readable status")
        return null
    }

    /** Read-back verification for a synthesized CellInfo: identity must be present and sane. */
    fun verifyCellInfo(info: Any?): Boolean {
        if (info == null) return false
        return try {
            // Only a *concrete* identity is required (never a bare CellIdentity, and never a null).
            // Ranges are informative, not fatal: dropping a valid object because a vendor clamps a
            // value differently would silently fall back to the real cell.
            val identity = Refl.invoke(Refl.methodOf(info.javaClass, "getCellIdentity", 0), info)
            if (identity == null) {
                Log.once("fakes.cell.noid", "CellInfo without identity dropped")
                return false
            }
            if (identity.javaClass.name == "android.telephony.CellIdentity") {
                Log.once("fakes.cell.baseid", "CellInfo with a bare CellIdentity dropped")
                return false
            }
            // The getters must read back what we wrote: an identity that reads back empty is exactly
            // the "unreadable by the consumer" failure mode, so it is dropped instead of shipped.
            val cls = identity.javaClass
            val tac = (Refl.invoke(Refl.methodOf(cls, "getTac", 0), identity) as? Int)
                ?: (Refl.invoke(Refl.methodOf(cls, "getLac", 0), identity) as? Int)
                ?: 0
            val ci = (Refl.invoke(Refl.methodOf(cls, "getCi", 0), identity) as? Int) ?: 0
            if (tac <= 0 || ci <= 0) {
                Log.once(
                    "fakes.cell.empty",
                    "dropping CellInfo: identity reads back empty (tac=" + tac + " ci=" + ci + ")",
                )
                return false
            }
            true
        } catch (t: Throwable) {
            Log.once("fakes.cell.verify", "CellInfo verification threw " + t.javaClass.simpleName)
            false
        }
    }

    // ---- cell identity strategy -------------------------------------------------------

    @Volatile
    private var cellStrategy = STRATEGY_UNKNOWN

    private fun cellIdentityCandidates(cls: Class<*>): List<Constructor<*>> =
        Refl.constructorsWhere(cls) { c ->
            c.parameterTypes.isNotEmpty() && c.parameterTypes.all { it == Int::class.javaPrimitiveType }
        }.sortedByDescending { it.parameterTypes.size }

    private fun describeCellIdentity(identity: Any?): String {
        if (identity == null) return "null"
        val cls = identity.javaClass
        val mcc = Refl.invoke(Refl.methodOf(cls, "getMccString", 0), identity)
            ?: Refl.invoke(Refl.methodOf(cls, "getMcc", 0), identity)
        val tac = Refl.invoke(Refl.methodOf(cls, "getTac", 0), identity)
        val ci = Refl.invoke(Refl.methodOf(cls, "getCi", 0), identity)
        return "mcc=" + mcc + " tac=" + tac + " ci=" + ci
    }

    /** Human-readable identity of a synthesized CellInfo - evidence for the acceptance log. */
    fun describeCellInfo(info: Any?): String {
        if (info == null) return "null"
        return try {
            describeCellIdentity(Refl.invoke(Refl.methodOf(info.javaClass, "getCellIdentity", 0), info))
        } catch (_: Throwable) {
            "unreadable"
        }
    }

    private fun cellIdentitySane(identity: Any?): Boolean {
        if (identity == null) return false
        val cls = identity.javaClass
        val tac = (Refl.invoke(Refl.methodOf(cls, "getTac", 0), identity) as? Int) ?: 0
        val ci = (Refl.invoke(Refl.methodOf(cls, "getCi", 0), identity) as? Int) ?: 0
        return tac == 22538 && ci == 48356046
    }

    /** Round-trip probe for CellIdentityLte: the ctor shape that reads back is the one we use. */
    private fun probeCellIdentity() {
        val cls = Refl.findClassAnywhere("android.telephony.CellIdentityLte")
        if (cls == null) {
            Log.hook(false, "probe CellIdentityLte", "class not found")
            return
        }
        val probe = intArrayOf(460, 0, 48356046, 302, 22538)
        var index = 0
        for (ctor in cellIdentityCandidates(cls)) {
            val args = Array<Any>(ctor.parameterTypes.size) { probe[it.coerceAtMost(probe.size - 1)] }
            val built = Refl.newInstance(ctor, *args)
            Log.i(
                "probe CellIdentityLte ctor#" + index + "(" + ctor.parameterTypes.size + " ints): " +
                    describeCellIdentity(built),
            )
            if (cellIdentitySane(built)) {
                cellStrategy = index
                Log.i("probe CellIdentityLte -> USING ctor#" + index)
                return
            }
            index++
        }
        cellStrategy = STRATEGY_NONE
        Log.w("probe CellIdentityLte -> NO ctor round-trips (cells will be suppressed rather than wrong)")
    }

    fun platformSdk(): Int = Build.VERSION.SDK_INT

    /** Plausible number of satellites for a synthesized fix. */
    fun usedInFixCount(satellites: List<Satellite>): Int = satellites.count { it.usedInFix }
}

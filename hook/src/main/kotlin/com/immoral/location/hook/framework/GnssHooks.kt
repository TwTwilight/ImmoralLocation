package com.immoral.location.hook.framework

import android.location.Location
import com.immoral.location.core.engine.GnssDelivery
import com.immoral.location.core.engine.GnssTruthGate
import com.immoral.location.core.model.Satellite
import com.immoral.location.hook.EngineRuntime
import com.immoral.location.hook.Fakes
import com.immoral.location.hook.Log
import com.immoral.location.hook.Refl
import de.robv.android.xposed.XC_MethodHook
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Layer A — GNSS signal simulation (GPS 信号模拟) inside `system_server`.
 *
 * GNSS status and NMEA are delivered by `LocationManagerService` to per-app transports
 * ("IGnssStatusListener"/"IGnssNmeaListener"), which we capture at registration time and then feed
 * with synthesized data once per second. Real GNSS status reports are replaced on the way in so a
 * real sky view cannot contradict the simulated one.
 */
object GnssHooks {

    private const val LMS = "com.android.server.location.LocationManagerService"
    private const val GNSS_STATUS_PROVIDER = "com.android.server.location.gnss.GnssStatusProvider"
    private const val GNSS_LOCATION_PROVIDER = "com.android.server.location.gnss.GnssLocationProvider"
    private const val GNSS_STATUS_NAME = "android.location.GnssStatus"

    /**
     * Additional holders of `registerGnssStatusCallback` / `registerGnssNmeaCallback`, tried after
     * the outer class and its `LocalService`-ish inner classes (see [registrationHolders]).
     *
     * `LocationManagerService$LocalService` is the Android 12+ home of those methods; the vendor
     * variants exist because HyperOS subclasses the AOSP services (the same lesson `WifiHooks`
     * learned with `MiuiWifiService`). Every name that does not exist is simply skipped.
     */
    private val EXTRA_REGISTRATION_HOLDERS = arrayOf(
        "com.android.server.location.LocationManagerService\$LocalService",
        "com.android.server.location.MiuiLocationManagerService",
        "com.android.server.location.MiuiLocationManagerService\$LocalService",
    )

    private val installed = AtomicBoolean(false)

    private val statusListeners = CopyOnWriteArrayList<WeakReference<Any>>()
    private val nmeaListeners = CopyOnWriteArrayList<WeakReference<Any>>()

    /**
     * `true` only while **this module** is delivering its own synthesized status/NMEA.
     *
     * Why this exists: [pushStatus] and [pushNmea] reach the app by invoking the very callbacks
     * that [blockRealGnssDelivery] cancels (`IGnssStatusListener$Stub$Proxy#onSvStatusChanged`,
     * `IGnssNmeaListener$Stub$Proxy#onNmeaReceived`). A hooked method is hooked for reflective
     * invocation too, so the truth gate cancelled our own synthetic stream and an app saw no GNSS
     * at all while `gpsSignalSimulation = true` (`9-gnss`: `no gnss line, samples=0`). The marker
     * makes "who is delivering" explicit instead of relying on the (wrong) assumption that our
     * push bypasses the hook. It goes through [withSyntheticDelivery], which removes it in a
     * `finally`, so a nested real delivery inside our own push cannot inherit the mark.
     */
    private val syntheticDelivery = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    /** Instrumentation for the acceptance run: our deliveries that the gate let through. */
    private val syntheticPasses = AtomicInteger(0)

    /** Real deliveries the gate withheld (should keep rising while the engine runs). */
    private val withheldDeliveries = AtomicInteger(0)

    @Volatile
    private var lastStatusPushMs: Long = 0L

    @Volatile
    private var lastNmeaMs: Long = 0L

    /** Last framework-built GnssStatus we mutated successfully: guaranteed readable, so it can
     *  be replayed to listeners that register later instead of constructing a new object. */
    @Volatile
    private var lastMutatedStatus: Any? = null

    private var resolved = 0
    private var missed = 0

    fun install(loader: ClassLoader?) {
        if (!installed.compareAndSet(false, true)) return
        EngineRuntime.addTickListener { snap -> push(snap) }
        try {
            hookRegistrations(loader)
        } catch (t: Throwable) {
            Log.e("GnssHooks: registration stage failed", t)
        }
        try {
            captureListenerProxies(loader)
        } catch (t: Throwable) {
            Log.e("GnssHooks: proxy capture stage failed", t)
        }
        try {
            hookReportPath(loader)
        } catch (t: Throwable) {
            Log.e("GnssHooks: report stage failed", t)
        }
        try {
            blockRealGnssDelivery(loader)
        } catch (t: Throwable) {
            Log.e("GnssHooks: delivery stage failed", t)
        }
        Log.summary("framework/gnss", resolved, missed)
    }

    // ------------------------------------------------- real GNSS data must not reach a simulated app

    /**
     * The per-app GNSS channels carry **raw device data**, not a location: `IGnssNmeaListener`
     * delivers the real `$GPGGA/$GPRMC` sentences, `IGnssMeasurementsListener` the raw measurements
     * and `IGnssStatusListener` the real sky view. A locator SDK that parses NMEA computes a position
     * of its own and never touches `LocationManagerService#getLastLocation*`, so replacing fixes on
     * the report path is not enough to fool it.
     *
     * Measured on 高德地图: every `Location` the framework handed it was our synthetic one (299 gps +
     * 1216 passive deliveries in 5 minutes, `dumpsys location` showed only `39.9087,116.3975`), it
     * stayed on the simulated point with WiFi **and** mobile data off, and it still drew the real
     * street-level position a few seconds into every run — the only remaining source was the GNSS
     * chip itself, reached through these callbacks.
     *
     * Policy while a simulation runs (framework layer, so it applies to every app without scoping
     * anything): real NMEA / status / measurements are **not delivered**. `gpsSignalSimulation=true`
     * still works because [push] marks its own calls with [syntheticDelivery] and
     * [GnssTruthGate] lets exactly those through — see [withSyntheticDelivery]; the gate and the
     * push therefore share one pure decision table (`core/.../engine/GnssTruthGate.kt`) instead of
     * two inline `if`s that could disagree.
     * Independent mode is left alone (a non-whitelisted app must keep the real data, and this delivery point does
     * not carry the receiving package).
     */
    private fun blockRealGnssDelivery(loader: ClassLoader?) {
        val classes = listOf(
            "android.location.IGnssNmeaListener\$Stub\$Proxy",
            "android.location.IGnssStatusListener\$Stub\$Proxy",
            "android.location.IGnssMeasurementsListener\$Stub\$Proxy",
            "android.location.IGnssNavigationMessageListener\$Stub\$Proxy",
        )
        for (name in classes) {
            val cls = findFrameworkClass(name, loader)
            if (cls == null) {
                Log.hook(false, name, "class not found")
                missed++
                continue
            }
            val methods = Refl.methodsWhere(cls) { m -> m.returnType == Void.TYPE && m.name.startsWith("on") }
            if (methods.isEmpty()) {
                Log.hook(false, "$name#on*", "no matching method")
                missed++
                continue
            }
            for (m in methods) {
                val ok = Refl.hook(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val snap = EngineRuntime.activeSnap() ?: return
                            val synthetic = syntheticDelivery.get() == true
                            val verdict = GnssTruthGate.decide(
                                engineRunning = true,
                                gateEnabled = gnssTruthGateEnabled(),
                                independentMode = snap.config.independentMode,
                                syntheticPush = synthetic,
                            )
                            if (verdict == GnssDelivery.DELIVER) {
                                if (synthetic) {
                                    val passes = syntheticPasses.incrementAndGet()
                                    Log.rate(
                                        "gnss.gate.synthetic",
                                        15_000L,
                                        "gnss: synthetic " + m.name + " passed the truth gate (" +
                                            passes + " so far, " + withheldDeliveries.get() + " real withheld)",
                                    )
                                } else if (snap.config.independentMode) {
                                    Log.rate(
                                        "gnss.indep.passthrough",
                                        60_000L,
                                        "gnss: independent mode, real ${m.name} passes",
                                    )
                                }
                                return
                            }
                            // Cancels the original call (void return).
                            param.result = null
                            withheldDeliveries.incrementAndGet()
                            Log.rate(
                                "gnss.truth." + m.name,
                                15_000L,
                                "gnss: real " + m.name + " withheld from a listener " +
                                    "(gpsSignalSimulation=" + snap.config.gpsSignalSimulation + ")",
                            )
                        } catch (t: Throwable) {
                            Log.rate("gnss.truth.err", 30_000L, "gnss delivery gate failed: ${t.javaClass.simpleName}")
                        }
                    }
                }, name.substringAfterLast('.') + "#" + m.name)
                if (ok) resolved++ else missed++
            }
        }
    }

    private fun findFrameworkClass(name: String, loader: ClassLoader?): Class<*>? {        try {
            return Class.forName(name, false, loader ?: ClassLoader.getSystemClassLoader())
        } catch (_: Throwable) {
        }
        try {
            return Class.forName(name)
        } catch (_: Throwable) {
        }
        return Refl.findClassAnywhere(name, loader)
    }

    // ------------------------------------------------------------------ capture

    /**
     * Captures every per-app GNSS listener proxy the framework creates, **independently of where the
     * registration API lives**.
     *
     * This is the second, more robust capture path: each app's `IGnssStatusListener` /
     * `IGnssNmeaListener` reaches `system_server` as an `…$Stub$Proxy`, and the proxy is constructed
     * the moment the app registers. Hooking the constructor therefore sees the listener whatever the
     * ROM calls the registration method (`registerGnssStatusCallback` on `LocationManagerService`,
     * on its `LocalService`, on a vendor subclass, or through a `LocationProviderManager` helper).
     *
     * Both capture paths are kept: registration capture can deliver a status *before* the proxy is
     * alive, constructor capture survives a renamed API. Duplicates are ignored by [remember].
     */
    private fun captureListenerProxies(loader: ClassLoader?) {
        val targets = listOf(
            "android.location.IGnssStatusListener\$Stub\$Proxy" to statusListeners,
            "android.location.IGnssNmeaListener\$Stub\$Proxy" to nmeaListeners,
        )
        for ((className, list) in targets) {
            val cls = findFrameworkClass(className, loader)
            if (cls == null) {
                Log.hook(false, className, "class not found")
                missed++
                continue
            }
            val count = Refl.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val instance = param.thisObject ?: return
                        remember(list, instance)
                        Log.rate(
                            "gnss.proxy.capture",
                            15_000L,
                            "gnss: captured a listener proxy " + className.substringAfterLast('.') +
                                " (status=" + statusListeners.size + " nmea=" + nmeaListeners.size + ")",
                        )
                        // Feed a freshly created listener immediately, so the app does not have to
                        // wait a full tick (the framework sends its own, possibly empty, status at
                        // registration time).
                        val snap = EngineRuntime.activeSnap() ?: return
                        if (!snap.config.gpsSignalSimulation) return
                        if (list === statusListeners) {
                            (Fakes.gnssStatus(snap.satellites) ?: lastMutatedStatus)?.let { status ->
                                pushStatus(instance, status, snap.satellites)
                            }
                        } else if (snap.nmeaLines.isNotEmpty()) {
                            pushNmea(instance, snap.nmeaLines)
                        }
                    } catch (t: Throwable) {
                        Log.once("gnss.proxy.err", "proxy capture failed: ${t.javaClass.simpleName}")
                    }
                }
            }, className.substringAfterLast('.') + ".<init>")
            if (count > 0) resolved += count else missed++
        }
    }

    /**
     * Every class that can hold the app-facing GNSS registration entry points.
     *
     * `Refl.methodsWhere` walks superclasses only, so a registration method that the ROM moved into
     * an inner class (`LocationManagerService$LocalService` since Android 12, plus vendor classes)
     * is **not** found by looking at `LocationManagerService` alone. That failure mode is silent
     * from the product side: the gate withholds the real delivery, our push has zero captured
     * listeners, and an app sees no GNSS at all (`9-gnss`: `no gnss line, samples=0`). The inner
     * classes are therefore enumerated explicitly, and a total miss dumps the candidate inventory
     * so the next log names the real API instead of us guessing.
     */
    private fun registrationHolders(loader: ClassLoader?): List<Class<*>> {
        val out = ArrayList<Class<*>>(6)
        val outer = Refl.findClassAnywhere(LMS, loader)
        if (outer == null) {
            Log.hook(false, LMS, "class not found")
            missed++
        } else {
            out.add(outer)
            val inner = try {
                outer.declaredClasses
            } catch (_: Throwable) {
                emptyArray<Class<*>>()
            }
            for (c in inner) {
                val simple = c.name.substringAfterLast('$')
                if (simple.contains("LocalService") || simple.contains("Gnss") || simple.contains("Gps")) {
                    out.add(c)
                }
            }
        }
        for (name in EXTRA_REGISTRATION_HOLDERS) {
            val cls = Refl.findClassAnywhere(name, loader) ?: continue
            if (cls !in out) out.add(cls)
        }
        return out
    }

    private fun hookRegistrations(loader: ClassLoader?) {
        val holders = registrationHolders(loader)
        var statusHooks = 0
        var nmeaHooks = 0
        val inventory = StringBuilder()
        for (cls in holders) {
            val statusMethods = Refl.methodsWhere(cls) { m ->
                m.name == "registerGnssStatusCallback" && m.parameterTypes.isNotEmpty()
            }
            for (m in statusMethods) {
                val ok = Refl.hook(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val listener = Refl.listenerArgument(param.args, "Listener") ?: return
                            remember(statusListeners, listener)
                            // Deliver a status immediately: the framework sends its own (possibly empty)
                            // status at registration time, so the app should see ours right away.
                            EngineRuntime.activeSnap()?.let { snap ->
                                if (snap.config.gpsSignalSimulation) {
                                    // Prefer our own synthesized status (exactly the simulated sky
                                    // view); the framework-built one is a fallback for ROMs where
                                    // construction cannot round-trip — it still carries the tail of
                                    // the real constellation.
                                    (Fakes.gnssStatus(snap.satellites) ?: lastMutatedStatus)?.let { status ->
                                        pushStatus(listener, status, snap.satellites)
                                        Log.rate("gnss.push.immediate", 30_000L, "gnss: pushed a status to a freshly registered listener")
                                    }
                                }
                            }
                            Log.rate("gnssstatus.capture", 60_000L, "captured GNSS status listener ${listener.javaClass.name}")
                        } catch (t: Throwable) {
                            Log.once("gnss.capture.status", "capture failed: ${t.javaClass.simpleName}")
                        }
                    }
                }, cls.name.substringAfterLast('.') + "#registerGnssStatusCallback")
                if (ok) {
                    resolved++
                    statusHooks++
                } else {
                    missed++
                }
            }
            val nmeaMethods = Refl.methodsWhere(cls) { m ->
                (m.name == "registerGnssNmeaCallback" || m.name == "registerNmeaListener") &&
                    m.parameterTypes.isNotEmpty()
            }
            for (m in nmeaMethods) {
                val ok = Refl.hook(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val listener = Refl.listenerArgument(param.args, "Listener") ?: return
                            remember(nmeaListeners, listener)
                            Log.rate("gnssnmea.capture", 60_000L, "captured NMEA listener ${listener.javaClass.name}")
                        } catch (t: Throwable) {
                            Log.once("gnss.capture.nmea", "capture failed: ${t.javaClass.simpleName}")
                        }
                    }
                }, cls.name.substringAfterLast('.') + "#registerGnssNmeaCallback")
                if (ok) {
                    resolved++
                    nmeaHooks++
                } else {
                    missed++
                }
            }
            // Name the reality when nothing matched: every gnss/nmea-shaped method with its shape.
            if (statusMethods.isEmpty() && nmeaMethods.isEmpty()) {
                val shaped = try {
                    cls.declaredMethods.filter { m ->
                        val n = m.name.lowercase()
                        n.contains("gnss") || n.contains("nmea") || n.contains("gps")
                    }
                } catch (_: Throwable) {
                    emptyList()
                }
                inventory.append(cls.name.substringAfterLast('.')).append("=[")
                inventory.append(shaped.joinToString("; ") { Refl.signatureOf(it) })
                inventory.append("] ")
            }
        }
        if (statusHooks == 0) {
            Log.hook(false, "registerGnssStatusCallback", "no matching method in " + holders.size + " holder(s)")
            missed++
        }
        if (nmeaHooks == 0) {
            Log.hook(false, "registerGnssNmeaCallback", "no matching method in " + holders.size + " holder(s)")
            missed++
        }
        if (inventory.isNotEmpty()) {
            Log.i("gnss registration inventory: " + inventory.toString().take(900))
        }
        Log.i(
            "gnss capture: " + statusHooks + " status hook(s), " + nmeaHooks + " nmea hook(s) over " +
                holders.size + " holder(s)",
        )
    }

    private fun hookReportPath(loader: ClassLoader?) {
        for (className in arrayOf(GNSS_STATUS_PROVIDER, GNSS_LOCATION_PROVIDER)) {
            val cls = Refl.findClassAnywhere(className, loader) ?: run {
                Log.hook(false, className, "class not found")
                missed++
                null
            } ?: continue
            Log.hook(true, "class $className")
            val statusMethods = Refl.methodsWhere(cls) { m ->
                (m.name == "onReportSvStatus" || m.name == "handleReportSvStatus") && m.parameterTypes.size == 1
            }
            for (m in statusMethods) {
                val ok = Refl.hook(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        replaceStatusArgument(param)
                    }
                }, "$className#${m.name}")
                if (ok) resolved++ else missed++
            }
            if (statusMethods.isEmpty()) {
                Log.hook(false, "$className#onReportSvStatus", "no matching method")
                missed++
            }
            val locationMethods = Refl.methodsWhere(cls) { m ->
                (m.name == "onReportLocation" || m.name == "onReportLocations") &&
                    m.parameterTypes.any { it == Location::class.java || it == Array<Location>::class.java }
            }
            for (m in locationMethods) {
                val ok = Refl.hook(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val snap = EngineRuntime.activeSnap() ?: return
                            for (i in param.args.indices) {
                                when (val arg = param.args[i]) {
                                    is Location -> param.args[i] = Fakes.location("gps", snap)
                                    is Array<*> -> {
                                        val replaced = arrayOfNulls<Location>(arg.size)
                                        for (j in arg.indices) replaced[j] = Fakes.location("gps", snap)
                                        param.args[i] = replaced
                                    }
                                    else -> Unit
                                }
                            }
                        } catch (t: Throwable) {
                            Log.rate("gnss.loc", 15_000L, "gnss location replace failed: ${t.javaClass.simpleName}")
                        }
                    }
                }, "$className#${m.name}")
                if (ok) resolved++ else missed++
            }
        }
    }

    private fun replaceStatusArgument(param: XC_MethodHook.MethodHookParam) {
        try {
            val snap = EngineRuntime.activeSnap() ?: return
            if (!snap.config.gpsSignalSimulation) return
            val incoming = param.args.getOrNull(0)
            val realCount = Fakes.readbackStatus(incoming)?.first ?: 0
            // Preferred: mutate the framework-built status (vendor representation + parcelling intact).
            if (realCount > 0 && Fakes.mutateStatus(incoming, snap.satellites)) {
                // Remember the ROM own (now mutated) status: it is guaranteed readable, so it can be
                // replayed to listeners that register later instead of constructing a new one.
                lastMutatedStatus = incoming
                Log.rate(
                    "gnss.mutated",
                    30_000L,
                    "gnss: branch=mutate realCount=" + realCount + " sats=" + snap.satellites.size,
                )
                return
            }
            // No real sky view to mutate (indoors: 0 sats) - construct our own and hand it over.
            val status = Fakes.gnssStatus(snap.satellites)
            if (status == null) {
                Log.rate(
                    "gnss.construct.fail",
                    60_000L,
                    "gnss: branch=construct FAILED realCount=" + realCount + " sats=" + snap.satellites.size,
                )
                return
            }
            param.args[0] = status
            Log.rate(
                "gnss.constructed",
                30_000L,
                "gnss: branch=construct realCount=" + realCount + " sats=" + snap.satellites.size +
                    " readback=" + Fakes.readbackStatus(status),
            )
        } catch (t: Throwable) {
            Log.rate("gnss.status", 15_000L, "gnss status replace failed: " + t.javaClass.simpleName)
        }
    }

    private fun remember(list: CopyOnWriteArrayList<WeakReference<Any>>, listener: Any) {
        list.removeAll { it.get() == null }
        if (list.any { it.get() === listener }) return
        if (list.size >= 32) list.removeAt(0)
        list.add(WeakReference(listener))
    }

    // ------------------------------------------------------------------ push

    private fun push(snap: EngineRuntime.Snap) {
        if (!snap.running) return
        if (!snap.config.gpsSignalSimulation) return
        val status = statusListeners.let { list ->
            if (list.isEmpty()) null else (Fakes.gnssStatus(snap.satellites) ?: lastMutatedStatus)
        }
        val now = System.currentTimeMillis()
        if (snap.satellites.isEmpty()) {
            Log.rate("gnss.nosats", 60_000L, "gnss: 0 satellites synthesized (gpsSignalSimulation=${snap.config.gpsSignalSimulation})")
        }
        if (status == null) {
            Log.rate("gnss.nolisteners", 60_000L, "gnss: ${statusListeners.size} status listeners captured, sats=${snap.satellites.size}")
        }
        if (status != null) {
            Log.rate("gnss.push", 20_000L, "gnss: pushing ${snap.satellites.size} satellites to ${statusListeners.size} listeners")
        }
        // One seam line per ~20 s with everything an acceptance run needs to read the outcome
        // without guessing: how many listeners the capture found, how many of our deliveries the
        // gate let through and how much real chip data it withheld.
        Log.rate(
            "gnss.seam",
            20_000L,
            "gnss seam: sats=" + snap.satellites.size + " statusListeners=" + statusListeners.size +
                " nmeaListeners=" + nmeaListeners.size + " built=" + (status != null) +
                " syntheticPassed=" + syntheticPasses.get() + " realWithheld=" + withheldDeliveries.get(),
        )
        if (status != null && now - lastStatusPushMs >= 950L) {
            lastStatusPushMs = now
            for (ref in statusListeners) {
                val listener = ref.get() ?: continue
                pushStatus(listener, status, snap.satellites)
            }
        }
        if (snap.nmeaLines.isNotEmpty() && snap.nmeaBuiltAtMs != lastNmeaMs) {
            lastNmeaMs = snap.nmeaBuiltAtMs
            for (ref in nmeaListeners) {
                val listener = ref.get() ?: continue
                pushNmea(listener, snap.nmeaLines)
            }
        }
    }

    /**
     * Runs [block] with the "this is our own synthesized data" mark set, and clears it again in a
     * `finally` (also on the `Throwable` paths: a leaked mark would make the truth gate pass real
     * chip data on the same thread — the exact opposite of what the gate is for).
     */
    private inline fun withSyntheticDelivery(block: () -> Unit) {
        syntheticDelivery.set(true)
        try {
            block()
        } finally {
            syntheticDelivery.remove()
        }
    }

    private fun pushStatus(listener: Any, status: Any, satellites: List<Satellite>) {
        withSyntheticDelivery {
            try {
                val methods = Refl.methodsNamed(listener.javaClass, "onSvStatusChanged")
                for (m in methods) {
                    when (m.parameterTypes.size) {
                        1 -> {
                            Refl.invoke(m, listener, status)
                            return@withSyntheticDelivery
                        }
                        7 -> {
                            // Legacy AIDL shape (API 24..S): (count, prns, snrs, elevations, azimuths, flags, usedInFix)
                            val prns = satellites.map { it.svid or PRN_FLAG_HAS_ALMANAC }.toIntArray()
                            val snrs = satellites.map { it.cn0DbHz }.toFloatArray()
                            val elevations = satellites.map { it.elevationDeg }.toFloatArray()
                            val azimuths = satellites.map { it.azimuthDeg }.toFloatArray()
                            val usedInFix = satellites.map { if (it.usedInFix) 1 else 0 }.toIntArray()
                            Refl.invoke(m, listener, satellites.size, prns, snrs, elevations, azimuths, 0, usedInFix)
                            return@withSyntheticDelivery
                        }
                    }
                }
                Log.once("gnss.push.shape", "no usable onSvStatusChanged shape on ${listener.javaClass.name}")
            } catch (t: Throwable) {
                Log.once("gnss.push.err" + t.javaClass.simpleName, "gnss status push failed: ${t.message}")
            }
        }
    }

    private fun pushNmea(listener: Any, lines: List<String>) {
        withSyntheticDelivery {
            try {
                val method = Refl.methodsNamed(listener.javaClass, "onNmeaReceived")
                    .firstOrNull { it.parameterTypes.size == 2 } ?: return@withSyntheticDelivery
                val timestamp = SystemClockMillis()
                for (line in lines) {
                    Refl.invoke(method, listener, timestamp, line)
                }
            } catch (t: Throwable) {
                Log.once("gnss.push.nmea", "nmea push failed: ${t.javaClass.simpleName}")
            }
        }
    }

    /**
     * Acceptance control switch (deliberately runtime, not build-time): with
     * `persist.immoral.gnss_truth_gate=0` the real GNSS deliveries are let through again and nothing
     * else changes, so the two arms of the 高德 experiment can run on the **same boot, same APK, same
     * scope** — the only difference is this property. Default (absent) = gate on.
     */
    @Volatile
    private var gateEnabled = true

    @Volatile
    private var gateCheckedAt = 0L

    private fun gnssTruthGateEnabled(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - gateCheckedAt > 2_000L) {
            gateCheckedAt = now
            gateEnabled = try {
                val sp = Class.forName("android.os.SystemProperties")
                val get = sp.getMethod("getBoolean", String::class.java, java.lang.Boolean.TYPE)
                get.invoke(null, "persist.immoral.gnss_truth_gate", java.lang.Boolean.TRUE) as Boolean
            } catch (_: Throwable) {
                true
            }
        }
        return gateEnabled
    }

    private fun SystemClockMillis(): Long = System.currentTimeMillis()

    /** AOSP marks PRNs that have almanac data with 0x10000; harmless here. */
    private const val PRN_FLAG_HAS_ALMANAC = 0x10000
}

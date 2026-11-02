package com.immoral.location.hook.framework

import android.location.Location
import com.immoral.location.hook.EngineRuntime
import com.immoral.location.hook.Fakes
import com.immoral.location.hook.Log
import com.immoral.location.hook.Refl
import com.immoral.location.hook.Scope
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Layer A — the location choke point inside `system_server`.
 *
 * Two halves that work together:
 *
 * 1. **Replacement.** Every path that carries a fix towards an app is hooked and its
 *    `Location`/`LocationResult` argument replaced with a spoofed one:
 *    `AbstractLocationProvider.reportLocation`, `LocationProviderManager.onReportLocation` (and the
 *    per-registration lambda), plus the `getLastLocation*` family (return value replaced).
 *
 * 2. **Injection.** Hooking alone is not enough: a device indoors has no GPS fix, so there would be
 *    nothing to replace. [EngineRuntime] therefore drives this class once per tick, which calls
 *    `LocationProviderManager.onReportLocation(<our LocationResult>)` on every provider manager we
 *    captured. That makes the whole framework (and every listener registered by any app) receive
 *    our synthetic fixes at `config.updateIntervalMs`, mock flag cleared.
 *
 * All reflection; every hook body is wrapped in try/catch so a failure can never propagate into
 * system_server.
 */
object LocationHooks {

    private const val LPM = "com.android.server.location.provider.LocationProviderManager"
    private const val ALP = "com.android.server.location.provider.AbstractLocationProvider"
    private const val LMS = "com.android.server.location.LocationManagerService"
    private const val LR_NAME = "android.location.LocationResult"

    private val installed = AtomicBoolean(false)

    /** provider name -> LocationProviderManager instance */
    private val managers = ConcurrentHashMap<String, Any>()

    /** Fallback bucket for managers whose provider name we could not read. */
    private val unknownManagers = CopyOnWriteArrayList<Any>()

    /** Report method used for injection (`onReportLocation`/`reportLocation`), resolved once. */
    @Volatile
    private var injectMethod: Method? = null

    /** Pre-31 `LocationManagerService.reportLocation(Location, boolean)` instance. */
    @Volatile
    private var legacyService: Any? = null
    private var legacyReportMethod: Method? = null

    /** Reentrancy guard: our own injection must not be re-processed. */
    private val reentrant = ThreadLocal<Boolean>()

    private var resolved = 0

    @Volatile
    private var managerFieldsDumped = false
    private var missed = 0

    // ------------------------------------------------------------------ install

    fun install(loader: ClassLoader?) {
        if (!installed.compareAndSet(false, true)) return
        EngineRuntime.addTickListener { snap -> inject(snap) }
        try {
            hookProviderManager(loader)
        } catch (t: Throwable) {
            Log.e("LocationHooks: LocationProviderManager stage failed", t)
        }
        try {
            hookAbstractProvider(loader)
        } catch (t: Throwable) {
            Log.e("LocationHooks: AbstractLocationProvider stage failed", t)
        }
        try {
            hookLocationManagerService(loader)
        } catch (t: Throwable) {
            Log.e("LocationHooks: LocationManagerService stage failed", t)
        }
        Log.summary("framework/location", resolved, missed)
    }

    // ------------------------------------------------------------------ targets

    private fun hookProviderManager(loader: ClassLoader?) {
        val cls = Refl.findClassAnywhere(LPM, loader)
        if (cls == null) {
            Log.hook(false, LPM, "class not found")
            missed++
            return
        }
        Log.hook(true, "class $LPM")

        // Capture every provider manager instance (name read from its own fields).
        Refl.hookAllConstructors(cls, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    captureManager(param.thisObject)
                } catch (t: Throwable) {
                    Log.e("captureManager failed", t)
                }
            }
        }, "LocationProviderManager")

        // Report path -> replacement + capture (both).
        val reportMethods = Refl.methodsWhere(cls) { m ->
            (m.name == "onReportLocation" || m.name == "reportLocation") &&
                m.parameterTypes.isNotEmpty() &&
                (m.parameterTypes.any { it.name == LR_NAME } || m.parameterTypes.any { it == Location::class.java })
        }
        if (reportMethods.isEmpty()) {
            Log.hook(false, "LocationProviderManager#onReportLocation", "no matching method")
            missed++
        } else {
            for (m in reportMethods) {
                val ok = Refl.hook(m, reportReplaceHook(m.name), "LocationProviderManager#${m.name}")
                if (ok) resolved++ else missed++
                if (injectMethod == null && m.parameterTypes.size == 1 && m.parameterTypes[0].name == LR_NAME) {
                    injectMethod = m
                    Log.i("injection method = LocationProviderManager#${m.name}${Refl.signatureOf(m)}")
                }
            }
        }
        if (injectMethod == null) {
            Refl.methodOf(cls, "reportLocation", 1)?.let {
                injectMethod = it
                Log.i("injection method fallback = reportLocation${Refl.signatureOf(it)}")
            }
        }

        // Per-registration dispatch lambda — also carries the fix, and knows the registration.
        val lambdaMethods = Refl.methodsWhere(cls) { m ->
            m.name.contains("onReportLocation") && m.parameterTypes.size >= 2 && m.isSynthetic
        }
        for (m in lambdaMethods) {
            val ok = Refl.hook(m, registrationLambdaHook(), "LocationProviderManager#${m.name}(per-registration)")
            if (ok) resolved++ else missed++
        }

        // Per-listener delivery: the only boundary where the target package is known, which is
        // where independent mode (独立模拟) is enforced.
        hookPerRegistrationDelivery(cls)
        hookKnownRegistrationClasses(loader)

        // Last location family.
        val lastLocationMethods = Refl.methodsWhere(cls) { m ->
            m.returnType == Location::class.java && (
                m.name == "getLastLocation" || m.name == "getLastLocationUnsafe" ||
                    m.name == "getLastKnownLocation" || m.name == "getLastLocationSafe"
                )
        }
        for (m in lastLocationMethods) {
            val ok = Refl.hook(m, lastLocationHook("LPM#" + m.name), "LocationProviderManager#${m.name}")
            if (ok) resolved++ else missed++
        }
        if (lastLocationMethods.isEmpty()) {
            Log.hook(false, "LocationProviderManager#getLastLocation*", "no matching method")
            missed++
        }
    }

    private fun hookAbstractProvider(loader: ClassLoader?) {
        val cls = Refl.findClassAnywhere(ALP, loader)
        if (cls == null) {
            Log.hook(false, ALP, "class not found")
            missed++
            return
        }
        Log.hook(true, "class $ALP")
        val methods = Refl.methodsWhere(cls) { m ->
            m.name == "reportLocation" && m.parameterTypes.isNotEmpty()
        }
        for (m in methods) {
            val ok = Refl.hook(m, reportReplaceHook("ALP#reportLocation"), "AbstractLocationProvider#reportLocation")
            if (ok) resolved++ else missed++
        }
        if (methods.isEmpty()) {
            Log.hook(false, "AbstractLocationProvider#reportLocation", "no matching method")
            missed++
        }
    }

    private fun hookLocationManagerService(loader: ClassLoader?) {
        val cls = Refl.findClassAnywhere(LMS, loader)
        if (cls == null) {
            Log.hook(false, LMS, "class not found")
            missed++
            return
        }
        Log.hook(true, "class $LMS")

        // Pre-31 report path: LocationManagerService.reportLocation(Location, boolean)
        val reportMethods = Refl.methodsWhere(cls) { m ->
            m.name == "reportLocation" && m.parameterTypes.size >= 1 && m.parameterTypes[0] == Location::class.java
        }
        for (m in reportMethods) {
            val ok = Refl.hook(m, reportReplaceHook("LMS#reportLocation"), "LocationManagerService#reportLocation")
            if (ok) resolved++ else missed++
            if (legacyReportMethod == null && m.parameterTypes.size >= 1) {
                legacyReportMethod = m
                Refl.hookAllConstructors(cls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        legacyService = param.thisObject
                    }
                }, "LocationManagerService")
            }
        }

        // Last known location for the app-facing API (independent mode decides per caller uid).
        // Structural match, not a name list: measured 2026-11-02, a non-whitelisted verifier still read
        // the simulated point through `source=last:passive` while no `last:` line ever appeared in our
        // log - the name-list hook never fired. Now: ANY method on this class returning a Location is a
        // last-location getter. The signature cannot be used as a filter: measured 2026-11-02, an
        // app calling `getLastKnownLocation("gps")` still read the simulated point while our hook log
        // showed the per-package filter working, i.e. the overload that actually serves the call takes
        // neither a provider String nor any other shape we matched.
        val lastMethods = Refl.methodsWhere(cls) { m ->
            m.returnType == Location::class.java
        }
        for (m in lastMethods) {
            val ok = Refl.hook(m, lastLocationHook("LMS#" + m.name), "LocationManagerService#${m.name}")
            if (ok) resolved++ else missed++
        }
        if (lastMethods.isEmpty()) {
            Log.hook(false, "LocationManagerService#getLastLocation*", "no matching method")
            missed++
        }
        // LocalService (the binder-facing inner class) exposes its own getLastLocation family.
        try {
            for (inner in cls.declaredClasses) {
                val innerMethods = Refl.methodsWhere(inner) { m ->
                    m.returnType == Location::class.java
                }
                for (m in innerMethods) {
                    val ok = Refl.hook(
                        m,
                        lastLocationHook("LMS$" + inner.simpleName + "#" + m.name),
                        "LocationManagerService$" + inner.simpleName + "#" + m.name,
                    )
                    if (ok) resolved++ else missed++
                }
            }
        } catch (t: Throwable) {
            Log.once("lms.localservice", "LocalService scan failed: " + t.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------------ capture + inject

    private fun captureManager(instance: Any?) {
        if (!managerFieldsDumped) {
            managerFieldsDumped = true
            val dump = StringBuilder()
            var dumpCursor: Class<*>? = instance?.javaClass
            while (dumpCursor != null && dumpCursor != Any::class.java) {
                for (field in dumpCursor.declaredFields) {
                    dump.append(dumpCursor.simpleName).append(".").append(field.name)
                        .append(":").append(field.type.simpleName).append(", ")
                }
                dumpCursor = dumpCursor.superclass
            }
            Log.i("provider manager fields: " + dump)
        }
        if (instance == null) return
        // Only LocationProviderManager (or a subclass such as PassiveLocationProviderManager) can
        // answer onReportLocation; LocationManagerService instances must never enter this map.
        if (!instance.javaClass.name.contains("LocationProviderManager")) return
        val name = providerNameOf(instance)
        if (name == null) {
            if (unknownManagers.size < 8 && unknownManagers.none { it === instance }) unknownManagers.add(instance)
            return
        }
        if (managers.put(name, instance) == null) {
            Log.i("captured provider manager '$name' ($instance)")
        }
    }

    private fun providerNameOf(instance: Any): String? {
        val viaGetter = Refl.invoke(Refl.methodOf(instance.javaClass, "getName", 0), instance) as? String
        if (!viaGetter.isNullOrEmpty()) return viaGetter
        val viaField = Refl.getString(instance, "mName", "name", "mProviderName")
        if (!viaField.isNullOrEmpty()) return viaField
        val provider = Refl.get(instance, "mProvider", "mLocationProvider")
        if (provider != null) {
            val nested = Refl.getString(provider, "mName", "name")
            if (!nested.isNullOrEmpty()) return nested
        }
        return null
    }

    /**
     * Called once per engine tick: push our synthetic fix into every captured provider manager.
     * This is what makes spoofing work without a real GPS fix.
     */
    private fun inject(snap: EngineRuntime.Snap) {
        // Independent mode must still restore the provider cache when the engine is STOPPED -
        // otherwise a fix injected by an earlier normal-mode run keeps being served to
        // non-whitelisted readers (measured 2026-11-02: the outside read in a combined run
        // returned a previous check's simulated point, 475 m from the current one).
        if (!snap.running && !snap.config.independentMode) return
        if (reentrant.get() == true) return
        reentrant.set(true)
        try {
            // Before anything is replaced, remember what the platform itself last knew. Independent
            // mode can only *restore* a real cache if it has one, and indoors no real GNSS fix ever
            // arrives - so without this snapshot `publishRealToManager` has nothing to publish and a
            // previously injected point stays visible to every non-whitelisted reader (measured
            // 2026-11-02: the outside read in a combined run returned an earlier check's simulated
            // point, 475 m from the current one).
            if (realLast.isEmpty()) captureRealLastKnown()
            if (snap.config.independentMode) {
                // Restore FIRST, even when the engine is stopped, so the platform cache cannot
                // keep replaying our fabricated value to registrations we do not deliver to.
                publishRealToManager(snap)
                if (snap.running) injectPerWhitelistedRegistration(snap)
                return
            }
            val method = injectMethod
            if (method != null) {
                val targets = ArrayList<Pair<String, Any>>(managers.size + unknownManagers.size)
                for ((name, instance) in managers) targets.add(name to instance)
                for (instance in unknownManagers) targets.add("gps" to instance)
                for ((name, instance) in targets) {
                    try {
                        val location = Fakes.location(providerFor(name), snap)
                        val arg = when (method.parameterTypes[0].name) {
                            LR_NAME -> Fakes.locationResult(listOf(location))
                            "android.location.Location" -> location
                            else -> null
                        } ?: continue
                        method.invoke(instance, arg)
                    } catch (t: Throwable) {
                        Log.once("inject." + name + t.javaClass.simpleName, "inject into '$name' failed: ${t.message}")
                    }
                }
            }
            val legacy = legacyService
            val legacyMethod = legacyReportMethod
            if (legacy != null && legacyMethod != null) {
                try {
                    val location = Fakes.location("gps", snap)
                    val args = arrayOfNulls<Any>(legacyMethod.parameterTypes.size)
                    args[0] = location
                    for (i in 1 until args.size) args[i] = if (legacyMethod.parameterTypes[i] == Boolean::class.javaPrimitiveType) false else null
                    legacyMethod.invoke(legacy, *args)
                } catch (t: Throwable) {
                    Log.once("inject.legacy", "legacy inject failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.rate("inject", 10_000L, "injection tick failed: ${t.javaClass.simpleName}")
        } finally {
            reentrant.set(false)
        }
    }

    /**
     * Independent-mode delivery: enumerate the provider manager registrations and hand the fix only to
     * the whitelisted ones. Nothing is published to the manager, so the provider keeps the real fix as
     * its last-known value and no replay can leak ours.
     */
    private fun injectPerWhitelistedRegistration(snap: EngineRuntime.Snap) {
        var delivered = 0
        for ((name, instance) in managers) {
            val registrations = registrationsOf(instance)
            if (registrations == null) {
                Log.once("indep.noregistrations", "independent: cannot read the registration list of this provider manager")
                continue
            }
            for ((index, registration) in registrations.withIndex()) {
                try {
                    val packageName = packageOf(registration)
                    Log.rate(
                        "indep.reg." + index,
                        30_000L,
                        "registration[" + index + "] class=" + registration.javaClass.simpleName +
                            " pkg=" + (packageName ?: "UNRESOLVED"),
                    )
                    if (packageName == null || !Scope.isSpoofedPackage(packageName)) continue
                    val accept = Refl.methodOf(registration.javaClass, "acceptLocationChange", 1) ?: continue
                    val result = Fakes.locationResult(listOf(Fakes.location(providerFor(name), snap))) ?: continue
                    Refl.invoke(accept, registration, result)
                    delivered++
                } catch (_: Throwable) {
                }
            }
        }
        Log.rate(
            "indep.deliver",
            15_000L,
            "independent: delivered per-registration to " + delivered + " whitelisted listener(s)",
        )
    }

    /**
     * Pushes the real last-known fix (if we ever saw one) back into the provider managers, so the
     * manager stored value is never our fabricated one. Without this, a fix injected before
     * independent mode was switched on stays cached and is replayed to every new registration -
     * which is exactly how a non-whitelisted app still saw the fake while no drop line was emitted.
     */
    /** Bounded retries for the pre-replacement snapshot (one attempt per engine tick). */
    private var snapshotAttempts = 0

    @Volatile
    private var snapshotGaveUp = false

    /**
     * Read the platform's **own** last-known fix for every captured provider manager, before we
     * replace anything.
     *
     * Called from [inject] while the reentrancy guard is already held, so [lastLocationHook] and the
     * report-path hooks stay out of the way and the raw cached value is what comes back.
     *
     * A value that is already ours (`Fakes.isFabricated`) is never recorded: an earlier run may have
     * left a synthetic point in the cache, and storing that as "real" would be worse than storing
     * nothing at all. When nothing genuine is available the attempt is retried on later ticks
     * (bounded, logged once in each direction).
     */
    private fun captureRealLastKnown() {
        if (snapshotGaveUp) return
        var captured = 0
        var fabricated = 0
        for ((name, instance) in managers) {
            val value = try {
                readLastLocation(instance)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (Fakes.isFabricated(value)) {
                fabricated++
                continue
            }
            val provider = value.provider ?: providerFor(name)
            // Copy: the manager keeps a reference and refreshes elapsedRealtimeNanos on the original.
            realLast[providerFor(provider)] = Location(value)
            realLast[name] = Location(value)
            captured++
        }
        if (captured > 0) {
            Log.i(
                "snapshot: captured the platform's own last-known fix for " +
                    realLast.keys.sorted() +
                    if (fabricated > 0) " (skipped $fabricated cached value(s) that were ours)" else "",
            )
            return
        }
        snapshotAttempts++
        if (snapshotAttempts == 1) {
            Log.i(
                "snapshot: no genuine last-known fix available yet (fabricated=$fabricated, " +
                    "managers=${managers.size}) — retrying on later ticks",
            )
        }
        if (snapshotAttempts >= 10) {
            snapshotGaveUp = true
            Log.w(
                "snapshot: giving up after $snapshotAttempts attempts — independent mode cannot restore " +
                    "a real provider cache this session (indoors, no real fix ever arrives)",
            )
        }
    }

    /** Call the manager's own last-location getter; the held reentrancy guard keeps it unsubstituted. */
    private fun readLastLocation(instance: Any): Location? {
        val cls = instance.javaClass
        for (name in arrayOf("getLastLocationUnsafe", "getLastLocation", "getLastKnownLocation", "getLastLocationSafe")) {
            val getter = Refl.methodOf(cls, name, 0) ?: continue
            if (getter.returnType != Location::class.java) continue
            return Refl.invoke(getter, instance) as? Location
        }
        return null
    }

    private fun publishRealToManager(snap: EngineRuntime.Snap) {
        val method = injectMethod ?: return
        for ((name, instance) in managers) {
            val real = realLast[providerFor(name)] ?: realLast[name] ?: continue
            try {
                val realCopy = Location(real)
                realCopy.elapsedRealtimeNanos = SystemClock_elapsedRealtimeNanos()
                val arg: Any = when (method.parameterTypes[0].name) {
                    LR_NAME -> Fakes.locationResult(listOf(realCopy)) ?: continue
                    "android.location.Location" -> realCopy
                    else -> continue
                }
                method.invoke(instance, arg)
                Log.rate("indep.realpublish", 30_000L, "independent: provider last-known restored to the real fix on " + name)
            } catch (t: Throwable) {
                Log.once("indep.realpublish.err", "independent: real re-publish failed: " + t.javaClass.simpleName)
            }
        }
    }

    private fun SystemClock_elapsedRealtimeNanos(): Long = try {
        android.os.SystemClock.elapsedRealtimeNanos()
    } catch (_: Throwable) {
        0L
    }

    private fun registrationsOf(manager: Any): List<Any>? {
        val found = LinkedHashMap<Any, Any>()
        fun harvest(value: Any?, via: String) {
            try {
                when (value) {
                    null -> Unit
                    is Map<*, *> -> for (entry in value.values) {
                        if (entry != null && entry.javaClass.name.contains("Registration")) {
                            if (found.put(entry, entry) == null) {
                                Log.once("regs.via", "independent: registration list via " + via + " (map)")
                            }
                        }
                    }
                    is Collection<*> -> for (entry in value) {
                        if (entry != null && entry.javaClass.name.contains("Registration")) {
                            if (found.put(entry, entry) == null) {
                                Log.once("regs.via", "independent: registration list via " + via + " (collection)")
                            }
                        }
                    }
                    else -> Unit
                }
            } catch (_: Throwable) {
            }
        }
        var cursor: Class<*>? = manager.javaClass
        while (cursor != null && cursor != Any::class.java) {
            for (field in cursor.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                } catch (_: Throwable) {
                    continue
                }
                val value = try {
                    field.get(manager)
                } catch (_: Throwable) {
                    null
                } ?: continue
                val name = cursor.simpleName + "." + field.name
                if (value is Map<*, *> || value is Collection<*>) {
                    harvest(value, name)
                    continue
                }
                // One level deeper: RegistrationManager / ListenerMultiplexer hold the map.
                if (value.javaClass.name.contains("Registration") || value.javaClass.name.contains("Multiplexer")) {
                    var inner: Class<*>? = value.javaClass
                    while (inner != null && inner != Any::class.java) {
                        for (innerField in inner.declaredFields) {
                            if (java.lang.reflect.Modifier.isStatic(innerField.modifiers)) continue
                            try {
                                innerField.isAccessible = true
                            } catch (_: Throwable) {
                                continue
                            }
                            val innerValue = try {
                                innerField.get(value)
                            } catch (_: Throwable) {
                                null
                            }
                            if (innerValue is Map<*, *> || innerValue is Collection<*>) {
                                harvest(innerValue, name + "->" + innerField.name)
                            }
                        }
                        inner = inner.superclass
                    }
                    val getter = Refl.methodOf(value.javaClass, "getRegistrations", 0)
                    if (getter != null) harvest(Refl.invoke(getter, value), name + ".getRegistrations()")
                }
            }
            cursor = cursor.superclass
        }
        if (found.isEmpty()) return null
        Log.rate("regs.size", 30_000L, "independent: registration list size=" + found.size)
        return found.values.toList()
    }

    private fun providerFor(name: String): String = when (name.lowercase()) {
        "gps", "gnss" -> "gps"
        "network" -> "network"
        "fused" -> "fused"
        "passive" -> "passive"
        else -> name
    }

    /**
     * Hook for `lambda$onReportLocation$NN(LocationResult, Registration)`: the per-registration
     * dispatch closure. It is the cheapest place to drop a delivery in independent mode, because the
     * Registration names its package and the lambda only *returns* a deferred operation - handing
     * back a no-op operation cancels the delivery without any exception.
     */
    private fun registrationLambdaHook(): XC_MethodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                val snap = EngineRuntime.activeSnap() ?: return
                if (!snap.config.independentMode) {
                    if (reentrant.get() != true) replaceFixArguments(param, snap)
                    return
                }
                val registration = param.args.firstOrNull {
                    it != null && it.javaClass.name.contains("Registration")
                }
                val packageName = packageOf(registration)
                if (packageName != null && Scope.isSpoofedPackage(packageName)) {
                    if (reentrant.get() != true) replaceFixArguments(param, snap)
                    Log.rate("indep.lambda.allow." + packageName, 60_000L, "independent: filter pass for $packageName")
                    return
                }
                // Unknown identity counts as "not whitelisted": never leak our synthetic fix, and
                // never touch a real one either.
                if (!Fakes.isFabricated(param.args.firstOrNull())) return
                val noOp = noOpReturnValue(param)
                if (noOp != NO_OP_UNAVAILABLE) {
                    param.result = noOp
                    Log.rate("indep.lambda." + packageName, 30_000L, "independent: dropped delivery to $packageName")
                } else {
                    Log.rate("indep.lambda.stuck", 60_000L, "independent: cannot drop delivery for $packageName (return type not proxyable)")
                }
            } catch (t: Throwable) {
                Log.rate("indep.lambda.err", 15_000L, "registration lambda hook failed: ${t.javaClass.simpleName}")
            }
        }
    }

    /** Sentinel meaning "this return type cannot be neutralised". */
    private val NO_OP_UNAVAILABLE = Any()

    /**
     * A no-op instance of the method is return type (typically the ListenerOperation interface), or
     * [NO_OP_UNAVAILABLE] when the type is not an interface / is void.
     */
    private fun noOpReturnValue(param: XC_MethodHook.MethodHookParam): Any? {
        val method = param.method as? Method ?: return NO_OP_UNAVAILABLE
        val type = method.returnType
        if (type == Void.TYPE || type.name == "void") return null
        if (!type.isInterface) return NO_OP_UNAVAILABLE
        return try {
            java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, _ ->
                when (m.returnType) {
                    java.lang.Boolean.TYPE -> java.lang.Boolean.FALSE
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    java.lang.Short.TYPE -> 0.toShort()
                    java.lang.Byte.TYPE -> 0.toByte()
                    java.lang.Character.TYPE -> 0.toChar()
                    else -> null
                }
            }
        } catch (_: Throwable) {
            NO_OP_UNAVAILABLE
        }
    }

    /**
     * Hooks the *Registration* classes by name. They are not always inner classes of
     * LocationProviderManager, and their delivery methods are where the receiving identity actually
     * lives (`mIdentity`/`getIdentity()`), so they must be hooked explicitly rather than only through
     * the declaredClasses walk.
     */
    private fun hookKnownRegistrationClasses(loader: ClassLoader?) {
        val simpleNames = listOf(
            "ListenerRegistration",
            "LocationListenerRegistration",
            "LocationPendingIntentRegistration",
            "LocationListenerTransport",
            "GetCurrentLocationTransport",
            "ProviderTransport",
            "PendingIntentSender",
        )
        for (simple in simpleNames) {
            for (prefix in arrayOf(
                "com.android.server.location.provider.",
                "com.android.server.location.provider.LocationProviderManager$",
            )) {
                val cls = Refl.findClassAnywhere(prefix + simple, loader) ?: continue
                val methods = Refl.methodsWhere(cls) { m ->
                    (m.name.startsWith("deliver") || m.name.startsWith("accept")) &&
                        m.parameterTypes.any { it.name.contains("Location") }
                }
                for (m in methods) {
                    if (Refl.hook(m, deliveryHook(), "Registration$" + simple + "#" + m.name)) {
                        resolved++
                    } else {
                        missed++
                    }
                }
            }
        }
    }

    // --------------------------------------------------- independent mode (per-listener)

    /** Real (non-fabricated) last location per provider — kept so independent mode can hand it back. */
    private val realLast = ConcurrentHashMap<String, Location>()

    /** Identity map: listener transport/registration instance -> receiving package. */
    private val identityByObject: MutableMap<Any, String> =
        java.util.Collections.synchronizedMap(java.util.IdentityHashMap<Any, String>())

    private fun rememberReal(result: Any?) {
        if (result == null || Fakes.isFabricated(result)) return
        val active = EngineRuntime.activeSnap()
        val locations = if (Refl.isInstanceOf(result, LR_NAME)) Fakes.locationsOfResult(result)
        else listOfNotNull(result as? Location)
        for (location in locations) {
            val provider = location.provider ?: continue
            // A Parcelable round-trip drops object identity, and production builds omit the
            // `immoral.*` extras - so our own fabricated fix can come back looking genuine. If it is
            // remembered as "the real one", independent mode then publishes it straight back into the
            // provider cache and every non-whitelisted read keeps seeing the simulated point
            // (measured 2026-11-02: cache stayed fake after the mode switch although the restore ran).
            if (active != null && coordinatesMatchSnap(location, active)) continue
            // A delayed report of an earlier fake can arrive after the engine has moved, so the
            // current-snap test alone is not enough: check the recent fabricated set as well.
            if (Fakes.matchesFabricatedPoint(location.latitude, location.longitude)) continue
            realLast[provider] = location
        }
    }

    private fun rememberIdentity(target: Any?, packageName: String?) {
        if (target == null || packageName == null) return
        if (identityByObject.size > 512) identityByObject.clear()
        identityByObject[target] = packageName
    }

    /** Best-effort package extraction from a constructor argument list. */
    private fun packageFromArguments(args: Array<Any?>): String? {
        for (arg in args) {
            when (arg) {
                null -> Unit

                else -> {
                    val getter = Refl.methodOf(arg.javaClass, "getPackageName", 0)
                    val value = Refl.invoke(getter, arg) as? String
                    if (!value.isNullOrEmpty() && value != "android") return value
                }
            }
        }
        return null
    }
    private val packageAccessors = ConcurrentHashMap<Class<*>, (Any) -> String?>()

    /**
     * Hooks every "hand a fix to one listener" method of the manager's inner classes
     * (Registration / LocationTransport / ListenerTransport …).
     *
     * This is the only boundary where the receiving package is known, so 独立模拟 is enforced here:
     * our synthetic fix is dropped for apps that are not whitelisted, while real fixes still flow
     * through untouched.
     */
    private fun hookPerRegistrationDelivery(managerClass: Class<*>) {
        val classes = LinkedHashSet<Class<*>>()
        var cursor: Class<*>? = managerClass
        while (cursor != null && cursor != Any::class.java) {
            try {
                classes.addAll(cursor.declaredClasses)
            } catch (_: Throwable) {
            }
            cursor = cursor.superclass
        }
        if (classes.isEmpty()) {
            Log.hook(false, "LocationProviderManager inner classes", "none")
            missed++
            return
        }
        Log.i("LocationProviderManager inner classes: " + classes.joinToString { it.simpleName })
        var hooked = 0
        for (inner in classes) {
            // Capture the receiving package from the transport/registration constructor arguments;
            // transports do not expose an identity field, so this is the only way to filter them.
            try {
                Refl.hookAllConstructors(inner, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            // Second chance: a transport is constructed during the registering binder
                            // call, so the caller uid identifies the app.
                            var resolved = packageFromArguments(param.args)
                            if (resolved == null) {
                                val uid = Scope.callingUid()
                                if (uid != 1000 && uid != android.os.Process.myUid()) {
                                    resolved = Scope.packageForUid(uid)
                                }
                            }
                            rememberIdentity(param.thisObject, resolved)
                        } catch (_: Throwable) {
                        }
                    }
                }, "LocationProviderManager$" + inner.simpleName)
            } catch (_: Throwable) {
            }
            val methods = Refl.methodsWhere(inner) { m ->
                (m.name.startsWith("deliver") || m.name.startsWith("accept") || m.name == "onLocationChanged") &&
                    m.parameterTypes.any { it.name.contains("Location") }
            }
            for (m in methods) {
                if (Refl.hook(m, deliveryHook(), "LocationProviderManager$" + inner.simpleName + "#" + m.name)) {
                    hooked++
                    resolved++
                } else {
                    missed++
                }
            }
        }
        if (hooked == 0) {
            Log.hook(false, "per-listener delivery", "no deliver* method found")
            missed++
        }
    }

    private fun deliveryHook(): XC_MethodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                val snap = EngineRuntime.activeSnap() ?: return
                val carrier = param.args.firstOrNull { it != null } ?: return
                rememberReal(carrier)
                val packageName = packageOf(param.thisObject)
                // Diagnostic: proves whether a given provider's delivery actually passes through this
                // hook. Framework-only builds depend on it, so "no line for provider X" is the
                // signal that X reaches the app through another seam.
                Log.rate(
                    "deliver." + (param.method?.name ?: "?") + "." + (packageName ?: "?"),
                    20_000L,
                    "delivery hook: " + param.thisObject.javaClass.simpleName + "#" + param.method?.name +
                        " pkg=" + (packageName ?: "UNRESOLVED") +
                        (if (snap.config.independentMode) " (independent)" else " (normal)"),
                )
                if (snap.config.independentMode) {
                    Log.rate(
                        "indep.filter." + (param.method?.name ?: "?") + "." + (packageName ?: "UNRESOLVED"),
                        20_000L,
                        "independent: filter " + param.method?.name + " pkg=" + (packageName ?: "UNRESOLVED"),
                    )
                    if (packageName == null) {
                        // Unresolved listener: in independent mode it is by definition NOT whitelisted,
                        // so a fabricated fix is suppressed (never passed through). Real fixes pass.
                        //
                        // Two tests, because the markers do not always survive: the `immoral.*` extras
                        // can be dropped when the platform re-creates the Location, so a coordinate
                        // match against the engine's own fix is the fallback. Without it a
                        // non-whitelisted app could still receive our fake through an unresolved
                        // registration (measured: `pkg=UNRESOLVED (independent)` deliveries).
                        // Fail closed for unresolved identities: the ONLY thing that may pass is a value that
                        // matches the last genuine fix we saw. Any other object here is either our fake or
                        // a platform re-creation of it, and handing that to an unknown receiver is exactly
                        // the leak measured on 2026-11-02 (samples=2, fake=True).
                        // Drop when it looks like ours, or when it matches no genuine fix while we KNOW one.
                        // With no known real fix we must let it through: the alternative is a blackout for every
                        // unresolved receiver (measured as "inconclusive: no sample").
                        val looksOurs = Fakes.containsFabricated(carrier) || coordinatesMatchSnap(carrier, snap)
                        val realLike = coordinatesMatchReal(carrier) || realLast.isEmpty()
                        val dropIt = looksOurs || (!realLike && realLast.isNotEmpty())
                        if (dropIt) {
                            dropDelivery(param)
                            Log.rate(
                                "indep.unresolved.drop",
                                30_000L,
                                "independent: dropped fabricated delivery (unresolved package) on " +
                                    param.method?.name,
                            )
                        }
                        return
                    } else if (!Scope.isSpoofedPackage(packageName)) {
                        if (Fakes.containsFabricated(carrier)) {
                            // Our synthetic fix: dropping it is the right semantic (there is no real
                            // fix to fall back to — we injected this flow ourselves).
                            dropDelivery(param)
                            Log.rate("indep.drop." + packageName, 30_000L, "independent: dropped delivery to $packageName")
                            return
                        }
                        Log.rate("indep.pass." + packageName, 60_000L, "independent: real fix passed through to $packageName")
                        // "Pass through" is not automatically a *real* fix: the report path rewrites the
                        // provider's own stored location, so the value arriving here can already be our
                        // fake. Measured 2026-11-02: a non-whitelisted verifier received
                        // `passive lat=31.234916` — the simulated point — through exactly this branch.
                        // Substitute the last genuine fix captured for this provider, else drop.
                        if (Fakes.containsFabricated(carrier) || coordinatesMatchSnap(carrier, snap)) {
                            val provider = (carrier as? Location)?.provider ?: "gps"
                            val real = anyRealFix(provider)
                            if (real != null) {
                                for (i in param.args.indices) {
                                    when (val a = param.args[i]) {
                                        is Location -> param.args[i] = real
                                        else -> if (Refl.isInstanceOf(a, LR_NAME)) {
                                            Fakes.locationResult(listOf(real))?.let { param.args[i] = it }
                                        }
                                    }
                                }
                                Log.rate(
                                    "indep.subst." + packageName,
                                    30_000L,
                                    "independent: substituted the last real $provider fix for $packageName",
                                )
                            } else {
                                dropDelivery(param)
                                Log.rate(
                                    "indep.drop.noreal." + packageName,
                                    30_000L,
                                    "independent: dropped our fix for $packageName (no real fix captured yet)",
                                )
                            }
                        }
                        return
                    } else {
                        Log.rate("indep.allow." + packageName, 60_000L, "independent: filter pass for $packageName")
                    }
                }
                replaceFixArguments(param, snap)
            } catch (t: Throwable) {
                Log.rate("delivery.filter", 15_000L, "delivery filter failed: ${t.javaClass.simpleName}")
            }
        }
    }

    /**
     * Does this delivery carry the engine's own fix?
     *
     * Independent mode needs a second identity test: the `immoral.*` extras survive the report path
     * but not necessarily a platform-side re-creation of the `Location`, and a fabricated fix that
     * slips through to a non-whitelisted registration is a product-visible leak (measured as
     * `pkg=UNRESOLVED (independent)` deliveries). Coordinates are compared with a 150 m tolerance —
     * orders of magnitude below the distance between the fake position and the device's real one.
     */
    /**
     * Does this delivery resemble a GENUINE fix?
     *
     * Used for the fail-closed branch: an unresolved receiver may only be handed a value that is
     * close to a real fix we have seen. 500 m is generous on purpose (a walking engine barely moves
     * the real chip), while our simulated point is normally kilometres away.
     */
    private fun coordinatesMatchReal(carrier: Any?): Boolean {
        if (carrier == null || realLast.isEmpty()) return false
        val locations = try {
            if (Refl.isInstanceOf(carrier, LR_NAME)) Fakes.locationsOfResult(carrier)
            else listOfNotNull(carrier as? Location)
        } catch (_: Throwable) {
            return false
        }
        for (location in locations) {
            for (real in realLast.values) {
                val dLat = location.latitude - real.latitude
                val dLon = location.longitude - real.longitude
                val meters = Math.sqrt(dLat * dLat + dLon * dLon * 0.66) * 111_320.0
                if (meters <= 500.0) return true
            }
        }
        return false
    }
    private fun coordinatesMatchSnap(carrier: Any?, snap: EngineRuntime.Snap): Boolean {
        if (carrier == null) return false
        val locations = try {
            if (Refl.isInstanceOf(carrier, LR_NAME)) Fakes.locationsOfResult(carrier)
            else listOfNotNull(carrier as? Location)
        } catch (_: Throwable) {
            return false
        }
        for (location in locations) {
            val dLat = location.latitude - snap.lat
            val dLon = location.longitude - snap.lon
            val meters = Math.sqrt(dLat * dLat + dLon * dLon * 0.66) * 111_320.0
            if (meters <= 150.0) return true
        }
        return false
    }

    /** Cancels the delivery: no-op ListenerOperation when possible, plain early return otherwise. */
    private fun dropDelivery(param: XC_MethodHook.MethodHookParam) {        val noOp = noOpReturnValue(param)
        param.result = if (noOp == NO_OP_UNAVAILABLE) null else noOp
    }

    /** Replaces every `Location`/`LocationResult` argument of the current call. */
    private fun replaceFixArguments(param: XC_MethodHook.MethodHookParam, snap: EngineRuntime.Snap) {
        for (i in param.args.indices) {
            when (val arg = param.args[i]) {
                is Location -> param.args[i] = Fakes.location(arg.provider ?: "gps", snap)
                else -> if (Refl.isInstanceOf(arg, LR_NAME)) {
                    val provider = Fakes.providerOfResult(arg) ?: "gps"
                    Fakes.locationResult(listOf(Fakes.location(provider, snap)))?.let { param.args[i] = it }
                }
            }
        }
    }

    /** Receiving package of a per-listener transport/registration, or null when unreadable. */
    private fun packageOf(target: Any?): String? {
        if (target == null) return null
        try {
            identityByObject[target]?.let { return it }
        } catch (_: Throwable) {
        }
        val accessor = packageAccessors.getOrPut(target.javaClass) { buildPackageAccessor(target.javaClass) }
        return try {
            accessor(target)
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildPackageAccessor(cls: Class<*>): (Any) -> String? {
        for (name in arrayOf("mPackageName", "packageName", "mClientPackageName", "mTargetPackage")) {
            val field = Refl.fieldOf(cls, name) ?: continue
            if (field.type == String::class.java) {
                Log.i("independent: package accessor ${cls.simpleName}.$name")
                return { target -> Refl.get(target, field) as? String }
            }
        }
        for (name in arrayOf("mAttributionSource", "mPackageIdentity", "mListenerIdentity", "mIdentity", "mCallerIdentity", "mAppOpsHelper")) {
            val field = Refl.fieldOf(cls, name) ?: continue
            val holder = field.type
            val getter = Refl.methodOf(holder, "getPackageName", 0)
            if (getter != null) {
                Log.i("independent: package accessor ${cls.simpleName}.$name.getPackageName()")
                return { target -> Refl.invoke(getter, Refl.get(target, field)) as? String }
            }
            val nested = Refl.fieldOf(holder, "mPackageName")
            if (nested != null) {
                Log.i("independent: package accessor ${cls.simpleName}.$name.mPackageName")
                return { target -> Refl.get(Refl.get(target, field), nested) as? String }
            }
            val uidGetter = Refl.methodOf(holder, "getUid", 0)
            if (uidGetter != null) {
                Log.i("independent: package accessor ${cls.simpleName}.$name.getUid()->lookup")
                return { target ->
                    val uid = Refl.invoke(uidGetter, Refl.get(target, field)) as? Int
                    if (uid == null) null else Scope.packageForUid(uid)
                }
            }
        }
        Log.i("independent: no package accessor on ${cls.simpleName} (independent mode coarse for it)")
        return { _ -> null }
    }
    // ------------------------------------------------------------------ hooks

    /** Replaces the fix carried by report-path arguments. */
    private fun reportReplaceHook(label: String): XC_MethodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                if (reentrant.get() == true) return
                val snap = EngineRuntime.activeSnap() ?: return
                // In independent mode the replacement is done per listener instead, so the real fix
                // keeps flowing to apps that are not whitelisted.
                if (snap.config.independentMode) return
                if (param.args.isEmpty()) return
                val original = param.args[0] ?: return
                // Keep the real world alongside the fake one (independent mode hands it back).
                rememberReal(original)
                // Capture the manager instance the first time we see a real report.
                if (managers.isEmpty()) captureManager(param.thisObject)
                // Independent mode must still be able to hand the REAL fix to non-whitelisted callers, and
            // the only dependable source of it is the provider's own cache - so do not overwrite it in
            // that mode. The fake is injected at the delivery/query seams instead, which know who the
            // receiver is (measured 2026-11-02: rewriting the cache here made 10-independent either
            // leak the fake through getLastKnownLocation or black out entirely).
            if (snap.config.independentMode) return
            val replaced = replaceValue(original, snap) ?: return
                param.args[0] = replaced
                Log.rate("replace:" + label, 30_000L, "report path replaced via $label")
            } catch (t: Throwable) {
                Log.rate("reportreplace", 15_000L, "report replace failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /** Replaces the return value of `getLastLocation`-style methods. */
    /**
     * Last genuine fix for [key], falling back to any other provider.
     *
     * Provider names do not line up across seams: reports arrive as `gps`/`network`/`fused`, while an
     * app frequently reads `passive`, and an exact-key lookup then returned null - which the
     * non-whitelisted branch turned into "no location at all" (measured 2026-11-02 as a blackout in
     * `10-independent`). A real fix from any provider is a far better answer than none.
     */
    private fun anyRealFix(key: String): Location? {
        val chain = listOfNotNull(
            realLast[providerFor(key)],
            realLast[key],
            realLast["fused"],
            realLast["gps"],
            realLast["network"],
        ) + realLast.values
        val snap = EngineRuntime.activeSnap()
        for (candidate in chain) {
            // Read-time hygiene, not bookkeeping hygiene: `realLast` can be poisoned by a path that
            // never went through [rememberReal]'s guards (a cached provider value read back through
            // our own hook is one). A non-whitelisted reader must never receive our fix, whatever
            // ended up in the map - measured 2026-11-02: the outside read of `gps` returned our
            // simulated point (31.2306,121.4737, acc=4) while `passive`/`network`/`fused` returned
            // genuine values (30.2763,120.1762), i.e. exactly one key was contaminated.
            if (Fakes.containsFabricated(candidate)) continue
            if (snap != null && coordinatesMatchSnap(candidate, snap)) continue
            if (Fakes.matchesFabricatedPoint(candidate.latitude, candidate.longitude)) continue
            return candidate
        }
        return null
    }
    private fun lastLocationHook(label: String): XC_MethodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                // Our own reads (the pre-replacement snapshot in [captureRealLastKnown]) must see the
                // platform's raw cached value, not a substituted one.
                if (reentrant.get() == true) return
                val snap = EngineRuntime.activeSnap() ?: return
                if (!isSystemCaller() && !Scope.isCallerSpoofed()) {
                    // Independent mode: this caller must never see the fabricated fix. Hand back the
                    // real last fix we observed (null = unknown, which is legal for this API).
                    val key = providerArgument(param) ?: providerOfManager(param.thisObject) ?: "gps"
                    val real = anyRealFix(key)
                    param.result = real
                    Log.rate(
                        "indep.last." + key,
                        30_000L,
                        "independent: getLastLocation(" + key + ") for a non-whitelisted caller -> " +
                            if (real != null) "real fix" else "no location",
                    )
                    return
                }
                // Opportunistic capture: the first app query also registers the manager instance,
                // which matters if our hooks landed after LocationService had already started.
                captureManager(param.thisObject)
                val provider = providerArgument(param) ?: providerOfManager(param.thisObject) ?: "gps"
                param.result = Fakes.location(providerFor(provider), snap)
                Log.rate("last:" + label, 30_000L, "last-location answered via $label as $provider")
            } catch (t: Throwable) {
                Log.rate("lastlocation", 15_000L, "last-location replace failed: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun providerArgument(param: XC_MethodHook.MethodHookParam): String? {
        val method = param.method as? Method ?: return null
        for ((index, type) in method.parameterTypes.withIndex()) {
            if (type == String::class.java) {
                val value = param.args.getOrNull(index) as? String
                if (value == "gps" || value == "network" || value == "fused" || value == "passive") return value
            }
        }
        return null
    }

    private fun providerOfManager(instance: Any?): String? = instance?.let { providerNameOf(it) }

    /** system_server / provider uids are always spoofed regardless of independent mode. */
    private fun isSystemCaller(): Boolean {
        val uid = Scope.callingUid()
        return uid == 0 || uid == Scope.SYSTEM_UID || uid == android.os.Process.myUid()
    }

    private fun replaceValue(value: Any?, snap: EngineRuntime.Snap): Any? = when {
        value == null -> null
        value is Location -> Fakes.location(value.provider ?: "gps", snap)
        Refl.isInstanceOf(value, LR_NAME) -> {
            val provider = Fakes.providerOfResult(value) ?: "gps"
            Fakes.locationResult(listOf(Fakes.location(provider, snap))) ?: value
        }
        value is List<*> -> {
            val out = ArrayList<Any>(value.size.coerceAtLeast(1))
            for (item in value) {
                val replaced = replaceValue(item, snap) ?: continue
                out.add(replaced)
            }
            if (out.isEmpty()) value else out
        }
        else -> value
    }
}









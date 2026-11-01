package com.immoral.location.hook

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Framework-side seam for 步频模拟 (step counter / step detector) inside `system_server`.
 *
 * ## Why this file is mostly diagnostics
 *
 * There is **no Java choke point** for sensor events in `system_server`: `SensorService` is native
 * (`SensorService::SensorEventConnection::sendEvents`), and every app-owned Java hook was removed
 * with the client layer. The only framework-side delivery path is therefore the native hook in
 * `libimmoralhook.so` (`native/src/sensor_hook.cpp`), which rewrites step events **in place** on
 * their way out of `SensorService`.
 *
 * That asymmetry matters for the acceptance check: rewriting can only change events the sensor HAL
 * actually produced. `TYPE_STEP_COUNTER` reports on change, so a phone lying on a desk produces
 * none, and "counter delta=0 over 0 samples" (`8-steps`) is then not a broken rewrite but a starving
 * delivery path. This object makes that visible instead of leaving it to guesswork:
 *
 * ```
 * steps seam: engine steps=64 delta=4 cadence=120bpm sim=true running=true
 *             native=[installed=1 calls=2211 counter=0 detector=0 lookup_fail=0]
 * ```
 *
 * `engine steps` climbing proves the engine half (cadence-driven since the semantics change in
 * `SimulationEngine`); `counter=0` with `calls>0` proves the native hook is live but handed nothing
 * to rewrite — the named structural gap written up in `docs/ROUND-NOTES.md`.
 *
 * Install is wired from [NativeBridge.install], i.e. exactly once per `system_server` boot, and is
 * idempotent. Nothing here can throw into the framework: the tick listener body is wrapped.
 */
object SensorBoot {

    private val installed = AtomicBoolean(false)

    private var lastReportMs = 0L
    private var lastSteps = -1L
    private var loggedMissingPath = false
    private var loggedHandle = false
    private var lastHandleCheckMs = 0L

    /** Handles of every `TYPE_STEP_COUNTER` variant (non-wakeup and wake-up). */
    private var stepCounterHandles: IntArray = IntArray(0)

    /** Registers the diagnostic tick listener (idempotent). */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        try {
            EngineRuntime.addTickListener { snap -> report(snap) }
            Log.i("steps seam: monitoring the framework delivery path (native: ${NativeBridge.status()})")
        } catch (t: Throwable) {
            Log.e("steps seam install failed", t)
        }
    }

    /**
     * Resolves the HAL handles of `TYPE_STEP_COUNTER` from the **system context** and arms the
     * native fabrication path with them.
     *
     * Why the handles are needed at all: `SensorService` filters every outgoing batch per connection
     * by sensor handle, so a fabricated event is only delivered to an app that registered *that*
     * sensor. Measured on device (2026-11-02): pushing only `getDefaultSensor`'s handle (`0xbf`,
     * non-wakeup) produced 27 injections and **zero** app-visible events, while this device also
     * exposes a wake-up step counter (`0xc0`) — so *every* handle of the type is pushed and the
     * connection's own filter decides. `getSensorList` is the only source that can name both before
     * any step has ever happened; the native side additionally learns the authoritative handle from
     * the first real step event it sees.
     *
     * Resolved lazily (on the first tick with 步频模拟 on) rather than at install time, because at
     * `handleLoadPackage` the `ActivityThread`/`SensorManager` of `system_server` may not be ready
     * yet — and a `SensorManager` connection is only worth creating when the feature is used.
     *
     * The handles are **re-checked** every [HANDLE_RECHECK_MS] and re-pushed when they change, and
     * fabrication is disarmed (empty array) when the sensor disappears: `sensorservice` can be
     * restarted (HAL death, `killall sensorservice`) and the numbering is then rebuilt, so a cached
     * handle could name a *different* sensor in `sendEvents`. A stale handle is the one failure mode
     * that could touch another sensor's stream, so it is not left to chance. A transient reflection
     * failure keeps the previous handles instead of disarming, because that would silently stop the
     * feature.
     */
    private fun syncStepSensorHandles() {
        if (!NativeBridge.isHooked) return
        val now = System.currentTimeMillis()
        if (stepCounterHandles.isNotEmpty() && now - lastHandleCheckMs < HANDLE_RECHECK_MS) return
        lastHandleCheckMs = now
        val handles = resolveStepHandles() ?: return
        if (handles.contentEquals(stepCounterHandles)) return
        val previous = stepCounterHandles
        stepCounterHandles = handles
        NativeBridge.setStepSensorHandles(handles)
        when {
            handles.isEmpty() ->
                Log.w("steps seam: no TYPE_STEP_COUNTER sensor visible any more — fabrication disarmed")
            previous.isEmpty() -> {
                loggedHandle = true
                Log.i("steps seam: TYPE_STEP_COUNTER handles=${handles.joinToString(",")} pushed to the native layer")
            }
            else -> Log.w(
                "steps seam: step-counter handles changed " +
                    "${previous.joinToString(",")} -> ${handles.joinToString(",")} (re-armed)",
            )
        }
    }

    /**
     * All handles of `TYPE_STEP_COUNTER`, or `null` when the lookup itself failed (keep whatever we
     * had and retry on the next tick). An empty array means "the sensor is gone" (disarm).
     */
    private fun resolveStepHandles(): IntArray? = try {
        val threadClass = Class.forName("android.app.ActivityThread")
        val current = threadClass.getMethod("currentActivityThread").invoke(null)
        val context = threadClass.getMethod("getSystemContext").invoke(current) as android.content.Context
        val manager = context.getSystemService(android.content.Context.SENSOR_SERVICE)
            as? android.hardware.SensorManager
        if (manager == null) {
            null
        } else {
            // `Sensor#getHandle()` is a public method at runtime but hidden from the SDK stubs, so it
            // has to be resolved reflectively (the same reason [Refl] exists).
            val getHandle = Class.forName("android.hardware.Sensor").getMethod("getHandle")
            val handles = LinkedHashSet<Int>(4)
            @Suppress("DEPRECATION")
            for (sensor in manager.getSensorList(android.hardware.Sensor.TYPE_STEP_COUNTER).orEmpty()) {
                (getHandle.invoke(sensor) as? Int)?.let { handles.add(it) }
            }
            manager.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER)?.let { sensor ->
                (getHandle.invoke(sensor) as? Int)?.let { handles.add(it) }
            }
            handles.toIntArray()
        }
    } catch (t: Throwable) {
        Log.once("steps.seam.context", "cannot resolve the step-counter handles yet: ${t.javaClass.simpleName}")
        null
    }

    /**
     * One line per ~5 s while 步频模拟 is on.
     *
     * Deliberately only while the toggle is on: a stopped feature must not paint the module's
     * fingerprint into logcat for nothing (`docs/STEALTH.md`).
     */
    private fun report(snap: EngineRuntime.Snap) {
        try {
            if (!snap.config.stepSimulation) {
                lastSteps = -1L
                return
            }
            syncStepSensorHandles()
            val now = System.currentTimeMillis()
            if (now - lastReportMs < REPORT_INTERVAL_MS) return
            lastReportMs = now
            val delta = if (lastSteps < 0L) -1L else snap.steps - lastSteps
            lastSteps = snap.steps
            Log.rate(
                "steps.seam",
                REPORT_INTERVAL_MS,
                "steps seam: engine steps=" + snap.steps + " delta=" + delta +
                    " cadence=" + snap.config.stepCadenceBpm + "bpm sim=" + snap.config.stepSimulation +
                    " running=" + snap.running + " native=[" + NativeBridge.stats() + "]" +
                    " fabricate=[" + NativeBridge.stepSeam() + "]",
            )
            if (!NativeBridge.isHooked && !loggedMissingPath) {
                loggedMissingPath = true
                Log.w(
                    "steps: stepSimulation is ON but no framework delivery path installed (" +
                        NativeBridge.status() + "); apps registered for TYPE_STEP_COUNTER see the real " +
                        "(unchanged) counter — see docs/ROUND-NOTES.md 步频模拟",
                )
            }
        } catch (t: Throwable) {
            Log.once("steps.seam.err", "step seam report failed: ${t.javaClass.simpleName}")
        }
    }

    private const val REPORT_INTERVAL_MS = 5_000L

    /** How often the step-counter handles are re-validated against the live `SensorManager`. */
    private const val HANDLE_RECHECK_MS = 30_000L
}

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

    /** `getHandle()` of `TYPE_STEP_COUNTER`, or -1 while unknown. */
    private var stepCounterHandle = -1

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
     * Resolves the HAL handle of `TYPE_STEP_COUNTER` from the **system context** and arms the
     * native fabrication path with it.
     *
     * Why the handle is needed at all: `SensorService` filters every outgoing batch per connection
     * by sensor handle, so a fabricated event is only delivered to an app that registered *that*
     * sensor. Resolved lazily (on the first tick with 步频模拟 on) rather than at install time,
     * because at `handleLoadPackage` the `ActivityThread`/`SensorManager` of `system_server` may
     * not be ready yet — and a `SensorManager` connection is only worth creating when the feature
     * is actually used.
     *
     * The handle is **re-checked** every [HANDLE_RECHECK_MS] and re-pushed when it changes, and it is
     * disarmed (`-1`) when the sensor disappears: `sensorservice` can be restarted (HAL death,
     * `killall sensorservice`) and the numbering is then rebuilt, so a cached handle could name a
     * *different* sensor in `sendEvents`. A stale handle is the one failure mode that could touch
     * another sensor's stream, so it is not left to chance. A transient reflection failure keeps the
     * previous handle instead of disarming, because that would silently stop the feature.
     */
    private fun syncStepSensorHandle() {
        if (!NativeBridge.isHooked) return
        val now = System.currentTimeMillis()
        if (stepCounterHandle >= 0 && now - lastHandleCheckMs < HANDLE_RECHECK_MS) return
        lastHandleCheckMs = now
        val handle = when (val resolved = resolveStepHandle()) {
            HANDLE_UNKNOWN -> return
            else -> resolved
        }
        if (handle == stepCounterHandle) return
        stepCounterHandle = handle
        NativeBridge.setStepSensorHandle(handle)
        if (handle >= 0) {
            if (!loggedHandle) {
                Log.i("steps seam: TYPE_STEP_COUNTER handle=$handle pushed to the native layer")
                loggedHandle = true
            } else {
                Log.w("steps seam: step-counter handle changed to $handle (re-armed)")
            }
        } else {
            Log.w("steps seam: no TYPE_STEP_COUNTER sensor visible any more — fabrication disarmed")
        }
    }

    /**
     * `> 0` = handle, `-1` = the sensor is gone (disarm), [HANDLE_UNKNOWN] = could not look it up
     * (keep whatever we had; the next tick retries).
     */
    private fun resolveStepHandle(): Int = try {
        val threadClass = Class.forName("android.app.ActivityThread")
        val current = threadClass.getMethod("currentActivityThread").invoke(null)
        val context = threadClass.getMethod("getSystemContext").invoke(current) as android.content.Context
        val manager = context.getSystemService(android.content.Context.SENSOR_SERVICE)
            as? android.hardware.SensorManager
        val sensor = manager?.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            -1
        } else {
            // `Sensor#getHandle()` is a public method at runtime but hidden from the SDK stubs, so it
            // has to be resolved reflectively (the same reason [Refl] exists).
            Class.forName("android.hardware.Sensor").getMethod("getHandle").invoke(sensor) as? Int
                ?: HANDLE_UNKNOWN
        }
    } catch (t: Throwable) {
        Log.once("steps.seam.context", "cannot resolve the step-counter handle yet: ${t.javaClass.simpleName}")
        HANDLE_UNKNOWN
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
            syncStepSensorHandle()
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

    /** How often the step-counter handle is re-validated against the live `SensorManager`. */
    private const val HANDLE_RECHECK_MS = 30_000L

    /** "Could not look the handle up" — keep the previous one (see [resolveStepHandle]). */
    private const val HANDLE_UNKNOWN = -2
}

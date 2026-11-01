package com.immoral.location.core.engine

/**
 * What the framework layer may do with one GNSS delivery to an app while the module is running.
 *
 * The hook layer (`hook/.../framework/GnssHooks.kt`) sits on the app-facing AIDL callbacks
 * (`IGnssStatusListener#onSvStatusChanged`, `IGnssNmeaListener#onNmeaReceived`, …). Those same
 * callbacks are how the module delivers its **own** synthesized status and NMEA, so a delivery
 * policy that only knows "cancel everything" also cancels the synthetic stream and leaves the app
 * with no GNSS at all. That defect is what this type exists to prevent: it makes the decision a
 * pure, testable function of *who* is delivering instead of an inline `if` in a hot hook body.
 *
 * | engine | independent | gate | synthetic | verdict |
 * |---|---|---|---|---|
 * | stopped | any | any | any | [GnssDelivery.DELIVER] |
 * | running | any | disabled (`persist.immoral.gnss_truth_gate=0`) | any | [GnssDelivery.DELIVER] |
 * | running | any | enabled | yes (ours) | [GnssDelivery.DELIVER] |
 * | running | yes | enabled | no | [GnssDelivery.DELIVER] |
 * | running | no | enabled | no | [GnssDelivery.WITHHOLD] |
 *
 * Rationale for the rows that are easy to get wrong:
 *
 * * **A stopped engine spoofs nothing** — a stopped simulation must never suppress the real data,
 *   otherwise "turn the module off" would leave apps without GNSS.
 * * **Independent mode (独立模拟) passes real data through**: the delivery point carries no
 *   receiving package, so a non-whitelisted package cannot be told apart from a whitelisted one.
 *   Withholding there would *hide* real GNSS from outsiders instead of hiding our simulation from
 *   them, which is the wrong direction for that feature.
 * * **`syntheticPush` is decided by the caller**, which is the only side that knows the data is
 *   synthesized (it is set around the module's own reflection call into the listener, and cleared
 *   again in a `finally`).
 */
enum class GnssDelivery {
    /** Let the delivery happen (the app-visible call keeps its original behaviour). */
    DELIVER,

    /** Cancel the delivery: the app must not see it (real chip data during a simulation). */
    WITHHOLD,
}

/** Pure decision table for the framework GNSS delivery gate. */
object GnssTruthGate {

    /**
     * @param engineRunning whether a simulation is actually running right now.
     * @param gateEnabled the runtime escape hatch (`persist.immoral.gnss_truth_gate`, default on).
     * @param independentMode whether 独立模拟 is on.
     * @param syntheticPush `true` only while the module itself is delivering synthesized data.
     */
    fun decide(
        engineRunning: Boolean,
        gateEnabled: Boolean,
        independentMode: Boolean,
        syntheticPush: Boolean,
    ): GnssDelivery = when {
        !engineRunning -> GnssDelivery.DELIVER
        !gateEnabled -> GnssDelivery.DELIVER
        syntheticPush -> GnssDelivery.DELIVER
        independentMode -> GnssDelivery.DELIVER
        else -> GnssDelivery.WITHHOLD
    }
}

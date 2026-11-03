package com.immoral.location.core.engine

/**
 * What the framework layer may do with one `LocationAvailability` delivery to an app while the
 * module is running.
 *
 * ## The defect this exists for
 *
 * `LocationAvailability` is the second half of the location contract: `onLocationChanged` says
 * *where*, `onLocationAvailability` says *whether there is a position at all*. Nothing in the hook
 * layer touched it, so the platform's own answer kept arriving untouched — and while the module
 * injects a synthetic fix once per tick, the device's real availability state (indoors, GNSS
 * toggled off, provider disabled, airplane mode) is frequently `false`.
 *
 * A locator SDK does not treat that as a hint. 高德/百度/腾讯 all stop consuming the stream on
 * `isLocationAvailable() == false`, and some drop the registration entirely — so the app received a
 * perfectly good fix every second *and* "there is no location", and believed the second one. That is
 * simultaneously a functional break (the simulation appears to stop) and an implausibility (a
 * receiver that reports fixes while claiming no signal exists).
 *
 * ## Why the verdict is a pure function
 *
 * The hook (`hook/.../framework/LocationHooks.kt`) sits on a binder-facing callback: it must not
 * contain branching policy that nobody unit-tests, and it must not be the only place where the
 * "independent mode" rule is remembered. The three consumers of that rule — GNSS
 * ([GnssTruthGate]), location availability ([LocationTruthGate]) and the fix replacement in
 * `LocationHooks` — otherwise drift apart, which is exactly how the earlier "independent mode leaks
 * the fake / blacks out a non-whitelisted app" bugs happened.
 *
 * ## Decision table
 *
 * | engine | gate | independent | already available | can fabricate | verdict |
 * |---|---|---|---|---|---|
 * | stopped | any | any | any | any | [LocationAvailabilityDelivery.PASS] |
 * | running | off (`persist.immoral.location_truth_gate=0`) | any | any | any | [LocationAvailabilityDelivery.PASS] |
 * | running | on | yes (独立模拟) | any | any | [LocationAvailabilityDelivery.PASS] |
 * | running | on | no | yes | any | [LocationAvailabilityDelivery.PASS] |
 * | running | on | no | no | yes | [LocationAvailabilityDelivery.FABRICATE] |
 * | running | on | no | no | no | [LocationAvailabilityDelivery.WITHHOLD] |
 *
 * Rationale for the rows that are easy to get wrong (they mirror [GnssTruthGate] deliberately):
 *
 * * **A stopped engine spoofs nothing.** Switching the simulation off must leave the real device
 *   alone; fabricating "available" there would keep an app consuming a stream that no longer exists
 *   (the fix hooks are already gated on `EngineRuntime.activeSnap()`, which is `null` when stopped).
 * * **Independent mode (独立模拟) passes through — the same decision [GnssTruthGate] makes, for the
 *   same reason.** This delivery point carries a `LocationAvailability` and nothing else: there is
 *   no receiving package, no registration, no uid, so a whitelisted target and a non-whitelisted
 *   outsider are indistinguishable here. Fabricating "available" would therefore hand *real-looking
 *   device state* to apps that must keep seeing the real device — the outsiders would be told
 *   "location is available" even while their own real availability is `false`, which is a
 *   contradiction in the direction that hurts them. Withholding is worse still: it would hide real
 *   availability from everyone. So the only correct answer for an identity-less delivery point in
 *   that mode is to leave it untouched; the whitelisted packages are served by the per-registration
 *   seams in `LocationHooks` (`deliver*`/`accept*`), where the package *is* known, and by the
 *   injected `onLocationChanged` stream itself.
 * * **`alreadyAvailable` short-circuits.** A delivery that already says "available" needs no work
 *   and no allocation: the platform's own object is more realistic than ours (it carries the real
 *   cell/Wi-Fi status codes), so it is passed through. This also makes the decision idempotent when
 *   the same availability object reaches several internal seams.
 * * **`canFabricate = false` ⇒ [LocationAvailabilityDelivery.WITHHOLD], not PASS.** If the object
 *   cannot be built (hidden constructor and field write both unavailable on this ROM), letting the
 *   platform's `false` through reproduces the exact defect above. Cancelling the delivery leaves the
 *   app on its last known availability (normally `true`, since we do deliver fixes) — the smaller
 *   lie. It is the same trade the GNSS gate makes when it cancels a real delivery.
 */
enum class LocationAvailabilityDelivery {
    /** Leave the delivery exactly as the platform produced it. */
    PASS,

    /** Replace the `LocationAvailability` argument with a fabricated "available" instance. */
    FABRICATE,

    /** Cancel the delivery: the app must not see this availability (and we cannot fake it). */
    WITHHOLD,
}

/** Pure decision table for the framework `LocationAvailability` delivery gate. */
object LocationTruthGate {

    /**
     * @param engineRunning whether a simulation is actually running right now
     *   (`EngineRuntime.activeSnap() != null`).
     * @param gateEnabled the runtime escape hatch (`persist.immoral.location_truth_gate`,
     *   default **on**).
     * @param independentMode whether 独立模拟 is on.
     * @param alreadyAvailable what the platform's own `LocationAvailability` says
     *   (`isLocationAvailable()` read back from the argument; `false` when unreadable).
     * @param canFabricate whether the hook layer can materialise a verified "available" instance.
     */
    fun decide(
        engineRunning: Boolean,
        gateEnabled: Boolean,
        independentMode: Boolean,
        alreadyAvailable: Boolean,
        canFabricate: Boolean,
    ): LocationAvailabilityDelivery = when {
        !engineRunning -> LocationAvailabilityDelivery.PASS
        !gateEnabled -> LocationAvailabilityDelivery.PASS
        independentMode -> LocationAvailabilityDelivery.PASS
        alreadyAvailable -> LocationAvailabilityDelivery.PASS
        canFabricate -> LocationAvailabilityDelivery.FABRICATE
        else -> LocationAvailabilityDelivery.WITHHOLD
    }
}

package com.immoral.location.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The framework `LocationAvailability` delivery gate.
 *
 * The defect this covers: while a simulation runs, the platform's own `onLocationAvailability(false)`
 * (indoors / GNSS off / provider disabled) kept reaching the app while synthetic fixes were still
 * being delivered once per tick. A locator SDK treats that flag as "there is no location" and stops
 * consuming the stream, so the app saw a contradiction. The hook layer may only repair it through
 * [LocationTruthGate], whose rows are pinned here — the hook itself is Android/reflection code and
 * cannot be unit-tested on the JVM.
 */
class LocationTruthGateTest {

    private fun decide(
        engineRunning: Boolean = true,
        gateEnabled: Boolean = true,
        independentMode: Boolean = false,
        alreadyAvailable: Boolean = false,
        canFabricate: Boolean = true,
    ) = LocationTruthGate.decide(
        engineRunning = engineRunning,
        gateEnabled = gateEnabled,
        independentMode = independentMode,
        alreadyAvailable = alreadyAvailable,
        canFabricate = canFabricate,
    )

    @Test
    fun anUnavailableDeliveryIsRepairedWhileSimulating() {
        // The row the whole feature exists for.
        assertEquals(LocationAvailabilityDelivery.FABRICATE, decide())
    }

    @Test
    fun anAlreadyAvailableDeliveryIsLeftAlone() {
        // No allocation, and the platform's own object (with its real cell/Wi-Fi status codes) is
        // more realistic than anything we could build — this also makes the gate idempotent when the
        // same availability object reaches several internal seams.
        assertEquals(LocationAvailabilityDelivery.PASS, decide(alreadyAvailable = true))
        assertEquals(
            LocationAvailabilityDelivery.PASS,
            decide(alreadyAvailable = true, canFabricate = false),
        )
    }

    @Test
    fun aStoppedEngineNeverTouchesRealAvailability() {
        for (independent in listOf(true, false)) {
            for (available in listOf(true, false)) {
                for (fabricable in listOf(true, false)) {
                    assertEquals(
                        LocationAvailabilityDelivery.PASS,
                        decide(
                            engineRunning = false,
                            independentMode = independent,
                            alreadyAvailable = available,
                            canFabricate = fabricable,
                        ),
                        "stopped engine, independent=$independent available=$available fabricable=$fabricable",
                    )
                }
            }
        }
    }

    @Test
    fun theRuntimeEscapeHatchDisablesTheGate() {
        // persist.immoral.location_truth_gate=0 is the A/B switch: same boot, same APK, same scope,
        // only this property differs, and the platform's availability flows untouched again.
        assertEquals(LocationAvailabilityDelivery.PASS, decide(gateEnabled = false))
        assertEquals(
            LocationAvailabilityDelivery.PASS,
            decide(gateEnabled = false, canFabricate = false),
        )
    }

    @Test
    fun independentModePassesRealAvailabilityThrough() {
        // 独立模拟: this delivery point carries no receiving package, so a whitelisted target and a
        // non-whitelisted outsider cannot be told apart here. Fabricating "available" would hand
        // fabricated device state to an outsider that must keep seeing the real device, and
        // withholding would hide real availability from everyone — the same decision, and the same
        // reasoning, as GnssTruthGate's independent-mode row.
        for (available in listOf(true, false)) {
            for (fabricable in listOf(true, false)) {
                assertEquals(
                    LocationAvailabilityDelivery.PASS,
                    decide(
                        independentMode = true,
                        alreadyAvailable = available,
                        canFabricate = fabricable,
                    ),
                    "independent, available=$available fabricable=$fabricable",
                )
            }
        }
    }

    @Test
    fun anUnbuildableAvailabilityIsWithheldNotPassedThrough() {
        // If the hidden LocationAvailability(boolean) constructor and the private-field fallback both
        // fail on this ROM, letting the platform's `false` through reproduces the defect, so the
        // delivery is cancelled instead (the app keeps its last known availability).
        assertEquals(LocationAvailabilityDelivery.WITHHOLD, decide(canFabricate = false))
    }

    @Test
    fun theThreeGatesAgreeOnTheIndependentModeBoundary() {
        // Regression guard for the class of bug that produced "independent mode leaks the fake" and
        // "independent mode blacks out a non-whitelisted app": every identity-less, app-facing gate
        // must treat independent mode the same way (pass real data through, never fabricate, never
        // withhold). If a future edit changes one table and not the other, this fails.
        val gnss = GnssTruthGate.decide(
            engineRunning = true,
            gateEnabled = true,
            independentMode = true,
            syntheticPush = false,
        )
        assertEquals(GnssDelivery.DELIVER, gnss, "gnss gate passes independent mode")
        assertEquals(LocationAvailabilityDelivery.PASS, decide(independentMode = true))
    }

    @Test
    fun everyInputCombinationIsTotal() {
        // Safety property for a binder-facing callback: no input may fall through without a verdict.
        for (engine in listOf(true, false)) {
            for (gate in listOf(true, false)) {
                for (independent in listOf(true, false)) {
                    for (available in listOf(true, false)) {
                        for (fabricable in listOf(true, false)) {
                            val verdict = decide(engine, gate, independent, available, fabricable)
                            // The only way to reach WITHHOLD is "we wanted to fix it and could not":
                            // engine running, gate on, not independent, not available, unbuildable.
                            assertEquals(
                                !engine || !gate || independent || available || fabricable,
                                verdict != LocationAvailabilityDelivery.WITHHOLD,
                                "engine=$engine gate=$gate independent=$independent " +
                                    "available=$available fabricable=$fabricable -> $verdict",
                            )
                        }
                    }
                }
            }
        }
    }
}

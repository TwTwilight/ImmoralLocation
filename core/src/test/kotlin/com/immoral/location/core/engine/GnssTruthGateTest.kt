package com.immoral.location.core.engine

import com.immoral.location.core.Reference
import com.immoral.location.core.model.SimulationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The framework GNSS delivery gate: who is allowed to reach an app.
 *
 * The defect this covers was measured on the device: with a simulation running, the hook layer
 * withheld **every** `onSvStatusChanged` / `onNmeaReceived` delivery — including the module's own
 * synthesized ones, which are pushed through the very same app-facing callbacks — so an app saw no
 * GNSS status at all while `gpsSignalSimulation = true` (`9-gnss`: `no gnss line, samples=0`).
 */
class GnssTruthGateTest {

    @Test
    fun aStoppedEngineNeverSuppressesAnything() {
        for (synthetic in listOf(true, false)) {
            for (independent in listOf(true, false)) {
                assertEquals(
                    GnssDelivery.DELIVER,
                    GnssTruthGate.decide(
                        engineRunning = false,
                        gateEnabled = true,
                        independentMode = independent,
                        syntheticPush = synthetic,
                    ),
                    "stopped engine, synthetic=$synthetic independent=$independent",
                )
            }
        }
    }

    @Test
    fun realDataIsWithheldWhileSimulating() {
        assertEquals(
            GnssDelivery.WITHHOLD,
            GnssTruthGate.decide(
                engineRunning = true,
                gateEnabled = true,
                independentMode = false,
                syntheticPush = false,
            ),
        )
    }

    @Test
    fun ourOwnSynthesizedDeliveryPassesTheGate() {
        // This is the regression: the synthetic push has to survive the gate, otherwise
        // gpsSignalSimulation=true delivers nothing at all.
        assertEquals(
            GnssDelivery.DELIVER,
            GnssTruthGate.decide(
                engineRunning = true,
                gateEnabled = true,
                independentMode = false,
                syntheticPush = true,
            ),
        )
    }

    @Test
    fun independentModeKeepsRealDataForNonWhitelistedApps() {
        // The app-facing delivery point carries no receiving package, so independent mode cannot be
        // enforced here; withholding would hide *real* GNSS from outsiders instead.
        assertEquals(
            GnssDelivery.DELIVER,
            GnssTruthGate.decide(
                engineRunning = true,
                gateEnabled = true,
                independentMode = true,
                syntheticPush = false,
            ),
        )
    }

    @Test
    fun theRuntimeEscapeHatchDisablesTheGate() {
        // persist.immoral.gnss_truth_gate=0 is the A/B switch the 高德 experiments use.
        assertEquals(
            GnssDelivery.DELIVER,
            GnssTruthGate.decide(
                engineRunning = true,
                gateEnabled = false,
                independentMode = false,
                syntheticPush = false,
            ),
        )
    }

    @Test
    fun whatTheGateLetsThroughSatisfiesCheckNine() {
        // The acceptance check asserts satellites>0, usedInFix>0 and maxCn0>0 on the status the app
        // receives. Guard that the synthesized set we now deliver passes that predicate.
        val engine = SimulationEngine(Reference.SHANGHAI, SimulationConfig(gpsSignalSimulation = true))
        val satellites = engine.satellites()
        assertTrue(satellites.isNotEmpty(), "satelliteCount>0")
        assertTrue(satellites.any { it.usedInFix }, "usedInFix>0")
        assertTrue(satellites.maxOf { it.cn0DbHz } > 0f, "maxCn0>0")
    }
}

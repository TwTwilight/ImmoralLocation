package com.immoral.location.core.engine

import com.immoral.location.core.Reference
import com.immoral.location.core.model.Satellite
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Geometry-driven DOP and the accuracy that follows from it.
 *
 * These tests assert *properties* a real receiver guarantees (a spread constellation beats a
 * clustered one, a strong signal beats a weak one, the number is continuous in time), not a
 * snapshot of the current arithmetic.
 */
class DopAccuracyTest {

    private fun satellite(
        svid: Int,
        azimuth: Float,
        elevation: Float,
        cn0: Float = 38f,
        used: Boolean = true,
        constellation: Int = SatelliteSynth.CONSTELLATION_GPS,
    ) = Satellite(
        svid = svid,
        constellation = constellation,
        cn0DbHz = cn0,
        usedInFix = used,
        elevationDeg = elevation,
        azimuthDeg = azimuth,
    )

    /** Twelve satellites spread over the whole sky, like a phone in the open. */
    private fun spreadConstellation(cn0: Float = 38f): List<Satellite> = listOf(
        satellite(1, 15f, 70f, cn0),
        satellite(2, 75f, 60f, cn0),
        satellite(3, 135f, 45f, cn0),
        satellite(4, 195f, 65f, cn0),
        satellite(5, 255f, 55f, cn0),
        satellite(6, 315f, 40f, cn0),
        satellite(7, 45f, 25f, cn0),
        satellite(8, 105f, 20f, cn0),
        satellite(9, 165f, 30f, cn0),
        satellite(10, 225f, 15f, cn0),
        satellite(11, 285f, 35f, cn0),
        satellite(12, 345f, 50f, cn0),
    )

    /** The same count of satellites, but all in one direction: localisation is impossible. */
    private fun clusteredConstellation(cn0: Float = 38f): List<Satellite> =
        (0 until 12).map { index ->
            satellite(index + 1, 90f, 30f + index * 3f, cn0)
        }

    @Test
    fun aSpreadConstellationYieldsAUsableGeometry() {
        val dop = DopGeometry.of(spreadConstellation())
        assertTrue(dop.usable, "expected a usable constellation, got $dop")
        assertTrue(dop.hdop > 0.3 && dop.hdop < 3.0, "hdop=${dop.hdop}")
        assertTrue(dop.vdop > dop.hdop * 0.8, "vertical dilution should not beat horizontal: $dop")
        assertTrue(dop.pdop >= dop.hdop, "pdop must be at least hdop: $dop")
        assertTrue(dop.pdop.isFinite() && dop.vdop.isFinite())
    }

    @Test
    fun aClusteredConstellationIsMuchWorse() {
        val spread = DopGeometry.of(spreadConstellation())
        val clustered = DopGeometry.of(clusteredConstellation())
        assertTrue(
            clustered.hdop > spread.hdop * 2.0 || !clustered.usable,
            "clustered geometry must be far worse: spread=$spread clustered=$clustered",
        )
    }

    @Test
    fun fewerThanFourSatellitesCannotProduceADop() {
        val three = spreadConstellation().take(3)
        assertTrue(!DopGeometry.of(three).usable, "three satellites must be unusable")
        assertTrue(!DopGeometry.of(emptyList()).usable)
    }

    @Test
    fun horizontalDilutionDoesNotDependOnTheAbsoluteAzimuth() {
        // Rotating the whole sky must not change how well it is spread.
        val original = DopGeometry.of(spreadConstellation())
        val rotated = DopGeometry.of(
            spreadConstellation().map {
                it.copy(azimuthDeg = (it.azimuthDeg + 137f) % 360f)
            },
        )
        assertTrue(
            abs(original.hdop - rotated.hdop) < 0.01,
            "hdop must be invariant under rotation: ${original.hdop} vs ${rotated.hdop}",
        )
    }

    @Test
    fun accuracyImprovesWithSignalStrength() {
        val position = Reference.BEIJING
        val now = 1_700_000_000_000L
        val strong = AccuracySynth.accuracyMeters(position, spreadConstellation(cn0 = 46f), now)
        val weak = AccuracySynth.accuracyMeters(position, spreadConstellation(cn0 = 26f), now)
        assertTrue(strong < weak, "a stronger constellation must report a better accuracy: $strong vs $weak")
    }

    @Test
    fun accuracyFollowsTheGeometry() {
        val position = Reference.SHANGHAI
        val now = 1_700_000_123_000L
        val spread = AccuracySynth.accuracyMeters(position, spreadConstellation(), now)
        val clustered = AccuracySynth.accuracyMeters(position, clusteredConstellation(), now)
        assertTrue(clustered > spread, "clustered geometry must report a worse accuracy: $clustered vs $spread")
    }

    @Test
    fun accuracyStaysInsideTheDocumentedRange() {
        val now = 1_700_000_000_000L
        for (position in listOf(Reference.BEIJING, Reference.SHANGHAI, Reference.NEW_YORK)) {
            for (cn0 in listOf(18f, 26f, 38f, 48f)) {
                val accuracy = AccuracySynth.accuracyMeters(position, spreadConstellation(cn0), now)
                assertTrue(
                    accuracy >= AccuracySynth.MIN_ACCURACY_M && accuracy <= AccuracySynth.MAX_ACCURACY_M,
                    "accuracy $accuracy out of range at $position",
                )
            }
            val withoutSatellites = AccuracySynth.accuracyMeters(position, emptyList(), now)
            assertTrue(withoutSatellites in AccuracySynth.MIN_ACCURACY_M..AccuracySynth.MAX_ACCURACY_M)
        }
    }

    @Test
    fun accuracyIsContinuousInTime() {
        val position = Reference.BEIJING
        val start = 1_700_000_000_000L
        var previous = AccuracySynth.accuracyMeters(position, spreadConstellation(), start)
        var maxStep = 0.0
        for (second in 1..600) {
            val next = AccuracySynth.accuracyMeters(position, spreadConstellation(), start + second * 1_000L)
            maxStep = maxOf(maxStep, abs(next - previous))
            previous = next
        }
        assertTrue(
            maxStep <= AccuracySynth.MAX_STEP_METERS,
            "accuracy jumped by $maxStep m between consecutive seconds",
        )
    }

    @Test
    fun accuracyActuallyMovesOverTime() {
        val position = Reference.BEIJING
        val start = 1_700_000_000_000L
        val samples = (0 until 120).map {
            AccuracySynth.accuracyMeters(position, spreadConstellation(), start + it * 1_000L)
        }
        assertNotEquals(
            1,
            samples.toSet().size,
            "a constant accuracy is exactly the signature this model exists to remove",
        )
        val spread = samples.max() - samples.min()
        assertTrue(spread > 0.3, "the wander should be visible over two minutes, was $spread m")
    }

    @Test
    fun accuracyIsDeterministic() {
        val position = Reference.NEW_YORK
        val now = 1_700_000_555_000L
        val first = AccuracySynth.accuracyMeters(position, spreadConstellation(), now)
        val second = AccuracySynth.accuracyMeters(position, spreadConstellation(), now)
        assertTrue(first == second, "the same inputs must produce the same accuracy")
    }

    @Test
    fun providerAccuraciesAreOrderedLikeTheRealOnes() {
        for (gps in listOf(2.0, 5.0, 12.0, 30.0)) {
            val network = AccuracySynth.networkAccuracyMeters(gps)
            val fused = AccuracySynth.fusedAccuracyMeters(gps)
            assertTrue(network >= gps, "network fix ($network) cannot beat GNSS ($gps)")
            assertTrue(fused >= gps * 0.9, "fused fix ($fused) should not be better than GNSS ($gps)")
            assertTrue(network <= 120.0 && fused <= 50.0)
        }
        // A great GNSS fix still leaves the network provider coarse.
        assertTrue(AccuracySynth.networkAccuracyMeters(2.0) >= 15.0)
    }

    @Test
    fun jitterWidensTheReportedError() {
        val position = Reference.BEIJING
        val now = 1_700_000_000_000L
        val tight = AccuracySynth.accuracyMeters(position, spreadConstellation(), now, jitterMeters = 0.0)
        val loose = AccuracySynth.accuracyMeters(position, spreadConstellation(), now, jitterMeters = 12.0)
        assertTrue(loose > tight, "12 m of position jitter must widen the reported accuracy: $loose vs $tight")
    }
}

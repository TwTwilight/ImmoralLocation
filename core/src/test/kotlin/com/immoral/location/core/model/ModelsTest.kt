package com.immoral.location.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Model defaults, speed table and configuration sanitization. */
class ModelsTest {
    @Test
    fun geoPointDefaultsToZero() {
        assertEquals(GeoPoint.ZERO, GeoPoint())
        assertEquals(0.0, GeoPoint.ZERO.lat)
        assertEquals(0.0, GeoPoint.ZERO.lon)
        assertEquals(50.0, GeoPoint.ZERO.alt)
    }

    @Test
    fun geoPointValidityChecksRanges() {
        assertTrue(GeoPoint(39.9, 116.4).isValidLatLon())
        assertTrue(GeoPoint(-90.0, 180.0).isValidLatLon())
        assertTrue(GeoPoint(90.0, -180.0).isValidLatLon())
        assertFalse(GeoPoint(90.1, 0.0).isValidLatLon())
        assertFalse(GeoPoint(-90.1, 0.0).isValidLatLon())
        assertFalse(GeoPoint(0.0, 180.1).isValidLatLon())
        assertFalse(GeoPoint(0.0, -180.1).isValidLatLon())
        assertFalse(GeoPoint(Double.NaN, 0.0).isValidLatLon())
        assertFalse(GeoPoint(0.0, Double.POSITIVE_INFINITY).isValidLatLon())
    }

    @Test
    fun nullIslandIsValidCoordinatesButNotAValidTarget() {
        assertTrue(GeoPoint.ZERO.isValidLatLon())
        assertTrue(GeoPoint.ZERO.isZero())
        assertFalse(GeoPoint.ZERO.isValid())
        assertTrue(GeoPoint(0.0, 0.5).isValid())
    }

    @Test
    fun speedLevelCarriesTheDocumentedDefaults() {
        assertEquals(1.4, SpeedLevel.WALK.defaultMetersPerSecond)
        assertEquals(3.3, SpeedLevel.RUN.defaultMetersPerSecond)
        assertEquals(5.5, SpeedLevel.BIKE.defaultMetersPerSecond)
        assertEquals(13.9, SpeedLevel.DRIVE.defaultMetersPerSecond)
        assertEquals(230.0, SpeedLevel.PLANE.defaultMetersPerSecond)
        assertEquals(1.4, SpeedLevel.CUSTOM.defaultMetersPerSecond)
        assertEquals(6, SpeedLevel.entries.size)
    }

    @Test
    fun speedLevelFromNameIsCaseInsensitiveAndForgiving() {
        assertEquals(SpeedLevel.RUN, SpeedLevel.fromName("run"))
        assertEquals(SpeedLevel.PLANE, SpeedLevel.fromName("  Plane "))
        assertEquals(SpeedLevel.WALK, SpeedLevel.fromName(null))
        assertEquals(SpeedLevel.WALK, SpeedLevel.fromName(""))
        assertEquals(SpeedLevel.WALK, SpeedLevel.fromName("teleport"))
    }

    @Test
    fun effectiveSpeedUsesThePresetForEveryLevel() {
        assertEquals(1.4, SimulationConfig(speed = SpeedLevel.WALK).effectiveSpeedMps())
        assertEquals(3.3, SimulationConfig(speed = SpeedLevel.RUN).effectiveSpeedMps())
        assertEquals(5.5, SimulationConfig(speed = SpeedLevel.BIKE).effectiveSpeedMps())
        assertEquals(13.9, SimulationConfig(speed = SpeedLevel.DRIVE).effectiveSpeedMps())
        assertEquals(230.0, SimulationConfig(speed = SpeedLevel.PLANE).effectiveSpeedMps())
    }

    @Test
    fun effectiveSpeedUsesCustomValueOnlyForCustomLevel() {
        assertEquals(
            7.5,
            SimulationConfig(speed = SpeedLevel.CUSTOM, customSpeedMps = 7.5).effectiveSpeedMps(),
        )
        assertEquals(
            1.4,
            SimulationConfig(speed = SpeedLevel.WALK, customSpeedMps = 7.5).effectiveSpeedMps(),
        )
    }

    @Test
    fun defaultConfigMatchesTheContract() {
        val config = SimulationConfig()
        assertEquals(MoveMode.STATIC, config.mode)
        assertEquals(SpeedLevel.WALK, config.speed)
        assertEquals(1.4, config.customSpeedMps)
        // North-up joystick by default: "push up" must move north unless the user opts in.
        assertFalse(config.followHeading)
        assertEquals(0.0, config.fixedBearingDeg)
        assertEquals(1_000L, config.updateIntervalMs)
        assertEquals(120, config.stepCadenceBpm)
        assertEquals(2.0, config.routeSpeedMps)
        assertFalse(config.independentMode)
        assertTrue(config.independentPackages.isEmpty())
    }

    @Test
    fun sanitizeClampsTheUpdateInterval() {
        assertEquals(
            SimulationConfig.MIN_INTERVAL_MS,
            SimulationConfig(updateIntervalMs = 1L).sanitized().updateIntervalMs,
        )
        assertEquals(
            SimulationConfig.MAX_INTERVAL_MS,
            SimulationConfig(updateIntervalMs = 99_999L).sanitized().updateIntervalMs,
        )
        assertEquals(
            500L,
            SimulationConfig(updateIntervalMs = 500L).sanitized().updateIntervalMs,
        )
    }

    @Test
    fun sanitizeClampsTheStepCadence() {
        assertEquals(60, SimulationConfig(stepCadenceBpm = 0).sanitized().stepCadenceBpm)
        assertEquals(240, SimulationConfig(stepCadenceBpm = 1_000).sanitized().stepCadenceBpm)
        assertEquals(180, SimulationConfig(stepCadenceBpm = 180).sanitized().stepCadenceBpm)
    }

    @Test
    fun sanitizeClampsTheCustomSpeed() {
        assertEquals(
            0.1,
            SimulationConfig(customSpeedMps = 0.0).sanitized().customSpeedMps,
        )
        assertEquals(
            0.1,
            SimulationConfig(customSpeedMps = -20.0).sanitized().customSpeedMps,
        )
        assertEquals(
            1_000.0,
            SimulationConfig(customSpeedMps = 5_000.0).sanitized().customSpeedMps,
        )
    }

    @Test
    fun sanitizeClampsTheRouteSpeed() {
        assertEquals(0.1, SimulationConfig(routeSpeedMps = 0.0).sanitized().routeSpeedMps)
        assertEquals(1_000.0, SimulationConfig(routeSpeedMps = 9_999.0).sanitized().routeSpeedMps)
        assertEquals(3.5, SimulationConfig(routeSpeedMps = 3.5).sanitized().routeSpeedMps)
    }

    @Test
    fun sanitizeClampsJitter() {
        val config = SimulationConfig(gpsJitterMeters = -5.0, altitudeJitterMeters = 500.0)
            .sanitized()
        assertEquals(0.0, config.gpsJitterMeters)
        assertEquals(SimulationConfig.MAX_JITTER_METERS, config.altitudeJitterMeters)
    }

    @Test
    fun sanitizeNormalizesTheFixedBearing() {
        assertEquals(350.0, SimulationConfig(fixedBearingDeg = -10.0).sanitized().fixedBearingDeg)
        assertEquals(0.0, SimulationConfig(fixedBearingDeg = 720.0).sanitized().fixedBearingDeg)
        assertEquals(45.0, SimulationConfig(fixedBearingDeg = 45.0).sanitized().fixedBearingDeg)
    }

    @Test
    fun sanitizeReplacesNonFiniteNumbersWithDefaults() {
        val config = SimulationConfig(
            customSpeedMps = Double.NaN,
            routeSpeedMps = Double.NEGATIVE_INFINITY,
            gpsJitterMeters = Double.NaN,
            altitudeJitterMeters = Double.POSITIVE_INFINITY,
            fixedBearingDeg = Double.NaN,
        ).sanitized()
        assertEquals(1.4, config.customSpeedMps)
        assertEquals(2.0, config.routeSpeedMps)
        assertEquals(0.0, config.gpsJitterMeters)
        assertEquals(0.0, config.altitudeJitterMeters)
        assertEquals(0.0, config.fixedBearingDeg)
    }

    @Test
    fun sanitizeTrimsIndependentPackages() {
        val config = SimulationConfig(
            independentMode = true,
            independentPackages = setOf(" com.example.app ", "", "  ", "com.other.app"),
        ).sanitized()
        assertEquals(setOf("com.example.app", "com.other.app"), config.independentPackages)
    }

    @Test
    fun sanitizeIsIdempotentAndKeepsValidValues() {
        val original = SimulationConfig(
            mode = MoveMode.ROUTE,
            speed = SpeedLevel.BIKE,
            customSpeedMps = 9.0,
            updateIntervalMs = 500L,
            stepCadenceBpm = 90,
            gpsJitterMeters = 3.0,
            routeSpeedMps = 4.0,
            independentPackages = setOf("com.a", "com.b"),
        )
        val once = original.sanitized()
        assertEquals(original, once)
        assertEquals(once, once.sanitized())
    }

    @Test
    fun engineStateDefaultsAreEmpty() {
        val state = EngineState()
        assertFalse(state.running)
        assertEquals(MoveMode.STATIC, state.mode)
        assertEquals(RouteState.IDLE, state.routeState)
        assertEquals(GeoPoint.ZERO, state.point)
        assertEquals(0L, state.steps)
        assertEquals(0.0, state.routeProgress)
        assertTrue(state.activePackages.isEmpty())
    }

    @Test
    fun satelliteAndRadioModelsKeepTheirDefaults() {
        val cell = SimulatedCell()
        assertEquals("LTE", cell.type)
        assertEquals("460", cell.mcc)
        assertEquals("00", cell.mnc)
        assertEquals(3, cell.level)
        assertEquals(-75, cell.dbm)
        assertTrue(cell.registered)

        val ap = SimulatedAp(ssid = "x", bssid = "02:00:00:00:00:00")
        assertEquals(-60, ap.level)
        assertEquals(2412, ap.frequencyMhz)

        val satellite = Satellite(
            svid = 3,
            constellation = 1,
            cn0DbHz = 30f,
            usedInFix = true,
            elevationDeg = 45f,
            azimuthDeg = 90f,
        )
        assertTrue(satellite.usedInFix)
    }
}

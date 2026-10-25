package com.immoral.location.core.geo

import com.immoral.location.core.model.GeoPoint
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spherical-earth geodesy helpers.
 *
 * Everything here is a pure function of its arguments: no state, no randomness, no Android
 * dependencies. Distances use the haversine formula on a sphere of [EARTH_RADIUS_M]; the error
 * against a WGS-84 ellipsoid reference is well below 0.5 % over city-scale distances, which is
 * far inside the accuracy any location consumer (or anti-spoof check) can observe.
 */
object GeoMath {
    /** Mean earth radius used by the haversine model, in meters. */
    const val EARTH_RADIUS_M: Double = 6_371_008.8

    private const val DEG_TO_RAD = Math.PI / 180.0
    private const val RAD_TO_DEG = 180.0 / Math.PI

    /**
     * Great-circle distance between [a] and [b] in meters (haversine formula).
     *
     * @return distance in meters, always `>= 0`; `0.0` for identical points.
     */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = a.lat * DEG_TO_RAD
        val lat2 = b.lat * DEG_TO_RAD
        val dLat = lat2 - lat1
        val dLon = (b.lon - a.lon) * DEG_TO_RAD
        val sinLat = sin(dLat / 2.0)
        val sinLon = sin(dLon / 2.0)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        return 2.0 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * Initial bearing when travelling from [a] to [b], in degrees clockwise from true north.
     *
     * @return bearing normalized into `[0, 360)`.
     */
    fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = a.lat * DEG_TO_RAD
        val lat2 = b.lat * DEG_TO_RAD
        val dLon = (b.lon - a.lon) * DEG_TO_RAD
        if (lat1 == lat2 && dLon == 0.0) return 0.0
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalizeBearing(atan2(y, x) * RAD_TO_DEG)
    }

    /**
     * Point reached by travelling [distanceM] meters from [start] along [bearingDeg].
     *
     * Altitude is carried over from [start]. Negative distances move backwards.
     */
    fun destination(start: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        if (distanceM == 0.0) return start
        val angular = distanceM / EARTH_RADIUS_M
        val bearing = bearingDeg * DEG_TO_RAD
        val lat1 = start.lat * DEG_TO_RAD
        val lon1 = start.lon * DEG_TO_RAD
        val sinLat2 = sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing)
        val lat2 = asin(sinLat2.coerceIn(-1.0, 1.0))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sinLat2,
        )
        return GeoPoint(
            lat = lat2 * RAD_TO_DEG,
            lon = normalizeLongitude(lon2 * RAD_TO_DEG),
            alt = start.alt,
        )
    }

    /** Wraps a bearing into `[0, 360)`; non-finite input maps to `0.0`. */
    fun normalizeBearing(deg: Double): Double {
        if (!deg.isFinite()) return 0.0
        val wrapped = deg % 360.0
        return if (wrapped < 0.0) wrapped + 360.0 else wrapped
    }

    /** Wraps a longitude into `(-180, 180]`; non-finite input maps to `0.0`. */
    fun normalizeLongitude(deg: Double): Double {
        if (!deg.isFinite()) return 0.0
        var wrapped = deg % 360.0
        if (wrapped > 180.0) wrapped -= 360.0
        if (wrapped < -180.0) wrapped += 360.0
        return wrapped
    }

    /**
     * Linear interpolation between [a] and [b], where [fraction] `0` is [a] and `1` is [b].
     *
     * Longitude interpolation takes the short way around the antimeridian. [fraction] is not
     * clamped so callers may extrapolate; altitude is interpolated linearly too.
     */
    fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint {
        val lat = a.lat + (b.lat - a.lat) * fraction
        var dLon = b.lon - a.lon
        if (dLon > 180.0) dLon -= 360.0
        if (dLon < -180.0) dLon += 360.0
        val lon = normalizeLongitude(a.lon + dLon * fraction)
        val alt = a.alt + (b.alt - a.alt) * fraction
        return GeoPoint(lat = lat, lon = lon, alt = alt)
    }

    /**
     * Shortest signed rotation needed to turn from [fromDeg] to [toDeg].
     *
     * @return value in `[-180, 180]`; positive means clockwise (to the right).
     */
    fun shortestAngleDelta(fromDeg: Double, toDeg: Double): Double {
        if (!fromDeg.isFinite() || !toDeg.isFinite()) return 0.0
        var delta = (toDeg - fromDeg) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        // `-0.0` would leak into equality checks such as assertEquals(0.0, ...).
        return if (abs(delta) < 1e-12) 0.0 else delta
    }

    /**
     * Compass sector name for a bearing, useful for UI labels.
     *
     * @return one of `N, NE, E, SE, S, SW, W, NW`.
     */
    fun compassPoint(bearingDeg: Double): String {
        val labels = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = (((normalizeBearing(bearingDeg) + 22.5) / 45.0).toInt()) % 8
        return labels[index]
    }

    /**
     * Great-circle distance via the spherical law of cosines, used by tests as an independent
     * cross-check of [distanceMeters].
     */
    internal fun distanceMetersLawOfCosines(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = a.lat * DEG_TO_RAD
        val lat2 = b.lat * DEG_TO_RAD
        val dLon = (b.lon - a.lon) * DEG_TO_RAD
        val cosC = sin(lat1) * sin(lat2) + cos(lat1) * cos(lat2) * cos(dLon)
        return acos(cosC.coerceIn(-1.0, 1.0)) * EARTH_RADIUS_M
    }
}

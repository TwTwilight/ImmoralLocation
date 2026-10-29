package com.immoral.location.map

import com.immoral.location.core.model.GeoPoint

/**
 * WGS-84 ⇄ GCJ-02 ("火星坐标") conversion.
 *
 * Chinese raster tile providers (高德/腾讯) publish their maps in GCJ-02, while the simulation
 * engine and every consumer app work in WGS-84. Rendering a WGS-84 coordinate on a GCJ-02 tile
 * puts the marker 300–500 m off, and a tap on such a tile has to be converted back before it is
 * handed to `SetPosition`.
 *
 * The algorithm is the well-known public-domain offset transform (the same numbers used by every
 * open-source implementation); the inverse is iterative because the forward map is not analytic.
 * Error of the inverse after the fixed iteration count is well below 1 m.
 */
object Gcj02 {

    /** Krasovsky 1940 semi-major axis used by the GCJ-02 polynomial. */
    private const val A = 6_378_245.0

    /** Krasovsky 1940 eccentricity squared. */
    private const val EE = 0.006_693_421_622_965_943

    /** Points outside China are never offset by the GCJ transform. */
    private const val MIN_LAT = 3.86
    private const val MAX_LAT = 53.55
    private const val MIN_LON = 73.66
    private const val MAX_LON = 135.05

    /** `true` when the point is inside the "China bounding box" the transform applies to. */
    fun inChina(lat: Double, lon: Double): Boolean =
        lat in MIN_LAT..MAX_LAT && lon in MIN_LON..MAX_LON

    /** Forward transform: a WGS-84 point as it appears on a GCJ-02 map. */
    fun fromWgs84(point: GeoPoint): GeoPoint {
        if (!point.isValidLatLon() || !inChina(point.lat, point.lon)) return point
        val (lat, lon) = wgs84ToGcj02(point.lat, point.lon)
        return GeoPoint(lat, lon, point.alt)
    }

    /** Inverse transform: a GCJ-02 map coordinate expressed in WGS-84. */
    fun toWgs84(point: GeoPoint): GeoPoint {
        if (!point.isValidLatLon() || !inChina(point.lat, point.lon)) return point
        val (lat, lon) = gcj02ToWgs84(point.lat, point.lon)
        return GeoPoint(lat, lon, point.alt)
    }

    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (!inChina(lat, lon)) return lat to lon
        val dLat = transformLat(lon - 105.0, lat - 35.0)
        val dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = Math.sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        val outLat = lat + (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        val outLon = lon + (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI)
        return outLat to outLon
    }

    fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        if (!inChina(lat, lon)) return lat to lon
        var wgsLat = lat
        var wgsLon = lon
        var dLat: Double
        var dLon: Double
        for (i in 0 until MAX_ITERATIONS) {
            val (testLat, testLon) = wgs84ToGcj02(wgsLat, wgsLon)
            dLat = testLat - lat
            dLon = testLon - lon
            if (Math.abs(dLat) < EPSILON && Math.abs(dLon) < EPSILON) break
            wgsLat -= dLat
            wgsLon -= dLon
        }
        return wgsLat to wgsLon
    }

    /** Iterations needed for sub-millimetre convergence; the loop exits early when it can. */
    private const val MAX_ITERATIONS = 12

    /** ~1e-9 degrees is ~0.1 mm — far below anything a GPS fix can resolve. */
    private const val EPSILON = 1e-9

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}

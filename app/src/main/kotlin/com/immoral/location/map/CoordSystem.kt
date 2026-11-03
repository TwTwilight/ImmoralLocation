package com.immoral.location.map

import com.immoral.location.core.model.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The coordinate systems a user is likely to paste a coordinate from.
 *
 * Everything inside the app is WGS-84; a coordinate copied out of 高德 / 腾讯 is GCJ-02 and one out
 * of 百度 is BD-09. Feeding those to the engine unconverted puts the simulated fix 300-700 m away
 * from where the user picked it, which is the single most common "定位偏了" complaint.
 */
enum class CoordSystem {
    WGS84,
    GCJ02,
    BD09,
    ;

    /** Converts a point typed in this system into WGS-84. */
    fun toWgs84(point: GeoPoint): GeoPoint = when (this) {
        WGS84 -> point
        GCJ02 -> Gcj02.toWgs84(point)
        BD09 -> Gcj02.toWgs84(bd09ToGcj02(point))
    }

    companion object {
        private const val X_PI = Math.PI * 3000.0 / 180.0

        fun bd09ToGcj02(point: GeoPoint): GeoPoint {
            val x = point.lon - 0.0065
            val y = point.lat - 0.006
            val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
            val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
            return GeoPoint(lat = z * sin(theta), lon = z * cos(theta), alt = point.alt)
        }
    }
}

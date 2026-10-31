package com.immoral.location.map

import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the WGS-84 ⇄ GCJ-02 transform.
 *
 * Run with `.\gradlew.bat :app:testDebugUnitTest`. Acceptance criteria from the brief: round-trip
 * error < 1 m, known reference pairs, and identity outside China.
 */
class Gcj02Test {

    private fun meters(a: GeoPoint, b: GeoPoint) = GeoMath.distanceMeters(a, b)

    /** JUnit4 puts the message first; this keeps the assertions readable. */
    private fun check(condition: Boolean, message: String) = assertTrue(message, condition)

    @Test
    fun forwardOffsetIsHundredsOfMetersInChina() {
        val wgs = GeoPoint(39.908_692, 116.397_532) // Tiananmen
        val gcj = Gcj02.fromWgs84(wgs)
        val offset = meters(wgs, gcj)
        check(offset > 200.0, "offset too small: $offset m")
        check(offset < 900.0, "offset too large: $offset m")
    }

    @Test
    fun roundTripIsWithinOneMeter() {
        val points = listOf(
            GeoPoint(39.908_692, 116.397_532), // 北京
            GeoPoint(31.230_416, 121.473_701), // 上海
            GeoPoint(23.129_163, 113.264_385), // 广州
            GeoPoint(30.658_601, 104.064_857), // 成都
            GeoPoint(22.542_928, 114.059_560), // 深圳
        )
        for (point in points) {
            val gcj = Gcj02.fromWgs84(point)
            val back = Gcj02.toWgs84(gcj)
            val error = meters(point, back)
            check(error < 1.0, "round-trip error $error m at $point")
        }
    }

    @Test
    fun inverseMatchesKnownReference() {
        // Widely used reference pair for validating GCJ implementations (Tiananmen area).
        val gcj = Gcj02.fromWgs84(GeoPoint(39.908_692, 116.397_532))
        check(abs(gcj.lat - 39.910084) < 0.002, "lat ${gcj.lat}")
        check(abs(gcj.lon - 116.403866) < 0.002, "lon ${gcj.lon}")

        val wgs = Gcj02.toWgs84(GeoPoint(39.910084, 116.403866))
        check(abs(wgs.lat - 39.908_692) < 0.0005, "inverse lat ${wgs.lat}")
        check(abs(wgs.lon - 116.397_532) < 0.0005, "inverse lon ${wgs.lon}")
    }

    @Test
    fun outsideChinaIsIdentity() {
        val tokyo = GeoPoint(35.681_236, 139.767_124)
        val london = GeoPoint(51.500_729, -0.124_626)
        for (point in listOf(tokyo, london)) {
            check(
                meters(point, Gcj02.fromWgs84(point)) < 0.001,
                "forward changed an out-of-China point",
            )
            check(
                meters(point, Gcj02.toWgs84(point)) < 0.001,
                "inverse changed an out-of-China point",
            )
        }
    }

    @Test
    fun altitudeIsPreserved() {
        val point = GeoPoint(31.230_416, 121.473_701, 123.5)
        check(Gcj02.fromWgs84(point).alt == 123.5, "forward lost the altitude")
        check(Gcj02.toWgs84(point).alt == 123.5, "inverse lost the altitude")
    }
}

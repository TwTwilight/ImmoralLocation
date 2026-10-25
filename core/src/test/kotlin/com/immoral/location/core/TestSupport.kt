package com.immoral.location.core

import com.immoral.location.core.model.GeoPoint
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shared helpers for the `:core` test-suite.
 *
 * The assertions here deliberately re-implement small bits of math (checksum, degree math)
 * instead of calling the production code, so a bug in the implementation cannot mask itself.
 */

/** Absolute tolerance used for "is roughly equal" assertions. */
const val EPS = 1e-9

/** Fails unless [actual] is within [tolerance] of [expected]. */
fun assertNear(
    expected: Double,
    actual: Double,
    tolerance: Double,
    message: String? = null,
) {
    val delta = abs(expected - actual)
    assertTrue(
        delta <= tolerance,
        message ?: "expected $expected ± $tolerance but was $actual (delta $delta)",
    )
}

/** Asserts that both points describe the same location within [toleranceMeters]. */
fun assertSamePoint(expected: GeoPoint, actual: GeoPoint, toleranceMeters: Double = 0.01) {
    val distance = com.immoral.location.core.geo.GeoMath.distanceMeters(expected, actual)
    assertTrue(
        distance <= toleranceMeters,
        "points differ by $distance m: expected $expected, was $actual",
    )
}

/** XOR checksum of every character strictly between `$` and `*`, as two uppercase hex digits. */
fun referenceNmeaChecksum(sentence: String): String {
    val start = sentence.indexOf('$')
    val star = sentence.lastIndexOf('*')
    require(start in 0 until star) { "not an NMEA sentence: $sentence" }
    var checksum = 0
    for (index in start + 1 until star) {
        checksum = checksum xor sentence[index].code
    }
    return checksum.toString(16).uppercase().padStart(2, '0')
}

/** Splits an NMEA sentence into its comma separated fields, without `$` and `*hh`. */
fun nmeaFields(sentence: String): List<String> {
    val body = sentence.removePrefix("$").substringBefore('*')
    return body.split(',')
}

/** Two reference cities used across the geodesy tests. */
object Reference {
    val BEIJING = GeoPoint(lat = 39.9042, lon = 116.4074, alt = 43.5)
    val SHANGHAI = GeoPoint(lat = 31.2304, lon = 121.4737, alt = 4.0)
    val NEW_YORK = GeoPoint(lat = 40.7128, lon = -74.0060, alt = 10.0)
    val GUANGZHOU = GeoPoint(lat = 23.1291, lon = 113.2644, alt = 21.0)
}

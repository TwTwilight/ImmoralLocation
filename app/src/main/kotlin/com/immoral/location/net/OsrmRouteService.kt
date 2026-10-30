package com.immoral.location.net

import android.util.Log
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.route.RouteGeometry
import com.immoral.location.core.route.RouteLeg
import com.immoral.location.core.route.RouteProfile
import com.immoral.location.core.route.RouteRequest
import com.immoral.location.core.route.RouteResult
import com.immoral.location.core.route.RouteSource
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Outcome of one [OsrmRouteService] attempt. */
sealed interface OsrmAttempt {
    /** Road-level geometry was obtained. [result] is already densified and WGS-84. */
    data class Ok(val result: RouteResult) : OsrmAttempt

    /** No usable answer; [reason] is a short technical note for the log/snackbar. */
    data class Failed(val reason: String) : OsrmAttempt
}

/**
 * Routing through a public **OSRM** server (default `router.project-osrm.org`).
 *
 * Why OSRM is the second choice behind 高德: it needs no API key, speaks plain REST, accepts the
 * whole waypoint chain in one request (`lon,lat;lon,lat;...`), and answers with the full route
 * geometry (`overview=full&geometries=geojson`) — exactly the road-level polyline the simulation
 * needs. Its data is OpenStreetMap, which is WGS-84, so **no coordinate conversion happens here**:
 * the request points go out as-is and the answer is used as-is.
 *
 * The public instance is a courtesy service: it is rate limited and may be unreachable from
 * mainland China. A failure here is normal and simply degrades to a straight line.
 */
class OsrmRouteService(
    private val fetcher: HttpFetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Plans [request].
     *
     * Blocking — call from a background dispatcher. Never throws.
     */
    fun plan(request: RouteRequest, timeoutMs: Int = DEFAULT_TIMEOUT_MS): OsrmAttempt {
        val stops = request.stops()
        if (stops.size < 2) return OsrmAttempt.Failed("起点与终点相同")
        val url = buildUrl(stops, request.profile)
        val body = fetcher.get(url, timeoutMs)
            ?: return OsrmAttempt.Failed("OSRM 请求失败（网络不可用）")
        return parse(body, request, stops)
    }

    /** The exact request URL for [stops]; public so tests can assert its shape. */
    fun buildUrl(stops: List<GeoPoint>, profile: RouteProfile): String {
        val coordinates = stops.joinToString(";") { point ->
            String.format(Locale.US, "%.6f,%.6f", point.lon, point.lat)
        }
        return "$baseUrl/route/v1/${profile.wireName}/$coordinates" +
            "?overview=full&geometries=geojson&alternatives=false&steps=false"
    }

    private fun parse(body: String, request: RouteRequest, stops: List<GeoPoint>): OsrmAttempt {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (t: Throwable) {
            return OsrmAttempt.Failed("OSRM 返回格式异常")
        }
        val code = root["code"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
        if (code != "Ok") return OsrmAttempt.Failed("OSRM 返回 $code")
        val route = (root["routes"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return OsrmAttempt.Failed("OSRM 没有返回路线")

        val polyline = parseGeometry(route)
        if (polyline.size < 2) return OsrmAttempt.Failed("OSRM 返回的几何为空")

        val legs = buildLegs(route, stops, request.profile)
        val result = RouteGeometry.assemble(
            request = request,
            legs = legs,
            source = RouteSource.OSRM,
            polyline = polyline,
        )
        if (!result.isUsable) return OsrmAttempt.Failed("OSRM 返回的路线不可用")
        Log.i(TAG, "planned ${request.profile.id} ${result.distanceMeters} m / " +
            "${result.durationSeconds} s, ${result.polyline.size} points, ${legs.size} legs")
        return OsrmAttempt.Ok(result)
    }

    private fun parseGeometry(route: JsonObject): List<GeoPoint> {
        val coordinates = (route["geometry"] as? JsonObject)
            ?.get("coordinates") as? JsonArray
            ?: return emptyList()
        val points = ArrayList<GeoPoint>(coordinates.size)
        for (element in coordinates) {
            val pair = element as? JsonArray ?: continue
            if (pair.size < 2) continue
            // GeoJSON order is [longitude, latitude].
            val lon = pair[0].jsonPrimitive.doubleOrNull ?: continue
            val lat = pair[1].jsonPrimitive.doubleOrNull ?: continue
            val point = GeoPoint(lat, lon)
            if (point.isValidLatLon()) points.add(point)
        }
        return points
    }

    /**
     * Builds the per-leg totals.
     *
     * **Duration quirk of the public demo server**: `router.project-osrm.org` only runs the *car*
     * profile — `/route/v1/walking/…` and `/cycling/…` answer with byte-identical driving geometry
     * and driving durations (measured: 2922.4 m / 321.7 s for all three profiles, i.e. 9.1 m/s,
     * which would claim a 3 km walk takes five minutes). The geometry of a car route is still a
     * usable road-level walk/bike path, but the duration is not, so for anything other than
     * [RouteProfile.DRIVE] the duration is recomputed from the profile's own walking/riding speed.
     * Self-hosted OSRM instances that do honour the profile would also be handled correctly as
     * long as their durations are within a factor 4 of the profile speed — beyond that we assume a
     * wrong-profile answer and recompute (logged).
     */
    private fun buildLegs(
        route: JsonObject,
        stops: List<GeoPoint>,
        profile: RouteProfile,
    ): List<RouteLeg> {
        val responseLegs = (route["legs"] as? JsonArray).orEmpty()
        val legs = ArrayList<RouteLeg>(stops.size - 1)
        var recomputed = false
        for (index in 1 until stops.size) {
            val from = stops[index - 1]
            val to = stops[index]
            val reported = responseLegs.getOrNull(index - 1) as? JsonObject
            val distance = reported?.get("distance")?.jsonPrimitive?.doubleOrNull
                ?: GeoMath.distanceMeters(from, to)
            val reportedDuration = reported?.get("duration")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val duration = if (profile == RouteProfile.DRIVE) {
                reportedDuration
            } else {
                val speed = profile.defaultSpeedMps
                val implied = if (reportedDuration > 0.0) distance / reportedDuration else Double.MAX_VALUE
                if (speed > 0.0 && (implied > speed * IMPLIED_SPEED_FACTOR || reportedDuration <= 0.0)) {
                    if (implied != Double.MAX_VALUE) recomputed = true
                    distance / speed
                } else {
                    reportedDuration
                }
            }
            legs.add(
                RouteLeg(
                    from = from,
                    to = to,
                    // OSRM answers with one LineString for the whole route, so per-leg geometry
                    // stays empty; `assemble(polyline = ...)` carries the full geometry.
                    points = emptyList(),
                    distanceMeters = distance,
                    durationSeconds = duration,
                ),
            )
        }
        if (recomputed) {
            Log.i(
                TAG,
                "${profile.id}: server reported a car-profile duration, " +
                    "recomputed from ${profile.defaultSpeedMps} m/s",
            )
        }
        return legs
    }

    companion object {
        private const val TAG = "OsrmRoute"

        /** Public OSRM demo server; rate limited, no key, WGS-84 / OpenStreetMap data. */
        const val DEFAULT_BASE_URL: String = "https://router.project-osrm.org"

        /** A route request is a bounded walk, so 12 s is already generous. */
        const val DEFAULT_TIMEOUT_MS: Int = 12_000

        /**
         * How far the server's implied speed may exceed the profile default before we treat the
         * duration as belonging to another profile. 1.5× leaves headroom for traffic-aware car
         * estimates on a genuinely fast bike route, while catching the demo server's 6.5× car/walk
         * mismatch and its 1.65× car/bike mismatch.
         */
        const val IMPLIED_SPEED_FACTOR: Double = 1.5
    }
}

package com.immoral.location.core.route

import com.immoral.location.core.model.GeoPoint
import kotlinx.serialization.Serializable

/**
 * A planned route the user decided to keep.
 *
 * Only the *stops* are stored, not the geometry: geometry is a few thousand points and depends on
 * the backend that happened to answer, while the stops are what the user composed. Re-opening a
 * saved route re-plans it, which also means a saved route keeps working when the routing backend
 * changes between sessions.
 *
 * @property id Stable id (creation timestamp based), used as the list key.
 * @property name Display name, e.g. `王府井 → 北海公园`.
 * @property origin Start point (WGS-84).
 * @property destination End point (WGS-84).
 * @property waypoints Intermediate stops in travel order.
 * @property profile Travel mode the route was saved with.
 * @property distanceMeters Distance of the plan at save time, for the list subtitle.
 * @property durationSeconds Duration of the plan at save time, for the list subtitle.
 * @property createdAtMs Wall-clock creation time.
 */
@Serializable
data class SavedRoute(
    val id: String,
    val name: String,
    val origin: GeoPoint,
    val destination: GeoPoint,
    val waypoints: List<GeoPoint> = emptyList(),
    val profile: RouteProfile = RouteProfile.WALK,
    val distanceMeters: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val createdAtMs: Long = 0L,
) {
    /** The request this route would re-plan into. */
    fun toRequest(): RouteRequest = RouteRequest(
        origin = origin,
        destination = destination,
        waypoints = waypoints,
        profile = profile,
    )

    companion object {
        /**
         * Builds a saveable route from a finished [result].
         *
         * @param name Display name; blank falls back to `起点 → 终点`.
         */
        fun from(
            request: RouteRequest,
            result: RouteResult?,
            name: String,
            nowMs: Long,
        ): SavedRoute = SavedRoute(
            id = "route-$nowMs",
            name = name.trim().ifBlank { defaultName(request) },
            origin = request.origin,
            destination = request.destination,
            waypoints = request.validWaypoints,
            profile = request.profile,
            distanceMeters = result?.distanceMeters ?: 0.0,
            durationSeconds = result?.durationSeconds ?: 0.0,
            createdAtMs = nowMs,
        )

        /** `起点 39.9087,116.3975 → 终点 39.9254,116.3831` — used when the user gives no name. */
        fun defaultName(request: RouteRequest): String =
            "${formatCoordinate(request.origin)} → ${formatCoordinate(request.destination)}"

        private fun formatCoordinate(point: GeoPoint): String =
            String.format(java.util.Locale.US, "%.4f,%.4f", point.lat, point.lon)
    }
}

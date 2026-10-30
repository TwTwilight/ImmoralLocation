package com.immoral.location.net

import android.content.Context
import android.util.Log
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.DrivePath
import com.amap.api.services.route.DriveRouteResult
import com.amap.api.services.route.DriveStep
import com.amap.api.services.route.RidePath
import com.amap.api.services.route.RideRouteResult
import com.amap.api.services.route.RideStep
import com.amap.api.services.route.RouteSearch
import com.amap.api.services.route.RouteSearch.DriveRouteQuery
import com.amap.api.services.route.RouteSearch.RideRouteQuery
import com.amap.api.services.route.RouteSearch.WalkRouteQuery
import com.amap.api.services.route.WalkPath
import com.amap.api.services.route.WalkRouteResult
import com.amap.api.services.route.WalkStep
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.route.RouteLeg
import com.immoral.location.core.route.RouteProfile
import com.immoral.location.core.route.RouteRequest
import com.immoral.location.map.Gcj02
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Outcome of one [AmapRouteService] attempt. */
sealed interface AmapAttempt {
    /** Per-leg road geometry (already converted to WGS-84). */
    data class Ok(val legs: List<RouteLeg>) : AmapAttempt

    /** The AMap key was rejected; rotating the identity pool and retrying can help. */
    data class Unauthorized(val code: Int) : AmapAttempt

    /** Any other failure, with a short technical reason. */
    data class Failed(val reason: String) : AmapAttempt
}

/**
 * Routing through the bundled **高德 (AMap) services SDK** — `RouteSearch`.
 *
 * **Coordinate convention.** This is the only place in the routing stack that speaks GCJ-02:
 * the SDK expects and returns 火星坐标 (GCJ-02), so every request point is converted forward with
 * [Gcj02.fromWgs84] and every returned vertex is converted back with [Gcj02.toWgs84]. Everything
 * that leaves this class is WGS-84, matching `MapBackend`, the engine and `SettingsStore`.
 * Outside the China bounding box the transform is the identity, so foreign coordinates pass
 * through untouched.
 *
 * **Waypoints.** The SDK's walking and riding queries take a single origin/destination pair (no
 * `passedByPoints`), so the route is planned **leg by leg** and the legs are concatenated. That
 * keeps walk / bike / drive identical in behaviour and gives per-leg distance and duration for
 * free. Drive legs use the same strategy on purpose: it keeps the geometry continuous and the
 * totals comparable across modes.
 *
 * **Authentication.** AMap rejects requests from unknown package/signature pairs with a numeric
 * code. The app ships a rotating identity pool (`AmapPoiSearch.rotateIdentity`), and this class
 * reports those codes as [AmapAttempt.Unauthorized] so [RouteClient] can rotate and retry — the
 * same pattern the POI search relies on.
 */
class AmapRouteService(
    context: Context,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    private val appContext: Context = context.applicationContext

    /**
     * Plans [request] leg by leg.
     *
     * Blocking — call from a background dispatcher. Never throws.
     */
    fun plan(request: RouteRequest): AmapAttempt {
        val stops = request.stops()
        if (stops.size < 2) return AmapAttempt.Failed("起点与终点相同")
        val legs = ArrayList<RouteLeg>(stops.size - 1)
        for (index in 1 until stops.size) {
            val from = stops[index - 1]
            val to = stops[index]
            when (val attempt = searchLeg(from, to, request.profile)) {
                is LegAttempt.Ok -> legs.add(attempt.leg)
                is LegAttempt.Unauthorized -> return AmapAttempt.Unauthorized(attempt.code)
                is LegAttempt.Failed -> return AmapAttempt.Failed(attempt.reason)
            }
        }
        if (legs.isEmpty()) return AmapAttempt.Failed("高德没有返回路径")
        Log.i(TAG, "planned ${request.profile.id}: ${legs.size} leg(s), " +
            "${legs.sumOf { it.distanceMeters }} m")
        return AmapAttempt.Ok(legs)
    }

    private sealed interface LegAttempt {
        data class Ok(val leg: RouteLeg) : LegAttempt
        data class Unauthorized(val code: Int) : LegAttempt
        data class Failed(val reason: String) : LegAttempt
    }

    private fun searchLeg(from: GeoPoint, to: GeoPoint, profile: RouteProfile): LegAttempt {
        val origin = toGcj(from)
        val destination = toGcj(to)
        val latch = CountDownLatch(1)
        val result = AtomicReference<LegAttempt?>(null)
        val search = try {
            RouteSearch(appContext)
        } catch (t: Throwable) {
            return LegAttempt.Failed("高德路径服务初始化失败: ${t.message}")
        }
        search.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
            override fun onBusRouteSearched(
                busRouteResult: com.amap.api.services.route.BusRouteResult?,
                rCode: Int,
            ) = Unit

            override fun onWalkRouteSearched(walkRouteResult: WalkRouteResult?, rCode: Int) {
                result.set(
                    if (rCode != AMapException.CODE_AMAP_SUCCESS) {
                        failure(rCode)
                    } else {
                        walkRouteResult?.paths?.firstOrNull()?.toLeg(from, to, profile)
                            ?: LegAttempt.Failed("高德未返回步行路径")
                    },
                )
                latch.countDown()
            }

            override fun onRideRouteSearched(rideRouteResult: RideRouteResult?, rCode: Int) {
                result.set(
                    if (rCode != AMapException.CODE_AMAP_SUCCESS) {
                        failure(rCode)
                    } else {
                        rideRouteResult?.paths?.firstOrNull()?.toLeg(from, to, profile)
                            ?: LegAttempt.Failed("高德未返回骑行路径")
                    },
                )
                latch.countDown()
            }

            override fun onDriveRouteSearched(driveRouteResult: DriveRouteResult?, rCode: Int) {
                result.set(
                    if (rCode != AMapException.CODE_AMAP_SUCCESS) {
                        failure(rCode)
                    } else {
                        driveRouteResult?.paths?.firstOrNull()?.toLeg(from, to, profile)
                            ?: LegAttempt.Failed("高德未返回驾车路径")
                    },
                )
                latch.countDown()
            }
        })
        try {
            when (profile) {
                RouteProfile.WALK -> search.calculateWalkRouteAsyn(
                    WalkRouteQuery(RouteSearch.FromAndTo(origin, destination)),
                )
                RouteProfile.BIKE -> search.calculateRideRouteAsyn(
                    RideRouteQuery(RouteSearch.FromAndTo(origin, destination)),
                )
                RouteProfile.DRIVE -> search.calculateDriveRouteAsyn(
                    DriveRouteQuery(
                        RouteSearch.FromAndTo(origin, destination),
                        RouteSearch.DRIVING_SINGLE_DEFAULT,
                        emptyList<LatLonPoint>(),
                        emptyList<List<LatLonPoint>>(),
                        null,
                    ),
                )
            }
        } catch (t: Throwable) {
            return LegAttempt.Failed("高德路径请求失败: ${t.message}")
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return LegAttempt.Failed("高德路径计算超时")
        }
        return result.get() ?: LegAttempt.Failed("高德没有返回路径")
    }

    /** Maps an AMap error code to an attempt kind (never success — the caller checked that). */
    private fun failure(code: Int): LegAttempt =
        if (code in AUTH_CODES) LegAttempt.Unauthorized(code) else LegAttempt.Failed("高德返回错误码 $code")

    private fun WalkPath.toLeg(from: GeoPoint, to: GeoPoint, profile: RouteProfile): LegAttempt {
        val polyline = ArrayList<LatLonPoint>()
        steps.orEmpty().forEach { step: WalkStep -> polyline.addAll(step.polyline.orEmpty()) }
        return buildLeg(from, to, profile, polyline, distance.toDouble(), duration.toDouble())
    }

    private fun RidePath.toLeg(from: GeoPoint, to: GeoPoint, profile: RouteProfile): LegAttempt {
        val polyline = ArrayList<LatLonPoint>()
        steps.orEmpty().forEach { step: RideStep -> polyline.addAll(step.polyline.orEmpty()) }
        return buildLeg(from, to, profile, polyline, distance.toDouble(), duration.toDouble())
    }

    private fun DrivePath.toLeg(from: GeoPoint, to: GeoPoint, profile: RouteProfile): LegAttempt {
        val polyline = ArrayList<LatLonPoint>()
        steps.orEmpty().forEach { step: DriveStep -> polyline.addAll(step.polyline.orEmpty()) }
        return buildLeg(from, to, profile, polyline, distance.toDouble(), duration.toDouble())
    }

    private fun buildLeg(
        from: GeoPoint,
        to: GeoPoint,
        profile: RouteProfile,
        polyline: List<LatLonPoint>,
        distanceMeters: Double,
        durationSeconds: Double,
    ): LegAttempt {
        val points = ArrayList<GeoPoint>(polyline.size + 2)
        // Keep the requested endpoints so the drawn polyline starts and ends exactly on the
        // start/finish markers even when the router snapped to the nearest road.
        points.add(from)
        for (point in polyline) {
            val converted = toWgs(point)
            if (converted.isValidLatLon()) points.add(converted)
        }
        points.add(to)
        if (points.size < 2) return LegAttempt.Failed("高德返回的几何为空")
        return LegAttempt.Ok(
            RouteLeg(
                from = from,
                to = to,
                points = points,
                distanceMeters = if (distanceMeters.isFinite() && distanceMeters > 0.0) {
                    distanceMeters
                } else {
                    0.0
                },
                durationSeconds = if (durationSeconds.isFinite() && durationSeconds > 0.0) {
                    durationSeconds
                } else {
                    0.0
                },
            ),
        )
    }

    /** WGS-84 → GCJ-02 (identity outside China). */
    private fun toGcj(point: GeoPoint): LatLonPoint {
        val converted = Gcj02.fromWgs84(point)
        return LatLonPoint(converted.lat, converted.lon)
    }

    /** GCJ-02 → WGS-84 (identity outside China). */
    private fun toWgs(point: LatLonPoint): GeoPoint =
        Gcj02.toWgs84(GeoPoint(point.latitude, point.longitude))

    companion object {
        private const val TAG = "AmapRoute"
        private const val DEFAULT_TIMEOUT_MS = 8_000L

        /** Same set the POI search treats as "this identity is not accepted". */
        private val AUTH_CODES =
            setOf(10001, 10002, 10003, 10005, 10007, 10009, 10010, 10011, 10012, 10013, 10044)
    }
}

package com.immoral.location.net

import android.content.Context
import com.immoral.location.core.route.RouteGeometry
import com.immoral.location.core.route.RouteRequest
import com.immoral.location.core.route.RouteResult
import com.immoral.location.core.route.RouteSource

/** Why a routing backend did not produce geometry. Localized text is added by the UI layer. */
sealed interface RouteFailure {
    /** The request itself is unusable (same start and end, or no valid stop). */
    data object NoRoute : RouteFailure

    /** 高德 rejected the app identity (key/package/signature mismatch). */
    data object AmapUnauthorized : RouteFailure

    /** 高德 answered with an error, a timeout, or empty geometry. */
    data object AmapFailed : RouteFailure

    /** The public OSRM server could not be reached or returned no route. */
    data object OsrmFailed : RouteFailure

    /** Anything else, with a short technical note. */
    data class Other(val reason: String) : RouteFailure
}

/**
 * Result of [RouteClient.plan]: the geometry plus, when it is a fallback, the reasons why.
 *
 * @property result Always usable for playback (or degenerate, when [RouteFailure.NoRoute]).
 * @property failures Backends that failed, in the order they were tried. When non-empty the UI
 *   shows a non-blocking notice — the simulation still works.
 */
data class RouteOutcome(
    val result: RouteResult,
    val failures: List<RouteFailure> = emptyList(),
) {
    /** `true` when the geometry follows no roads (straight-line fallback). */
    val degraded: Boolean
        get() = result.source == RouteSource.STRAIGHT_LINE
}

/**
 * Turns a [RouteRequest] into road-level geometry, trying backends best-first.
 *
 * Order (see docs/UIUX-REWORK.md §6 and the product's mainland-China focus):
 *  1. **高德 `RouteSearch` SDK** — best road data in China, GCJ-02 handled inside
 *     [AmapRouteService]. Needs a working SDK key; on an auth rejection the rotating identity pool
 *     is advanced and the plan retried.
 *  2. **Public OSRM** (`router.project-osrm.org`) — no key, WGS-84, whole waypoint chain in one
 *     request.
 *  3. **Straight line** — never fails, keeps the product usable offline, reported through
 *     [RouteOutcome.failures] so the UI can say "未取得道路路径，使用直线连接".
 *
 * Every point that enters or leaves this class is **WGS-84**; the only GCJ-02 vocabulary lives
 * inside [AmapRouteService].
 *
 * Blocking calls: run [plan] off the main thread (the UI controller uses `Dispatchers.IO`).
 */
class RouteClient private constructor(
    private val amap: AmapRouteService?,
    private val osrm: OsrmRouteService?,
    private val rotateIdentity: (() -> Unit)?,
) {

    /**
     * App-facing constructor: 高德 SDK first, OSRM second, with the identity pool wired so an
     * auth rejection can be retried under the next key.
     */
    constructor(
        context: Context,
        userAgent: String = DEFAULT_USER_AGENT,
        osrmBaseUrl: String = OsrmRouteService.DEFAULT_BASE_URL,
    ) : this(
        amap = AmapRouteService(context),
        osrm = OsrmRouteService(UrlConnectionFetcher(userAgent), osrmBaseUrl),
        rotateIdentity = { AmapPoiSearch.rotateIdentity(context.applicationContext) },
    )

    /**
     * Test / advanced constructor.
     *
     * @param amap `null` disables the 高德 backend (unit tests run without the SDK or a key).
     * @param osrm `null` disables the OSRM backend.
     */
    constructor(amap: AmapRouteService?, osrm: OsrmRouteService?) : this(
        amap = amap,
        osrm = osrm,
        rotateIdentity = null,
    )

    /**
     * Plans [request].
     *
     * @return a result that is always safe to play: a real route when any backend answered, a
     *   densified straight line otherwise.
     */
    fun plan(request: RouteRequest): RouteOutcome {
        val normalized = request.normalized()
        if (!normalized.isUsable) {
            return RouteOutcome(
                result = RouteGeometry.straightLine(normalized),
                failures = listOf(RouteFailure.NoRoute),
            )
        }
        val failures = mutableListOf<RouteFailure>()

        var rotations = 0
        while (amap != null) {
            when (val attempt = amap.plan(normalized)) {
                is AmapAttempt.Ok -> return RouteOutcome(
                    result = RouteGeometry.assemble(
                        request = normalized,
                        legs = attempt.legs,
                        source = RouteSource.AMAP,
                    ),
                    failures = failures,
                )

                is AmapAttempt.Unauthorized -> {
                    if (failures.none { it == RouteFailure.AmapUnauthorized }) {
                        failures.add(RouteFailure.AmapUnauthorized)
                    }
                    // A rejected key is usually the wrong identity in the pool: advance and retry.
                    if (rotateIdentity == null || rotations >= MAX_IDENTITY_ROTATIONS) break
                    rotations++
                    rotateIdentity.invoke()
                }

                is AmapAttempt.Failed -> {
                    failures.add(RouteFailure.AmapFailed)
                    if (attempt.reason.isNotBlank()) {
                        failures.add(RouteFailure.Other(attempt.reason))
                    }
                    break
                }
            }
        }

        if (osrm != null) {
            when (val attempt = osrm.plan(normalized)) {
                is OsrmAttempt.Ok -> return RouteOutcome(
                    result = attempt.result.copy(source = RouteSource.OSRM),
                    failures = failures,
                )

                is OsrmAttempt.Failed -> {
                    failures.add(RouteFailure.OsrmFailed)
                    if (attempt.reason.isNotBlank()) {
                        failures.add(RouteFailure.Other(attempt.reason))
                    }
                }
            }
        }

        // Last resort: a densified straight line. The simulation is never blocked by the network.
        return RouteOutcome(
            result = RouteGeometry.straightLine(normalized),
            failures = failures,
        )
    }

    private companion object {
        /** How many extra identities the pool may try after an auth rejection. */
        const val MAX_IDENTITY_ROTATIONS = 2

        /** Descriptive User-Agent: the public OSRM server asks clients to identify themselves. */
        const val DEFAULT_USER_AGENT = "ImmoralLocation/1.0 (RoutePlanner; +https://github.com/)"
    }
}

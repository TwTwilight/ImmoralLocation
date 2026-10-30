package com.immoral.location.ui.route

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.immoral.location.R
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.route.RouteProfile
import com.immoral.location.core.route.RouteResult
import com.immoral.location.core.route.RouteSource
import com.immoral.location.data.SettingsStore
import com.immoral.location.net.RouteClient
import com.immoral.location.net.RouteFailure
import java.util.Locale

/**
 * String / number helpers for the route planner.
 *
 * Kept out of the composables so the same formatting is reused by the result card, the saved-route
 * list and `ui-maps` (which only needs [labelRes]). Every user-visible text lives in
 * `res/values/strings_route.xml`.
 */
object RoutePlannerStrings {

    /** Localized name of a travel mode (步行 / 骑行 / 驾车). */
    fun labelRes(profile: RouteProfile): Int = when (profile) {
        RouteProfile.WALK -> R.string.rp_mode_walk
        RouteProfile.BIKE -> R.string.rp_mode_bike
        RouteProfile.DRIVE -> R.string.rp_mode_drive
    }

    /** Localized name of the backend that produced the geometry. */
    fun sourceRes(source: RouteSource): Int = when (source) {
        RouteSource.AMAP -> R.string.rp_source_amap
        RouteSource.OSRM -> R.string.rp_source_osrm
        RouteSource.STRAIGHT_LINE -> R.string.rp_source_straight
    }

    /** Short technical reason of a failed backend, for the non-blocking notice. */
    fun reasonText(context: Context, failure: RouteFailure): String = when (failure) {
        RouteFailure.NoRoute -> context.getString(R.string.rp_error_same_point)
        RouteFailure.AmapUnauthorized -> context.getString(R.string.rp_error_amap_unauth)
        RouteFailure.AmapFailed -> context.getString(R.string.rp_error_amap)
        RouteFailure.OsrmFailed -> context.getString(R.string.rp_error_osrm)
        is RouteFailure.Other -> failure.reason
    }

    /**
     * `39.9087, 116.3975` — the label used when a stop has no place name.
     *
     * Four decimals on purpose: ~11 m is far more than a field label needs to convey, while a
     * 5-decimal string (`31.23040, 121.47370`) is wide enough to ellipsise next to the waypoint
     * reorder/delete buttons on a 360 dp sheet. The [GeoPoint] keeps full precision — display only.
     */
    fun coordinateLabel(point: GeoPoint): String =
        String.format(Locale.US, "%.4f, %.4f", point.lat, point.lon)

    /** `850 米` / `1.4 公里`. */
    fun distanceText(context: Context, meters: Double): String {
        if (!meters.isFinite() || meters <= 0.0) return "0 m"
        return if (meters < 1_000.0) {
            context.getString(R.string.rp_fmt_meters, meters.toInt())
        } else {
            context.getString(R.string.rp_fmt_kilometers, String.format(Locale.US, "%.1f", meters / 1_000.0))
        }
    }

    /** Composable variant of [distanceText], resolved through `stringResource`. */
    @Composable
    fun distanceLabel(meters: Double): String {
        if (!meters.isFinite() || meters <= 0.0) return "0"
        return if (meters < 1_000.0) {
            stringResource(R.string.rp_fmt_meters, meters.toInt())
        } else {
            stringResource(
                R.string.rp_fmt_kilometers,
                String.format(Locale.US, "%.1f", meters / 1_000.0),
            )
        }
    }

    /** Composable variant of [durationText], resolved through `stringResource`. */
    @Composable
    fun durationLabel(seconds: Double): String {
        if (!seconds.isFinite() || seconds <= 0.0) return "0"
        val total = seconds.toLong()
        return when {
            total < 60L -> stringResource(R.string.rp_fmt_seconds, total.toInt())
            total < 3_600L -> stringResource(R.string.rp_fmt_minutes, (total / 60L).toInt())
            else -> stringResource(
                R.string.rp_fmt_hours_minutes,
                (total / 3_600L).toInt(),
                ((total % 3_600L) / 60L).toInt(),
            )
        }
    }

    /** Composable variant of [countText], resolved through `stringResource`. */
    @Composable
    fun countLabel(count: Int): String = stringResource(R.string.rp_fmt_count, count)

    /** `45 秒` → `25 分钟` → `1 小时 5 分`. */
    fun durationText(context: Context, seconds: Double): String {
        if (!seconds.isFinite() || seconds <= 0.0) return "0"
        val total = seconds.toLong()
        return when {
            total < 60L -> context.getString(R.string.rp_fmt_seconds, total.toInt())
            total < 3_600L -> context.getString(R.string.rp_fmt_minutes, (total / 60L).toInt())
            else -> context.getString(
                R.string.rp_fmt_hours_minutes,
                (total / 3_600L).toInt(),
                ((total % 3_600L) / 60L).toInt(),
            )
        }
    }

    /** `3 个` — used for the waypoint count. */
    fun countText(context: Context, count: Int): String =
        context.getString(R.string.rp_fmt_count, count)
}

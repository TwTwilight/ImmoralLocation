package com.immoral.location.ui.map

import android.view.View
import com.immoral.location.core.model.FavoritePlace
import com.immoral.location.core.model.GeoPoint

/**
 * ARGB colours the map layer needs, resolved once from the active Material colour scheme.
 *
 * The map backends draw their markers as Android bitmaps (neither 高德 nor osmdroid understands
 * Compose), so the palette has to cross the boundary as plain ints. Keeping it in one immutable
 * object means a theme change is a single call, not a re-draw of every marker.
 */
data class MapPalette(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val surface: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val surfaceContainerHigh: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val dark: Boolean,
)

/**
 * Backend-agnostic map surface used by the map screen.
 *
 * Two implementations exist:
 *  * [AmapHost] — 高德地图 SDK (default);
 *  * [MapHost]  — osmdroid raster tiles (silent fallback when the SDK cannot initialise).
 *
 * Both speak **WGS-84** at this interface: the AMap implementation converts with
 * [com.immoral.location.map.Gcj02] internally, so callers never deal with 火星坐标.
 */
interface MapBackend {

    /** The Android view to host through `AndroidView`. */
    val view: View

    /** Called once before the view enters the window (AMap needs `onCreate`). */
    fun onCreate()

    /** Applies the current Material palette to every marker drawn from now on. */
    fun setPalette(palette: MapPalette)

    /**
     * Moves the simulated-position marker.
     *
     * @param accuracyMeters radius of the accuracy halo
     * @param bearingDeg cone direction in degrees clockwise from north, `null` = no cone
     */
    fun setPosition(point: GeoPoint, accuracyMeters: Double, bearingDeg: Double?)

    /** The pin dropped by a tap / long-press, or `null` to clear it. */
    fun setSelection(point: GeoPoint?)

    /**
     * Replaces the drawn route.
     *
     * @param geometry dense polyline (road-level when available, straight legs otherwise)
     * @param waypoints user waypoints **excluding** origin/destination, drawn as numbered badges
     * @param origin start marker, `null` when the plan has no explicit start
     * @param destination end marker
     */
    fun setRoute(
        geometry: List<GeoPoint>,
        waypoints: List<GeoPoint> = emptyList(),
        origin: GeoPoint? = null,
        destination: GeoPoint? = null,
    )

    /**
     * Rebuilds the saved-place markers.
     *
     * @param selectedId the favourite whose label chip is expanded
     */
    fun setFavorites(places: List<FavoritePlace>, selectedId: String? = null)

    /** Animated recentre; [zoom] is kept when `null`. */
    fun animateTo(point: GeoPoint, zoom: Float? = null)

    /** Instant recentre (first layout only). */
    fun jumpTo(point: GeoPoint)

    fun setDarkFilter(enabled: Boolean)

    fun onResume()

    fun onPause()

    fun detach()
}

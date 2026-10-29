package com.immoral.location.ui.map

import android.content.Context
import android.view.View
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.immoral.location.core.model.FavoritePlace
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.map.Gcj02

/**
 * 高德地图 SDK backend.
 *
 * The SDK works in **GCJ-02** while the simulation (and every other component) uses WGS-84, so
 * every point drawn is passed through [Gcj02.fromWgs84] and every tap/marker is converted back with
 * [Gcj02.toWgs84] before it reaches `SetPosition`.
 *
 * No AMap locator is used: the SDK dependency carries a few `com.amap.api.location.*` interface
 * types (they ship inside 3dmap), but nothing ever constructs `AMapLocationClient` here, the "blue
 * dot" is never shown, `isMyLocationEnabled` stays off, and the only marker is ours.
 *
 * Markers are Material You bitmaps painted by [MarkerArt] and cached per palette, so a theme
 * change re-tints them without rebuilding the map.
 */
class AmapHost(
    context: Context,
    private val onTap: (GeoPoint) -> Unit,
    private val onLongPress: (GeoPoint) -> Unit,
    private val onFavoriteTap: (FavoritePlace) -> Unit,
    private val onMapGesture: () -> Unit = {},
    private val onMapLoaded: () -> Unit = {},
) : MapBackend {

    private val mapView = MapView(context)
    private val map: AMap = mapView.map
    private val density = context.resources.displayMetrics.density

    private var palette: MapPalette = DEFAULT_PALETTE

    // Cached art, rebuilt only when the palette changes.
    private var iconPosition: BitmapDescriptor = descriptor(MarkerArt.currentPosition(palette, density))
    private var iconCone: BitmapDescriptor = descriptor(MarkerArt.bearingCone(palette, density))
    private var iconFavourite: BitmapDescriptor = descriptor(MarkerArt.favouriteDot(palette, density))
    private var iconSelection: BitmapDescriptor = descriptor(MarkerArt.selectionPin(palette, density))

    private var positionMarker: Marker? = null
    private var coneMarker: Marker? = null
    private var accuracy: Circle? = null
    private var selectionMarker: Marker? = null
    private var routeCasing: Polyline? = null
    private var routeMain: Polyline? = null
    private val favoriteMarkers = mutableListOf<Marker>()
    private val routeBadges = mutableListOf<Marker>()

    /** Marker id → the saved place it shows, so the single map-level click listener can route. */
    private val favoriteById = mutableMapOf<String, FavoritePlace>()

    override val view: View get() = mapView

    override fun onCreate() {
        try {
            mapView.onCreate(null)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "mapView.onCreate failed", t)
        }
        map.uiSettings.apply {
            isZoomControlsEnabled = false
            isCompassEnabled = false
            isMyLocationButtonEnabled = false
            isScaleControlsEnabled = false
            // Gestures stay on; only the SDK's own widgets are hidden (we draw our own chrome).
            isZoomGesturesEnabled = true
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
            isScrollGesturesEnabled = true
        }
        map.isMyLocationEnabled = false
        map.setOnMapLoadedListener {
            // 打点：`e2e/amap_check.ps1` 靠这一行判定「SDK 真的渲染成功了」。
            android.util.Log.i(TAG, "amap map loaded (tiles rendered)")
            onMapLoaded()
        }
        map.setOnMapClickListener { latLng -> onTap(fromMap(latLng)) }
        map.setOnMapLongClickListener { latLng -> onLongPress(fromMap(latLng)) }
        // Any camera motion (drag / pinch / our own recentre) dismisses the post-tap chip.
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition?) = Unit

            override fun onCameraChangeFinish(position: CameraPosition?) = onMapGesture()
        })
        map.setOnMarkerClickListener { marker ->
            val place = marker.id?.let { favoriteById[it] }
            if (place != null) {
                onFavoriteTap(place)
                true
            } else {
                false
            }
        }
    }

    // ------------------------------------------------------------ conversions

    private fun toMap(point: GeoPoint): LatLng {
        val gcj = Gcj02.fromWgs84(point)
        return LatLng(gcj.lat, gcj.lon)
    }

    private fun fromMap(latLng: LatLng): GeoPoint =
        Gcj02.toWgs84(GeoPoint(latLng.latitude, latLng.longitude))

    private fun descriptor(art: MarkerBitmap): BitmapDescriptor =
        BitmapDescriptorFactory.fromBitmap(art.bitmap)

    // ----------------------------------------------------------------- palette

    override fun setPalette(palette: MapPalette) {
        if (this.palette == palette) return
        this.palette = palette
        iconPosition = descriptor(MarkerArt.currentPosition(palette, density))
        iconCone = descriptor(MarkerArt.bearingCone(palette, density))
        iconFavourite = descriptor(MarkerArt.favouriteDot(palette, density))
        iconSelection = descriptor(MarkerArt.selectionPin(palette, density))
        positionMarker?.setIcon(iconPosition)
        coneMarker?.setIcon(iconCone)
        selectionMarker?.setIcon(iconSelection)
        favoriteMarkers.forEach { it.setIcon(iconFavourite) }
        // Badges carry text, so they are re-painted from scratch by the caller's state.
        routeMain?.color = palette.primary
        routeCasing?.color = alpha(palette.surface, 0.9f)
    }

    // --------------------------------------------------------------- position

    override fun setPosition(point: GeoPoint, accuracyMeters: Double, bearingDeg: Double?) {
        if (!point.isValidLatLon()) return
        val latLng = toMap(point)
        val radius = accuracyMeters.coerceIn(6.0, 500.0)

        val marker = positionMarker
        if (marker == null) {
            positionMarker = map.addMarker(
                markerOptions(latLng, iconPosition, 0.5f, 0.5f).zIndex(POSITION_Z),
            )
            accuracy = map.addCircle(
                CircleOptions()
                    .center(latLng)
                    .radius(radius)
                    .fillColor(alpha(palette.primary, 0.12f))
                    .strokeWidth(0f),
            )
        } else {
            marker.position = latLng
            accuracy?.center = latLng
            accuracy?.radius = radius
            accuracy?.fillColor = alpha(palette.primary, 0.12f)
            accuracy?.strokeColor = alpha(palette.primary, HALO_EDGE_ALPHA)
        }

        if (bearingDeg == null || !bearingDeg.isFinite()) {
            coneMarker?.remove()
            coneMarker = null
        } else {
            val cone = coneMarker
            if (cone == null) {
                coneMarker = map.addMarker(
                    markerOptions(latLng, iconCone, 0.5f, 0.5f)
                        .rotateAngle(bearingDeg.toFloat())
                        .zIndex(POSITION_Z - 1f),
                )
            } else {
                cone.position = latLng
                cone.rotateAngle = bearingDeg.toFloat()
            }
        }
    }

    override fun setSelection(point: GeoPoint?) {
        if (point == null) {
            selectionMarker?.remove()
            selectionMarker = null
            return
        }
        if (!point.isValidLatLon()) return
        val latLng = toMap(point)
        val existing = selectionMarker
        if (existing == null) {
            selectionMarker = map.addMarker(
                markerOptions(latLng, iconSelection, 0.5f, 1f).zIndex(SELECTION_Z),
            )
        } else {
            existing.position = latLng
        }
    }

    // ------------------------------------------------------------------ route

    override fun setRoute(
        geometry: List<GeoPoint>,
        waypoints: List<GeoPoint>,
        origin: GeoPoint?,
        destination: GeoPoint?,
    ) {
        routeCasing?.remove()
        routeMain?.remove()
        val points = geometry.filter { it.isValidLatLon() }.map { toMap(it) }
        if (points.size < 2) {
            routeCasing = null
            routeMain = null
        } else {
            // Casing first so the coloured line sits on top of it (spec §3, row 5).
            routeCasing = map.addPolyline(
                PolylineOptions()
                    .addAll(points)
                    .color(alpha(palette.surface, 0.9f))
                    .width(ROUTE_CASING_DP * density)
                    .zIndex(ROUTE_Z)
                    .setUseTexture(false),
            )
            routeMain = map.addPolyline(
                PolylineOptions()
                    .addAll(points)
                    .color(palette.primary)
                    .width(ROUTE_WIDTH_DP * density)
                    .zIndex(ROUTE_Z + 1f)
                    .setUseTexture(false),
            )
        }
        rebuildBadges(waypoints, origin, destination)
    }

    private fun rebuildBadges(
        waypoints: List<GeoPoint>,
        origin: GeoPoint?,
        destination: GeoPoint?,
    ) {
        routeBadges.forEach { it.remove() }
        routeBadges.clear()

        fun add(point: GeoPoint?, text: String, style: MarkerArt.Badge) {
            if (point == null || !point.isValidLatLon()) return
            val art = MarkerArt.badge(palette, density, text, style)
            map.addMarker(markerOptions(toMap(point), descriptor(art), art.anchorX, art.anchorY).zIndex(BADGE_Z))
                ?.let { routeBadges.add(it) }
        }

        add(origin, MarkerGeometry.badgeText(MarkerGeometry.BadgeKind.START, -1), MarkerArt.Badge.START)
        waypoints.forEachIndexed { index, point ->
            add(point, MarkerGeometry.badgeText(MarkerGeometry.BadgeKind.WAYPOINT, index), MarkerArt.Badge.WAYPOINT)
        }
        add(destination, MarkerGeometry.badgeText(MarkerGeometry.BadgeKind.END, -1), MarkerArt.Badge.END)
    }

    // -------------------------------------------------------------- favorites

    override fun setFavorites(places: List<FavoritePlace>, selectedId: String?) {
        favoriteMarkers.forEach { it.remove() }
        favoriteMarkers.clear()
        favoriteById.clear()
        places.forEach { place ->
            if (!place.point.isValidLatLon()) return@forEach
            val selected = place.id == selectedId
            val marker = if (selected) {
                val art = MarkerArt.favouriteChip(palette, density, place.name)
                map.addMarker(
                    markerOptions(toMap(place.point), descriptor(art), art.anchorX, art.anchorY)
                        .zIndex(FAVORITE_SELECTED_Z),
                )
            } else {
                map.addMarker(
                    markerOptions(toMap(place.point), iconFavourite, 0.5f, 0.5f).zIndex(FAVORITE_Z),
                )
            }
            if (marker != null && marker.id != null) {
                favoriteMarkers.add(marker)
                favoriteById[marker.id] = place
            }
        }
    }

    // ----------------------------------------------------------------- camera

    override fun animateTo(point: GeoPoint, zoom: Float?) {
        if (!point.isValidLatLon()) return
        val update = if (zoom == null) {
            CameraUpdateFactory.newLatLng(toMap(point))
        } else {
            CameraUpdateFactory.newLatLngZoom(toMap(point), zoom)
        }
        map.animateCamera(update)
    }

    override fun jumpTo(point: GeoPoint) {
        if (!point.isValidLatLon()) return
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(toMap(point), DEFAULT_ZOOM))
    }

    override fun setDarkFilter(enabled: Boolean) {
        // 高德 has a real night style — much better than filtering raster tiles.
        map.mapType = if (enabled) AMap.MAP_TYPE_NIGHT else AMap.MAP_TYPE_NORMAL
    }

    // -------------------------------------------------------------- lifecycle

    override fun onResume() {
        try {
            mapView.onResume()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "onResume failed", t)
        }
    }

    override fun onPause() {
        try {
            mapView.onPause()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "onPause failed", t)
        }
    }

    override fun detach() {
        try {
            mapView.onDestroy()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "onDestroy failed", t)
        }
    }

    private fun markerOptions(
        position: LatLng,
        icon: BitmapDescriptor,
        anchorX: Float,
        anchorY: Float,
    ): MarkerOptions = MarkerOptions()
        .position(position)
        .icon(icon)
        .anchor(anchorX, anchorY)
        .setFlat(true)

    private fun alpha(color: Int, fraction: Float): Int {
        val a = ((color ushr 24) * fraction).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private companion object {
        const val TAG = "AmapHost"
        const val DEFAULT_ZOOM = 16f
        const val ROUTE_CASING_DP = 12f
        const val ROUTE_WIDTH_DP = 7f
        const val HALO_EDGE_DP = 1.5f
        const val HALO_EDGE_ALPHA = 0.30f

        const val ROUTE_Z = 1f
        const val FAVORITE_Z = 3f
        const val ROUTE_BADGE_Z = 4f
        const val SELECTION_Z = 6f
        const val POSITION_Z = 8f
        const val FAVORITE_SELECTED_Z = 8.5f
        const val BADGE_Z = 8.6f

        val DEFAULT_PALETTE = MapPalette(
            primary = 0xFF3B5BA5.toInt(),
            onPrimary = 0xFFFFFFFF.toInt(),
            primaryContainer = 0xFFDBE1FF.toInt(),
            onPrimaryContainer = 0xFF00174B.toInt(),
            tertiary = 0xFF00696E.toInt(),
            onTertiary = 0xFFFFFFFF.toInt(),
            surface = 0xFFFEFBFF.toInt(),
            onSurface = 0xFF1B1B1F.toInt(),
            onSurfaceVariant = 0xFF44474F.toInt(),
            surfaceContainerHigh = 0xFFE9E7EC.toInt(),
            errorContainer = 0xFFFFDAD6.toInt(),
            onErrorContainer = 0xFF410002.toInt(),
            dark = false,
        )
    }
}

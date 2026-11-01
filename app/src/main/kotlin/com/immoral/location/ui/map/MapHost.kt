package com.immoral.location.ui.map

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.view.View
import com.immoral.location.core.model.FavoritePlace
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.map.Gcj02
import com.immoral.location.map.TileCoordSystem
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint as OsGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/**
 * Imperative owner of the osmdroid [MapView] and its overlays.
 *
 * Compose only owns the view; everything that has to be mutated several times per second (marker
 * position, accuracy halo, route polylines, favourite pins) is updated through plain calls instead
 * of recomposing the whole [MapView], which would drop the tile cache and cause visible flicker.
 *
 * **Coordinate systems**: the simulation works in WGS-84, but Chinese tile providers (高德/腾讯)
 * publish GCJ-02, so when [coordSystem] is GCJ-02 every drawn point is converted forward and every
 * tap/marker is converted back. Without that the marker sits 300–500 m off the tapped street.
 *
 * Marker artwork comes from [MarkerArt], the same painter the 高德 backend uses, so the fallback
 * map looks like the same product (spec §3).
 */
class MapHost(
    context: Context,
    private val onTap: (GeoPoint) -> Unit,
    private val onLongPress: (GeoPoint) -> Unit,
    private val onFavoriteTap: (FavoritePlace) -> Unit,
    private val onMapGesture: () -> Unit = {},
) : MapBackend {

    val mapView: MapView = MapView(context).apply {
        setMultiTouchControls(true)
        setTilesScaledToDpi(true)
        controller.setZoom(DEFAULT_ZOOM)
    }

    private val appContext = context.applicationContext
    private val density = context.resources.displayMetrics.density

    /** Coordinate system of the active tile source; WGS-84 until told otherwise. */
    @Volatile
    var coordSystem: TileCoordSystem = TileCoordSystem.WGS84

    private var palette: MapPalette = DEFAULT_PALETTE

    private val positionMarker = Marker(mapView).apply {
        setAnchor(ANCHOR_CENTER, ANCHOR_CENTER)
        setInfoWindow(null)
    }

    private val coneMarker = Marker(mapView).apply {
        setAnchor(ANCHOR_CENTER, ANCHOR_CENTER)
        setInfoWindow(null)
    }

    private val selectionMarker = Marker(mapView).apply {
        setAnchor(ANCHOR_CENTER, ANCHOR_BOTTOM)
        setInfoWindow(null)
    }

    private val accuracyCircle = Polygon(mapView).apply {
        strokeWidth = 0f
        setPoints(emptyList())
    }

    private val routeCasing = Polyline(mapView).apply {
        width = ROUTE_CASING_DP * density
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
    }

    private val routeMain = Polyline(mapView).apply {
        width = ROUTE_WIDTH_DP * density
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
    }

    private val favoriteMarkers = mutableListOf<Marker>()
    private val badgeMarkers = mutableListOf<Marker>()

    override val view: View get() = mapView

    override fun onCreate() = Unit

    init {
        val eventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: OsGeoPoint): Boolean {
                onTap(fromMap(GeoPoint(p.latitude, p.longitude, p.altitude)))
                return true
            }

            override fun longPressHelper(p: OsGeoPoint): Boolean {
                onLongPress(fromMap(GeoPoint(p.latitude, p.longitude, p.altitude)))
                return true
            }
        }
        mapView.overlays.add(MapEventsOverlay(eventsReceiver))
        // Any camera motion (drag / pinch / our own recentre) dismisses the post-tap chip.
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                onMapGesture()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                onMapGesture()
                return false
            }
        })
        mapView.overlays.add(routeCasing)
        mapView.overlays.add(routeMain)
        mapView.overlays.add(accuracyCircle)
        refreshArt()
    }

    // ------------------------------------------------------------ conversions

    /** WGS-84 simulation point → the tile source's coordinate system. */
    private fun toMap(point: GeoPoint): OsGeoPoint {
        val converted = if (coordSystem == TileCoordSystem.GCJ02) Gcj02.fromWgs84(point) else point
        return OsGeoPoint(converted.lat, converted.lon, converted.alt)
    }

    /** A tile-source coordinate (tap / marker) → the WGS-84 point the engine understands. */
    private fun fromMap(point: GeoPoint): GeoPoint =
        if (coordSystem == TileCoordSystem.GCJ02) Gcj02.toWgs84(point) else point

    // ----------------------------------------------------------------- palette

    override fun setPalette(palette: MapPalette) {
        if (this.palette == palette) return
        this.palette = palette
        refreshArt()
    }

    private fun refreshArt() {
        positionMarker.icon = drawable(MarkerArt.currentPosition(palette, density))
        coneMarker.icon = drawable(MarkerArt.bearingCone(palette, density))
        selectionMarker.icon = drawable(MarkerArt.selectionPin(palette, density))
        val favourite = drawable(MarkerArt.favouriteDot(palette, density))
        favoriteMarkers.forEach { it.icon = favourite }
        accuracyCircle.fillPaint.color = alpha(palette.primary, 0.12f)
        accuracyCircle.outlinePaint.color = alpha(palette.primary, HALO_EDGE_ALPHA)
        routeCasing.outlinePaint.color = alpha(palette.surface, 0.9f)
        routeMain.outlinePaint.color = palette.primary
        mapView.invalidate()
    }

    private fun drawable(art: MarkerBitmap): BitmapDrawable =
        BitmapDrawable(appContext.resources, art.bitmap)

    // ------------------------------------------------------------------ tiles

    /** Swaps the tile endpoint (preset or user template) and its coordinate system. */
    fun setTileSource(template: String, subdomains: List<String>, system: TileCoordSystem) {
        try {
            coordSystem = system
            val source = TemplateTileSource(template, subdomains)
            mapView.setTileSource(source)
            mapView.tileProvider.clearTileCache()
            mapView.invalidate()
            android.util.Log.i("MapHost", "tile source -> $source (${system.name})")
        } catch (t: Throwable) {
            android.util.Log.w("MapHost", "setTileSource failed for $template", t)
        }
    }

    /** Applies a dark-mode filter to the raster tiles (dimmed + desaturated). */
    override fun setDarkFilter(enabled: Boolean) {
        try {
            val filter = if (enabled) {
                // A plain invert turns the light road casings black over a black base, which reads
                // as a blank map. Dimming + desaturating keeps the road hierarchy readable under a
                // dark UI while dropping the luminance enough not to glare.
                val dim = ColorMatrix(
                    floatArrayOf(
                        0.52f, 0f, 0f, 0f, 30f,
                        0f, 0.52f, 0f, 0f, 32f,
                        0f, 0f, 0.54f, 0f, 38f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                )
                val desaturate = ColorMatrix().apply { setSaturation(0.45f) }
                dim.preConcat(desaturate)
                ColorMatrixColorFilter(dim)
            } else {
                // Light mode: the base map is a third-party raster style (bilingual OSM labels),
                // and left untouched it dominates the frame no matter how good the chrome is.
                // 0.86 saturation + a small brightness lift pulls it towards the M3 neutral
                // surfaces while keeping road hierarchy and labels legible. The lift is deliberately
                // small: the tonal *scrim* in MapScreen already tints the bands under the chrome,
                // and lifting the whole frame on top of that is what made round 1 look foggy.
                val soften = ColorMatrix().apply { setSaturation(0.86f) }
                val lift = ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, 5f,
                        0f, 1f, 0f, 0f, 5f,
                        0f, 0f, 1f, 0f, 4f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                )
                soften.preConcat(lift)
                ColorMatrixColorFilter(soften)
            }
            mapView.overlayManager.tilesOverlay.setColorFilter(filter)
            mapView.invalidate()
        } catch (t: Throwable) {
            // ignore
        }
    }

    // --------------------------------------------------------------- overlays

    /** Moves the simulated-position marker, its halo and its bearing cone. */
    override fun setPosition(point: GeoPoint, accuracyMeters: Double, bearingDeg: Double?) {
        if (!point.isValidLatLon()) return
        val mapped = toMap(point)
        positionMarker.position = mapped
        val radius = accuracyMeters.coerceIn(6.0, 500.0)
        accuracyCircle.setPoints(Polygon.pointsAsCircle(mapped, radius))
        if (bearingDeg == null || !bearingDeg.isFinite()) {
            if (mapView.overlays.contains(coneMarker)) mapView.overlays.remove(coneMarker)
        } else {
            coneMarker.position = mapped
            coneMarker.rotation = -bearingDeg.toFloat()
            ensureOverlayOrder()
        }
        ensureOverlayOrder()
        mapView.invalidate()
    }

    override fun setSelection(point: GeoPoint?) {
        if (point == null) {
            if (mapView.overlays.contains(selectionMarker)) mapView.overlays.remove(selectionMarker)
            mapView.invalidate()
            return
        }
        if (!point.isValidLatLon()) return
        selectionMarker.position = toMap(point)
        ensureOverlayOrder()
        mapView.invalidate()
    }

    /** Replaces the drawn route: casing, coloured line, then the start/waypoint/end badges. */
    override fun setRoute(
        geometry: List<GeoPoint>,
        waypoints: List<GeoPoint>,
        origin: GeoPoint?,
        destination: GeoPoint?,
    ) {
        routeCasing.setPoints(geometry.filter { it.isValidLatLon() }.map { toMap(it) })
        routeMain.setPoints(geometry.filter { it.isValidLatLon() }.map { toMap(it) })

        badgeMarkers.forEach { mapView.overlays.remove(it) }
        badgeMarkers.clear()

        fun add(point: GeoPoint?, text: String, style: MarkerArt.Badge) {
            if (point == null || !point.isValidLatLon()) return
            val art = MarkerArt.badge(palette, density, text, style)
            val marker = Marker(mapView).apply {
                position = toMap(point)
                icon = BitmapDrawable(appContext.resources, art.bitmap)
                setAnchor(art.anchorX, art.anchorY)
                setInfoWindow(null)
            }
            badgeMarkers.add(marker)
        }

        add(origin, MarkerGeometry.badgeText(MarkerGeometry.BadgeKind.START, -1), MarkerArt.Badge.START)
        waypoints.forEachIndexed { index, point ->
            add(point, MarkerGeometry.badgeText(MarkerGeometry.BadgeKind.WAYPOINT, index), MarkerArt.Badge.WAYPOINT)
        }
        add(destination, MarkerGeometry.badgeText(MarkerGeometry.BadgeKind.END, -1), MarkerArt.Badge.END)

        ensureOverlayOrder()
        mapView.invalidate()
    }

    /** Rebuilds the saved-place pins (few entries, so a full rebuild is cheapest). */
    override fun setFavorites(places: List<FavoritePlace>, selectedId: String?) {
        favoriteMarkers.forEach { mapView.overlays.remove(it) }
        favoriteMarkers.clear()
        places.forEach { place ->
            if (!place.point.isValidLatLon()) return@forEach
            val art = if (place.id == selectedId) {
                MarkerArt.favouriteChip(palette, density, place.name)
            } else {
                MarkerArt.favouriteDot(palette, density)
            }
            val marker = Marker(mapView).apply {
                position = toMap(place.point)
                icon = BitmapDrawable(appContext.resources, art.bitmap)
                setAnchor(art.anchorX, art.anchorY)
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ ->
                    onFavoriteTap(place)
                    true
                }
            }
            favoriteMarkers.add(marker)
        }
        ensureOverlayOrder()
        mapView.invalidate()
    }

    /**
     * Keeps a deterministic stacking order regardless of the sequence of updates:
     * route → accuracy → badges → favourites → selection → cone → simulated position.
     */
    private fun ensureOverlayOrder() {
        val overlays = mapView.overlays
        overlays.remove(accuracyCircle)
        overlays.remove(routeCasing)
        overlays.remove(routeMain)
        overlayInsert(routeCasing)
        overlayInsert(routeMain)
        overlayInsert(accuracyCircle)
        badgeMarkers.forEach { overlays.remove(it); overlayInsert(it) }
        favoriteMarkers.forEach { overlays.remove(it); overlayInsert(it) }
        if (overlays.contains(selectionMarker)) overlays.remove(selectionMarker)
        overlayInsert(selectionMarker)
        overlays.remove(coneMarker)
        overlayInsert(coneMarker)
        overlays.remove(positionMarker)
        overlayInsert(positionMarker)
    }

    private fun overlayInsert(overlay: org.osmdroid.views.overlay.Overlay) {
        if (!mapView.overlays.contains(overlay)) mapView.overlays.add(overlay)
    }

    /** Smoothly recentres on [point] (a WGS-84 point). */
    override fun animateTo(point: GeoPoint, zoom: Float?) {
        if (!point.isValidLatLon()) return
        try {
            mapView.controller.animateTo(toMap(point))
            if (zoom != null) mapView.controller.setZoom(zoom.toDouble())
        } catch (t: Throwable) {
            // ignore
        }
    }

    /** Jumps to [point] without animation (used on first layout). */
    override fun jumpTo(point: GeoPoint) {
        if (!point.isValidLatLon()) return
        try {
            mapView.controller.setZoom(DEFAULT_ZOOM)
            mapView.controller.setCenter(toMap(point))
        } catch (t: Throwable) {
            // ignore
        }
    }

    override fun onResume() {
        try {
            mapView.onResume()
        } catch (t: Throwable) {
            // ignore
        }
    }

    override fun onPause() {
        try {
            mapView.onPause()
        } catch (t: Throwable) {
            // ignore
        }
    }

    override fun detach() {
        try {
            positionMarker.closeInfoWindow()
            mapView.onDetach()
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun alpha(color: Int, fraction: Float): Int {
        val a = ((color ushr 24) * fraction).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    companion object {
        const val DEFAULT_ZOOM: Double = 16.0
        private const val ROUTE_CASING_DP = 12f
        private const val ROUTE_WIDTH_DP = 7f
        private const val HALO_EDGE_DP = 1.5f
        private const val HALO_EDGE_ALPHA = 0.30f
        private const val ANCHOR_CENTER = 0.5f
        private const val ANCHOR_BOTTOM = 1.0f

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

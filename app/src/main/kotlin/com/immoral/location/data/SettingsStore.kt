package com.immoral.location.data

import android.content.Context
import androidx.core.content.edit
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.route.RouteProfile
import com.immoral.location.core.route.RouteRequest
import com.immoral.location.core.route.RouteResult
import com.immoral.location.core.route.SavedRoute
import com.immoral.location.map.TilePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A place the user searched for, kept for the route planner's 最近使用 list. */
data class RecentPlace(
    val name: String,
    val point: GeoPoint,
)

/**
 * Single source of truth for everything that survives a process restart.
 *
 * The app targets `minSdk 21`, so `androidx.datastore` (minSdk 23) is not available and
 * [android.content.SharedPreferences] is used directly. Structured values are stored as
 * kotlinx-serialization JSON **using the serializers generated inside `:core`**, which keeps the
 * on-disk format identical to the wire format used by the bridge.
 *
 * Reads never throw: a corrupt entry falls back to the documented default value.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val _config = MutableStateFlow(readConfig())
    /** The live simulation configuration; always sanitized. */
    val config: StateFlow<SimulationConfig> = _config.asStateFlow()

    private val _routePoints = MutableStateFlow(readRoutePoints())
    /** The route currently being edited on the 路线 screen. */
    val routePoints: StateFlow<List<GeoPoint>> = _routePoints.asStateFlow()

    private val _routePath = MutableStateFlow(readPoints(KEY_ROUTE_PATH))

    /**
     * Road-level geometry of the planned route, in **WGS-84**, already densified.
     *
     * This is what the map draws and what playback follows. Empty means "no planned route", in
     * which case consumers fall back to [routePoints] (a raw waypoint list from taps or GPX).
     * Kept separate from [routePoints] on purpose: the geometry has thousands of vertices and is
     * derived data, while the waypoints are the user's editable input.
     */
    val routePath: StateFlow<List<GeoPoint>> = _routePath.asStateFlow()

    private val _routeOrigin = MutableStateFlow(readPoint(KEY_ROUTE_ORIGIN))

    /** Start of the planned route; `null` when nothing is planned. */
    val routeOrigin: StateFlow<GeoPoint?> = _routeOrigin.asStateFlow()

    private val _routeDestination = MutableStateFlow(readPoint(KEY_ROUTE_DESTINATION))

    /** End of the planned route; `null` when nothing is planned. */
    val routeDestination: StateFlow<GeoPoint?> = _routeDestination.asStateFlow()

    private val _routeWaypoints = MutableStateFlow(readPoints(KEY_ROUTE_WAYPOINTS))

    /** Intermediate stops of the planned route, in travel order. */
    val routeWaypoints: StateFlow<List<GeoPoint>> = _routeWaypoints.asStateFlow()

    private val _routeProfile = MutableStateFlow(
        RouteProfile.fromId(prefs.getString(KEY_ROUTE_PROFILE, null)),
    )

    /** Travel mode of the planned route (步行 / 骑行 / 驾车). */
    val routeProfile: StateFlow<RouteProfile> = _routeProfile.asStateFlow()

    private val _savedRoutes = MutableStateFlow(readSavedRoutes())

    /** Routes the user saved, newest first. */
    val savedRoutes: StateFlow<List<SavedRoute>> = _savedRoutes.asStateFlow()

    private val _recentPlaces = MutableStateFlow(readRecentPlaces())

    /** Recently picked places (name + point), newest first — feeds the route search sheet. */
    val recentPlaces: StateFlow<List<RecentPlace>> = _recentPlaces.asStateFlow()

    private val _tileUrl = MutableStateFlow(
        prefs.getString(KEY_TILE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TILE_URL,
    )
    /** Custom XYZ template, used only when [tilePreset] is [com.immoral.location.map.TilePreset.CUSTOM]. */
    val tileUrl: StateFlow<String> = _tileUrl.asStateFlow()

    private val _tilePreset = MutableStateFlow(
        TilePreset.fromId(prefs.getString(KEY_TILE_PRESET, null)),
    )
    /** Active tile preset (高德 by default, because the product targets mainland China). */
    val tilePreset: StateFlow<TilePreset> = _tilePreset.asStateFlow()

    private val _tiandituKey = MutableStateFlow(prefs.getString(KEY_TIANDITU_KEY, "").orEmpty())
    /** Optional 天地图 key: unlocks the official WMTS source and its geocoder. */
    val tiandituKey: StateFlow<String> = _tiandituKey.asStateFlow()

    private val _lastPosition = MutableStateFlow(readLastPosition())
    /** Last simulated position, so the map opens where the user left it. */
    val lastPosition: StateFlow<GeoPoint> = _lastPosition.asStateFlow()

    // ---------------------------------------------------------------- config

    /** Applies [transform] to the current config, sanitizes and persists the result. */
    fun updateConfig(transform: (SimulationConfig) -> SimulationConfig): SimulationConfig {
        val next = transform(_config.value).sanitized()
        _config.value = next
        writeConfig(next)
        return next
    }

    fun setConfig(config: SimulationConfig) {
        val next = config.sanitized()
        _config.value = next
        writeConfig(next)
    }

    /** Resets every simulation parameter, keeping route points and the tile URL. */
    fun resetConfig() = setConfig(SimulationConfig())

    private fun readConfig(): SimulationConfig = try {
        val raw = prefs.getString(KEY_CONFIG, null)
        if (raw.isNullOrBlank()) {
            SimulationConfig()
        } else {
            json.decodeFromString(SimulationConfig.serializer(), raw).sanitized()
        }
    } catch (t: Throwable) {
        SimulationConfig()
    }

    private fun writeConfig(config: SimulationConfig) {
        try {
            prefs.edit {
                putString(KEY_CONFIG, json.encodeToString(SimulationConfig.serializer(), config))
            }
        } catch (t: Throwable) {
            // Ignore: a failed write must never take the UI down.
        }
    }

    // ----------------------------------------------------------------- route

    /**
     * Replaces the raw waypoint list (map taps, GPX import) and *invalidates* the planned route.
     *
     * A geometry planned for the previous stops would no longer describe this one, so [routePath]
     * is cleared and the map goes back to drawing the waypoints themselves. The endpoints are
     * derived from the list so markers stay meaningful without a plan.
     */
    fun setRoutePoints(points: List<GeoPoint>) {
        val clean = points.filter { it.isValidLatLon() }
        _routePoints.value = clean
        writePoints(KEY_ROUTE, clean)
        _routePath.value = emptyList()
        writePoints(KEY_ROUTE_PATH, emptyList())
        setRouteStops(
            origin = clean.firstOrNull(),
            destination = when {
                clean.size >= 2 -> clean.last()
                else -> null
            },
            waypoints = if (clean.size >= 3) clean.subList(1, clean.size - 1).toList() else emptyList(),
        )
    }

    fun addRoutePoint(point: GeoPoint) = setRoutePoints(_routePoints.value + point)

    fun removeRoutePoint(index: Int) {
        val current = _routePoints.value
        if (index in current.indices) setRoutePoints(current.filterIndexed { i, _ -> i != index })
    }

    fun moveRoutePoint(from: Int, to: Int) {
        val current = _routePoints.value.toMutableList()
        if (from !in current.indices || to !in current.indices) return
        val moved = current.removeAt(from)
        current.add(to, moved)
        setRoutePoints(current)
    }

    /** 清除: drops the raw waypoints *and* the planned route. */
    fun clearRoute() {
        setRoutePoints(emptyList())
    }

    // --------------------------------------------------------- planned route

    /**
     * Publishes a freshly planned route.
     *
     * Everything the map shell and the player need is written in one call so they can never
     * observe a half-updated plan: the geometry ([routePath]), the markers ([routeOrigin],
     * [routeDestination], [routeWaypoints]), the travel mode ([routeProfile]) and — for
     * backwards compatibility — the waypoint list in [routePoints].
     */
    fun setPlannedRoute(
        request: RouteRequest,
        result: RouteResult? = null,
        geometry: List<GeoPoint> = result?.polyline.orEmpty(),
    ) {
        val stops = request.stops()
        val clean = geometry.filter { it.isValidLatLon() }
        _routeOrigin.value = stops.firstOrNull()
        _routeDestination.value = if (stops.size >= 2) stops.last() else null
        _routeWaypoints.value = request.validWaypoints
        _routeProfile.value = request.profile
        // Providers do not all return the polyline origin-first: measured 2026-11-02, starting the
        // simulation on a 3 km planned route teleported to the destination and stayed there. Normalise
        // the direction so the player always begins at the origin.
        val origin = _routeOrigin.value
        val ordered = if (
            origin != null && clean.size >= 2 &&
            GeoMath.distanceMeters(clean.first(), origin) > GeoMath.distanceMeters(clean.last(), origin)
        ) clean.reversed() else clean
        _routePath.value = ordered
        // Keep the editable waypoint list in sync so the legacy 路线 panel and GPX export still
        // describe the same route.
        _routePoints.value = stops

        prefs.edit {
            putString(KEY_ROUTE_ORIGIN, encodePoint(stops.firstOrNull()))
            putString(KEY_ROUTE_DESTINATION, encodePoint(stops.lastOrNull()))
            putString(KEY_ROUTE_PROFILE, request.profile.id)
        }
        writePoints(KEY_ROUTE_WAYPOINTS, request.validWaypoints)
        writePoints(KEY_ROUTE_PATH, ordered)
        writePoints(KEY_ROUTE, stops)
    }

    /** Geometry-only update (e.g. a re-plan that kept the same stops). */
    fun setRoutePath(points: List<GeoPoint>) {
        val clean = points.filter { it.isValidLatLon() }
        // Providers do not all return the polyline origin-first: measured 2026-11-02, starting the
        // simulation on a 3 km planned route teleported to the destination and stayed there. Normalise
        // the direction so the player always begins at the origin.
        val origin = _routeOrigin.value
        val ordered = if (
            origin != null && clean.size >= 2 &&
            GeoMath.distanceMeters(clean.first(), origin) > GeoMath.distanceMeters(clean.last(), origin)
        ) clean.reversed() else clean
        _routePath.value = ordered
        writePoints(KEY_ROUTE_PATH, ordered)
    }

    /** Removes the planned route, keeping any raw waypoints the user had put on the map. */
    fun clearPlannedRoute() {
        _routePath.value = emptyList()
        _routeOrigin.value = null
        _routeDestination.value = null
        _routeWaypoints.value = emptyList()
        prefs.edit {
            remove(KEY_ROUTE_ORIGIN)
            remove(KEY_ROUTE_DESTINATION)
            remove(KEY_ROUTE_WAYPOINTS)
            remove(KEY_ROUTE_PATH)
        }
    }

    fun setRouteProfile(profile: RouteProfile) {
        _routeProfile.value = profile
        prefs.edit { putString(KEY_ROUTE_PROFILE, profile.id) }
    }

    /**
     * Sets the playback speed used by the engine while following a plan.
     *
     * The route planner writes the chosen travel mode's speed here right before starting playback,
     * so the simulated ETA matches what the result card promised (a 3 km 驾车 route advances at
     * ~13.9 m/s instead of the generic 2 m/s default). Sanitized by [SimulationConfig.sanitized].
     */
    fun setRouteSpeedMps(mps: Double) {
        if (!mps.isFinite() || mps <= 0.0) return
        updateConfig { it.copy(routeSpeedMps = mps) }
    }

    private fun setRouteStops(
        origin: GeoPoint?,
        destination: GeoPoint?,
        waypoints: List<GeoPoint>,
    ) {
        _routeOrigin.value = origin
        _routeDestination.value = destination
        _routeWaypoints.value = waypoints
        prefs.edit {
            putString(KEY_ROUTE_ORIGIN, encodePoint(origin))
            putString(KEY_ROUTE_DESTINATION, encodePoint(destination))
        }
        writePoints(KEY_ROUTE_WAYPOINTS, waypoints)
    }

    // ------------------------------------------------------------ saved routes

    /** Persists [route] (newest first, capped at [SAVED_ROUTE_LIMIT]). */
    fun saveRoute(route: SavedRoute): List<SavedRoute> {
        val next = (listOf(route) + _savedRoutes.value.filter { it.id != route.id })
            .take(SAVED_ROUTE_LIMIT)
        _savedRoutes.value = next
        writeJson(KEY_SAVED_ROUTES, ListSerializer(SavedRoute.serializer()), next)
        return next
    }

    /** Renames a saved route in place (keeps its position in the list). */
    fun renameSavedRoute(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val next = _savedRoutes.value.map { if (it.id == id) it.copy(name = clean) else it }
        _savedRoutes.value = next
        writeJson(KEY_SAVED_ROUTES, ListSerializer(SavedRoute.serializer()), next)
    }

    /** Puts a deleted route back at [index] (snackbar 撤销). */
    fun restoreSavedRoute(route: SavedRoute, index: Int) {
        val current = _savedRoutes.value.filter { it.id != route.id }.toMutableList()
        current.add(index.coerceIn(0, current.size), route)
        val next = current.take(SAVED_ROUTE_LIMIT)
        _savedRoutes.value = next
        writeJson(KEY_SAVED_ROUTES, ListSerializer(SavedRoute.serializer()), next)
    }

    fun deleteSavedRoute(id: String) {
        val next = _savedRoutes.value.filter { it.id != id }
        _savedRoutes.value = next
        writeJson(KEY_SAVED_ROUTES, ListSerializer(SavedRoute.serializer()), next)
    }

    private fun readSavedRoutes(): List<SavedRoute> = try {
        val raw = prefs.getString(KEY_SAVED_ROUTES, null)
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            json.decodeFromString(ListSerializer(SavedRoute.serializer()), raw)
                .filter { it.origin.isValidLatLon() && it.destination.isValidLatLon() }
                .take(SAVED_ROUTE_LIMIT)
        }
    } catch (t: Throwable) {
        emptyList()
    }

    // ------------------------------------------------------------- recent places

    /** Records a searched place for the 最近使用 list (newest first, de-duplicated by name+point). */
    fun addRecentPlace(name: String, point: GeoPoint) {
        if (!point.isValidLatLon()) return
        val clean = name.trim().ifBlank { formatPoint(point) }
        val existing = _recentPlaces.value.filterNot {
            it.name == clean && it.point.lat == point.lat && it.point.lon == point.lon
        }
        val next = (listOf(RecentPlace(clean, point)) + existing).take(RECENT_PLACE_LIMIT)
        _recentPlaces.value = next
        try {
            prefs.edit {
                putString(
                    KEY_RECENT_PLACES,
                    next.joinToString("\n") { "${it.name.replace('\n', ' ')}\t${it.point.lat}\t${it.point.lon}" },
                )
            }
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun readRecentPlaces(): List<RecentPlace> = try {
        prefs.getString(KEY_RECENT_PLACES, null)
            ?.lines()
            ?.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 3) return@mapNotNull null
                val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                val lon = parts[2].toDoubleOrNull() ?: return@mapNotNull null
                val point = GeoPoint(lat, lon)
                if (!point.isValidLatLon()) return@mapNotNull null
                RecentPlace(parts[0], point)
            }
            .orEmpty()
            .take(RECENT_PLACE_LIMIT)
    } catch (t: Throwable) {
        emptyList()
    }

    // ------------------------------------------------------------- serialization

    private fun readPoints(key: String): List<GeoPoint> = try {
        val raw = prefs.getString(key, null)
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            json.decodeFromString(ListSerializer(GeoPoint.serializer()), raw)
                .filter { it.isValidLatLon() }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun writePoints(key: String, points: List<GeoPoint>) {
        try {
            prefs.edit {
                putString(key, json.encodeToString(ListSerializer(GeoPoint.serializer()), points))
            }
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun readPoint(key: String): GeoPoint? {
        val encoded = prefs.getString(key, null) ?: return null
        val parts = encoded.split(',')
        if (parts.size < 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        val alt = parts.getOrNull(2)?.toDoubleOrNull() ?: 50.0
        val point = GeoPoint(lat, lon, alt)
        return point.takeIf { it.isValidLatLon() }
    }

    private fun encodePoint(point: GeoPoint?): String? =
        point?.let { "${it.lat},${it.lon},${it.alt}" }

    private fun formatPoint(point: GeoPoint): String =
        String.format(java.util.Locale.US, "%.5f, %.5f", point.lat, point.lon)

    private fun <T> writeJson(key: String, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        try {
            prefs.edit { putString(key, json.encodeToString(serializer, value)) }
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun readRoutePoints(): List<GeoPoint> = readPoints(KEY_ROUTE)

    // ------------------------------------------------------------- tile url

    fun setTileUrl(url: String) {
        val clean = url.trim().ifBlank { DEFAULT_TILE_URL }
        _tileUrl.value = clean
        prefs.edit { putString(KEY_TILE_URL, clean) }
    }

    fun resetTileUrl() = setTileUrl(DEFAULT_TILE_URL)

    fun setTilePreset(preset: TilePreset) {
        _tilePreset.value = preset
        prefs.edit { putString(KEY_TILE_PRESET, preset.id) }
    }

    fun setTiandituKey(key: String) {
        val clean = key.trim()
        _tiandituKey.value = clean
        prefs.edit { putString(KEY_TIANDITU_KEY, clean) }
    }

    // ---------------------------------------------------------- last position

    fun setLastPosition(point: GeoPoint) {
        if (!point.isValidLatLon()) return
        _lastPosition.value = point
        prefs.edit {
            putString(KEY_LAST_POSITION, "${point.lat},${point.lon},${point.alt}")
        }
        addRecentPosition(point)
    }

    // ------------------------------------------------------------ recent positions

    private val _recentPositions = MutableStateFlow(readRecent())
    /** Last few teleport targets, newest first (max [RECENT_LIMIT]). */
    val recentPositions: StateFlow<List<GeoPoint>> = _recentPositions.asStateFlow()

    /** Records a teleport target for the 最近使用 quick list, de-duplicating by ~11 m. */
    fun addRecentPosition(point: GeoPoint) {
        if (!point.isValidLatLon()) return
        val current = _recentPositions.value
        val same = current.firstOrNull { it.lat == point.lat && it.lon == point.lon }
        if (same != null) return
        val next = (listOf(point) + current).take(RECENT_LIMIT)
        _recentPositions.value = next
        try {
            prefs.edit {
                putString(KEY_RECENT, json.encodeToString(ListSerializer(GeoPoint.serializer()), next))
            }
        } catch (t: Throwable) {
            // ignore
        }
    }

    /** 清除最近使用: forgets the recent teleport targets and the recently searched places. */
    fun clearRecents() {
        _recentPositions.value = emptyList()
        _recentPlaces.value = emptyList()
        try {
            prefs.edit {
                remove(KEY_RECENT)
                remove(KEY_RECENT_PLACES)
            }
        } catch (t: Throwable) {
            // ignore
        }
    }

    /** Removes one recent teleport target. */
    fun removeRecentPosition(point: GeoPoint) {
        val next = _recentPositions.value.filterNot { it.lat == point.lat && it.lon == point.lon }
        _recentPositions.value = next
        try {
            prefs.edit {
                putString(KEY_RECENT, json.encodeToString(ListSerializer(GeoPoint.serializer()), next))
            }
        } catch (t: Throwable) {
            // ignore
        }
    }

    // --------------------------------------------------------------- start page

    private val _startPage = MutableStateFlow(prefs.getString(KEY_START_PAGE, null) ?: START_PAGE_LAST)

    /** Which tab the app opens on: [START_PAGE_LAST] (default), [START_PAGE_MAP] or [START_PAGE_QUICK]. */
    val startPage: StateFlow<String> = _startPage.asStateFlow()

    /** The tab that was on screen last, restored when [startPage] is [START_PAGE_LAST]. */
    var lastTab: String?
        get() = prefs.getString(KEY_LAST_TAB, null)
        set(value) = prefs.edit { putString(KEY_LAST_TAB, value) }

    fun setStartPage(page: String) {
        _startPage.value = page
        prefs.edit { putString(KEY_START_PAGE, page) }
    }

    private fun readRecent(): List<GeoPoint> = try {
        val raw = prefs.getString(KEY_RECENT, null)
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            json.decodeFromString(ListSerializer(GeoPoint.serializer()), raw)
                .filter { it.isValidLatLon() }
                .take(RECENT_LIMIT)
        }
    } catch (t: Throwable) {
        emptyList()
    }

    // ------------------------------------------------------------- coach mark

    /** `true` once the one-time map coach mark has been shown (it must not nag on every launch). */
    var coachMarkShown: Boolean
        get() = prefs.getBoolean(KEY_COACH_MARK, false)
        set(value) = prefs.edit { putBoolean(KEY_COACH_MARK, value) }

    private fun readLastPosition(): GeoPoint = try {
        val raw = prefs.getString(KEY_LAST_POSITION, null)
        val parts = raw?.split(',')
        if (parts != null && parts.size == 3) {
            val lat = parts[0].toDouble()
            val lon = parts[1].toDouble()
            val alt = parts[2].toDouble()
            GeoPoint(lat, lon, alt).takeIf { it.isValidLatLon() } ?: DEFAULT_POSITION
        } else {
            DEFAULT_POSITION
        }
    } catch (t: Throwable) {
        DEFAULT_POSITION
    }

    companion object {
        private const val PREFS_NAME = "immoral_settings"
        private const val KEY_CONFIG = "config_json"
        private const val KEY_ROUTE = "route_points_json"
        private const val KEY_TILE_URL = "tile_url"
        private const val KEY_LAST_POSITION = "last_position"
        private const val KEY_RECENT = "recent_positions_json"
        private const val KEY_COACH_MARK = "coach_mark_shown"
        private const val KEY_TILE_PRESET = "tile_preset"
        private const val KEY_TIANDITU_KEY = "tianditu_key"
        private const val KEY_ROUTE_PATH = "route_path_json"
        private const val KEY_ROUTE_ORIGIN = "route_origin"
        private const val KEY_ROUTE_DESTINATION = "route_destination"
        private const val KEY_ROUTE_WAYPOINTS = "route_waypoints_json"
        private const val KEY_ROUTE_PROFILE = "route_profile"
        private const val KEY_SAVED_ROUTES = "saved_routes_json"
        private const val KEY_RECENT_PLACES = "recent_places"
        private const val KEY_START_PAGE = "start_page"

        private const val KEY_LAST_TAB = "last_tab"

        const val START_PAGE_LAST: String = "last"
        const val START_PAGE_QUICK: String = "quick"
        const val START_PAGE_MAP: String = "map"

        /**
         * Default tile server: the canonical OSM carto style. Unlike the German mirror it renders
         * **local** (Chinese) place names, which is what a Chinese audience expects; it is also the
         * most policy-clean endpoint. Users behind a restrictive network can switch to the mirror
         * or supply their own `{z}/{x}/{y}` template in 设置.
         */
        const val DEFAULT_TILE_URL: String = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

        /** Reachable-from-China OSM mirror; German place names, but fast. */
        const val TILE_PRESET_OSM_DE: String = "https://tile.openstreetmap.de/{z}/{x}/{y}.png"

        /** Unofficial AMap raster endpoint (Chinese labels, mainland CDN). User's own compliance. */
        const val TILE_PRESET_AMAP: String =
            "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}"

        /** Tiananmen Square — a neutral, well-known starting point for a Chinese audience. */
        val DEFAULT_POSITION: GeoPoint = GeoPoint(39.908_692, 116.397_532, 50.0)

        /** How many recently used positions are kept for the 跳转到坐标 quick list. */
        const val RECENT_LIMIT: Int = 5

        /** How many routes the 路线计划 saved-routes list keeps. */
        const val SAVED_ROUTE_LIMIT: Int = 20

        /** How many searched places the route planner's 最近使用 list keeps. */
        const val RECENT_PLACE_LIMIT: Int = 8
    }
}



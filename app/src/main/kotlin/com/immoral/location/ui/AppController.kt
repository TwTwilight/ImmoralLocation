package com.immoral.location.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.immoral.location.App
import com.immoral.location.BuildConfig
import com.immoral.location.R
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.FavoritePlace
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.MoveMode
import com.immoral.location.core.model.RouteState
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.model.SpeedLevel
import com.immoral.location.core.route.RouteIO
import com.immoral.location.core.route.RoutePlan
import com.immoral.location.map.TilePreset
import com.immoral.location.net.AmapPoiSearch
import com.immoral.location.net.CoordinateParser
import com.immoral.location.net.PlaceSearchClient
import com.immoral.location.net.SearchOutcome
import com.immoral.location.service.SimulationService
import com.immoral.location.ui.route.RoutePlannerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * All user intents of the app, in one place.
 *
 * Screens stay dumb (they render state and call back); this controller owns the sequencing —
 * persist the change, push it to the engine over the bridge, and surface a snackbar. Keeping it out
 * of the composables makes the screens trivially previewable and keeps the config/persistence
 * invariants in one file.
 */
class AppController(
    private val app: App,
    private val scope: CoroutineScope,
) {

    val settings = app.settings
    val favorites = app.favorites
    val bridge = app.bridge

    /**
     * Route planner state machine + UI model (owned by the route-planner work face).
     *
     * It is constructed lazily here, with this controller's own gateways wired in, so the planner
     * never reaches into `App`, the bridge or SharedPreferences directly: search goes through
     * [searchPlaces] (same identity pool / caching as the search bar), playback goes through
     * [startRoute] (which reads the freshly persisted road geometry), and every user-facing
     * message goes through the one [showMessage] funnel.
     */
    val routePlanner: RoutePlannerController by lazy {
        RoutePlannerController(
            context = app,
            settings = settings,
            scope = scope,
            search = { query, done -> searchPlaces(query, done) },
            currentPosition = { currentPosition() },
            recentPoints = { settings.recentPositions.value },
            rememberRecent = { point -> rememberRecent(point) },
            startPlayback = { startRoute() },
            onMessage = { text -> showMessage(text) },
        )
    }

    /** Replaced by the host so messages land in the app's snackbar. */
    var showMessage: (String) -> Unit = {}

    /** Snackbar with one action (撤销); replaced by the host. */
    var showAction: (message: String, action: String, onAction: () -> Unit) -> Unit =
        { message, _, _ -> showMessage(message) }

    // ----------------------------------------------------------- configuration

    fun updateConfig(transform: (SimulationConfig) -> SimulationConfig) {
        val next = settings.updateConfig(transform)
        bridge.setConfig(next)
        bridge.publishConfig(next)
    }

    fun setSpeed(level: SpeedLevel) = updateConfig { it.copy(speed = level) }

    fun setCustomSpeed(mps: Double) = updateConfig {
        it.copy(speed = SpeedLevel.CUSTOM, customSpeedMps = mps)
    }

    fun setFollowHeading(follow: Boolean) = updateConfig {
        it.copy(followHeading = follow, mode = MoveMode.JOYSTICK)
    }

    fun setFixedBearing(deg: Double) = updateConfig { it.copy(fixedBearingDeg = deg) }

    fun setRouteRepeat(repeat: Boolean) = updateConfig { it.copy(routeRepeat = repeat) }

    fun setRouteSpeed(mps: Double) = updateConfig { it.copy(routeSpeedMps = mps) }

    fun resetSettings() {
        settings.resetConfig()
        bridge.setConfig(settings.config.value)
        showMessage(app.getString(R.string.settings_reset))
    }

    fun setTileUrl(url: String) {
        settings.setTileUrl(url)
        showMessage(app.getString(R.string.shell_saved))
    }

    fun setTilePreset(preset: TilePreset) {
        settings.setTilePreset(preset)
        showMessage(app.getString(preset.labelRes))
    }

    fun setTiandituKey(key: String) {
        settings.setTiandituKey(key)
        showMessage(app.getString(R.string.shell_saved))
    }

    // ---------------------------------------------------------------- search

    private var searchClient: PlaceSearchClient? = null
    private var searchClientKey: String? = null

    private fun placeSearch(): PlaceSearchClient {
        val key = settings.tiandituKey.value
        val stamp = key + "|" + AmapPoiSearch.activeIdentity(app)
        val existing = searchClient
        if (existing != null && searchClientKey == stamp) return existing
        return PlaceSearchClient(
            userAgent = "ImmoralLocation/${BuildConfig.VERSION_NAME} (Android)",
            tiandituKey = key,
            amap = AmapPoiSearch(app),
        ).also {
            searchClient = it
            searchClientKey = stamp
        }
    }

    /**
     * Geocodes [query] off the main thread (天地图 when a key exists, else Nominatim).
     * [onDone] is invoked on the main thread with either results or a Chinese error message.
     */
    fun searchPlaces(query: String, onDone: (SearchOutcome) -> Unit) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    placeSearch().search(query)
                } catch (t: Throwable) {
                    SearchOutcome.Failure(t.message ?: app.getString(R.string.search_network_error))
                }
            }
            onDone(outcome)
        }
    }

    /** 坐标直达: teleports to a typed coordinate. Returns `false` (with a message) when invalid. */
    fun jumpToCoordinateInput(text: String): Boolean {
        val point = CoordinateParser.parse(text)
        if (point == null) {
            showMessage(app.getString(R.string.search_bad_coord))
            return false
        }
        setPosition(point)
        return true
    }

    /** Records a picked search result in the 最近使用 list. */
    fun rememberRecent(point: GeoPoint) = settings.addRecentPosition(point)

    // ---------------------------------------------------------------- engine

    fun currentPosition(): GeoPoint {
        val fromEngine = bridge.engineState.value?.point
        if (fromEngine != null && fromEngine.isValid()) return fromEngine
        return settings.lastPosition.value
    }

    fun isRunning(): Boolean = bridge.engineState.value?.running == true

    fun toggleRun() {
        if (isRunning()) stopSimulation() else startSimulation()
    }

    /**
     * Tells the framework whether our own UI is on screen. The engine status row it publishes lives
     * in `Settings.Global` and is readable by every app, so it is only kept while it can be seen.
     */
    fun setUiVisible(visible: Boolean) {
        bridge.setUiVisible(visible)
    }

    fun startSimulation() {
        val config = settings.config.value
        val position = currentPosition()
        bridge.setPosition(position)
        bridge.setConfig(config)
        bridge.startEngine()
        SimulationService.start(app, SimulationService.ACTION_START_ENGINE)
        showMessage(app.getString(R.string.status_running))
    }

    fun stopSimulation() {
        bridge.releaseJoystick()
        bridge.stopEngine()
        SimulationService.command(app, SimulationService.ACTION_SYNC_CONFIG)
    }

    /**
     * Teleports the simulation (map tap / 收藏 return / route jump).
     *
     * A teleport is an explicit "put me here" intent, so a stopped engine is started first: the
     * framework stores `SetPosition` even while stopped but injects nothing until `Start`
     * (`EngineRuntime.activeSnap()` returns null when not running), which used to make a favourite
     * tap look like a no-op.
     *
     * Deliberately silent: a map tap is now answered by the map's own `e2e.tapchip` affordance
     * (coordinates + 撤销 / 收藏 / 加入路线), and callers with a message of their own —
     * [jumpToFavorite], the route planner — raise it themselves. A snackbar here too would double
     * every tap into two overlapping pieces of feedback, which is the overlap complaint this round
     * is fixing.
     */
    fun setPosition(point: GeoPoint) {
        if (!point.isValidLatLon()) return
        // Always (re)assert the engine, not only when our cached state says "stopped": the cache can
        // be stale-true while the engine is actually down (measured 2026-11-02 - a favourite tap sent
        // SetPosition to a stopped engine, the peek updated from the stored state, and the framework
        // fix never moved). startEngine is idempotent, so this is a cheap guarantee that a jump to a
        // point always produces a fix.
        bridge.setConfig(settings.config.value)
        bridge.startEngine()
        SimulationService.start(app, SimulationService.ACTION_START_ENGINE)
        bridge.setPosition(point)
        settings.setLastPosition(point)
    }

    // --------------------------------------------------------------- joystick

    fun joystick(x: Float, y: Float) {
        val config = settings.config.value
        if (config.mode != MoveMode.JOYSTICK) {
            updateConfig { it.copy(mode = MoveMode.JOYSTICK) }
        }
        bridge.setJoystick(x, y)
    }

    fun releaseJoystick() = bridge.releaseJoystick()

    fun setOverlayEnabled(enabled: Boolean) {
        if (!enabled) {
            SimulationService.command(app, SimulationService.ACTION_HIDE_OVERLAY)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) {
            showMessage(app.getString(R.string.overlay_permission_needed))
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${app.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(intent) }
            return
        }
        SimulationService.start(app, SimulationService.ACTION_TOGGLE_OVERLAY)
    }

    // ----------------------------------------------------------------- route

    fun addRoutePoint(point: GeoPoint) {
        if (!point.isValidLatLon()) return
        settings.addRoutePoint(point)
        showMessage(app.getString(R.string.map_route_append))
    }

    fun addCurrentToRoute() = addRoutePoint(currentPosition())

    fun moveRoutePoint(from: Int, to: Int) = settings.moveRoutePoint(from, to)

    fun removeRoutePoint(index: Int) = settings.removeRoutePoint(index)

    fun clearRoute() = settings.clearRoute()

    /** Signature of the plan currently loaded, so the same plan is never re-seeded. */
    private var lastRouteSignature: String? = null

    /**
     * 播放 / 开始路线 — an explicit user action, the only place that sends `StartRoute`.
     *
     * The plan carries its own `speedMps`/`repeat` so the engine's route speed is authoritative and
     * cannot be retuned by a configuration push. Re-tapping 播放 while the very same plan is already
     * playing resumes it (`PlayRoute`) instead of re-seeding the route from the first point; progress
     * is read from `EngineState.routeProgress`/`routeState`, never re-sent.
     */
    fun startRoute() {
        // Road-level geometry wins when a route was planned by the route planner; hand-tapped
        // points / GPX imports (settings.routePoints) remain the fallback so both flows work.
        val points = settings.routePath.value.ifEmpty { settings.routePoints.value }
        if (points.size < 2) return
        val config = settings.config.value
        val plan = RoutePlan(
            points = points,
            repeat = config.routeRepeat,
            speedMps = config.routeSpeedMps,
        )
        val signature = routeSignature(plan)
        val routeState = bridge.engineState.value?.routeState
        if (signature == lastRouteSignature && routeState == RouteState.PLAYING) {
            bridge.playRoute()
            return
        }
        // Persist the mode change (not just push it) so every other writer — the foreground
        // service in particular — sees the same config and cannot re-publish a stale mode on top.
        updateConfig { it.copy(mode = MoveMode.ROUTE) }
        bridge.startRoute(plan)
        lastRouteSignature = signature
        SimulationService.start(app, SimulationService.ACTION_START_ENGINE)
    }

    private fun routeSignature(plan: RoutePlan): String = buildString {
        append(plan.speedMps).append('|').append(plan.repeat).append('|').append(plan.points.size)
        for (point in plan.points) {
            append(';').append(point.lat).append(',').append(point.lon).append(',').append(point.alt)
        }
    }

    fun playRoute() = bridge.playRoute()

    fun pauseRoute() = bridge.pauseRoute()

    fun stopRoute() = bridge.stopRoute()

    fun importGpx(uri: Uri) {
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val text = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    val points = if (text.isNullOrBlank()) emptyList() else RouteIO.parseGpx(text)
                    if (points.isEmpty()) {
                        app.getString(R.string.route_gpx_empty)
                    } else {
                        settings.setRoutePoints(points)
                        app.getString(R.string.route_gpx_imported, points.size)
                    }
                } catch (t: Throwable) {
                    app.getString(R.string.route_gpx_failed, t.message ?: t.javaClass.simpleName)
                }
            }
            showMessage(message)
        }
    }

    fun exportGpx(uri: Uri) {
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val points = settings.routePoints.value
                    val gpx = RouteIO.toGpx(points)
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(gpx.toByteArray(Charsets.UTF_8))
                    }
                    app.getString(R.string.route_gpx_exported)
                } catch (t: Throwable) {
                    app.getString(R.string.route_gpx_failed, t.message ?: t.javaClass.simpleName)
                }
            }
            showMessage(message)
        }
    }

    // ------------------------------------------------------------- favorites

    fun addFavorite(name: String, point: GeoPoint) {
        val created = favorites.add(name, point)
        if (created == null) {
            showMessage(app.getString(R.string.favorites_invalid_position))
        } else {
            showMessage(
                app.getString(
                    R.string.shell_favorite_saved,
                    com.immoral.location.ui.components.FavoriteText.displayName(created.name),
                ),
            )
        }
    }

    fun addCurrentFavorite() {
        val position = currentPosition()
        if (!position.isValid()) {
            showMessage(app.getString(R.string.favorites_invalid_position))
            return
        }
        favorites.add("", position)
        showMessage(app.getString(R.string.favorites_saved))
    }

    fun renameFavorite(place: FavoritePlace, name: String) = favorites.rename(place.id, name)

    fun deleteFavorite(place: FavoritePlace) = favorites.remove(place.id)

    /** Deletes [place] and offers 撤销 in the snackbar. */
    fun deleteFavoriteWithUndo(place: FavoritePlace) {
        val index = favorites.favorites.value.indexOfFirst { it.id == place.id }
        favorites.remove(place.id)
        showAction(
            app.getString(R.string.favorites_deleted),
            app.getString(R.string.tap_chip_undo),
        ) { favorites.restore(place, index) }
    }

    /** Deletes a saved route and offers 撤销. */
    fun deleteSavedRouteWithUndo(route: com.immoral.location.core.route.SavedRoute) {
        val index = routePlanner.deleteSaved(route)
        showAction(
            app.getString(R.string.shell_route_deleted),
            app.getString(R.string.tap_chip_undo),
        ) { settings.restoreSavedRoute(route, index) }
    }

    fun renameSavedRoute(route: com.immoral.location.core.route.SavedRoute, name: String) =
        routePlanner.renameSaved(route, name)

    /** 快捷 page: one tap on a saved route plans it and starts playback as soon as it lands. */
    fun startSavedRoute(route: com.immoral.location.core.route.SavedRoute) {
        routePlanner.loadSaved(route, startWhenReady = true)
        showMessage(app.getString(R.string.shell_route_preparing, route.name))
    }

    /**
     * 去这里 from a list (收藏 / 最近 / 自定义): teleport, remember it, and say where we are now.
     */
    fun goTo(point: GeoPoint, label: String) {
        if (!point.isValidLatLon()) {
            showMessage(app.getString(R.string.favorites_invalid_position))
            return
        }
        setPosition(point)
        settings.addRecentPlace(label, point)
        showMessage(app.getString(R.string.shell_went_to, label))
    }

    /**
     * 导航到这里: the planner opens with this point as destination and the live position as origin.
     * [label] (a searched place name, when we have one) is kept so the saved route can be called
     * something better than two coordinates.
     */
    fun planTo(point: GeoPoint, label: String) {
        routePlanner.resetOrigin()
        routePlanner.setDestination(com.immoral.location.ui.route.RoutePlace(name = label, point = point))
    }

    fun clearRecents() {
        settings.clearRecents()
        showMessage(app.getString(R.string.shell_recents_cleared))
    }

    fun setStartPage(page: String) = settings.setStartPage(page)

    fun jumpToFavorite(place: FavoritePlace) {
        setPosition(place.point)
        showMessage(app.getString(R.string.favorites_jumped, place.name))
    }

    /** Distance from the current simulated position, for the favourites list. */
    fun distanceFromCurrent(place: FavoritePlace): Double =
        GeoMath.distanceMeters(currentPosition(), place.point)
}

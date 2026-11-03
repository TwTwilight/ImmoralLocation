package com.immoral.location.ui.route

import android.content.Context
import com.immoral.location.R
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.route.RouteProfile
import com.immoral.location.core.route.RouteRequest
import com.immoral.location.core.route.RouteResult
import com.immoral.location.core.route.SavedRoute
import com.immoral.location.data.RecentPlace
import com.immoral.location.data.SettingsStore
import com.immoral.location.net.RouteClient
import com.immoral.location.net.RouteFailure
import com.immoral.location.net.SearchOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A stop the user picked, with the label shown in its field. */
data class RoutePlace(
    val name: String,
    val point: GeoPoint,
)

/** Which kind of field the inline search screen is filling. */
enum class RouteFieldKind { ORIGIN, DESTINATION, WAYPOINT }

/**
 * The field currently being edited by the search screen.
 *
 * @property index Waypoint position for [RouteFieldKind.WAYPOINT]; `-1` means "append a new one".
 */
data class RouteFieldTarget(
    val kind: RouteFieldKind,
    val index: Int = -1,
)

/**
 * State + behaviour of the 路径规划 sheet.
 *
 * The composable [RoutePlannerHost] is a pure projection of this class: every field, the planning
 * spinner, the result card and the search screen read a [StateFlow] and call one method. All the
 * wiring the sheet needs from the rest of the app is passed as lambdas, so this class never
 * touches `AppController`, the bridge or the engine directly (that keeps it previewable and keeps
 * the seams explicit):
 *
 *  * [search] — geocode a query (callback on the main thread), the app's `PlaceSearchClient` path;
 *  * [currentPosition] — the live simulated position, the default origin;
 *  * [startPlayback] — load the (already persisted) plan into the engine;
 *  * [onMessage] — show a snackbar (never a Toast).
 *
 * Planning runs on [dispatcher] and never throws: a network failure degrades to a straight line
 * and surfaces a non-blocking [notice], per docs/UIUX-REWORK.md §6.6.
 */
class RoutePlannerController(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val search: (String, (SearchOutcome) -> Unit) -> Unit,
    private val currentPosition: () -> GeoPoint,
    private val recentPoints: () -> List<GeoPoint> = { emptyList() },
    private val rememberRecent: (GeoPoint) -> Unit = {},
    private val startPlayback: () -> Unit = {},
    private val onMessage: (String) -> Unit = {},
    private val client: RouteClient = RouteClient(context),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    // ------------------------------------------------------------------ fields

    private val _origin = MutableStateFlow<RoutePlace?>(null)

    /** Start field; never `null` after [resetOrigin] — defaults to the simulated position. */
    val origin: StateFlow<RoutePlace?> = _origin.asStateFlow()

    private val _destination = MutableStateFlow<RoutePlace?>(null)

    /** Destination field; `null` until the user picks one. */
    val destination: StateFlow<RoutePlace?> = _destination.asStateFlow()

    private val _waypoints = MutableStateFlow<List<RoutePlace>>(emptyList())

    /** Intermediate stops in travel order. */
    val waypoints: StateFlow<List<RoutePlace>> = _waypoints.asStateFlow()

    private val _profile = MutableStateFlow(settings.routeProfile.value)

    /** Travel mode; changing it re-plans automatically when a route already exists. */
    val profile: StateFlow<RouteProfile> = _profile.asStateFlow()

    // ------------------------------------------------------------------ result

    private val _result = MutableStateFlow<RouteResult?>(null)

    /** Last planned geometry; `null` until the first successful plan. */
    val result: StateFlow<RouteResult?> = _result.asStateFlow()

    private val _planning = MutableStateFlow(false)

    /** `true` while a plan request is in flight (drives the spinner / disabled primary button). */
    val planning: StateFlow<Boolean> = _planning.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /**
     * Non-blocking explanation shown under the result card, e.g. "未取得道路路径，使用直线连接".
     *
     * This is deliberately *not* a snackbar: the degradation is part of the result, so it belongs
     * next to it and must never block the simulation from starting.
     */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    // ------------------------------------------------------------------ search

    private val _searchTarget = MutableStateFlow<RouteFieldTarget?>(null)

    /** Non-null while the inline search screen is open. */
    val searchTarget: StateFlow<RouteFieldTarget?> = _searchTarget.asStateFlow()

    private val _searchQuery = MutableStateFlow("")

    /** Text in the search field. */
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<RoutePlace>>(emptyList())

    /** Results of the last search. */
    val searchResults: StateFlow<List<RoutePlace>> = _searchResults.asStateFlow()

    private val _searching = MutableStateFlow(false)

    /** `true` while a geocode request is in flight. */
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)

    /** Non-blocking search error, e.g. "网络不可用，请检查网络后重试". */
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    // ---------------------------------------------------------------- delegated

    /** Routes the user saved; owned by [SettingsStore]. */
    val savedRoutes: StateFlow<List<SavedRoute>> get() = settings.savedRoutes

    /** Recently picked places, for the search screen's 最近使用 list. */
    val recentPlaces: StateFlow<List<RecentPlace>> get() = settings.recentPlaces

    /** Recently teleported-to points, shown as plain coordinates in the search screen. */
    fun recentPoints(): List<GeoPoint> = recentPoints.invoke()

    private val _pendingStartId = MutableStateFlow<String?>(null)

    /**
     * Id of the saved route that is being planned in order to start right away (快捷页 一键开始).
     * The quick page shows a spinner on that row instead of a play button.
     */
    val pendingStartId: StateFlow<String?> = _pendingStartId.asStateFlow()

    private var planJob: Job? = null

    /** `31.2304,121.4787` / `31.2304 121.4787` / `31.2304，121.4787` — nothing but coordinates. */
    private val coordinateOnlyPattern = Regex("^[-+0-9.,;\\s\\uFF0C\\u3001]+$")

    init {
        // Restore the last plan's stop list so re-opening the sheet does not look empty.
        settings.routeOrigin.value?.let { point ->
            _origin.value = RoutePlace(RoutePlannerStrings.coordinateLabel(point), point)
        }
        settings.routeDestination.value?.let { point ->
            _destination.value = RoutePlace(RoutePlannerStrings.coordinateLabel(point), point)
        }
        _waypoints.value = settings.routeWaypoints.value.map {
            RoutePlace(RoutePlannerStrings.coordinateLabel(it), it)
        }
        if (_origin.value == null) resetOrigin()
    }

    // ------------------------------------------------------------------ editing

    /** Sets the origin explicitly (search result, saved route, map long-press). */
    fun setOrigin(place: RoutePlace) {
        _origin.value = normalized(place)
        autoPlanIfComplete()
    }

    /** Sets the destination explicitly. */
    fun setDestination(place: RoutePlace) {
        _destination.value = normalized(place)
        autoPlanIfComplete()
    }

    /**
     * Normalizes the label shown in a stop field.
     *
     *  * a blank name (e.g. the map long-press "导航到这里" passes `""`) becomes a coordinate label —
     *    an empty field would look broken;
     *  * a *coordinate* result also becomes a compact coordinate label. `PlaceSearchClient` names a
     *    raw-coordinate hit `坐标 31.2304,121.4787`, which is too wide for a waypoint row that also
     *    carries reorder/delete buttons on a 360 dp sheet, and the prefix adds nothing there.
     */
    private fun normalized(place: RoutePlace): RoutePlace {
        val name = place.name.trim()
        val coordinateLike = name.isEmpty() ||
            name.startsWith("坐标") ||
            coordinateOnlyPattern.matches(name)
        return if (coordinateLike) {
            place.copy(name = RoutePlannerStrings.coordinateLabel(place.point))
        } else {
            place.copy(name = name)
        }
    }

    /** 设为当前位置: the origin becomes the live simulated position. */
    fun resetOrigin() {
        val position = currentPosition()
        _origin.value = if (position.isValid()) {
            RoutePlace(context.getString(R.string.rp_current_location), position)
        } else {
            null
        }
    }

    /** Appends a waypoint (a new stop is always the last intermediate stop). */
    fun addWaypoint(place: RoutePlace) {
        _waypoints.value = _waypoints.value + normalized(place)
        autoPlanIfComplete()
    }

    fun removeWaypoint(index: Int) {
        val current = _waypoints.value
        if (index !in current.indices) return
        _waypoints.value = current.filterIndexed { position, _ -> position != index }
        onMessage.invoke(context.getString(R.string.rp_removed_waypoint, index + 1))
        if (_result.value != null) plan()
    }

    fun moveWaypoint(from: Int, to: Int) {
        val current = _waypoints.value.toMutableList()
        if (from !in current.indices || to !in current.indices || from == to) return
        val moved = current.removeAt(from)
        current.add(to, moved)
        _waypoints.value = current
        if (_result.value != null) plan()
    }

    /** Swaps origin and destination (both must be set). */
    fun swapEnds() {
        val origin = _origin.value ?: return
        val destination = _destination.value ?: return
        _origin.value = destination
        _destination.value = origin
        if (_result.value != null) plan()
    }

    /**
     * Changes the travel mode; re-plans immediately when a route is already on screen.
     *
     * @param autoPlan `false` only for restoring state, so opening the sheet never fires requests.
     */
    fun setProfile(profile: RouteProfile, autoPlan: Boolean = true) {
        if (_profile.value == profile) return
        _profile.value = profile
        settings.setRouteProfile(profile)
        if (autoPlan && _result.value != null) plan()
    }

    /** The request described by the current fields, or `null` when it cannot be planned. */
    fun currentRequest(): RouteRequest? {
        val origin = _origin.value ?: return null
        val destination = _destination.value ?: return null
        if (!origin.point.isValidLatLon() || !destination.point.isValidLatLon()) return null
        return RouteRequest(
            origin = origin.point,
            destination = destination.point,
            waypoints = _waypoints.value.map { it.point },
            profile = _profile.value,
        )
    }

    // ---------------------------------------------------------------- planning

    private fun autoPlanIfComplete() {
        if (currentRequest()?.isUsable == true) plan()
    }

    /**
     * Plans the current fields.
     *
     * Cancels an in-flight request (mode switching is fast and must not race), runs the network
     * call on [dispatcher], persists the result so the map and the player see it immediately, and
     * publishes a non-blocking notice when the geometry is a fallback.
     */
    fun plan() {
        val request = currentRequest()
        if (request == null) {
            _pendingStartId.value = null
            _notice.value = context.getString(R.string.rp_error_missing)
            return
        }
        if (!request.isUsable) {
            _pendingStartId.value = null
            _notice.value = context.getString(R.string.rp_error_same_point)
            _result.value = null
            return
        }
        planJob?.cancel()
        planJob = scope.launch {
            _planning.value = true
            _notice.value = null
            val outcome = try {
                withContext(dispatcher) { client.plan(request) }
            } catch (t: Throwable) {
                // Defensive: the client already swallows network errors, but a bug must not
                // crash the sheet.
                _planning.value = false
                _notice.value = t.message ?: context.getString(R.string.rp_error_osrm)
                if (_pendingStartId.value != null) {
                    _pendingStartId.value = null
                    onMessage.invoke(_notice.value ?: context.getString(R.string.rp_error_osrm))
                }
                return@launch
            }
            _planning.value = false
            _result.value = outcome.result
            settings.setPlannedRoute(request, outcome.result)
            _notice.value = noticeFor(outcome.failures, outcome.degraded)
            if (_pendingStartId.value != null) {
                _pendingStartId.value = null
                if (outcome.result.isUsable) {
                    start()
                } else {
                    onMessage.invoke(context.getString(R.string.rp_error_osrm))
                }
            }
        }
    }

    private fun noticeFor(failures: List<RouteFailure>, degraded: Boolean): String? {
        if (!degraded) return null
        val reason = failures
            .filterNot { it == RouteFailure.NoRoute }
            .joinToString("、") { RoutePlannerStrings.reasonText(context, it) }
            .ifBlank { null }
        return if (reason == null) {
            context.getString(R.string.rp_degraded)
        } else {
            context.getString(R.string.rp_degraded_detail, reason)
        }
    }

    /**
     * 开始模拟: hands the planned geometry to the engine.
     *
     * The geometry is persisted **before** [startPlayback] runs, because the app's `startRoute()`
     * reads `settings.routePath` — the player must never see a stale plan.
     */
    fun start() {
        val request = currentRequest()
        val result = _result.value
        if (request == null) {
            _notice.value = context.getString(R.string.rp_error_missing)
            return
        }
        if (result == null || !result.isUsable) {
            // No plan yet (e.g. the user typed nothing): plan first, the plan button re-plans anyway.
            _notice.value = context.getString(R.string.rp_no_route_yet)
            return
        }
        // Match the simulation speed to the chosen travel mode *before* playback starts, so the
        // elapsed simulation time equals the ETA the result card promised (the engine reads
        // `routeSpeedMps` from the persisted config when the plan is loaded).
        settings.setRouteSpeedMps(_profile.value.defaultSpeedMps)
        settings.setPlannedRoute(request, result)
        startPlayback.invoke()
        onMessage.invoke(context.getString(R.string.rp_started))
    }

    /** 保存: stores the current stops (not the geometry) so they can be re-planned later. */
    fun save() {
        val request = currentRequest()
        if (request == null) {
            _notice.value = context.getString(R.string.rp_error_missing)
            return
        }
        val saved = SavedRoute.from(
            request = request,
            result = _result.value,
            name = defaultRouteName(),
            nowMs = System.currentTimeMillis(),
        )
        settings.saveRoute(saved)
        onMessage.invoke(context.getString(R.string.rp_saved))
    }

    /**
     * A readable default for a saved route: "王府井 → 北海公园" when both ends carry real names,
     * "前往 北海公园" when only the destination does, else "路线 N" — never two raw coordinates.
     */
    private fun defaultRouteName(): String {
        val origin = _origin.value?.name?.takeIf { isRealName(it) && it != context.getString(R.string.rp_current_location) }
        val destination = _destination.value?.name?.takeIf { isRealName(it) }
        return when {
            origin != null && destination != null -> "$origin → $destination"
            destination != null -> context.getString(R.string.shell_route_to, destination)
            else -> context.getString(R.string.shell_route_default_name, settings.savedRoutes.value.size + 1)
        }
    }

    private fun isRealName(name: String): Boolean =
        name.isNotBlank() && !name.startsWith("坐标") && !coordinateOnlyPattern.matches(name)

    /** Deletes a saved route; returns its former index so the caller can offer 撤销. */
    fun deleteSaved(route: SavedRoute): Int {
        val index = settings.savedRoutes.value.indexOfFirst { it.id == route.id }
        settings.deleteSavedRoute(route.id)
        return index
    }

    /** Renames a saved route in place. */
    fun renameSaved(route: SavedRoute, name: String) {
        settings.renameSavedRoute(route.id, name)
    }

    /**
     * Re-opens a saved route and plans it again.
     *
     * @param startWhenReady 	rue from the 快捷 page: playback starts as soon as the plan lands, so
     *   one tap on a saved route is "start this route", with no planner in between.
     */
    fun loadSaved(route: SavedRoute, startWhenReady: Boolean = false) {
        _pendingStartId.value = if (startWhenReady) route.id else null
        _origin.value = RoutePlace(RoutePlannerStrings.coordinateLabel(route.origin), route.origin)
        _destination.value = RoutePlace(
            RoutePlannerStrings.coordinateLabel(route.destination),
            route.destination,
        )
        _waypoints.value = route.waypoints.map {
            RoutePlace(RoutePlannerStrings.coordinateLabel(it), it)
        }
        _profile.value = route.profile
        settings.setRouteProfile(route.profile)
        plan()
    }

    /** 清除: drops the fields, the geometry and the persisted plan. */
    fun clear() {
        planJob?.cancel()
        planJob = null
        settings.clearRoute()
        _destination.value = null
        _waypoints.value = emptyList()
        _result.value = null
        _notice.value = null
        _planning.value = false
        resetOrigin()
        onMessage.invoke(context.getString(R.string.rp_cleared))
    }

    /** Dismisses the non-blocking notice. */
    fun dismissNotice() {
        _notice.value = null
    }

    // ------------------------------------------------------------------ search

    /** Opens the inline search screen for the given field. */
    fun openSearch(target: RouteFieldTarget) {
        _searchTarget.value = target
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _searchError.value = null
        _searching.value = false
    }

    /** Closes the inline search screen, keeping the fields as they are. */
    fun closeSearch() {
        _searchTarget.value = null
        _searchResults.value = emptyList()
        _searchError.value = null
        _searching.value = false
    }

    /** Updates the search text without running a request (typing is debounced by the sheet). */
    fun setSearchQuery(text: String) {
        _searchQuery.value = text
        if (text.isBlank()) {
            _searchResults.value = emptyList()
            _searchError.value = null
        }
    }

    /** Runs a geocode for [query] and publishes the results. */
    fun submitSearch(query: String) {
        _searchQuery.value = query
        val trimmed = query.trim()
        _searchError.value = null
        if (trimmed.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        _searching.value = true
        try {
            search.invoke(trimmed) { outcome ->
                _searching.value = false
                when (outcome) {
                    is SearchOutcome.Success -> {
                        _searchResults.value = outcome.results.map {
                            RoutePlace(it.name, it.point)
                        }
                        if (outcome.results.isEmpty()) {
                            _searchError.value = context.getString(R.string.rp_search_empty)
                        }
                    }

                    is SearchOutcome.Failure -> {
                        _searchResults.value = emptyList()
                        _searchError.value = outcome.message
                    }
                }
            }
        } catch (t: Throwable) {
            _searching.value = false
            _searchError.value = t.message ?: context.getString(R.string.rp_search_empty)
        }
    }

    /** Applies a search result (or a 最近使用 entry) to the field being edited. */
    fun pickPlace(place: RoutePlace) {
        val target = _searchTarget.value
        when (target?.kind) {
            RouteFieldKind.ORIGIN -> setOrigin(place)
            RouteFieldKind.DESTINATION -> setDestination(place)
            RouteFieldKind.WAYPOINT -> addWaypoint(place)
            null -> Unit
        }
        if (place.point.isValidLatLon()) {
            settings.addRecentPlace(place.name, place.point)
            runCatching { rememberRecent.invoke(place.point) }
        }
        closeSearch()
    }

    /** Starts a search for the 最近使用 list's field label, used by the sheet header row. */
    fun searchFieldLabel(target: RouteFieldTarget): String = context.getString(
        when (target.kind) {
            RouteFieldKind.ORIGIN -> R.string.rp_origin
            RouteFieldKind.DESTINATION -> R.string.rp_destination
            RouteFieldKind.WAYPOINT -> R.string.rp_waypoint
        },
        target.index + 1,
    )
}

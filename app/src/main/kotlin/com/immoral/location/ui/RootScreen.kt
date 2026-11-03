package com.immoral.location.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.unit.dp
import com.immoral.location.ui.theme.sheetContainer
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.EditLocationAlt
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.immoral.location.App
import com.immoral.location.BuildConfig
import com.immoral.location.R
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.data.PreviewCounts
import com.immoral.location.data.SettingsStore
import com.immoral.location.map.TileSources
import com.immoral.location.net.CoordinateParser
import com.immoral.location.net.PlaceResult
import com.immoral.location.net.SearchOutcome
import com.immoral.location.service.SimulationService
import com.immoral.location.ui.components.MessageCenter
import com.immoral.location.ui.route.RoutePlace
import com.immoral.location.ui.route.RoutePlannerHost
import com.immoral.location.ui.screens.AppPickerDialog
import com.immoral.location.ui.screens.CustomActions
import com.immoral.location.ui.screens.CustomLocationScreen
import com.immoral.location.ui.screens.CustomTarget
import com.immoral.location.ui.screens.FavoritesScreen
import com.immoral.location.ui.screens.MapFocusRequest
import com.immoral.location.ui.screens.MapScreen
import com.immoral.location.ui.screens.QuickActions
import com.immoral.location.ui.screens.QuickScreen
import com.immoral.location.ui.screens.SettingsActions
import com.immoral.location.ui.screens.SettingsScreen
import com.immoral.location.ui.screens.SheetDetent
import com.immoral.location.ui.screens.launchLsposed
import com.immoral.location.ui.sheets.HeadingSheetContent
import com.immoral.location.ui.sheets.LayerSheetContent
import com.immoral.location.ui.sheets.OverlayPromptContent
import com.immoral.location.ui.sheets.SearchUiState
import com.immoral.location.ui.sheets.StatusSheetContent
import com.immoral.location.ui.sheets.ToolsPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SEARCH_TIMEOUT_MS = 8_000L

/** Secondary sheets that deserve a real modal. */
private enum class ModalSheet { NONE, STATUS, LAYERS, HEADING, FAVORITES, APPS, OVERLAY_PROMPT }

/** The four top-level destinations (issue #1). */
enum class AppTab(
    val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val tag: String,
) {
    MAP(R.string.shell_tab_map, Icons.Outlined.Map, Icons.Filled.Map, "e2e.tab.map"),
    QUICK(R.string.shell_tab_quick, Icons.Outlined.Bolt, Icons.Filled.Bolt, "e2e.tab.quick"),
    CUSTOM(R.string.shell_tab_custom, Icons.Outlined.EditLocationAlt, Icons.Filled.EditLocationAlt, "e2e.tab.custom"),
    SETTINGS(R.string.shell_tab_settings, Icons.Outlined.Settings, Icons.Filled.Settings, "e2e.tab.settings"),
}

/**
 * App shell: a Material 3 navigation bar over four destinations.
 *
 *  * **快捷** — saved places, saved routes and recents as lists; one tap starts. No map involved, so
 *    the everyday "pick → start → switch to the target app" flow never waits for tiles (issue #1).
 *  * **地图** — the full-bleed map with the joystick, the place card and the route planner, exactly
 *    as before. It is composed the first time it is opened and then kept alive (paused while hidden)
 *    so switching back is instant.
 *  * **自定义** — coordinate input (with coordinate-system conversion), place search, pick on map.
 *  * **设置** — every switch in one page.
 *
 * Every user intent still goes through [AppController]; this file only wires state to screens.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun RootScreen(app: App, initialSearch: String? = null) {
    val scope = rememberCoroutineScope()
    val controller = remember(app) { AppController(app, scope) }
    val snackbarHost = remember { SnackbarHostState() }
    val messages = remember { MessageCenter() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(controller) {
        controller.showMessage = { message ->
            scope.launch { messages.show(snackbarHost, message) }
        }
        controller.showAction = { message, action, onAction ->
            scope.launch { messages.showAction(snackbarHost, message, action, onAction) }
        }
    }
    // A user command with no reply within ~1 s is surfaced instead of silently doing nothing.
    LaunchedEffect(app) {
        app.bridge.commandFailures.collect { label ->
            messages.show(snackbarHost, context.getString(R.string.command_not_delivered, label))
        }
    }

    val config by app.settings.config.collectAsStateWithLifecycle()
    val routePoints by app.settings.routePoints.collectAsStateWithLifecycle()
    val routePath by app.settings.routePath.collectAsStateWithLifecycle()
    val routeOrigin by app.settings.routeOrigin.collectAsStateWithLifecycle()
    val routeDestination by app.settings.routeDestination.collectAsStateWithLifecycle()
    val routeWaypoints by app.settings.routeWaypoints.collectAsStateWithLifecycle()
    val savedRoutes by app.settings.savedRoutes.collectAsStateWithLifecycle()
    val recentPlaces by app.settings.recentPlaces.collectAsStateWithLifecycle()
    val startPage by app.settings.startPage.collectAsStateWithLifecycle()
    val favorites by app.favorites.favorites.collectAsStateWithLifecycle()
    val engineState by app.bridge.engineState.collectAsStateWithLifecycle()
    val link by app.bridge.link.collectAsStateWithLifecycle()
    val moduleVersion by app.bridge.moduleVersion.collectAsStateWithLifecycle()
    val customTileUrl by app.settings.tileUrl.collectAsStateWithLifecycle()
    val tilePreset by app.settings.tilePreset.collectAsStateWithLifecycle()
    val tiandituKey by app.settings.tiandituKey.collectAsStateWithLifecycle()
    val lastPosition by app.settings.lastPosition.collectAsStateWithLifecycle()
    val recentPositions by app.settings.recentPositions.collectAsStateWithLifecycle()
    val overlayRunning by SimulationService.overlayRunning.collectAsStateWithLifecycle()
    val headingDeg by SimulationService.headingDegFlow.collectAsStateWithLifecycle()
    val pendingRouteId by controller.routePlanner.pendingStartId.collectAsStateWithLifecycle()

    // 悬浮窗权限 is an edge state the settings must be able to explain; re-read on every resume.
    var overlayPermission by remember { mutableStateOf(canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val overlayRunningNow by rememberUpdatedState(overlayRunning)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> overlayPermission = canDrawOverlays(context)

                Lifecycle.Event.ON_START -> {
                    // The floating pad is for *other* apps; while ours is in front it would float
                    // over our own controls.
                    if (overlayRunningNow) {
                        SimulationService.command(app, SimulationService.ACTION_SUSPEND_OVERLAY)
                    }
                }

                Lifecycle.Event.ON_STOP -> {
                    SimulationService.command(app, SimulationService.ACTION_RESUME_OVERLAY)
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val position = remember(engineState, lastPosition) {
        engineState?.point?.takeIf { it.isValid() } ?: lastPosition
    }
    val counts: PreviewCounts = remember(position.lat, position.lon, config) {
        app.preview.counts(position, config)
    }
    val tiles = remember(tilePreset, customTileUrl, tiandituKey) {
        TileSources.resolve(tilePreset, customTileUrl, tiandituKey)
    }
    val darkMap = isSystemInDarkTheme()

    // ------------------------------------------------------------------ navigation state
    // 首页 for the back button is the map (or 快捷 when the user pinned it); what the app *opens* on
    // is, by default, simply the tab the user was on last time.
    val homeTab = if (startPage == SettingsStore.START_PAGE_QUICK) AppTab.QUICK else AppTab.MAP
    var tab by rememberSaveable {
        val opening = when {
            !initialSearch.isNullOrBlank() -> AppTab.MAP
            startPage == SettingsStore.START_PAGE_LAST ->
                AppTab.entries.firstOrNull { it.name == app.settings.lastTab } ?: AppTab.MAP
            else -> homeTab
        }
        mutableStateOf(opening)
    }
    LaunchedEffect(tab) { app.settings.lastTab = tab.name }
    var mapEverShown by rememberSaveable { mutableStateOf(tab == AppTab.MAP) }
    var pickMode by rememberSaveable { mutableStateOf(false) }
    var customTarget by remember { mutableStateOf<CustomTarget?>(null) }
    var focusRequest by remember { mutableStateOf<MapFocusRequest?>(null) }

    var detent by rememberSaveable { mutableStateOf(SheetDetent.PEEK) }
    var modal by remember { mutableStateOf(ModalSheet.NONE) }
    var query by remember { mutableStateOf("") }
    var searchState by remember { mutableStateOf(SearchUiState()) }
    var searchArmed by remember { mutableStateOf(false) }
    var showActivationHelp by remember { mutableStateOf(false) }
    var overlayPrompted by remember { mutableStateOf(false) }

    val tabStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val selectTab: (AppTab) -> Unit = { next ->
        if (next != AppTab.MAP) pickMode = false
        if (next == AppTab.MAP) mapEverShown = true
        tab = next
    }
    val openMap: (GeoPoint?) -> Unit = { point ->
        mapEverShown = true
        tab = AppTab.MAP
        focusRequest = point?.let { MapFocusRequest(it) }
    }

    // Back from a secondary tab returns to the home tab before it may leave the app.
    // Back with the keyboard up closes the keyboard only. Android does not always swallow that
    // press for us, and a press that reaches the activity finishes it — which is how typing a query
    // and then pressing back used to close the app on top of the map.
    val imeUpForBack = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    BackHandler(enabled = imeUpForBack) {
        focusManager.clearFocus()
        keyboardController?.hide()
    }
    BackHandler(enabled = !imeUpForBack && tab != homeTab && !pickMode) { selectTab(homeTab) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Offer the floating joystick once, and only when *this session* starts the simulation.
    var wasRunning by remember { mutableStateOf(engineState?.running == true) }
    LaunchedEffect(engineState?.running) {
        val running = engineState?.running == true
        if (running && !wasRunning && !overlayRunning && !overlayPrompted) {
            overlayPrompted = true
            modal = ModalSheet.OVERLAY_PROMPT
        }
        wasRunning = running
    }

    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(overlayRunning, lifecycleState) {
        if (overlayRunning && lifecycleState.isAtLeast(Lifecycle.State.STARTED)) {
            SimulationService.command(app, SimulationService.ACTION_SUSPEND_OVERLAY)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(controller::importGpx) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri -> uri?.let(controller::exportGpx) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }

    val copyToClipboard: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        scope.launch { messages.show(snackbarHost, context.getString(R.string.action_copied)) }
    }

    /** The simulation keeps a foreground notification; ask for it the first time anything starts. */
    val ensureNotificationPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                runCatching {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    val startRun: () -> Unit = {
        ensureNotificationPermission()
        controller.toggleRun()
    }
    val goTo: (GeoPoint, String) -> Unit = { point, label ->
        ensureNotificationPermission()
        controller.goTo(point, label)
    }
    val navigateTo: (GeoPoint, String) -> Unit = { point, label ->
        controller.planTo(point, label)
        detent = SheetDetent.FULL
        openMap(null)
    }
    val openPlanner: () -> Unit = {
        detent = SheetDetent.FULL
        openMap(null)
    }
    val showModuleHelp: () -> Unit = { showActivationHelp = true }

    // ------------------------------------------------------------------ map search
    val submitSearchText: (String) -> Unit = { raw ->
        val text = raw.trim()
        if (text.isEmpty()) {
            scope.launch { messages.show(snackbarHost, context.getString(R.string.search_hint)) }
        } else {
            val direct = CoordinateParser.parse(text)
            if (direct != null) {
                controller.setPosition(direct)
                searchState = SearchUiState()
            } else {
                searchArmed = false
                searchState = SearchUiState(query = text, loading = true)
                controller.searchPlaces(text) { outcome ->
                    searchState = when (outcome) {
                        is SearchOutcome.Success -> SearchUiState(
                            query = text,
                            results = outcome.results,
                            provider = outcome.provider,
                        )

                        is SearchOutcome.Failure -> SearchUiState(query = text, error = outcome.message)
                    }
                }
            }
        }
    }

    // Live search: 450 ms after the user stops typing the suggestion card under the pill updates.
    LaunchedEffect(query, searchArmed) {
        if (!searchArmed) return@LaunchedEffect
        val text = query.trim()
        if (text.length < 2 || CoordinateParser.parse(text) != null) {
            searchState = SearchUiState()
            return@LaunchedEffect
        }
        delay(450)
        searchState = SearchUiState(query = text, loading = true)
        controller.searchPlaces(text) { outcome ->
            searchState = when (outcome) {
                is SearchOutcome.Success -> SearchUiState(
                    query = text,
                    results = outcome.results,
                    provider = outcome.provider,
                )

                is SearchOutcome.Failure -> SearchUiState(query = text, error = outcome.message)
            }
        }
        // A request that never answers must still end in a visible outcome.
        delay(SEARCH_TIMEOUT_MS)
        if (searchState.loading && searchState.query == text) {
            searchState = SearchUiState(
                query = text,
                error = context.getString(R.string.search_timeout),
            )
        }
    }

    // e2e / 调试入口：`am start -n <pkg>/.ui.MainActivity --es search 北京大学` 直接跑一次搜索。
    LaunchedEffect(initialSearch) {
        if (!initialSearch.isNullOrBlank()) {
            query = initialSearch
            submitSearchText(initialSearch)
        }
    }

    /** ♡ in the peek row: save the current spot, or forget it when one is already saved here. */
    val toggleFavouriteHere: () -> Unit = {
        val near = favorites.firstOrNull { GeoMath.distanceMeters(position, it.point) < 25.0 }
        if (near != null) {
            controller.deleteFavoriteWithUndo(near)
        } else {
            controller.addCurrentFavorite()
        }
    }

    val toggleOverlay: (Boolean) -> Unit = { enabled -> controller.setOverlayEnabled(enabled) }

    // The bar steps aside while the keyboard is up: a 80 dp bar pinned above the IME is dead space
    // exactly when the screen is shortest.
    val imeVisible = WindowInsets.isImeVisible
    val bottomBarShown = !imeVisible

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Turns every `Modifier.testTag` below into a `resource-id` for `uiautomator dump`.
            .semantics { testTagsAsResourceId = true },
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(
                    if (bottomBarShown) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier,
                ),
        ) {
            if (mapEverShown) {
                MapScreen(
                    state = engineState,
                    link = link,
                    config = config,
                    position = position,
                    favorites = favorites,
                    routeGeometry = routePath.ifEmpty { routePoints },
                    routeWaypoints = routeWaypoints,
                    routeOrigin = routeOrigin ?: routePoints.firstOrNull(),
                    routeDestination = routeDestination ?: routePoints.lastOrNull(),
                    tiles = tiles,
                    darkMap = darkMap,
                    headingDeg = headingDeg,
                    snackbarHostState = snackbarHost,
                    searchState = searchState,
                    onPickSuggestion = { result: PlaceResult ->
                        searchArmed = false
                        controller.setPosition(result.point)
                        controller.rememberRecent(result.point)
                        app.settings.addRecentPlace(result.name, result.point)
                        query = result.name
                        searchState = SearchUiState()
                        detent = SheetDetent.PEEK
                    },
                    query = query,
                    onQueryChange = { text ->
                        query = text
                        searchArmed = true
                    },
                    onSearchSubmit = { submitSearchText(query) },
                    onOpenTools = { detent = SheetDetent.HALF },
                    onOpenLayers = { modal = ModalSheet.LAYERS },
                    onToggleRun = startRun,
                    onSpeedSelect = controller::setSpeed,
                    onJoystick = controller::joystick,
                    onReleaseJoystick = controller::releaseJoystick,
                    onSetPosition = controller::setPosition,
                    onAddFavorite = controller::addFavorite,
                    onAddRoutePoint = controller::addRoutePoint,
                    // The map's own card says 导航到这里; the point has no name, so the planner
                    // labels it by coordinate.
                    onNavigateTo = { point -> navigateTo(point, "") },
                    onSelectFavorite = controller::jumpToFavorite,
                    onOpenRoutePlanner = { detent = SheetDetent.FULL },
                    onToggleFavoriteHere = toggleFavouriteHere,
                    onOpenStatus = { modal = ModalSheet.STATUS },
                    favouriteCount = favorites.size,
                    onOpenFavorites = { modal = ModalSheet.FAVORITES },
                    detent = detent,
                    onDetentChange = { detent = it },
                    visible = tab == AppTab.MAP,
                    bottomBarShown = bottomBarShown,
                    pickMode = pickMode,
                    onPickPoint = { point ->
                        customTarget = CustomTarget(
                            name = context.getString(R.string.shell_picked_on_map),
                            point = point,
                        )
                        pickMode = false
                        tab = AppTab.CUSTOM
                    },
                    onCancelPick = {
                        pickMode = false
                        tab = AppTab.CUSTOM
                    },
                    focusRequest = focusRequest,
                    // Transient feedback belongs to the surface that is actually on screen: the tab
                    // pages carry their own host, and a modal sheet gets one of its own below. A
                    // message raised from inside a sheet used to render behind the sheet's scrim,
                    // where it could be neither read nor tapped (撤销 was unreachable).
                    snackbarEnabled = tab == AppTab.MAP && modal == ModalSheet.NONE,
                    toolsContent = {
                        ToolsPanel(
                            config = config,
                            position = position,
                            favorites = favorites,
                            onUpdate = controller::updateConfig,
                            onPickFavorite = controller::jumpToFavorite,
                            onAddCurrentFavorite = controller::addCurrentFavorite,
                            onOpenSettings = { selectTab(AppTab.SETTINGS) },
                            onOpenLayers = { modal = ModalSheet.LAYERS },
                            onOpenHeading = { modal = ModalSheet.HEADING },
                            onToggleOverlay = toggleOverlay,
                            overlayRunning = overlayRunning,
                            overlayPermissionGranted = overlayPermission,
                            onManageFavorites = { modal = ModalSheet.FAVORITES },
                            onPickApps = { modal = ModalSheet.APPS },
                            recentPositions = recentPositions,
                            onPickRecent = controller::setPosition,
                        )
                    },
                    routeContent = { onHeightChange ->
                        RoutePlannerHost(
                            controller = controller.routePlanner,
                            onDismiss = { detent = SheetDetent.PEEK },
                            onContentHeightChange = onHeightChange,
                            backEnabled = tab == AppTab.MAP,
                        )
                    },
                )
            }

            if (tab != AppTab.MAP) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    // Each tab keeps its own saveable state (typed query, scroll position) while
                    // another tab is in front, so switching away and back never wipes an input.
                    tabStateHolder.SaveableStateProvider(tab.name) {
                    when (tab) {
                        AppTab.QUICK -> QuickScreen(
                            state = engineState,
                            link = link,
                            config = config,
                            position = position,
                            favorites = favorites,
                            savedRoutes = savedRoutes,
                            pendingRouteId = pendingRouteId,
                            recentPositions = recentPositions,
                            recentPlaces = recentPlaces,
                            snackbarHostState = snackbarHost,
                            actions = QuickActions(
                                onToggleRun = startRun,
                                onGoTo = goTo,
                                onShowOnMap = { point -> openMap(point) },
                                onNavigateTo = { point, label -> navigateTo(point, label) },
                                onSaveFavorite = controller::addFavorite,
                                onRenameFavorite = controller::renameFavorite,
                                onDeleteFavorite = controller::deleteFavoriteWithUndo,
                                onStartRoute = { route ->
                                    ensureNotificationPermission()
                                    controller.startSavedRoute(route)
                                },
                                onEditRoute = { route ->
                                    controller.routePlanner.loadSaved(route)
                                    openPlanner()
                                },
                                onRenameRoute = controller::renameSavedRoute,
                                onDeleteRoute = controller::deleteSavedRouteWithUndo,
                                onPauseRoute = controller::pauseRoute,
                                onResumeRoute = controller::playRoute,
                                onStopRoute = controller::stopRoute,
                                onRemoveRecent = { point -> app.settings.removeRecentPosition(point) },
                                onClearRecents = controller::clearRecents,
                                onOpenCustom = { selectTab(AppTab.CUSTOM) },
                                onOpenMap = { openMap(null) },
                                onPlanRoute = openPlanner,
                                onOpenStatus = { modal = ModalSheet.STATUS },
                                onModuleHelp = showModuleHelp,
                                onCopy = copyToClipboard,
                            ),
                        )

                        AppTab.CUSTOM -> CustomLocationScreen(
                            position = position,
                            target = customTarget,
                            onTargetChange = { customTarget = it },
                            snackbarHostState = snackbarHost,
                            actions = CustomActions(
                                onSearch = controller::searchPlaces,
                                onPickOnMap = {
                                    pickMode = true
                                    detent = SheetDetent.PEEK
                                    openMap(customTarget?.point)
                                },
                                onStartHere = goTo,
                                onSaveFavorite = controller::addFavorite,
                                onNavigateTo = { point, label -> navigateTo(point, label) },
                                onShowOnMap = { point -> openMap(point) },
                            ),
                        )

                        AppTab.SETTINGS -> SettingsScreen(
                            config = config,
                            link = link,
                            moduleVersion = moduleVersion,
                            tileUrl = customTileUrl,
                            tilePreset = tilePreset,
                            versionName = BuildConfig.VERSION_NAME,
                            overlayRunning = overlayRunning,
                            overlayPermission = overlayPermission,
                            startPage = startPage,
                            snackbarHostState = snackbarHost,
                            actions = SettingsActions(
                                onUpdate = controller::updateConfig,
                                onTileUrlChange = controller::setTileUrl,
                                onReset = controller::resetSettings,
                                onOpenLayers = { modal = ModalSheet.LAYERS },
                                onOpenHeading = { modal = ModalSheet.HEADING },
                                onToggleOverlay = toggleOverlay,
                                onImportGpx = {
                                    importLauncher.launch(arrayOf("application/gpx+xml", "text/xml", "*/*"))
                                },
                                onExportGpx = { exportLauncher.launch("immoral-route.gpx") },
                                onOpenStatus = { modal = ModalSheet.STATUS },
                                onModuleHelp = showModuleHelp,
                                onStartPageChange = controller::setStartPage,
                            ),
                        )

                        AppTab.MAP -> Unit
                    }
                    }
                }
            }
        }

        if (bottomBarShown) {
            // On the map the bar takes the sheet's own tone, so sheet + bar read as one bottom panel
            // instead of two stacked slabs.
            val barColor by animateColorAsState(
                targetValue = if (tab == AppTab.MAP) sheetContainer() else MaterialTheme.colorScheme.surfaceContainer,
                label = "navBarColor",
            )
            NavigationBar(
                modifier = Modifier.testTag("e2e.navbar"),
                containerColor = barColor,
                tonalElevation = 0.dp,
            ) {
                AppTab.entries.forEach { item ->
                    val selected = tab == item
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (selected && item == AppTab.MAP) {
                                // Re-tapping 地图 collapses whatever the sheet was showing.
                                detent = SheetDetent.PEEK
                            }
                            selectTab(item)
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.icon,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(item.labelRes)) },
                        modifier = Modifier.testTag(item.tag),
                    )
                }
            }
        }
    }

    if (modal != ModalSheet.NONE && modal != ModalSheet.APPS) {
        ModalBottomSheet(
            onDismissRequest = { modal = ModalSheet.NONE },
            sheetState = sheetState,
        ) {
            Box {
            when (modal) {
                ModalSheet.STATUS -> StatusSheetContent(
                    state = engineState,
                    link = link,
                    config = config,
                    position = position,
                    counts = counts,
                    onCopy = copyToClipboard,
                    onHelpClick = {
                        modal = ModalSheet.NONE
                        showActivationHelp = true
                    },
                )

                ModalSheet.LAYERS -> LayerSheetContent(
                    preset = tilePreset,
                    tiandituKey = tiandituKey,
                    hasKey = tiandituKey.isNotBlank(),
                    onPresetChange = controller::setTilePreset,
                    onKeyChange = controller::setTiandituKey,
                )

                ModalSheet.HEADING -> HeadingSheetContent(
                    followHeading = config.followHeading,
                    headingDeg = headingDeg,
                    fixedBearingDeg = config.fixedBearingDeg,
                    onFollowChange = controller::setFollowHeading,
                    onBearingChange = controller::setFixedBearing,
                )

                ModalSheet.FAVORITES -> FavoritesScreen(
                    favorites = favorites,
                    currentPosition = position,
                    onSelect = { place ->
                        controller.jumpToFavorite(place)
                        modal = ModalSheet.NONE
                    },
                    onRename = controller::renameFavorite,
                    onDelete = controller::deleteFavoriteWithUndo,
                    onAddCurrent = controller::addCurrentFavorite,
                )

                ModalSheet.OVERLAY_PROMPT -> OverlayPromptContent(
                    onEnable = {
                        toggleOverlay(true)
                        modal = ModalSheet.NONE
                    },
                    onDismiss = { modal = ModalSheet.NONE },
                )

                ModalSheet.APPS -> Unit
                ModalSheet.NONE -> Unit
            }
            SnackbarHost(
                hostState = snackbarHost,
                // Top of the sheet, not the bottom: the bottom of a sheet is its primary action.
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            }
        }
    }

    if (modal == ModalSheet.APPS) {
        AppPickerDialog(
            selected = config.independentPackages,
            onDismiss = { modal = ModalSheet.NONE },
            onConfirm = { selection ->
                controller.updateConfig { it.copy(independentPackages = selection) }
                modal = ModalSheet.NONE
            },
        )
    }

    if (showActivationHelp) {
        AlertDialog(
            onDismissRequest = { showActivationHelp = false },
            title = { Text(stringResource(R.string.hint_activate_title)) },
            text = { Text(stringResource(R.string.hint_activate_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showActivationHelp = false
                        if (!launchLsposed(context)) {
                            controller.showMessage(context.getString(R.string.shell_lsposed_missing))
                        }
                    },
                ) {
                    Text(stringResource(R.string.shell_open_lsposed))
                }
            },
            dismissButton = {
                TextButton(onClick = { showActivationHelp = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }
}

/** `SYSTEM_ALERT_WINDOW` state; devices before M never needed the runtime switch. */
private fun canDrawOverlays(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        android.provider.Settings.canDrawOverlays(context)

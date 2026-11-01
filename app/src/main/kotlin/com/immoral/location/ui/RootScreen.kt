package com.immoral.location.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.immoral.location.App
import com.immoral.location.BuildConfig
import com.immoral.location.R
import com.immoral.location.bridge.ModuleLink
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.EngineState
import com.immoral.location.core.model.FavoritePlace
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.data.PreviewCounts
import com.immoral.location.map.TileSources
import com.immoral.location.net.CoordinateParser
import com.immoral.location.net.PlaceResult
import com.immoral.location.net.SearchOutcome
import com.immoral.location.service.SimulationService
import com.immoral.location.ui.components.MessageCenter
import com.immoral.location.ui.screens.AppPickerDialog
import com.immoral.location.ui.screens.FavoritesScreen
import com.immoral.location.ui.screens.MapScreen
import com.immoral.location.ui.route.RoutePlace
import com.immoral.location.ui.route.RoutePlannerHost
import com.immoral.location.ui.screens.SettingsScreen
import com.immoral.location.ui.screens.SheetDetent
import com.immoral.location.ui.sheets.HeadingSheetContent
import com.immoral.location.ui.sheets.LayerSheetContent
import com.immoral.location.ui.sheets.OverlayPromptContent
import com.immoral.location.ui.sheets.SearchUiState
import com.immoral.location.ui.sheets.StatusSheetContent
import com.immoral.location.ui.sheets.ToolsPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Secondary sheets that still deserve a real modal (they are not part of the map IA). */
private enum class ModalSheet { NONE, STATUS, LAYERS, HEADING, SETTINGS, FAVORITES, APPS, OVERLAY_PROMPT }

/**
 * App shell.
 *
 * One activity, **one** top-level surface (the map) and a single three-detent bottom sheet. The old
 * five-tab navigation bar is gone: 收藏 / 路线 / 设置 live inside the sheet, because a user who
 * drags the joystick must be able to see the marker move at the same time.
 *
 * Wiring rules that keep this screen dumb:
 *  * every user intent is an `AppController` method;
 *  * the only transient feedback channel is the top-anchored snackbar owned by [MapScreen];
 *  * nothing outside the clipboard/notification permissions touches Android APIs directly.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
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

    // 悬浮窗权限 is an *edge state* the tools panel must be able to explain: without it the
    // "悬浮摇杆" row silently does nothing visible until the system settings screen opens. Read on
    // every RESUME (the user comes back from that screen) instead of on every recomposition.
    var overlayPermission by remember { mutableStateOf(canDrawOverlays(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val overlayRunningNow by rememberUpdatedState(overlayRunning)
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    overlayPermission = canDrawOverlays(context)
                }

                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    // The floating pad is for *other* apps. While ours is in front the in-app FAB is
                    // the control; the bubble would otherwise float over our own sheet (it covered
                    // the switch that turns it off). Suspending keeps the user's preference intact.
                    if (overlayRunningNow) {
                        SimulationService.command(app, SimulationService.ACTION_SUSPEND_OVERLAY)
                    }
                }

                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    // Leaving the app: bring the floating pad back for wherever the user goes next.
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

    var detent by rememberSaveable { mutableStateOf(SheetDetent.PEEK) }
    var modal by remember { mutableStateOf(ModalSheet.NONE) }
    var query by remember { mutableStateOf("") }
    var searchState by remember { mutableStateOf(SearchUiState()) }
    var searchArmed by remember { mutableStateOf(false) }
    var showActivationHelp by remember { mutableStateOf(false) }
    var overlayPrompted by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Offer the floating joystick once, and only when *this session* starts the simulation
    // (never on launch just because the engine was already running).
    var wasRunning by remember { mutableStateOf(engineState?.running == true) }
    LaunchedEffect(engineState?.running) {
        val running = engineState?.running == true
        if (running && !wasRunning && !overlayRunning && !overlayPrompted) {
            overlayPrompted = true
            modal = ModalSheet.OVERLAY_PROMPT
        }
        wasRunning = running
    }

    // The ON_START hook alone missed the common case "user enables the pad from 工具 while already in
    // the app", so the window appeared on the sheet. This effect covers both: entering the app with
    // the pad on, and turning it on while in front.
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(overlayRunning, lifecycleState) {
        if (overlayRunning && lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
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

    val startRun: () -> Unit = {
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
        controller.toggleRun()
    }

    // 把「输入框里的文本」抽成显式参数，方便下面的 e2e 入口复用同一套逻辑。
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
                // One search surface only: the inline suggestion card under the pill. Opening a
                // modal list as well would show the same results twice.
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

    // Live search: 450 ms after the user stops typing, the inline suggestion card under the pill
    // shows results. Picking one disarms this so writing the chosen name into the field does not
    // immediately re-open the list.
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
            controller.deleteFavorite(near)
            controller.showMessage(context.getString(R.string.favorites_deleted))
        } else {
            controller.addCurrentFavorite()
        }
    }

    val toggleOverlay: (Boolean) -> Unit = { enabled -> controller.setOverlayEnabled(enabled) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Turns every `Modifier.testTag` below into a `resource-id` for `uiautomator dump`,
            // which is what the e2e matrix asserts on instead of Chinese label matching.
            .semantics { testTagsAsResourceId = true },
    ) {
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
            onNavigateTo = { point ->
                // 导航到这里 = route planner with the pin as destination and "here" as origin.
                controller.routePlanner.resetOrigin()
                controller.routePlanner.setDestination(RoutePlace(name = "", point = point))
                detent = SheetDetent.FULL
            },
            onSelectFavorite = controller::jumpToFavorite,
            onOpenRoutePlanner = { detent = SheetDetent.FULL },
            onToggleFavoriteHere = toggleFavouriteHere,
            onOpenStatus = { modal = ModalSheet.STATUS },
            favouriteCount = favorites.size,
            // The saved-places list is reachable from the peek card. Before this entry point the
            // only control on the default surface that mentioned 收藏 was the heart — which saves
            // (or, when the point already has a saved place, deletes) instead of opening the list.
            onOpenFavorites = { modal = ModalSheet.FAVORITES },
            detent = detent,
            onDetentChange = { detent = it },
            toolsContent = {
                ToolsPanel(
                    config = config,
                    position = position,
                    favorites = favorites,
                    onUpdate = controller::updateConfig,
                    onPickFavorite = controller::jumpToFavorite,
                    onAddCurrentFavorite = controller::addCurrentFavorite,
                    onOpenSettings = { modal = ModalSheet.SETTINGS },
                    onOpenLayers = { modal = ModalSheet.LAYERS },
                    onOpenHeading = { modal = ModalSheet.HEADING },
                    onToggleOverlay = toggleOverlay,
                    overlayRunning = overlayRunning,
                    overlayPermissionGranted = overlayPermission,
                    onManageFavorites = { modal = ModalSheet.FAVORITES },
                    onPickApps = { modal = ModalSheet.APPS },
                    onImportGpx = {
                        importLauncher.launch(arrayOf("application/gpx+xml", "text/xml", "*/*"))
                    },
                    onExportGpx = { exportLauncher.launch("immoral-route.gpx") },
                    recentPositions = recentPositions,
                    onPickRecent = controller::setPosition,
                )
            },
            routeContent = { onHeightChange ->
                RoutePlannerHost(
                    controller = controller.routePlanner,
                    onDismiss = { detent = SheetDetent.PEEK },
                    // The planner reports its content height so the FULL detent can hug it instead of
                    // expanding to the screen and leaving a dead lower half (audit P1).
                    onContentHeightChange = onHeightChange,
                )
            },
        )
    }

    if (modal != ModalSheet.NONE) {
        ModalBottomSheet(
            onDismissRequest = { modal = ModalSheet.NONE },
            sheetState = sheetState,
        ) {
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


                ModalSheet.SETTINGS -> SettingsScreen(
                    config = config,
                    link = link,
                    moduleVersion = moduleVersion,
                    tileUrl = customTileUrl,
                    versionName = BuildConfig.VERSION_NAME,
                    onUpdate = controller::updateConfig,
                    onTileUrlChange = controller::setTileUrl,
                    onReset = controller::resetSettings,
                )

                ModalSheet.FAVORITES -> FavoritesScreen(
                    favorites = favorites,
                    currentPosition = position,
                    onSelect = controller::jumpToFavorite,
                    onRename = controller::renameFavorite,
                    onDelete = controller::deleteFavorite,
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
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showActivationHelp = false },
            title = { androidx.compose.material3.Text(stringResource(R.string.hint_activate_title)) },
            text = { androidx.compose.material3.Text(stringResource(R.string.hint_activate_body)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showActivationHelp = false },
                ) {
                    androidx.compose.material3.Text(stringResource(R.string.action_ok))
                }
            },
        )
    }
}

/** `SYSTEM_ALERT_WINDOW` state; devices before M never needed the runtime switch. */
private fun canDrawOverlays(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        android.provider.Settings.canDrawOverlays(context)
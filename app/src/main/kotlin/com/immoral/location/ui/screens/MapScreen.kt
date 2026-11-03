package com.immoral.location.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.immoral.location.R
import com.immoral.location.bridge.ModuleLink
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.EngineState
import com.immoral.location.core.model.FavoritePlace
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.model.SpeedLevel
import com.immoral.location.map.ResolvedTiles
import com.immoral.location.ui.components.ChromeActionPill
import com.immoral.location.ui.components.ChromeFab
import com.immoral.location.ui.components.ChromeFabGroup
import com.immoral.location.ui.components.ChromeFabGroupDivider
import com.immoral.location.ui.components.Fmt
import com.immoral.location.ui.components.JoystickPad
import com.immoral.location.ui.components.PlaceCard
import com.immoral.location.ui.components.SearchPill
import com.immoral.location.ui.components.TapChipCard
import com.immoral.location.ui.map.AmapHost
import com.immoral.location.ui.map.MapBackend
import com.immoral.location.ui.map.MapHost
import com.immoral.location.ui.map.MapPalette
import com.immoral.location.ui.sheets.PeekPanel
import com.immoral.location.ui.sheets.SheetHandle
import com.immoral.location.net.CoordinateParser
import com.immoral.location.net.PlaceResult
import com.immoral.location.ui.sheets.CollapsedPeekHeader
import com.immoral.location.ui.sheets.coordinateLine
import com.immoral.location.ui.sheets.SearchSuggestionCard
import com.immoral.location.ui.sheets.SearchUiState
import com.immoral.location.ui.sheets.speedLabelRes
import com.immoral.location.ui.theme.Dimens
import com.immoral.location.ui.theme.SheetShape
import com.immoral.location.ui.theme.sheetContainer
import com.immoral.location.ui.theme.tabular
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The three resting positions of the bottom sheet (spec §1).
 *
 * [PEEK] is the whole product status, [HALF] is the tool panel, [FULL] hosts the route planner.
 */
enum class SheetDetent {
    PEEK,
    HALF,
    FULL,
    ;

    fun up(): SheetDetent = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]

    fun down(): SheetDetent = entries[(ordinal - 1).coerceAtLeast(0)]
}

/**
 * The 4-second affordance shown after a single map tap.
 *
 * [previous] is the position the simulation was at *before* the tap, so 撤销 is a real undo rather
 * than a guess.
 */
private data class TapChip(val point: GeoPoint, val previous: GeoPoint)

/**
 * "Centre the map on [point]" coming from another tab (在地图中查看). [nonce] makes two requests for
 * the same point distinct, so the second tap still moves the camera.
 */
data class MapFocusRequest(val point: GeoPoint, val nonce: Long = System.nanoTime())

/**
 * The single top-level surface: a full-bleed map with everything else floating on top of it.
 *
 * Layout, all overlaying the map:
 *  * top: pill search bar (56dp) + the tools avatar;
 *  * top-centre, under the search bar: the **only** snackbar host in the app;
 *  * right edge: vertical FAB stack (图层 / 摇杆 / 定位 / 真实位置), fading out when the sheet is full;
 *  * bottom-start (above the sheet): the opt-in joystick;
 *  * bottom: the M3 bottom sheet with peek / half / full detents.
 *
 * Nothing here uses `android.widget.Toast`; transient feedback goes through [snackbarHostState],
 * which lives in the one place no other chrome wants: the idle strip under the search bar.
 */
@Composable
fun MapScreen(
    state: EngineState?,
    link: ModuleLink,
    config: SimulationConfig,
    position: GeoPoint,
    favorites: List<FavoritePlace>,
    routeGeometry: List<GeoPoint>,
    routeWaypoints: List<GeoPoint>,
    routeOrigin: GeoPoint?,
    routeDestination: GeoPoint?,
    tiles: ResolvedTiles?,
    darkMap: Boolean,
    headingDeg: Double?,
    snackbarHostState: SnackbarHostState,
    searchState: SearchUiState,
    onPickSuggestion: (PlaceResult) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenLayers: () -> Unit,
    onJumpReal: () -> Unit,
    onToggleRun: () -> Unit,
    onSpeedSelect: (SpeedLevel) -> Unit,
    onJoystick: (Float, Float) -> Unit,
    onReleaseJoystick: () -> Unit,
    onSetPosition: (GeoPoint) -> Unit,
    onAddFavorite: (String, GeoPoint) -> Unit,
    onAddRoutePoint: (GeoPoint) -> Unit,
    onNavigateTo: (GeoPoint) -> Unit,
    onSelectFavorite: (FavoritePlace) -> Unit,
    onOpenRoutePlanner: () -> Unit,
    onToggleFavoriteHere: () -> Unit,
    onOpenStatus: () -> Unit,
    detent: SheetDetent,
    onDetentChange: (SheetDetent) -> Unit,
    favouriteCount: Int = 0,
    onOpenFavorites: () -> Unit = {},
    toolsContent: @Composable ColumnScope.() -> Unit,
    routeContent: @Composable ColumnScope.(onHeightChange: (Int) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    bottomBarShown: Boolean = false,
    pickMode: Boolean = false,
    onPickPoint: (GeoPoint) -> Unit = {},
    onCancelPick: () -> Unit = {},
    focusRequest: MapFocusRequest? = null,
    snackbarEnabled: Boolean = true,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val setPositionHandler by rememberUpdatedState(onSetPosition)
    val favoriteTapHandler by rememberUpdatedState(onSelectFavorite)
    val altitudeRef by rememberUpdatedState(position.alt)
    val positionRef by rememberUpdatedState(position)
    val pickModeRef by rememberUpdatedState(pickMode)
    val pickHandler by rememberUpdatedState(onPickPoint)
    val visibleRef by rememberUpdatedState(visible)

    var joystickEnabled by rememberSaveable { mutableStateOf(false) }
    var pendingPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var tapChip by remember { mutableStateOf<TapChip?>(null) }
    // Timestamp holder: a camera settle fired by our own position update must not eat the chip.
    val tapChipAtMs = remember { LongArray(1) }
    var selectedFavoriteId by remember { mutableStateOf<String?>(null) }
    var peekHeightPx by remember { mutableIntStateOf(0) }
    // Height the route planner reports for its own content. The FULL detent hugs this number, which
    // is the fix for the "expanded sheet with a dead lower half" defect: a 3-field planner used to
    // open at full screen height and leave ~800 px of empty container under its actions.
    var routeContentPx by remember { mutableIntStateOf(0) }

    // The search field keeps the IME up, and a card drawn while it is up lands *under* the keyboard
    // (audit R1/R6: the place card's whole action row was hidden by it). A map gesture means the
    // user is done typing, so the keyboard goes first — cheaper and quieter than padding every
    // floating card, and it is what every map app does.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissIme: () -> Unit = remember { { focusManager.clearFocus(); keyboardController?.hide() } }
    // The map callbacks are captured once by the (remembered) backend, so they must only read
    // state through `rememberUpdatedState` refs, never through captured values.
    val handleTap: (GeoPoint) -> Unit = remember {
        tap@{ raw ->
            dismissIme()
            val point = raw.copy(alt = altitudeRef)
            if (pickModeRef) {
                // 自定义 → 在地图上选点: the tap is an answer, not a teleport.
                pickHandler(point)
                return@tap
            }
            val previous = positionRef
            selectedFavoriteId = null
            pendingPoint = null
            // Single tap keeps the original product gesture: switch the simulated position right
            // away. The chip below the FABs is the affordance (undo / save / route), not a modal.
            setPositionHandler(point)
            tapChip = TapChip(point, previous)
            tapChipAtMs[0] = android.os.SystemClock.uptimeMillis()
        }
    }
    val handleLongPress: (GeoPoint) -> Unit = remember {
        { raw ->
            dismissIme()
            if (pickModeRef) {
                pickHandler(raw.copy(alt = altitudeRef))
            } else {
                selectedFavoriteId = null
                tapChip = null
                pendingPoint = raw.copy(alt = altitudeRef)
            }
        }
    }
    val handleFavoriteTap: (FavoritePlace) -> Unit = remember {
        { place ->
            selectedFavoriteId = place.id
            favoriteTapHandler(place)
        }
    }
    // AMap reports a camera change after the marker moves, which used to dismiss the chip in the
    // same frame it appeared. Ignore camera events in the first 800 ms after our own tap.
    val handleMapGesture: () -> Unit = remember {
        {
            dismissIme()
            if (android.os.SystemClock.uptimeMillis() - tapChipAtMs[0] > 800) tapChip = null
        }
    }

    // The place card and the tap chip are peek-state affordances: expanding the sheet to the tools
    // or the route planner means the user moved on, and leaving them floating would either overlap
    // the panel or (at FULL) be pushed off-screen.
    LaunchedEffect(detent) {
        if (detent != SheetDetent.PEEK) {
            pendingPoint = null
            tapChip = null
            // Expanding the sheet means the user moved on from typing: the field gives up focus so
            // the keyboard and the suggestion card do not sit on top of the panel.
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    // A tapped favourite shows its label chip for a few seconds, then collapses back to a dot.
    LaunchedEffect(selectedFavoriteId) {
        if (selectedFavoriteId != null) {
            delay(4_000)
            selectedFavoriteId = null
        }
    }

    // The tap chip is a 4 s affordance: it must never become a second sheet.
    LaunchedEffect(tapChip) {
        if (tapChip != null) {
            delay(4_000)
            tapChip = null
        }
    }

    // The native map silently falls back to raster tiles when the SDK cannot initialise: the old
    // pink "SDK 未配置 Key" banner is gone on purpose (spec §0 row 7) — layer choice lives in 图层.
    var amapFailed by remember { mutableStateOf(false) }
    val amapLoaded = remember { mutableStateOf(false) }
    val useAmap = !amapFailed

    val scheme = MaterialTheme.colorScheme
    val palette = remember(scheme, darkMap) {
        MapPalette(
            primary = scheme.primary.toArgb(),
            onPrimary = scheme.onPrimary.toArgb(),
            primaryContainer = scheme.primaryContainer.toArgb(),
            onPrimaryContainer = scheme.onPrimaryContainer.toArgb(),
            tertiary = scheme.tertiary.toArgb(),
            onTertiary = scheme.onTertiary.toArgb(),
            surface = scheme.surface.toArgb(),
            onSurface = scheme.onSurface.toArgb(),
            onSurfaceVariant = scheme.onSurfaceVariant.toArgb(),
            surfaceContainerHigh = scheme.surfaceContainerHigh.toArgb(),
            errorContainer = scheme.errorContainer.toArgb(),
            onErrorContainer = scheme.onErrorContainer.toArgb(),
            dark = darkMap,
        )
    }

    val backend: MapBackend = remember(useAmap) {
        if (useAmap) {
            AmapHost(
                context = context,
                onTap = handleTap,
                onLongPress = handleLongPress,
                onFavoriteTap = handleFavoriteTap,
                onMapGesture = handleMapGesture,
                onMapLoaded = { amapLoaded.value = true },
            )
        } else {
            MapHost(
                context = context,
                onTap = handleTap,
                onLongPress = handleLongPress,
                onFavoriteTap = handleFavoriteTap,
                onMapGesture = handleMapGesture,
            )
        }
    }

    // A configured-but-invalid AMap key never fires onMapLoaded: fall back to the tile backend.
    LaunchedEffect(useAmap) {
        if (useAmap) {
            delay(3_000)
            if (!amapLoaded.value) {
                // Logged because the swap is otherwise invisible: the user keeps a working map and
                // support has no way to tell which backend produced the frame they are looking at.
                android.util.Log.i("MapScreen", "amap did not load within 3s - falling back to the tile backend")
                amapFailed = true
            }
        }
    }

    // AMap reports "loaded" even when it has no tiles (measured: tile cache cleared + radio off gave
    // "amap map loaded (tiles rendered)" over a blank grid), so the fallback above cannot detect the
    // ordinary offline case. Say so instead of leaving a blank map unexplained.
    var hasNetwork by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
        while (true) {
            hasNetwork = cm?.activeNetwork != null
            kotlinx.coroutines.delay(2_000)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, backend) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (visibleRef) backend.onResume()
                Lifecycle.Event.ON_PAUSE -> backend.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            backend.detach()
        }
    }

    LaunchedEffect(palette, backend) { backend.setPalette(palette) }

    // The map stays composed while another tab is in front (so coming back is instant), but it must
    // not keep rendering frames nobody can see.
    LaunchedEffect(visible, backend) {
        if (visible) backend.onResume() else backend.onPause()
    }

    // 在地图中查看 from another tab: centre on the point once per request.
    LaunchedEffect(focusRequest, backend) {
        val request = focusRequest ?: return@LaunchedEffect
        delay(120)
        backend.animateTo(request.point, 17f)
    }

    // Back unwinds the map's own layers before it may leave the tab: card → chip → sheet.
    BackHandler(enabled = visible && pickMode) { onCancelPick() }
    BackHandler(enabled = visible && !pickMode && (pendingPoint != null || tapChip != null || detent != SheetDetent.PEEK)) {
        when {
            pendingPoint != null -> pendingPoint = null
            tapChip != null -> tapChip = null
            else -> onDetentChange(SheetDetent.PEEK)
        }
    }

    val tileTemplate = tiles?.template
    LaunchedEffect(tileTemplate, darkMap, backend) {
        if (backend is MapHost) {
            tiles?.let { backend.setTileSource(it.template, it.subdomains, it.coordSystem) }
        }
        backend.setDarkFilter(darkMap)
        backend.jumpTo(position)
    }
    LaunchedEffect(backend, routeGeometry, routeWaypoints, routeOrigin, routeDestination) {
        backend.setRoute(routeGeometry, routeWaypoints, routeOrigin, routeDestination)
    }
    LaunchedEffect(favorites, selectedFavoriteId, backend) {
        backend.setFavorites(favorites, selectedFavoriteId)
    }
    LaunchedEffect(pendingPoint, backend) { backend.setSelection(pendingPoint) }

    // The cone only earns its place when the marker actually moves under a bearing.
    val bearing = remember(state) {
        val current = state ?: return@remember null
        if (current.running && current.speedMps > 0.05) current.bearingDeg else null
    }
    LaunchedEffect(position.lat, position.lon, position.alt, bearing, config.gpsJitterMeters, backend) {
        // The halo mirrors the accuracy the hook reports: jitter radius, never below 6 m so it is
        // still visible at street zoom.
        // The halo is a visual hint, not the data: the engine still reports gpsJitterMeters.
        // At street zoom a 6 m radius is ~4 px and reads as "no halo at all", so the drawn radius
        // gets a 20 m floor while the injected accuracy value is untouched.
        backend.setPosition(position, config.gpsJitterMeters.coerceAtLeast(20.0), bearing)
    }

    val running = state?.running == true
    val modeLabel = stringResource(speedLabelRes(config.speed)) + " " +
        Fmt.trimZero(config.effectiveSpeedMps()) + " m/s"
    // Accuracy is part of the peek summary now (audit S7). It is only shown when jitter is
    // configured, because "±0 m" would claim a precision the simulation does not have.
    val accuracyLabel = if (config.gpsJitterMeters > 0.0) {
        "±" + Fmt.trimZero(config.gpsJitterMeters) + " m"
    } else {
        null
    }
    val favouriteHere = remember(favorites, position) {
        favorites.any { GeoMath.distanceMeters(position, it.point) < 25.0 }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Height of the live-search card, so the snackbar stacks *under* it instead of behind it.
        var suggestionsHeightPx by remember { mutableIntStateOf(0) }
        val totalPx = constraints.maxHeight.toFloat()
        val totalWidthPx = constraints.maxWidth.toFloat()
        // Never constrain the very first layout to a guessed peek height: the measured value is
        // read back from PeekPanel *inside* the sheet, so a too-small guess would lock the sheet at
        // a height that clips the action row. 42 % of the screen is comfortably above the panel's
        // intrinsic height (~200 dp) and settles invisibly in one frame because the first measured
        // value snaps instead of animating.
        //
        // The peek block itself measures *without* the gesture-bar inset (the sheet's Column owns
        // that padding, at its very bottom), so the PEEK detent has to add it back here.
        // With the app's navigation bar under the map, the gesture-bar inset belongs to that bar.
        val navInsetPx = if (bottomBarShown) 0 else WindowInsets.navigationBars.getBottom(density)
        // The sheet is bottom-anchored with an explicit height, so an IME inset cannot be padded
        // inside it — that squeezed the picker's list to zero height. Instead the sheet keeps its
        // bottom edge above the keyboard: height capped by the space left over, lifted by the inset.
        // Zero when no keyboard is up, so every other state is byte-identical.
        val imeInsetPx = if (bottomBarShown) 0 else WindowInsets.ime.getBottom(density)
        val navInsetTopPx = WindowInsets.statusBars.getTop(density)
        val peekPx = (
            if (peekHeightPx > 0) {
                peekHeightPx.toFloat()
            } else {
                (totalPx * 0.42f).coerceAtLeast(with(density) { 280.dp.toPx() })
            }
            ) + navInsetPx
        val halfPx = (totalPx * 0.62f).coerceAtLeast(peekPx + with(density) { 160.dp.toPx() })
        // FULL stops below the search pill instead of at the status bar: the pill is the app's
        // primary action and covering it with the route planner reads as a dead end.
        val chromeTopPx = with(density) {
            (Dimens.searchBarHeight + Dimens.chromeGap * 4).toPx()
        } + navInsetTopPx
        val fullCapPx = (totalPx - chromeTopPx).coerceAtLeast(halfPx)
        // FULL hugs the planner's measured content (plus the collapsed status row above it and the
        // gesture bar below it) and falls back to the screen cap until the first measurement lands.
        val collapsedHeaderPx = with(density) { 52.dp.toPx() }
        val fullPx = if (routeContentPx > 0) {
            // Floor is PEEK, not HALF: clamping to HALF is what left ~500 px of dead sheet under a
            // three-field planner even after the height was measured.
            (routeContentPx + collapsedHeaderPx + navInsetPx).coerceIn(peekPx, fullCapPx)
        } else {
            fullCapPx
        }
        val targetPx = when (detent) {
            SheetDetent.PEEK -> peekPx
            SheetDetent.HALF -> halfPx
            SheetDetent.FULL -> fullPx
        }
        // Until the peek block has been measured once, snap instead of animating: otherwise the
        // sheet visibly grows by a dozen dp on the first frame after layout.
        val sheetPx by animateFloatAsState(
            targetValue = targetPx,
            animationSpec = if (peekHeightPx == 0) {
                tween(durationMillis = 0)
            } else {
                spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow)
            },
            label = "sheetHeight",
        )

        // Whether the FAB plate can run into the top chrome is a fact about the *available space*,
        // not about orientation: the plate is anchored just above the sheet, so a short window lifts
        // its top into the chrome row (landscape, small phones, a split-screen pane), and a narrow
        // window cannot hold search + 工具 + plate side by side. Both are measured here and drive one
        // set of consequences, so every form factor takes the same code path.
        val chromeRowDp = 56.dp + Dimens.chromeGap * 2
        // Four buttons + three 1 dp dividers (the plate's own 8 dp of vertical padding is left out
        // here exactly as it was for three, so the collision test stays a floor, not an estimate).
        val plateDp = Dimens.fabSize * 4 + 3.dp
        val bandDp = with(density) { (totalPx - sheetPx).coerceAtLeast(0f).toDp() }
        val rowAvailDp = with(density) { totalWidthPx.toDp() } - Dimens.chromeMargin * 2
        val rowNeeds = 140.dp + Dimens.chromeGap + 96.dp + Dimens.chromeGap + Dimens.fabGroupWidth
        val plateReachesChromeRow = bandDp < plateDp + chromeRowDp
        val reservePlateWidth = plateReachesChromeRow || rowAvailDp < rowNeeds
        // With the plate reserved and the row still tight, the 工具 label is what gives way: the entry
        // stays fully visible and tappable as an icon-only pill instead of being half covered.
        val toolsIconOnly = reservePlateWidth && rowAvailDp < rowNeeds + 56.dp
        // The floating chrome steps aside whenever the sheet leaves the peek detent or a place
        // card is showing: the card owns the bottom third of the screen in that moment, and at
        // half/full the tools/route panel would otherwise collide with the FAB stack.
        // …and it stays out of the keyboard's way too: typing in the search pill leaves only the
        // pill, the card and the map.
        val chromeVisible = detent == SheetDetent.PEEK && pendingPoint == null && imeInsetPx <= 0
        val chromeAlpha by animateFloatAsState(
            targetValue = if (chromeVisible) 1f else 0f,
            animationSpec = tween(durationMillis = 160),
            label = "chromeAlpha",
        )

        // Keep the simulated subject inside the band of map that the sheet leaves visible: with a
        // sheet at PEEK the subject sits half a sheet-height above the view centre, so its halo and
        // ring are never clipped by the sheet edge (audit R6).
        val subjectOffsetPx = (sheetPx / 2f).roundToInt()
        LaunchedEffect(subjectOffsetPx, backend) { backend.setSubjectOffsetY(subjectOffsetPx) }

        // ------------------------------------------------------------ the map
        AndroidView(
            factory = {
                backend.onCreate()
                backend.view
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Tone the raster tiles towards the M3 surfaces so the chrome and the base map read as one
        // picture. A *gradient* rather than a flat wash: a uniform 8 % veil over the whole frame is
        // what made the light theme look foggy and the labels pale, while the tint is only needed
        // where chrome actually sits — under the search pill and just above the sheet. The middle
        // of the frame (where the user reads the map) stays untouched.
        val scrimTop = if (darkMap) 0.30f else 0.10f
        val scrimBottom = if (darkMap) 0.34f else 0.16f
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.surface.copy(alpha = scrimTop),
                        0.26f to MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                        0.62f to MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                        0.86f to MaterialTheme.colorScheme.surface.copy(alpha = scrimBottom),
                    ),
                ),
        )

        // Which direction the veil must go depends on the theme: light needs a white veil (the system
        // icons are dark), dark needs a black one — `surface` in dark mode is *lighter* than the
        // night map tiles, so a surface veil brightened the top strip and broke the full-bleed look
        // (that was the dark sweep's finding, photographed before this change).
        val statusVeilDark = MaterialTheme.colorScheme.surface.luminance() < 0.2f
        val statusVeil = if (statusVeilDark) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surface
        val statusVeilTopAlpha = if (statusVeilDark) 0.55f else 0.62f
        val statusVeilMidAlpha = if (statusVeilDark) 0.20f else 0.26f
        // Status-bar legibility band. The map is deliberately full-bleed, but on pale tiles the
        // system clock and battery sit on light POI labels and stop being readable (R1/R6). One
        // short gradient in the *surface* role to the status-bar height: a white veil in light
        // (the icons there are dark) and a dark veil in dark mode, so no tonal bar is needed and
        // the map still reads as full-bleed.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(with(density) { navInsetTopPx.toDp() })
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to statusVeil.copy(alpha = statusVeilTopAlpha),
                        0.75f to statusVeil.copy(alpha = statusVeilMidAlpha),
                        1f to statusVeil.copy(alpha = 0f),
                    ),
                ),
        )

        // ----------------------------------------------------------- top chrome
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(
                    start = Dimens.chromeMargin,
                    end = if (reservePlateWidth) {
                        Dimens.chromeMargin + Dimens.fabGroupWidth + Dimens.chromeGap
                    } else {
                        Dimens.chromeMargin
                    },
                    top = Dimens.chromeGap,
                    bottom = Dimens.chromeGap,
                ),
        ) {
            if (pickMode) {
                PickBanner(onCancel = onCancelPick)
            } else androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                SearchPill(
                    query = query,
                    onQueryChange = onQueryChange,
                    onSubmit = onSearchSubmit,
                    // Tapping the pill only opens the field; searching is the IME action.
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(Dimens.chromeGap))
                // The 工具 entry is the search pill's twin: same 56 dp height, same circle, same
                // container/border/elevation, plus a visible label. The unlabelled 48 dp square that
                // used to sit here had the same visual weight as search but a different shape and no
                // name (audit S2: the worst of both).
                ChromeActionPill(
                    icon = Icons.Filled.Tune,
                    label = stringResource(R.string.action_tools),
                    onClick = onOpenTools,
                    // Floor width: the label must never be clipped to an icon-only sliver when the
                    // search pill is greedier than the row (landscape squeeze, audit R1/R5).
                    // Icon-only keeps its natural 56 dp instead of holding a label-width floor, so
                    // the freed 40 dp goes to the search pill rather than to empty space.
                    modifier = Modifier.widthIn(min = if (toolsIconOnly) 56.dp else 96.dp),
                    showLabel = !toolsIconOnly,
                    tag = "e2e.fab.tools",
                )
            }
            if (!hasNetwork && !pickMode) {
                Spacer(modifier = Modifier.height(Dimens.chromeGap))
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.map_offline_hint),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            // Live suggestions while typing: the pill keeps focus and the keyboard stays up, so
            // this is an inline card instead of a modal (Google-Maps behaviour).
            // A query that parses as a coordinate has a *local* answer, so it produces no network
            // result — without this the card stayed hidden and the user got no feedback at all that
            // the input was understood (they had to press 搜索 to find out).
            val parsedCoordinate = remember(query) {
                CoordinateParser.parse(query.trim())
            }
            // The card belongs to the peek state: at HALF/FULL the tools or the planner is the
            // subject and a floating result list would cover it.
            val showSuggestions = !pickMode && detent == SheetDetent.PEEK && query.isNotBlank() &&
                (parsedCoordinate != null || searchState.loading ||
                    searchState.error != null || searchState.results.isNotEmpty())
            if (showSuggestions) {
                Spacer(modifier = Modifier.height(Dimens.chromeGap))
                SearchSuggestionCard(
                    state = searchState,
                    parsedCoordinate = parsedCoordinate,
                    onPickParsed = onSearchSubmit,
                    onPick = onPickSuggestion,
                    onRetry = onSearchSubmit,
                    modifier = Modifier.onSizeChanged { suggestionsHeightPx = it.height },
                )
            }
        }

        // ------------------------------------------------------------- snackbar
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(
                    // Stacked under the search pill, and under the live-suggestion card when it
                    // is showing: the snackbar must never be painted behind another surface.
                    top = Dimens.searchBarHeight + Dimens.chromeMargin +
                        with(density) { suggestionsHeightPx.toDp() },
                    start = Dimens.chromeMargin,
                    end = Dimens.chromeMargin,
                )
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("e2e.snackbar"),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (visible && snackbarEnabled) SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.fillMaxWidth(),
            ) { data ->
                // `SnackbarHost` stacks its child at the top-start of its own box; the contract
                // wants a centred pill under the search bar, so the alignment is done here.
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shadowElevation = 6.dp,
                    ) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier
                                .widthIn(max = 560.dp)
                                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = data.visuals.message,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            val label = data.visuals.actionLabel
                            if (label != null) {
                                TextButton(onClick = { data.performAction() }) {
                                    Text(
                                        text = label,
                                        color = MaterialTheme.colorScheme.inversePrimary,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // A 1dp transparent child keeps this container in the accessibility tree (and hence in
            // `uiautomator dump`) while no snackbar is showing, so the e2e overlap matrix always
            // has real bounds to compare. It is invisible and takes no touches.
            Box(
                modifier = Modifier
                    .size(1.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.01f)),
            )
        }

        // ----------------------------------------------------------- place card
        // The card slides in from the sheet edge and fades back into it. `AnimatedVisibility` needs
        // a non-null child during the exit animation, hence the remembered last point.
        var lastCardPoint by remember { mutableStateOf<GeoPoint?>(null) }
        LaunchedEffect(pendingPoint) { if (pendingPoint != null) lastCardPoint = pendingPoint }
        AnimatedVisibility(
            visible = pendingPoint != null,
            enter = fadeIn(tween(150)) + slideInVertically(tween(180), initialOffsetY = { it / 3 }),
            exit = fadeOut(tween(120)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset { IntOffset(0, -(sheetPx.roundToInt() + with(density) { 12.dp.toPx() }.roundToInt())) }
                .padding(horizontal = Dimens.chromeMargin),
        ) {
            val card = lastCardPoint ?: pendingPoint
            if (card != null) {
                val distance = GeoMath.distanceMeters(position, card)
                PlaceCard(
                    title = stringResource(R.string.place_card_title),
                    coordinates = coordinateLine(card),
                    distance = stringResource(R.string.place_card_distance, Fmt.distance(distance)),
                    onSetPosition = {
                        setPositionHandler(card)
                        pendingPoint = null
                    },
                    onAddToRoute = {
                        onAddRoutePoint(card)
                        pendingPoint = null
                    },
                    onSave = {
                        onAddFavorite("", card)
                        pendingPoint = null
                    },
                    onNavigate = {
                        onNavigateTo(card)
                        pendingPoint = null
                    },
                    onDismiss = { pendingPoint = null },
                )
            }
        }

        // ------------------------------------------------------- tap affordance
        var lastChip by remember { mutableStateOf<TapChip?>(null) }
        LaunchedEffect(tapChip) { if (tapChip != null) lastChip = tapChip }
        AnimatedVisibility(
            visible = tapChip != null,
            enter = fadeIn(tween(150)) + slideInVertically(tween(180), initialOffsetY = { it / 3 }),
            exit = fadeOut(tween(120)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset { IntOffset(0, -(sheetPx.roundToInt() + with(density) { Dimens.aboveSheet.toPx() }.roundToInt())) }
                .padding(start = Dimens.chromeMargin, end = 76.dp),
        ) {
            val chip = lastChip ?: tapChip
            if (chip != null) {
                TapChipCard(
                    coordinates = coordinateLine(chip.point),
                    onUndo = {
                        setPositionHandler(chip.previous)
                        tapChip = null
                    },
                    onSave = {
                        onAddFavorite("", chip.point)
                        tapChip = null
                    },
                    onRoute = {
                        onAddRoutePoint(chip.point)
                        tapChip = null
                    },
                )
            }
        }

        // ------------------------------------------------------------- fabs
        // The stack does not just fade when the sheet takes over: it slips 10dp towards the sheet
        // edge as it fades, so it reads as "moving out of the way" instead of "blinking off".
        val fabShiftPx = with(density) { 10.dp.toPx() }
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(0, -(sheetPx.roundToInt() + with(density) { Dimens.aboveSheet.toPx() }.roundToInt())) }
                .padding(end = Dimens.chromeMargin)
                .graphicsLayer {
                    alpha = chromeAlpha
                    translationY = (1f - chromeAlpha) * fabShiftPx
                },
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // One tonal plate (audit S3) instead of three unrelated squares floating 10 dp apart.
            // The dividers keep the 48 dp touch targets and their exact bounds, so the overlap
            // matrix keeps measuring the same rectangles.
            ChromeFabGroup {
                ChromeFab(
                    icon = Icons.Filled.Layers,
                    contentDescription = stringResource(R.string.map_layer),
                    onClick = onOpenLayers,
                    active = false,
                    tag = "e2e.fab.layer",
                    grouped = true,
                )
                ChromeFabGroupDivider()
                ChromeFab(
                    icon = Icons.Filled.SportsEsports,
                    contentDescription = stringResource(R.string.map_joystick),
                    onClick = { joystickEnabled = !joystickEnabled },
                    active = joystickEnabled,
                    tag = "e2e.fab.joystick",
                    grouped = true,
                )
                ChromeFabGroupDivider()
                ChromeFab(
                    icon = Icons.Filled.MyLocation,
                    contentDescription = stringResource(R.string.map_back_to_position),
                    onClick = { backend.animateTo(position) },
                    tag = "e2e.fab.locate",
                    grouped = true,
                )
                ChromeFabGroupDivider()
                // 回到真实位置: the phone's own position, not the simulated one. The plate is
                // icon-only, so the glyph has to carry the difference on its own: a GNSS crosshair
                // (GpsFixed) rather than a second MyLocation arrow, which would be the same picture
                // twice with two different meanings. TalkBack reads 回到真实位置 via the content
                // description.
                ChromeFab(
                    icon = Icons.Filled.GpsFixed,
                    contentDescription = stringResource(R.string.map_real_location),
                    onClick = onJumpReal,
                    tag = "e2e.fab.real",
                    grouped = true,
                )
            }
        }

        // ---------------------------------------------------------- joystick
        // Springs in instead of tweening in: the pad appears *because the user asked for it*, and a
        // short overshoot confirms the tap even before the eye finds the control. Still < 220 ms,
        // per the "no flashy custom animation" rule in the contract.
        AnimatedVisibility(
            visible = joystickEnabled && chromeVisible && tapChip == null,
            enter = fadeIn(tween(150)) + scaleIn(
                animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
                initialScale = 0.82f,
            ),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.9f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset { IntOffset(0, -(sheetPx.roundToInt() + with(density) { Dimens.aboveSheet.toPx() }.roundToInt())) }
                .padding(start = Dimens.chromeMargin),
        ) {
            Box(modifier = Modifier.testTag("e2e.joystick")) {
                JoystickPad(
                    followHeading = config.followHeading,
                    headingDeg = headingDeg,
                    onVector = onJoystick,
                    onRelease = onReleaseJoystick,
                )
                // The pad had no visible way out: the only affordance was the toggle FAB, which is
                // off-screen-corner knowledge. A close chip on the pad's own rim fixes that (audit:
                // missing label / ambiguous affordance). It lives *inside* the pad's bounds so the
                // e2e overlap matrix measures the same rectangle as before.
                FilledTonalIconButton(
                    onClick = { joystickEnabled = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(36.dp)
                        .testTag("e2e.joystick.close"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.joystick_hide),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // ------------------------------------------------------------- sheet
        // One step of tonal elevation above the map (`surfaceContainerLow` in light, one lighter
        // role in dark where surfaceContainerLow sits within ~2 % of the dimmed tiles) plus the
        // 1dp hairline the contract asks for: without the stroke the dark sheet and the dark map
        // shared an edge and the sheet looked like a cut-out of the map rather than chrome on it.
        // With the keyboard up the sheet must take the whole area above it, not just `sheetPx` minus
        // the inset: the planner's own height is content-sized (~1 000 px), so subtracting the 894 px
        // keyboard left ~130 px — room for the header and the search field but no result rows at all.
        // Zero-inset (no keyboard) keeps `sheetPx` and every other state unchanged.
        // While the keyboard is up the sheet must never grow behind it: at PEEK it gets out of the
        // way entirely (the search card owns the visible band, exactly like Maps' collapsed sheet),
        // and the planner keeps everything between the search pill and the keyboard instead of the
        // screen height, which used to make the sheet cover the map and the keyboard at once.
        val aboveImePx = (totalPx - imeInsetPx).coerceAtLeast(0f)
        val layoutSheetPx = when {
            imeInsetPx <= 0 -> sheetPx
            detent == SheetDetent.PEEK -> 0f
            else -> (aboveImePx - chromeTopPx).coerceIn(with(density) { 240.dp.toPx() }, sheetPx)
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Over the app's navigation bar the sheet runs 2 dp *under* it, so its hairline and
                // shadow end there and sheet + bar read as one continuous bottom panel.
                .height(with(density) { layoutSheetPx.toDp() } + if (bottomBarShown) 2.dp else 0.dp)
                .offset(y = -(with(density) { imeInsetPx.toDp() }) + if (bottomBarShown) 2.dp else 0.dp)
                .testTag("e2e.sheet"),
            shape = SheetShape,
            color = sheetContainer(),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
            shadowElevation = 14.dp,
        ) {
            // The gesture-bar inset is applied once, at the bottom of the sheet, so the peek block
            // itself measures without it and the inset never ends up as a dead strip between the
            // action row and the tools panel at the HALF detent.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (bottomBarShown) Modifier else Modifier.navigationBarsPadding()),
            ) {
                var dragAccum by remember { mutableFloatStateOf(0f) }
                val threshold = with(density) { 22.dp.toPx() }
                val dragState = rememberDraggableState { delta -> dragAccum += delta }
                // At FULL the route planner owns the sheet: showing the peek row above it would
                // duplicate 开始/路线 and waste ~200 dp of the planner's height, so the peek block
                // collapses to a single status line with an explicit way back.
                if (detent == SheetDetent.FULL) {
                    CollapsedPeekHeader(
                        running = running,
                        modeLabel = modeLabel,
                        onCollapse = { onDetentChange(SheetDetent.PEEK) },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = dragState,
                                onDragStopped = {
                                    when {
                                        dragAccum < -threshold -> onDetentChange(detent.up())
                                        dragAccum > threshold -> onDetentChange(detent.down())
                                        else -> Unit
                                    }
                                    dragAccum = 0f
                                },
                            )
                            // Measures the peek block itself (no inset); the PEEK detent adds the
                            // gesture-bar height back when it computes its target.
                            .onSizeChanged { peekHeightPx = it.height },
                    ) {
                        PeekPanel(
                            running = running,
                            speed = config.speed,
                            speedMps = config.effectiveSpeedMps(),
                            coordinates = coordinateLine(position),
                            // Only a *confirmed* missing module is worth a warning: during the
                            // first seconds the bridge is still CHECKING, and a red badge on every
                            // cold start would read as a defect.
                            hint = if (link == ModuleLink.INACTIVE) {
                                stringResource(R.string.status_module_inactive)
                            } else {
                                null
                            },
                            // Tapping the warning opens the status sheet, which carries 查看激活方法:
                            // a warning that leads nowhere is only half a warning.
                            onHintClick = onOpenStatus,
                            favouriteHere = favouriteHere,
                            favouriteCount = favouriteCount,
                            accuracyLabel = accuracyLabel,
                            onOpenStatus = onOpenStatus,
                            onToggleRun = onToggleRun,
                            onOpenRoute = onOpenRoutePlanner,
                            onToggleFavourite = onToggleFavoriteHere,
                            onOpenFavorites = onOpenFavorites,
                            onSpeedSelect = onSpeedSelect,
                            dragHandle = {
                                SheetHandle(
                                    modifier = Modifier
                                        .testTag("e2e.sheet.handle")
                                        .clickable { onDetentChange(detent.up()) },
                                )
                            },
                            modifier = Modifier,
                        )
                    }
                }
                if (detent != SheetDetent.PEEK) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        when (detent) {
                            SheetDetent.HALF -> Column(modifier = Modifier.fillMaxSize()) { toolsContent() }
                            SheetDetent.FULL -> Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // The planner is full-height content with no insets
                                    // of its own; the horizontal gutter is ours.
                                    .padding(horizontal = 16.dp),
                            ) {
                                // The planner reports its own content height; the FULL detent uses
                                // it so a short planner never opens into a dead lower half.
                                routeContent { height -> routeContentPx = height }
                            }
                            SheetDetent.PEEK -> Unit
                        }
                    }
                }
            }
        }
    }
}

/** Replaces the search row while 自定义 → 在地图上选点 is waiting for a tap. */
@Composable
private fun PickBanner(onCancel: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.searchBarHeight)
            .testTag("e2e.map.pickbanner"),
        shape = com.immoral.location.ui.theme.PillShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 3.dp,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.MyLocation,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.shell_pick_banner),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

package com.immoral.location.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
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
import com.immoral.location.ui.components.ChromeFab
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
import com.immoral.location.net.PlaceResult
import com.immoral.location.ui.sheets.CollapsedPeekHeader
import com.immoral.location.ui.sheets.coordinateLine
import com.immoral.location.ui.sheets.SearchSuggestionCard
import com.immoral.location.ui.sheets.SearchUiState
import com.immoral.location.ui.sheets.speedLabelRes
import com.immoral.location.ui.theme.Dimens
import com.immoral.location.ui.theme.SheetShape
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
 * The single top-level surface: a full-bleed map with everything else floating on top of it.
 *
 * Layout, all overlaying the map:
 *  * top: pill search bar (56dp) + the tools avatar;
 *  * top-centre, under the search bar: the **only** snackbar host in the app;
 *  * right edge: vertical FAB stack (图层 / 摇杆 / 定位), fading out when the sheet is full;
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
    toolsContent: @Composable ColumnScope.() -> Unit,
    routeContent: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val setPositionHandler by rememberUpdatedState(onSetPosition)
    val favoriteTapHandler by rememberUpdatedState(onSelectFavorite)
    val altitudeRef by rememberUpdatedState(position.alt)
    val positionRef by rememberUpdatedState(position)

    var joystickEnabled by rememberSaveable { mutableStateOf(false) }
    var pendingPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var tapChip by remember { mutableStateOf<TapChip?>(null) }
    var selectedFavoriteId by remember { mutableStateOf<String?>(null) }
    var peekHeightPx by remember { mutableIntStateOf(0) }

    // The map callbacks are captured once by the (remembered) backend, so they must only read
    // state through `rememberUpdatedState` refs, never through captured values.
    val handleTap: (GeoPoint) -> Unit = remember {
        { raw ->
            val point = raw.copy(alt = altitudeRef)
            val previous = positionRef
            selectedFavoriteId = null
            pendingPoint = null
            // Single tap keeps the original product gesture: switch the simulated position right
            // away. The chip below the FABs is the affordance (undo / save / route), not a modal.
            setPositionHandler(point)
            tapChip = TapChip(point, previous)
        }
    }
    val handleLongPress: (GeoPoint) -> Unit = remember {
        { raw ->
            selectedFavoriteId = null
            tapChip = null
            pendingPoint = raw.copy(alt = altitudeRef)
        }
    }
    val handleFavoriteTap: (FavoritePlace) -> Unit = remember {
        { place ->
            selectedFavoriteId = place.id
            favoriteTapHandler(place)
        }
    }
    val handleMapGesture: () -> Unit = remember { { tapChip = null } }

    // The place card and the tap chip are peek-state affordances: expanding the sheet to the tools
    // or the route planner means the user moved on, and leaving them floating would either overlap
    // the panel or (at FULL) be pushed off-screen.
    LaunchedEffect(detent) {
        if (detent != SheetDetent.PEEK) {
            pendingPoint = null
            tapChip = null
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
            if (!amapLoaded.value) amapFailed = true
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, backend) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> backend.onResume()
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
    val favouriteHere = remember(favorites, position) {
        favorites.any { GeoMath.distanceMeters(position, it.point) < 25.0 }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Height of the live-search card, so the snackbar stacks *under* it instead of behind it.
        var suggestionsHeightPx by remember { mutableIntStateOf(0) }
        val totalPx = constraints.maxHeight.toFloat()
        // Never constrain the very first layout to a guessed peek height: the measured value is
        // read back from PeekPanel *inside* the sheet, so a too-small guess would lock the sheet at
        // a height that clips the action row. 42 % of the screen is comfortably above the panel's
        // intrinsic height (~200 dp) and settles invisibly in one frame because the first measured
        // value snaps instead of animating.
        //
        // The peek block itself measures *without* the gesture-bar inset (the sheet's Column owns
        // that padding, at its very bottom), so the PEEK detent has to add it back here.
        val navInsetPx = WindowInsets.navigationBars.getBottom(density)
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
        val fullPx = (totalPx - chromeTopPx).coerceAtLeast(halfPx)
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
        // The floating chrome steps aside whenever the sheet leaves the peek detent or a place
        // card is showing: the card owns the bottom third of the screen in that moment, and at
        // half/full the tools/route panel would otherwise collide with the FAB stack.
        val chromeVisible = detent == SheetDetent.PEEK && pendingPoint == null
        val chromeAlpha by animateFloatAsState(
            targetValue = if (chromeVisible) 1f else 0f,
            animationSpec = tween(durationMillis = 160),
            label = "chromeAlpha",
        )

        // ------------------------------------------------------------ the map
        AndroidView(
            factory = {
                backend.onCreate()
                backend.view
            },
            modifier = Modifier.fillMaxSize(),
        )

        // A very light tonal scrim over the raster tiles. The base map is a third-party raster
        // style, so tinting it towards `surface` is what makes the M3 chrome sit on top of it
        // instead of fighting it. Markers are drawn inside the map view, so they are tinted too —
        // at 5 % that is imperceptible and it keeps the whole surface coherent.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = if (darkMap) 0.12f else 0.08f),
                ),
        )

        // ----------------------------------------------------------- top chrome
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Dimens.chromeMargin, vertical = Dimens.chromeGap),
        ) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                SearchPill(
                    query = query,
                    onQueryChange = onQueryChange,
                    onSubmit = onSearchSubmit,
                    onClick = onSearchSubmit,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(Dimens.chromeGap))
                ChromeFab(
                    icon = Icons.Filled.Tune,
                    contentDescription = stringResource(R.string.action_tools),
                    onClick = onOpenTools,
                    tag = "e2e.fab.tools",
                )
            }
            // Live suggestions while typing: the pill keeps focus and the keyboard stays up, so
            // this is an inline card instead of a modal (Google-Maps behaviour).
            val showSuggestions = query.isNotBlank() &&
                (searchState.loading || searchState.error != null || searchState.results.isNotEmpty())
            if (showSuggestions) {
                Spacer(modifier = Modifier.height(Dimens.chromeGap))
                SearchSuggestionCard(
                    state = searchState,
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
            SnackbarHost(
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
        val card = pendingPoint
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
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .offset { IntOffset(0, -(sheetPx.roundToInt() + with(density) { 12.dp.toPx() }.roundToInt())) }
                    .padding(horizontal = Dimens.chromeMargin),
            )
        }

        // ------------------------------------------------------- tap affordance
        val chip = tapChip
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
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset { IntOffset(0, -(sheetPx.roundToInt() + with(density) { Dimens.aboveSheet.toPx() }.roundToInt())) }
                    .padding(start = Dimens.chromeMargin, end = 76.dp),
            )
        }

        // ------------------------------------------------------------- fabs
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(0, -(sheetPx.roundToInt() + with(density) { Dimens.aboveSheet.toPx() }.roundToInt())) }
                .padding(end = Dimens.chromeMargin)
                .graphicsLayer { alpha = chromeAlpha },
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChromeFab(
                icon = Icons.Filled.Layers,
                contentDescription = stringResource(R.string.map_layer),
                onClick = onOpenLayers,
                active = false,
                tag = "e2e.fab.layer",
            )
            ChromeFab(
                icon = Icons.Filled.SportsEsports,
                contentDescription = stringResource(R.string.map_joystick),
                onClick = { joystickEnabled = !joystickEnabled },
                active = joystickEnabled,
                tag = "e2e.fab.joystick",
            )
            ChromeFab(
                icon = Icons.Filled.MyLocation,
                contentDescription = stringResource(R.string.map_back_to_position),
                onClick = { backend.animateTo(position) },
                tag = "e2e.fab.locate",
            )
        }

        // ---------------------------------------------------------- joystick
        AnimatedVisibility(
            visible = joystickEnabled && chromeVisible && tapChip == null,
            enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.86f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120)),
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
            }
        }

        // ------------------------------------------------------------- sheet
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { sheetPx.toDp() })
                .testTag("e2e.sheet"),
            shape = SheetShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 14.dp,
        ) {
            // The gesture-bar inset is applied once, at the bottom of the sheet, so the peek block
            // itself measures without it and the inset never ends up as a dead strip between the
            // action row and the tools panel at the HALF detent.
            Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
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
                            favouriteHere = favouriteHere,
                            onOpenStatus = onOpenStatus,
                            onToggleRun = onToggleRun,
                            onOpenRoute = onOpenRoutePlanner,
                            onToggleFavourite = onToggleFavoriteHere,
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
                            ) { routeContent() }
                            SheetDetent.PEEK -> Unit
                        }
                    }
                }
            }
        }
    }
}

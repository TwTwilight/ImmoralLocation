package com.immoral.location.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.immoral.location.R
import com.immoral.location.bridge.ModuleLink
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.EngineState
import com.immoral.location.core.model.FavoritePlace
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.MoveMode
import com.immoral.location.core.model.RouteState
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.route.SavedRoute
import com.immoral.location.data.RecentPlace
import com.immoral.location.ui.components.ActionSheet
import com.immoral.location.ui.components.EmptyTile
import com.immoral.location.ui.components.FavoriteText
import com.immoral.location.ui.components.Fmt
import com.immoral.location.ui.components.GroupRow
import com.immoral.location.ui.components.IconBadge
import com.immoral.location.ui.components.SectionHeader
import com.immoral.location.ui.components.SheetAction
import com.immoral.location.ui.components.TextInputDialog
import com.immoral.location.ui.components.groupItemShape
import com.immoral.location.ui.route.RoutePlannerStrings
import com.immoral.location.ui.sheets.speedLabelRes
import com.immoral.location.ui.theme.tabular

/** Everything the 快捷 page can ask the shell to do. */
class QuickActions(
    val onToggleRun: () -> Unit,
    val onGoTo: (GeoPoint, String) -> Unit,
    val onShowOnMap: (GeoPoint) -> Unit,
    val onNavigateTo: (GeoPoint, String) -> Unit,
    val onSaveFavorite: (String, GeoPoint) -> Unit,
    val onRenameFavorite: (FavoritePlace, String) -> Unit,
    val onDeleteFavorite: (FavoritePlace) -> Unit,
    val onStartRoute: (SavedRoute) -> Unit,
    val onEditRoute: (SavedRoute) -> Unit,
    val onRenameRoute: (SavedRoute, String) -> Unit,
    val onDeleteRoute: (SavedRoute) -> Unit,
    val onPauseRoute: () -> Unit,
    val onResumeRoute: () -> Unit,
    val onStopRoute: () -> Unit,
    val onRemoveRecent: (GeoPoint) -> Unit,
    val onClearRecents: () -> Unit,
    val onOpenCustom: () -> Unit,
    val onOpenMap: () -> Unit,
    val onPlanRoute: () -> Unit,
    val onOpenStatus: () -> Unit,
    val onModuleHelp: () -> Unit,
    val onCopy: (String) -> Unit,
)

/** A recent teleport target with the best name we know for it. */
private data class RecentEntry(val point: GeoPoint, val name: String?)

private sealed interface QuickTarget {
    data class Favorite(val place: FavoritePlace) : QuickTarget
    data class Route(val route: SavedRoute) : QuickTarget
    data class Recent(val entry: RecentEntry) : QuickTarget
}

private const val NEAR_METERS = 25.0
private const val COLLAPSED_FAVORITES = 6

/**
 * 快捷 — the map-free start page (issue #1).
 *
 * The everyday flow is "pick one of my places or routes → start → switch to the target app". This
 * page does exactly that without waiting for a map: a status card that is also the start/stop
 * control, then 收藏地点, 已保存路线 and 最近使用 as tappable lists. Tap = go / start, long-press =
 * manage (rename, delete, show on map, navigate there). The map stays one tab away for picking new
 * points and planning routes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickScreen(
    state: EngineState?,
    link: ModuleLink,
    config: SimulationConfig,
    position: GeoPoint,
    favorites: List<FavoritePlace>,
    savedRoutes: List<SavedRoute>,
    pendingRouteId: String?,
    recentPositions: List<GeoPoint>,
    recentPlaces: List<RecentPlace>,
    snackbarHostState: SnackbarHostState,
    actions: QuickActions,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var sheetTarget by remember { mutableStateOf<QuickTarget?>(null) }
    var renameTarget by remember { mutableStateOf<QuickTarget?>(null) }
    var showAllFavorites by rememberSaveable { mutableStateOf(false) }

    val placeName: (GeoPoint) -> String? = { point ->
        favorites.firstOrNull { GeoMath.distanceMeters(point, it.point) < NEAR_METERS }
            ?.let { FavoriteText.displayName(it.name) }
            ?: recentPlaces.firstOrNull { GeoMath.distanceMeters(point, it.point) < NEAR_METERS }
                ?.name?.takeIf { !looksLikeCoordinate(it) }
    }
    val recents = remember(recentPositions, recentPlaces, favorites) {
        recentPositions.map { RecentEntry(it, placeName(it)) }
    }
    val hereName = remember(position, favorites, recentPlaces) { placeName(position) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .testTag("e2e.quick"),
        topBar = {
            MediumTopAppBar(
                title = { Text(stringResource(R.string.shell_tab_quick_title)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item(key = "hero") {
                StatusHero(
                    state = state,
                    link = link,
                    config = config,
                    position = position,
                    placeName = hereName,
                    actions = actions,
                )
            }

            // ------------------------------------------------------------ 收藏地点
            item(key = "fav-header") {
                SectionHeader(
                    title = stringResource(R.string.shell_section_favorites),
                    modifier = Modifier.padding(top = 20.dp),
                    action = {
                        TextButton(onClick = actions.onOpenCustom) {
                            Text(stringResource(R.string.shell_add_place))
                        }
                    },
                )
            }
            if (favorites.isEmpty()) {
                item(key = "fav-empty") {
                    EmptyTile(
                        icon = Icons.Outlined.BookmarkBorder,
                        title = stringResource(R.string.favorites_empty_title),
                        body = stringResource(R.string.shell_favorites_empty_body),
                    )
                }
            } else {
                val collapsible = favorites.size > COLLAPSED_FAVORITES + 1
                val shown = if (showAllFavorites || !collapsible) favorites else favorites.take(COLLAPSED_FAVORITES)
                val count = shown.size + if (collapsible) 1 else 0
                itemsIndexed(shown, key = { _, it -> "fav-" + it.id }) { index, place ->
                    val here = GeoMath.distanceMeters(position, place.point) < NEAR_METERS
                    val name = FavoriteText.displayName(place.name)
                    PlaceRow(
                        name = name,
                        detail = coordinateText(place.point) + "  ·  " + Fmt.distance(GeoMath.distanceMeters(position, place.point)),
                        icon = Icons.Filled.Place,
                        accent = true,
                        isHere = here && state?.running == true,
                        index = index,
                        count = count,
                        tag = "e2e.quick.fav.$index",
                        onClick = { actions.onGoTo(place.point, name) },
                        onLongClick = { sheetTarget = QuickTarget.Favorite(place) },
                    )
                    if (index < shown.lastIndex || count > shown.size) Spacer(modifier = Modifier.height(2.dp))
                }
                if (count > shown.size) {
                    item(key = "fav-more") {
                        GroupRow(
                            title = if (showAllFavorites) {
                                stringResource(R.string.shell_show_less)
                            } else {
                                stringResource(R.string.shell_show_all, favorites.size)
                            },
                            shape = groupItemShape(count - 1, count),
                            onClick = { showAllFavorites = !showAllFavorites },
                        )
                    }
                }
            }

            // ------------------------------------------------------------ 已保存路线
            item(key = "route-header") {
                SectionHeader(
                    title = stringResource(R.string.shell_section_routes),
                    modifier = Modifier.padding(top = 20.dp),
                    action = {
                        TextButton(onClick = actions.onPlanRoute) {
                            Text(stringResource(R.string.shell_plan_route))
                        }
                    },
                )
            }
            if (savedRoutes.isEmpty()) {
                item(key = "route-empty") {
                    EmptyTile(
                        icon = Icons.Outlined.Route,
                        title = stringResource(R.string.shell_routes_empty_title),
                        body = stringResource(R.string.shell_routes_empty_body),
                    )
                }
            } else {
                itemsIndexed(savedRoutes, key = { _, it -> "route-" + it.id }) { index, route ->
                    RouteRow(
                        route = route,
                        index = index,
                        count = savedRoutes.size,
                        preparing = pendingRouteId == route.id,
                        onClick = { actions.onStartRoute(route) },
                        onLongClick = { sheetTarget = QuickTarget.Route(route) },
                    )
                    if (index < savedRoutes.lastIndex) Spacer(modifier = Modifier.height(2.dp))
                }
            }

            // ------------------------------------------------------------ 最近使用
            if (recents.isNotEmpty()) {
                item(key = "recent-header") {
                    SectionHeader(
                        title = stringResource(R.string.tools_recent),
                        modifier = Modifier.padding(top = 20.dp),
                        action = {
                            TextButton(onClick = actions.onClearRecents) {
                                Text(stringResource(R.string.shell_clear))
                            }
                        },
                    )
                }
                itemsIndexed(recents, key = { _, it -> "recent-${it.point.lat},${it.point.lon}" }) { index, entry ->
                    PlaceRow(
                        name = entry.name ?: coordinateText(entry.point),
                        detail = if (entry.name != null) {
                            coordinateText(entry.point) + "  ·  " + Fmt.distance(GeoMath.distanceMeters(position, entry.point))
                        } else {
                            Fmt.distance(GeoMath.distanceMeters(position, entry.point))
                        },
                        icon = Icons.Filled.History,
                        accent = false,
                        isHere = false,
                        index = index,
                        count = recents.size,
                        tag = "e2e.quick.recent.$index",
                        onClick = { actions.onGoTo(entry.point, entry.name ?: coordinateText(entry.point)) },
                        onLongClick = { sheetTarget = QuickTarget.Recent(entry) },
                    )
                    if (index < recents.lastIndex) Spacer(modifier = Modifier.height(2.dp))
                }
            }

            item(key = "hint") {
                Text(
                    text = stringResource(R.string.shell_quick_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
    }

    // ---------------------------------------------------------------- long-press sheets
    when (val target = sheetTarget) {
        is QuickTarget.Favorite -> {
            val name = FavoriteText.displayName(target.place.name)
            ActionSheet(
                title = name,
                subtitle = coordinateText(target.place.point),
                onDismiss = { sheetTarget = null },
                actions = listOf(
                    SheetAction(Icons.Filled.PlayArrow, stringResource(R.string.place_action_move_short)) {
                        actions.onGoTo(target.place.point, name)
                    },
                    SheetAction(Icons.Filled.Map, stringResource(R.string.shell_show_on_map)) {
                        actions.onShowOnMap(target.place.point)
                    },
                    SheetAction(Icons.Filled.Directions, stringResource(R.string.place_action_navigate)) {
                        actions.onNavigateTo(target.place.point, name)
                    },
                    SheetAction(Icons.Filled.ContentCopy, stringResource(R.string.action_copy_coords)) {
                        actions.onCopy(coordinateText(target.place.point))
                    },
                    SheetAction(Icons.Filled.Edit, stringResource(R.string.action_rename)) {
                        renameTarget = target
                    },
                    SheetAction(Icons.Filled.Delete, stringResource(R.string.action_delete), destructive = true) {
                        actions.onDeleteFavorite(target.place)
                    },
                ),
            )
        }

        is QuickTarget.Route -> {
            val route = target.route
            ActionSheet(
                title = route.name,
                subtitle = routeSummary(route),
                onDismiss = { sheetTarget = null },
                actions = listOf(
                    SheetAction(Icons.Filled.PlayArrow, stringResource(R.string.shell_start_route)) {
                        actions.onStartRoute(route)
                    },
                    SheetAction(Icons.Filled.EditLocationAlt, stringResource(R.string.shell_edit_route)) {
                        actions.onEditRoute(route)
                    },
                    SheetAction(Icons.Filled.Place, stringResource(R.string.shell_go_to_origin)) {
                        actions.onGoTo(route.origin, coordinateText(route.origin))
                    },
                    SheetAction(Icons.Filled.Edit, stringResource(R.string.action_rename)) {
                        renameTarget = target
                    },
                    SheetAction(Icons.Filled.Delete, stringResource(R.string.action_delete), destructive = true) {
                        actions.onDeleteRoute(route)
                    },
                ),
            )
        }

        is QuickTarget.Recent -> {
            val entry = target.entry
            val label = entry.name ?: coordinateText(entry.point)
            ActionSheet(
                title = label,
                subtitle = if (entry.name != null) coordinateText(entry.point) else null,
                onDismiss = { sheetTarget = null },
                actions = listOf(
                    SheetAction(Icons.Filled.PlayArrow, stringResource(R.string.place_action_move_short)) {
                        actions.onGoTo(entry.point, label)
                    },
                    SheetAction(Icons.Filled.FavoriteBorder, stringResource(R.string.shell_save_as_favorite)) {
                        renameTarget = target
                    },
                    SheetAction(Icons.Filled.Map, stringResource(R.string.shell_show_on_map)) {
                        actions.onShowOnMap(entry.point)
                    },
                    SheetAction(Icons.Filled.Directions, stringResource(R.string.place_action_navigate)) {
                        actions.onNavigateTo(entry.point, label)
                    },
                    SheetAction(Icons.Filled.ContentCopy, stringResource(R.string.action_copy_coords)) {
                        actions.onCopy(coordinateText(entry.point))
                    },
                    SheetAction(Icons.Filled.Delete, stringResource(R.string.shell_remove_recent), destructive = true) {
                        actions.onRemoveRecent(entry.point)
                    },
                ),
            )
        }

        null -> Unit
    }

    when (val target = renameTarget) {
        is QuickTarget.Favorite -> TextInputDialog(
            title = stringResource(R.string.favorites_rename_title),
            initial = FavoriteText.displayName(target.place.name),
            label = stringResource(R.string.favorites_name_hint),
            onConfirm = {
                actions.onRenameFavorite(target.place, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )

        is QuickTarget.Route -> TextInputDialog(
            title = stringResource(R.string.shell_rename_route),
            initial = target.route.name,
            label = stringResource(R.string.shell_route_name),
            onConfirm = {
                actions.onRenameRoute(target.route, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )

        is QuickTarget.Recent -> TextInputDialog(
            title = stringResource(R.string.shell_save_as_favorite),
            initial = target.entry.name.orEmpty(),
            label = stringResource(R.string.favorites_name_hint),
            supporting = coordinateText(target.entry.point),
            onConfirm = {
                actions.onSaveFavorite(it, target.entry.point)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )

        null -> Unit
    }
}

/**
 * The page's headline: state, where we are, and the one control that matters (start / stop).
 * While a route plays it also carries the progress and pause / resume / stop.
 */
@Composable
private fun StatusHero(
    state: EngineState?,
    link: ModuleLink,
    config: SimulationConfig,
    position: GeoPoint,
    placeName: String?,
    actions: QuickActions,
) {
    val running = state?.running == true
    val routeMode = running && (state?.mode == MoveMode.ROUTE) &&
        state.routeState != RouteState.IDLE
    val container by animateColorAsState(
        if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "heroContainer",
    )
    val onContainer = if (running) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val muted = onContainer.copy(alpha = 0.72f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("e2e.quick.hero"),
        shape = RoundedCornerShape(28.dp),
        color = container,
        contentColor = onContainer,
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(if (running) R.string.peek_running else R.string.peek_idle),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                ModuleChip(link = link, onClick = if (link == ModuleLink.INACTIVE) actions.onModuleHelp else actions.onOpenStatus)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                // The route state already lives in the meta line below, so the headline stays the
                // *place*: "正在沿路线移动" was wrong the moment a route finished.
                text = placeName ?: stringResource(
                    if (running) R.string.shell_current_position else R.string.shell_last_position,
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = coordinateText(position),
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = muted,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("e2e.quick.position"),
                )
                IconButton(onClick = { actions.onCopy(coordinateText(position)) }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy_coords),
                        tint = muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (routeMode && state != null) {
                val stateLabel = stringResource(
                    when (state.routeState) {
                        RouteState.PLAYING -> R.string.route_state_playing
                        RouteState.PAUSED -> R.string.route_state_paused
                        RouteState.FINISHED -> R.string.route_state_finished
                        RouteState.IDLE -> R.string.route_state_idle
                    },
                )
                Text(
                    text = stringResource(R.string.mode_route) + " · " + stateLabel + " · " +
                        (state.routeProgress * 100).toInt() + "%",
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.routeProgress.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp),
                )
            } else {
                Text(
                    text = stringResource(speedLabelRes(config.speed)) + " · " +
                        Fmt.trimZero(config.effectiveSpeedMps()) + " m/s",
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = actions.onToggleRun,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("e2e.quick.toggle"),
                    colors = if (running) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Icon(
                        if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(if (running) R.string.action_stop else R.string.action_start),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (routeMode && state != null && state.routeState != RouteState.FINISHED) {
                    val paused = state.routeState == RouteState.PAUSED
                    FilledTonalIconButton(
                        onClick = if (paused) actions.onResumeRoute else actions.onPauseRoute,
                        modifier = Modifier.size(52.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                        Icon(
                            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = stringResource(if (paused) R.string.route_play else R.string.route_pause),
                        )
                    }
                }
                FilledTonalIconButton(
                    onClick = { actions.onShowOnMap(position) },
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Icon(Icons.Filled.Map, contentDescription = stringResource(R.string.shell_show_on_map))
                }
            }
        }
    }
}

/** 模块已激活 / 未激活 / 连接中, as a small tonal chip at the end of the hero's status line. */
@Composable
private fun ModuleChip(link: ModuleLink, onClick: () -> Unit) {
    val (container, content) = when (link) {
        ModuleLink.ACTIVE -> MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f) to
            MaterialTheme.colorScheme.primary
        ModuleLink.INACTIVE -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ModuleLink.CHECKING -> MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f) to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
        modifier = Modifier.testTag("e2e.quick.module"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (link) {
                ModuleLink.CHECKING -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = content,
                )
                else -> Icon(
                    if (link == ModuleLink.ACTIVE) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(
                    when (link) {
                        ModuleLink.ACTIVE -> R.string.status_module_active
                        ModuleLink.INACTIVE -> R.string.status_module_inactive
                        ModuleLink.CHECKING -> R.string.status_connecting
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun PlaceRow(
    name: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Boolean,
    isHere: Boolean,
    index: Int,
    count: Int,
    tag: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    GroupRow(
        title = name,
        subtitle = detail,
        subtitleMaxLines = 2,
        shape = groupItemShape(index, count),
        modifier = Modifier.testTag(tag),
        onClick = onClick,
        onLongClick = onLongClick,
        leading = {
            IconBadge(
                icon = icon,
                container = if (accent) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                content = if (accent) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailing = {
            if (isHere) {
                Text(
                    text = stringResource(R.string.shell_here),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            } else {
                FilledTonalIconButton(onClick = onClick) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.place_action_move_short))
                }
            }
        },
    )
}

@Composable
private fun RouteRow(
    route: SavedRoute,
    index: Int,
    count: Int,
    preparing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    GroupRow(
        title = route.name,
        subtitle = routeSummary(route),
        shape = groupItemShape(index, count),
        modifier = Modifier.testTag("e2e.quick.route.$index"),
        onClick = onClick,
        onLongClick = onLongClick,
        leading = {
            IconBadge(
                icon = Icons.Filled.AltRoute,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
        trailing = {
            if (preparing) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                }
            } else {
                FilledTonalIconButton(onClick = onClick) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.shell_start_route))
                }
            }
        },
    )
}

@Composable
private fun routeSummary(route: SavedRoute): String {
    val parts = mutableListOf(stringResource(RoutePlannerStrings.labelRes(route.profile)))
    if (route.distanceMeters > 0) parts += RoutePlannerStrings.distanceLabel(route.distanceMeters)
    if (route.durationSeconds > 0) parts += RoutePlannerStrings.durationLabel(route.durationSeconds)
    if (route.waypoints.isNotEmpty()) parts += stringResource(R.string.shell_waypoint_count, route.waypoints.size)
    return parts.joinToString("  ·  ")
}

/** `31.227200, 121.478100` — the form every list and the clipboard use. */
fun coordinateText(point: GeoPoint): String = "${Fmt.coord(point.lat)}, ${Fmt.coord(point.lon)}"

private val coordinateLike = Regex("^[-+0-9.,;\\s\\uFF0C\\u3001]+$")

private fun looksLikeCoordinate(text: String): Boolean =
    text.startsWith("坐标") || coordinateLike.matches(text.trim())

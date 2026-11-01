package com.immoral.location.ui.route

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.immoral.location.R
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.route.RouteProfile
import com.immoral.location.core.route.RouteResult
import com.immoral.location.core.route.RouteSource
import com.immoral.location.core.route.SavedRoute
import com.immoral.location.data.RecentPlace
import com.immoral.location.ui.theme.CardShape
import kotlinx.coroutines.delay

/**
 * Surface roles used *inside* the bottom sheet.
 *
 * The sheet is `sheetContainer()` — `surfaceContainerLow` in light, `surfaceContainer` in dark — so
 * an inner card has to sit one step up the M3 tonal ramp (`surfaceContainerHigh`) or it reads as a
 * near-white hole in a tonal sheet. Elevation stays 0 and the boundary is the same 1 dp
 * `outlineVariant` hairline the map chrome uses, which is what makes the planner and the rest of
 * the app look like one design instead of two stacked ones.
 */
@Composable
private fun plannerCardColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

/** The focal card (route summary): one step above [plannerCardColor] so the result stands out. */
@Composable
private fun plannerFocalColor(): Color = MaterialTheme.colorScheme.surfaceContainerHighest

/** Faint hairline matching the map chrome's card outline. */
@Composable
private fun plannerBorderColor(): Color =
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)

/**
 * Height of every action control in the planner (primary, tonal, icon).
 *
 * Matches the peek card's action row so the sheet and the planner read as one control system;
 * the icon button gets the same square box so the three actions share one baseline.
 */
private val ActionHeight = 48.dp

/** Hairline used *between rows inside* a card: fainter than the card boundary. */
@Composable
private fun plannerDividerColor(): Color =
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

/**
 * A card inside the sheet: tonal container, 20 dp radius, 1 dp hairline, no elevation.
 *
 * @param focal `true` for the route summary, which gets the highest container role.
 */
@Composable
private fun PlannerCard(
    modifier: Modifier = Modifier,
    focal: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = if (focal) plannerFocalColor() else plannerCardColor(),
        border = BorderStroke(1.dp, plannerBorderColor()),
    ) {
        Column(content = content)
    }
}

/**
 * 路径规划 sheet content — the Google-Maps-style planner.
 *
 * Rendered as the **full-detent content** of the map shell's `ModalBottomSheet`: no scaffold, no
 * `ModalBottomSheet` of its own and no system-bar insets (the host already removed the gesture bar
 * and applies the horizontal padding), so the only padding added here is 24dp of bottom breathing
 * room.
 *
 * Three states, all driven by [RoutePlannerController]:
 *  1. **empty** — only the origin/destination fields, with the live position as a ready-to-use origin;
 *  2. **planning** — progress bar + disabled primary action;
 *  3. **planned** — result card (distance, ETA, waypoint count, data source) + actions.
 *
 * A network failure never blocks the flow: the geometry degrades to a straight line and the reason
 * is shown as a non-blocking line inside the result card.
 *
 * @param controller State owner; created once by `AppController`.
 * @param onDismiss Closes the sheet (called by the header's close button).
 */
@Composable
fun RoutePlannerHost(
    controller: RoutePlannerController,
    onDismiss: () -> Unit,
) {
    val searchTarget by controller.searchTarget.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("e2e.route.planner"),
        // Transparent on purpose: the host sheet already paints its own M3 sheet container
        // (`surfaceContainerLow` in light, `surfaceContainer` in dark). Painting `surface` here
        // would lay a second, whiter panel over the sheet and is exactly what made the planner read
        // as a different design stacked inside the sheet.
        color = Color.Transparent,
    ) {
        if (searchTarget != null) {
            RouteSearchPage(controller = controller)
        } else {
            RoutePlanPage(controller = controller, onDismiss = onDismiss)
        }
    }
}

// ---------------------------------------------------------------------------- plan page

@Composable
private fun RoutePlanPage(
    controller: RoutePlannerController,
    onDismiss: () -> Unit,
) {
    val origin by controller.origin.collectAsStateWithLifecycle()
    val destination by controller.destination.collectAsStateWithLifecycle()
    val waypoints by controller.waypoints.collectAsStateWithLifecycle()
    val profile by controller.profile.collectAsStateWithLifecycle()
    val result by controller.result.collectAsStateWithLifecycle()
    val planning by controller.planning.collectAsStateWithLifecycle()
    val notice by controller.notice.collectAsStateWithLifecycle()
    val savedRoutes by controller.savedRoutes.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        SheetHeader(
            title = stringResource(R.string.rp_title),
            subtitle = stringResource(R.string.rp_subtitle),
            onDismiss = onDismiss,
        )
        LazyColumn(
            // The list owns the flexible height between the header and the action bar: content
            // hugs the top (no band between the mode segment and the summary), long content
            // scrolls, and the action bar below is a fixed, always-reachable control row — so the
            // leftover height reads as "content area above an action bar", not as a hole.
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StopsCard(
                    controller = controller,
                    origin = origin,
                    destination = destination,
                    waypoints = waypoints,
                )
            }
            item { AddStopRow(controller = controller) }
            item { TravelModeRow(profile = profile, onSelect = controller::setProfile) }

            if (planning) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("e2e.route.planning"),
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.rp_planning),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                ResultCard(
                    result = result,
                    notice = notice,
                    planning = planning,
                )
            }

            if (savedRoutes.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.rp_saved_routes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
                items(savedRoutes, key = { it.id }) { route ->
                    SavedRouteRow(
                        route = route,
                        onLoad = { controller.loadSaved(route) },
                        onDelete = { controller.deleteSaved(route) },
                    )
                }
            }
        }
        // Fixed action bar: the flexible height is the content area above it, so the leftover
        // reads as deliberate separation rather than a gap between two floating blocks.
        HorizontalDivider(
            color = plannerBorderColor(),
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        ActionsRow(controller = controller, result = result, planning = planning)
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
        }
    }
}

@Composable
private fun StopsCard(
    controller: RoutePlannerController,
    origin: RoutePlace?,
    destination: RoutePlace?,
    waypoints: List<RoutePlace>,
) {
    PlannerCard {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            StopField(
                badge = { StopBadge(label = "A", container = MaterialTheme.colorScheme.primaryContainer) },
                label = stringResource(R.string.rp_origin),
                value = origin?.name ?: stringResource(R.string.rp_tap_to_search),
                testTag = "e2e.route.origin",
                onClick = { controller.openSearch(RouteFieldTarget(RouteFieldKind.ORIGIN)) },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = plannerDividerColor())
            waypoints.forEachIndexed { index, waypoint ->
                StopField(
                    badge = {
                        StopBadge(
                            label = "${index + 1}",
                            container = MaterialTheme.colorScheme.primaryContainer,
                        )
                    },
                    label = stringResource(R.string.rp_waypoint, index + 1),
                    value = waypoint.name,
                    testTag = "e2e.route.waypoint.$index",
                    onClick = {
                        controller.openSearch(
                            RouteFieldTarget(RouteFieldKind.WAYPOINT, index),
                        )
                    },
                    trailing = {
                        IconButton(
                            onClick = { controller.moveWaypoint(index, index - 1) },
                            enabled = index > 0,
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.rp_move_up, index + 1),
                            )
                        }
                        IconButton(
                            onClick = { controller.moveWaypoint(index, index + 1) },
                            enabled = index < waypoints.lastIndex,
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.rp_move_down, index + 1),
                            )
                        }
                        IconButton(onClick = { controller.removeWaypoint(index) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(
                                    R.string.rp_remove_waypoint,
                                    index + 1,
                                ),
                            )
                        }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = plannerDividerColor())
            }
            StopField(
                badge = {
                    StopBadge(
                        label = "B",
                        container = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                },
                label = stringResource(R.string.rp_destination),
                value = destination?.name ?: stringResource(R.string.rp_tap_to_search),
                testTag = "e2e.route.dest",
                onClick = { controller.openSearch(RouteFieldTarget(RouteFieldKind.DESTINATION)) },
            )
        }
    }
}

@Composable
private fun StopBadge(
    label: String,
    container: Color,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = contentColor,
        modifier = Modifier.size(28.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun StopField(
    badge: @Composable () -> Unit,
    label: String,
    value: String,
    testTag: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        badge()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                Icons.Filled.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AddStopRow(controller: RoutePlannerController) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = {
                controller.openSearch(RouteFieldTarget(RouteFieldKind.WAYPOINT, -1))
            },
            label = { Text(stringResource(R.string.rp_add_waypoint)) },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
            modifier = Modifier.testTag("e2e.route.addwaypoint"),
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = controller::swapEnds) {
            Icon(Icons.Filled.SwapVert, contentDescription = stringResource(R.string.rp_swap))
        }
        IconButton(onClick = controller::resetOrigin) {
            Icon(
                Icons.Filled.MyLocation,
                contentDescription = stringResource(R.string.rp_use_current),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TravelModeRow(
    profile: RouteProfile,
    onSelect: (RouteProfile) -> Unit,
) {
    val modes = listOf(
        RouteProfile.WALK to "e2e.route.mode.walk",
        RouteProfile.BIKE to "e2e.route.mode.bike",
        RouteProfile.DRIVE to "e2e.route.mode.drive",
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, (mode, tag) ->
            SegmentedButton(
                selected = profile == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                // Same 48 dp control height as the actions below, so the whole planner shares the
                // peek card's control rhythm instead of mixing 40 dp segments with 48 dp buttons.
                modifier = Modifier
                    .height(ActionHeight)
                    .testTag(tag),
                icon = {},
                label = { Text(stringResource(RoutePlannerStrings.labelRes(mode)), maxLines = 1) },
            )
        }
    }
}

@Composable
private fun ResultCard(
    result: RouteResult?,
    notice: String?,
    planning: Boolean,
) {
    PlannerCard(
        focal = true,
        modifier = Modifier.testTag("e2e.route.result"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (result == null || !result.isUsable) {
                // Neutral empty state only. A red "请先选择起点和终点" here would scold the user for
                // something they are still doing (the origin is usually already filled in); the
                // actionable message appears through [notice] when 开始模拟 is actually pressed.
                Text(
                    text = stringResource(R.string.rp_no_route_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatColumn(
                    label = stringResource(R.string.rp_distance),
                    value = RoutePlannerStrings.distanceLabel(result.distanceMeters),
                    emphasized = true,
                )
                StatColumn(
                    label = stringResource(R.string.rp_duration),
                    value = RoutePlannerStrings.durationLabel(result.durationSeconds),
                )
                StatColumn(
                    label = stringResource(R.string.rp_waypoint_total),
                    value = RoutePlannerStrings.countLabel(result.waypointCount),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.rp_source) + " · " +
                    stringResource(RoutePlannerStrings.sourceRes(result.source)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (result.source == RouteSource.STRAIGHT_LINE) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = notice ?: stringResource(R.string.rp_degraded),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("e2e.route.notice"),
                )
            } else if (planning) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.rp_planning),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, emphasized: Boolean = false) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = if (emphasized) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.titleMedium
            },
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ActionsRow(
    controller: RoutePlannerController,
    result: RouteResult?,
    planning: Boolean,
) {
    val request = controller.currentRequest()
    val canStart = !planning && result?.isUsable == true
    val canEdit = !planning && request?.isUsable == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = controller::start,
            enabled = canStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(ActionHeight)
                .testTag("e2e.route.start"),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.size(6.dp))
            Text(stringResource(R.string.rp_start))
        }
        // Two equal tonal actions plus a destructive icon action. Three equal-weight *labelled*
        // buttons over-constrain a 360dp-wide sheet (「规划路线」 used to render as 「规划路」), so
        // 清除 is an icon button here — deliberately distinct because it destroys the plan.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = controller::plan,
                enabled = canEdit,
                modifier = Modifier
                    .weight(1.2f)
                    .height(ActionHeight)
                    .testTag("e2e.route.plan"),
            ) {
                Text(stringResource(R.string.rp_plan), maxLines = 1)
            }
            FilledTonalButton(
                onClick = controller::save,
                enabled = canEdit,
                modifier = Modifier
                    .weight(1f)
                    .height(ActionHeight)
                    .testTag("e2e.route.save"),
            ) {
                Text(stringResource(R.string.rp_save), maxLines = 1)
            }
            IconButton(
                onClick = controller::clear,
                modifier = Modifier
                    .size(ActionHeight)
                    .testTag("e2e.route.clear"),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.rp_clear_route),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SavedRouteRow(
    route: SavedRoute,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = plannerCardColor(),
        border = BorderStroke(1.dp, plannerBorderColor()),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onLoad),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = route.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(RoutePlannerStrings.labelRes(route.profile)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.rp_delete_saved, route.name),
                )
            }
        }
    }
}

// -------------------------------------------------------------------------- search page

@Composable
private fun RouteSearchPage(controller: RoutePlannerController) {
    val target by controller.searchTarget.collectAsStateWithLifecycle()
    val query by controller.searchQuery.collectAsStateWithLifecycle()
    val results by controller.searchResults.collectAsStateWithLifecycle()
    val searching by controller.searching.collectAsStateWithLifecycle()
    val error by controller.searchError.collectAsStateWithLifecycle()
    val recentPlaces by controller.recentPlaces.collectAsStateWithLifecycle()
    var localQuery by remember(query) { mutableStateOf(query) }
    val focusRequester = remember { FocusRequester() }

    // The search sheet opens focused: the user (and the e2e harness, which pipes `input text`
    // into the focused widget) must be able to type immediately without an extra tap.
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    // Typing is debounced so a 12-character street name costs one request, not twelve.
    LaunchedEffect(query) {
        if (query.trim().length >= 2) {
            delay(350)
            controller.submitSearch(query)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = controller::closeSearch) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.rp_search_back),
                )
            }
            Text(
                text = when (target?.kind) {
                    RouteFieldKind.ORIGIN -> stringResource(R.string.rp_origin)
                    RouteFieldKind.DESTINATION -> stringResource(R.string.rp_destination)
                    RouteFieldKind.WAYPOINT -> stringResource(R.string.rp_add_waypoint)
                    null -> stringResource(R.string.rp_search_title)
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        OutlinedTextField(
            value = localQuery,
            onValueChange = {
                localQuery = it
                controller.setSearchQuery(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("e2e.route.search")
                .focusRequester(focusRequester),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (localQuery.isNotEmpty()) {
                    IconButton(onClick = {
                        localQuery = ""
                        controller.setSearchQuery("")
                    }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.rp_search_clear),
                        )
                    }
                }
            },
            placeholder = { Text(stringResource(R.string.rp_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { controller.submitSearch(localQuery) }),
            shape = MaterialTheme.shapes.large,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (searching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.rp_searching),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (error != null) {
            Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (results.isEmpty() && query.isBlank()) {
                item {
                    SectionLabel(stringResource(R.string.rp_recent_places))
                }
                if (recentPlaces.isEmpty()) {
                    item {
                        EmptyHint(stringResource(R.string.rp_recent_empty))
                    }
                }
                items(recentPlaces, key = { "${it.name}|${it.point.lat}|${it.point.lon}" }) { place ->
                    PlaceRow(
                        title = place.name,
                        subtitle = RoutePlannerStrings.coordinateLabel(place.point),
                        onClick = { controller.pickPlace(RoutePlace(place.name, place.point)) },
                    )
                }
                val points: List<GeoPoint> = controller.recentPoints()
                if (points.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.tools_recent)) }
                    items(points, key = { "p${it.lat}|${it.lon}" }) { point ->
                        PlaceRow(
                            title = RoutePlannerStrings.coordinateLabel(point),
                            subtitle = "",
                            onClick = {
                                controller.pickPlace(
                                    RoutePlace(RoutePlannerStrings.coordinateLabel(point), point),
                                )
                            },
                        )
                    }
                }
            }
            itemsIndexed(
                results,
                key = { _, place -> "${place.name}|${place.point.lat}|${place.point.lon}" },
            ) { index, place ->
                PlaceRow(
                    title = place.name,
                    subtitle = RoutePlannerStrings.coordinateLabel(place.point),
                    onClick = { controller.pickPlace(place) },
                    modifier = Modifier.testTag("e2e.route.search.place.$index"),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun PlaceRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = {
            Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = if (subtitle.isNotBlank()) {
            { Text(text = subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        leadingContent = {
            Icon(
                Icons.Filled.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.clickable(onClick = onClick),
    )
}

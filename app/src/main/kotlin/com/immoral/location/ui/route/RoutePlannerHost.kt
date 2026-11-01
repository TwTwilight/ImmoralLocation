package com.immoral.location.ui.route

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.layout.onSizeChanged
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
import com.immoral.location.ui.components.SheetHint
import com.immoral.location.ui.components.SheetLabel
import com.immoral.location.ui.components.SheetSupporting
import com.immoral.location.ui.components.SheetTitle
import com.immoral.location.ui.components.SheetValue
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
 * Matches the peek card's action row so the sheet and the planner read as one control system.
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
 * 路径规划 sheet content — the Google-Maps-style planner, second pass.
 *
 * ## What the audit changed (and why)
 *
 * * **The sheet hugs its content.** Before this pass the planner was laid out inside a
 *   `fillMaxSize` surface while the host sheet was expanded to the full detent, so an empty or
 *   half-filled planner left ~800 px of dead container below its actions. The page now reports its
 *   own measured height through [onContentHeightChange] and the host sizes the FULL detent to that
 *   height (capped by the screen), so the sheet grows as the plan does.
 * * **Progressive disclosure.** Disabled primaries were the bulk of the frame (开始模拟 / 规划路线 /
 *   保存路线, all greyed). Each state now renders only what is currently possible: nothing while
 *   there is no destination (a plain hint instead), 规划路线 once a route is requestable, and
 *   开始模拟 + 重新规划 / 保存路线 / 清除 once a plan exists. Nothing is ever rendered disabled.
 * * **Travel mode belongs to the route**, so the segment sits directly under the 起点/终点 card,
 *   above the add-waypoint affordances, under its own `出行方式` label.
 * * **Every action carries a visible label** and shares the 48 dp rhythm (交换 / 设为当前位置 used to
 *   be unlabelled icon buttons).
 * * **One way out.** The header keeps no ✕: the sheet's own top chevron (the collapsed status row
 *   above this content) is the single dismiss affordance.
 * * **One type scale.** Title / label / value / supporting come from `SheetText`, not from a local
 *   invention per row.
 *
 * A network failure never blocks the flow: the geometry degrades to a straight line and the reason
 * is shown inside the result card.
 *
 * @param controller State owner; created once by `AppController`.
 * @param onDismiss Kept for the host contract; the sheet's own chevron is the visible way out.
 * @param onContentHeightChange Reports this page's measured height so the sheet can hug it.
 */
@Composable
fun RoutePlannerHost(
    controller: RoutePlannerController,
    onDismiss: () -> Unit,
    onContentHeightChange: (Int) -> Unit = {},
) {
    val searchTarget by controller.searchTarget.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("e2e.route.planner"),
        // Transparent on purpose: the host sheet already paints its own M3 sheet container. Painting
        // `surface` here would lay a second, whiter panel over the sheet.
        color = Color.Transparent,
    ) {
        if (searchTarget != null) {
            RouteSearchPage(controller = controller)
        } else {
            RoutePlanPage(
                controller = controller,
                onDismiss = onDismiss,
                onContentHeightChange = onContentHeightChange,
            )
        }
    }
}

// ---------------------------------------------------------------------------- plan page

@Composable
private fun RoutePlanPage(
    controller: RoutePlannerController,
    onDismiss: () -> Unit,
    onContentHeightChange: (Int) -> Unit,
) {
    val origin by controller.origin.collectAsStateWithLifecycle()
    val destination by controller.destination.collectAsStateWithLifecycle()
    val waypoints by controller.waypoints.collectAsStateWithLifecycle()
    val profile by controller.profile.collectAsStateWithLifecycle()
    val result by controller.result.collectAsStateWithLifecycle()
    val planning by controller.planning.collectAsStateWithLifecycle()
    val notice by controller.notice.collectAsStateWithLifecycle()
    val savedRoutes by controller.savedRoutes.collectAsStateWithLifecycle()

    val requestUsable = controller.currentRequest()?.isUsable == true
    val planned = result?.isUsable == true

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // `maxHeight` is what the sheet currently offers. Capping the page at it and reporting the
        // *measured* height back means: short content → the sheet shrinks to it, long content → the
        // sheet keeps its height and this column scrolls.
        val cap = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = cap)
                .verticalScroll(rememberScrollState())
                .onSizeChanged { onContentHeightChange(it.height) }
                .testTag("e2e.route.page")
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SheetTitle(stringResource(R.string.rp_title))
                Spacer(modifier = Modifier.height(2.dp))
                SheetSupporting(stringResource(R.string.rp_subtitle))
            }

            StopsCard(
                controller = controller,
                origin = origin,
                destination = destination,
                waypoints = waypoints,
            )

            // Travel mode is a property of *this route*, so it sits between the stops and the
            // waypoint affordances instead of floating below them as an unrelated tab bar.
            Column(modifier = Modifier.fillMaxWidth()) {
                SheetLabel(stringResource(R.string.rp_mode_label))
                Spacer(modifier = Modifier.height(6.dp))
                TravelModeRow(profile = profile, onSelect = controller::setProfile)
            }

            when {
                // Planning: progress only. Rendering the actions disabled here is exactly the
                // "disabled noise" the audit flagged.
                planning -> PlanningRow()

                planned -> {
                    ResultCard(result = result, notice = notice, planning = false)
                    PlannedActions(controller = controller)
                }

                // A destination exists but no plan yet: exactly one action is possible.
                requestUsable -> PlanAction(controller = controller)

                // Nothing to plan yet: a hint, not a control (it used to be a filled card, which
                // read as a disabled button).
                else -> SheetHint(
                    text = stringResource(R.string.rp_no_route_yet),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }

            if (savedRoutes.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SheetLabel(stringResource(R.string.rp_saved_routes))
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        savedRoutes.forEach { route ->
                            SavedRouteRow(
                                route = route,
                                onLoad = { controller.loadSaved(route) },
                                onDelete = { controller.deleteSaved(route) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanningRow() {
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

/**
 * The 起点 / 途经点 / 终点 card.
 *
 * All stop *editing* lives inside this card (add / swap / use-current), because those actions belong
 * to the object they modify — as a separate row below the card they read as a toolbar for something
 * else. Each one carries a visible label.
 */
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
            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = plannerDividerColor())
            StopEditRow(controller = controller)
        }
    }
}

/** ＋添加途经点 · 交换 · 设为当前位置 — the stop-editing actions, all labelled, all 48 dp. */
@Composable
private fun StopEditRow(controller: RoutePlannerController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AssistChip(
            onClick = { controller.openSearch(RouteFieldTarget(RouteFieldKind.WAYPOINT, -1)) },
            label = { Text(stringResource(R.string.rp_add_waypoint), maxLines = 1) },
            leadingIcon = {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            modifier = Modifier
                .height(32.dp)
                .testTag("e2e.route.addwaypoint"),
        )
        Spacer(modifier = Modifier.weight(1f))
        TextButton(
            onClick = controller::swapEnds,
            modifier = Modifier
                .heightIn(min = ActionHeight)
                .testTag("e2e.route.swap"),
        ) {
            Text(stringResource(R.string.rp_swap_short), maxLines = 1)
        }
        TextButton(
            onClick = controller::resetOrigin,
            modifier = Modifier
                .heightIn(min = ActionHeight)
                .testTag("e2e.route.use_current"),
        ) {
            Text(stringResource(R.string.rp_use_current_short), maxLines = 1)
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
        // 24 dp, the same diameter as the map's start/end badges: the card used 28 dp next to 24 dp
        // pins on the map, so the same stop looked like two different objects (audit S9).
        modifier = Modifier.size(24.dp),
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        badge()
        Column(modifier = Modifier.weight(1f)) {
            SheetLabel(label)
            SheetValue(text = value)
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                Icons.Filled.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
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
    if (result == null || !result.isUsable) return
    PlannerCard(
        focal = true,
        modifier = Modifier.testTag("e2e.route.result"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
            Spacer(modifier = Modifier.height(10.dp))
            SheetSupporting(
                stringResource(R.string.rp_source) + " · " +
                    stringResource(RoutePlannerStrings.sourceRes(result.source)),
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
                SheetSupporting(stringResource(R.string.rp_planning))
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, emphasized: Boolean = false) {
    Column {
        SheetLabel(label)
        SheetValue(
            text = value,
            color = if (emphasized) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** Planned: 开始模拟 is the one action; editing actions sit on a second line, all labelled. */
@Composable
private fun PlannedActions(controller: RoutePlannerController) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = controller::start,
            modifier = Modifier
                .fillMaxWidth()
                .height(ActionHeight)
                .testTag("e2e.route.start"),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.rp_start), maxLines = 1)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = controller::plan,
                modifier = Modifier
                    .weight(1.2f)
                    .height(ActionHeight)
                    .testTag("e2e.route.plan"),
            ) {
                Text(stringResource(R.string.rp_replan), maxLines = 1)
            }
            FilledTonalButton(
                onClick = controller::save,
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

/** Requestable but not planned: exactly one primary action. */
@Composable
private fun PlanAction(controller: RoutePlannerController) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = controller::plan,
            modifier = Modifier
                .weight(1f)
                .height(ActionHeight)
                .testTag("e2e.route.plan"),
        ) {
            Text(stringResource(R.string.rp_plan), maxLines = 1)
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
                SheetLabel(stringResource(RoutePlannerStrings.labelRes(route.profile)))
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
            SheetTitle(
                text = when (target?.kind) {
                    RouteFieldKind.ORIGIN -> stringResource(R.string.rp_origin)
                    RouteFieldKind.DESTINATION -> stringResource(R.string.rp_destination)
                    RouteFieldKind.WAYPOINT -> stringResource(R.string.rp_add_waypoint)
                    null -> stringResource(R.string.rp_search_title)
                },
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
    SheetLabel(
        text = text,
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

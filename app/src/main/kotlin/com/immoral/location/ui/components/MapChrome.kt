package com.immoral.location.ui.components
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.immoral.location.R
import com.immoral.location.core.model.SpeedLevel
import com.immoral.location.ui.theme.Dimens
import com.immoral.location.ui.theme.Elevation
import com.immoral.location.ui.theme.FabShape
import com.immoral.location.ui.theme.PillShape
import com.immoral.location.ui.theme.chromeBorder
import com.immoral.location.ui.theme.chromeContainer
import com.immoral.location.ui.theme.chromeContentColor
import com.immoral.location.ui.theme.tabular
/**
 * The floating search pill (56dp, Material You).
 *
 * A `BasicTextField` rather than an `OutlinedTextField`: the latter carries its own 56dp minimum,
 * inset, label and focus outline, which fights the pill shape and always looked like a form field
 * dropped on a map. Here the box *is* the surface and the text is just text.
 */
@Composable
fun SearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    Surface(
        modifier = modifier
            .height(Dimens.searchBarHeight)
            .testTag("e2e.searchbar"),
        shape = PillShape,
        color = chromeContainer(),
        contentColor = chromeContentColor(),
        border = BorderStroke(1.dp, chromeBorder()),
        shadowElevation = Elevation.search,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = readOnly) { onClick() }
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.map_search_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    readOnly = readOnly,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty() && !readOnly) {
                androidx.compose.material3.IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(10.dp))
            }
        }
    }
}
/**
 * A square-ish chrome button (48dp, 16dp corners) used by the right-edge stack.
 *
 * @param active when `true` the button uses `primary`/`onPrimary` — the "this tool is on" state
 *   of the joystick toggle (spec §2).
 */
@Composable
fun ChromeFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    tag: String? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(Dimens.fabSize)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        shape = FabShape,
        color = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = if (active) null else BorderStroke(1.dp, chromeBorder()),
        shadowElevation = Elevation.fab,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
/**
 * The one labelled action that shares the top bar with the search pill (audit S2).
 *
 * Before this pass the top bar had a 48 dp square of a *different* size and shape next to a 56 dp
 * pill: same visual weight, no label, and it meant 工具. A control that is not search must not look
 * like a peer of search unless it says what it does, so this control is the search pill's twin —
 * same 56 dp height, same circle shape, same container/border/elevation — plus a visible label and a
 * 16 dp gap, which is the same gap the pill keeps from the screen edge.
 */
@Composable
fun ChromeActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(Dimens.searchBarHeight)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        shape = PillShape,
        color = chromeContainer(),
        contentColor = chromeContentColor(),
        border = BorderStroke(1.dp, chromeBorder()),
        shadowElevation = Elevation.search,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

/**
 * The right-edge floating button stack, as **one** container (audit S3).
 *
 * Three separate 48 dp squares used to float over the map with 10 dp air between them, so they read
 * as three unrelated buttons that happen to be stacked (and one of them covered a POI label the map
 * had drawn underneath). One tonal plate with hairline-inset dividers groups them: the group reads
 * as a single control cluster, the dividers keep the 48 dp touch targets exactly where the overlap
 * matrix measures them, and the plate's 20 dp radius matches the cards.
 */
@Composable
fun ChromeFabGroup(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        // Width is *pinned* to one button. The dividers inside want to fill their row, so without
        // this the plate stretched to the whole map width and the three buttons floated in its
        // middle — caught in the first capture of this round, not by reasoning.
        modifier = modifier.width(Dimens.fabGroupWidth),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, chromeBorder()),
        shadowElevation = Elevation.fab,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** A divider that separates two buttons *inside* [ChromeFabGroup]. */
@Composable
fun ChromeFabGroupDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** One transport / movement-speed preset. */
data class TransportMode(
    val level: SpeedLevel,
    val icon: ImageVector,
    val labelRes: Int,
    val tag: String,
)
/** The six movement presets, in the order the peek row renders them. */
val transportModes: List<TransportMode> = listOf(
    TransportMode(SpeedLevel.WALK, Icons.Filled.DirectionsWalk, R.string.speed_walk, "e2e.speed.walk"),
    TransportMode(SpeedLevel.RUN, Icons.Filled.DirectionsRun, R.string.speed_run, "e2e.speed.run"),
    TransportMode(SpeedLevel.BIKE, Icons.Filled.DirectionsBike, R.string.speed_bike, "e2e.speed.bike"),
    TransportMode(SpeedLevel.DRIVE, Icons.Filled.DirectionsCar, R.string.speed_drive, "e2e.speed.drive"),
    TransportMode(SpeedLevel.PLANE, Icons.Filled.Flight, R.string.speed_plane, "e2e.speed.plane"),
    // `Speed`, not `Tune`: the gear glyph made the sixth cell look like a settings/图层 button
    // sitting at the end of the speed row (audit S5 — one control, one job, one meaning per glyph).
    TransportMode(SpeedLevel.CUSTOM, Icons.Filled.Speed, R.string.speed_custom, "e2e.speed.custom"),
)
/**
 * Six equal-width transport cells.
 *
 * Labels are deliberately *not* inside the cells: at 360dp the six cells are 52dp wide each, and
 * any Chinese label wider than two glyphs would ellipsise. The selected mode is spelled out in the
 * caption line above the row instead, which also carries the exact m/s — so nothing is clipped at
 * any density, and the content description still names every cell for TalkBack.
 */
@Composable
fun TransportModeRow(
    selected: SpeedLevel,
    onSelect: (SpeedLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            transportModes.forEachIndexed { index, mode ->
                // The sixth cell opens a different idea (custom speed, dialled in 工具), so it gets a
                // hairline separator and stops pretending to be the fifth speed (audit S5). Widths
                // stay within 1 px, so 14b-speed-row still measures six equal cells.
                if (index == transportModes.lastIndex) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .size(width = 1.dp, height = 20.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
                    )
                }
                val active = mode.level == selected
                // Animated, because the selection moves while the row is already on screen: a hard
                // colour swap between two neighbouring cells reads as a flicker, a 120 ms fade
                // reads as the highlight travelling.
                val container by animateColorAsState(
                    targetValue = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    },
                    animationSpec = tween(120),
                    label = "transportContainer",
                )
                val content by animateColorAsState(
                    targetValue = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(120),
                    label = "transportContent",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(Dimens.transportCell)
                        .testTag(mode.tag)
                        .background(color = container, shape = RoundedCornerShape(12.dp))
                        .clickable { onSelect(mode.level) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = mode.icon,
                        contentDescription = stringResource(mode.labelRes),
                        tint = content,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * The peek card's summary line: `● 模拟中 · 步行 1.4 m/s · ±3 m`.
 *
 * The live dot pulses only while the simulation runs: it is the one element on the peek row that
 * answers "is this thing on?" before the user reads a single word, and a static dot could not be
 * told from a decorative bullet. Text weight does the hierarchy (`titleMedium` state, `bodyMedium`
 * muted detail); the coordinates deliberately live in the meta row below, at `labelSmall`, so the
 * headline of the whole card stays a state and not a number.
 *
 * Audit S7: the row used to be "未开始 · 步行 1.4 m/s" with a chevron stranded at the far right of
 * the card — a status line with no affordance next to a lone glyph. It is now a real summary
 * (state · mode · speed · accuracy) with the expand affordance inline where the eye already is.
 */
@Composable
fun PeekStatusText(
    running: Boolean,
    modeLabel: String,
    accuracyLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "peekDot")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (running) 0.42f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "peekDotAlpha",
    )
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(if (running) pulse else 1f)
                .background(
                    color = if (running) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = CircleShape,
                ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (running) {
                stringResource(R.string.peek_running)
            } else {
                stringResource(R.string.peek_idle)
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            text = "  ·  $modeLabel",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Accuracy belongs in the summary: it is the one number that says how "real" the simulated
        // fix looks, and it used to live only inside the status sheet.
        if (accuracyLabel != null) {
            Text(
                text = "  ·  $accuracyLabel",
                style = MaterialTheme.typography.bodyMedium.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The expand affordance sits at the end of the sentence the user is already reading, not
        // alone at the far right of a 360 dp row (audit S7).
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(18.dp),
        )
    }
}

/**
 * The peek card's way into the saved-places list: `收藏 N ›`, rendered as a *link*.
 *
 * Audit S1 said the old tonal chip and the action row's ♡ looked like two controls for one job. They
 * were not (the ♡ toggles "save this point", this opens a list), but the chip *looked* like a state
 * chip, so the distinction was invisible. The rule is now stated in the design contract and encoded
 * in the idioms:
 *
 *  * a **filled tonal icon button** is a toggle (`e2e.action.favorite`, the only save control);
 *  * **primary text + trailing chevron, no container** is a link to a list (this component).
 *
 * The count stays because it is the discoverability signal ("there are 5 saved places"), and it is
 * also what keeps the surface reachable for the acceptance harness without touching the ♡.
 */
@Composable
fun PeekFavoritesLink(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .testTag("e2e.peek.favorites")
            .clickable(onClick = onClick)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Bookmark,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (count > 0) {
                stringResource(R.string.tools_tab_favorites) + " " + count
            } else {
                stringResource(R.string.tools_tab_favorites)
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** A one-line, non-blocking warning (module not activated) that fits beside the status row. */
@Composable
fun PeekHintChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
/**
 * The Material You "place card" shown after a tap / long-press on the map.
 *
 * Four actions, ordered by how likely they are: move there (primary), send to the route planner,
 * save it, or start navigation (which is the route planner with this point as the destination).
 */
@Composable
fun PlaceCard(
    title: String,
    coordinates: String,
    distance: String,
    onSetPosition: () -> Unit,
    onAddToRoute: () -> Unit,
    onSave: () -> Unit,
    onNavigate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag("e2e.placecard"),
        shape = RoundedCornerShape(Dimens.cardRadius),
        color = chromeContainer(),
        contentColor = chromeContentColor(),
        border = BorderStroke(1.dp, chromeBorder()),
        shadowElevation = Elevation.card,
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "$coordinates  ·  $distance",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                androidx.compose.material3.IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaceAction(
                    icon = Icons.Filled.MyLocation,
                    label = stringResource(R.string.place_action_move_short),
                    description = stringResource(R.string.place_action_move),
                    onClick = onSetPosition,
                    tag = "e2e.place.move",
                    primary = true,
                )
                PlaceAction(
                    icon = Icons.Filled.Directions,
                    label = stringResource(R.string.place_action_navigate_short),
                    description = stringResource(R.string.place_action_navigate),
                    onClick = onNavigate,
                    tag = "e2e.place.navigate",
                )
                PlaceAction(
                    icon = Icons.Filled.AltRoute,
                    label = stringResource(R.string.place_action_route_short),
                    description = stringResource(R.string.place_action_route),
                    onClick = onAddToRoute,
                    tag = "e2e.place.route",
                )
                PlaceAction(
                    icon = Icons.Filled.FavoriteBorder,
                    label = stringResource(R.string.place_action_save_short),
                    description = stringResource(R.string.place_action_save),
                    onClick = onSave,
                    tag = "e2e.place.save",
                )
            }
        }
    }
}
/**
 * One of the four place-card actions: icon over a two-glyph label, evenly spaced like the Google
 * Maps place sheet. Icon-only buttons would force the user to decode glyphs, and one full-width
 * filled button per action would need four rows — two characters always fit in 72 dp.
 */
@Composable
private fun PlaceAction(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    tag: String,
    primary: Boolean = false,
) {
    val tint = if (primary) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}
/**
 * The compact, non-blocking card shown for ~4 s after a single tap on the map.
 *
 * It is deliberately *not* a sheet: it floats above the bottom sheet next to the FAB column, keeps
 * the map fully interactive, and only offers the three things a user wants right after teleporting
 * somewhere by accident or on purpose: undo, save, send to the route.
 */
@Composable
fun TapChipCard(
    coordinates: String,
    onUndo: () -> Unit,
    onSave: () -> Unit,
    onRoute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag("e2e.tapchip"),
        shape = RoundedCornerShape(Dimens.cardRadius),
        color = chromeContainer(),
        contentColor = chromeContentColor(),
        border = BorderStroke(1.dp, chromeBorder()),
        shadowElevation = Elevation.card,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.MyLocation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = stringResource(R.string.tap_chip_title),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                Text(
                    text = coordinates,
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            ChipAction(
                icon = Icons.AutoMirrored.Filled.Undo,
                description = stringResource(R.string.tap_chip_undo),
                onClick = onUndo,
                tag = "e2e.tap.undo",
            )
            ChipAction(
                icon = Icons.Filled.FavoriteBorder,
                description = stringResource(R.string.place_action_save),
                onClick = onSave,
                tag = "e2e.tap.save",
            )
            ChipAction(
                icon = Icons.Filled.AltRoute,
                description = stringResource(R.string.place_action_route),
                onClick = onRoute,
                tag = "e2e.tap.route",
            )
        }
    }
}
@Composable
private fun ChipAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tag: String,
) {
    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = Modifier.size(38.dp).testTag(tag),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

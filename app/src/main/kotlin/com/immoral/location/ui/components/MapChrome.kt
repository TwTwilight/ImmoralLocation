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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
    TransportMode(SpeedLevel.CUSTOM, Icons.Filled.Tune, R.string.speed_custom, "e2e.speed.custom"),
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
            transportModes.forEach { mode ->
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
 * One line of `模拟中 · 步行 1.4 m/s`.
 *
 * The live dot pulses only while the simulation runs: it is the one element on the peek row that
 * answers "is this thing on?" before the user reads a single word, and a static dot could not be
 * told from a decorative bullet. Text weight does the hierarchy (`titleMedium` state, `bodyMedium`
 * muted detail); the coordinates deliberately live in the meta row below, at `labelSmall`, so the
 * headline of the whole card stays a state and not a number.
 */
@Composable
fun PeekStatusText(
    running: Boolean,
    modeLabel: String,
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
    }
}

/**
 * The one-tap way into the saved-places list, docked on the peek card's meta row.
 *
 * Without it the list existed but had no entrance: it sat three interactions deep inside 工具 and
 * the only control that mentioned 收藏 was the heart, which *saves* (or, when a place is already
 * saved here, silently deletes). A tonal chip with the bookmark count makes the surface reachable
 * from the default state and visible even when the list is empty (that is what turns "0 saved
 * places" from a non-feature into an invitation).
 */
@Composable
fun PeekFavoritesEntry(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        modifier = modifier
            .height(32.dp)
            .testTag("e2e.peek.favorites"),
        shape = RoundedCornerShape(10.dp),
        border = null,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            trailingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Bookmark,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        },
        label = {
            Text(
                text = stringResource(R.string.tools_tab_favorites),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        },
        trailingIcon = if (count > 0) {
            {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        } else {
            null
        },
    )
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

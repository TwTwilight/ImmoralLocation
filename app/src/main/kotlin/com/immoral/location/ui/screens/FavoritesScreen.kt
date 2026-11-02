package com.immoral.location.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.immoral.location.R
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.FavoritePlace
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.ui.components.FavoriteText
import com.immoral.location.ui.components.Fmt
import com.immoral.location.ui.components.SheetSupporting
import com.immoral.location.ui.components.SheetTitle
import com.immoral.location.ui.components.SheetValue
import com.immoral.location.ui.theme.tabular

/**
 * 收藏: saved places with one-tap return (点击即回到该位置), rename and delete.
 *
 * Layout follows the "list inside a sheet" pattern rather than the old stack of nested cards:
 *
 *  * one header block (title, count, hint) — the count is the first number on the surface, so a
 *    user (and the acceptance harness) reads "how many" before scrolling anything;
 *  * one tonal row per place, with the name as the row title and the coordinates at `labelSmall`
 *    + `tnum` under it (spec §2: coordinates are never the headline);
 *  * one primary action per state, placed after the content, so an empty list and a one-row list
 *    both end where the content ends instead of floating under a screen-high blank area (the sheet
 *    wraps its content up to 92 % and only then scrolls — a 4-row list used to open at full height
 *    with ~1000 px of nothing below it).
 */
@Composable
fun FavoritesScreen(
    favorites: List<FavoritePlace>,
    currentPosition: GeoPoint,
    onSelect: (FavoritePlace) -> Unit,
    onRename: (FavoritePlace, String) -> Unit,
    onDelete: (FavoritePlace) -> Unit,
    onAddCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf<FavoritePlace?>(null) }
    var deleting by remember { mutableStateOf<FavoritePlace?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag("e2e.favorites"),
    ) {
        // `maxHeight` is the sheet's own available height, so 92 % of it is the cap that keeps the
        // map visible behind the sheet while still leaving room for a long list.
        val cap = maxHeight * 0.92f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = cap)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp)) {
                SheetTitle(stringResource(R.string.favorites_sheet_title))
                Spacer(modifier = Modifier.height(2.dp))
                SheetValue(
                    text = stringResource(R.string.favorites_count, favorites.size),
                    color = MaterialTheme.colorScheme.primary,
                )
                // The long "how it works" line is only shown when there is something to explain: in
                // the empty state the copy belongs to the empty state's own body, and printing it
                // twice (header + body) is exactly the kind of duplication that reads as unfinished.
                if (favorites.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    SheetSupporting(stringResource(R.string.favorites_hint))
                }
            }

            if (favorites.isEmpty()) {
                EmptyState()
            } else {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    favorites.forEach { place ->
                        FavoriteRow(
                            place = place,
                            currentPosition = currentPosition,
                            onSelect = { onSelect(place) },
                            onRename = { renaming = place },
                            onDelete = { deleting = place },
                        )
                    }
                }
            }

            Button(
                onClick = onAddCurrent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(52.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.favorites_add_current))
            }
        }
    }

    renaming?.let { place ->
        var name by remember(place.id) { mutableStateOf(FavoriteText.displayName(place.name)) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.favorites_rename_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.favorites_name_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(place, name)
                        renaming = null
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    deleting?.let { place ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.favorites_delete_title)) },
            text = { Text(stringResource(R.string.favorites_delete_body, FavoriteText.displayName(place.name))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(place)
                        deleting = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** One saved place: name, tabular coordinates, distance, and the two management actions. */
@Composable
private fun FavoriteRow(
    place: FavoritePlace,
    currentPosition: GeoPoint,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val distance = GeoMath.distanceMeters(currentPosition, place.point)
    Surface(
        // Modifier.clickable on the surface itself, not Surface(onClick=): the tap must land
        // anywhere on the row (name, coordinate line, or the empty strip to its right). Taps at the
        // coordinate text's own centre produced no callback at all, which is the 11b defect.
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Neutral tile + `tertiary` glyph: "saved place" stays the product's tertiary accent
            // (it is the colour of the pin on the map) without a saturated pink blob per row.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                SheetValue(text = FavoriteText.displayName(place.name))
                Text(
                    text = "${Fmt.coord(place.point.lat)}, ${Fmt.coord(place.point.lon)}",
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.favorites_distance, Fmt.distance(distance)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onRename, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.action_rename),
                    // Rename is not destructive: it stays neutral so only the trash reads as
                    // a delete (the earlier patch tinted both icons by accident).
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    // Destructive controls look destructive (§10.2): neutral here read as
                    // just another row action.
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** The empty state is a designed state, not a missing list (spec: empty/edge states). */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 32.dp, top = 40.dp, bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.BookmarkBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.favorites_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.favorites_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp),
        )    }
}

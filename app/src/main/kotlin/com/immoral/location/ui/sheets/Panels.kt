package com.immoral.location.ui.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.immoral.location.R
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.FavoritePlace
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.model.SpeedLevel
import com.immoral.location.map.TilePreset
import com.immoral.location.net.PlaceResult
import com.immoral.location.net.SearchProvider
import com.immoral.location.ui.components.CompassDial
import com.immoral.location.ui.components.FavoriteText
import com.immoral.location.ui.components.Fmt
import com.immoral.location.ui.components.LabeledSlider
import com.immoral.location.ui.components.PeekFavoritesLink
import com.immoral.location.ui.components.PeekHintChip
import com.immoral.location.ui.components.PeekStatusText
import com.immoral.location.ui.components.SheetLabel
import com.immoral.location.ui.components.SheetSupporting
import com.immoral.location.ui.components.SheetTitle
import com.immoral.location.ui.components.SheetValue
import com.immoral.location.ui.components.TransportModeRow
import com.immoral.location.ui.theme.Dimens
import com.immoral.location.ui.theme.tabular

/** UI state of the place search sheet. */
data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<PlaceResult> = emptyList(),
    val error: String? = null,
    val provider: SearchProvider? = null,
)

/**
 * The peek row: the whole app in one card.
 *
 * Order is deliberately Google-Maps-like — *what is happening* (status), *how fast* (transport
 * modes), *what can I do about it* (primary actions). Everything the old build scattered across
 * chips, pills and two FABs lives here now, which is why the map can stay full-bleed.
 */
@Composable
fun PeekPanel(
    running: Boolean,
    speed: SpeedLevel,
    speedMps: Double,
    coordinates: String,
    favouriteHere: Boolean,
    favouriteCount: Int,
    onOpenStatus: () -> Unit,
    onToggleRun: () -> Unit,
    onOpenRoute: () -> Unit,
    onToggleFavourite: () -> Unit,
    onOpenFavorites: () -> Unit,
    onSpeedSelect: (SpeedLevel) -> Unit,
    dragHandle: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    onHintClick: (() -> Unit)? = null,
    accuracyLabel: String? = null,
) {
    val modeLabel = buildString {
        append(stringResource(speedLabelRes(speed)))
        append(' ')
        append(Fmt.trimZero(speedMps))
        append(" m/s")
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("e2e.peek")
            .padding(bottom = 4.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            dragHandle()
        }
        Column(modifier = Modifier.padding(horizontal = Dimens.sheetPadding)) {
            // What is happening. One tap opens the precise readout, which is why the whole row is
            // the target; the chevron lives inside the summary line (audit S7) instead of floating
            // alone at the far right. A confirmed-missing module adds its chip at the row's end.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenStatus() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PeekStatusText(
                    running = running,
                    modeLabel = modeLabel,
                    accuracyLabel = accuracyLabel,
                    modifier = Modifier.weight(1f),
                )
            }
            // Where we are, and the way to everywhere we saved. Coordinates are `labelSmall` +
            // `tnum` (spec §2): they must be readable and stable, never the headline. The 收藏 link
            // is navigation only — the ♡ in the action row is the one save/unsave control (S1).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = coordinates,
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    // A stable handle for the *live* position. Without it a check can only scrape
                    // every `dd.dddd, ddd.dddd` node in the tree — and while the planner is open
                    // those are the route's 起点/终点, static by design, which made a real movement
                    // sample impossible to distinguish from two frozen route fields.
                    modifier = Modifier
                        .weight(1f)
                        .testTag("e2e.peek.position"),
                )
                if (hint != null) {
                    PeekHintChip(text = hint, onClick = onHintClick)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                PeekFavoritesLink(count = favouriteCount, onClick = onOpenFavorites)
            }

            Spacer(modifier = Modifier.height(12.dp))
            TransportModeRow(selected = speed, onSelect = onSpeedSelect)
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // All three controls are 48dp tall: mixing the 40dp Button default with a 48dp
                // icon button is the kind of 8dp misalignment a reviewer reads as "unfinished".
                Button(
                    onClick = onToggleRun,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("e2e.action.primary"),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(
                        imageVector = if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (running) R.string.action_pause_short else R.string.action_start_short,
                        ),
                        maxLines = 1,
                    )
                }
                FilledTonalButton(
                    onClick = onOpenRoute,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("e2e.action.route"),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(Icons.Filled.AltRoute, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_route_short), maxLines = 1)
                }
                // The on-state stays inside the *action* system (`primaryContainer`, the same family
                // as the filled primary, one step quieter) instead of `tertiaryContainer`. Tertiary is
                // this product's saved-place accent and belongs on map pins — as a button fill it was
                // a third, pink weight competing with 开始 (§10.2: one emphasised action per surface).
                val favouriteColors = if (favouriteHere) {
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    IconButtonDefaults.filledTonalIconButtonColors()
                }
                FilledTonalIconButton(
                    onClick = onToggleFavourite,
                    colors = favouriteColors,
                    modifier = Modifier
                        .size(48.dp)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            shape = CircleShape,
                        )
                        .testTag("e2e.action.favorite"),
                ) {
                    Icon(
                        imageVector = if (favouriteHere) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = stringResource(R.string.place_action_save),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

/** The pill-shaped drag handle of the sheet. */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(vertical = 10.dp)
            .size(width = Dimens.handleWidth, height = Dimens.handleHeight)
            .background(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                shape = CircleShape,
            ),
    )
}

/**
 * Half-sheet tools panel: the simulation switches, the saved places and the shortcuts that used to
 * occupy a five-tab navigation bar.
 */
@Composable
fun ToolsPanel(
    config: SimulationConfig,
    position: GeoPoint,
    favorites: List<FavoritePlace>,
    onUpdate: ((SimulationConfig) -> SimulationConfig) -> Unit,
    onPickFavorite: (FavoritePlace) -> Unit,
    onAddCurrentFavorite: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLayers: () -> Unit,
    onOpenHeading: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    overlayRunning: Boolean,
    overlayPermissionGranted: Boolean = true,
    modifier: Modifier = Modifier,
    onManageFavorites: () -> Unit = {},
    onImportGpx: () -> Unit = {},
    onExportGpx: () -> Unit = {},
    recentPositions: List<GeoPoint> = emptyList(),
    onPickRecent: (GeoPoint) -> Unit = {},
    onPickApps: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("e2e.sheet.tools")
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.sheetPadding)
            .padding(bottom = 20.dp),
    ) {
        SectionLabel(stringResource(R.string.tools_section_simulation))
        ToolSwitchRow(
            icon = Icons.Filled.CellTower,
            title = stringResource(R.string.settings_cell),
            checked = config.cellSimulation,
            tag = "e2e.tool.cell",
            onChange = { on -> onUpdate { it.copy(cellSimulation = on) } },
        )
        ToolSwitchRow(
            icon = Icons.Filled.Wifi,
            title = stringResource(R.string.settings_wifi),
            checked = config.wifiSimulation,
            tag = "e2e.tool.wifi",
            onChange = { on -> onUpdate { it.copy(wifiSimulation = on) } },
        )
        ToolSwitchRow(
            icon = Icons.Filled.SatelliteAlt,
            title = stringResource(R.string.settings_gps_signal),
            checked = config.gpsSignalSimulation,
            tag = "e2e.tool.gnss",
            onChange = { on -> onUpdate { it.copy(gpsSignalSimulation = on) } },
        )
        ToolSwitchRow(
            icon = Icons.Filled.DirectionsWalk,
            title = stringResource(R.string.settings_step),
            checked = config.stepSimulation,
            tag = "e2e.tool.steps",
            onChange = { on -> onUpdate { it.copy(stepSimulation = on) } },
        )
        ToolSwitchRow(
            icon = Icons.Filled.Apps,
            title = stringResource(R.string.settings_independent),
            checked = config.independentMode,
            tag = "e2e.tool.independent",
            subtitle = if (config.independentPackages.isEmpty()) {
                null
            } else {
                stringResource(R.string.settings_apps_selected, config.independentPackages.size)
            },
            onChange = { on -> onUpdate { it.copy(independentMode = on) } },
        )

        Spacer(modifier = Modifier.height(6.dp))
        SectionLabel(stringResource(R.string.settings_interval))
        IntervalChips(
            current = config.updateIntervalMs,
            tag = "e2e.tool.interval",
            onChange = { ms -> onUpdate { it.copy(updateIntervalMs = ms) } },
        )

        Spacer(modifier = Modifier.height(10.dp))
        // 自定义速度: selecting CUSTOM in the peek row is useless without a way to dial it in.
        LabeledSlider(
            title = stringResource(R.string.speed_custom),
            valueText = Fmt.trimZero(config.customSpeedMps) + " m/s",
            value = config.customSpeedMps.toFloat().coerceIn(0.5f, 30f),
            valueRange = 0.5f..30f,
            onValueChange = { value ->
                onUpdate {
                    it.copy(speed = SpeedLevel.CUSTOM, customSpeedMps = value.toDouble())
                }
            },
            modifier = Modifier.testTag("e2e.tool.customspeed"),
        )

        if (config.independentMode) {
            ShortcutRow(
                icon = Icons.Filled.Apps,
                title = stringResource(R.string.settings_pick_apps),
                subtitle = if (config.independentPackages.isEmpty()) {
                    stringResource(R.string.settings_apps_none)
                } else {
                    stringResource(R.string.settings_apps_selected, config.independentPackages.size)
                },
                onClick = onPickApps,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(10.dp))

        SectionLabel(stringResource(R.string.tools_section_shortcuts))
        ShortcutRow(
            icon = Icons.Filled.Layers,
            title = stringResource(R.string.map_layer),
            subtitle = stringResource(R.string.layers_subtitle),
            onClick = onOpenLayers,
        )
        ShortcutRow(
            icon = Icons.Filled.Explore,
            title = stringResource(R.string.sheet_heading_title),
            subtitle = if (config.followHeading) {
                stringResource(R.string.joystick_follow_heading_on, config.fixedBearingDeg.toInt())
            } else {
                stringResource(R.string.joystick_follow_heading_off)
            },
            onClick = onOpenHeading,
        )
        ToolSwitchRow(
            icon = Icons.Filled.Smartphone,
            title = stringResource(R.string.joystick_overlay_title),
            checked = overlayRunning,
            tag = "e2e.tool.overlay",
            onChange = onToggleOverlay,
            // Three states, not two: "off" and "off because the permission was never granted" look
            // identical to the user otherwise, and the row would silently bounce them to the system
            // settings screen without having said why.
            subtitle = when {
                !overlayPermissionGranted -> stringResource(R.string.overlay_permission_needed)
                overlayRunning -> stringResource(R.string.overlay_running)
                else -> stringResource(R.string.overlay_idle)
            },
            warn = !overlayPermissionGranted,
        )
        ShortcutRow(
            icon = Icons.Filled.Bookmark,
            title = stringResource(R.string.tools_all_settings),
            subtitle = stringResource(R.string.tools_all_settings_desc),
            onClick = onOpenSettings,
        )
        ShortcutRow(
            icon = Icons.Filled.FileUpload,
            title = stringResource(R.string.route_import_gpx),
            subtitle = stringResource(R.string.route_import_hint),
            onClick = onImportGpx,
        )
        ShortcutRow(
            icon = Icons.Filled.FileDownload,
            title = stringResource(R.string.route_export_gpx),
            subtitle = stringResource(R.string.route_export_hint),
            onClick = onExportGpx,
        )

        Spacer(modifier = Modifier.height(14.dp))
        SectionLabel(stringResource(R.string.tools_tab_favorites))
        // Count and the way in share one line: the section used to say `收藏地点 · 共 N 个收藏` as a
        // single label that looked like a heading and was not tappable, which is why the list it
        // announced stayed unreachable. 管理 is also offered when the list is empty, so the empty
        // state (and its 收藏当前位置 button) is one tap away instead of hidden.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.favorites_count, favorites.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onManageFavorites) {
                Text(stringResource(R.string.favorites_manage), maxLines = 1)
            }
        }
        if (favorites.isEmpty()) {
            Text(
                text = stringResource(R.string.favorites_empty_short),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                favorites.forEach { place ->
                    AssistChip(
                        onClick = { onPickFavorite(place) },
                        label = {
                            Column {
                                Text(
                                    text = FavoriteText.displayName(place.name),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = Fmt.distance(GeoMath.distanceMeters(position, place.point)),
                                    style = MaterialTheme.typography.labelSmall.tabular(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Favorite,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onAddCurrentFavorite,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.favorites_add_current), maxLines = 1)
            }
        }

        if (recentPositions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            SectionLabel(stringResource(R.string.tools_recent))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                recentPositions.forEach { point ->
                    AssistChip(
                        onClick = { onPickRecent(point) },
                        label = {
                            Text(
                                text = coordinateLine(point),
                                style = MaterialTheme.typography.labelMedium.tabular(),
                            )
                        },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    // One type scale for every sheet (audit P7): a section label is `labelMedium`/onSurfaceVariant
    // from SheetText, never a locally invented `labelLarge`/primary headline.
    SheetLabel(
        text = text,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun ToolSwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
    subtitle: String? = null,
    warn: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onChange(!checked) }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (warn) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ShortcutRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    warn: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (warn) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 更新频率 as four presets — a slider inside a half sheet is unusable with a 20 Hz tick. */
@Composable
private fun IntervalChips(
    current: Long,
    tag: String,
    onChange: (Long) -> Unit,
) {
    val presets = listOf(200L, 500L, 1_000L, 2_000L, 5_000L)
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        presets.forEach { ms ->
            FilterChip(
                selected = current == ms,
                onClick = { onChange(ms) },
                label = { Text("${ms} ms", style = MaterialTheme.typography.labelMedium.tabular()) },
            )
        }
    }
}

/** 图层 sheet: the tile presets plus the 天地图 key. */
@Composable
fun LayerSheetContent(
    preset: TilePreset,
    tiandituKey: String,
    hasKey: Boolean,
    onPresetChange: (TilePreset) -> Unit,
    onKeyChange: (String) -> Unit,
) {
    var keyDraft by remember(tiandituKey) { mutableStateOf(tiandituKey) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.sheetPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        SheetTitle(stringResource(R.string.map_layer))
        Spacer(modifier = Modifier.height(2.dp))
        SheetSupporting(stringResource(R.string.layers_desc))
        Spacer(modifier = Modifier.height(8.dp))
        TilePreset.entries.forEach { option ->
            val enabled = option != TilePreset.TIANDITU || hasKey
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = preset == option,
                    enabled = enabled,
                    onClick = { onPresetChange(option) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    // A disabled preset's label must read dimmer than an enabled one; black-on-
                    // black made 天地图 look selectable and only the grey note said otherwise.
                    SheetValue(
                        text = stringResource(option.labelRes),
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                    )
                    if (!enabled) {
                        // Information, not an error: the preset is fine, it just needs a key. Red
                        // here read as "this option is broken" (audit: inconsistent role).
                        SheetLabel(stringResource(R.string.tile_preset_needs_key))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(8.dp))
        SheetLabel(stringResource(R.string.settings_tianditu_key))
        Spacer(modifier = Modifier.height(2.dp))
        SheetSupporting(stringResource(R.string.settings_tianditu_key_desc))
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            // 48 dp control rhythm, same as every other action in the app (audit S6).
            androidx.compose.material3.FilledTonalButton(
                onClick = { onKeyChange(keyDraft) },
                modifier = Modifier.height(48.dp),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
        Text(
            text = stringResource(R.string.tile_preset_gcj_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 28.dp),
        )
    }
}

/** 方向设置: follow-heading switch + draggable compass for a fixed bearing. */
@Composable
fun HeadingSheetContent(
    followHeading: Boolean,
    headingDeg: Double?,
    fixedBearingDeg: Double,
    onFollowChange: (Boolean) -> Unit,
    onBearingChange: (Double) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.sheetPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        SheetTitle(stringResource(R.string.sheet_heading_title))
        Spacer(modifier = Modifier.height(4.dp))
        SheetSupporting(stringResource(R.string.sheet_heading_desc))
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.joystick_follow_heading),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.joystick_follow_heading_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = followHeading, onCheckedChange = onFollowChange)
        }
        CompassDial(
            headingDeg = headingDeg,
            bearingDeg = fixedBearingDeg,
            following = followHeading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            onBearingChange = if (followHeading) null else onBearingChange,
        )
        Text(
            text = if (followHeading) {
                stringResource(R.string.compass_heading, (headingDeg ?: 0.0).toInt())
            } else {
                stringResource(R.string.joystick_fixed_bearing_value, fixedBearingDeg.toInt())
            },
            style = MaterialTheme.typography.labelMedium.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

/** Search results (also renders the empty / error states). */
@Composable
fun SearchSheetContent(
    state: SearchUiState,
    onPick: (PlaceResult) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .padding(horizontal = Dimens.sheetPadding),
    ) {
        Text(
            text = stringResource(R.string.search_results),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        when {
            state.loading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodyMedium)
                }
            }

            state.error != null -> {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                }
            }

            state.results.isEmpty() -> {
                Text(
                    text = stringResource(R.string.search_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }

            else -> {
                Text(
                    text = when (state.provider) {
                        SearchProvider.AMAP -> stringResource(R.string.search_via_amap)
                        SearchProvider.TIANDITU -> stringResource(R.string.search_via_tianditu)
                        else -> stringResource(R.string.search_via_nominatim)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(state.results.size) { index ->
                        val result = state.results[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(result) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        shape = RoundedCornerShape(12.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Explore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = result.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (result.address.isNotBlank()) {
                                    Text(
                                        text = result.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

/** 悬浮摇杆 offer, shown right after the simulation starts. */
@Composable
fun OverlayPromptContent(onEnable: () -> Unit, onDismiss: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = Dimens.sheetPadding, vertical = 8.dp)) {
        SheetTitle(stringResource(R.string.overlay_prompt_title))
        Spacer(modifier = Modifier.height(8.dp))
        SheetSupporting(stringResource(R.string.overlay_prompt_body))
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 48 dp primary + secondary rhythm, same hierarchy as every other surface (audit S6).
            Button(
                onClick = onEnable,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) {
                Text(stringResource(R.string.overlay_prompt_enable))
            }
            androidx.compose.material3.FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) {
                Text(stringResource(R.string.overlay_prompt_later))
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** The `SpeedLevel` → string-resource mapping shared by the peek row and the tools panel. */
fun speedLabelRes(level: SpeedLevel): Int = when (level) {
    SpeedLevel.WALK -> R.string.speed_walk
    SpeedLevel.RUN -> R.string.speed_run
    SpeedLevel.BIKE -> R.string.speed_bike
    SpeedLevel.DRIVE -> R.string.speed_drive
    SpeedLevel.PLANE -> R.string.speed_plane
    SpeedLevel.CUSTOM -> R.string.speed_custom
}

/** Coordinate-line helper used by the peek row and the place card. */
fun coordinateLine(point: GeoPoint): String =
    "${Fmt.coord4(point.lat)}, ${Fmt.coord4(point.lon)}"
/**
 * One-line version of [PeekPanel], shown while the sheet is at the FULL detent.
 *
 * When the route planner owns the sheet, the peek row would be a second task in the same surface
 * (and would eat ~200 dp of the planner's height). This header keeps the live simulation status
 * visible and offers one obvious way back to the peek state.
 */
@Composable
fun CollapsedPeekHeader(
    running: Boolean,
    modeLabel: String,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Dimens.sheetPadding, end = 8.dp)
            .heightIn(min = 52.dp)
            .testTag("e2e.peek.collapsed"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (running) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = CircleShape,
                ),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(if (running) R.string.peek_running else R.string.peek_idle),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        // Same three-tier yield as the peek card: full label, then its head, then nothing. A planner
        // header reading "未开始 · 步…" is a fragment, not information (R7 third step, 720 width).
        BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
            val shown = when {
                maxWidth >= 96.dp -> modeLabel
                maxWidth >= 40.dp -> modeLabel.substringBefore(' ')
                else -> ""
            }
            if (shown.isNotEmpty()) {
                Text(
                    text = "  ·  $shown",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onCollapse, modifier = Modifier.size(48.dp).testTag("e2e.peek.collapse")) {
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = stringResource(R.string.action_collapse_sheet),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
/**
 * Live suggestion list under the search pill — the Google-Maps behaviour the shell was missing.
 *
 * It is an inline card, not a modal: the pill keeps focus, the keyboard stays up and the map is
 * still visible, so the user can type and pick without a second surface fighting for the screen.
 */
@Composable
fun SearchSuggestionCard(
    state: SearchUiState,
    onPick: (PlaceResult) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    parsedCoordinate: GeoPoint? = null,
    onPickParsed: () -> Unit = {},
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp)
            .testTag("e2e.search.suggestions"),
        shape = RoundedCornerShape(Dimens.cardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
        // The alpha variant is not a `colorScheme` role, so Material 3 cannot derive an `on*`
        // colour for it; without this the result names below render black on the dark card.
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            // A parsed coordinate is an answer, not a query: show it as the first (and only)
            // suggestion so the user sees that the input was understood and gets a tap target
            // instead of having to press 搜索 (audit R2: feedback for a locally-resolvable query).
            if (parsedCoordinate != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPickParsed)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(32.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(10.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MyLocation,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.search_jump_title,
                                Fmt.coord(parsedCoordinate.lat) + ", " + Fmt.coord(parsedCoordinate.lon),
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.search_jump_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            when {
                state.loading -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.searching),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.error != null -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                }

                state.results.isEmpty() && parsedCoordinate == null -> Text(
                    text = stringResource(R.string.search_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )

                else -> Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    state.results.take(6).forEach { result ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(result) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            // Top-aligned: a centred 32 dp tile against a two-line name/address block
                            // sits between the lines, so the icon and the headline never share an
                            // optical baseline (audit R5 on the search results).
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 1.dp)
                                    .size(32.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        shape = RoundedCornerShape(10.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = result.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (result.address.isNotBlank()) {
                                    Text(
                                        text = result.address,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


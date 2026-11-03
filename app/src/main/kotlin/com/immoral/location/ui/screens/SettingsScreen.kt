package com.immoral.location.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.immoral.location.R
import com.immoral.location.bridge.ModuleLink
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.model.SpeedLevel
import com.immoral.location.data.SettingsStore
import com.immoral.location.map.TilePreset
import com.immoral.location.ui.components.Fmt
import com.immoral.location.ui.components.GroupRow
import com.immoral.location.ui.components.GroupTile
import com.immoral.location.ui.components.LabeledSlider
import com.immoral.location.ui.components.NavRow
import com.immoral.location.ui.components.RowIcon
import com.immoral.location.ui.components.SectionHeader
import com.immoral.location.ui.components.SwitchGroupRow
import com.immoral.location.ui.components.TextInputDialog
import com.immoral.location.ui.components.TransportModeRow
import com.immoral.location.ui.components.groupItemShape
import com.immoral.location.ui.sheets.speedLabelRes
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Callbacks of the 设置 page. */
class SettingsActions(
    val onUpdate: ((SimulationConfig) -> SimulationConfig) -> Unit,
    val onTileUrlChange: (String) -> Unit,
    val onReset: () -> Unit,
    val onOpenLayers: () -> Unit,
    val onOpenHeading: () -> Unit,
    val onToggleOverlay: (Boolean) -> Unit,
    val onImportGpx: () -> Unit,
    val onExportGpx: () -> Unit,
    val onOpenStatus: () -> Unit,
    val onModuleHelp: () -> Unit,
    val onStartPageChange: (String) -> Unit,
)

/**
 * 设置 — every switch of the app in one scrollable page.
 *
 * Before the tab bar these were spread over three surfaces (the 工具 half-sheet, a 全部设置 modal and
 * the 图层 sheet) with some switches in two of them. The page is grouped by what the user is
 * thinking about, not by implementation: 状态 → 移动 → 模拟项目 → 定位参数 → 独立模拟 → 路线 → 地图
 * → 通用 → 关于.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    config: SimulationConfig,
    link: ModuleLink,
    moduleVersion: String,
    tileUrl: String,
    tilePreset: TilePreset,
    versionName: String,
    overlayRunning: Boolean,
    overlayPermission: Boolean,
    startPage: String,
    snackbarHostState: SnackbarHostState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var showAppPicker by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var editTile by remember { mutableStateOf(false) }
    val onUpdate = actions.onUpdate

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .testTag("e2e.settings"),
        topBar = {
            MediumTopAppBar(
                title = { Text(stringResource(R.string.tools_tab_settings)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            // ---------------------------------------------------------------- 状态
            ModuleCard(link = link, moduleVersion = moduleVersion, onOpenStatus = actions.onOpenStatus, onHelp = actions.onModuleHelp)

            // ---------------------------------------------------------------- 移动
            SectionHeader(stringResource(R.string.shell_section_movement), modifier = Modifier.padding(top = 16.dp))
            GroupTile(shape = groupItemShape(0, 3)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.shell_speed),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(speedLabelRes(config.speed)) + " · " +
                            Fmt.trimZero(config.effectiveSpeedMps()) + " m/s",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                TransportModeRow(
                    selected = config.speed,
                    onSelect = { level -> onUpdate { it.copy(speed = level) } },
                )
                if (config.speed == SpeedLevel.CUSTOM) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LabeledSlider(
                        title = stringResource(R.string.speed_custom),
                        valueText = { Fmt.trimZero(it.toDouble()) + " m/s" },
                        value = config.customSpeedMps.toFloat().coerceIn(0.5f, 60f),
                        valueRange = 0.5f..60f,
                        onValueChange = { value ->
                            val snapped = (value * 10f).roundToInt() / 10.0
                            onUpdate { it.copy(speed = SpeedLevel.CUSTOM, customSpeedMps = snapped) }
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            NavRow(
                title = stringResource(R.string.sheet_heading_title),
                subtitle = if (config.followHeading) {
                    stringResource(R.string.joystick_follow_heading_on)
                } else {
                    stringResource(R.string.shell_heading_fixed, config.fixedBearingDeg.roundToInt())
                },
                icon = Icons.Filled.Explore,
                shape = groupItemShape(1, 3),
                onClick = actions.onOpenHeading,
            )
            Spacer(modifier = Modifier.height(2.dp))
            SwitchGroupRow(
                title = stringResource(R.string.joystick_overlay_title),
                subtitle = when {
                    !overlayPermission -> stringResource(R.string.shell_overlay_permission)
                    overlayRunning -> stringResource(R.string.overlay_running)
                    else -> stringResource(R.string.overlay_idle)
                },
                subtitleColor = if (overlayPermission) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                checked = overlayRunning,
                onCheckedChange = actions.onToggleOverlay,
                icon = Icons.Filled.Smartphone,
                shape = groupItemShape(2, 3),
                modifier = Modifier.testTag("e2e.tool.overlay"),
            )

            // ---------------------------------------------------------------- 模拟项目
            SectionHeader(stringResource(R.string.tools_section_simulation), modifier = Modifier.padding(top = 16.dp))
            val simRows = if (config.stepSimulation) 5 else 4
            SwitchGroupRow(
                title = stringResource(R.string.settings_gps_signal),
                subtitle = stringResource(R.string.shell_gps_signal_desc),
                checked = config.gpsSignalSimulation,
                onCheckedChange = { on -> onUpdate { it.copy(gpsSignalSimulation = on) } },
                icon = Icons.Filled.SatelliteAlt,
                shape = groupItemShape(0, simRows),
                modifier = Modifier.testTag("e2e.tool.gnss"),
            )
            Spacer(modifier = Modifier.height(2.dp))
            SwitchGroupRow(
                title = stringResource(R.string.settings_cell),
                subtitle = stringResource(R.string.shell_cell_desc),
                checked = config.cellSimulation,
                onCheckedChange = { on -> onUpdate { it.copy(cellSimulation = on) } },
                icon = Icons.Filled.CellTower,
                shape = groupItemShape(1, simRows),
                modifier = Modifier.testTag("e2e.tool.cell"),
            )
            Spacer(modifier = Modifier.height(2.dp))
            SwitchGroupRow(
                title = stringResource(R.string.settings_wifi),
                subtitle = stringResource(R.string.shell_wifi_desc),
                checked = config.wifiSimulation,
                onCheckedChange = { on -> onUpdate { it.copy(wifiSimulation = on) } },
                icon = Icons.Filled.Wifi,
                shape = groupItemShape(2, simRows),
                modifier = Modifier.testTag("e2e.tool.wifi"),
            )
            Spacer(modifier = Modifier.height(2.dp))
            SwitchGroupRow(
                title = stringResource(R.string.settings_step),
                subtitle = stringResource(R.string.shell_step_desc),
                checked = config.stepSimulation,
                onCheckedChange = { on -> onUpdate { it.copy(stepSimulation = on) } },
                icon = Icons.Filled.DirectionsWalk,
                shape = groupItemShape(3, simRows),
                modifier = Modifier.testTag("e2e.tool.steps"),
            )
            if (config.stepSimulation) {
                Spacer(modifier = Modifier.height(2.dp))
                GroupTile(shape = groupItemShape(4, simRows)) {
                    LabeledSlider(
                        title = stringResource(R.string.settings_step_cadence),
                        valueText = { stringResource(R.string.settings_step_cadence_value, it.roundToInt()) },
                        value = config.stepCadenceBpm.toFloat(),
                        valueRange = SimulationConfig.MIN_CADENCE_BPM.toFloat()..SimulationConfig.MAX_CADENCE_BPM.toFloat(),
                        onValueChange = { raw -> onUpdate { it.copy(stepCadenceBpm = raw.roundToInt()) } },
                    )
                }
            }

            // ---------------------------------------------------------------- 定位参数
            SectionHeader(stringResource(R.string.shell_section_fix), modifier = Modifier.padding(top = 16.dp))
            GroupTile(shape = groupItemShape(0, 3)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_interval),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.settings_interval_value, config.updateIntervalMs.toInt()),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                IntervalChips(
                    current = config.updateIntervalMs,
                    onChange = { ms -> onUpdate { it.copy(updateIntervalMs = ms) } },
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            GroupTile(shape = groupItemShape(1, 3)) {
                LabeledSlider(
                    title = stringResource(R.string.settings_gps_jitter),
                    valueText = { stringResource(R.string.settings_jitter_value, it.toDouble()) },
                    value = config.gpsJitterMeters.toFloat(),
                    valueRange = 0f..30f,
                    onValueChange = { v -> onUpdate { it.copy(gpsJitterMeters = (v * 2f).roundToInt() / 2.0) } },
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            GroupTile(shape = groupItemShape(2, 3)) {
                LabeledSlider(
                    title = stringResource(R.string.settings_alt_jitter),
                    valueText = { stringResource(R.string.settings_jitter_value, it.toDouble()) },
                    value = config.altitudeJitterMeters.toFloat(),
                    valueRange = 0f..30f,
                    onValueChange = { v -> onUpdate { it.copy(altitudeJitterMeters = (v * 2f).roundToInt() / 2.0) } },
                )
            }

            // ---------------------------------------------------------------- 独立模拟
            SectionHeader(stringResource(R.string.settings_independent), modifier = Modifier.padding(top = 16.dp))
            SwitchGroupRow(
                title = stringResource(R.string.settings_independent_enable),
                subtitle = stringResource(R.string.settings_independent_desc),
                checked = config.independentMode,
                onCheckedChange = { on -> onUpdate { it.copy(independentMode = on) } },
                icon = Icons.Filled.Apps,
                shape = groupItemShape(0, 2),
                modifier = Modifier.testTag("e2e.tool.independent"),
            )
            Spacer(modifier = Modifier.height(2.dp))
            NavRow(
                title = stringResource(R.string.settings_pick_apps),
                subtitle = if (config.independentPackages.isEmpty()) {
                    stringResource(R.string.settings_apps_none)
                } else {
                    stringResource(R.string.settings_apps_selected, config.independentPackages.size)
                },
                shape = groupItemShape(1, 2),
                onClick = { showAppPicker = true },
            )

            // ---------------------------------------------------------------- 路线
            SectionHeader(stringResource(R.string.tools_tab_route), modifier = Modifier.padding(top = 16.dp))
            SwitchGroupRow(
                title = stringResource(R.string.route_repeat),
                subtitle = stringResource(R.string.shell_route_repeat_desc),
                checked = config.routeRepeat,
                onCheckedChange = { on -> onUpdate { it.copy(routeRepeat = on) } },
                icon = Icons.Filled.Repeat,
                shape = groupItemShape(0, 4),
            )
            Spacer(modifier = Modifier.height(2.dp))
            SwitchGroupRow(
                title = stringResource(R.string.route_traffic_stops),
                subtitle = stringResource(R.string.shell_route_traffic_desc),
                checked = config.routeTrafficStops,
                onCheckedChange = { on -> onUpdate { it.copy(routeTrafficStops = on) } },
                icon = Icons.Filled.Traffic,
                shape = groupItemShape(1, 4),
                modifier = Modifier.testTag("e2e.tool.route.stops"),
            )
            Spacer(modifier = Modifier.height(2.dp))
            NavRow(
                title = stringResource(R.string.route_import_gpx),
                subtitle = stringResource(R.string.route_import_hint),
                icon = Icons.Filled.FileUpload,
                shape = groupItemShape(2, 4),
                onClick = actions.onImportGpx,
            )
            Spacer(modifier = Modifier.height(2.dp))
            NavRow(
                title = stringResource(R.string.route_export_gpx),
                subtitle = stringResource(R.string.route_export_hint),
                icon = Icons.Filled.FileDownload,
                shape = groupItemShape(3, 4),
                onClick = actions.onExportGpx,
            )

            // ---------------------------------------------------------------- 地图
            SectionHeader(stringResource(R.string.shell_section_map), modifier = Modifier.padding(top = 16.dp))
            NavRow(
                title = stringResource(R.string.shell_layers_row),
                subtitle = stringResource(tilePreset.labelRes),
                icon = Icons.Filled.Layers,
                shape = groupItemShape(0, 2),
                onClick = actions.onOpenLayers,
            )
            Spacer(modifier = Modifier.height(2.dp))
            NavRow(
                title = stringResource(R.string.settings_tile),
                subtitle = if (tilePreset == TilePreset.CUSTOM) tileUrl else stringResource(R.string.shell_tile_only_custom),
                subtitleMaxLines = 1,
                icon = Icons.Filled.Link,
                shape = groupItemShape(1, 2),
                onClick = { editTile = true },
            )

            // ---------------------------------------------------------------- 通用
            SectionHeader(stringResource(R.string.settings_general), modifier = Modifier.padding(top = 16.dp))
            GroupTile(shape = groupItemShape(0, 1)) {
                Text(stringResource(R.string.shell_start_page), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.shell_start_page_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                val pages = listOf(
                    SettingsStore.START_PAGE_LAST to R.string.shell_start_last,
                    SettingsStore.START_PAGE_MAP to R.string.shell_tab_map,
                    SettingsStore.START_PAGE_QUICK to R.string.shell_tab_quick,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    pages.forEachIndexed { index, (page, label) ->
                        SegmentedButton(
                            selected = startPage == page,
                            onClick = { actions.onStartPageChange(page) },
                            shape = SegmentedButtonDefaults.itemShape(index, pages.size),
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }

            // ---------------------------------------------------------------- 关于
            SectionHeader(stringResource(R.string.settings_about), modifier = Modifier.padding(top = 16.dp))
            GroupRow(
                title = stringResource(R.string.settings_version, versionName),
                subtitle = "ImmoralLocation · LSPosed",
                leading = { RowIcon(Icons.Filled.Info) },
                shape = groupItemShape(0, 2),
            )
            Spacer(modifier = Modifier.height(2.dp))
            GroupRow(
                title = stringResource(R.string.settings_reset),
                subtitle = stringResource(R.string.settings_reset_body),
                leading = { RowIcon(Icons.Filled.RestartAlt, tint = MaterialTheme.colorScheme.error) },
                shape = groupItemShape(1, 2),
                onClick = { showReset = true },
            )
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            selected = config.independentPackages,
            onDismiss = { showAppPicker = false },
            onConfirm = { selection ->
                onUpdate { it.copy(independentPackages = selection) }
                showAppPicker = false
            },
        )
    }

    if (editTile) {
        TextInputDialog(
            title = stringResource(R.string.settings_tile),
            initial = tileUrl,
            label = stringResource(R.string.shell_tile_template),
            supporting = stringResource(R.string.settings_tile_desc),
            onConfirm = {
                actions.onTileUrlChange(it)
                editTile = false
            },
            onDismiss = { editTile = false },
        )
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            icon = { Icon(Icons.Filled.RestartAlt, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onReset()
                        showReset = false
                    },
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** 模块 status as the first card of the page: green check / red warning + the way to fix it. */
@Composable
private fun ModuleCard(
    link: ModuleLink,
    moduleVersion: String,
    onOpenStatus: () -> Unit,
    onHelp: () -> Unit,
) {
    val active = link == ModuleLink.ACTIVE
    val inactive = link == ModuleLink.INACTIVE
    Surface(
        onClick = if (inactive) onHelp else onOpenStatus,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("e2e.settings.module"),
        shape = RoundedCornerShape(24.dp),
        color = when {
            active -> MaterialTheme.colorScheme.primaryContainer
            inactive -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = when {
            active -> MaterialTheme.colorScheme.onPrimaryContainer
            inactive -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (link == ModuleLink.CHECKING) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            } else {
                Icon(
                    if (active) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        when (link) {
                            ModuleLink.ACTIVE -> R.string.status_module_active
                            ModuleLink.INACTIVE -> R.string.status_module_inactive
                            ModuleLink.CHECKING -> R.string.status_connecting
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (inactive) {
                        stringResource(R.string.hint_activate_short)
                    } else {
                        stringResource(
                            R.string.settings_engine_version,
                            moduleVersion.ifBlank { stringResource(R.string.settings_engine_version_unknown) },
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** 更新频率 presets — a slider over 100…10 000 ms cannot land on a round number. */
@Composable
private fun IntervalChips(current: Long, onChange: (Long) -> Unit) {
    val presets = listOf(200L, 500L, 1_000L, 2_000L, 5_000L)
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("e2e.tool.interval"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { ms ->
            FilterChip(
                selected = current == ms,
                onClick = { onChange(ms) },
                label = {
                    Text(
                        if (ms >= 1_000L) "${ms / 1000} s" else "$ms ms",
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }
    }
}

/** Opens LSPosed's manager when it is installed; returns `false` when nothing could be opened. */
fun launchLsposed(context: Context): Boolean {
    val candidates = listOf(
        "org.lsposed.manager",
        "de.robv.android.xposed.installer",
    )
    val intent = candidates.firstNotNullOfOrNull { pkg ->
        context.packageManager.getLaunchIntentForPackage(pkg)
    } ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}

private data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

/**
 * Multi-select picker for 独立模拟: label + icon + search, so 200+ packages stay navigable.
 * Already-selected apps are listed first, so reopening the picker shows what is on.
 */
@Composable
fun AppPickerDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    val working = remember(selected) { mutableStateListOf<String>().apply { addAll(selected) } }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
            .sortedWith(compareByDescending<AppEntry> { it.packageName in selected }.thenBy { it.label.lowercase() })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_app_picker_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.settings_apps_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(8.dp))
                val all = apps
                when {
                    all == null -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.settings_loading_apps))
                    }
                    all.isEmpty() -> Text(stringResource(R.string.settings_app_picker_empty))
                    else -> {
                        val filtered = all.filter {
                            query.isBlank() ||
                                it.label.contains(query, ignoreCase = true) ||
                                it.packageName.contains(query, ignoreCase = true)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.settings_apps_selected, working.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            if (working.isNotEmpty()) {
                                TextButton(onClick = { working.clear() }) {
                                    Text(stringResource(R.string.shell_clear))
                                }
                            }
                        }
                        HorizontalDivider()
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(filtered, key = { it.packageName }) { app ->
                                val checked = app.packageName in working
                                val toggle = {
                                    if (checked) working.remove(app.packageName) else working.add(app.packageName)
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { toggle() }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (app.icon != null) {
                                        Image(
                                            bitmap = app.icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(36.dp),
                                        )
                                    } else {
                                        Box(modifier = Modifier.size(36.dp)) {
                                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(36.dp)) {}
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            app.label,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            app.packageName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Checkbox(checked = checked, onCheckedChange = { toggle() })
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(working.toSet()) }) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = try {
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    } catch (t: Throwable) {
        emptyList()
    }
    return resolved
        .mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            val label = try {
                info.loadLabel(pm).toString()
            } catch (t: Throwable) {
                pkg
            }
            val icon = try {
                info.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            } catch (t: Throwable) {
                null
            }
            AppEntry(pkg, label, icon)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

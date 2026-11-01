package com.immoral.location.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.immoral.location.R
import com.immoral.location.bridge.ModuleLink
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.ui.components.Fmt
import com.immoral.location.ui.components.LabeledSlider
import com.immoral.location.ui.components.SectionCard
import com.immoral.location.ui.components.SwitchRow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置: every simulation parameter, the tile source, the app allow-list for 独立模拟, the module
 * status and the about block.
 */
@Composable
fun SettingsScreen(
    config: SimulationConfig,
    link: ModuleLink,
    moduleVersion: String,
    tileUrl: String,
    versionName: String,
    onUpdate: ((SimulationConfig) -> SimulationConfig) -> Unit,
    onTileUrlChange: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAppPicker by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var tileDraft by remember(tileUrl) { mutableStateOf(tileUrl) }
    val unknownVersion = stringResource(R.string.settings_engine_version_unknown)
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = stringResource(R.string.settings_general)) {
            LabeledSlider(
                title = stringResource(R.string.settings_interval),
                valueText = stringResource(
                    R.string.settings_interval_value,
                    config.updateIntervalMs.toInt(),
                ),
                value = config.updateIntervalMs.toFloat(),
                valueRange = SimulationConfig.MIN_INTERVAL_MS.toFloat()..SimulationConfig.MAX_INTERVAL_MS.toFloat(),
                steps = 98,
                onValueChange = { raw ->
                    val snapped = (raw / 100f).roundToInt() * 100L
                    onUpdate { it.copy(updateIntervalMs = snapped) }
                },
            )
            Spacer(modifier = Modifier.size(8.dp))
            LabeledSlider(
                title = stringResource(R.string.settings_gps_jitter),
                valueText = stringResource(R.string.settings_jitter_value, config.gpsJitterMeters),
                value = config.gpsJitterMeters.toFloat(),
                valueRange = 0f..SimulationConfig.MAX_JITTER_METERS.toFloat(),
                onValueChange = { onUpdate { cfg -> cfg.copy(gpsJitterMeters = it.toDouble()) } },
            )
            LabeledSlider(
                title = stringResource(R.string.settings_alt_jitter),
                valueText = stringResource(R.string.settings_jitter_value, config.altitudeJitterMeters),
                value = config.altitudeJitterMeters.toFloat(),
                valueRange = 0f..SimulationConfig.MAX_JITTER_METERS.toFloat(),
                onValueChange = { onUpdate { cfg -> cfg.copy(altitudeJitterMeters = it.toDouble()) } },
            )
        }

        SectionCard(title = stringResource(R.string.settings_simulation)) {
            SwitchRow(
                title = stringResource(R.string.settings_cell),
                checked = config.cellSimulation,
                onCheckedChange = { on -> onUpdate { it.copy(cellSimulation = on) } },
            )
            SwitchRow(
                title = stringResource(R.string.settings_wifi),
                checked = config.wifiSimulation,
                onCheckedChange = { on -> onUpdate { it.copy(wifiSimulation = on) } },
            )
            SwitchRow(
                title = stringResource(R.string.settings_gps_signal),
                checked = config.gpsSignalSimulation,
                onCheckedChange = { on -> onUpdate { it.copy(gpsSignalSimulation = on) } },
            )
            SwitchRow(
                title = stringResource(R.string.settings_step),
                checked = config.stepSimulation,
                onCheckedChange = { on -> onUpdate { it.copy(stepSimulation = on) } },
            )
            if (config.stepSimulation) {
                LabeledSlider(
                    title = stringResource(R.string.settings_step_cadence),
                    valueText = stringResource(R.string.settings_step_cadence_value, config.stepCadenceBpm),
                    value = config.stepCadenceBpm.toFloat(),
                    valueRange = SimulationConfig.MIN_CADENCE_BPM.toFloat()..SimulationConfig.MAX_CADENCE_BPM.toFloat(),
                    steps = 17,
                    onValueChange = { raw ->
                        onUpdate { it.copy(stepCadenceBpm = raw.roundToInt()) }
                    },
                )
            }
        }

        SectionCard(
            title = stringResource(R.string.settings_independent),
            subtitle = stringResource(R.string.settings_independent_desc),
        ) {
            // The switch used to repeat the card title verbatim (card 独立模拟 / row 独立模拟). The
            // row now names the *action* of the switch, which is what the user is toggling.
            SwitchRow(
                title = stringResource(R.string.settings_independent_enable),
                checked = config.independentMode,
                onCheckedChange = { on -> onUpdate { it.copy(independentMode = on) } },
            )
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = config.independentMode) { showAppPicker = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_pick_apps),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (config.independentPackages.isEmpty()) {
                            stringResource(R.string.settings_apps_none)
                        } else {
                            stringResource(R.string.settings_apps_selected, config.independentPackages.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // A real chevron icon, not the "›" glyph: the glyph renders at label-tracking width
                // and its baseline is off by 2-3 dp against the row (audit R5, settings).
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        SectionCard(
            title = stringResource(R.string.settings_tile),
            subtitle = stringResource(R.string.settings_tile_desc),
        ) {
            Spacer(modifier = Modifier.size(8.dp))
            OutlinedTextField(
                value = tileDraft,
                onValueChange = { tileDraft = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_tile)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Same 48 dp control rhythm and the same secondary/primary pair as every other
                // surface (audit R5): 恢复默认 is tertiary text, 保存 is the tonal action of the card.
                TextButton(
                    onClick = { tileDraft = com.immoral.location.data.SettingsStore.DEFAULT_TILE_URL },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.settings_tile_reset))
                }
                FilledTonalButton(
                    onClick = { onTileUrlChange(tileDraft) },
                    modifier = Modifier.height(48.dp),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }

        SectionCard(title = stringResource(R.string.settings_module)) {
            Text(
                text = stringResource(
                    R.string.settings_module_status,
                    stringResource(
                        when (link) {
                            ModuleLink.ACTIVE -> R.string.status_module_active
                            ModuleLink.INACTIVE -> R.string.status_module_inactive
                            ModuleLink.CHECKING -> R.string.status_connecting
                        },
                    ),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.settings_engine_version,
                    moduleVersion.ifBlank { unknownVersion },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(8.dp))
            OutlinedButton(onClick = { showHelp = true }) {
                Text(stringResource(R.string.settings_module_help))
            }
        }

        SectionCard(title = stringResource(R.string.settings_about)) {
            Text(
                text = stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "ImmoralLocation · LSPosed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(8.dp))
            OutlinedButton(onClick = { showReset = true }) {
                Text(stringResource(R.string.settings_reset))
            }
        }

        Spacer(modifier = Modifier.size(24.dp))
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

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(stringResource(R.string.hint_activate_title)) },
            text = { Text(stringResource(R.string.hint_activate_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showHelp = false
                        launchLsposed(context)
                    },
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onReset()
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

/** Opens LSPosed's module detail page when the manager is installed; otherwise does nothing. */
private fun launchLsposed(context: Context) {
    val candidates = listOf(
        "org.lsposed.manager",
        "de.robv.android.xposed.installer",
    )
    candidates.firstOrNull { pkg ->
        context.packageManager.getLaunchIntentForPackage(pkg) != null
    }?.let { pkg ->
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}

private data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

/** Multi-select picker for 独立模拟: label + icon + search, so 200+ packages stay navigable. */
@Composable
fun AppPickerDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var working by remember(selected) { mutableStateOf(selected.toMutableSet()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_app_picker_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.settings_apps_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(8.dp))
                val all = apps
                when {
                    all == null -> Text(stringResource(R.string.settings_loading_apps))
                    all.isEmpty() -> Text(stringResource(R.string.settings_app_picker_empty))
                    else -> {
                        val filtered = all.filter {
                            query.isBlank() ||
                                it.label.contains(query, ignoreCase = true) ||
                                it.packageName.contains(query, ignoreCase = true)
                        }
                        Text(
                            text = stringResource(R.string.settings_apps_selected, working.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(filtered, key = { it.packageName }) { app ->
                                val checked = working.contains(app.packageName)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (checked) working.remove(app.packageName)
                                            else working.add(app.packageName)
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (app.icon != null) {
                                        Icon(
                                            painter = BitmapPainter(app.icon),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(32.dp)
                                                .padding(end = 8.dp),
                                        )
                                    } else {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier
                                                .size(32.dp)
                                                .padding(end = 8.dp),
                                        ) {}
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            app.packageName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { on ->
                                            if (on) working.add(app.packageName)
                                            else working.remove(app.packageName)
                                        },
                                    )
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

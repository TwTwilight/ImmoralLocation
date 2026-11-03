package com.immoral.location.ui.sheets

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.immoral.location.R
import com.immoral.location.bridge.ModuleLink
import com.immoral.location.core.model.EngineState
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.MoveMode
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.data.PreviewCounts
import com.immoral.location.ui.components.Fmt
import com.immoral.location.ui.components.SheetTitle
import com.immoral.location.ui.theme.Dimens
import com.immoral.location.ui.theme.tabular
import java.util.Locale

/**
 * 当前状态 sheet: the precise readout.
 *
 * Coordinates are *secondary* here (spec §2: 坐标用 `labelSmall` + tnum，不再是主标题) — the
 * heading of the sheet is the simulation state, and the numbers are laid out as a two-column grid
 * of tonal tiles so they stay scannable instead of turning into a wall of `39.9087, 116.3975`.
 */
@Composable
fun StatusSheetContent(
    state: EngineState?,
    link: ModuleLink,
    config: SimulationConfig,
    position: GeoPoint,
    counts: PreviewCounts,
    onCopy: (String) -> Unit,
    onHelpClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = state?.running == true
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.sheetPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
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
            SheetTitle(stringResource(R.string.sheet_status_title))
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(
                    when (link) {
                        ModuleLink.ACTIVE -> R.string.status_module_active
                        ModuleLink.INACTIVE -> R.string.status_module_inactive
                        ModuleLink.CHECKING -> R.string.status_connecting
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (link == ModuleLink.ACTIVE) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = stringResource(R.string.label_lat),
                value = Fmt.coord(position.lat),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.label_lon),
                value = Fmt.coord(position.lon),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = stringResource(R.string.label_speed),
                value = Fmt.speed(state?.speedMps ?: 0.0),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.label_kmh),
                value = String.format(Locale.US, "%.1f km/h", (state?.speedMps ?: 0.0) * 3.6),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = stringResource(R.string.label_bearing),
                value = Fmt.deg(state?.bearingDeg ?: 0.0),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.label_heading),
                value = Fmt.deg(state?.headingDeg ?: 0.0),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.label_steps),
                value = (state?.steps ?: 0L).toString(),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = stringResource(R.string.label_mode),
                value = stringResource(
                    when (state?.mode ?: config.mode) {
                        MoveMode.JOYSTICK -> R.string.mode_joystick
                        MoveMode.ROUTE -> R.string.mode_route
                        else -> R.string.mode_static
                    },
                ),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.label_alt),
                value = "${Fmt.trimZero(position.alt)} m",
                modifier = Modifier.weight(1f),
            )
            if (counts.satellites > 0) {
                StatTile(
                    label = stringResource(R.string.shell_satellites),
                    value = "${counts.satellitesUsed}/${counts.satellites}",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (counts.cells > 0 || counts.accessPoints > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (counts.cells > 0) {
                    StatTile(
                        label = stringResource(R.string.settings_cell),
                        value = stringResource(R.string.status_cells, counts.cells),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (counts.accessPoints > 0) {
                    StatTile(
                        label = stringResource(R.string.settings_wifi),
                        value = stringResource(R.string.status_wifi, counts.accessPoints),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        FilledTonalButton(
            onClick = {
                onCopy(
                    String.format(
                        Locale.US,
                        "%s, %s",
                        Fmt.coord(position.lat),
                        Fmt.coord(position.lon),
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.action_copy_coords))
        }

        if (link != ModuleLink.ACTIVE) {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.hint_activate_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onHelpClick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_module_help))
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (value.isNotEmpty()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

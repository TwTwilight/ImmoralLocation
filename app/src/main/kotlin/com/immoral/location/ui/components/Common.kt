package com.immoral.location.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.immoral.location.R
import com.immoral.location.core.model.SpeedLevel
import java.util.Locale

/** Number formatting used across the UI. Coordinates are always shown with 6 decimals. */
object Fmt {
    fun coord(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.6f", value) else "--"

    /** 4-decimal variant for the always-visible status pill (6 decimals live in the detail sheet). */
    fun coord4(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.4f", value) else "--"

    fun speed(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.1f m/s", value) else "--"

    fun deg(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.0f°", value) else "--"

    fun distance(meters: Double): String = when {
        !meters.isFinite() -> "--"
        meters >= 100_000.0 -> String.format(Locale.US, "%.0f km", meters / 1000.0)
        meters >= 10_000.0 -> String.format(Locale.US, "%.1f km", meters / 1000.0)
        meters >= 1000.0 -> String.format(Locale.US, "%.2f km", meters / 1000.0)
        else -> String.format(Locale.US, "%.0f m", meters)
    }

    fun meters(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.1f m", value) else "--"

    /** `1.4 -> "1.4"`, `230.0 -> "230"`: keeps the speed chips compact. */
    fun trimZero(value: Double): String =
        if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
}

/**
 * Display-side naming of saved places.
 *
 * `FavoritesStore` names a place that was saved without a name `收藏地点 N`. That placeholder reads
 * like a heading ("收藏地点" is the title of the saved-places screen), so every list shows it as
 * `收藏点 N` instead: the row name and the screen title no longer look like the same thing, and a
 * row never starts with the title of the surface it lives in. User-chosen names are untouched.
 */
object FavoriteText {

    private val placeholder = Regex("""^收藏地点\s*(\d+)$""")

    /** The name to render for [name] (never blank; blank falls back to `收藏点`). */
    fun displayName(name: String): String {
        val trimmed = name.trim()
        val match = placeholder.matchEntire(trimmed)
        if (match != null) return "收藏点 " + match.groupValues[1]
        return trimmed.ifEmpty { "收藏点" }
    }
}

/** A titled rounded card; the building block of every screen. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            content()
        }
    }
}

/** Label + value pair used by the status panel. */
@Composable
fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

/** A row with a title, optional supporting text and a switch. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Title + live value + slider, used for every numeric setting. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LabeledSlider(
    title: String,
    valueText: @Composable (Float) -> String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    // The slider drags a local draft and commits once on release: committing every frame pushed a
    // full config over the bridge ~60 times a second while the thumb moved.
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = valueText(draft),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = draft.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { draft = it },
            onValueChangeFinished = { onValueChange(draft) },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                // Half a thumb of inset so a 0 % value does not leave the track's rounded
                // start cap peeking out from behind the disc (it read as a second, clipped handle).
                .padding(horizontal = 10.dp),
            // M3 1.3's default thumb is the expressive *bar*: at 0 % it sits half outside the
            // rounded track cap (reading as a stray vertical line) and where it overlaps the cap it
            // renders as a clipped "C". A 20 dp disc at the same optical weight as the app's other
            // 20 dp glyphs is unambiguous in both themes and cannot clip inside the 36 dp box.
            // Control experiment (Lead-requested): suppress M3's stop indicator. Two of the three
            // sliders never showed the crescent, so if this removes it here, it *was* the indicator
            // and the asymmetry was only how much active track each value leaves.
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    drawStopIndicator = null,
                )
            },            thumb = {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            },
        )
    }
}

/** The five preset speeds plus 自定义. */
@Composable
fun SpeedSelector(
    selected: SpeedLevel,
    onSelect: (SpeedLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = listOf(
        Triple(SpeedLevel.WALK, stringResource(R.string.speed_walk), true),
        Triple(SpeedLevel.RUN, stringResource(R.string.speed_run), true),
        Triple(SpeedLevel.BIKE, stringResource(R.string.speed_bike), true),
        Triple(SpeedLevel.DRIVE, stringResource(R.string.speed_drive), true),
        Triple(SpeedLevel.PLANE, stringResource(R.string.speed_plane), true),
        Triple(SpeedLevel.CUSTOM, stringResource(R.string.speed_custom), false),
    )
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        chips.forEach { (level, label, showSpeed) ->
            FilterChip(
                selected = selected == level,
                onClick = { onSelect(level) },
                label = {
                    Text(
                        if (showSpeed) "$label ${Fmt.trimZero(level.defaultMetersPerSecond)}" else label,
                    )
                },
            )
        }
    }
}

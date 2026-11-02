package com.immoral.location.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The three text roles every sheet in this app shares.
 *
 * The audit finding behind this file: each surface had invented its own scale — the planner used
 * `titleLarge` for its heading and `bodyLarge` for the value of a stop, the saved-places sheet used
 * `headlineSmall` + `titleSmall`, the tools panel used `labelLarge` for section labels and `bodyLarge`
 * for rows. Nothing was *wrong* on its own, but a user moving between two sheets saw two typographic
 * systems, which is the definition of "inconsistent role" in the audit.
 *
 * The contract is now exactly three roles, used everywhere:
 *
 * | role | style | used for |
 * |---|---|---|
 * | [SheetTitle] | `titleLarge` / SemiBold | the one heading of a surface |
 * | [SheetLabel] | `labelMedium` / onSurfaceVariant | the name of a field, group or stat |
 * | [SheetValue] | `titleMedium` | the readable value under a label |
 * | [SheetSupporting] | `bodySmall` / onSurfaceVariant | one line of help under a title |
 *
 * Nothing else may be used for those purposes: no `headlineSmall` headings, no `bodyLarge` field
 * values, no `labelLarge` section labels.
 */
@Composable
fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** One line of help right under a [SheetTitle]. */
@Composable
fun SheetSupporting(text: String, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // Callers that carry an explanation rather than a caption pass a higher ceiling: a body cut
        // mid-clause ("…适合在导航、运动类应用…") is a fragment, not brevity (R7 third step).
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The name of a field, a group of rows or a statistic. */
@Composable
fun SheetLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The readable value that belongs to a [SheetLabel]. */
@Composable
fun SheetValue(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * A hint that is *not* a control.
 *
 * The audit found "选择终点后即可规划路线" rendered inside a focal card, so it read as a disabled
 * button. A hint is text next to an info glyph, never a surface with a container colour.
 */
@Composable
fun SheetHint(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

package com.immoral.location.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Building blocks of the full-screen pages (快捷 / 自定义 / 设置).
 *
 * The pages follow the Material You "grouped list" pattern used by Android's own settings: rows of
 * one group sit on separate tonal tiles with a 2 dp gap, the outer corners of the group are large
 * (20 dp) and the inner ones small (6 dp). One component family for every page means the three tabs
 * read as one app instead of three experiments.
 */
object ListGroupDefaults {
    val outer: Dp = 20.dp
    val inner: Dp = 6.dp
    val gap: Dp = 2.dp
    val horizontalPadding: Dp = 16.dp
}

/** Shape of item [index] in a group of [count] rows. */
fun groupItemShape(index: Int, count: Int): Shape {
    val o = ListGroupDefaults.outer
    val i = ListGroupDefaults.inner
    return when {
        count <= 1 -> RoundedCornerShape(o)
        index == 0 -> RoundedCornerShape(topStart = o, topEnd = o, bottomStart = i, bottomEnd = i)
        index == count - 1 -> RoundedCornerShape(topStart = i, topEnd = i, bottomStart = o, bottomEnd = o)
        else -> RoundedCornerShape(i)
    }
}

/** Container colour of a grouped row. */
@Composable
fun groupItemColor(): Color = MaterialTheme.colorScheme.surfaceContainer

/** A labelled group: section title + rows with the grouped-list shape rhythm. */
@Composable
fun ListSection(
    title: String?,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            SectionHeader(title = title, action = action)
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListGroupDefaults.gap), content = content)
    }
}

/** Section title of a page (primary `titleSmall`, the Android settings convention). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (action != null) action()
    }
}

/** A round tonal tile holding the leading glyph of a row. */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: Dp = 40.dp,
) {
    Surface(color = container, contentColor = content, shape = CircleShape, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/**
 * One grouped row.
 *
 * Tap and long-press are both on the whole tile (never on a text node), so a tap anywhere on the
 * row works. [trailing] sits outside the click target's semantics only visually: an icon button
 * placed there keeps its own click.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupRow(
    title: String,
    shape: Shape,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    subtitleMaxLines: Int = 2,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    enabled: Boolean = true,
    container: Color = groupItemColor(),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    titleStyleBold: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = container,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        val clickable = if (onClick != null || onLongClick != null) {
            Modifier.combinedClickable(
                enabled = enabled,
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick,
            )
        } else {
            Modifier
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(clickable)
                .heightIn(min = 64.dp)
                .padding(start = 16.dp, end = if (trailing != null) 8.dp else 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(modifier = Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (titleStyleBold) FontWeight.Medium else null,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) subtitleColor else subtitleColor.copy(alpha = 0.38f),
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

/** A grouped row that navigates somewhere (trailing chevron). */
@Composable
fun NavRow(
    title: String,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    subtitleMaxLines: Int = 2,
) {
    GroupRow(
        title = title,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        subtitleMaxLines = subtitleMaxLines,
        shape = shape,
        modifier = modifier,
        onClick = onClick,
        leading = icon?.let { { RowIcon(it) } },
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        },
    )
}

/** A grouped row with a switch; the whole row toggles it. */
@Composable
fun SwitchGroupRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    GroupRow(
        title = title,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        shape = shape,
        modifier = modifier,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        leading = icon?.let { { RowIcon(it) } },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.padding(end = 8.dp),
            )
        },
    )
}

/** Neutral leading glyph for settings-style rows (24 dp, onSurfaceVariant). */
@Composable
fun RowIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
}

/**
 * Free-form content inside a group tile (sliders, chips, segmented rows), padded like a row.
 */
@Composable
fun GroupTile(
    shape: Shape,
    modifier: Modifier = Modifier,
    container: Color = groupItemColor(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = container,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), content = content)
    }
}

/** Designed empty state inside a page section: glyph, one line of title, one line of help. */
@Composable
fun EmptyTile(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ListGroupDefaults.outer),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon = icon,
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (action != null) {
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        content = action,
                    )
                }
            }
        }
    }
}

package com.immoral.location.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immoral.location.R
import com.immoral.location.ui.map.MarkerGeometry
import com.immoral.location.ui.theme.Dimens
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.coroutines.launch

/**
 * The map joystick — **screen-aligned** (spec §4).
 *
 * Direction semantics are the fix for the "摇杆方向反了" complaint:
 *  * the vector always means *screen* direction — pushing up moves the simulated position north,
 *    because the map is north-up and never rotates;
 *  * when 跟随手机朝向 is enabled the engine rotates the travel direction by the phone heading, so
 *    "up" is no longer north. The pad therefore rotates its tick ring by `-heading` and moves the
 *    `N` marker to wherever north actually is on the pad, and the caption changes to
 *    `上 = 朝向 45°`. The user can *see* the mapping instead of guessing it.
 *
 * Pushing up/down/left/right maps to `+y`/`-y`/`+x`/`-x` — the same convention
 * `SimulationEngine.setJoystick` documents (`atan2(x, y)`, 0° = north, +90° = east), so the arrow
 * the user draws on the pad and the arrow the marker travels are the same arrow.
 */
@Composable
fun JoystickPad(
    followHeading: Boolean,
    headingDeg: Double?,
    onVector: (Float, Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val knob = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var sidePx by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()

    val scheme = MaterialTheme.colorScheme
    val padColor = scheme.surfaceContainerHighest.copy(alpha = 0.78f)
    val rimColor = scheme.primary
    val tickColor = scheme.onSurfaceVariant
    val knobColor = scheme.primary
    val knobRing = scheme.surface
    val heading = (headingDeg ?: 0.0).toFloat()

    Box(
        modifier = modifier
            .size(Dimens.joystick)
            .onSizeChanged { sidePx = min(it.width, it.height).toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled, followHeading) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { position ->
                        scope.launch { knob.snapTo(clampToPad(position, sidePx)) }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val next = clampToPad(change.position, sidePx)
                        scope.launch { knob.snapTo(next) }
                        emitVector(next, sidePx, onVector)
                    },
                    onDragEnd = {
                        scope.launch {
                            knob.animateTo(
                                Offset.Zero,
                                spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
                            )
                        }
                        onRelease()
                    },
                    onDragCancel = {
                        scope.launch { knob.animateTo(Offset.Zero) }
                        onRelease()
                    },
                )
            },
    ) {
        val caption = if (followHeading) {
            stringResource(R.string.joystick_up_is_heading, heading.toInt())
        } else {
            stringResource(R.string.joystick_up_is_north)
        }
        Canvas(modifier = Modifier.size(Dimens.joystick)) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val travel = radius * TRAVEL_FRACTION

            // Frosted pad: a soft top-down sheen over a translucent container colour.
            drawCircle(
                brush = Brush.verticalGradient(
                    listOf(
                        padColor.copy(alpha = 0.9f),
                        padColor.copy(alpha = 0.72f),
                    ),
                ),
                radius = radius,
                center = center,
            )
            // 3dp primary outline on the base plate (spec §4).
            drawCircle(
                color = rimColor.copy(alpha = 0.75f),
                radius = radius - 1.5.dp.toPx(),
                center = center,
                style = Stroke(width = 3.dp.toPx()),
            )

            // Tick ring + N marker. Rotated by -heading so north is honest when following.
            rotate(degrees = MarkerGeometry.ringRotationDeg(followHeading, headingDeg)) {
                drawTickRing(center, radius, travel, tickColor)
                drawNorthMarker(center, radius, if (followHeading) rimColor else tickColor)
            }

            if (!followHeading) {
                // A faint static cross-hair reminds the user the pad is screen-aligned.
                val arm = radius * 0.86f
                val hair = tickColor.copy(alpha = 0.16f)
                drawLine(hair, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), 1.dp.toPx())
                drawLine(hair, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), 1.dp.toPx())
            }

            val knobCenter = center + knob.value
            // Direction indicator: the line from the centre makes the emitted vector legible.
            if (hypot(knob.value.x, knob.value.y) > 2f) {
                drawLine(
                    color = rimColor.copy(alpha = 0.45f),
                    start = center,
                    end = knobCenter,
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            // 44dp thumb: primary fill with a 2dp surface ring (spec §4).
            val thumbR = Dimens.joystickThumb.toPx() / 2f
            drawCircle(color = Color.Black.copy(alpha = 0.16f), radius = thumbR + 1.dp.toPx(), center = knobCenter + Offset(0f, 2.dp.toPx()))
            drawCircle(color = knobRing, radius = thumbR + 2.dp.toPx(), center = knobCenter)
            drawCircle(color = knobColor, radius = thumbR, center = knobCenter)

            drawCaption(caption, center, radius, scheme.onSurfaceVariant)
        }
    }
}

/** 12 ticks, every third one long — the tick ring spec §4 asks for. */
private fun DrawScope.drawTickRing(
    center: Offset,
    radius: Float,
    travel: Float,
    color: Color,
) {
    drawCircle(
        color = color.copy(alpha = 0.18f),
        radius = travel,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )
    for (i in 0 until 12) {
        rotate(degrees = i * 30f, pivot = center) {
            val major = i % 3 == 0
            drawLine(
                color = color.copy(alpha = if (major) 0.55f else 0.24f),
                start = Offset(center.x, radius - 8.dp.toPx()),
                end = Offset(center.x, radius - (if (major) 16.dp else 12.dp).toPx()),
                strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/** The `N` marker: a small filled triangle plus the letter, so it survives any tile colour. */
private fun DrawScope.drawNorthMarker(center: Offset, radius: Float, color: Color) {
    val y = radius - 24.dp.toPx()
    val half = 4.dp.toPx()
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(center.x, y - half)
        lineTo(center.x - half, y + half)
        lineTo(center.x + half, y + half)
        close()
    }
    drawPath(path, color)
    drawContext.canvas.nativeCanvas.drawText(
        "N",
        center.x,
        y - half - 3.dp.toPx(),
        android.graphics.Paint().apply {
            this.color = color.toArgb()
            textSize = 10.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD,
            )
        },
    )
}

/** `上 = 北` / `上 = 朝向 45°` — the visible answer to "which way is up?". */
private fun DrawScope.drawCaption(text: String, center: Offset, radius: Float, color: Color) {
    drawContext.canvas.nativeCanvas.drawText(
        text,
        center.x,
        center.y + radius * 0.62f,
        android.graphics.Paint().apply {
            this.color = (color.copy(alpha = 0.82f)).toArgb()
            textSize = 10.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT
        },
    )
}

/** Clamps [position] into the pad and converts it to a knob offset from the centre. */
private fun clampToPad(position: Offset, sidePx: Float): Offset {
    val radius = sidePx / 2f
    val center = Offset(radius, radius)
    val travel = radius * TRAVEL_FRACTION
    var dx = position.x - center.x
    var dy = position.y - center.y
    val dist = hypot(dx, dy)
    if (dist > travel && dist > 0f) {
        val scale = travel / dist
        dx *= scale
        dy *= scale
    }
    return Offset(dx, dy).takeIf { it.x.isFinite() && it.y.isFinite() } ?: Offset.Zero
}

/** Emits the normalized vector; `+y` is up on screen = north on the map (see [MarkerGeometry]). */
private fun emitVector(offset: Offset, sidePx: Float, onVector: (Float, Float) -> Unit) {
    val travel = sidePx / 2f * TRAVEL_FRACTION
    if (travel <= 0f) return
    val (nx, ny) = MarkerGeometry.normalizedVector(offset.x, offset.y, travel)
    onVector(nx, ny)
}

private val TRAVEL_FRACTION = 0.56f

/**
 * Compass dial: shows where the phone points (跟随手机朝向) or the fixed bearing, and doubles as a
 * drag target so a fixed direction can be dialled in by hand.
 */
@Composable
fun CompassDial(
    headingDeg: Double?,
    bearingDeg: Double,
    following: Boolean,
    modifier: Modifier = Modifier,
    onBearingChange: ((Double) -> Unit)? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    val onSurface = MaterialTheme.colorScheme.onSurface
    val variant = MaterialTheme.colorScheme.onSurfaceVariant

    val needle = if (following) (headingDeg ?: bearingDeg) else bearingDeg

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(onBearingChange == null) {
                if (onBearingChange == null) return@pointerInput
                detectDragGestures(
                    onDrag = { change, _ ->
                        change.consume()
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val dx = change.position.x - cx
                        val dy = change.position.y - cy
                        var deg = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble()))
                        if (deg < 0) deg += 360.0
                        onBearingChange(deg)
                    },
                )
            },
    ) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(color = surface, radius = radius, center = center)
        drawCircle(
            color = primary.copy(alpha = 0.35f),
            radius = radius - 1.dp.toPx(),
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
        )
        for (i in 0 until 12) {
            rotate(degrees = i * 30f, pivot = center) {
                val long = i % 3 == 0
                drawLine(
                    color = variant.copy(alpha = if (long) 0.7f else 0.3f),
                    start = Offset(center.x, 4.dp.toPx()),
                    end = Offset(center.x, (if (long) 12.dp else 8.dp).toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        }

        rotate(degrees = needle.toFloat(), pivot = center) {
            drawLine(
                color = primary,
                start = Offset(center.x, center.y + radius * 0.42f),
                end = Offset(center.x, center.y - radius * 0.78f),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = primary,
                radius = radius * 0.09f,
                center = Offset(center.x, center.y - radius * 0.78f),
            )
        }
        drawCircle(color = onSurface.copy(alpha = 0.85f), radius = radius * 0.06f, center = center)

        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = variant.copy(alpha = 0.85f).toArgb()
                textSize = 11.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            drawText("N", center.x, 16.dp.toPx(), paint)
            drawText("S", center.x, size.height - 6.dp.toPx(), paint)
            drawText("E", size.width - 7.dp.toPx(), center.y + 4.dp.toPx(), paint)
            drawText("W", 7.dp.toPx(), center.y + 4.dp.toPx(), paint)
        }
    }
}


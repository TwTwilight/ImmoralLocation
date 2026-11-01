package com.immoral.location.ui.map

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/** A marker bitmap plus the anchor the map SDK should pin it to (`0..1` of width/height). */
internal class MarkerBitmap(
    val bitmap: Bitmap,
    val anchorX: Float,
    val anchorY: Float,
)

/**
 * Draws every map marker as a Material You bitmap.
 *
 * Rationale: both 高德 and osmdroid take bitmaps, not composables, and the previous build used
 * `BitmapDescriptorFactory.HUE_VIOLET` (a 2013-era Google Maps pin) plus emoji-free star PNGs.
 * These painters reuse the exact M3 roles — `primary` dot with a `surface` ring, a `tertiary`
 * favourite, `primaryContainer` / `errorContainer` badges — so the map and the chrome around it
 * are visibly one design language.
 *
 * All methods are cheap enough to call on config/palette changes only; the hosts cache the
 * resulting [MarkerBitmap] and only move the marker afterwards.
 */
internal object MarkerArt {

    /** Which container colour a numbered badge uses. */
    enum class Badge { WAYPOINT, START, END }

    // ---------------------------------------------------------------- helpers

    private fun bitmap(widthDp: Float, heightDp: Float, density: Float): Pair<Bitmap, Canvas> {
        val w = (widthDp * density).toInt().coerceAtLeast(1)
        val h = (heightDp * density).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return bmp to Canvas(bmp)
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun stroke(color: Int, widthPx: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = widthPx
        strokeCap = Paint.Cap.ROUND
    }

    /** Marker drop shadow: a blurred translucent ellipse instead of a hard border. */
    private fun shadow(density: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        maskFilter = BlurMaskFilter(2.5f * density, BlurMaskFilter.Blur.NORMAL)
    }

    /**
     * A denser shadow for the route badges.
     *
     * They are the only markers that must stay legible against a *route line and POI labels* at the
     * same time, so the soft 20 % halo used by the position dot is not enough: this one is deeper
     * and offset by a full pixel below the badge.
     */
    private fun hardShadow(density: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000
        maskFilter = BlurMaskFilter(3f * density, BlurMaskFilter.Blur.NORMAL)
    }

    /**
     * The ring/casing colour that separates a marker from whatever is underneath it.
     *
     * The spec says `surface`, which is near-white in light mode — exactly right. In dark mode
     * `surface` is nearly black, so a `surface` ring around a light `primary` dot is invisible over
     * a dark map; there the ring has to be the light `onSurface` instead. Same intent, correct in
     * both schemes.
     */
    private fun ring(palette: MapPalette): Int =
        if (palette.dark) palette.onSurface else palette.surface

    private fun alpha(color: Int, fraction: Float): Int {
        val a = ((color ushr 24) * fraction).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    // --------------------------------------------------------------- markers

    /**
     * Current position: the most important object on the map (audit S4).
     *
     * Was: a flat 22 dp dot with a 3 dp ring and a 20 % shadow — on a busy street network it read as
     * another POI glyph. Is now: a 24 dp `primary` core, a **3.5 dp `surface` casing plus a 1 dp
     * dark hairline** outside it (a contrasting double ring, so the marker keeps an edge over white
     * roads, green parks and blue water alike), and a soft `primary` halo that makes it read as the
     * anchor of the frame. The accuracy circle drawn by the map host sits under all of it.
     */
    fun currentPosition(palette: MapPalette, density: Float): MarkerBitmap {
        val box = 80f
        val (bmp, canvas) = bitmap(box, box, density)
        val c = box / 2f * density
        val dotR = 12f * density
        val ring = 3.5f * density
        // Soft halo: a three-stop radial gradient rather than a flat disc, so the marker glows into
        // the map instead of stamping a translucent circle onto it.
        val haloRadius = 34f * density
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                c,
                c,
                haloRadius,
                intArrayOf(
                    alpha(palette.primary, 0.30f),
                    alpha(palette.primary, 0.12f),
                    alpha(palette.primary, 0f),
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(c, c, haloRadius, halo)
        canvas.drawCircle(c, c + 2f * density, dotR + ring, shadow(density))
        // Contrasting double ring: light casing with a dark hairline outside it.
        canvas.drawCircle(
            c,
            c,
            dotR + ring + 0.5f * density,
            stroke(alpha(0xFF000000.toInt(), 0.35f), 1f * density),
        )
        canvas.drawCircle(c, c, dotR + ring, fill(ring(palette)))
        canvas.drawCircle(c, c, dotR, fill(palette.primary))
        // 1 dp top-light highlight keeps the core from looking like a flat sticker.
        canvas.drawCircle(c, c, dotR - 0.5f * density, stroke(0x38FFFFFF, 1f * density))
        return MarkerBitmap(bmp, MarkerGeometry.CENTER, MarkerGeometry.CENTER)
    }

    /**
     * Bearing cone: a 28dp fan pointing up (= north before rotation), fading outwards.
     * The host rotates it by the reported bearing.
     */
    fun bearingCone(palette: MapPalette, density: Float): MarkerBitmap {
        val box = 72f
        val (bmp, canvas) = bitmap(box, box, density)
        val c = box / 2f * density
        val radius = 30f * density
        val path = Path().apply {
            moveTo(c, c)
            arcTo(
                RectF(c - radius, c - radius, c + radius, c + radius),
                -90f - 26f,
                52f,
                false,
            )
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                c,
                c,
                radius,
                intArrayOf(alpha(palette.primary, 0.42f), alpha(palette.primary, 0f)),
                floatArrayOf(0.15f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(path, paint)
        return MarkerBitmap(bmp, MarkerGeometry.CENTER, MarkerGeometry.CENTER)
    }

    /** Saved place: 12dp `tertiary` dot with a `surface` ring (spec §3, row 2). */
    fun favouriteDot(palette: MapPalette, density: Float): MarkerBitmap {
        val box = 28f
        val (bmp, canvas) = bitmap(box, box, density)
        val c = box / 2f * density
        val r = 6f * density
        canvas.drawCircle(c, c + 1f * density, r + 2f * density, shadow(density))
        canvas.drawCircle(c, c, r + 2f * density, fill(ring(palette)))
        canvas.drawCircle(c, c, r, fill(palette.tertiary))
        return MarkerBitmap(bmp, MarkerGeometry.CENTER, MarkerGeometry.CENTER)
    }

    /**
     * Selected saved place: the dot plus a `surfaceContainerHigh` label chip, so the name is
     * readable without opening a sheet (spec §3, row 2).
     */
    fun favouriteChip(palette: MapPalette, density: Float, label: String): MarkerBitmap {
        val heightDp = 40f
        val text = label.ifBlank { "★" }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.onSurface
            textSize = 12f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val maxTextWidth = 164f * density
        val textWidth = textPaint.measureText(text).coerceAtMost(maxTextWidth)
        val dotCenterDp = 9f
        val chipLeftDp = 21f
        val chipRightDp = chipLeftDp + 12f + 12f + textWidth / density + 12f
        val (bmp, canvas) = bitmap(chipRightDp, heightDp, density)
        val cy = heightDp / 2f * density

        val chip = RectF(
            chipLeftDp * density,
            cy - 15f * density,
            (chipRightDp - 1f) * density,
            cy + 15f * density,
        )
        canvas.drawRoundRect(chip, 15f * density, 15f * density, shadow(density))
        canvas.drawRoundRect(chip, 15f * density, 15f * density, fill(palette.surfaceContainerHigh))

        val dotX = dotCenterDp * density
        canvas.drawCircle(dotX, cy + 1f * density, 8f * density, shadow(density))
        canvas.drawCircle(dotX, cy, 8f * density, fill(ring(palette)))
        canvas.drawCircle(dotX, cy, 6f * density, fill(palette.tertiary))

        canvas.save()
        canvas.clipRect(RectF(
            (chipLeftDp + 6f) * density,
            cy - 14f * density,
            (chipRightDp - 12f) * density,
            cy + 14f * density,
        ))
        canvas.drawText(text, (chipLeftDp + 12f) * density, cy + 4f * density, textPaint)
        canvas.restore()
        return MarkerBitmap(bmp, dotX / bmp.width, MarkerGeometry.CENTER)
    }

    /**
     * Numbered waypoint or start/end badge: 24dp circle, `primaryContainer` / `errorContainer`,
     * 2.5dp casing (spec §3, rows 3–4).
     *
     * The sizes are chosen for legibility *over busy tiles*, which is where the old 22dp/11sp
     * badge failed: at street zoom it sat on top of POI labels and road shields and its 2dp ring
     * was thinner than the strokes underneath it, so 起 and 终 blurred into the map. A 24dp body
     * with a 2.5dp casing, 12sp bold text and a stronger drop shadow keeps the badge a badge.
     */
    fun badge(
        palette: MapPalette,
        density: Float,
        text: String,
        style: Badge,
    ): MarkerBitmap {
        val box = 32f
        val (bmp, canvas) = bitmap(box, box, density)
        val c = box / 2f * density
        val r = 12f * density
        val casing = 2.5f * density
        val container = if (style == Badge.END) palette.errorContainer else palette.primaryContainer
        val content = if (style == Badge.END) palette.onErrorContainer else palette.onPrimaryContainer
        canvas.drawCircle(c, c + 2f * density, r + casing, hardShadow(density))
        canvas.drawCircle(c, c, r + casing, fill(ring(palette)))
        canvas.drawCircle(c, c, r, fill(container))
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = content
            textSize = 12f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, c, c + 4.2f * density, textPaint)
        return MarkerBitmap(bmp, MarkerGeometry.CENTER, MarkerGeometry.CENTER)
    }

    /** The pin dropped by a tap / long-press: a teardrop anchored at its tip. */
    fun selectionPin(palette: MapPalette, density: Float): MarkerBitmap {
        val widthDp = 32f
        val heightDp = 44f
        val (bmp, canvas) = bitmap(widthDp, heightDp, density)
        val cx = widthDp / 2f * density
        val headCy = 15f * density
        val headR = 11f * density
        val tipY = heightDp * density - 2f * density

        val shadowPaint = shadow(density)
        val body = Path().apply {
            moveTo(cx, tipY)
            lineTo(cx - headR * 0.72f, headCy + headR * 0.62f)
            lineTo(cx + headR * 0.72f, headCy + headR * 0.62f)
            close()
            addCircle(cx, headCy, headR, Path.Direction.CW)
        }
        canvas.drawPath(body, shadowPaint)
        // 2dp surface casing so the pin stays readable over dark and light tiles alike.
        canvas.drawPath(body, stroke(ring(palette), 4f * density))
        canvas.drawPath(body, fill(palette.primary))
        canvas.drawPath(body, stroke(ring(palette), 2f * density))
        canvas.drawCircle(cx, headCy, headR * 0.38f, fill(ring(palette)))
        return MarkerBitmap(bmp, MarkerGeometry.CENTER, MarkerGeometry.BOTTOM)
    }
}

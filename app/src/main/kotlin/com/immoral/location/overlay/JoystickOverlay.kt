package com.immoral.location.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Floating joystick drawn over other apps (`SYSTEM_ALERT_WINDOW`).
 *
 * Interaction model — the same one users know from game-pad overlays:
 *  * collapsed: a translucent bubble; **drag** it anywhere on screen, **tap** to expand,
 *    **long-press** to close;
 *  * expanded: a dial with a knob; dragging reports a normalized vector to [onVector]
 *    (`x` right, `y` forward, each `-1..1`), releasing springs the knob back and calls
 *    [onRelease] so the engine stops moving.
 *
 * Everything is drawn with a plain [View] + [Canvas] instead of Compose: the overlay lives outside
 * any Activity, and a `ComposeView` there would need its own `LifecycleOwner` and `SavedStateRegistry`
 * for no benefit — the widget is three circles.
 */
class JoystickOverlay(
    context: Context,
    private val onVector: (Float, Float) -> Unit,
    private val onRelease: () -> Unit,
    private val onClose: () -> Unit,
) {

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = appContext.resources.displayMetrics.density
    private val view = JoystickView()

    private val params = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        gravity = Gravity.TOP or Gravity.START
        width = view.collapsedSizePx
        height = view.collapsedSizePx
        val metrics = appContext.resources.displayMetrics
        x = (metrics.widthPixels - width - view.marginPx).coerceAtLeast(0)
        y = (metrics.heightPixels * 6 / 10).coerceAtLeast(0)
    }

    var isShowing: Boolean = false
        private set

    fun show() {
        if (isShowing) return
        try {
            windowManager.addView(view, params)
            isShowing = true
        } catch (t: Throwable) {
            isShowing = false
        }
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        try {
            windowManager.removeView(view)
        } catch (t: Throwable) {
            // The window may already be gone (process death / permission revoked).
        }
    }

    fun destroy() {
        view.cancelAnimation()
        hide()
    }

    /** Updates the ring tint so the overlay matches the app's palette. */
    fun setAccentColor(color: Int) {
        view.accent = color
        view.invalidate()
    }

    /**
     * Latest device heading in degrees, or `null` when the sensor has no fix.
     *
     * Only used to rotate the tick ring: the pad always *emits* screen-aligned vectors (up = north
     * in the engine's convention), and when 跟随手机朝向 is on the engine rotates the travel
     * direction, so the ring has to show where north actually is.
     */
    fun setHeading(deg: Float?) {
        view.headingDeg = deg
        view.invalidate()
    }

    /** Mirrors `SimulationConfig.followHeading` so the pad can label what "up" means. */
    fun setFollowHeading(enabled: Boolean) {
        view.followHeading = enabled
        view.invalidate()
    }

    private inner class JoystickView : View(appContext) {

        val marginPx = (16 * density).toInt()
        val collapsedSizePx = (56 * density).toInt()

        /** 152dp per the design contract (spec §4). */
        private val expandedSizePx = (152 * density).toInt()

        var accent: Int = Color.parseColor("#6750A4")

        /** Latest device heading, see [setHeading]. */
        var headingDeg: Float? = null

        /** Mirrors the app setting; only changes the ring rotation and the caption. */
        var followHeading: Boolean = false

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC1C1B1F")
            style = Paint.Style.FILL
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#33FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
        }
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.FILL
        }
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CCFFFFFF")
            textSize = 10f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        private var expanded = false
        private var knobDx = 0f
        private var knobDy = 0f

        private var downRawX = 0f
        private var downRawY = 0f
        private var startWindowX = 0
        private var startWindowY = 0
        private var dragged = false
        private var touchSlop = 8 * density
        private var closePosted = false

        private val springBack = object : Runnable {
            override fun run() {
                knobDx *= 0.72f
                knobDy *= 0.72f
                if (hypot(knobDx, knobDy) < 1f) {
                    knobDx = 0f
                    knobDy = 0f
                } else {
                    postOnAnimation(this)
                }
                invalidate()
            }
        }

        fun cancelAnimation() = removeCallbacks(springBack)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            if (!expanded) {
                val r = min(width, height) / 2f - 3f * density
                canvas.drawCircle(cx, cy, r, fill)
                canvas.drawCircle(cx, cy, r, ring)
                val arm = r * 0.42f
                glyph.color = Color.WHITE
                canvas.drawLine(cx - arm, cy, cx + arm, cy, glyph)
                canvas.drawLine(cx, cy - arm, cx, cy + arm, glyph)
                canvas.drawCircle(cx, cy, r * 0.22f, knobPaint)
            } else {
                val r = min(width, height) / 2f - 6f * density
                canvas.drawCircle(cx, cy, r, fill)
                canvas.drawCircle(cx, cy, r, ring)

                // Tick ring + N marker. Rotated by -heading so "up" is honest when the engine is
                // following the phone heading (spec §4).
                canvas.save()
                if (followHeading) canvas.rotate(-(headingDeg ?: 0f), cx, cy)
                val tick = tickPaint
                for (i in 0 until 12) {
                    val rad = Math.toRadians((i * 30f - 90f).toDouble())
                    val major = i % 3 == 0
                    val rOuter = r - 4f * density
                    val rInner = rOuter - (if (major) 10f else 6f) * density
                    tick.color = Color.argb(if (major) 150 else 70, 255, 255, 255)
                    canvas.drawLine(
                        cx + (cos(rad) * rInner).toFloat(),
                        cy + (sin(rad) * rInner).toFloat(),
                        cx + (cos(rad) * rOuter).toFloat(),
                        cy + (sin(rad) * rOuter).toFloat(),
                        tick,
                    )
                }
                canvas.drawText("N", cx, cy - r + 20f * density, label)
                canvas.restore()

                val knobX = cx + knobDx
                val knobY = cy + knobDy
                if (hypot(knobDx, knobDy) > 2f) {
                    glyph.color = Color.argb(120, 255, 255, 255)
                    canvas.drawLine(cx, cy, knobX, knobY, glyph)
                }
                // 44dp thumb with a 2dp highlight ring (spec §4).
                val thumbR = 22f * density
                canvas.drawCircle(knobX, knobY, thumbR + 2f * density, inner)
                canvas.drawCircle(knobX, knobY, thumbR, knobPaint)
                canvas.drawText(
                    if (followHeading) "上=朝向" else "上=北",
                    cx,
                    cy + r * 0.64f,
                    label,
                )
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startWindowX = params.x
                    startWindowY = params.y
                    dragged = false
                    closePosted = false
                    if (!expanded) {
                        postDelayed(closeRunnable, 700L)
                    }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragged && hypot(dx, dy) > touchSlop) {
                        dragged = true
                        removeCallbacks(closeRunnable)
                    }
                    if (!expanded && dragged) {
                        params.x = startWindowX + dx.toInt()
                        params.y = startWindowY + dy.toInt()
                        clampToScreen()
                        safeUpdateLayout()
                    } else if (expanded) {
                        handleKnob(dx, dy)
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(closeRunnable)
                    if (expanded) {
                        knobDx = 0f
                        knobDy = 0f
                        invalidate()
                        onRelease()
                        if (!dragged) setExpanded(false)
                    } else if (!dragged) {
                        setExpanded(true)
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private val closeRunnable = Runnable {
            if (!dragged && !expanded) {
                closePosted = true
                onClose()
            }
        }

        private fun handleKnob(dx: Float, dy: Float) {
            val maxRadius = (min(width, height) / 2f) - 12f * density
            var ox = dx
            var oy = dy
            val dist = hypot(ox, oy)
            if (dist > maxRadius) {
                val scale = maxRadius / dist
                ox *= scale
                oy *= scale
            }
            knobDx = ox
            knobDy = oy
            invalidate()
            val nx = (ox / maxRadius).coerceIn(-1f, 1f)
            val ny = -(oy / maxRadius).coerceIn(-1f, 1f)
            if (hypot(nx, ny) < DEAD_ZONE) {
                onVector(0f, 0f)
            } else {
                onVector(nx, ny)
            }
        }

        fun setExpanded(value: Boolean) {
            if (expanded == value) return
            expanded = value
            knobDx = 0f
            knobDy = 0f
            val size = if (value) expandedSizePx else collapsedSizePx
            // Keep the bubble centred while it grows.
            params.x -= (size - params.width) / 2
            params.y -= (size - params.height) / 2
            params.width = size
            params.height = size
            clampToScreen()
            safeUpdateLayout()
            requestLayout()
            invalidate()
            if (!value) onRelease()
        }

        /** Keeps the window fully on screen after growing, or after being dragged to an edge. */
        private fun clampToScreen() {
            val metrics = appContext.resources.displayMetrics
            val maxX = (metrics.widthPixels - params.width).coerceAtLeast(0)
            val maxY = (metrics.heightPixels - params.height).coerceAtLeast(0)
            // Never let the floating pad drift into the top 30 % of the screen: that strip is
            // where every app (including ours) anchors its snackbars and banners.
            val minY = (metrics.heightPixels * TOP_KEEP_OUT).toInt().coerceAtMost(maxY)
            params.x = params.x.coerceIn(0, maxX)
            params.y = params.y.coerceIn(minY, maxY)
        }

        private fun safeUpdateLayout() {
            try {
                windowManager.updateViewLayout(this, params)
            } catch (t: Throwable) {
                // ignore
            }
        }

        private var animation: android.animation.ValueAnimator? = null

        @Suppress("unused")
        private fun animateIn() {
            animation?.cancel()
            animation = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 160L
                interpolator = OvershootInterpolator(0.9f)
                addUpdateListener { invalidate() }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(springBack)
            removeCallbacks(closeRunnable)
            animation?.cancel()
            super.onDetachedFromWindow()
        }
    }

    private companion object {
        const val DEAD_ZONE = 0.14f

        /** Fraction of the screen height the floating pad must stay below. */
        const val TOP_KEEP_OUT = 0.30f
    }
}

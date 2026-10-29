package com.immoral.location.ui.map

import kotlin.math.hypot

/**
 * Pure geometry shared by the Compose joystick and the native marker painters.
 *
 * Everything here is side-effect free and Android-free on purpose: these are the numbers that
 * regress silently when somebody "just tweaks the drawing" — which way `+y` points, how far the
 * tick ring rotates, where a pin is anchored — so they are unit-tested instead of eyeballed.
 */
object MarkerGeometry {

    /** Anchor of everything that should be centred on its point (dots, cones, badges). */
    const val CENTER: Float = 0.5f

    /** Anchor of the teardrop pin: its tip sits on the point. */
    const val BOTTOM: Float = 1.0f

    /**
     * Below this normalized magnitude the pad reports "not moving".
     *
     * Large enough that resting a thumb never drifts the simulation, small enough that a deliberate
     * nudge in any direction still moves it.
     */
    const val DEAD_ZONE: Float = 0.12f

    /**
     * Rotation in degrees applied to the pad's tick ring and `N` marker.
     *
     * The pad emits *screen* vectors (up = north) while the engine rotates the travel direction by
     * the phone heading when `followHeading` is on. To keep the picture honest, north has to be
     * drawn where it actually is: `-heading`. Input is wrapped into `[0, 360)` first, so a heading of
     * `350°` rotates the ring by `+10°` rather than `-350°`, and a negative or non-finite heading
     * never produces a NaN transform.
     */
    fun ringRotationDeg(followHeading: Boolean, headingDeg: Double?): Float {
        if (!followHeading) return 0f
        val raw = headingDeg?.takeIf { it.isFinite() } ?: 0.0
        val wrapped = ((raw % 360.0) + 360.0) % 360.0
        // Negate, then take the shortest equivalent rotation in (-180, 180]: a heading of 350°
        // draws north at +10°, not at -350°, which keeps the ring's motion sane if it is ever
        // animated and keeps the `N` marker at the expected side of the pad.
        var rotation = -wrapped
        if (rotation <= -180.0) rotation += 360.0
        return rotation.toFloat()
    }

    /**
     * Finger offset from the pad centre → the `(x, y)` vector `SimulationEngine.setJoystick` wants.
     *
     * `+y` is **up on screen**, which the engine interprets as north (`atan2(x, y)`, 0° = north,
     * +90° = east). Values inside [DEAD_ZONE] collapse to `(0, 0)` so a released thumb stops the
     * position instead of creeping.
     */
    fun normalizedVector(dx: Float, dy: Float, travel: Float): Pair<Float, Float> {
        if (travel <= 0f || !dx.isFinite() || !dy.isFinite()) return 0f to 0f
        val nx = (dx / travel).coerceIn(-1f, 1f)
        val ny = (-dy / travel).coerceIn(-1f, 1f)
        return if (hypot(nx, ny) < DEAD_ZONE) 0f to 0f else nx to ny
    }

    /** Anchor of a centred marker (current position, cone, favourite dot, numbered badge). */
    fun centeredAnchor(): Pair<Float, Float> = CENTER to CENTER

    /** Anchor of the selection pin: horizontal centre, vertical tip. */
    fun pinAnchor(): Pair<Float, Float> = CENTER to BOTTOM

    /**
     * Text drawn inside a route badge.
     *
     * Waypoints are numbered from 1 (`1..n`), start/end use the two label characters the design
     * contract specifies.
     */
    fun badgeText(kind: BadgeKind, waypointIndex: Int): String = when (kind) {
        BadgeKind.START -> START_LABEL
        BadgeKind.END -> END_LABEL
        BadgeKind.WAYPOINT -> (waypointIndex + 1).toString()
    }

    /** Which container colour / label a route badge gets. */
    enum class BadgeKind { START, WAYPOINT, END }

    const val START_LABEL: String = "起"
    const val END_LABEL: String = "终"
}

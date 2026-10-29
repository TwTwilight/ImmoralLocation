package com.immoral.location.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout tokens of the map surface.
 *
 * The design contract fixes almost every number (56dp search pill, 152dp joystick, 28dp sheet
 * top …); keeping them here instead of literal `dp` calls keeps the four parallel work faces
 * (map / route / hook / e2e) from drifting apart, and lets the e2e overlap assertions read the
 * same constants.
 */
object Dimens {
    /** Horizontal chrome margin used by the search pill, FABs and cards. */
    val chromeMargin: Dp = 16.dp

    /** Height of the floating search pill. */
    val searchBarHeight: Dp = 56.dp

    /** Vertical gap between two pieces of floating chrome. */
    val chromeGap: Dp = 8.dp

    /** Gap between the sheet's top edge and anything floating directly above it. */
    val aboveSheet: Dp = 16.dp

    /** Joystick pad diameter (spec §4). */
    val joystick: Dp = 152.dp

    /** Joystick thumb diameter. */
    val joystickThumb: Dp = 44.dp

    /** Padding inside bottom-sheet panels. */
    val sheetPadding: Dp = 20.dp

    /** Corner radius of floating cards (place card, status card). */
    val cardRadius: Dp = 20.dp

    /** A small floating button (layer / locate / joystick toggle). */
    val fabSize: Dp = 48.dp

    /** Height of one transport-mode cell in the peek row. */
    val transportCell: Dp = 44.dp

    /** The drag handle pill. */
    val handleWidth: Dp = 32.dp
    val handleHeight: Dp = 4.dp
}

/**
 * The single "floating on top of the map" surface style: `surfaceContainerLow` at 94 % with a
 * hairline `outlineVariant` stroke and a soft shadow — used by every card so they read as one
 * system instead of five unrelated backgrounds.
 */
@Composable
fun chromeContainer(): Color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f)

/**
 * Content colour of [chromeContainer].
 *
 * Needed explicitly: `chromeContainer` is an alpha variant of a role, so Material 3's
 * `contentColorFor()` cannot match it and would fall back to `LocalContentColor`. Any floating
 * card that hangs off the map must therefore state its own content colour, otherwise a `Text`
 * without an explicit colour is black — invisible in dark mode.
 */
@Composable
fun chromeContentColor(): Color = MaterialTheme.colorScheme.onSurface

/** Border colour of floating chrome (deliberately faint: tonal separation does the work). */
@Composable
fun chromeBorder(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)

/** Shape shared by every floating card on the map. */
val CardShape = RoundedCornerShape(Dimens.cardRadius)

/** Shape of the pill search bar and the speed chips. */
val PillShape = RoundedCornerShape(percent = 50)

/** Shape of the bottom sheet (28dp top corners per the contract). */
val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** Shape of a FAB (16dp). */
val FabShape = RoundedCornerShape(16.dp)

/** Default M3 elevation steps used for the chrome. */
object Elevation {
    val search: Dp = 3.dp
    val fab: Dp = 6.dp
    val card: Dp = 6.dp
    val joystick: Dp = 8.dp
}

/** Convenience so callers do not import FloatingActionButtonDefaults everywhere. */
@Composable
fun fabElevation(): androidx.compose.material3.FloatingActionButtonElevation =
    FloatingActionButtonDefaults.elevation(
        defaultElevation = Elevation.fab,
        pressedElevation = Elevation.fab,
        focusedElevation = Elevation.fab,
        hoveredElevation = Elevation.fab,
    )

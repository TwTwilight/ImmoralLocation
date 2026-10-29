package com.immoral.location.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Material You theme.
 *
 * Colours are Monet-generated on Android 12+ and fall back to the curated [SeededLight] /
 * [SeededDark] schemes below (seed `#3B5BA5`) on older devices, so the product keeps the same
 * identity everywhere — a slightly desaturated blue-grey primary with a teal tertiary for the
 * "saved place" accents.
 *
 * Every surface role of the M3 tonal palette is declared, including the `surfaceContainer*`
 * ramp: the map chrome (search pill, FABs, sheet) is built from those roles instead of raw
 * white/black surfaces with borders, which is what makes it read as Material You rather than
 * as a Compose sample.
 */
private val SeededLight = lightColorScheme(
    primary = Color(0xFF3B5BA5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBE1FF),
    onPrimaryContainer = Color(0xFF00174B),
    inversePrimary = Color(0xFFB3C5FF),
    secondary = Color(0xFF565E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF00696E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF6FF6FE),
    onTertiaryContainer = Color(0xFF002022),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFEFBFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFEFBFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceTint = Color(0xFF3B5BA5),
    inverseSurface = Color(0xFF303034),
    inverseOnSurface = Color(0xFFF2F0F4),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFEFBFF),
    surfaceDim = Color(0xFFDBD9DD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F3F7),
    surfaceContainer = Color(0xFFEFEDF1),
    surfaceContainerHigh = Color(0xFFE9E7EC),
    surfaceContainerHighest = Color(0xFFE3E1E6),
)

private val SeededDark = darkColorScheme(
    primary = Color(0xFFB3C5FF),
    onPrimary = Color(0xFF002780),
    primaryContainer = Color(0xFF204095),
    onPrimaryContainer = Color(0xFFDBE1FF),
    inversePrimary = Color(0xFF3B5BA5),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFF4CD9E2),
    onTertiary = Color(0xFF00363A),
    tertiaryContainer = Color(0xFF004F53),
    onTertiaryContainer = Color(0xFF6FF6FE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1B1B1F),
    onBackground = Color(0xFFE4E2E6),
    surface = Color(0xFF1B1B1F),
    onSurface = Color(0xFFE4E2E6),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceTint = Color(0xFFB3C5FF),
    inverseSurface = Color(0xFFE4E2E6),
    inverseOnSurface = Color(0xFF303034),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF39383C),
    surfaceDim = Color(0xFF1B1B1F),
    surfaceContainerLowest = Color(0xFF0F0E12),
    surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
)

/**
 * M3 shape tokens: pills for the search bar / chips, 16dp for FABs, 28dp for the sheet top,
 * 20dp for floating cards and 8dp for chips — exactly the values in the design contract.
 */
private val AppShapes = Shapes(
    extraSmall = Shapes().extraSmall,
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

/** Roboto/M3 defaults; only the tracking of the small labels is tightened for tabular numbers. */
private val AppTypography = Typography()

/**
 * App theme.
 *
 * @param darkTheme follow the system (no in-app toggle: the system setting is the contract).
 * @param dynamicColor use Monet on Android 12+; `false` is used by screenshot tooling that needs
 *   deterministic colours on any device.
 */
@Composable
fun ImmoralLocationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> SeededDark
        else -> SeededLight
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = AppTypography,
    ) {
        // `LocalContentColor` defaults to `Color.Black`, and every `Surface` whose colour is not an
        // exact `colorScheme` role (the floating chrome uses `surfaceContainerLow` at 94 % alpha)
        // falls back to it instead of resolving an `on*` role. Without this provider, any `Text`
        // that does not spell out its colour inherits black — right in light mode, unreadable black
        // on black in dark mode. Providing `onBackground` makes the default follow the theme.
        CompositionLocalProvider(
            LocalContentColor provides colorScheme.onBackground,
            content = content,
        )
    }
}

/**
 * Tabular figures for anything that changes in place (coordinates, speeds, distances).
 *
 * Proportional digits make a live coordinate readout jitter horizontally several times per second;
 * `tnum` makes every digit the same advance width so the text stays put.
 */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

package com.immoral.location.ui

import com.immoral.location.ui.theme.Dimens
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the design tokens the acceptance matrix measures on a real device.
 *
 * These are not cosmetic: `extra` spacing on the search pill or a smaller pad changes what
 * `14c-token-geometry` reports, and a silent drift on either side turns a green round red much
 * later. Keeping the numbers asserted here means a token change is a deliberate, reviewed edit.
 */
class DesignTokenTest {

    private val eps = 0.01f

    @Test
    fun searchPillIsFiftySixDp() {
        // Spec §1: pill 56dp tall, 16dp from the screen edge.
        assertEquals(56f, Dimens.searchBarHeight.value, eps)
        assertEquals(16f, Dimens.chromeMargin.value, eps)
        assertEquals(8f, Dimens.chromeGap.value, eps)
    }

    @Test
    fun joystickIsOneHundredFiftyTwoDpWithAFortyFourDpThumb() {
        // Spec §4.
        assertEquals(152f, Dimens.joystick.value, eps)
        assertEquals(44f, Dimens.joystickThumb.value, eps)
    }

    @Test
    fun chromeMetricsFollowTheContract() {
        // Spec §2: FAB 16dp corners at 48dp, cards 20dp, sheet padding 20dp.
        assertEquals(48f, Dimens.fabSize.value, eps)
        assertEquals(20f, Dimens.cardRadius.value, eps)
        assertEquals(20f, Dimens.sheetPadding.value, eps)
        assertEquals(16f, Dimens.aboveSheet.value, eps)
        assertEquals(44f, Dimens.transportCell.value, eps)
        // The right-edge plate is one 48 dp button plus 8 dp of plate on each side. It must wrap its
        // content: the first capture of the audit round showed it stretched to the map width.
        assertEquals(64f, Dimens.fabGroupWidth.value, eps)
    }
}

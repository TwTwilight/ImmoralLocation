package com.immoral.location.ui.components

import android.os.SystemClock
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult

/**
 * Single funnel for transient feedback.
 *
 * Two behaviours matter here and neither is available from `SnackbarHostState` on its own:
 *  * **de-duplication** — a failing command fires the same message several times per second from
 *    the bridge watchdog; without this the queue would take minutes to drain;
 *  * **replacement** — a new message dismisses the current one instead of queueing behind it, so
 *    the snackbar always shows what just happened.
 */
class MessageCenter {

    private var lastMessage: String? = null
    private var lastAtMs: Long = 0L

    suspend fun show(host: SnackbarHostState, message: String) {
        val now = SystemClock.uptimeMillis()
        if (message == lastMessage && now - lastAtMs < DEDUPE_WINDOW_MS) return
        lastMessage = message
        lastAtMs = now
        host.currentSnackbarData?.dismiss()
        host.showSnackbar(message = message, duration = SnackbarDuration.Short)
    }

    /** A snackbar with one action (撤销); [onAction] runs only when the user taps it. */
    suspend fun showAction(
        host: SnackbarHostState,
        message: String,
        actionLabel: String,
        onAction: () -> Unit,
    ) {
        lastMessage = message
        lastAtMs = SystemClock.uptimeMillis()
        host.currentSnackbarData?.dismiss()
        val result = host.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) onAction()
    }

    private companion object {
        /** Same wording is dropped inside this window (1.5 s). */
        const val DEDUPE_WINDOW_MS = 1_500L
    }
}

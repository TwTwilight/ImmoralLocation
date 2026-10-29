package com.immoral.location.ui.components

import android.os.SystemClock
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState

/**
 * Single funnel for transient feedback.
 *
 * Two behaviours matter here and neither is available from `SnackbarHostState` on its own:
 *  * **de-duplication** — a failing command fires the same message several times per second from
 *    the bridge watchdog; without this the queue would take minutes to drain (the old build did
 *    exactly that, and a stale "已切换位置" from a minute ago looked like a bug);
 *  * **replacement** — a new message dismisses the current one instead of queueing behind it, so
 *    the snackbar always shows what just happened.
 *
 * There is exactly one [SnackbarHostState] in the app and it is anchored top-centre under the
 * search bar, which is why nothing can be covered by it (spec §5).
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

    private companion object {
        /** Same wording is dropped inside this window (spec §5: 1.5 s). */
        const val DEDUPE_WINDOW_MS = 1_500L
    }
}

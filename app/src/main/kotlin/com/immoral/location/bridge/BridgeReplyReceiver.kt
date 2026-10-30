package com.immoral.location.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives `com.immoral.location.action.REPLY` broadcasts from the framework and forwards them to
 * the live [BridgeClient].
 *
 * The receiver is used twice:
 *  * registered **dynamically** by [BridgeClient] (exported, so a reply sent by `system_server`
 *    is delivered), which is the normal path while the UI is alive;
 *  * declared in the manifest so a reply that arrives while the activity is gone still finds its
 *    way into the running client instead of being dropped.
 *
 * It never assumes a client exists: without one the broadcast is simply ignored.
 */
class BridgeReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BridgeClient.dispatch(intent)
    }
}

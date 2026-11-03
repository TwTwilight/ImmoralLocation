// Framework -> app push channel. All methods are `oneway`: the framework iterates callbacks on
// its own thread and must never be stalled by a slow or dying client.
package com.immoral.location.ipc;

import com.immoral.location.ipc.WireState;

interface IImmoralStateCallback {
    /**
     * New engine snapshot.
     *
     * [seq] is strictly increasing for the lifetime of the framework process. Clients must keep
     * the newest snapshot by seq and ignore anything that arrives out of order — that is what
     * stops a coalesced older push from making the UI jump backwards.
     */
    oneway void onState(in WireState state, long seq);

    /** Outcome of a command that carried a [requestId]. */
    oneway void onResult(long requestId, int code, String message);

    /**
     * The engine (re)started, e.g. after a framework reload. Clients drop cached state.
     *
     * [reason] is one of the `WireRestart` constants.
     */
    oneway void onEngineRestarted(int reason);
}

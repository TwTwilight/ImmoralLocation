// Binder contract between the app (client) and the framework module living in system_server.
//
// The app obtains this interface through the HELLO handshake (see `Bridge` in :core), never
// through ServiceManager. Every mutating call is `oneway`: the caller must not wait for the
// engine, and the engine thread must never block on a client. Results that matter (a rejected
// route, an invalid point, an internal failure) come back asynchronously on
// IImmoralStateCallback#onResult with the requestId the caller supplied.
//
// Versioning: Parcelable layouts are positional and there is no field negotiation. `apiVersion()`
// is compared against `IPC_API_VERSION` by the client before anything else is called; a mismatch
// means the injected framework is a different build and the client must refuse to talk to it.
package com.immoral.location.ipc;

import com.immoral.location.ipc.IImmoralStateCallback;
import com.immoral.location.ipc.WireConfig;
import com.immoral.location.ipc.WireDiagnostics;
import com.immoral.location.ipc.WirePoint;
import com.immoral.location.ipc.WireRoute;
import com.immoral.location.ipc.WireState;

interface IImmoralEngine {
    /** Protocol version of the running framework build; see IPC_API_VERSION. */
    int apiVersion();

    /** Liveness probe. Answered by an empty transaction; no state is produced. */
    oneway void ping();

    /** Starts (or re-asserts) the engine. Idempotent. */
    oneway void start(long requestId);

    /**
     * Stops the engine and releases every movement source with it.
     *
     * This is a single transaction that *sets* the engine state to stopped: there is no separate
     * "release joystick" call, no edge-triggered channel, and therefore no way for a stale mirror
     * of the previous state to restart the engine behind the user's back.
     */
    oneway void stop(long requestId);

    /** Teleports the simulated position. */
    oneway void setPosition(in WirePoint point, long requestId);

    /** Replaces the whole simulation configuration (sanitized by the engine). */
    oneway void setConfig(in WireConfig config, long requestId);

    /** Joystick vector: x right (+1), y forward (+1), each in -1..1. */
    oneway void setJoystick(float x, float y);

    /** Centres the joystick without touching the running flag. */
    oneway void releaseJoystick();

    /** Device heading in degrees (rotation-vector azimuth). */
    oneway void setHeading(double deg);

    /** Loads [route] and plays it from the first point. */
    oneway void startRoute(in WireRoute route, long requestId);

    /** Resumes a paused route. */
    oneway void playRoute(long requestId);

    /** Pauses route playback, keeping the progress. */
    oneway void pauseRoute(long requestId);

    /** Unloads the route and returns to static movement. */
    oneway void stopRoute(long requestId);

    /** Overrides the step counter. */
    oneway void resetSteps(long steps, long requestId);

    /**
     * Current engine snapshot, read without touching the engine thread.
     *
     * Cheap enough to poll on reconnect; the callback below is the normal path.
     */
    WireState snapshot();

    /** Synthesized environment data (satellites, cells, APs, NMEA) for the diagnostics panel. */
    WireDiagnostics diagnostics();

    /**
     * Subscribes to state pushes.
     *
     * [minIntervalMs] is a floor, not a schedule: the framework coalesces ticks to at most one
     * push per interval and never queues more than one outstanding transaction per callback.
     */
    oneway void registerCallback(IImmoralStateCallback callback, int minIntervalMs);

    /** Drops a subscription. Safe to call with an unknown callback. */
    oneway void unregisterCallback(IImmoralStateCallback callback);
}

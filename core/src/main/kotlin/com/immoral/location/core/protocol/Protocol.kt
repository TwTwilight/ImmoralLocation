package com.immoral.location.core.protocol

/**
 * Constants of the app <-> framework binder bridge.
 *
 * The app is the client: it sends [Bridge.ACTION_HELLO] (a signature-permission broadcast,
 * package-scoped to this app by the framework) and receives [Bridge.ACTION_HELLO_REPLY] carrying
 * the engine binder in [Bridge.EXTRA_BINDER]. Nothing else travels over the broadcast bus; every
 * command and every state snapshot after that is a binder transaction on the interface declared
 * in `hook/src/main/aidl/com/immoral/location/ipc/IImmoralEngine.aidl`.
 *
 * The engine service is deliberately **not** registered with `ServiceManager`: a registered name
 * is enumerable by any app through `IServiceManager.listServices()`, and the whole point of the
 * out-of-band handshake is that the module leaves no entry in the service table.
 */
object Bridge {
    /** Package of the module / app APK (a single APK is both). */
    const val MODULE_PACKAGE: String = "io.github.twtwilight.immorallocation"

    /** Signature-level permission guarding the handshake broadcast in both directions. */
    const val PERMISSION_CONTROL: String = "io.github.twtwilight.immorallocation.permission.CONTROL"

    /** app -> framework: "give me the engine binder". */
    const val ACTION_HELLO: String = "com.immoral.location.action.HELLO"

    /** framework -> app: binder reply, package-scoped to [MODULE_PACKAGE]. */
    const val ACTION_HELLO_REPLY: String = "com.immoral.location.action.HELLO_REPLY"

    /** Extra carrying the `IBinder` in a handshake reply. */
    const val EXTRA_BINDER: String = "com.immoral.location.extra.BINDER"

    /** Extra echoing the handshake request id, so a stale reply can be dropped. */
    const val EXTRA_REQUEST_ID: String = "com.immoral.location.extra.REQUEST_ID"
}

/** Version of the engine implementation, reported through `IImmoralEngine.apiVersion()`. */
const val CORE_ENGINE_VERSION: String = "1.0.0"

/**
 * Version of the binder protocol itself.
 *
 * The framework layer is injected into `system_server` **once per boot**, so after an app update
 * the running framework may still be the previous build. The wire structs are `Parcelable`, whose
 * layout is positional: a mismatch would silently decode into garbage instead of failing. The app
 * therefore compares this value against `IImmoralEngine.apiVersion()` and reports "restart the
 * device" rather than trusting a different layout.
 */
const val IPC_API_VERSION: Int = 1

package com.immoral.location

import android.app.Application
import com.immoral.location.bridge.BridgeClient
import com.immoral.location.data.FavoritesStore
import com.immoral.location.data.PreviewEngine
import com.immoral.location.data.SettingsStore
import com.immoral.location.ui.map.OsmdroidSetup

/**
 * Process-wide singletons.
 *
 * The bridge client, the persisted settings and the favourites list must outlive individual
 * activities (the foreground service and the overlay joystick also use them), so they live here
 * instead of in a `ViewModel`.
 */
class App : Application() {

    lateinit var settings: SettingsStore
        private set

    lateinit var favorites: FavoritesStore
        private set

    lateinit var bridge: BridgeClient
        private set

    /** Display-only engine used for the satellites / cells / AP counts in the status panel. */
    val preview: PreviewEngine = PreviewEngine()

    override fun onCreate() {
        super.onCreate()
        OsmdroidSetup.initialize(this)
        settings = SettingsStore(this)
        favorites = FavoritesStore(this)
        bridge = BridgeClient(this) { settings.config.value }
        bridge.start()
        instance = this
    }

    companion object {
        @Volatile
        var instance: App? = null
            private set
    }
}

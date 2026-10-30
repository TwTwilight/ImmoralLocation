package com.immoral.location.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.immoral.location.App
import com.immoral.location.R
import com.immoral.location.bridge.ModuleLink
import com.immoral.location.overlay.JoystickOverlay
import com.immoral.location.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the simulation alive while the UI is backgrounded.
 *
 * It owns three things the UI cannot own:
 *  * the **heading sensor** (rotation vector → `SetHeading` at 5 Hz, required by 跟随手机朝向);
 *  * the **floating joystick overlay**, which must live in a long-running component so it
 *    survives the activity being stopped while the user is in 高德 / 百度 / 云运动;

 *
 * All engine mutations still travel through [com.immoral.location.bridge.BridgeClient] — the
 * service never simulates anything itself.
 */
class SimulationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    private var app: App? = null
    private var heading: HeadingSensorSource? = null
    private var overlay: JoystickOverlay? = null

    private var running = false
    private var lastNotificationAt = 0L
    private var lastNotificationRunning: Boolean? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        markRunning()
        val application = applicationContext as? App
        app = application
        if (application != null) {
            observeEngine(application)
        }
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            ACTION_START_ENGINE -> startEngine()
            ACTION_STOP_ENGINE -> stopEngineAndSelf()
            ACTION_SYNC_CONFIG -> syncConfig()
            ACTION_TOGGLE_OVERLAY -> toggleOverlay()
            ACTION_SET_JOYSTICK -> {
                val x = intent.getFloatExtra(EXTRA_X, 0f)
                val y = intent.getFloatExtra(EXTRA_Y, 0f)
                app?.bridge?.setJoystick(x, y)
            }

            ACTION_RELEASE_JOYSTICK -> app?.bridge?.releaseJoystick()
            ACTION_HIDE_OVERLAY -> hideOverlay()
            ACTION_STOP_SERVICE -> stopSelf()
            else -> Unit
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideOverlay()
        stopHeading()
        stateJob?.cancel()
        scope.cancel()
        _overlayRunning.value = false
        markStopped()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ engine

    private fun startEngine() {
        val application = app ?: return
        val config = application.settings.config.value
        // The caller (AppController.startSimulation / startRoute) already pushed the config right
        // before starting the service; pushing it again here raced with that write and could
        // re-publish a stale `mode`/`routeSpeedMps` on top of it.
        application.bridge.setConfig(config)
        application.bridge.startEngine()
        startHeading()
        updateNotification(force = true)
    }

    private fun stopEngineAndSelf() {
        val application = app
        application?.bridge?.stopEngine()
        application?.bridge?.releaseJoystick()
        hideOverlay()
        stopHeading()
        running = false
        updateNotification(force = true)
        stopSelf()
    }

    private fun syncConfig() {
        val application = app ?: return
        val config = application.settings.config.value
        application.bridge.setConfig(config)
        // Keep the floating pad's "up = north" / "up = heading" affordance in step with the app.
        overlay?.setFollowHeading(config.followHeading)
    }

    private fun observeEngine(application: App) {
        stateJob = scope.launch {
            application.bridge.engineState.collect { state ->
                running = state?.running == true
                if (running) startHeading() else stopHeading()
                updateNotification(force = false)
            }
        }
        scope.launch {
            application.bridge.link.collect { _ -> updateNotification(force = false) }
        }
    }

    // ----------------------------------------------------------------- heading

    private fun startHeading() {
        if (heading != null) return
        val application = app ?: return
        val sensor = HeadingSensorSource(this) { deg ->
            if (running) application.bridge.setHeading(deg)
            publishHeading(deg)
        }
        if (sensor.start()) {
            heading = sensor
        }
    }

    private fun stopHeading() {
        heading?.stop()
        heading = null
        // No live heading: the floating pad falls back to the plain north-up glyph instead of
        // keeping a stale needle pointing at where the phone pointed thirty seconds ago.
        overlay?.setHeading(null)
    }

    /** Latest device heading, in degrees; shared with the UI through [headingDegFlow]. */
    private fun publishHeading(deg: Double) {
        _headingDeg.value = deg
        overlay?.setHeading(deg.toFloat())
    }

    // ----------------------------------------------------------------- overlay

    private fun toggleOverlay() {
        if (overlay?.isShowing == true) hideOverlay() else showOverlay()
        updateNotification(force = true)
    }

    private fun showOverlay() {
        if (overlay?.isShowing == true) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission missing")
            _overlayRunning.value = false
            return
        }
        val application = app ?: return
        val joystick = overlay ?: JoystickOverlay(
            this,
            onVector = { x, y ->
                application.bridge.setJoystick(x, y)
                if (!running) {
                    // First touch of the floating stick implicitly starts the simulation, so the
                    // overlay is usable without returning to the app first.
                    running = true
                    application.bridge.startEngine()
                    startHeading()
                }
            },
            onRelease = { application.bridge.releaseJoystick() },
            onClose = {
                hideOverlay()
                updateNotification(force = true)
            },
        ).also { overlay = it }
        joystick.setFollowHeading(application.settings.config.value.followHeading)
        joystick.show()
        _overlayRunning.value = joystick.isShowing
    }

    private fun hideOverlay() {
        overlay?.destroy()
        overlay = null
        _overlayRunning.value = false
    }

    // ------------------------------------------------------------ notification

    private fun ensureForeground() {
        if (foregroundStarted) return
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed", t)
        }
    }

    private fun updateNotification(force: Boolean) {
        if (!foregroundStarted) return
        val now = System.currentTimeMillis()
        val runningChanged = lastNotificationRunning != running
        if (!force && !runningChanged && now - lastNotificationAt < NOTIFICATION_REFRESH_MS) return
        lastNotificationAt = now
        lastNotificationRunning = running
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed", t)
        }
    }

    private fun buildNotification(): Notification {
        val application = app
        val state = application?.bridge?.engineState?.value
        val point = state?.point
        val speed = state?.speedMps ?: 0.0
        val link = application?.bridge?.link?.value ?: ModuleLink.CHECKING
        val running = state?.running == true

        val title = if (running) {
            getString(R.string.notif_engine_title)
        } else {
            getString(R.string.notif_engine_stopped_title)
        }
        val text = if (point != null) {
            getString(R.string.notif_engine_text, point.lat, point.lon, "%.1f m/s".format(speed))
        } else {
            getString(R.string.notif_engine_idle_text)
        }
        val subtitle = when (link) {
            ModuleLink.ACTIVE -> getString(R.string.status_module_active)
            ModuleLink.INACTIVE -> getString(R.string.status_module_inactive)
            ModuleLink.CHECKING -> getString(R.string.status_connecting)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            pendingIntentFlags(),
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SimulationService::class.java).setAction(ACTION_STOP_ENGINE),
            pendingIntentFlags(),
        )
        val joystickIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, SimulationService::class.java).setAction(ACTION_TOGGLE_OVERLAY),
            pendingIntentFlags(),
        )

        return NotificationCompat.Builder(this, CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subtitle)
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .addAction(0, getString(R.string.notif_action_joystick), joystickIntent)
            .build()
    }

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val engineChannel = NotificationChannel(
            CHANNEL_ENGINE,
            getString(R.string.notif_channel_engine),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_engine_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(engineChannel)
    }

    companion object {
        private const val TAG = "SimulationService"

        const val CHANNEL_ENGINE = "engine"
        const val NOTIFICATION_ID = 0x1A0
        private const val NOTIFICATION_REFRESH_MS = 2_000L

        const val ACTION_START_ENGINE = "com.immoral.location.service.START_ENGINE"
        const val ACTION_STOP_ENGINE = "com.immoral.location.service.STOP_ENGINE"
        const val ACTION_SYNC_CONFIG = "com.immoral.location.service.SYNC_CONFIG"
        const val ACTION_TOGGLE_OVERLAY = "com.immoral.location.service.TOGGLE_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.immoral.location.service.HIDE_OVERLAY"
        const val ACTION_STOP_SERVICE = "com.immoral.location.service.STOP_SERVICE"
        const val ACTION_SET_JOYSTICK = "com.immoral.location.service.SET_JOYSTICK"
        const val ACTION_RELEASE_JOYSTICK = "com.immoral.location.service.RELEASE_JOYSTICK"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"

        private val _overlayRunning = MutableStateFlow(false)
        /** Observable overlay state, so the 摇杆 screen switch reflects reality. */
        val overlayRunning: StateFlow<Boolean> = _overlayRunning.asStateFlow()

        private val _headingDeg = MutableStateFlow<Double?>(null)
        /** Last device heading (degrees), or `null` before the sensor reported. */
        val headingDegFlow: StateFlow<Double?> = _headingDeg.asStateFlow()

        @Volatile
        private var serviceRunning: Boolean = false

        internal fun markRunning() {
            serviceRunning = true
        }

        internal fun markStopped() {
            serviceRunning = false
        }

        /** Starts the service (idempotent) and optionally applies [action]. */
        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, SimulationService::class.java)
            if (action != null) intent.action = action
            ContextCompat.startForegroundService(context, intent)
        }

        /** Fire-and-forget command to an already running service; a no-op when it is not up. */
        fun command(context: Context, action: String, x: Float = 0f, y: Float = 0f) {
            if (!serviceRunning) return
            val intent = Intent(context, SimulationService::class.java).setAction(action)
            if (action == ACTION_SET_JOYSTICK) {
                intent.putExtra(EXTRA_X, x)
                intent.putExtra(EXTRA_Y, y)
            }
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "startService failed", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, SimulationService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "stopService failed", t)
            }
        }
    }
}

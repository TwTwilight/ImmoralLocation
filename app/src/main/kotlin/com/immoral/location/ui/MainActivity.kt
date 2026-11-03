package com.immoral.location.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.immoral.location.App
import com.immoral.location.ui.theme.ImmoralLocationTheme

/**
 * The single activity of the app.
 *
 * Everything else is Compose; the activity only sets up edge-to-edge drawing and hands the
 * process-wide [App] singleton to [RootScreen].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash: the animated mark (drawable/splash_icon_animated), then a short lift-and-fade
        // into the first frame instead of a hard cut.
        val splash = installSplashScreen()
        splash.setOnExitAnimationListener { provider ->
            val view = provider.view
            val icon = runCatching { provider.iconView }.getOrNull()
            icon?.animate()
                ?.scaleX(1.12f)
                ?.scaleY(1.12f)
                ?.setDuration(SPLASH_EXIT_MS)
                ?.setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                ?.start()
            view.animate()
                .alpha(0f)
                .setDuration(SPLASH_EXIT_MS)
                .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
                .withEndAction { provider.remove() }
                .start()
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = applicationContext as App
        // e2e / 调试入口：`am start ... --es search 北京大学` 启动后立刻跑一次搜索。
        val initialSearch = intent?.getStringExtra(EXTRA_SEARCH)?.takeIf { it.isNotBlank() }
        setContent {
            // uiMode is handled in-process (no recreate), so the system-bar icon colours must follow
            // the theme from here; a stale light style left dark icons on a dark map after a switch.
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            androidx.compose.runtime.DisposableEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(transparent, transparent) { dark },
                    navigationBarStyle = SystemBarStyle.auto(transparent, transparent) { dark },
                )
                onDispose { }
            }
            ImmoralLocationTheme {
                RootScreen(app = app, initialSearch = initialSearch)
            }
        }
    }

    private companion object {
        const val EXTRA_SEARCH = "search"
        const val SPLASH_EXIT_MS = 260L
    }
}

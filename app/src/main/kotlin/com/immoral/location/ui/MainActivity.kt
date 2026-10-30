package com.immoral.location.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = applicationContext as App
        // e2e / 调试入口：`am start ... --es search 北京大学` 启动后立刻跑一次搜索。
        val initialSearch = intent?.getStringExtra(EXTRA_SEARCH)?.takeIf { it.isNotBlank() }
        setContent {
            ImmoralLocationTheme {
                RootScreen(app = app, initialSearch = initialSearch)
            }
        }
    }

    private companion object {
        const val EXTRA_SEARCH = "search"
    }
}

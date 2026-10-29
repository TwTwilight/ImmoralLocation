package com.immoral.location

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.immoral.location.ui.theme.ImmoralLocationTheme

class ProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ImmoralLocationTheme {
                Surface { Text("probe") }
            }
        }
    }
}

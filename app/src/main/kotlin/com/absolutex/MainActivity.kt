package com.absolutex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.absolutex.core.ui.AbsolutexTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AbsolutexTheme { Placeholder() } }
    }
}

// TODO(phase2): replaced by the SAF picker -> reader slice. Scaffold placeholder only.
@Composable
private fun Placeholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Absolutex")
    }
}

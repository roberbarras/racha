package io.github.roberbarras.racha.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.roberbarras.racha.ui.app.RachaApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var platform: AndroidPlatform

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash covers the first frame; LaunchAnimation continues it while data loads in background.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as RachaApplication).graph
        platform = AndroidPlatform(this)
        // Scanning and file watching run only while the app is visible: nothing stays awake in the background.
        lifecycleScope.launch {
            graph.ensureBuiltInCategories()
            repeatOnLifecycle(Lifecycle.State.STARTED) { graph.keepLibraryUpToDate.run() }
        }
        val dependencies = graph.dependencies(platform)
        val coldStart = savedInstanceState == null
        setContent { RachaApp(dependencies, launchAnimation = coldStart) }
    }

    override fun onResume() {
        super.onResume()
        platform.refresh()
    }
}

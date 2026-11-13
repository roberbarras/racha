package io.github.roberbarras.racha.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.roberbarras.racha.application.environment.DescribeEnvironment
import io.github.roberbarras.racha.ui.App
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as RachaApplication).graph
        // Scanning and file watching run only while the app is visible: nothing stays awake in the background.
        lifecycleScope.launch {
            graph.ensureBuiltInCategories()
            repeatOnLifecycle(Lifecycle.State.STARTED) { graph.keepLibraryUpToDate.run() }
        }
        val describeEnvironment = DescribeEnvironment { "Android ${Build.VERSION.RELEASE}" }
        setContent { App(greeting = describeEnvironment()) }
    }
}
